"""Offline tests for the unregistered local model provider adapter."""

import io
import re
from pathlib import Path

import pytest
import requests
from PIL import Image

import backend.local_model_provider as local_module
import backend.provider_images as provider_images
from backend.local_model_provider import LocalModelProvider
from backend.provider_control import GenerationBudget, ProviderFailure
from backend.tryon_provider import MAX_RESULT_BYTES, ProviderResult


def png(size=(320, 480), color="#456789"):
    output = io.BytesIO()
    with Image.new("RGB", size, color) as image:
        image.save(output, "PNG")
    return output.getvalue()


class Clock:
    def __init__(self):
        self.now = 0.0

    def __call__(self):
        return self.now

    def advance(self, seconds):
        self.now += seconds


class FakeRaw:
    def __init__(self, data=b"", *, chunks=None, on_chunk=None):
        self.data = data
        self.chunks = chunks
        self.on_chunk = on_chunk

    def stream(self, amt, decode_content=False):
        source = self.chunks if self.chunks is not None else [self.data]
        for chunk in source:
            if self.on_chunk:
                self.on_chunk()
            if chunk:
                yield chunk

    def close(self):
        pass


def response(status, body=b"", headers=None, *, chunks=None, on_chunk=None):
    value = requests.Response()
    value.status_code = status
    value.headers.update(headers or {})
    value.raw = FakeRaw(body, chunks=chunks, on_chunk=on_chunk)
    value._content = False
    value._content_consumed = False
    return value


class FakeAdapter(requests.adapters.BaseAdapter):
    def __init__(self, handler):
        self.handler = handler
        self.calls = []

    def send(self, request, **kwargs):
        self.calls.append((request, kwargs))
        value = self.handler(request, kwargs)
        value.request = request
        value.url = request.url
        value.connection = self
        return value

    def close(self):
        pass


def configured_provider(handler, *, base_url="http://127.0.0.1:8787", allow_mock=True):
    adapter = FakeAdapter(handler)

    def session_factory():
        session = requests.Session()
        session.mount("http://", adapter)
        return session

    return LocalModelProvider(
        base_url,
        allow_mock_for_tests=allow_mock,
        session_factory=session_factory,
    ), adapter


def health(*, ready=True, is_mock=True):
    import json

    return response(200, json.dumps({
        "ready": ready, "is_mock": is_mock, "model_id": "test-model-v1"
    }).encode(), {"Content-Type": "application/json"})


def write_garment(tmp_path: Path):
    path = tmp_path / "private-local-garment.png"
    path.write_bytes(png())
    return path


@pytest.fixture(autouse=True)
def temporary_files_stay_in_test_directory(tmp_path, monkeypatch):
    monkeypatch.setattr(local_module.tempfile, "tempdir", str(tmp_path))


def run(provider, tmp_path, *, cloth_type="tops", clock=None):
    return provider.generate(
        png(), write_garment(tmp_path), cloth_type,
        budget=GenerationBudget(120, clock=clock or Clock()),
    )


def test_default_is_unconfigured_and_does_not_make_a_request(tmp_path):
    provider = LocalModelProvider()
    with pytest.raises(ProviderFailure) as error:
        run(provider, tmp_path)
    assert error.value.code == "PROVIDER_UNAVAILABLE"


@pytest.mark.parametrize("url", [
    "https://127.0.0.1:8787", "http://example.com:8787", "http://0.0.0.0:8787",
    "http://user:pass@127.0.0.1:8787", "http://127.0.0.1:8787/prefix",
    "http://127.0.0.1:8787?next=elsewhere", "http://127.0.0.1:99999",
])
def test_only_explicit_plain_http_loopback_origin_is_accepted(url):
    with pytest.raises(ValueError):
        LocalModelProvider(url)


def test_only_tops_are_supported_and_unsupported_type_does_not_contact_server(tmp_path):
    provider, adapter = configured_provider(lambda *_: health())
    with pytest.raises(ProviderFailure) as error:
        run(provider, tmp_path, cloth_type="pants")
    assert error.value.code == "PROVIDER_UNSUPPORTED_CLOTH_TYPE"
    assert adapter.calls == []


def test_oversized_and_invalid_inputs_are_rejected_before_http(tmp_path, monkeypatch):
    provider, adapter = configured_provider(lambda *_: health())
    garment = write_garment(tmp_path)
    budget = GenerationBudget(120)

    monkeypatch.setattr(local_module, "MAX_SOURCE_BYTES", 8)
    with pytest.raises(ProviderFailure) as error:
        provider.generate(png(), garment, "tops", budget=budget)
    assert error.value.code == "PROVIDER_INVALID_INPUT"
    assert adapter.calls == []

    monkeypatch.setattr(local_module, "MAX_SOURCE_BYTES", 64 * 1024 * 1024)
    with pytest.raises(ProviderFailure) as error:
        provider.generate(b"not-an-image", garment, "tops", budget=GenerationBudget(120))
    assert error.value.code == "PROVIDER_INVALID_INPUT"
    assert adapter.calls == []

    monkeypatch.setattr(provider_images, "MAX_SOURCE_PIXELS", 100_000)
    with pytest.raises(ProviderFailure) as error:
        provider.generate(png(), garment, "tops", budget=GenerationBudget(120))
    assert error.value.code == "PROVIDER_INVALID_INPUT"
    assert adapter.calls == []


