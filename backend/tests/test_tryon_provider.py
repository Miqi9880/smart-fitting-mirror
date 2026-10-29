import io
import json
import runpy
import sys
import threading
import time
import uuid
from pathlib import Path
from types import SimpleNamespace

from fastapi.testclient import TestClient
from PIL import Image

from backend.aliyun_tryon_smoke import Settings as AidgeSettings
from backend.cos_storage import CosSettings
from backend.service import create_app
from backend.tryon_provider import AidgeCosProvider, ProviderFailure, ProviderResult


def png(size=(32, 48), color="#345678") -> bytes:
    output = io.BytesIO()
    with Image.new("RGB", size, color) as image:
        image.save(output, "PNG")
    return output.getvalue()


def session_and_upload(client: TestClient):
    session = client.post("/api/v1/sessions").json()
    headers = {"Authorization": "Bearer " + session["session_token"]}
    upload = client.post(
        "/api/v1/uploads", headers=headers, files={"file": ("model.png", png(), "image/png")}
    ).json()
    return headers, upload["upload_id"]


def wait_terminal(client: TestClient, headers: dict[str, str], job_id: str) -> dict:
    deadline = time.monotonic() + 3
    while time.monotonic() < deadline:
        value = client.get(f"/api/v1/tryon-jobs/{job_id}", headers=headers).json()
        if value["status"] in {"succeeded", "failed", "cancelled"}:
            return value
        time.sleep(0.01)
    raise AssertionError("provider job did not become terminal")


class FakeProvider:
    name = "fake-real"

    def __init__(self, *, failure: ProviderFailure | None = None, blocked: bool = False):
        self.failure = failure
        self.calls = 0
        self.started = threading.Event()
        self.release = threading.Event()
        if not blocked:
            self.release.set()

    def generate(self, model_image: bytes, garment_image: Path, cloth_type: str, *, budget=None) -> ProviderResult:
        self.calls += 1
        assert model_image.startswith(b"\x89PNG")
        assert garment_image.is_file()
        assert cloth_type == "tops"
        self.started.set()
        assert self.release.wait(3)
        if self.failure:
            raise self.failure
        return ProviderResult(png((40, 60), "#112233"), 40, 60)


def test_real_provider_uses_existing_contract_and_idempotency():
    provider = FakeProvider()
    with TestClient(create_app(provider=provider, catalog_profile="regression")) as client:
        assert client.get("/health").json() == {"status": "ok", "mode": "fake-real", "is_mock": False}
        headers, upload_id = session_and_upload(client)
        key = str(uuid.uuid4())
        body = {"upload_id": upload_id, "garment_id": "upper_blue"}
        first = client.post("/api/v1/tryon-jobs", headers={**headers, "Idempotency-Key": key}, json=body)
        replay = client.post("/api/v1/tryon-jobs", headers={**headers, "Idempotency-Key": key}, json=body)
        assert first.status_code == 202 and replay.status_code == 200
        assert first.json()["job_id"] == replay.json()["job_id"]
        done = wait_terminal(client, headers, first.json()["job_id"])
        assert done["status"] == "succeeded" and done["is_mock"] is False
        assert done["result"]["width"] == 40 and done["result"]["height"] == 60
        result = client.get(done["result"]["image_url"], headers=headers)
        assert result.status_code == 200 and result.content.startswith(b"\x89PNG")
        assert provider.calls == 1


def test_real_provider_disabled_blocks_retries_before_creating_a_job_and_mock_is_independent():
    disabled_app = create_app(provider_disabled=True, catalog_profile="regression")
    with TestClient(disabled_app) as client:
        assert client.get("/health").json() == {
            "status": "ok", "mode": "provider-disabled", "is_mock": False
        }
        headers, upload_id = session_and_upload(client)
        key = str(uuid.uuid4())
        request_headers = {**headers, "Idempotency-Key": key}
        body = {"upload_id": upload_id, "garment_id": "upper_blue"}

        first = client.post("/api/v1/tryon-jobs", headers=request_headers, json=body)
        replay = client.post("/api/v1/tryon-jobs", headers=request_headers, json=body)

        for response in (first, replay):
            assert response.status_code == 503
            assert response.json()["error"]["code"] == "PROVIDER_UNAVAILABLE"
            assert "本地预览仍可使用" in response.json()["error"]["message"]
        store = disabled_app.state.store
        assert store.provider is None and store.executor is None
        assert not store.guests[next(iter(store.guests))].jobs
        assert not store.guests[next(iter(store.guests))].keys

    with TestClient(create_app(catalog_profile="regression")) as client:
        headers, upload_id = session_and_upload(client)
        response = client.post(
            "/api/v1/tryon-jobs",
            headers={**headers, "Idempotency-Key": str(uuid.uuid4())},
            json={"upload_id": upload_id, "garment_id": "upper_blue"},
        )
        assert response.status_code == 202
        result = wait_terminal(client, headers, response.json()["job_id"])
        assert result["status"] == "succeeded" and result["is_mock"] is True


