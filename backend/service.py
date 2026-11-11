from __future__ import annotations

import asyncio
import hashlib
import hmac
import io
import logging
import re
import secrets
import threading
import time
import uuid
import warnings
from collections import defaultdict, deque
from concurrent.futures import ThreadPoolExecutor
from contextlib import asynccontextmanager, suppress
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable, Literal

from fastapi import FastAPI, Request, Query
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, Response, FileResponse
from pydantic import BaseModel, ConfigDict, Field, ValidationError, model_validator
from starlette.concurrency import run_in_threadpool
from starlette.datastructures import UploadFile
from starlette.exceptions import HTTPException
from PIL import Image, ImageDraw, ImageOps, UnidentifiedImageError
from .catalog import ASSET_SPECS, CATALOG_CAPACITY, GarmentWrite, initial_catalog, regression_catalog, public_item, matches
from .catalog_repository import CatalogRepository
from .outfit import ACCESSORY_DIRECTORY, OutfitSelection, accessory_catalog, valid_kinds
from .tryon_provider import ProviderFailure, ProviderResult, TryOnProvider
from .provider_control import GenerationBudget
from .usage_metrics import UsageMetrics
from .video_contracts import VideoRecommendationRequest, VideoSemanticAnalysis, VideoTag
from .video_library import VideoLibrary
from .video_semantics import OutfitTagger, SemanticUnavailable

MAX_FILE = 10 * 1024 * 1024
MAX_BODY = MAX_FILE + 64 * 1024
MAX_PIXELS = 16_000_000
MAX_STORED = 128 * 1024 * 1024
ASSETS = Path(__file__).parent / "assets"
TERMINAL = {"succeeded", "failed", "cancelled"}
Mode = Literal["success", "failure", "delayed", "timeout", "rate-limit"]
LOGGER = logging.getLogger(__name__)


