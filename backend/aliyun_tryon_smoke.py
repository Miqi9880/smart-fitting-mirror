"""Private, opt-in smoke test for Alibaba Cloud Aidge virtual try-on.

The default command only validates a Git-ignored local configuration file. It
does not send an API request, fetch either image URL, or create an output file.
Pass --execute only after the two public image URLs have been explicitly
approved for transmission to Alibaba Cloud.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from alibabacloud_aidge20260428 import models as aidge_models
from alibabacloud_aidge20260428.client import Client as AidgeClient
from alibabacloud_tea_openapi.models import Config
from darabonba.policy.retry import RetryOptions

from backend.volc_tryon_smoke import ConfigurationError, load_dotenv, resolve_private_path


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_ENV_FILE = REPO_ROOT / ".env.aliyun"
DEFAULT_OUTPUT_DIR = REPO_ROOT.parent / "private" / "aliyun-tryon-results"
DEFAULT_STATE_FILE = REPO_ROOT.parent / "private" / "aliyun-tryon-last-task.json"
DEFAULT_ENDPOINT = "aidge.cn-beijing.aliyuncs.com"
DEFAULT_REGION = "cn-beijing"
SUPPORTED_CLOTH_TYPES = {"tops", "bottoms", "dresses", "tops_and_bottoms", "shoes", "hats"}
SUPPORTED_RESOLUTIONS = {"1K", "2K"}
RESUME_MODEL_URL = "https://resume.invalid/model.png"
RESUME_GARMENT_URL = "https://resume.invalid/garment.png"


class RemoteCallError(RuntimeError):
    """A concise, non-secret description of an upstream API failure."""


@dataclass(frozen=True)
class Settings:
    access_key_id: str = field(repr=False)
    access_key_secret: str = field(repr=False)
    endpoint: str
    region: str
    model_image_url: str = field(repr=False)
    garment_image_url: str = field(repr=False)
    cloth_type: str
    resolution: str
    output_dir: Path
    state_file: Path


def is_public_http_url(value: str) -> bool:
    """Require an absolute HTTP(S) URL; local paths and data URLs are rejected."""
    parsed = urllib.parse.urlsplit(value)
    return parsed.scheme in {"http", "https"} and bool(parsed.hostname) and not parsed.username and not parsed.password


def settings_from_env(
    path: Path,
    *,
    model_image_url: str | None = None,
    garment_image_url: str | None = None,
) -> Settings:
    values = {**os.environ, **load_dotenv(path)}
    required = ["ALIYUN_ACCESS_KEY_ID", "ALIYUN_ACCESS_KEY_SECRET"]
    missing = [name for name in required if not values.get(name)]
    if missing:
        raise ConfigurationError("Missing required local config values: " + ", ".join(missing))
    resolved_model_url = model_image_url or values.get("ALIYUN_TRYON_MODEL_IMAGE_URL", "")
    resolved_garment_url = garment_image_url or values.get("ALIYUN_TRYON_GARMENT_IMAGE_URL", "")
    missing_urls = [
        name
        for name, value in (
            ("ALIYUN_TRYON_MODEL_IMAGE_URL", resolved_model_url),
            ("ALIYUN_TRYON_GARMENT_IMAGE_URL", resolved_garment_url),
        )
        if not value
    ]
    if missing_urls:
        raise ConfigurationError("Missing required local config values: " + ", ".join(missing_urls))
    for label, url in (("model image URL", resolved_model_url), ("garment image URL", resolved_garment_url)):
        if not is_public_http_url(url):
            raise ConfigurationError(f"Configured {label} must be an absolute public HTTP(S) URL.")
    cloth_type = values.get("ALIYUN_TRYON_CLOTH_TYPE", "tops")
    if cloth_type not in SUPPORTED_CLOTH_TYPES:
        raise ConfigurationError("ALIYUN_TRYON_CLOTH_TYPE must be one of: " + ", ".join(sorted(SUPPORTED_CLOTH_TYPES)))
    resolution = values.get("ALIYUN_TRYON_RESOLUTION", "1K")
    if resolution not in SUPPORTED_RESOLUTIONS:
        raise ConfigurationError("ALIYUN_TRYON_RESOLUTION must be one of: " + ", ".join(sorted(SUPPORTED_RESOLUTIONS)))
    output_dir = resolve_private_path(values.get("ALIYUN_TRYON_OUTPUT_DIR", str(DEFAULT_OUTPUT_DIR)), env_file=path)
    state_file = resolve_private_path(values.get("ALIYUN_TRYON_STATE_FILE", str(DEFAULT_STATE_FILE)), env_file=path)
    return Settings(
        access_key_id=values["ALIYUN_ACCESS_KEY_ID"],
        access_key_secret=values["ALIYUN_ACCESS_KEY_SECRET"],
        endpoint=values.get("ALIYUN_AIDGE_ENDPOINT", DEFAULT_ENDPOINT),
        region=values.get("ALIYUN_AIDGE_REGION", DEFAULT_REGION),
        model_image_url=resolved_model_url,
        garment_image_url=resolved_garment_url,
        cloth_type=cloth_type,
        resolution=resolution,
        output_dir=output_dir,
        state_file=state_file,
    )


def build_request(settings: Settings) -> aidge_models.VirtualTryOnRequest:
    """Build the documented asynchronous VirtualTryOn request."""
    return aidge_models.VirtualTryOnRequest(
        model_image_url=settings.model_image_url,
        cloth_image_url=settings.garment_image_url,
        cloth_type=settings.cloth_type,
        resolution=settings.resolution,
    )


def build_client(settings: Settings) -> AidgeClient:
    config = Config(access_key_id=settings.access_key_id, access_key_secret=settings.access_key_secret)
    config.endpoint = settings.endpoint
    config.region_id = settings.region
    # A submit response may be lost after the paid task was accepted. Never let
    # the SDK silently repeat submission; only task queries are retried by us.
    config.retry_options = RetryOptions(retryable=False)
    return AidgeClient(config)


def save_result(image_url: str, output_dir: Path) -> Path:
    if not is_public_http_url(image_url):
        raise RemoteCallError("Aidge returned an invalid result image URL.")
    output_dir.mkdir(parents=True, exist_ok=True)
    target = output_dir / f"aliyun-tryon-{dt.datetime.now().strftime('%Y%m%d-%H%M%S')}.png"
    try:
        with urllib.request.urlopen(image_url, timeout=60) as response:
            target.write_bytes(response.read())
    except urllib.error.URLError as error:
        raise RemoteCallError(f"Could not download the result image: {error.reason}") from error
    return target


def write_task_state(state_file: Path, state: dict[str, Any]) -> None:
    """Persist non-secret task metadata outside Git for safe later resumption."""
    state_file.parent.mkdir(parents=True, exist_ok=True)
    temporary = state_file.with_suffix(state_file.suffix + ".tmp")
    temporary.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")
    temporary.replace(state_file)


def read_task_state(state_file: Path) -> dict[str, Any]:
    if not state_file.is_file():
        raise ConfigurationError(f"No saved Aidge task state exists at: {state_file}")
    try:
        state = json.loads(state_file.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ConfigurationError(f"Saved task state is unreadable: {state_file}") from error
    if not isinstance(state, dict) or not isinstance(state.get("task_id"), str) or not state["task_id"]:
        raise ConfigurationError(f"Saved task state has no usable task_id: {state_file}")
    return state


def elapsed_since(timestamp: str) -> str:
    try:
        started = dt.datetime.fromisoformat(timestamp)
    except ValueError:
        return "unknown"
    if started.tzinfo is None:
        started = started.replace(tzinfo=dt.timezone.utc)
    return str(dt.datetime.now(dt.timezone.utc) - started).split(".")[0]


def result_image_url(result: str | dict[str, Any] | None) -> str:
    if isinstance(result, str):
        try:
            result = json.loads(result)
        except json.JSONDecodeError as error:
            raise RemoteCallError("Aidge returned an unreadable task result.") from error
    if not isinstance(result, dict):
        raise RemoteCallError("Aidge completed without a result image URL.")
    for key in ("ImageUrl", "imageUrl", "url"):
        if isinstance(result.get(key), str) and result[key]:
            return result[key]
    raise RemoteCallError("Aidge completed without a result image URL.")


def local_summary(settings: Settings) -> str:
    return (
        "Local preflight passed. No API request was made and neither image URL was fetched.\n"
        f"- endpoint: {settings.endpoint} ({settings.region})\n"
        f"- garment type / resolution: {settings.cloth_type} / {settings.resolution}\n"
        f"- result directory: {settings.output_dir}\n"
        "Run again with --execute only after confirming both public image URLs may be sent to Alibaba Cloud."
    )


def submit_task(settings: Settings) -> dict[str, Any]:
    try:
        response = build_client(settings).virtual_try_on(build_request(settings))
    except Exception as error:
        raise RemoteCallError(f"Aidge request failed: {type(error).__name__}") from error
    body = response.body
    task_id = getattr(getattr(body, "data", None), "task_id", None)
    if not getattr(body, "success", False) or not task_id:
        raise RemoteCallError(f"Aidge did not return a task ID: {getattr(body, 'code', 'unknown')} {getattr(body, 'message', '')}".strip())
    state = {
        "task_id": str(task_id),
        "submitted_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        "status": "submitted",
        "model_image_url_host": urllib.parse.urlsplit(settings.model_image_url).hostname,
        "garment_image_url_host": urllib.parse.urlsplit(settings.garment_image_url).hostname,
    }
    write_task_state(settings.state_file, state)
    return state


def wait_for_result(settings: Settings, state: dict[str, Any], *, poll_interval: float, max_wait_seconds: int) -> int:
    task_id = str(state["task_id"])
    deadline = time.monotonic() + max_wait_seconds
    while time.monotonic() < deadline:
        time.sleep(poll_interval)
        try:
            response = build_client(settings).query_async_task_result(aidge_models.QueryAsyncTaskResultRequest(task_id=task_id))
        except Exception as error:
            raise RemoteCallError(f"Aidge task query failed: {type(error).__name__}") from error
        body = response.body
        data = getattr(body, "data", None)
        status = str(getattr(data, "status", "unknown")).upper()
        state["status"] = status
        state["last_checked_at"] = dt.datetime.now(dt.timezone.utc).isoformat()
        write_task_state(settings.state_file, state)
        if status in {"PENDING", "PROCESSING", "IN_PROGRESS", "RUNNING", "QUEUED", "SUBMITTED"}:
            print(f"Task is {status}; continuing to wait.", flush=True)
            continue
        if getattr(body, "success", False) and status == "COMPLETED":
            target = save_result(result_image_url(getattr(data, "result", None)), settings.output_dir)
            state["status"] = "COMPLETED"
            state["completed_at"] = dt.datetime.now(dt.timezone.utc).isoformat()
            state["result_file"] = str(target)
            write_task_state(settings.state_file, state)
            print(f"Success. Result saved locally outside Git: {target}", flush=True)
            print(f"Elapsed since submission: {elapsed_since(str(state['submitted_at']))}", flush=True)
            return 0
        raise RemoteCallError(f"Aidge task did not complete successfully: {status} {getattr(body, 'message', '')}".strip())
    raise RemoteCallError(
        f"Timed out after {max_wait_seconds} seconds; task ID remains saved at {settings.state_file} and can be resumed without resubmitting."
    )


def run(settings: Settings, *, execute: bool, resume_last: bool, poll_interval: float, max_wait_seconds: int) -> int:
    print(local_summary(settings), flush=True)
    if resume_last:
        state = read_task_state(settings.state_file)
        print("Resuming the saved Aidge task; no new image submission will be made.", flush=True)
        return wait_for_result(settings, state, poll_interval=poll_interval, max_wait_seconds=max_wait_seconds)
    if not execute:
        return 0
    print("Submitting one real virtual try-on request to Alibaba Cloud (no secret values will be printed)...", flush=True)
    state = submit_task(settings)
    print(f"Task ID and start time were saved privately: {settings.state_file}", flush=True)
    return wait_for_result(settings, state, poll_interval=poll_interval, max_wait_seconds=max_wait_seconds)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Private Alibaba Cloud Aidge virtual try-on smoke test")
    parser.add_argument("--env-file", type=Path, default=DEFAULT_ENV_FILE, help="Git-ignored local configuration file")
    parser.add_argument("--execute", action="store_true", help="Send the configured public image URLs to Alibaba Cloud exactly once")
    parser.add_argument("--resume-last", action="store_true", help="Query the saved task without submitting images again")
    parser.add_argument("--poll-interval", type=float, default=5.0)
    parser.add_argument("--max-wait-seconds", type=int, default=180)
    args = parser.parse_args(argv)
    try:
        if args.execute and args.resume_last:
            raise ConfigurationError("Use either --execute or --resume-last, not both.")
        settings = settings_from_env(
            args.env_file,
            model_image_url=RESUME_MODEL_URL if args.resume_last else None,
            garment_image_url=RESUME_GARMENT_URL if args.resume_last else None,
        )
        return run(
            settings,
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
