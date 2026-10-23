"""Exercise one explicitly enabled real provider through the public HTTP API.

This client never prints the session token and uses one stable idempotency key
for the single create request. It does not start the backend itself.
"""

from __future__ import annotations

import argparse
import io
import time
import uuid
from pathlib import Path

import httpx
from PIL import Image, UnidentifiedImageError


TERMINAL = {"succeeded", "failed", "cancelled"}


def checked(response: httpx.Response) -> dict:
    response.raise_for_status()
    value = response.json()
    if not isinstance(value, dict):
        raise RuntimeError("Backend returned an invalid JSON object.")
    return value


def run(base_url: str, model_image: Path, garment_id: str, output: Path, max_wait_seconds: int) -> dict:
    model = model_image.resolve()
    if not model.is_file():
        raise FileNotFoundError(f"Model image does not exist: {model}")
    base = base_url.rstrip("/")
    with httpx.Client(timeout=30) as client:
        health = checked(client.get(base + "/health"))
        if health.get("is_mock") is not False:
            raise RuntimeError("Refusing to run: backend is not using a real provider.")
        session = checked(client.post(base + "/api/v1/sessions"))
        headers = {"Authorization": "Bearer " + session["session_token"]}
        try:
            with model.open("rb") as source:
                uploaded = checked(
                    client.post(
                        base + "/api/v1/uploads",
                        headers=headers,
                        files={"file": (model.name, source, "image/png")},
                    )
                )
            key = str(uuid.uuid4())
            created = checked(
                client.post(
                    base + "/api/v1/tryon-jobs",
                    headers={**headers, "Idempotency-Key": key},
                    json={"upload_id": uploaded["upload_id"], "garment_id": garment_id},
                )
            )
            if created.get("is_mock") is not False:
                raise RuntimeError("Backend created a mock job instead of a real provider job.")
            job_id = created["job_id"]
            deadline = time.monotonic() + max_wait_seconds
            job = created
            while job.get("status") not in TERMINAL and time.monotonic() < deadline:
                time.sleep(2)
                job = checked(client.get(base + f"/api/v1/tryon-jobs/{job_id}", headers=headers))
            if job.get("status") != "succeeded":
                code = (job.get("error") or {}).get("code", "CLIENT_WAIT_TIMEOUT")
                raise RuntimeError(f"Real provider job did not succeed: {code}")
            result = client.get(job["result"]["image_url"], headers=headers)
            result.raise_for_status()
            try:
                with Image.open(io.BytesIO(result.content)) as image:
                    image.verify()
            except (UnidentifiedImageError, OSError, ValueError, SyntaxError) as error:
                raise RuntimeError("Downloaded result is not a valid image.") from error
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_bytes(result.content)
            return {
                "provider": health.get("mode"),
                "status": job["status"],
                "is_mock": job["is_mock"],
                "width": job["result"]["width"],
                "height": job["result"]["height"],
                "output": str(output.resolve()),
            }
        finally:
            try:
                client.delete(base + "/api/v1/sessions/current", headers=headers)
            except Exception:
                pass


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Run one approved real-provider job through the public backend API")
    parser.add_argument("--url", default="http://127.0.0.1:8110")
    parser.add_argument("--model-image", type=Path, required=True)
    parser.add_argument("--garment-id", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--max-wait-seconds", type=int, default=150)
    args = parser.parse_args(argv)
    summary = run(args.url, args.model_image, args.garment_id, args.output, args.max_wait_seconds)
    print("Real provider HTTP smoke: PASS")
    for key, value in summary.items():
        print(f"- {key}: {value}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
