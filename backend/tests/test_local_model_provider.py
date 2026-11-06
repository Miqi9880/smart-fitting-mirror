"""Offline tests for the unregistered local model provider adapter."""

import io
import json
import re
from contextlib import contextmanager, suppress
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from random import Random
from threading import Event, Thread
from time import monotonic, sleep
from types import SimpleNamespace

import pytest
from PIL import Image

import backend.local_model_provider as local_module
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


class FakeResponse:
    def __init__(self, status, body=b"", headers=None, *, chunks=None, on_chunk=None):
        self.status = status
        self.headers = headers or {}
        self.chunks = iter(chunks if chunks is not None else [body])
        self.on_chunk = on_chunk

    def getheader(self, name, default=None):
        return self.headers.get(name, default)

    def read1(self, amount):
        if self.on_chunk:
            self.on_chunk()
        return next(self.chunks, b"")

    def __enter__(self):
        return self

    def __exit__(self, *args):
        pass


class FakeTransport:
    def __init__(self, handler):
        self.handler = handler
        self.calls = []

    def __call__(self, host, port, *, timeout):
        transport = self

        class Connection:
            sock = SimpleNamespace(settimeout=lambda _: None, shutdown=lambda _: None)

            def connect(self):
                pass

            def request(self, method, path, *, body, headers):
                self.request_data = SimpleNamespace(method=method, path=path, body=body, headers=headers)

            def getresponse(self):
                settings = {"host": host, "port": port, "connect_timeout": timeout}
                transport.calls.append((self.request_data, settings))
                return transport.handler(self.request_data, settings)

            def close(self):
                pass

        return Connection()


def configured_provider(handler, *, base_url="http://127.0.0.1:8787", allow_mock=True):
    adapter = FakeTransport(handler)
    return LocalModelProvider(
        base_url,
        allow_mock_for_tests=allow_mock,
        connection_factory=adapter,
    ), adapter


def health(*, ready=True, is_mock=True):
    return FakeResponse(200, json.dumps({
        "ready": ready, "is_mock": is_mock, "model_id": "test-model-v1"
    }).encode(), {"Content-Type": "application/json"})


def write_garment(tmp_path: Path):
    path = tmp_path / "private-local-garment.png"
    path.write_bytes(png())
    return path


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

    monkeypatch.setattr(local_module, "MAX_SOURCE_PIXELS", 100_000)
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


def test_success_preserves_input_bytes_and_accepts_narrow_public_images(tmp_path):
    output = png((64, 96), "#aa5522")
    person = png((180, 640))
    garment = tmp_path / "private-local-garment.jpg"
    with Image.new("RGB", (400, 320), "#456789") as image:
        image.save(garment, "JPEG")

    def handler(request, kwargs):
        if request.method == "GET":
            return health()
        assert request.path == "/internal/tryon"
        assert (kwargs["host"], kwargs["port"]) == ("127.0.0.1", 8787)
        assert kwargs["connect_timeout"] > 0
        assert "Authorization" not in request.headers
        body = request.body
        assert b'name="person_image"' in body and b'name="garment_image"' in body
        assert b'name="cloth_type"' in body and b"tops" in body
        assert re.search(rb'name="budget_ms"\r\n\r\n(?:[1-9]\d{0,5}|120000)', body)
        assert person in body and garment.read_bytes() in body
        assert b"Content-Type: image/png" in body and b"Content-Type: image/jpeg" in body
        assert b"private-local-garment.jpg" not in body
        return FakeResponse(200, output, {"Content-Type": "image/png", "Content-Length": str(len(output))})

    provider, adapter = configured_provider(handler)
    result = provider.generate(person, garment, "tops", budget=GenerationBudget(120))

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
    def handler(request, _kwargs):
        if request.method == "GET":
            return health()
        body = json.dumps({"error": {"code": error_code, "message": "private server detail", "retryable": True}}).encode()
        return FakeResponse(status, body, {"Content-Type": "application/json"})

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
                                     else FakeResponse(200, body, {"Content-Type": content_type}))
    with pytest.raises(ProviderFailure) as error:
        run(provider, tmp_path)
    assert error.value.code == expected


def test_oversized_result_is_rejected_before_reading_body(tmp_path):
    provider, _ = configured_provider(lambda request, _kwargs: health() if request.method == "GET"
                                     else FakeResponse(200, b"", {
                                         "Content-Type": "image/png",
                                         "Content-Length": str(MAX_RESULT_BYTES + 1),
                                     }))
    with pytest.raises(ProviderFailure) as error:
        run(provider, tmp_path)
    assert error.value.code == "PROVIDER_INVALID_RESULT"


def test_result_pixel_limit_is_checked(monkeypatch, tmp_path):
    monkeypatch.setattr(local_module, "MAX_RESULT_PIXELS", 100)
    provider, _ = configured_provider(lambda request, _kwargs: health() if request.method == "GET"
                                     else FakeResponse(200, png((11, 11)), {"Content-Type": "image/png"}))
    with pytest.raises(ProviderFailure) as error:
        run(provider, tmp_path)
    assert error.value.code == "PROVIDER_INVALID_RESULT"


