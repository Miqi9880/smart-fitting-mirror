"""Private, opt-in smoke test for Volcengine Image Dressing V2.

This script is deliberately separate from the local MOCK backend.  It reads
credentials only from a Git-ignored local .env file and makes no network call
unless --execute is supplied explicitly.
"""

from __future__ import annotations

import argparse
import base64
import datetime as dt
import hashlib
import hmac
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any


HOST = "visual.volcengineapi.com"
REGION = "cn-north-1"
SERVICE = "cv"
VERSION = "2022-08-31"
REQ_KEY = "dressing_diffusionV2"
REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_ENV_FILE = REPO_ROOT / ".env"
DEFAULT_OUTPUT_DIR = REPO_ROOT.parent / "private" / "tryon-results"
DEFAULT_STATE_FILE = REPO_ROOT.parent / "private" / "volc-tryon-last-task.json"


class ConfigurationError(ValueError):
    """Raised before any network request when private local setup is incomplete."""


class RemoteCallError(RuntimeError):
    """A concise, non-secret description of an upstream API failure."""


@dataclass(frozen=True)
class Settings:
    access_key_id: str
    secret_access_key: str
    model_image: Path
    garment_image: Path
    output_dir: Path
    state_file: Path
    producer_id: str


def load_dotenv(path: Path) -> dict[str, str]:
    """Read a small KEY=VALUE file without printing its values."""
    if not path.is_file():
        raise ConfigurationError(f"Missing local config: {path}. Copy .env.example to .env first.")
    values: dict[str, str] = {}
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            raise ConfigurationError(f"Invalid .env line {number}; expected KEY=VALUE.")
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip().strip('"').strip("'")
    return values


def resolve_private_path(value: str, *, env_file: Path) -> Path:
    path = Path(value).expanduser()
    return path if path.is_absolute() else (env_file.parent / path).resolve()


def settings_from_env(path: Path) -> Settings:
    values = {**os.environ, **load_dotenv(path)}
    required = ["VOLC_ACCESS_KEY_ID", "VOLC_SECRET_ACCESS_KEY", "VOLC_TRYON_MODEL_IMAGE", "VOLC_TRYON_GARMENT_IMAGE"]
    missing = [name for name in required if not values.get(name)]
    if missing:
        raise ConfigurationError("Missing required local config values: " + ", ".join(missing))
    model_image = resolve_private_path(values["VOLC_TRYON_MODEL_IMAGE"], env_file=path)
    garment_image = resolve_private_path(values["VOLC_TRYON_GARMENT_IMAGE"], env_file=path)
    output_dir = resolve_private_path(values.get("VOLC_TRYON_OUTPUT_DIR", str(DEFAULT_OUTPUT_DIR)), env_file=path)
    state_file = resolve_private_path(values.get("VOLC_TRYON_STATE_FILE", str(DEFAULT_STATE_FILE)), env_file=path)
    for label, image in (("model image", model_image), ("garment image", garment_image)):
        if not image.is_file():
            raise ConfigurationError(f"Configured {label} does not exist: {image}")
        if image.stat().st_size > 5 * 1024 * 1024:
            raise ConfigurationError(f"Configured {label} exceeds Volcengine's 5 MiB limit: {image}")
    return Settings(
        access_key_id=values["VOLC_ACCESS_KEY_ID"],
        secret_access_key=values["VOLC_SECRET_ACCESS_KEY"],
        model_image=model_image,
        garment_image=garment_image,
        output_dir=output_dir,
        state_file=state_file,
        producer_id=values.get("VOLC_TRYON_PRODUCER_ID", "smart-fitting-mirror-poc"),
    )


def image_base64(path: Path) -> str:
    return base64.b64encode(path.read_bytes()).decode("ascii")


def build_submit_payload(model_image: Path, garment_image: Path) -> dict[str, Any]:
    """Build one upper-garment request with local Base64 input, never public URLs."""
    return {
        "req_key": REQ_KEY,
        "req_image_store_type": 0,
        "binary_data_base64": [image_base64(model_image), image_base64(garment_image)],
        "garment": {"data": [{"type": "upper"}]},
        "inference_config": {"keep_head": True, "keep_lower": True, "do_sr": False},
    }


def _sign(key: bytes, message: str) -> bytes:
    return hmac.new(key, message.encode("utf-8"), hashlib.sha256).digest()