def stamp(epoch: float) -> str:
    return datetime.fromtimestamp(epoch, timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def detail(code: str, message: str, retryable=False) -> dict:
    return {"code": code, "message": message, "retryable": retryable, "request_id": str(uuid.uuid4())}


class Problem(Exception):
    def __init__(self, status: int, code: str, message: str, retryable=False, retry_after=None):
        self.status = status
        self.error = detail(code, message, retryable)
        self.headers = {"Retry-After": str(retry_after)} if retry_after else {}


class BodyLimit:
    """Bound the body before multipart parsing, including chunked requests.

    Reject before the parser opens temporary files, so a size-limit abort cannot
    strand a partially parsed upload. Ordinary form errors are closed by Starlette.
    """
    def __init__(self, app, max_uploads=4):
        self.app = app
        self.max_uploads = max_uploads
        self.uploads = 0

    async def __call__(self, scope, receive, send):
        is_upload = scope["type"] == "http" and scope["method"] == "POST" and scope["path"] == "/api/v1/uploads"
        if is_upload:
            if self.uploads >= self.max_uploads:
                return await JSONResponse({"error": detail("REQUEST_CAPACITY", "正在处理其他上传，请稍后重试", True)},
                                          429, headers={"Cache-Control": "no-store", "Retry-After": "1"})(scope, receive, send)
            self.uploads += 1
        try:
            await self.bounded_body(scope, receive, send)
        finally:
            if is_upload:
                self.uploads -= 1

    async def bounded_body(self, scope, receive, send):
        if scope["type"] != "http" or scope["method"] not in {"POST", "PUT", "PATCH"}:
            return await self.app(scope, receive, send)
        limit = MAX_BODY if scope["path"] == "/api/v1/uploads" else 64 * 1024
        length = dict(scope.get("headers", [])).get(b"content-length")
        try:
            declared = int(length or b"0")
            if declared < 0:
                raise ValueError()
        except ValueError:
            return await JSONResponse({"error": detail("INVALID_REQUEST", "Content-Length 无效")},
                                      400, headers={"Cache-Control": "no-store"})(scope, receive, send)

        async def reject():
            await JSONResponse({"error": detail("IMAGE_TOO_LARGE", "请求体过大")},
                               413, headers={"Cache-Control": "no-store"})(scope, receive, send)

        if declared > limit:
            return await reject()
        count = 0
        chunks = deque()
        while True:
            message = await receive()
            if message["type"] == "http.disconnect":
                return
            count += len(message.get("body", b""))
            if count > limit:
                return await reject()
            chunks.append(message)
            if not message.get("more_body", False):
                break

        async def limited_receive():
            return chunks.popleft() if chunks else await receive()

        await self.app(scope, limited_receive, send)


@dataclass
class Photo:
    data: bytes
    width: int
    height: int
    expires: float


@dataclass
class Job:
    value: dict
    created: float
    mode: Mode
    asset_id: str | None
    result_bytes: bytes | None = None
    is_mock: bool = True
    budget: GenerationBudget | None = None
    started_monotonic: float = 0.0
    metrics_terminal_recorded: bool = False
    semantic_tags: list[VideoTag] | None = None


@dataclass
class Guest:
    sid: str
    expires: float
    uploads: dict[str, Photo] = field(default_factory=dict)
    jobs: dict[str, Job] = field(default_factory=dict)
    keys: dict[str, tuple[tuple, str]] = field(default_factory=dict)
    throttled_keys: set[str] = field(default_factory=set)
    catalog: dict[str, GarmentWrite] = field(default_factory=initial_catalog)


class Store:
    """One-process service. State transitions and ownership checks share a lock."""
    def __init__(self, mode: Mode = "success", clock: Callable[[], float] = time.time, ttl: float = 1800, catalog_path=None, catalog_profile="studio", provider: TryOnProvider | None = None, monotonic: Callable[[], float] = time.monotonic, provider_disabled: bool = False, metrics: UsageMetrics | None = None, video_library: VideoLibrary | None = None, outfit_tagger: OutfitTagger | None = None):
        self.mode, self.clock, self.ttl = mode, clock, ttl
        self.monotonic = monotonic
        self.provider = provider
        self.provider_disabled = provider_disabled
        self.outfit_available = not provider_disabled and (provider is None or callable(getattr(provider, "generate_outfit", None)))
        self.accessories = accessory_catalog()
        self.metrics = metrics
        self.video_library = video_library if video_library is not None else VideoLibrary.from_file()
        self.outfit_tagger = outfit_tagger
        self.outfit_tagging = threading.Lock()
        # Keep one local provider worker in flight, including its cleanup.
        # The upstream cannot be cancelled; a local timeout is not cloud cancellation.
        self.executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="tryon-provider") if provider else None
        self.lock = threading.RLock()
        self.guests: dict[str, Guest] = {}
        self.provider_inflight: set[tuple[str, str]] = set()
        self.buckets: dict[str, deque] = defaultdict(deque)
        self.secret = secrets.token_bytes(32)
        self.catalog_factory = regression_catalog if catalog_profile == "regression" else initial_catalog
        self.catalog_repository = CatalogRepository(catalog_path,profile=catalog_profile) if catalog_path is not None else None
        observe_submission = getattr(provider, "set_submission_observer", None)
        if metrics is not None and callable(observe_submission):
            observe_submission(lambda metric: self._record_usage(metric, is_mock=False))

    def analyze_outfit(self, authorization: str | None, job_id: str) -> VideoSemanticAnalysis:
        unavailable = VideoSemanticAnalysis(job_id=job_id, status="unavailable", tags=[])
        with self.lock:
            guest = self.guest(authorization)
            job = guest.jobs.get(job_id)
            if job is None or job.value["status"] != "succeeded" or job.is_mock or job.result_bytes is None:
                raise Problem(404, "RESOURCE_NOT_FOUND", "当前会话没有可分析的生成结果")
            if job.semantic_tags is not None:
                status = "used" if job.semantic_tags else "no_tags"
                return VideoSemanticAnalysis(job_id=job_id, status=status, tags=job.semantic_tags)
            if self.outfit_tagger is None or not self.outfit_tagging.acquire(blocking=False):
                return unavailable
            image = job.result_bytes
        try:
            tags = self.outfit_tagger.tags(image)
            analysis = VideoSemanticAnalysis(job_id=job_id, status="used" if tags else "no_tags", tags=tags)
            with self.lock:
                self.guest(authorization)
                job.semantic_tags = analysis.tags
            return analysis
        except (SemanticUnavailable, ValidationError):
            return unavailable
        finally:
            self.outfit_tagging.release()

    def _record_usage(self, metric: str, *, is_mock: bool, duration_ms: int | None = None) -> None:
        if self.metrics is None:
            return
        provider = "mock" if is_mock else getattr(self.provider, "name", "unknown")
        if provider not in {"mock", "aidge-cos", "local-model"}:
            provider = "unknown"
        model = "mock-static" if is_mock else "unknown"
        try:
            self.metrics.record(provider=provider, model=model, mode="mock" if is_mock else "real",
                                metric=metric, duration_ms=duration_ms)
        except Exception:
            # Metrics failures must not fail a fitting request or provider cleanup.
            LOGGER.warning("Anonymous usage metric could not be recorded (metric=%s)", metric)

    def _record_terminal(self, job: Job) -> None:
        if job.metrics_terminal_recorded:
            return
        job.metrics_terminal_recorded = True
        status = job.value["status"]
        if status in {"succeeded", "failed", "cancelled"}:
            duration_ms = max(0, round((self.monotonic() - job.started_monotonic) * 1000))
            self._record_usage(status, is_mock=job.is_mock, duration_ms=duration_ms)

    def token(self, sid: str) -> str:
        return sid + "." + hmac.new(self.secret, sid.encode(), hashlib.sha256).hexdigest()

    def token_id(self, authorization: str | None) -> str:
        token = (authorization or "").removeprefix("Bearer ")
        if not authorization or not authorization.startswith("Bearer ") or not re.fullmatch(r"session_[0-9a-f]{32}\.[0-9a-f]{64}", token):
            raise Problem(401, "SESSION_INVALID", "会话无效或已结束")
        sid = token.split(".")[0]
        if not hmac.compare_digest(self.token(sid), token):
            raise Problem(401, "SESSION_INVALID", "会话无效或已结束")
        return sid

    def guest(self, authorization: str | None) -> Guest:
        sid = self.token_id(authorization)
        self.sweep()
        guest = self.guests.get(sid)
        if guest is None:
            raise Problem(401, "SESSION_INVALID", "会话无效或已结束")
        return guest

    def rate(self, key: str, limit: int):
        now = self.clock()
        bucket = self.buckets[key]
        while bucket and bucket[0] <= now - 60:
            bucket.popleft()
        if len(bucket) >= limit:
            raise Problem(429, "RATE_LIMITED", "请求较多，请稍后重试", True, max(1, int(60 - now + bucket[0]) + 1))
        bucket.append(now)

    def sweep(self):
        with self.lock:
            now = self.clock()
            for sid in [sid for sid, guest in self.guests.items() if guest.expires <= now]:
                self.guests.pop(sid)
            for guest in self.guests.values():
                for job in guest.jobs.values():
                    self.advance(guest, job)
            for key in [key for key, times in self.buckets.items() if not times or times[-1] <= now - 60]:
                self.buckets.pop(key)

    def bytes_stored(self):
        return sum(sum(len(p.data) for p in g.uploads.values()) +
                   sum(len(j.result_bytes or b"") for j in g.jobs.values()) for g in self.guests.values())

    def create_session(self, ip: str):
        with self.lock:
            self.sweep()
            self.rate("sessions:" + ip, 20)
            if len(self.guests) >= 100:
                raise Problem(503, "CAPACITY_REACHED", "模拟服务会话容量已满", True)
            sid = "session_" + uuid.uuid4().hex
            guest = Guest(sid, self.clock() + self.ttl)
            if self.catalog_repository is not None:
                guest.catalog = self.catalog_repository.snapshot()
            else:
                guest.catalog = self.catalog_factory()
            self.guests[sid] = guest
            return {"session_id": sid, "session_token": self.token(sid), "expires_at": stamp(guest.expires)}

    def end(self, authorization):
        with self.lock:
            # A valid issued token can repeat DELETE even after its in-memory record was removed.
            self.guests.pop(self.token_id(authorization), None)

    def save_photo(self, authorization, image: Photo):
        with self.lock:
            guest = self.guest(authorization)
            self.rate("uploads:" + guest.sid, 10)
            if len(guest.uploads) >= 8 or self.bytes_stored() + len(image.data) > MAX_STORED:
                raise Problem(429, "UPLOAD_CAPACITY", "请结束会话后重试上传", True, 60)
            uid = "upload_" + uuid.uuid4().hex
            image.expires = guest.expires
            guest.uploads[uid] = image
            return {"upload_id": uid, "width": image.width, "height": image.height, "expires_at": stamp(image.expires)}

    def create_job(self, authorization, upload_id, garment_id, key, outfit: OutfitSelection | None = None):
        try:
            key = str(uuid.UUID(key))
        except (ValueError, TypeError, AttributeError):
            raise Problem(400, "IDEMPOTENCY_KEY_REQUIRED", "请提供 UUID 格式的 Idempotency-Key")
        work = None
        with self.lock:
            guest = self.guest(authorization)
            fingerprint = (upload_id, garment_id,
                           tuple(outfit.garment_ids) if outfit else (), tuple(outfit.accessory_ids) if outfit else ())
            if key in guest.keys:
                prior, jid = guest.keys[key]
                if prior != fingerprint:
                    raise Problem(409, "IDEMPOTENCY_CONFLICT", "相同幂等键不能用于不同输入")
                return guest.jobs[jid].value.copy(), 200
            if self.provider_disabled:
                raise Problem(503, "PROVIDER_UNAVAILABLE", "真实试衣调用当前未启用；本地预览仍可使用", False)
            if upload_id not in guest.uploads:
                raise Problem(404, "RESOURCE_NOT_FOUND", "上传不存在")
            references = ()
            garment_path = cloth_type = asset_id = None
            if outfit is not None:
                references = self.outfit_references(guest, outfit)
                garment_id = (outfit.garment_ids + outfit.accessory_ids)[0]
            else:
                garment = guest.catalog.get(garment_id)
                if garment is None:
                    raise Problem(404, "RESOURCE_NOT_FOUND", "服装不存在")
                asset_id = garment.asset_id
                if not ASSET_SPECS[asset_id]["generatable"]:
                    raise Problem(422, "GARMENT_NOT_GENERATABLE", "该服装仅支持实时预览")
                if self.provider is not None:
                    garment_path = self.asset_path(asset_id)
                    cloth_type = self.cloth_type(garment.category)
            if self.provider is not None and self.provider_inflight:
                raise Problem(429, "PROVIDER_BUSY", "正在生成另一项试衣，请稍后重试", True, 5)
            # Limit new attempts too; rejected simulation keys must not grow without bound.
            self.rate("jobs:" + guest.sid, 6)
            if self.mode == "rate-limit" and key not in guest.throttled_keys:
                guest.throttled_keys.add(key)
                raise Problem(429, "RATE_LIMITED", "模拟首次创建限流，请用原幂等键重试", True, 1)
            if len(guest.jobs) >= 30:
                raise Problem(429, "JOB_CAPACITY", "请结束会话后重试", True, 60)
            jid = "job_" + uuid.uuid4().hex
            now = self.clock()
            value = {"job_id": jid, "session_id": guest.sid, "upload_id": upload_id, "garment_id": garment_id,
                     "status": "queued", "is_mock": self.provider is None, "created_at": stamp(now), "updated_at": stamp(now),
                     "result": None, "error": None}
            if outfit is not None:
                value["outfit"] = outfit.model_dump()
            budget = GenerationBudget(120, clock=self.monotonic) if self.provider is not None else None
            guest.jobs[jid] = Job(value, now, self.mode, asset_id, is_mock=self.provider is None,
                                  budget=budget, started_monotonic=self.monotonic())
            guest.keys[key] = fingerprint, jid
            self._record_usage("task_created", is_mock=self.provider is None)
            if self.provider is not None:
                work = (guest.sid, jid, guest.uploads[upload_id].data, garment_path, cloth_type, references)
                self.provider_inflight.add((guest.sid, jid))
            response = value.copy(), 202
        if work is not None:
            assert self.executor is not None
            self.executor.submit(self.run_provider, *work)
        return response

    def outfit_references(self, guest, outfit: OutfitSelection):
        if not self.outfit_available:
            raise Problem(422, "OUTFIT_NOT_SUPPORTED", "当前服务不支持搭配生成")
        garments = [guest.catalog.get(gid) for gid in outfit.garment_ids]
        accessories = [self.accessories.get(aid) for aid in outfit.accessory_ids]
        if any(item is None for item in garments + accessories):
            raise Problem(404, "RESOURCE_NOT_FOUND", "搭配中的服装或配饰不存在")
        kinds = [item.category for item in garments] + [item["category"] for item in accessories]
        if not valid_kinds(kinds):
            raise Problem(422, "INVALID_OUTFIT", "同一部位只能选一件，连衣裙与上衣和下装互斥")
        accessory_refs = tuple((item["category"], self.accessory_path(item["accessory_id"])) for item in accessories)
        if self.provider is None:
            return ()
        return tuple((item.category, self.asset_path(item.asset_id)) for item in garments) + accessory_refs

    def accessory_path(self, accessory_id):
        if accessory_id not in self.accessories:
            raise Problem(404, "RESOURCE_NOT_FOUND", "配饰不存在")
        path = ACCESSORY_DIRECTORY / f"{accessory_id}.png"
        if not path.is_file():
            raise Problem(503, "ASSET_NOT_INSTALLED", "配饰参考素材尚未安装", True)
        return path

    @staticmethod
    def cloth_type(category: str) -> str:
        return {"upper_body": "tops", "lower_body": "bottoms", "shorts": "bottoms",
                "skirt": "bottoms", "dress": "dresses", "headwear": "hats"}[category]

    @staticmethod
    def asset_path(asset_id: str) -> Path:
        path = ASSETS / ASSET_SPECS[asset_id]["file"]
        if not path.is_file() and path.parent.name == "studio":
            local_source = ASSETS / "real" / path.name
            if local_source.is_file():
                path = local_source
        if not path.is_file():
            raise Problem(503, "ASSET_NOT_INSTALLED", "请先安装本地商品素材包", True)
        return path

    def run_provider(self, sid: str, jid: str, model_image: bytes, garment_path, cloth_type, references):
        try:
            with self.lock:
                guest = self.guests.get(sid)
                job = guest.jobs.get(jid) if guest else None
                if job is not None:
                    self.advance(guest, job)
                if job is None or job.value["status"] in TERMINAL:
                    return
                assert job.budget is not None
                budget = job.budget
                job.value["status"] = "running"
                job.value["updated_at"] = stamp(self.clock())
            try:
                assert self.provider is not None
                if references:
                    result = self.provider.generate_outfit(model_image, references, budget=budget)
                else:
                    result = self.provider.generate(model_image, garment_path, cloth_type, budget=budget)
            except ProviderFailure as error:
                self.finish_provider_failure(sid, jid, error)
                return
            except Exception:
                self.finish_provider_failure(
                    sid, jid, ProviderFailure("PROVIDER_UNAVAILABLE", "虚拟试衣服务暂时不可用", retryable=True)
                )
                return
            self.finish_provider_success(sid, jid, result)
        finally:
            with self.lock:
                self.provider_inflight.discard((sid, jid))

    def finish_provider_failure(self, sid: str, jid: str, error: ProviderFailure):
        with self.lock:
            guest = self.guests.get(sid)
            job = guest.jobs.get(jid) if guest else None
            if job is not None:
                self.advance(guest, job)
            if job is None or job.value["status"] in TERMINAL:
                return
            job.value["status"] = "failed"
            job.value["error"] = detail(error.code, error.message, error.retryable)
            job.value["updated_at"] = stamp(self.clock())
            self._record_terminal(job)

    def finish_provider_success(self, sid: str, jid: str, result: ProviderResult):
        with self.lock:
            guest = self.guests.get(sid)
            job = guest.jobs.get(jid) if guest else None
            if job is not None:
                self.advance(guest, job)
            if job is None or job.value["status"] in TERMINAL:
                return
            if self.bytes_stored() + len(result.data) > MAX_STORED:
                job.value["status"] = "failed"
                job.value["error"] = detail("RESULT_CAPACITY", "试衣结果暂时无法保存，请结束会话后重试", True)
            else:
                job.result_bytes = result.data
                job.value["status"] = "succeeded"
                job.value["result"] = {"image_url": f"results/{jid}.png", "width": result.width,
                                       "height": result.height, "expires_at": stamp(guest.expires)}
            job.value["updated_at"] = stamp(self.clock())
            self._record_terminal(job)

    def advance(self, guest: Guest, job: Job):
        if job.value["status"] in TERMINAL:
            return
        if not job.is_mock:
            assert job.budget is not None
            if job.budget.expired:
                job.value.update(status="failed", result=None,
                    error=detail("GENERATION_TIMEOUT", "虚拟试衣超过120秒总等待时限", True),
                    updated_at=stamp(self.clock()))
                self._record_terminal(job)
            # Keep the local slot until the worker and its cleanup finish, even
            # after timeout/cancel. No implicit paid retry is queued here.
            return
        now = self.clock()
        age = now - job.created
        status = "queued" if age < .5 else "running"
        if age >= 120:
            status = "failed"
            job.value["error"] = detail("GENERATION_TIMEOUT", "模拟任务超过 120 秒时限")
        elif job.mode != "timeout" and age >= (15 if job.mode == "delayed" else 2):
            if job.mode == "failure":
                status = "failed"
                job.value["error"] = detail("MOCK_GENERATION_FAILED", "模拟生成失败，可重新拍摄")
            else:
                try:
                    photo = guest.uploads[job.value["upload_id"]]
                    result = mock_result(photo, job.asset_id)
                    if self.bytes_stored() + len(result) > MAX_STORED:
                        raise ValueError("capacity")
                    job.result_bytes = result
                    status = "succeeded"
                    job.value["result"] = {"image_url": f"results/{job.value['job_id']}.png", "width": photo.width,
                                           "height": photo.height, "expires_at": stamp(guest.expires)}
                except Exception:
                    status = "failed"
                    job.value["error"] = detail("MOCK_GENERATION_FAILED", "模拟结果生成失败")
        if job.value["status"] != status:
            job.value["status"] = status
            job.value["updated_at"] = stamp(now)
            if status in TERMINAL:
                self._record_terminal(job)

    def get_job(self, authorization, jid, cancel=False):
        with self.lock:
            guest = self.guest(authorization)
            job = guest.jobs.get(jid)
            if job is None:
                raise Problem(404, "RESOURCE_NOT_FOUND", "任务不存在")
            if cancel and job.value["status"] not in TERMINAL:
                job.value["status"] = "cancelled"
                job.value["updated_at"] = stamp(self.clock())
                self._record_terminal(job)
            return job.value.copy()


