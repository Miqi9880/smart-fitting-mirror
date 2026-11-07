"""End-to-end public-job tests backed by an offline loopback model stub."""

import io
import json
import runpy
import sys
import threading
import time
import uuid
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from PIL import Image

import backend.service as service_module
from backend.local_model_provider import LocalModelProvider
from backend.provider_control import GenerationBudget
from backend.service import create_app
from backend.usage_metrics import UsageMetrics


def png(size=(320, 480), color="#456789"):
    output = io.BytesIO()
    with Image.new("RGB", size, color) as image:
        image.save(output, "PNG")
    return output.getvalue()


@contextmanager
def local_model_stub(mode="success", *, outfit_supported=False):
    result = png((64, 96), "#b35a27")

    class Server(ThreadingHTTPServer):
        daemon_threads = True
        allow_reuse_address = True

        def __init__(self, address, handler):
            super().__init__(address, handler)
            self.started = threading.Event()
            self.release = threading.Event()
            self.posts = 0
            self.body = b""
            self.path = ""

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_args):
            pass

        def send(self, status, body, content_type):
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            try:
                self.wfile.write(body)
            except OSError:
                pass  # The provider may have closed its socket at the budget deadline.

        def do_GET(self):
            self.send(200, json.dumps({
                "ready": True, "is_mock": True, "model_id": "local-integration-stub",
                "outfit_supported": outfit_supported,
            }).encode(), "application/json")

        def do_POST(self):
            length = int(self.headers.get("Content-Length", "0"))
            self.server.body = self.rfile.read(length)
            self.server.path = self.path
            self.server.posts += 1
            self.server.started.set()
            if mode == "block":
                self.server.release.wait(5)
            elif mode == "slow":
                time.sleep(0.4)
            if mode == "busy":
                self.send(503, b'{"error":{"code":"MODEL_BUSY","retryable":true}}', "application/json")
            else:
                self.send(200, result, "image/png")

    server = Server(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_port}", server
    finally:
        server.release.set()
        server.shutdown()
        thread.join(timeout=2)
        server.server_close()


def make_test_catalog(tmp_path, monkeypatch):
    relative = Path(service_module.ASSET_SPECS["upper_blue"]["file"])
    garment = tmp_path / relative
    garment.parent.mkdir(parents=True, exist_ok=True)
    garment.write_bytes(png((320, 320), "#2277aa"))
    monkeypatch.setattr(service_module, "ASSETS", tmp_path)


def open_session(client):
    session = client.post("/api/v1/sessions").json()
    return {"Authorization": "Bearer " + session["session_token"]}, session["session_token"]


def upload_person(client, headers):
    response = client.post(
        "/api/v1/uploads", headers=headers,
        files={"file": ("person.png", png(), "image/png")},
    )
    assert response.status_code == 201
    return response.json()["upload_id"]


def create_job(client, headers, upload_id, key):
    return client.post(
        "/api/v1/tryon-jobs", headers={**headers, "Idempotency-Key": key},
        json={"upload_id": upload_id, "garment_id": "upper_blue"},
    )


def wait_terminal(client, headers, job_id, timeout=4):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        response = client.get(f"/api/v1/tryon-jobs/{job_id}", headers=headers)
        if response.status_code != 200:
            return response
        job = response.json()
        if job["status"] in {"succeeded", "failed", "cancelled"}:
            return response
        time.sleep(0.01)
    raise AssertionError("local model job did not become terminal")