def signed_headers(access_key_id: str, secret_access_key: str, query: dict[str, str], payload: bytes) -> dict[str, str]:
    """Implement the provider's documented HMAC-SHA256 request signing."""
    now = dt.datetime.now(dt.timezone.utc)
    timestamp = now.strftime("%Y%m%dT%H%M%SZ")
    date = now.strftime("%Y%m%d")
    canonical_query = urllib.parse.urlencode(sorted(query.items()), quote_via=urllib.parse.quote, safe="~")
    payload_hash = hashlib.sha256(payload).hexdigest()
    signed = "content-type;host;x-content-sha256;x-date"
    canonical_headers = (
        "content-type:application/json\n"
        f"host:{HOST}\n"
        f"x-content-sha256:{payload_hash}\n"
        f"x-date:{timestamp}\n"
    )
    canonical_request = f"POST\n/\n{canonical_query}\n{canonical_headers}\n{signed}\n{payload_hash}"
    scope = f"{date}/{REGION}/{SERVICE}/request"
    string_to_sign = f"HMAC-SHA256\n{timestamp}\n{scope}\n{hashlib.sha256(canonical_request.encode('utf-8')).hexdigest()}"
    key_date = _sign(secret_access_key.encode("utf-8"), date)
    key_region = _sign(key_date, REGION)
    key_service = _sign(key_region, SERVICE)
    signing_key = _sign(key_service, "request")
    signature = hmac.new(signing_key, string_to_sign.encode("utf-8"), hashlib.sha256).hexdigest()
    authorization = f"HMAC-SHA256 Credential={access_key_id}/{scope}, SignedHeaders={signed}, Signature={signature}"
    return {
        "Content-Type": "application/json",
        "Host": HOST,
        "X-Date": timestamp,
        "X-Content-Sha256": payload_hash,
        "Authorization": authorization,
    }


