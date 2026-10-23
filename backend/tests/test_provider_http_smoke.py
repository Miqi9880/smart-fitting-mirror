import io
from pathlib import Path

import httpx
import pytest
from PIL import Image

from backend.provider_http_smoke import run


def png() -> bytes:
    output = io.BytesIO()
    with Image.new("RGB", (20, 30), "#556677") as image:
        image.save(output, "PNG")
    return output.getvalue()


def test_http_smoke_uses_one_job_and_ends_session(monkeypatch, tmp_path):
    calls = []
    job_polls = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal job_polls
        calls.append((request.method, request.url.path, request.headers.get("idempotency-key")))
        if request.url.path == "/health":
            return httpx.Response(200, json={"status": "ok", "mode": "aidge-cos", "is_mock": False})
        if request.url.path == "/api/v1/sessions" and request.method == "POST":
            return httpx.Response(201, json={"session_token": "private-token"})
        if request.url.path == "/api/v1/uploads":
            return httpx.Response(201, json={"upload_id": "upload_one"})
        if request.url.path == "/api/v1/tryon-jobs" and request.method == "POST":
            return httpx.Response(202, json={"job_id": "job_one", "status": "queued", "is_mock": False})
        if request.url.path == "/api/v1/tryon-jobs/job_one":
            job_polls += 1
            return httpx.Response(200, json={
                "job_id": "job_one", "status": "succeeded", "is_mock": False,
                "result": {"image_url": "http://test/api/v1/results/job_one.png", "width": 20, "height": 30},
            })
        if request.url.path == "/api/v1/results/job_one.png":
            return httpx.Response(200, content=png(), headers={"Content-Type": "image/png"})
        if request.url.path == "/api/v1/sessions/current" and request.method == "DELETE":
            return httpx.Response(204)
        return httpx.Response(404)

    model = tmp_path / "model.png"
    model.write_bytes(png())
    output = tmp_path / "result.png"
    monkeypatch.setattr("backend.provider_http_smoke.time.sleep", lambda _: None)
    original_client = httpx.Client
    monkeypatch.setattr(
        "backend.provider_http_smoke.httpx.Client",
        lambda **kwargs: original_client(transport=httpx.MockTransport(handler), base_url="http://test", **kwargs),
    )
    summary = run("http://test", model, "studio_zip_hoodie_04", output, 10)
    assert summary["status"] == "succeeded" and output.read_bytes().startswith(b"\x89PNG")
    creates = [call for call in calls if call[0:2] == ("POST", "/api/v1/tryon-jobs")]
    assert len(creates) == 1 and creates[0][2]
    assert calls[-1][0:2] == ("DELETE", "/api/v1/sessions/current")


def test_http_smoke_refuses_mock_backend(monkeypatch, tmp_path):
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"status": "ok", "mode": "success", "is_mock": True})

    original_client = httpx.Client
    monkeypatch.setattr(
        "backend.provider_http_smoke.httpx.Client",
        lambda **kwargs: original_client(transport=httpx.MockTransport(handler), base_url="http://test", **kwargs),
    )
    model = tmp_path / "model.png"
    model.write_bytes(png())
    with pytest.raises(RuntimeError, match="not using a real provider"):
        run("http://test", model, "studio_zip_hoodie_04", tmp_path / "result.png", 10)
