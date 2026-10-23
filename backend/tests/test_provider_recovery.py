"""Offline provider failure regressions: generated images, fake clients, no cloud config."""
import io
import random
import time
import uuid
from pathlib import Path
from types import SimpleNamespace

import pytest
import requests
from fastapi.testclient import TestClient
from PIL import Image

import backend.tryon_provider as provider_module
from backend.aliyun_tryon_smoke import Settings, build_client
from backend.cos_storage import BudgetSession, CosSettings, upload_tryon_input
from backend.provider_control import GenerationBudget, ProviderFailure, using_budget
from backend.provider_images import MAX_ENCODED_BYTES, prepare_input
from backend.service import create_app, decode_photo
from backend.tests.test_tryon_provider import (
    FakeAidge, FakeCos, FakeProvider, png, session_and_upload, wait_terminal,
)
from backend.tryon_provider import AidgeCosProvider, ProviderResult, download_result


class Clock:
    def __init__(self):
        self.now = 0.0

    def __call__(self):
        return self.now

    def sleep(self, seconds):
        self.now += seconds


@pytest.mark.parametrize("stage", ["put-response", "head", "presign", "invalid-url"])
def test_partial_upload_owns_and_deletes_key_before_any_response(tmp_path, stage):
    class BrokenCos(FakeCos):
        uploaded = None

        def put_object_from_local_file(self, **kwargs):
            self.uploaded = kwargs["Key"]
            if stage == "put-response":
                raise OSError("response lost after object was stored")
            return {}

        def head_object(self, **kwargs):
            if stage == "head":
                raise OSError("head failed")
            return {}

        def get_presigned_download_url(self, **kwargs):
            if stage == "presign":
                raise OSError("sign failed")
            return "http://invalid.example/object"

    client = BrokenCos()
    image = tmp_path / "model.png"; image.write_bytes(png())
    with pytest.raises((OSError, RuntimeError)):
        upload_tryon_input(client, CosSettings("test-id", "test-secret"), image, "model", unique_id="opaque-test")
    assert client.uploaded and client.deleted == [client.uploaded]


def test_failed_partial_cleanup_preserves_original_failure_and_logs_no_key(tmp_path, caplog):
    class BrokenCos(FakeCos):
        def head_object(self, **kwargs):
            raise OSError("original-head-error")

    client = BrokenCos(delete_failures=1)
    image = tmp_path / "model.png"; image.write_bytes(png())
    with pytest.raises(OSError, match="original-head-error"):
        upload_tryon_input(client, CosSettings("test-id", "test-secret"), image, "model", unique_id="not-for-logs")
    assert len(client.deleted) == 1
    assert "1 temporary COS input" in caplog.text
    assert client.deleted[0] not in caplog.text and "not-for-logs" not in caplog.text


@pytest.mark.parametrize("late_failure", [False, True])
@pytest.mark.parametrize("poll_before_finish", [False, True])
def test_public_deadline_is_terminal_even_without_a_poll_before_late_result(late_failure, poll_before_finish):
    clock = Clock()
    provider = FakeProvider(blocked=True, failure=ProviderFailure("OTHER_FAILURE", "late") if late_failure else None)
    with TestClient(create_app(provider=provider, catalog_profile="regression", monotonic=clock)) as client:
        headers, uid = session_and_upload(client)
        key = str(uuid.uuid4()); body = {"upload_id": uid, "garment_id": "upper_blue"}
        response = client.post("/api/v1/tryon-jobs", headers={**headers, "Idempotency-Key": key}, json=body)
        jid = response.json()["job_id"]
        assert provider.started.wait(1)
        try:
            clock.now = 120
            if poll_before_finish:
                expired = client.get(f"/api/v1/tryon-jobs/{jid}", headers=headers).json()
                assert expired["status"] == "failed" and expired["error"]["code"] == "GENERATION_TIMEOUT"
                busy = client.post("/api/v1/tryon-jobs", headers={**headers, "Idempotency-Key": str(uuid.uuid4())}, json=body)
                assert busy.status_code == 429 and busy.json()["error"]["code"] == "PROVIDER_BUSY"
        finally:
            provider.release.set()
        deadline = time.monotonic() + 3
        while client.app.state.store.provider_inflight and time.monotonic() < deadline:
            time.sleep(.01)
        assert not client.app.state.store.provider_inflight
        final = wait_terminal(client, headers, jid)
        assert final["status"] == "failed" and final["error"]["code"] == "GENERATION_TIMEOUT"
        assert final["result"] is None
        replay = client.post("/api/v1/tryon-jobs", headers={**headers, "Idempotency-Key": key}, json=body)
        assert replay.status_code == 200 and replay.json()["job_id"] == jid and provider.calls == 1
        # A fresh task is accepted only after the old upstream worker is done.
        fresh = client.post("/api/v1/tryon-jobs", headers={**headers, "Idempotency-Key": str(uuid.uuid4())}, json=body)
        assert fresh.status_code == 202


