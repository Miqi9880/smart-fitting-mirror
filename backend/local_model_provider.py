"""Opt-in adapter for the private, loopback-only local try-on service.

This component is deliberately not registered by the public service or CLI.
It only calls an explicitly configured local HTTP endpoint and implements the
existing TryOnProvider contract.
"""

from __future__ import annotations

import ipaddress
import io
import json
import tempfile
import warnings
from pathlib import Path
from typing import Callable
from urllib.parse import urlsplit

import requests
from PIL import Image, ImageOps, UnidentifiedImageError

from .provider_control import GenerationBudget, ProviderFailure
from .provider_images import MAX_SOURCE_BYTES, prepare_input
from .tryon_provider import MAX_RESULT_BYTES, MAX_RESULT_PIXELS, ProviderResult

MAX_CONTROL_RESPONSE_BYTES = 64 * 1024
RESPONSE_CHUNK_BYTES = 64 * 1024
MAX_BUDGET_MS = 120_000


def _validated_endpoint(value: str | None) -> str | None:
    if value is None:
        return None
    if not isinstance(value, str) or not value or value != value.strip():
        raise ValueError("local model URL must be an explicit URL without surrounding whitespace")
    parsed = urlsplit(value)
    if parsed.scheme != "http" or not parsed.hostname or parsed.username or parsed.password:
        raise ValueError("local model URL must be plain HTTP on loopback")
    if parsed.path not in ("", "/") or parsed.query or parsed.fragment:
        raise ValueError("local model URL must contain only an origin")
    try:
        port = parsed.port
    except ValueError as error:
        raise ValueError("local model URL has an invalid port") from error
    if port is not None and not 1 <= port <= 65535:
        raise ValueError("local model URL has an invalid port")

    host = parsed.hostname
    try:
        is_loopback = ipaddress.ip_address(host).is_loopback
    except ValueError:
        is_loopback = host.lower() == "localhost"
    if not is_loopback:
        raise ValueError("local model URL must resolve to an explicit loopback host")
    return value.rstrip("/")


