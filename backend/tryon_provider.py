"""Real virtual try-on provider boundary used by the public backend service.

The service owns sessions, idempotency and result authorization.  A provider
only receives normalized image bytes plus a local garment asset and returns a
normalized PNG.  Credentials and signed URLs never cross this boundary.
"""

from __future__ import annotations

import io
import logging
import tempfile
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, replace
from pathlib import Path
from typing import Protocol

from PIL import Image, UnidentifiedImageError
from alibabacloud_aidge20260428 import models as aidge_models

from backend.aliyun_tryon_smoke import (
    Settings as AidgeSettings,
    build_client as build_aidge_client,
    build_request,
    is_public_http_url,
    result_image_url,
    settings_from_env as aidge_settings_from_env,
)
from backend.cos_storage import (
    CosSettings,
    build_client as build_cos_client,
    delete_tryon_input,
    settings_from_env as cos_settings_from_env,
    upload_tryon_input,
)


MAX_RESULT_BYTES = 16 * 1024 * 1024
MAX_RESULT_PIXELS = 16_000_000
PENDING_STATES = {"PENDING", "PROCESSING", "IN_PROGRESS", "RUNNING", "QUEUED", "SUBMITTED"}
LOGGER = logging.getLogger(__name__)


class ProviderFailure(RuntimeError):
    """Safe, user-facing provider failure without credentials or signed URLs."""

    def __init__(self, code: str, message: str, *, retryable: bool = False):
        super().__init__(message)
        self.code = code
        self.message = message
        self.retryable = retryable


@dataclass(frozen=True)
class ProviderResult:
    data: bytes
    width: int
    height: int


class TryOnProvider(Protocol):
    name: str

    def generate(self, model_image: bytes, garment_image: Path, cloth_type: str) -> ProviderResult: ...


def download_result(url: str) -> ProviderResult:
    if not is_public_http_url(url):
        raise ProviderFailure("PROVIDER_INVALID_RESULT", "试衣服务返回了无效的结果地址")
    try:
        with urllib.request.urlopen(url, timeout=60) as response:
            data = response.read(MAX_RESULT_BYTES + 1)
    except (urllib.error.URLError, TimeoutError, OSError) as error:
        raise ProviderFailure("PROVIDER_RESULT_DOWNLOAD_FAILED", "试衣结果下载失败，请稍后重试", retryable=True) from error
    if len(data) > MAX_RESULT_BYTES:
        raise ProviderFailure("PROVIDER_INVALID_RESULT", "试衣结果图片过大")
    try:
        with Image.open(io.BytesIO(data)) as source:
            if source.width * source.height > MAX_RESULT_PIXELS:
                raise ProviderFailure("PROVIDER_INVALID_RESULT", "试衣结果图片像素过多")
            with source.convert("RGB") as normalized:
                output = io.BytesIO()
                normalized.save(output, "PNG")
                return ProviderResult(output.getvalue(), normalized.width, normalized.height)
    except ProviderFailure:
        raise
    except (UnidentifiedImageError, OSError, ValueError, SyntaxError) as error:
        raise ProviderFailure("PROVIDER_INVALID_RESULT", "试衣服务返回的结果不是有效图片") from error