def test_outfit_thumbnails_selection_and_replay_on_local_model(tmp_path, monkeypatch):
    make_test_catalog(tmp_path, monkeypatch)
    with local_model_stub(outfit_supported=True) as (url, worker), TestClient(create_app(
        provider=LocalModelProvider(url, allow_mock_for_tests=True), catalog_path=tmp_path / "catalog.sqlite3", catalog_profile="regression",
    )) as client:
        auth, _ = open_session(client)
        catalog = client.get("/api/v1/garments", headers=auth).json()
        assert catalog["outfit_available"] is True
        assert {item["category"] for item in catalog["accessories"]} == {"belt", "earrings"}
        path = "/api/v1/outfit-accessories/silver_studs.png"
        assert client.get(path).status_code == 401
        thumb = client.get(path, headers=auth)
        with Image.open(io.BytesIO(thumb.content)) as image:
            assert image.format == "PNG" and image.mode == "RGBA"
        outfit = {"garment_ids": ["upper_blue"], "accessory_ids": ["silver_studs"]}
        body = {"upload_id": upload_person(client, auth), "outfit": outfit}
        headers = {**auth, "Idempotency-Key": str(uuid.uuid4())}
        path = "/api/v1/tryon-jobs"
        created = client.post(path, headers=headers, json=body)
        assert created.status_code == 202, created.json()
        job = wait_terminal(client, auth, created.json()["job_id"]).json()
        assert job["status"] == "succeeded" and job["outfit"] == outfit
        assert job["garment_id"] == "upper_blue" and not job["is_mock"]
        assert client.post(path, headers=headers, json=body).json()["job_id"] == job["job_id"]
        changed = {**outfit, "accessory_ids": ["gold_hoops"]}
        assert client.post(path, headers=headers, json={**body, "outfit": changed}).status_code == 409
        assert worker.path == "/internal/outfit"
        assert worker.body.count(b'name="garment_image"') == 2
        assert b"upper_body,earrings" in worker.body
        assert worker.posts == 1


def test_outfit_is_rejected_before_sending_photo_to_legacy_worker(tmp_path, monkeypatch):
    make_test_catalog(tmp_path, monkeypatch)
    with local_model_stub() as (url, worker), TestClient(create_app(
        provider=LocalModelProvider(url, allow_mock_for_tests=True), catalog_path=tmp_path / "catalog.sqlite3", catalog_profile="regression",
    )) as client:
        auth, _ = open_session(client)
        created = client.post("/api/v1/tryon-jobs", headers={**auth, "Idempotency-Key": str(uuid.uuid4())}, json={
            "upload_id": upload_person(client, auth), "outfit": {"garment_ids": ["upper_blue"]},
        })
        assert created.status_code == 202
        job = wait_terminal(client, auth, created.json()["job_id"]).json()
        assert job["status"] == "failed" and job["error"]["code"] == "PROVIDER_UNAVAILABLE"
        assert worker.posts == 0


@pytest.mark.parametrize(("garments", "accessories", "code"), [
    (["studio_straight_jeans_67", "studio_cotton_t_dress_09"], [], "INVALID_OUTFIT"),
    (["studio_straight_jeans_67", "studio_straight_jeans_07"], [], "INVALID_OUTFIT"),
    ([], ["silver_studs", "gold_hoops"], "INVALID_OUTFIT"),
    ([], ["missing"], "RESOURCE_NOT_FOUND"),
])
def test_outfit_rejects_conflicting_or_unknown_references(tmp_path, garments, accessories, code):
    with TestClient(create_app(catalog_path=tmp_path / "catalog.sqlite3")) as client:
        auth, _ = open_session(client)
        response = client.post("/api/v1/tryon-jobs", headers={**auth, "Idempotency-Key": str(uuid.uuid4())}, json={
            "upload_id": upload_person(client, auth),
            "outfit": {"garment_ids": garments, "accessory_ids": accessories},
        })
        assert response.status_code == (404 if code == "RESOURCE_NOT_FOUND" else 422)
        assert response.json()["error"]["code"] == code


