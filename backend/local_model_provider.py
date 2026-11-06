"""Opt-in adapter for the private, loopback-only local try-on service.

This component is deliberately not registered by the public service or CLI.
It only calls an explicitly configured local HTTP endpoint and implements the
existing TryOnProvider contract.
"""

from __future__ import annotations

import ipaddress
import io
import json
import socket
import warnings
from contextlib import closing, contextmanager, suppress
from http.client import HTTPConnection, HTTPException, HTTPResponse
from pathlib import Path
from threading import Timer
from typing import Callable
from urllib.parse import urlsplit

import requests
from PIL import Image, UnidentifiedImageError

from .provider_control import GenerationBudget, ProviderFailure
from .provider_images import MAX_SOURCE_BYTES, MAX_SOURCE_PIXELS
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
    if parsed.scheme != "http" or not parsed.hostname or parsed.username is not None or parsed.password is not None:
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


def _validate_image(data: bytes, role: str, budget: GenerationBudget) -> str:
    budget.check()
    try:
        if len(data) > MAX_SOURCE_BYTES:
            raise ValueError("source too large")
        with warnings.catch_warnings():
            warnings.simplefilter("error", Image.DecompressionBombWarning)
            with Image.open(io.BytesIO(data)) as source:
                if source.format not in {"JPEG", "PNG"} or source.width * source.height > MAX_SOURCE_PIXELS:
                    raise ValueError("invalid source format or dimensions")
                source.load()
                media_type = Image.MIME[source.format]
    except (UnidentifiedImageError, OSError, ValueError, SyntaxError, Image.DecompressionBombError, Image.DecompressionBombWarning) as error:
        raise ProviderFailure("PROVIDER_INVALID_INPUT", f"{role}图片无效或过大") from error
    budget.check()
    return media_type


def _json_object(data: bytes) -> dict:
    try:
        payload = json.loads(data)
    except (UnicodeDecodeError, ValueError, RecursionError):
        return {}
    return payload if isinstance(payload, dict) else {}


def _error_fields(payload: dict) -> tuple[str | None, bool | None]:
    error = payload.get("error")
    if not isinstance(error, dict):
        return None, None
    code, retryable = error.get("code"), error.get("retryable")
    return code if isinstance(code, str) else None, retryable if type(retryable) is bool else None