def test_cli_real_provider_selection_does_not_read_env_without_enable_flag(monkeypatch, tmp_path):
    import uvicorn

    captured = {}
    monkeypatch.setattr(uvicorn, "run", lambda app, **kwargs: captured.setdefault("app", app))
    monkeypatch.setattr(sys, "argv", [
        "backend", "--provider", "aidge-cos",
        "--cos-env-file", str(tmp_path / "missing.cos"),
        "--aliyun-env-file", str(tmp_path / "missing.aliyun"),
        "--catalog-db", str(tmp_path / "catalog.sqlite3"),
    ])

    runpy.run_module("backend.__main__", run_name="__main__")

    app = captured["app"]
    assert app.state.store.provider_disabled is True
    assert app.state.store.provider is None and app.state.store.executor is None


def test_cancel_wins_and_late_provider_result_is_discarded():
    provider = FakeProvider(blocked=True)
    with TestClient(create_app(provider=provider, catalog_profile="regression")) as client:
        headers, upload_id = session_and_upload(client)
        response = client.post(
            "/api/v1/tryon-jobs",
            headers={**headers, "Idempotency-Key": str(uuid.uuid4())},
            json={"upload_id": upload_id, "garment_id": "upper_blue"},
        )
        job_id = response.json()["job_id"]
        assert provider.started.wait(1)
        cancelled = client.post(f"/api/v1/tryon-jobs/{job_id}/cancel", headers=headers).json()
        assert cancelled["status"] == "cancelled"
        busy = client.post(
            "/api/v1/tryon-jobs",
            headers={**headers, "Idempotency-Key": str(uuid.uuid4())},
            json={"upload_id": upload_id, "garment_id": "upper_blue"},
        )
        assert busy.status_code == 429 and busy.json()["error"]["code"] == "PROVIDER_BUSY"
        provider.release.set()
        time.sleep(0.05)
        final = client.get(f"/api/v1/tryon-jobs/{job_id}", headers=headers).json()
        assert final["status"] == "cancelled" and final["result"] is None


def test_real_provider_allows_idempotent_replay_but_rejects_second_active_job():
    provider = FakeProvider(blocked=True)
    with TestClient(create_app(provider=provider, catalog_profile="regression")) as client:
        headers, upload_id = session_and_upload(client)
        key = str(uuid.uuid4())
        body = {"upload_id": upload_id, "garment_id": "upper_blue"}
        first = client.post("/api/v1/tryon-jobs", headers={**headers, "Idempotency-Key": key}, json=body)
        assert first.status_code == 202 and provider.started.wait(1)
        replay = client.post("/api/v1/tryon-jobs", headers={**headers, "Idempotency-Key": key}, json=body)
        assert replay.status_code == 200 and replay.json()["job_id"] == first.json()["job_id"]
        second = client.post(
            "/api/v1/tryon-jobs",
            headers={**headers, "Idempotency-Key": str(uuid.uuid4())},
            json=body,
        )
        assert second.status_code == 429
        assert second.json()["error"]["code"] == "PROVIDER_BUSY"
        assert second.headers["retry-after"] == "5"
        provider.release.set()
        assert wait_terminal(client, headers, first.json()["job_id"])["status"] == "succeeded"


def test_provider_failure_is_sanitized_for_client():
    provider = FakeProvider(failure=ProviderFailure("PROVIDER_FAILED", "生成失败，请重试"))
    with TestClient(create_app(provider=provider, catalog_profile="regression")) as client:
        headers, upload_id = session_and_upload(client)
        response = client.post(
            "/api/v1/tryon-jobs",
            headers={**headers, "Idempotency-Key": str(uuid.uuid4())},
            json={"upload_id": upload_id, "garment_id": "upper_blue"},
        )
        done = wait_terminal(client, headers, response.json()["job_id"])
        assert done["status"] == "failed"
        assert done["error"]["code"] == "PROVIDER_FAILED"
        assert done["error"]["message"] == "生成失败，请重试"


class FakeCos:
    def __init__(self, delete_failures=0):
        self.deleted = []
        self.delete_failures = delete_failures

    def put_object_from_local_file(self, **kwargs):
        return {"ETag": "test"}

    def head_object(self, **kwargs):
        return {}

    def get_presigned_download_url(self, **kwargs):
        return "https://cos.example/temporary.png?signature=hidden"

    def delete_object(self, **kwargs):
        self.deleted.append(kwargs["Key"])
        if self.delete_failures:
            self.delete_failures -= 1
            raise OSError("simulated cleanup failure")
        return {}