def test_explicit_cli_local_provider_configuration_keeps_default_mock(monkeypatch, tmp_path):
    import uvicorn

    captured = {}
    monkeypatch.setattr(uvicorn, "run", lambda app, **kwargs: captured.setdefault("app", app))
    monkeypatch.setattr(sys, "argv", [
        "backend", "--provider", "local-model", "--local-model-url", "http://localhost:8787",
        "--catalog-db", str(tmp_path / "catalog.sqlite3"),
        "--usage-metrics-db", str(tmp_path / "usage.sqlite3"),
    ])
    runpy.run_module("backend.__main__", run_name="__main__")
    app = captured["app"]
    assert app.state.store.provider.name == "local-model"
    assert app.state.store.provider.base_url == "http://localhost:8787"
    assert app.state.store.provider_disabled is False
    assert app.state.store.executor is not None
    app.state.store.executor.shutdown(wait=True)

    captured.clear()
    monkeypatch.setattr(sys, "argv", [
        "backend", "--catalog-db", str(tmp_path / "default-catalog.sqlite3"),
        "--usage-metrics-db", str(tmp_path / "default-usage.sqlite3"),
    ])
    runpy.run_module("backend.__main__", run_name="__main__")
    assert captured["app"].state.store.provider is None
    assert captured["app"].state.store.executor is None


@pytest.mark.parametrize("url", ["http://example.com:8787", "http://0.0.0.0:8787"])
def test_cli_rejects_non_loopback_local_model_endpoint(monkeypatch, url):
    monkeypatch.setattr(sys, "argv", ["backend", "--provider", "local-model", "--local-model-url", url])
    with pytest.raises(SystemExit):
        runpy.run_module("backend.__main__", run_name="__main__")


def test_cli_requires_local_endpoint_and_success_mode(monkeypatch):
    monkeypatch.setattr(sys, "argv", ["backend", "--provider", "local-model"])
    with pytest.raises(SystemExit):
        runpy.run_module("backend.__main__", run_name="__main__")
    monkeypatch.setattr(sys, "argv", [
        "backend", "--provider", "local-model", "--local-model-url", "http://127.0.0.1:8787",
        "--mode", "failure",
    ])
    with pytest.raises(SystemExit):
        runpy.run_module("backend.__main__", run_name="__main__")


def test_local_model_success_flows_through_session_idempotency_download_and_metrics(tmp_path, monkeypatch):
    make_test_catalog(tmp_path, monkeypatch)
    metrics = UsageMetrics(tmp_path / "metrics.sqlite3")
    with local_model_stub() as (url, server):
        provider = LocalModelProvider(url, allow_mock_for_tests=True)
        with TestClient(create_app(provider=provider, usage_metrics=metrics,
                                   catalog_profile="regression")) as client:
            headers, token = open_session(client)
            upload_id = upload_person(client, headers)
            key = str(uuid.uuid4())
            created = create_job(client, headers, upload_id, key)
            replay = create_job(client, headers, upload_id, key)
            assert created.status_code == 202 and replay.status_code == 200
            assert replay.json()["job_id"] == created.json()["job_id"]
            terminal = wait_terminal(client, headers, created.json()["job_id"])
            assert terminal.status_code == 200
            job = terminal.json()
            assert job["status"] == "succeeded" and job["is_mock"] is False
            downloaded = client.get(job["result"]["image_url"], headers=headers)
            assert downloaded.status_code == 200 and downloaded.content.startswith(b"\x89PNG")
            assert b'name="person_image"' in server.body
            assert b'name="garment_image"' in server.body
            assert b'name="cloth_type"' in server.body and b"tops" in server.body
            assert b"session_token" not in server.body and token.encode() not in server.body
            assert str(tmp_path).encode() not in server.body
            assert server.posts == 1
            assert client.delete("/api/v1/sessions/current", headers=headers).status_code == 204
            assert client.get(job["result"]["image_url"], headers=headers).status_code == 401

    rows = metrics.read()
    counts = {row["metric"]: row["event_count"] for row in rows}
    assert counts == {
        "task_created": 1,
        "provider_submit_attempt": 1,
        "provider_submit_accepted": 1,
        "succeeded": 1,
    }
    assert all(row["provider"] == "local-model" and row["mode"] == "real" for row in rows)
    assert all(not ({"session_id", "job_id", "upload_id", "task_id"} & row.keys()) for row in rows)