def post_json(settings: Settings, action: str, body: dict[str, Any], *, timeout: int = 30) -> dict[str, Any]:
    query = {"Action": action, "Version": VERSION}
    payload = json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    url = f"https://{HOST}/?{urllib.parse.urlencode(query)}"
    request = urllib.request.Request(
        url,
        data=payload,
        headers=signed_headers(settings.access_key_id, settings.secret_access_key, query, payload),
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read()
    except urllib.error.HTTPError as error:
        raw = error.read()
        try:
            detail = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            detail = {}
        raise RemoteCallError(f"Volcengine HTTP {error.code}: {detail.get('code', 'unknown')} {detail.get('message', 'request failed')}") from error
    except urllib.error.URLError as error:
        raise RemoteCallError(f"Network request failed: {error.reason}") from error
    try:
        return json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise RemoteCallError("Volcengine returned a non-JSON response.") from error


def request_metadata(producer_id: str) -> str:
    return json.dumps(
        {
            "return_url": True,
            "logo_info": {"add_logo": True, "position": 0, "language": 0, "opacity": 0.3},
            "aigc_meta": {"producer_id": producer_id},
        },
        ensure_ascii=False,
        separators=(",", ":"),
    )


def retrieve_result(settings: Settings, task_id: str) -> tuple[str, list[str]]:
    reply = post_json(
        settings,
        "CVGetResult",
        {"req_key": REQ_KEY, "task_id": task_id, "req_json": request_metadata(settings.producer_id)},
    )
    if reply.get("code") != 10000:
        raise RemoteCallError(f"Result query failed: {reply.get('code')} {reply.get('message', 'unknown error')}")
    data = reply.get("data") or {}
    return str(data.get("status", "unknown")), [str(url) for url in data.get("image_urls") or []]


def save_first_result(url: str, output_dir: Path) -> Path:
    output_dir.mkdir(parents=True, exist_ok=True)
    target = output_dir / f"volc-tryon-{dt.datetime.now().strftime('%Y%m%d-%H%M%S')}.png"
    with urllib.request.urlopen(url, timeout=30) as response:
        target.write_bytes(response.read())
    return target


def write_task_state(state_file: Path, state: dict[str, Any]) -> None:
    """Persist non-secret task metadata outside Git for safe later resumption."""
    state_file.parent.mkdir(parents=True, exist_ok=True)
    temporary = state_file.with_suffix(state_file.suffix + ".tmp")
    temporary.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")
    temporary.replace(state_file)


def read_task_state(state_file: Path) -> dict[str, Any]:
    if not state_file.is_file():
        raise ConfigurationError(f"No saved Volcengine task state exists at: {state_file}")
    try:
        state = json.loads(state_file.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ConfigurationError(f"Saved task state is unreadable: {state_file}") from error
    if not isinstance(state, dict) or not isinstance(state.get("task_id"), str) or not state["task_id"]:
        raise ConfigurationError(f"Saved task state has no usable task_id: {state_file}")
    return state


def submit_task(settings: Settings) -> dict[str, Any]:
    submitted = post_json(settings, "CVSubmitTask", build_submit_payload(settings.model_image, settings.garment_image))
    if submitted.get("code") != 10000 or not (submitted.get("data") or {}).get("task_id"):
        raise RemoteCallError(f"Submit failed: {submitted.get('code')} {submitted.get('message', 'unknown error')}")
    state = {
        "task_id": str(submitted["data"]["task_id"]),
        "submitted_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        "status": "submitted",
        "model_image": settings.model_image.name,
        "garment_image": settings.garment_image.name,
    }
    write_task_state(settings.state_file, state)
    return state


def elapsed_since(timestamp: str) -> str:
    try:
        started = dt.datetime.fromisoformat(timestamp)
    except ValueError:
        return "unknown"
    if started.tzinfo is None:
        started = started.replace(tzinfo=dt.timezone.utc)
    return str(dt.datetime.now(dt.timezone.utc) - started).split(".")[0]


def local_summary(settings: Settings) -> str:
    return (
        "Local preflight passed. No network request was made.\n"
        f"- model image: {settings.model_image.name} ({settings.model_image.stat().st_size} bytes)\n"
        f"- garment image: {settings.garment_image.name} ({settings.garment_image.stat().st_size} bytes)\n"
        f"- result directory: {settings.output_dir}\n"
        "Run again with --execute only after confirming the two local images may be sent to Volcengine."
    )


def wait_for_result(settings: Settings, state: dict[str, Any], *, poll_interval: float, max_wait_seconds: int) -> int:
    task_id = str(state["task_id"])
    deadline = time.monotonic() + max_wait_seconds
    while time.monotonic() < deadline:
        time.sleep(poll_interval)
        status, urls = retrieve_result(settings, task_id)
        state["status"] = status
        state["last_checked_at"] = dt.datetime.now(dt.timezone.utc).isoformat()
        write_task_state(settings.state_file, state)
        if status in {"in_queue", "generating"}:
            print(f"Task is {status}; continuing to wait.", flush=True)
            continue
        if status == "done" and urls:
            target = save_first_result(urls[0], settings.output_dir)
            state["status"] = "done"
            state["completed_at"] = dt.datetime.now(dt.timezone.utc).isoformat()
            state["result_file"] = str(target)
            write_task_state(settings.state_file, state)
            print(f"Success. Result saved locally outside Git: {target}", flush=True)
            print(f"Elapsed since submission: {elapsed_since(str(state['submitted_at']))}", flush=True)
            return 0
        state["status"] = status
        write_task_state(settings.state_file, state)
        raise RemoteCallError(f"Task finished without a downloadable result: {status}")
    raise RemoteCallError(
        f"Timed out after {max_wait_seconds} seconds; task ID remains saved at {settings.state_file} and can be resumed without resubmitting."
    )


def run(settings: Settings, *, execute: bool, resume_last: bool, poll_interval: float, max_wait_seconds: int) -> int:
    print(local_summary(settings), flush=True)
    if resume_last:
        state = read_task_state(settings.state_file)
        print("Resuming the saved Volcengine task; no new image submission will be made.", flush=True)
        return wait_for_result(settings, state, poll_interval=poll_interval, max_wait_seconds=max_wait_seconds)
    if not execute:
        return 0
    print("Submitting one real try-on task to Volcengine (no secret values will be printed)...", flush=True)
    state = submit_task(settings)
    print(f"Task ID and start time were saved privately: {settings.state_file}", flush=True)
    return wait_for_result(settings, state, poll_interval=poll_interval, max_wait_seconds=max_wait_seconds)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Private Volcengine Image Dressing V2 smoke test")
    parser.add_argument("--env-file", type=Path, default=DEFAULT_ENV_FILE, help="Git-ignored local configuration file")
    parser.add_argument("--execute", action="store_true", help="Send the configured images to Volcengine exactly once")
    parser.add_argument("--resume-last", action="store_true", help="Query the saved task without submitting images again")
    parser.add_argument("--poll-interval", type=float, default=3.0)
    parser.add_argument("--max-wait-seconds", type=int, default=180)
    args = parser.parse_args(argv)
    try:
        if args.execute and args.resume_last:
            raise ConfigurationError("Use either --execute or --resume-last, not both.")
        return run(
            settings_from_env(args.env_file),
            execute=args.execute,
            resume_last=args.resume_last,
            poll_interval=args.poll_interval,
            max_wait_seconds=args.max_wait_seconds,
        )
    except (ConfigurationError, RemoteCallError) as error:
        print(f"ERROR: {error}", file=sys.stderr, flush=True)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