def test_calendar_clock_jump_does_not_shorten_real_generation_deadline():
    elapsed = Clock(); calendar = Clock(); calendar.now = 10_000
    provider = FakeProvider(blocked=True)
    with TestClient(create_app(provider=provider, catalog_profile="regression", clock=calendar, monotonic=elapsed)) as client:
        headers, uid = session_and_upload(client)
        response = client.post("/api/v1/tryon-jobs", headers={**headers, "Idempotency-Key": str(uuid.uuid4())},
                               json={"upload_id": uid, "garment_id": "upper_blue"})
        assert provider.started.wait(1)
        try:
            calendar.now += 500; elapsed.now = 119
            job = client.get(f"/api/v1/tryon-jobs/{response.json()['job_id']}", headers=headers).json()
            assert job["status"] == "running"
        finally:
            provider.release.set()
        assert wait_terminal(client, headers, response.json()["job_id"])["status"] == "succeeded"


def configuration(tmp_path):
    return Settings("test-id", "test-secret", "example", "region", "https://example.invalid/model.png",
                    "https://example.invalid/garment.png", "tops", "1K", tmp_path, tmp_path / "unused.json")


@pytest.mark.parametrize("stage", ["model-upload", "garment-upload", "submit", "query", "download"])
def test_one_budget_covers_every_provider_stage_and_discards_late_completion(tmp_path, monkeypatch, stage):
    clock = Clock(); uploads = []; submissions = []; queries = []; downloads = []

    class SlowCos(FakeCos):
        def put_object_from_local_file(self, **kwargs):
            uploads.append(kwargs["Key"])
            if (stage == "model-upload" and "/model/" in kwargs["Key"]) or (stage == "garment-upload" and "/garment/" in kwargs["Key"]):
                clock.now += 121
            return {}

    class SlowAidge(FakeAidge):
        def virtual_try_on_with_options(self, request, runtime):
            submissions.append(runtime)
            if stage == "submit":
                clock.now += 121
            return super().virtual_try_on_with_options(request, runtime)

        def query_async_task_result_with_options(self, request, runtime):
            queries.append(runtime)
            if stage == "query":
                clock.now += 121
            return super().query_async_task_result_with_options(request, runtime)

    def download(url, *, budget):
        downloads.append(url)
        if stage == "download":
            clock.now += 121
        return ProviderResult(png(), 32, 48)

    cos = SlowCos()
    provider = AidgeCosProvider(CosSettings("test-id", "test-secret"), configuration(tmp_path), cos_client=cos, aidge_client=SlowAidge())
    monkeypatch.setattr(provider_module, "time", SimpleNamespace(monotonic=clock, sleep=clock.sleep))
    monkeypatch.setattr(provider_module, "download_result", download)
    garment = tmp_path / "garment.png"; garment.write_bytes(png((600, 800)))
    with pytest.raises(ProviderFailure) as failure:
        provider.generate(png((320, 480)), garment, "tops", budget=GenerationBudget(120, clock=clock))
    assert failure.value.code == "GENERATION_TIMEOUT"
    assert sorted(cos.deleted) == sorted(uploads)
    assert len(submissions) == (0 if "upload" in stage else 1)
    assert len(queries) == (1 if stage in {"query", "download"} else 0)
    assert len(downloads) == (1 if stage == "download" else 0)
    for runtime in submissions + queries:
        assert 0 < runtime.connect_timeout <= 10_000 and 0 < runtime.read_timeout <= 10_000
        assert runtime.autoretry is False and runtime.max_attempts == 1


def test_cos_transport_receives_remaining_budget_and_scope_is_restored(monkeypatch):
    observed = []
    monkeypatch.setattr(requests.Session, "request", lambda self, method, url, **kwargs: observed.append(kwargs["timeout"]) or requests.Response())
    clock = Clock(); budget = GenerationBudget(2, clock=clock)
    with BudgetSession() as session:
        with using_budget(budget):
            session.get("https://never-requested.invalid")
        session.get("https://never-requested.invalid")
    assert observed == [(1.0, 1.0), (10.0, 10.0)]