def _shutdown_socket(transport: socket.socket) -> None:
    with suppress(OSError):
        transport.shutdown(socket.SHUT_RDWR)


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
        connection_factory: Callable[..., HTTPConnection] = HTTPConnection,
    ) -> None:
        self.base_url = _validated_endpoint(base_url)
        self.allow_mock_for_tests = allow_mock_for_tests
        self.connection_factory = connection_factory

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

        person_type = _validate_image(model_image, "人物", budget)
        try:
            with garment_image.open("rb") as source:
                garment_bytes = source.read(MAX_SOURCE_BYTES + 1)
        except OSError as error:
            raise ProviderFailure("PROVIDER_INVALID_INPUT", "服装图片无法读取") from error
        garment_type = _validate_image(garment_bytes, "服装", budget)

        self._check_health(budget)
        remaining_ms = min(MAX_BUDGET_MS, int(budget.remaining() * 1000))
        if remaining_ms < 1:
            raise ProviderFailure("GENERATION_TIMEOUT", "本地试衣模型已无足够等待时间", retryable=True)
        path = "/internal/tryon"
        # Requests prepares multipart bytes only. HTTPConnection performs the
        # actual call without environment proxies or automatic redirects.
        request = requests.Request(
            "POST", self.base_url + path,
            data={"cloth_type": "tops", "budget_ms": str(remaining_ms)},
            files={
                "person_image": ("person." + person_type.split("/", 1)[1], model_image, person_type),
                "garment_image": ("garment." + garment_type.split("/", 1)[1], garment_bytes, garment_type),
            },
        ).prepare()
        with self._request("POST", path, budget, body=request.body, headers=request.headers) as response:
            if response.status != 200:
                self._raise_http_failure(response, budget)
            content_type = response.getheader("Content-Type", "").split(";", 1)[0].strip().lower()
            if content_type != "image/png":
                raise ProviderFailure("PROVIDER_INVALID_RESULT", "本地试衣模型返回的不是PNG图片")
            result_bytes = self._read_bounded(
                response, MAX_RESULT_BYTES, budget, overflow_code="PROVIDER_INVALID_RESULT"
            )
        return self._decode_result(result_bytes, budget)

    def _connect(self, budget: GenerationBudget) -> HTTPConnection:
        origin = urlsplit(self.base_url)
        # Resolve the supported localhost alias without depending on DNS or a
        # hosts file override. Fallback only before sending any HTTP bytes.
        hosts = ("127.0.0.1", "::1") if origin.hostname == "localhost" else (origin.hostname,)
        for host in hosts:
            connection = self.connection_factory(host, origin.port, timeout=budget.socket_timeout())
            try:
                connection.connect()
                return connection
            except OSError:
                connection.close()
                if host == hosts[-1]:
                    raise

    @contextmanager
    def _request(self, method, path, budget, *, body=None, headers=None):
        try:
            with closing(self._connect(budget)) as connection:
                transport = connection.sock
                assert transport is not None
                # The connect timeout is not the synchronous inference timeout.
                # Shutdown also interrupts a peer that trickles headers or data.
                transport.settimeout(budget.remaining())
                timer = Timer(budget.remaining(), _shutdown_socket, args=(transport,))
                timer.daemon = True
                timer.start()
                try:
                    request_headers = dict(headers or {})
                    request_headers["Host"] = urlsplit(self.base_url).netloc
                    connection.request(method, path, body=body, headers=request_headers)
                    with connection.getresponse() as response:
                        budget.check()
                        yield response
                finally:
                    timer.cancel()
                    timer.join()
        except TimeoutError as error:
            budget.check()
            raise ProviderFailure("GENERATION_TIMEOUT", "本地试衣模型请求超时", retryable=True) from error
        except (OSError, HTTPException) as error:
            budget.check()
            raise ProviderFailure("PROVIDER_UNAVAILABLE", "本地试衣模型暂不可用", retryable=True) from error

    def _check_health(self, budget: GenerationBudget) -> None:
        with self._request("GET", "/internal/health", budget) as response:
            body = self._read_bounded(
                response, MAX_CONTROL_RESPONSE_BYTES, budget, overflow_code="PROVIDER_UNAVAILABLE"
            )
            payload = _json_object(body)
            if response.status == 503:
                code, retryable = _error_fields(payload)
                message = "本地试衣模型正忙，请稍后重试" if code == "MODEL_BUSY" else "本地试衣模型暂不可用"
                raise ProviderFailure("PROVIDER_UNAVAILABLE", message, retryable=retryable is not False)
            if response.status != 200:
                raise ProviderFailure("PROVIDER_UNAVAILABLE", "本地试衣模型健康检查失败", retryable=True)
            ready = payload.get("ready")
            is_mock = payload.get("is_mock")
            model_id = payload.get("model_id")
            if type(ready) is not bool or type(is_mock) is not bool or "model_id" not in payload or not (model_id is None or isinstance(model_id, str)):
                raise ProviderFailure("PROVIDER_UNAVAILABLE", "本地试衣模型健康状态无效", retryable=True)
            if not ready:
                raise ProviderFailure("PROVIDER_UNAVAILABLE", "本地试衣模型尚未就绪", retryable=True)
            if is_mock and not self.allow_mock_for_tests:
                raise ProviderFailure("PROVIDER_UNAVAILABLE", "本地试衣模型返回了测试模式，已拒绝", retryable=False)

    @staticmethod
    def _read_bounded(response: HTTPResponse, maximum: int, budget: GenerationBudget, *, overflow_code: str) -> bytes:
        declared = response.getheader("Content-Length")
        size = None
        if declared is not None:
            try:
                size = int(declared)
                if size < 0:
                    raise ValueError("negative content length")
            except ValueError:
                raise ProviderFailure("PROVIDER_UNAVAILABLE", "本地试衣模型响应长度无效", retryable=True)
            if size > maximum:
                raise ProviderFailure(overflow_code, "本地试衣模型响应过大")
        output = bytearray()
        while True:
            budget.check()
            chunk = response.read1(min(RESPONSE_CHUNK_BYTES, maximum + 1 - len(output)))
            budget.check()
            if not chunk:
                break
            output.extend(chunk)
            if len(output) > maximum:
                raise ProviderFailure(overflow_code, "本地试衣模型响应过大")
        if size is not None and len(output) != size:
            raise ProviderFailure("PROVIDER_UNAVAILABLE", "本地试衣模型响应不完整", retryable=True)
        return bytes(output)

    @staticmethod
    def _raise_http_failure(response: HTTPResponse, budget: GenerationBudget) -> None:
        body = LocalModelProvider._read_bounded(
            response, MAX_CONTROL_RESPONSE_BYTES, budget, overflow_code="PROVIDER_UNAVAILABLE"
        )
        code, retryable = _error_fields(_json_object(body))
        if response.status == 400 or code == "INVALID_INPUT":
            raise ProviderFailure("PROVIDER_INVALID_INPUT", "本地试衣模型拒绝了输入图片", retryable=retryable is True)
        if response.status == 422 or code == "UNSUPPORTED_CLOTH_TYPE":
            raise ProviderFailure("PROVIDER_UNSUPPORTED_CLOTH_TYPE", "本地试衣模型目前仅支持上衣", retryable=retryable is True)
        if response.status == 504 or code == "INFERENCE_TIMEOUT":
            raise ProviderFailure("GENERATION_TIMEOUT", "本地试衣模型生成超时", retryable=retryable is not False)
        message = "本地试衣模型正忙，请稍后重试" if code == "MODEL_BUSY" else "本地试衣模型暂不可用"
        raise ProviderFailure("PROVIDER_UNAVAILABLE", message, retryable=response.status >= 500 if retryable is None else retryable)

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
                        if output.tell() > MAX_RESULT_BYTES:
                            raise ProviderFailure("PROVIDER_INVALID_RESULT", "本地试衣模型返回的图片过大")
                        return ProviderResult(output.getvalue(), normalized.width, normalized.height)
        except ProviderFailure:
            raise
        except (UnidentifiedImageError, OSError, ValueError, SyntaxError, Image.DecompressionBombError, Image.DecompressionBombWarning) as error:
            raise ProviderFailure("PROVIDER_INVALID_RESULT", "本地试衣模型返回的图片无效") from error