class FakeAidge:
    def __init__(self, query_failures=0):
        self.query_failures = query_failures
        self.submit_calls = 0
        self.query_calls = 0

    def virtual_try_on_with_options(self, request, runtime):
        self.submit_calls += 1
        return SimpleNamespace(body=SimpleNamespace(success=True, data=SimpleNamespace(task_id="task-one")))

    def query_async_task_result_with_options(self, request, runtime):
        self.query_calls += 1
        if self.query_failures:
            self.query_failures -= 1
            raise OSError("temporary query failure")
        result = json.dumps({"url": "https://aidge.example/result.png"})
        return SimpleNamespace(body=SimpleNamespace(success=True, data=SimpleNamespace(status="COMPLETED", result=result)))


def test_aidge_cos_provider_cleans_both_inputs(monkeypatch, tmp_path):
    garment = tmp_path / "garment.png"
    garment.write_bytes(png((320, 480)))
    cos = FakeCos()
    aidge_settings = AidgeSettings(
        access_key_id="id", access_key_secret="secret", endpoint="example", region="region",
        model_image_url="https://placeholder.invalid/model.png",
        garment_image_url="https://placeholder.invalid/garment.png", cloth_type="tops", resolution="1K",
        output_dir=tmp_path, state_file=tmp_path / "state.json",
    )
    provider = AidgeCosProvider(
        CosSettings(secret_id="id", secret_key="secret"), aidge_settings,
        poll_interval=1, max_wait_seconds=3, cos_client=cos, aidge_client=FakeAidge(),
    )
    monkeypatch.setattr("backend.tryon_provider.time.sleep", lambda _: None)
    monkeypatch.setattr("backend.tryon_provider.download_result", lambda _, **kwargs: ProviderResult(png(), 32, 48))
    result = provider.generate(png((320, 480)), garment, "tops")
    assert (result.width, result.height) == (32, 48)
    assert len(cos.deleted) == 2
    assert all(key.startswith("tryon-input/") for key in cos.deleted)


def test_aidge_query_retry_keeps_inputs_until_success(monkeypatch, tmp_path):
    garment = tmp_path / "garment.png"
    garment.write_bytes(png((320, 480)))
    cos = FakeCos()
    aidge_settings = AidgeSettings(
        access_key_id="id", access_key_secret="secret", endpoint="example", region="region",
        model_image_url="https://placeholder.invalid/model.png",
        garment_image_url="https://placeholder.invalid/garment.png", cloth_type="tops", resolution="1K",
        output_dir=tmp_path, state_file=tmp_path / "state.json",
    )
    provider = AidgeCosProvider(
        CosSettings(secret_id="id", secret_key="secret"), aidge_settings,
        poll_interval=1, max_wait_seconds=3, cos_client=cos, aidge_client=FakeAidge(query_failures=1),
    )
    monkeypatch.setattr("backend.tryon_provider.time.sleep", lambda _: None)
    monkeypatch.setattr("backend.tryon_provider.download_result", lambda _, **kwargs: ProviderResult(png(), 32, 48))
    assert provider.generate(png((320, 480)), garment, "tops").width == 32
    assert len(cos.deleted) == 2
    assert provider.aidge_client.submit_calls == 1
    assert provider.aidge_client.query_calls == 2


def test_cleanup_failure_logs_count_without_object_key(monkeypatch, tmp_path, caplog):
    garment = tmp_path / "garment.png"
    garment.write_bytes(png((320, 480)))
    cos = FakeCos(delete_failures=1)
    settings = AidgeSettings(
        access_key_id="id", access_key_secret="secret", endpoint="example", region="region",
        model_image_url="https://placeholder.invalid/model.png",
        garment_image_url="https://placeholder.invalid/garment.png", cloth_type="tops", resolution="1K",
        output_dir=tmp_path, state_file=tmp_path / "state.json",
    )
    provider = AidgeCosProvider(
        CosSettings(secret_id="id", secret_key="secret"), settings,
        poll_interval=1, max_wait_seconds=3, cos_client=cos, aidge_client=FakeAidge(),
    )
    monkeypatch.setattr("backend.tryon_provider.time.sleep", lambda _: None)
    monkeypatch.setattr("backend.tryon_provider.download_result", lambda _, **kwargs: ProviderResult(png(), 32, 48))
    assert provider.generate(png((320, 480)), garment, "tops").width == 32
    assert "1 temporary COS input(s) could not be deleted" in caplog.text
    assert all(key not in caplog.text for key in cos.deleted)