def test_actual_aidge_sdk_receives_runtime_without_automatic_paid_retry(tmp_path, monkeypatch):
    client = build_client(configuration(tmp_path))
    calls = []
    def call_api(params, request, runtime):
        calls.append((params.action, runtime))
        return {"body": {"Success": True, "Data": {"TaskId": "fake"}}}
    monkeypatch.setattr(client, "call_api", call_api)
    runtime = AidgeCosProvider.runtime(GenerationBudget(2))
    from backend.aliyun_tryon_smoke import build_request
    result = client.virtual_try_on_with_options(build_request(configuration(tmp_path)), runtime)
    assert result.body.data.task_id == "fake"
    assert len(calls) == 1 and calls[0][0] == "VirtualTryOn"
    assert calls[0][1].connect_timeout + calls[0][1].read_timeout <= 2000
    assert client._retry_options.retryable is False


@pytest.mark.parametrize("size", [(4096, 1024), (1024, 4096), (600, 800)])
def test_input_is_proportionally_bounded_without_changing_original_bytes(tmp_path, size):
    original = png(size); target = tmp_path / "prepared.jpg"
    prepare_input(original, target, "人物", GenerationBudget(10))
    with Image.open(target) as result:
        assert result.format == "JPEG" and 256 <= min(result.size) <= max(result.size) <= 2049
        assert abs(result.width / result.height - size[0] / size[1]) < .01
    assert original == png(size) and target.stat().st_size <= MAX_ENCODED_BYTES


def test_normalized_png_above_provider_limit_is_reencoded_before_upload(tmp_path):
    raw = random.Random(7).randbytes(2049 * 2049 * 3)
    with Image.frombytes("RGB", (2049, 2049), raw) as source:
        jpeg = io.BytesIO(); source.save(jpeg, "JPEG", quality=90)
    assert len(jpeg.getvalue()) < 10 * 1024 * 1024
    uploaded = decode_photo(jpeg.getvalue(), "image/jpeg")
    assert len(uploaded.data) > MAX_ENCODED_BYTES
    target = tmp_path / "bounded.jpg"
    prepare_input(uploaded.data, target, "人物", GenerationBudget(15))
    assert target.stat().st_size <= MAX_ENCODED_BYTES


def test_tiny_garment_is_rejected_before_either_image_leaves_process(tmp_path):
    class NoTransmissionCos(FakeCos):
        def put_object_from_local_file(self, **kwargs):
            pytest.fail("No image may be uploaded when either input is invalid")
    provider = AidgeCosProvider(CosSettings("test-id", "test-secret"), configuration(tmp_path),
                                cos_client=NoTransmissionCos(), aidge_client=FakeAidge())
    garment = tmp_path / "tiny.png"; garment.write_bytes(png())
    with pytest.raises(ProviderFailure) as failure:
        provider.generate(png((320, 480)), garment, "tops")
    assert failure.value.code == "PROVIDER_INVALID_INPUT"


@pytest.mark.parametrize("size", [(32, 48), (16000, 256)])
def test_unusable_input_size_is_rejected_locally(tmp_path, size):
    target = tmp_path / "rejected.jpg"
    with pytest.raises(ProviderFailure) as failure:
        prepare_input(png(size), target, "人物", GenerationBudget(10))
    assert failure.value.code == "PROVIDER_INVALID_INPUT" and not target.exists()


def test_transparent_garment_background_becomes_white_and_metadata_is_removed(tmp_path):
    raw = io.BytesIO()
    with Image.new("RGBA", (320, 480), (0, 0, 0, 0)) as source:
        source.save(raw, "PNG")
    target = tmp_path / "garment.jpg"
    prepare_input(raw.getvalue(), target, "服装", GenerationBudget(10))
    with Image.open(target) as image:
        assert image.getpixel((0, 0)) == (255, 255, 255) and not image.getexif()


def test_result_stream_cannot_return_a_late_success(monkeypatch):
    clock = Clock()
    class SlowStream(io.BytesIO):
        def read1(self, size):
            clock.now += 121
            return super().read1(size)
    monkeypatch.setattr(provider_module.urllib.request, "urlopen", lambda *args, **kwargs: SlowStream(png()))
    with pytest.raises(ProviderFailure) as failure:
        download_result("https://example.invalid/result.png", budget=GenerationBudget(120, clock=clock))
    assert failure.value.code == "GENERATION_TIMEOUT"