class AidgeCosProvider:
    """Upload private inputs to COS, call Aidge, then always clean COS inputs."""

    name = "aidge-cos"

    def __init__(
        self,
        cos_settings: CosSettings,
        aidge_settings: AidgeSettings,
        *,
        poll_interval: float = 5.0,
        max_wait_seconds: int = 120,
        cos_client=None,
        aidge_client=None,
    ):
        if poll_interval < 1:
            raise ValueError("poll_interval must be at least 1 second")
        if max_wait_seconds < 1:
            raise ValueError("max_wait_seconds must be positive")
        self.cos_settings = cos_settings
        self.aidge_settings = aidge_settings
        self.poll_interval = poll_interval
        self.max_wait_seconds = max_wait_seconds
        self.cos_client = cos_client or build_cos_client(cos_settings)
        self.aidge_client = aidge_client or build_aidge_client(aidge_settings)

    @classmethod
    def from_env(cls, cos_env: Path, aliyun_env: Path) -> "AidgeCosProvider":
        # Placeholder URLs satisfy local validation only. Every job replaces
        # them in memory with fresh COS signed URLs before calling Aidge.
        aidge = aidge_settings_from_env(
            aliyun_env,
            model_image_url="https://provider.invalid/model.png",
            garment_image_url="https://provider.invalid/garment.png",
        )
        return cls(cos_settings_from_env(cos_env), aidge)

    def generate(self, model_image: bytes, garment_image: Path, cloth_type: str) -> ProviderResult:
        uploaded_keys: list[str] = []
        try:
            with tempfile.TemporaryDirectory(prefix="smartmirror-aidge-") as temporary:
                model_path = Path(temporary) / "model.png"
                model_path.write_bytes(model_image)
                model = upload_tryon_input(self.cos_client, self.cos_settings, model_path, "model")
                uploaded_keys.append(model.object_key)
                garment = upload_tryon_input(self.cos_client, self.cos_settings, garment_image, "garment")
                uploaded_keys.append(garment.object_key)
                settings = replace(
                    self.aidge_settings,
                    model_image_url=model.download_url,
                    garment_image_url=garment.download_url,
                    cloth_type=cloth_type,
                )
                try:
                    response = self.aidge_client.virtual_try_on(build_request(settings))
                except Exception as error:
                    raise ProviderFailure("PROVIDER_UNAVAILABLE", "虚拟试衣服务提交失败，请稍后重试", retryable=True) from error
                body = response.body
                task_id = getattr(getattr(body, "data", None), "task_id", None)
                if not getattr(body, "success", False) or not task_id:
                    raise ProviderFailure("PROVIDER_REJECTED", "虚拟试衣服务未接受任务")

                deadline = time.monotonic() + self.max_wait_seconds
                last_query_failed = False
                while time.monotonic() < deadline:
                    time.sleep(self.poll_interval)
                    try:
                        response = self.aidge_client.query_async_task_result(
                            aidge_models.QueryAsyncTaskResultRequest(task_id=str(task_id))
                        )
                    except Exception:
                        # A transient query failure does not prove the accepted
                        # Aidge task stopped. Keep its COS inputs alive and ask
                        # again until the total task deadline.
                        last_query_failed = True
                        continue
                    last_query_failed = False
                    body = response.body
                    data = getattr(body, "data", None)
                    status = str(getattr(data, "status", "UNKNOWN")).upper()
                    if status in PENDING_STATES:
                        continue
                    if getattr(body, "success", False) and status == "COMPLETED":
                        try:
                            return download_result(result_image_url(getattr(data, "result", None)))
                        except ProviderFailure:
                            raise
                        except Exception as error:
                            raise ProviderFailure("PROVIDER_INVALID_RESULT", "虚拟试衣结果无法解析") from error
                    raise ProviderFailure("PROVIDER_FAILED", "虚拟试衣生成失败，请重新拍摄后重试")
                if last_query_failed:
                    raise ProviderFailure("PROVIDER_UNAVAILABLE", "虚拟试衣任务查询持续失败，请稍后重试", retryable=True)
                raise ProviderFailure("GENERATION_TIMEOUT", "虚拟试衣超过等待时限", retryable=True)
        finally:
            # Aidge has no cancellation operation in the current integration.
            # The worker therefore waits for a terminal/timeout outcome before
            # deleting inputs; the one-day COS lifecycle is the crash fallback.
            cleanup_failures = 0
            for object_key in uploaded_keys:
                try:
                    delete_tryon_input(self.cos_client, self.cos_settings, object_key)
                except Exception:
                    cleanup_failures += 1
            if cleanup_failures:
                LOGGER.warning(
                    "%d temporary COS input(s) could not be deleted; lifecycle cleanup remains active",
                    cleanup_failures,
                )