def test_health_must_be_ready_and_real_unless_test_flag_is_explicit(tmp_path):
    not_ready, adapter = configured_provider(lambda *_: health(ready=False))
    with pytest.raises(ProviderFailure) as error:
        run(not_ready, tmp_path)
    assert error.value.code == "PROVIDER_UNAVAILABLE" and error.value.retryable
    assert len(adapter.calls) == 1

    real_only, adapter = configured_provider(lambda *_: health(), allow_mock=False)
    with pytest.raises(ProviderFailure) as error:
        run(real_only, tmp_path)
    assert error.value.code == "PROVIDER_UNAVAILABLE" and not error.value.retryable
    assert len(adapter.calls) == 1  # Fake service is rejected before POST.


def test_success_posts_only_normalized_image_bytes_and_returns_provider_result(tmp_path):
    output = png((64, 96), "#aa5522")

    def handler(request, kwargs):
        if request.method == "GET":
            return health()
        assert request.url == "http://127.0.0.1:8787/internal/tryon"
        assert kwargs["stream"] is True
        assert kwargs["timeout"] > 0
        assert "Authorization" not in request.headers
        body = request.body
        assert b'name="person_image"' in body and b'name="garment_image"' in body
        assert b'name="cloth_type"' in body and b"tops" in body
        assert re.search(rb'name="budget_ms"\r\n\r\n(?:[1-9]\d{0,5}|120000)', body)
        assert b"\xff\xd8\xff" in body  # Inputs are bounded/normalized JPEG bytes.
        assert b"private-local-garment.png" not in body
        return response(200, output, {"Content-Type": "image/png", "Content-Length": str(len(output))})

    provider, adapter = configured_provider(handler)
    result = run(provider, tmp_path)

    assert isinstance(result, ProviderResult)
    assert (result.width, result.height) == (64, 96)
    with Image.open(io.BytesIO(result.data)) as image:
        assert image.format == "PNG" and image.size == (64, 96)
    assert [call[0].method for call in adapter.calls] == ["GET", "POST"]


@pytest.mark.parametrize(("status", "error_code", "expected"), [
    (400, "INVALID_INPUT", "PROVIDER_INVALID_INPUT"),
    (422, "UNSUPPORTED_CLOTH_TYPE", "PROVIDER_UNSUPPORTED_CLOTH_TYPE"),
    (503, "MODEL_BUSY", "PROVIDER_UNAVAILABLE"),
    (504, "INFERENCE_TIMEOUT", "GENERATION_TIMEOUT"),
])
def test_model_error_responses_map_to_safe_provider_failures(tmp_path, status, error_code, expected):
    import json

    def handler(request, _kwargs):
        if request.method == "GET":
            return health()
        body = json.dumps({"error": {"code": error_code, "message": "private server detail", "retryable": True}}).encode()
        return response(status, body, {"Content-Type": "application/json"})

    provider, _ = configured_provider(handler)
    with pytest.raises(ProviderFailure) as error:
        run(provider, tmp_path)
    assert error.value.code == expected
    assert "private server detail" not in error.value.message


@pytest.mark.parametrize(("content_type", "body", "expected"), [
    ("application/json", b'{"image":"fake"}', "PROVIDER_INVALID_RESULT"),
    ("image/png", b"not-a-png", "PROVIDER_INVALID_RESULT"),
])
def test_bad_success_payloads_are_rejected(tmp_path, content_type, body, expected):
    provider, _ = configured_provider(lambda request, _kwargs: health() if request.method == "GET"
                                     else response(200, body, {"Content-Type": content_type}))
    with pytest.raises(ProviderFailure) as error:
        run(provider, tmp_path)
    assert error.value.code == expected


def test_oversized_result_is_rejected_before_reading_body(tmp_path):
    provider, _ = configured_provider(lambda request, _kwargs: health() if request.method == "GET"
                                     else response(200, b"", {
                                         "Content-Type": "image/png",
                                         "Content-Length": str(MAX_RESULT_BYTES + 1),
                                     }))
    with pytest.raises(ProviderFailure) as error:
        run(provider, tmp_path)
    assert error.value.code == "PROVIDER_INVALID_RESULT"


def test_result_pixel_limit_is_checked(monkeypatch, tmp_path):
    monkeypatch.setattr(local_module, "MAX_RESULT_PIXELS", 100)
    provider, _ = configured_provider(lambda request, _kwargs: health() if request.method == "GET"
                                     else response(200, png((11, 11)), {"Content-Type": "image/png"}))
    with pytest.raises(ProviderFailure) as error:
        run(provider, tmp_path)
    assert error.value.code == "PROVIDER_INVALID_RESULT"


def test_expired_budget_rejects_late_result_without_claiming_gpu_cancellation(tmp_path):
    clock = Clock()

    def handler(request, _kwargs):
        if request.method == "GET":
            return health()
        return response(200, chunks=[png()], headers={"Content-Type": "image/png"},
                        on_chunk=lambda: clock.advance(121))

    provider, adapter = configured_provider(handler)
    with pytest.raises(ProviderFailure) as error:
        run(provider, tmp_path, clock=clock)
    assert error.value.code == "GENERATION_TIMEOUT"
    assert len(adapter.calls) == 2  # Client timeout cannot prove upstream GPU work stopped.


def test_health_timeout_stops_before_tryon_post(tmp_path):
    provider, adapter = configured_provider(
        lambda *_: (_ for _ in ()).throw(requests.Timeout("fake timeout"))
    )
    with pytest.raises(ProviderFailure) as error:
        run(provider, tmp_path)
    assert error.value.code == "GENERATION_TIMEOUT"
    assert len(adapter.calls) == 1


def test_redirect_is_not_followed_to_another_host(tmp_path):
    provider, adapter = configured_provider(
        lambda *_: response(302, b"", {"Location": "http://example.com/steal"})
    )
    with pytest.raises(ProviderFailure) as error:
        run(provider, tmp_path)
    assert error.value.code == "PROVIDER_UNAVAILABLE"
    assert len(adapter.calls) == 1