def decode_photo(data: bytes, media_type: str) -> Photo:
    if len(data) > MAX_FILE:
        raise Problem(413, "IMAGE_TOO_LARGE", "图片不能超过 10 MiB")
    if media_type not in {"image/jpeg", "image/png"}:
        raise Problem(415, "UNSUPPORTED_IMAGE", "仅支持 JPEG 和 PNG")
    try:
        with warnings.catch_warnings():
            warnings.simplefilter("error", Image.DecompressionBombWarning)
            with Image.open(io.BytesIO(data)) as probe:
                if probe.format not in {"PNG", "JPEG"} or Image.MIME[probe.format] != media_type:
                    raise Problem(415, "UNSUPPORTED_IMAGE", "图片内容与类型不匹配")
                if probe.width * probe.height > MAX_PIXELS:
                    raise Problem(413, "IMAGE_TOO_LARGE", "图片不能超过 1600 万像素")
                probe.verify()
            with Image.open(io.BytesIO(data)) as original, ImageOps.exif_transpose(original) as upright, upright.convert("RGB") as image:
                image.info.clear()
                buffer = io.BytesIO()
                image.save(buffer, "PNG")  # No original EXIF or other metadata is retained.
                output = buffer.getvalue()
                return Photo(output, image.width, image.height, 0)
    except Problem:
        raise
    except (Image.DecompressionBombError, Image.DecompressionBombWarning):
        raise Problem(413, "IMAGE_TOO_LARGE", "图片像素过多")
    except (UnidentifiedImageError, OSError, ValueError, SyntaxError):
        raise Problem(422, "INVALID_IMAGE", "图片无法解码")