class LocalModelProvider:
    """Generate tops through an explicitly configured local HTTP service.

    ``allow_mock_for_tests`` is false by default. It exists solely so offline
    tests can exercise the adapter against an injected fake transport.
    """

    name = "local-model"

    def __init__(
        self,
        base_url: str | None = None,
        *,
        allow_mock_for_tests: bool = False,
        session_factory: Callable[[], requests.Session] = requests.Session,
    ) -> None:
        self.base_url = _validated_endpoint(base_url)
        self.allow_mock_for_tests = allow_mock_for_tests
        self.session_factory = session_factory

    def generate(
        self,
        model_image: bytes,
        garment_image: Path,
        cloth_type: str,
        *,
        budget: GenerationBudget,
    ) -> ProviderResult:
        if self.base_url is None:
            raise ProviderFailure("PROVIDER_UNAVAILABLE", "本地试衣模型尚未配置", retryable=True)
        if cloth_type != "tops":
            raise ProviderFailure("PROVIDER_UNSUPPORTED_CLOTH_TYPE", "本地试衣模型目前仅支持上衣")

        budget.check()
        try:
            garment_bytes = garment_image.read_bytes()
        except OSError as error:
            raise ProviderFailure("PROVIDER_INVALID_INPUT", "服装图片无法读取") from error
        if len(model_image) > MAX_SOURCE_BYTES or len(garment_bytes) > MAX_SOURCE_BYTES:
            raise ProviderFailure("PROVIDER_INVALID_INPUT", "输入图片过大，无法用于生成")

        with tempfile.TemporaryDirectory(prefix="smartmirror-local-model-") as directory:
            person_path = Path(directory) / "person.jpg"
            garment_path = Path(directory) / "garment.jpg"
            prepare_input(model_image, person_path, "人物", budget)
            prepare_input(garment_bytes, garment_path, "服装", budget)
            person_jpeg = person_path.read_bytes()
            garment_jpeg = garment_path.read_bytes()
            budget.check()

            with self.session_factory() as session:
                # Avoid ambient proxy variables and redirects that could send
                # private image bytes outside the configured loopback service.
                session.trust_env = False
                session.max_redirects = 0
                self._check_health(session, budget)
                remaining_ms = min(MAX_BUDGET_MS, int(budget.remaining() * 1000))
                if remaining_ms < 1:
                    budget.check()
                timeout = budget.socket_timeout()
                try:
                    response = session.post(
                        self.base_url + "/internal/tryon",
                        data={"cloth_type": "tops", "budget_ms": str(remaining_ms)},
                        files={
                            "person_image": ("person.jpg", person_jpeg, "image/jpeg"),
                            "garment_image": ("garment.jpg", garment_jpeg, "image/jpeg"),
                        },
                        timeout=timeout,
                        allow_redirects=False,
                        stream=True,
                    )
                except requests.Timeout as error:
                    budget.check()
                    raise ProviderFailure("GENERATION_TIMEOUT", "本地试衣模型请求超时", retryable=True) from error
                except requests.RequestException as error:
                    budget.check()
                    raise ProviderFailure("PROVIDER_UNAVAILABLE", "本地试衣模型暂不可用", retryable=True) from error

                with response:
                    budget.check()
                    if response.status_code != 200:
                        self._raise_http_failure(response, budget)
                    content_type = response.headers.get("Content-Type", "").split(";", 1)[0].strip().lower()
                    if content_type != "image/png":
                        raise ProviderFailure("PROVIDER_INVALID_RESULT", "本地试衣模型返回的不是PNG图片")
                    result_bytes = self._read_bounded(
                        response, MAX_RESULT_BYTES, budget, overflow_code="PROVIDER_INVALID_RESULT"
                    )
                    budget.check()
                    return self._decode_result(result_bytes, budget)

    def _check_health(self, session: requests.Session, budget: GenerationBudget) -> None:
        budget.check()
        try:
            response = session.get(
                self.base_url + "/internal/health",
                timeout=budget.socket_timeout(),
                allow_redirects=False,
                stream=True,
            )
        except requests.Timeout as error:
            budget.check()
            raise ProviderFailure("GENERATION_TIMEOUT", "本地试衣模型健康检查超时", retryable=True) from error
        except requests.RequestException as error:
            budget.check()
            raise ProviderFailure("PROVIDER_UNAVAILABLE", "本地试衣模型暂不可用", retryable=True) from error

        with response:
            budget.check()
            body = self._read_bounded(
                response, MAX_CONTROL_RESPONSE_BYTES, budget, overflow_code="PROVIDER_UNAVAILABLE"
            )
            try:
                payload = json.loads(body)
            except (UnicodeDecodeError, json.JSONDecodeError) as error:
                raise ProviderFailure("PROVIDER_UNAVAILABLE", "本地试衣模型健康状态无效", retryable=True) from error
            if response.status_code == 503:
                code = payload.get("error", {}).get("code") if isinstance(payload, dict) else None
                message = "本地试衣模型暂不可用" if code == "MODEL_NOT_READY" else "本地试衣模型正忙，请稍后重试"
                raise ProviderFailure("PROVIDER_UNAVAILABLE", message, retryable=True)
            if response.status_code != 200 or not isinstance(payload, dict):
                raise ProviderFailure("PROVIDER_UNAVAILABLE", "本地试衣模型健康检查失败", retryable=True)
            ready = payload.get("ready")
            is_mock = payload.get("is_mock")
            model_id = payload.get("model_id")
            if type(ready) is not bool or type(is_mock) is not bool or not (model_id is None or isinstance(model_id, str)):
                raise ProviderFailure("PROVIDER_UNAVAILABLE", "本地试衣模型健康状态无效", retryable=True)
            if not ready:
                raise ProviderFailure("PROVIDER_UNAVAILABLE", "本地试衣模型尚未就绪", retryable=True)
            if is_mock and not self.allow_mock_for_tests:
                raise ProviderFailure("PROVIDER_UNAVAILABLE", "本地试衣模型返回了测试模式，已拒绝", retryable=False)

    @staticmethod
    def _read_bounded(response: requests.Response, maximum: int, budget: GenerationBudget, *, overflow_code: str) -> bytes:
        declared = response.headers.get("Content-Length")
        if declared is not None:
            try:
                if int(declared) > maximum:
                    raise ProviderFailure(overflow_code, "本地试衣模型响应过大")
            except ValueError:
                raise ProviderFailure("PROVIDER_UNAVAILABLE", "本地试衣模型响应长度无效", retryable=True)
        output = bytearray()
        try:
            for chunk in response.iter_content(chunk_size=RESPONSE_CHUNK_BYTES):
                budget.check()
                if not chunk:
                    continue
                if len(output) + len(chunk) > maximum:
                    raise ProviderFailure(overflow_code, "本地试衣模型响应过大")
                output.extend(chunk)
            budget.check()
        except ProviderFailure:
            raise
        except requests.RequestException as error:
            budget.check()
            raise ProviderFailure("PROVIDER_UNAVAILABLE", "读取本地试衣模型响应失败", retryable=True) from error
        return bytes(output)

    @staticmethod
    def _raise_http_failure(response: requests.Response, budget: GenerationBudget) -> None:
        body = LocalModelProvider._read_bounded(
            response, MAX_CONTROL_RESPONSE_BYTES, budget, overflow_code="PROVIDER_UNAVAILABLE"
        )
        try:
            payload = json.loads(body)
            detail = payload.get("error", {}) if isinstance(payload, dict) else {}
            code = detail.get("code") if isinstance(detail, dict) else None
            retryable = detail.get("retryable") is True
        except (UnicodeDecodeError, json.JSONDecodeError):
            code, retryable = None, response.status_code in {500, 502, 503, 504}
        if response.status_code == 400 or code == "INVALID_INPUT":
            raise ProviderFailure("PROVIDER_INVALID_INPUT", "本地试衣模型拒绝了输入图片")
        if response.status_code == 422 or code == "UNSUPPORTED_CLOTH_TYPE":
            raise ProviderFailure("PROVIDER_UNSUPPORTED_CLOTH_TYPE", "本地试衣模型目前仅支持上衣")
        if response.status_code == 504 or code == "INFERENCE_TIMEOUT":
            raise ProviderFailure("GENERATION_TIMEOUT", "本地试衣模型生成超时", retryable=True)
        message = "本地试衣模型正忙，请稍后重试" if code == "MODEL_BUSY" else "本地试衣模型暂不可用"
        raise ProviderFailure("PROVIDER_UNAVAILABLE", message, retryable=retryable or response.status_code >= 500)

    @staticmethod
    def _decode_result(data: bytes, budget: GenerationBudget) -> ProviderResult:
        budget.check()
        try:
            with warnings.catch_warnings():
                warnings.simplefilter("error", Image.DecompressionBombWarning)
                with Image.open(io.BytesIO(data)) as source:
                    if source.format != "PNG" or source.width * source.height > MAX_RESULT_PIXELS:
                        raise ProviderFailure("PROVIDER_INVALID_RESULT", "本地试衣模型返回的图片无效")
                    with source.convert("RGB") as normalized:
                        output = io.BytesIO()
                        normalized.save(output, "PNG")
                        budget.check()
                        return ProviderResult(output.getvalue(), normalized.width, normalized.height)
        except ProviderFailure:
            raise
        except (UnidentifiedImageError, OSError, ValueError, SyntaxError, Image.DecompressionBombError, Image.DecompressionBombWarning) as error:
            raise ProviderFailure("PROVIDER_INVALID_RESULT", "本地试衣模型返回的图片无效") from error
