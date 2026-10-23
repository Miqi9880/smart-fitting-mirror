"""Private COS input storage for external virtual try-on.

This module performs no network operation during import or configuration
loading. Call ``upload_tryon_input`` explicitly to upload one approved local
image and create a short-lived, signed HTTPS download URL for Aidge.
"""

from __future__ import annotations

import mimetypes
import os
import re
import uuid
from dataclasses import dataclass, field
from pathlib import Path, PurePosixPath
from typing import Any, Protocol

from backend.volc_tryon_smoke import ConfigurationError, load_dotenv


DEFAULT_REGION = "ap-guangzhou"
DEFAULT_BUCKET = "smartmirror-tryon-01-1514304052"
DEFAULT_PREFIX = "tryon-input/"
DEFAULT_PRESIGN_SECONDS = 1800
MIN_PRESIGN_SECONDS = 60
MAX_PRESIGN_SECONDS = 3600
ALLOWED_ROLES = {"model", "garment"}
BUCKET_PATTERN = re.compile(r"^[a-z0-9][a-z0-9-]{0,62}-[0-9]+$")
REGION_PATTERN = re.compile(r"^[a-z]+(?:-[a-z0-9]+)+$")


class CosClient(Protocol):
    def put_object_from_local_file(self, **kwargs: Any) -> dict[str, Any]: ...

    def head_object(self, **kwargs: Any) -> dict[str, Any]: ...

    def get_presigned_download_url(self, **kwargs: Any) -> str: ...

    def delete_object(self, **kwargs: Any) -> dict[str, Any]: ...


@dataclass(frozen=True)
class CosSettings:
    secret_id: str = field(repr=False)
    secret_key: str = field(repr=False)
    region: str = DEFAULT_REGION
    bucket: str = DEFAULT_BUCKET
    prefix: str = DEFAULT_PREFIX
    presign_seconds: int = DEFAULT_PRESIGN_SECONDS
    session_token: str | None = field(default=None, repr=False)


@dataclass(frozen=True)
class UploadedInput:
    object_key: str
    download_url: str = field(repr=False)
    expires_seconds: int
    etag: str | None = None


def settings_from_env(path: Path) -> CosSettings:
    """Load and validate the Git-ignored COS configuration without logging it."""
    values = {**os.environ, **load_dotenv(path)}
    missing = [name for name in ("COS_SECRET_ID", "COS_SECRET_KEY") if not values.get(name)]
    if missing:
        raise ConfigurationError("Missing required local config values: " + ", ".join(missing))

    region = values.get("COS_REGION", DEFAULT_REGION)
    bucket = values.get("COS_BUCKET", DEFAULT_BUCKET)
    prefix = values.get("COS_INPUT_PREFIX", DEFAULT_PREFIX)
    if not REGION_PATTERN.fullmatch(region):
        raise ConfigurationError("COS_REGION is not a valid COS region name.")
    if not BUCKET_PATTERN.fullmatch(bucket):
        raise ConfigurationError("COS_BUCKET must include the bucket name and numeric APPID suffix.")
    if prefix != DEFAULT_PREFIX:
        raise ConfigurationError(f"COS_INPUT_PREFIX must remain {DEFAULT_PREFIX!r} for the current CAM policy.")

    raw_expiry = values.get("COS_PRESIGN_EXPIRES_SECONDS", str(DEFAULT_PRESIGN_SECONDS))
    try:
        presign_seconds = int(raw_expiry)
    except ValueError as error:
        raise ConfigurationError("COS_PRESIGN_EXPIRES_SECONDS must be an integer.") from error
    if not MIN_PRESIGN_SECONDS <= presign_seconds <= MAX_PRESIGN_SECONDS:
        raise ConfigurationError(
            f"COS_PRESIGN_EXPIRES_SECONDS must be between {MIN_PRESIGN_SECONDS} and {MAX_PRESIGN_SECONDS}."
        )

    return CosSettings(
        secret_id=values["COS_SECRET_ID"],
        secret_key=values["COS_SECRET_KEY"],
        region=region,
        bucket=bucket,
        prefix=prefix,
        presign_seconds=presign_seconds,
        session_token=values.get("COS_SESSION_TOKEN") or None,
    )


def build_client(settings: CosSettings) -> CosClient:
    """Create the official COS SDK client; this does not make a request."""
    from qcloud_cos import CosConfig, CosS3Client

    config = CosConfig(
        Region=settings.region,
        SecretId=settings.secret_id,
        SecretKey=settings.secret_key,
        Token=settings.session_token,
        Scheme="https",
    )
    return CosS3Client(config)


def build_object_key(local_path: Path, role: str, *, unique_id: str | None = None) -> str:
    """Create an opaque object key under the only CAM-authorized prefix."""
    if role not in ALLOWED_ROLES:
        raise ValueError("role must be 'model' or 'garment'.")
    suffix = local_path.suffix.lower()
    if suffix not in {".jpg", ".jpeg", ".png", ".webp"}:
        raise ValueError("try-on inputs must be JPEG, PNG, or WebP images.")
    identifier = unique_id or uuid.uuid4().hex
    if not re.fullmatch(r"[A-Za-z0-9_-]+", identifier):
        raise ValueError("unique_id contains unsupported characters.")
    return f"{DEFAULT_PREFIX}{role}/{identifier}{suffix}"


def _require_authorized_key(settings: CosSettings, object_key: str) -> None:
    path = PurePosixPath(object_key)
    if object_key.startswith("/") or ".." in path.parts or not object_key.startswith(settings.prefix):
        raise ValueError(f"COS object key must stay under {settings.prefix!r}.")


def upload_tryon_input(
    client: CosClient,
    settings: CosSettings,
    local_path: Path,
    role: str,
    *,
    unique_id: str | None = None,
) -> UploadedInput:
    """Upload one image, verify it exists, then issue a short-lived GET URL."""
    source = local_path.resolve()
    if not source.is_file():
        raise FileNotFoundError(f"Try-on input image does not exist: {source}")
    object_key = build_object_key(source, role, unique_id=unique_id)
    _require_authorized_key(settings, object_key)
    content_type = mimetypes.guess_type(source.name)[0] or "application/octet-stream"
    response = client.put_object_from_local_file(
        Bucket=settings.bucket,
        Key=object_key,
        LocalFilePath=str(source),
        ContentType=content_type,
    )
    client.head_object(Bucket=settings.bucket, Key=object_key)
    url = client.get_presigned_download_url(
        Bucket=settings.bucket,
        Key=object_key,
        Expired=settings.presign_seconds,
    )
    if not isinstance(url, str) or not url.startswith("https://"):
        raise RuntimeError("COS SDK did not return a signed HTTPS URL.")
    return UploadedInput(
        object_key=object_key,
        download_url=url,
        expires_seconds=settings.presign_seconds,
        etag=response.get("ETag") if isinstance(response, dict) else None,
    )


def delete_tryon_input(client: CosClient, settings: CosSettings, object_key: str) -> None:
    """Delete only an object covered by the configured try-on input prefix."""
    _require_authorized_key(settings, object_key)
    client.delete_object(Bucket=settings.bucket, Key=object_key)