def mock_result(photo: Photo, asset_id: str | None) -> bytes:
    with Image.open(io.BytesIO(photo.data)) as original:
        result = original.convert("RGBA")
        if asset_id is not None:
            with Image.open(ASSETS / ASSET_SPECS[asset_id]["file"]) as shirt:
                layer = ImageOps.contain(shirt, (max(1, int(photo.width * .65)), max(1, int(photo.height * .7))))
                result.alpha_composite(layer, ((photo.width - layer.width) // 2, int(photo.height * .12)))
        draw = ImageDraw.Draw(result)
        draw.rectangle((0, 0, photo.width, min(44, photo.height)), fill="#172D43")
        draw.text((8, 10), "MOCK PREVIEW - NOT AI OUTPUT", fill="white")
        output = io.BytesIO()
        result.convert("RGB").save(output, "PNG")
        return output.getvalue()


class CreateInput(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    upload_id: str = Field(min_length=1, max_length=128)
    garment_id: str | None = Field(default=None, min_length=1, max_length=128)
    outfit: OutfitSelection | None = None

    @model_validator(mode="after")
    def check_selection(self):
        if len(self.model_fields_set & {"garment_id", "outfit"}) != 1 or (self.garment_id is None and self.outfit is None):
            raise ValueError("Select a garment or an outfit")
        return self


def create_app(mode: Mode = "success", *, clock=time.time, ttl=1800, catalog_path=None, catalog_profile="studio", provider: TryOnProvider | None = None, monotonic=time.monotonic, provider_disabled: bool = False, usage_metrics: UsageMetrics | None = None, video_library: VideoLibrary | None = None, outfit_tagger: OutfitTagger | None = None) -> FastAPI:
    if mode not in {"success", "failure", "delayed", "timeout", "rate-limit"}:
        raise ValueError("Unknown mock mode")
    if not 0 < ttl <= 1800:
        raise ValueError("Session lifetime must be between 0 and 1800 seconds")
    if catalog_profile not in {"studio", "regression"}:
        raise ValueError("Unknown catalog profile")
    if provider is not None and mode != "success":
        raise ValueError("Mock failure modes cannot be combined with a real provider")
    if provider is not None and provider_disabled:
        raise ValueError("A provider cannot be active while real-provider calls are disabled")
    store = Store(mode, clock, ttl, catalog_path, catalog_profile, provider, monotonic,
                  provider_disabled, usage_metrics, video_library, outfit_tagger)

    @asynccontextmanager
    async def lifespan(app):
        async def cleanup():
            while True:
                await asyncio.sleep(1)
                await asyncio.to_thread(store.sweep)
        task = asyncio.create_task(cleanup())
        try:
            yield
        finally:
            task.cancel()
            with suppress(asyncio.CancelledError):
                await task
            with store.lock:
                store.guests.clear()
            if store.executor is not None:
                store.executor.shutdown(wait=True, cancel_futures=False)

    app = FastAPI(title="Smart Fitting Mirror backend", lifespan=lifespan)
    app.state.store = store
    app.add_middleware(BodyLimit)

    @app.exception_handler(Problem)
    async def handle_problem(request, problem):
        return JSONResponse({"error": problem.error}, status_code=problem.status,
                            headers={**problem.headers, "Cache-Control": "no-store"})

    @app.exception_handler(RequestValidationError)
    async def handle_validation(request, error):
        return await handle_problem(request, Problem(400, "INVALID_REQUEST", "请求字段缺失或格式不正确"))

    @app.exception_handler(HTTPException)
    async def handle_http(request, error):
        return await handle_problem(request, Problem(error.status_code, "INVALID_REQUEST", "请求路径或格式不正确"))

    @app.exception_handler(Exception)
    async def handle_unexpected(request, error):
        return await handle_problem(request, Problem(503, "SERVICE_UNAVAILABLE", "服务暂时不可用", True))

    def auth(request):
        return request.headers.get("authorization")

    def absolute_job(request, value):
        if value["result"]:
            value["result"] = {**value["result"], "image_url": str(request.base_url) + "api/v1/" + value["result"]["image_url"]}
        return value

    @app.get("/health")
    def health():
        mode = "provider-disabled" if store.provider_disabled else (store.mode if store.provider is None else store.provider.name)
        return {"status": "ok", "mode": mode,
                "is_mock": store.provider is None and not store.provider_disabled}

    @app.post("/api/v1/sessions", status_code=201)
    def sessions(request: Request):
        return JSONResponse(store.create_session(request.client.host if request.client else "local"), 201, headers={"Cache-Control": "no-store"})

    @app.get("/api/v1/garments")
    def garments(request: Request, q: str = Query(default="", max_length=128),
                 tag: str | None = Query(default=None, max_length=100)):
        with store.lock:
            guest = store.guest(auth(request))
            root = str(request.base_url) + "api/v1/assets/"
            items = [public_item(item, root) for item in guest.catalog.values() if matches(item, q, tag)]
        payload = {"items": items}
        if store.outfit_available:
            payload.update(outfit_available=True, accessories=[
                {**item, "thumbnail_url": str(request.base_url) + f"api/v1/outfit-accessories/{aid}.png",
                 "source": "ai_generated"}
                for aid, item in store.accessories.items() if (ACCESSORY_DIRECTORY / f"{aid}.png").is_file()
            ])
        return JSONResponse(payload, headers={"Cache-Control": "no-store"})

    @app.get("/api/v1/outfit-accessories/{aid}.png")
    def outfit_accessory(request: Request, aid: str):
        with store.lock:
            store.guest(auth(request))
        return FileResponse(store.accessory_path(aid), media_type="image/png", headers={"Cache-Control": "no-store"})

    @app.post("/api/v1/video-recommendations")
    def video_recommendations(request: Request, body: VideoRecommendationRequest):
        authorization = auth(request)
        with store.lock:
            guest = store.guest(authorization)
            store.rate("video-recommendations:" + guest.sid, 30)
            if body.makeup_ids:
                raise Problem(400, "INVALID_REQUEST", "妆容目录尚未接入，请暂时不要传 makeup_ids")
            if any(garment_id not in guest.catalog for garment_id in body.garment_ids):
                raise Problem(400, "INVALID_REQUEST", "服装ID不属于当前会话衣柜")
        analysis = store.analyze_outfit(authorization, body.semantic_job_id) if body.semantic_job_id else None
        with store.lock:
            guest = store.guest(authorization)
            try:
                result = store.video_library.recommend(body, guest.catalog, analysis)
            except ValueError:
                raise Problem(400, "INVALID_REQUEST", "服装或妆容上下文暂不可用")
            result.validate_context(body)
            result = store.video_library.public_urls(result, str(request.base_url))
        return JSONResponse(result.model_dump(mode="json"), headers={"Cache-Control": "no-store"})

    def video_media(video_id: str, poster: bool):
        # Public curated content, never a guest photo or generated result. Resolve
        # only catalogued, published/verified filenames, never arbitrary paths.
        path = store.video_library.media_path(video_id, poster=poster)
        if path is None:
            raise Problem(404, "RESOURCE_NOT_FOUND", "视频素材暂不可用")
        return FileResponse(path, media_type="image/jpeg" if poster else "video/mp4",
                            headers={"Cache-Control": "public, max-age=3600", "X-Content-Type-Options": "nosniff"})

    @app.api_route("/api/v1/video-media/{video_id}.mp4", methods=["GET", "HEAD"])
    def licensed_video(video_id: str):
        return video_media(video_id, False)

    @app.api_route("/api/v1/video-covers/{video_id}.jpg", methods=["GET", "HEAD"])
    def licensed_video_cover(video_id: str):
        return video_media(video_id, True)

    @app.get("/api/v1/garments/{gid}")
    def garment_detail(request: Request, gid: str):
        with store.lock:
            item = store.guest(auth(request)).catalog.get(gid)
            if item is None:
                raise Problem(404, "RESOURCE_NOT_FOUND", "服装不存在")
            value = public_item(item, str(request.base_url) + "api/v1/assets/")
        return JSONResponse(value, headers={"Cache-Control": "no-store"})

    def save_garment(request, body, *, replace):
        with store.lock:
            guest = store.guest(auth(request))
            exists = body.garment_id in guest.catalog
            if exists != replace:
                raise Problem(404 if replace else 409, "RESOURCE_NOT_FOUND" if replace else "GARMENT_EXISTS", "服装不存在" if replace else "服装编号已存在")
            if body.asset_id not in ASSET_SPECS:
                raise Problem(422, "INVALID_ASSET", "请选择目录中已有的图片素材")
            if body.category != ASSET_SPECS[body.asset_id]["category"]:
                raise Problem(422, "INVALID_ASSET", "品类必须与素材定位方式一致")
            if not body.name.strip() or any(not s.strip() or len(s) > 100 for s in [*body.tags, *body.details.sizes, *body.details.colors]):
                raise Problem(400, "INVALID_REQUEST", "名称、标签、尺寸或颜色格式无效")
            if not replace and len(guest.catalog) >= CATALOG_CAPACITY:
                raise Problem(429, "CATALOG_CAPACITY", f"临时衣柜最多 {CATALOG_CAPACITY} 件服装")
            guest.catalog[body.garment_id] = body
            value = public_item(body, str(request.base_url) + "api/v1/assets/")
        return JSONResponse(value, 200 if replace else 201, headers={"Cache-Control": "no-store"})

    @app.post("/api/v1/garments", status_code=201)
    def add_garment(request: Request, body: GarmentWrite):
        return save_garment(request, body, replace=False)

    @app.put("/api/v1/garments/{gid}")
    def update_garment(request: Request, gid: str, body: GarmentWrite):
        if gid != body.garment_id:
            raise Problem(400, "INVALID_REQUEST", "路径与服装编号不一致")
        return save_garment(request, body, replace=True)

    @app.delete("/api/v1/garments/{gid}", status_code=204)
    def delete_garment(request: Request, gid: str):
        with store.lock:
            guest = store.guest(auth(request))
            if guest.catalog.pop(gid, None) is None:
                raise Problem(404, "RESOURCE_NOT_FOUND", "服装不存在")
        return Response(status_code=204, headers={"Cache-Control": "no-store"})

    @app.post("/api/v1/uploads", status_code=201)
    async def upload(request: Request):
        with store.lock:
            store.guest(auth(request))
        async with request.form(max_files=1, max_fields=0) as form:
            files = form.getlist("file")
            if len(files) != 1 or not isinstance(files[0], UploadFile) or list(form.keys()) != ["file"]:
                raise Problem(400, "INVALID_REQUEST", "请仅上传一个名为 file 的文件字段")
            file = files[0]
            image = await run_in_threadpool(decode_photo, await file.read(MAX_FILE + 1), file.content_type)
        return JSONResponse(store.save_photo(auth(request), image), 201, headers={"Cache-Control": "no-store"})

    @app.post("/api/v1/tryon-jobs")
    def create_job(request: Request, body: CreateInput):
        value, status = store.create_job(auth(request), body.upload_id, body.garment_id,
                                         request.headers.get("idempotency-key"), body.outfit)
        return JSONResponse(absolute_job(request, value), status, headers={"Cache-Control": "no-store"})

    @app.get("/api/v1/tryon-jobs/{jid}")
    def get_job(request: Request, jid: str):
        return JSONResponse(absolute_job(request, store.get_job(auth(request), jid)), headers={"Cache-Control": "no-store"})

    @app.post("/api/v1/tryon-jobs/{jid}/cancel")
    def cancel_job(request: Request, jid: str):
        return JSONResponse(absolute_job(request, store.get_job(auth(request), jid, True)), headers={"Cache-Control": "no-store"})

    @app.delete("/api/v1/sessions/current", status_code=204)
    def end(request: Request):
        store.end(auth(request))
        return Response(status_code=204, headers={"Cache-Control": "no-store"})

    @app.get("/api/v1/assets/{gid}.png")
    def asset(request: Request, gid: str):
        with store.lock:
            store.guest(auth(request))
        if gid not in ASSET_SPECS:
            raise Problem(404, "RESOURCE_NOT_FOUND", "服装图片不存在")
        path = ASSETS / ASSET_SPECS[gid]["file"]
        if not path.is_file():
            raise Problem(503, "ASSET_NOT_INSTALLED", "请先安装本地商品素材包：python tools/install_studio_assets.py 素材包.zip", True)
        return Response(path.read_bytes(), media_type="image/png", headers={"Cache-Control": "no-store"})

    @app.get("/api/v1/results/{jid}.png")
    def result(request: Request, jid: str):
        with store.lock:
            guest = store.guest(auth(request))
            job = guest.jobs.get(jid)
            if job is None or job.value["status"] != "succeeded":
                raise Problem(404, "RESOURCE_NOT_FOUND", "结果不存在")
            return Response(job.result_bytes, media_type="image/png", headers={"Cache-Control": "no-store"})

    return app