def test_local_model_busy_fails_explicitly_without_mock_fallback_or_duplicate_submit(tmp_path, monkeypatch):
    make_test_catalog(tmp_path, monkeypatch)
    metrics = UsageMetrics(tmp_path / "metrics.sqlite3")
    with local_model_stub("busy") as (url, server):
        with TestClient(create_app(
            provider=LocalModelProvider(url, allow_mock_for_tests=True),
            usage_metrics=metrics, catalog_profile="regression",
        )) as client:
            headers, _ = open_session(client)
            upload_id = upload_person(client, headers)
            key = str(uuid.uuid4())
            created = create_job(client, headers, upload_id, key)
            replay = create_job(client, headers, upload_id, key)
            assert created.status_code == 202 and replay.status_code == 200
            assert replay.json()["job_id"] == created.json()["job_id"]
            terminal = wait_terminal(client, headers, created.json()["job_id"])
            assert terminal.status_code == 200
            job = terminal.json()
            assert job["status"] == "failed" and job["is_mock"] is False
            assert job["error"]["code"] == "PROVIDER_UNAVAILABLE"
            assert job["result"] is None and server.posts == 1

    rows = metrics.read()
    counts = {row["metric"]: row["event_count"] for row in rows}
    assert counts == {"task_created": 1, "provider_submit_attempt": 1, "failed": 1}
    assert all(row["provider"] == "local-model" and row["mode"] == "real" for row in rows)


def test_ended_session_discards_late_local_model_result(tmp_path, monkeypatch):
    make_test_catalog(tmp_path, monkeypatch)
    with local_model_stub("block") as (url, server):
        with TestClient(create_app(provider=LocalModelProvider(url, allow_mock_for_tests=True),
                                   catalog_profile="regression")) as client:
            headers, _ = open_session(client)
            upload_id = upload_person(client, headers)
            created = create_job(client, headers, upload_id, str(uuid.uuid4()))
            assert created.status_code == 202 and server.started.wait(2)
            assert client.delete("/api/v1/sessions/current", headers=headers).status_code == 204
            server.release.set()
            deadline = time.monotonic() + 3
            while client.app.state.store.provider_inflight and time.monotonic() < deadline:
                time.sleep(0.01)
            assert not client.app.state.store.provider_inflight
            assert client.get(f"/api/v1/tryon-jobs/{created.json()['job_id']}", headers=headers).status_code == 401
            result_url = f"http://testserver/api/v1/results/{created.json()['job_id']}.png"
            assert client.get(result_url, headers=headers).status_code == 401
            assert server.posts == 1


def test_local_model_timeout_never_turns_into_mock_success(tmp_path, monkeypatch):
    make_test_catalog(tmp_path, monkeypatch)
    real_budget = GenerationBudget
    monkeypatch.setattr(service_module, "GenerationBudget",
                        lambda _seconds, *, clock=time.monotonic: real_budget(0.12, clock=clock))
    with local_model_stub("slow") as (url, server):
        with TestClient(create_app(provider=LocalModelProvider(url, allow_mock_for_tests=True),
                                   catalog_profile="regression")) as client:
            headers, _ = open_session(client)
            upload_id = upload_person(client, headers)
            created = create_job(client, headers, upload_id, str(uuid.uuid4()))
            terminal = wait_terminal(client, headers, created.json()["job_id"], timeout=2)
            assert terminal.status_code == 200
            job = terminal.json()
            assert job["status"] == "failed" and job["is_mock"] is False
            assert job["error"]["code"] == "GENERATION_TIMEOUT"
            assert job["result"] is None
            deadline = time.monotonic() + 2
            while client.app.state.store.provider_inflight and time.monotonic() < deadline:
                time.sleep(0.01)
            assert not client.app.state.store.provider_inflight
            assert server.posts == 1