def test_expired_budget_rejects_late_result_without_claiming_gpu_cancellation(tmp_path):
    clock = Clock()

    def handler(request, _kwargs):
        if request.method == "GET":
            return health()
        return FakeResponse(200, chunks=[png()], headers={"Content-Type": "image/png"},
                        on_chunk=lambda: clock.advance(121))

    provider, adapter = configured_provider(handler)
    with pytest.raises(ProviderFailure) as error:
        run(provider, tmp_path, clock=clock)
    assert error.value.code == "GENERATION_TIMEOUT"
    assert len(adapter.calls) == 2  # Client timeout cannot prove upstream GPU work stopped.


def test_health_timeout_stops_before_tryon_post(tmp_path):
    provider, adapter = configured_provider(
        lambda *_: (_ for _ in ()).throw(TimeoutError("fake timeout"))
    )
    with pytest.raises(ProviderFailure) as error:
        run(provider, tmp_path)
    assert error.value.code == "GENERATION_TIMEOUT"
    assert len(adapter.calls) == 1


def test_redirect_is_not_followed_to_another_host(tmp_path):
    provider, adapter = configured_provider(
        lambda *_: FakeResponse(302, b"", {"Location": "http://example.com/steal"})
    )
    with pytest.raises(ProviderFailure) as error:
        run(provider, tmp_path)
    assert error.value.code == "PROVIDER_UNAVAILABLE"
    assert len(adapter.calls) == 1


@pytest.mark.parametrize("stage", ["GET", "POST"])
@pytest.mark.parametrize(("body", "retryable"), [
    (b'{"error":null}', True),
    (b'{"error":{"code":"MODEL_BUSY","retryable":false}}', False),
])
def test_busy_responses_handle_malformed_errors_and_preserve_explicit_retryability(tmp_path, stage, body, retryable):
    provider, _ = configured_provider(lambda request, _: FakeResponse(503, body) if request.method == stage else health())
    with pytest.raises(ProviderFailure) as error:
        run(provider, tmp_path)
    assert error.value.code == "PROVIDER_UNAVAILABLE"
    assert error.value.retryable is retryable


def test_budget_below_one_millisecond_skips_post(tmp_path):
    provider, adapter = configured_provider(lambda *_: health())
    with pytest.raises(ProviderFailure) as error:
        provider.generate(png(), write_garment(tmp_path), "tops", budget=GenerationBudget(0.0005, clock=Clock()))
    assert error.value.code == "GENERATION_TIMEOUT"
    assert [request.method for request, _ in adapter.calls] == ["GET"]


def test_normalized_png_cannot_exceed_result_byte_limit(tmp_path, monkeypatch):
    rng = Random(7)
    with Image.frombytes("P", (128, 128), rng.randbytes(128 * 128)) as image:
        image.putpalette(rng.randbytes(768))
        encoded = io.BytesIO()
        image.save(encoded, "PNG")
        source = encoded.getvalue()
        with image.convert("RGB") as rgb:
            encoded_rgb = io.BytesIO()
            rgb.save(encoded_rgb, "PNG")
    limit = (len(source) + encoded_rgb.tell()) // 2
    assert len(source) < limit < encoded_rgb.tell()
    monkeypatch.setattr(local_module, "MAX_RESULT_BYTES", limit)
    provider, _ = configured_provider(lambda request, _: health() if request.method == "GET" else
                                     FakeResponse(200, source, {"Content-Type": "image/png"}))
    with pytest.raises(ProviderFailure) as error:
        run(provider, tmp_path)
    assert error.value.code == "PROVIDER_INVALID_RESULT"


@contextmanager
def loopback_server(generate):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def send(self, body, media_type):
            self.send_response(200)
            self.send_header("Content-Type", media_type)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            self.send(b'{"ready":true,"is_mock":false,"model_id":"loopback-test"}', "application/json")

        def do_POST(self):
            self.rfile.read(int(self.headers["Content-Length"]))
            with suppress(OSError):
                generate(self)

    with ThreadingHTTPServer(("127.0.0.1", 0), Handler) as server:
        thread = Thread(target=server.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True)
        thread.start()
        try:
            yield LocalModelProvider(f"http://localhost:{server.server_port}")
        finally:
            server.shutdown()
            thread.join()


def test_inference_can_take_longer_than_connect_timeout(tmp_path):
    class ShortConnectBudget(GenerationBudget):
        def socket_timeout(self, cap=0.1):
            return super().socket_timeout(cap)

    def generate(handler):
        sleep(0.25)
        handler.send(png(), "image/png")

    with loopback_server(generate) as provider:
        result = provider.generate(png(), write_garment(tmp_path), "tops", budget=ShortConnectBudget(3))
    assert (result.width, result.height) == (320, 480)


@pytest.mark.parametrize("stage", ["headers", "body"])
def test_trickled_http_data_cannot_extend_total_deadline(tmp_path, stage):
    started_sending = Event()

    def generate(handler):
        if stage == "headers":
            handler.wfile.write(b"HTTP/1.0 200 OK\r\nX-Trickle: ")
        else:
            handler.send_response(200)
            handler.send_header("Content-Type", "image/png")
            handler.send_header("Content-Length", "60")
            handler.end_headers()
        started_sending.set()
        for _ in range(60):
            handler.wfile.write(b"a")
            sleep(0.04)

    person, garment = png(), write_garment(tmp_path)
    with loopback_server(generate) as provider:
        start = monotonic()
        with pytest.raises(ProviderFailure) as error:
            provider.generate(person, garment, "tops", budget=GenerationBudget(0.6))
        elapsed = monotonic() - start
    assert started_sending.is_set()
    assert error.value.code == "GENERATION_TIMEOUT"
    assert elapsed < 1.4
