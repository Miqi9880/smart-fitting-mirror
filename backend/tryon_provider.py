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
from darabonba.runtime import RuntimeOptions

from backend.provider_control import GenerationBudget, ProviderFailure
from backend.provider_images import MAX_SOURCE_BYTES, prepare_input

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


@dataclass(frozen=True)
class ProviderResult:
    data: bytes
    width: int
    height: int


class TryOnProvider(Protocol):
    name: str

    def generate(self, model_image: bytes, garment_image: Path, cloth_type: str, *, budget: GenerationBudget) -> ProviderResult: ...


def download_result(url: str, *, budget: GenerationBudget | None = None) -> ProviderResult:
    budget = budget or GenerationBudget(60)
    if not is_public_http_url(url):
        raise ProviderFailure("PROVIDER_INVALID_RESULT", "试衣服务返回了无效的结果地址")
    try:
        with urllib.request.urlopen(url, timeout=budget.socket_timeout()) as response:
            chunks = []
            length = 0
            while True:
                budget.check()
                block = response.read1(min(64 * 1024, MAX_RESULT_BYTES + 1 - length))
                if not block:
                    break
                chunks.append(block); length += len(block)
                if length > MAX_RESULT_BYTES:
                    raise ProviderFailure("PROVIDER_INVALID_RESULT", "试衣结果图片过大")
            data = b"".join(chunks)
    except (urllib.error.URLError, TimeoutError, OSError) as error:
        budget.check()
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
                budget.check()
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

    def generate(self, model_image: bytes, garment_image: Path, cloth_type: str, *, budget: GenerationBudget | None = None) -> ProviderResult:
        budget = budget.capped(self.max_wait_seconds) if budget else GenerationBudget(self.max_wait_seconds, clock=time.monotonic)
        uploaded_keys: list[str] = []
        try:
            with tempfile.TemporaryDirectory(prefix="smartmirror-aidge-") as temporary:
                model_path = Path(temporary) / "model.jpg"
                garment_path = Path(temporary) / "garment.jpg"
                prepare_input(model_image, model_path, "人物", budget)
                if garment_image.stat().st_size > MAX_SOURCE_BYTES:
                    raise ProviderFailure("PROVIDER_INVALID_INPUT", "服装图片过大，无法用于生成")
                prepare_input(garment_image.read_bytes(), garment_path, "服装", budget)
                model = upload_tryon_input(self.cos_client, self.cos_settings, model_path, "model", budget=budget)
                uploaded_keys.append(model.object_key)
                garment = upload_tryon_input(self.cos_client, self.cos_settings, garment_path, "garment", budget=budget)
                uploaded_keys.append(garment.object_key)
                settings = replace(
                    self.aidge_settings,
                    model_image_url=model.download_url,
                    garment_image_url=garment.download_url,
                    cloth_type=cloth_type,
                )
                try:
                    response = self.aidge_client.virtual_try_on_with_options(build_request(settings), self.runtime(budget))
                    budget.check()
                except ProviderFailure:
                    raise
                except Exception as error:
                    budget.check()
                    raise ProviderFailure("PROVIDER_UNAVAILABLE", "虚拟试衣服务提交失败，请稍后重试", retryable=True) from error
                body = response.body
                task_id = getattr(getattr(body, "data", None), "task_id", None)
                if not getattr(body, "success", False) or not task_id:
                    raise ProviderFailure("PROVIDER_REJECTED", "虚拟试衣服务未接受任务")

                while True:
                    time.sleep(min(self.poll_interval, budget.remaining()))
                    budget.check()
                    try:
                        response = self.aidge_client.query_async_task_result_with_options(
                            aidge_models.QueryAsyncTaskResultRequest(task_id=str(task_id)), self.runtime(budget)
                        )
                        budget.check()
                    except ProviderFailure:
                        raise
                    except Exception:
                        # A transient query failure does not prove the accepted
                        # Aidge task stopped. Keep its COS inputs alive and ask
                        # again until the total task deadline.
                        budget.check()
                        continue
                    body = response.body
                    data = getattr(body, "data", None)
                    status = str(getattr(data, "status", "UNKNOWN")).upper()
                    if status in PENDING_STATES:
                        continue
                    if getattr(body, "success", False) and status == "COMPLETED":
                        try:
                            result = download_result(result_image_url(getattr(data, "result", None)), budget=budget)
                            budget.check()
                            return result
                        except ProviderFailure:
                            raise
                        except Exception as error:
                            raise ProviderFailure("PROVIDER_INVALID_RESULT", "虚拟试衣结果无法解析") from error
                    raise ProviderFailure("PROVIDER_FAILED", "虚拟试衣生成失败，请重新拍摄后重试")
        finally:
            # Aidge has no cancellation operation in the current integration.
            # The worker therefore waits for a terminal/timeout outcome before
            # deleting inputs; the one-day COS lifecycle is the crash fallback.
            cleanup_failures = 0
            cleanup_budget = GenerationBudget(10)
            for object_key in uploaded_keys:
                try:
                    delete_tryon_input(self.cos_client, self.cos_settings, object_key, budget=cleanup_budget)
                except Exception:
                    cleanup_failures += 1
            if cleanup_failures:
                LOGGER.warning(
                    "%d temporary COS input(s) could not be deleted; lifecycle cleanup remains active",
                    cleanup_failures,
                )

    @staticmethod
    def runtime(budget: GenerationBudget) -> RuntimeOptions:
        timeout_ms = max(1, int(budget.socket_timeout() * 1000))
        return RuntimeOptions(connect_timeout=timeout_ms, read_timeout=timeout_ms,
                              autoretry=False, max_attempts=1)
