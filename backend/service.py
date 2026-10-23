from __future__ import annotations

import asyncio
import hashlib
import hmac
import io
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
from fastapi.responses import JSONResponse, Response
from pydantic import BaseModel, ConfigDict, Field
from starlette.concurrency import run_in_threadpool
from starlette.datastructures import UploadFile
from starlette.exceptions import HTTPException
from PIL import Image, ImageDraw, ImageOps, UnidentifiedImageError
from .catalog import ASSET_SPECS, CATALOG_CAPACITY, GarmentWrite, initial_catalog, regression_catalog, public_item, matches
from .catalog_repository import CatalogRepository
from .tryon_provider import ProviderFailure, ProviderResult, TryOnProvider
from .provider_control import GenerationBudget

MAX_FILE = 10 * 1024 * 1024
MAX_BODY = MAX_FILE + 64 * 1024
MAX_PIXELS = 16_000_000
MAX_STORED = 128 * 1024 * 1024
ASSETS = Path(__file__).parent / "assets"
TERMINAL = {"succeeded", "failed", "cancelled"}
Mode = Literal["success", "failure", "delayed", "timeout", "rate-limit"]


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
    asset_id: str
    result_bytes: bytes | None = None
    is_mock: bool = True
    budget: GenerationBudget | None = None


@dataclass
class Guest:
    sid: str
    expires: float
    uploads: dict[str, Photo] = field(default_factory=dict)
    jobs: dict[str, Job] = field(default_factory=dict)
    keys: dict[str, tuple[tuple[str, str], str]] = field(default_factory=dict)
    throttled_keys: set[str] = field(default_factory=set)
    catalog: dict[str, GarmentWrite] = field(default_factory=initial_catalog)


class Store:
    """One-process service. State transitions and ownership checks share a lock."""
    def __init__(self, mode: Mode = "success", clock: Callable[[], float] = time.time, ttl: float = 1800, catalog_path=None, catalog_profile="studio", provider: TryOnProvider | None = None, monotonic: Callable[[], float] = time.monotonic):
        self.mode, self.clock, self.ttl = mode, clock, ttl
        self.monotonic = monotonic
        self.provider = provider
        # The current Aidge account has QPS 1. Keep one real provider task in
        # flight and reject additional work instead of building a paid queue.
        self.executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="tryon-provider") if provider else None
        self.lock = threading.RLock()
        self.guests: dict[str, Guest] = {}
        self.provider_inflight: set[tuple[str, str]] = set()
        self.buckets: dict[str, deque] = defaultdict(deque)
        self.secret = secrets.token_bytes(32)
        self.catalog_factory = regression_catalog if catalog_profile == "regression" else initial_catalog
        self.catalog_repository = CatalogRepository(catalog_path,profile=catalog_profile) if catalog_path is not None else None

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

    def create_job(self, authorization, upload_id, garment_id, key):
        try:
            key = str(uuid.UUID(key))
        except (ValueError, TypeError, AttributeError):
            raise Problem(400, "IDEMPOTENCY_KEY_REQUIRED", "请提供 UUID 格式的 Idempotency-Key")
        work = None
        with self.lock:
            guest = self.guest(authorization)
            fingerprint = (upload_id, garment_id)
            if key in guest.keys:
                prior, jid = guest.keys[key]
                if prior != fingerprint:
                    raise Problem(409, "IDEMPOTENCY_CONFLICT", "相同幂等键不能用于不同输入")
                return guest.jobs[jid].value.copy(), 200
            if upload_id not in guest.uploads or garment_id not in guest.catalog:
                raise Problem(404, "RESOURCE_NOT_FOUND", "上传或服装不存在")
            asset_id = guest.catalog[garment_id].asset_id
            if not ASSET_SPECS[asset_id]["generatable"]:
                raise Problem(422, "GARMENT_NOT_GENERATABLE", "该服装仅支持实时预览")
            garment_path = self.asset_path(asset_id) if self.provider is not None else None
            cloth_type = self.cloth_type(guest.catalog[garment_id].category) if self.provider is not None else None
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
            budget = GenerationBudget(120, clock=self.monotonic) if self.provider is not None else None
            guest.jobs[jid] = Job(value, now, self.mode, asset_id, is_mock=self.provider is None, budget=budget)
            guest.keys[key] = fingerprint, jid
            if self.provider is not None:
                assert garment_path is not None and cloth_type is not None
                work = (guest.sid, jid, bytes(guest.uploads[upload_id].data), garment_path, cloth_type)
                self.provider_inflight.add((guest.sid, jid))
            response = value.copy(), 202
        if work is not None:
            assert self.executor is not None
            self.executor.submit(self.run_provider, *work)
        return response

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

    def run_provider(self, sid: str, jid: str, model_image: bytes, garment_path: Path, cloth_type: str):
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

    def advance(self, guest: Guest, job: Job):
        if job.value["status"] in TERMINAL:
            return
        if not job.is_mock:
            assert job.budget is not None
            if job.budget.expired:
                job.value.update(status="failed", result=None,
                    error=detail("GENERATION_TIMEOUT", "虚拟试衣超过120秒总等待时限", True),
                    updated_at=stamp(self.clock()))
            # Keep the upstream slot until the worker and its cleanup actually
            # finish, even after local timeout/cancel. Never submit an overlap.
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

    def get_job(self, authorization, jid, cancel=False):
        with self.lock:
            guest = self.guest(authorization)
            job = guest.jobs.get(jid)
            if job is None:
                raise Problem(404, "RESOURCE_NOT_FOUND", "任务不存在")
            if cancel and job.value["status"] not in TERMINAL:
                job.value["status"] = "cancelled"
                job.value["updated_at"] = stamp(self.clock())
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


def mock_result(photo: Photo, asset_id: str) -> bytes:
    with Image.open(io.BytesIO(photo.data)) as original, Image.open(ASSETS / ASSET_SPECS[asset_id]["file"]) as shirt:
        result = original.convert("RGBA")
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
    garment_id: str = Field(min_length=1, max_length=128)


def create_app(mode: Mode = "success", *, clock=time.time, ttl=1800, catalog_path=None, catalog_profile="studio", provider: TryOnProvider | None = None, monotonic=time.monotonic) -> FastAPI:
    if mode not in {"success", "failure", "delayed", "timeout", "rate-limit"}:
        raise ValueError("Unknown mock mode")
    if not 0 < ttl <= 1800:
        raise ValueError("Session lifetime must be between 0 and 1800 seconds")
    if catalog_profile not in {"studio", "regression"}:
        raise ValueError("Unknown catalog profile")
    if provider is not None and mode != "success":
        raise ValueError("Mock failure modes cannot be combined with a real provider")
    store = Store(mode, clock, ttl, catalog_path, catalog_profile, provider, monotonic)

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
        return {"status": "ok", "mode": store.mode if store.provider is None else store.provider.name,
                "is_mock": store.provider is None}

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
        return JSONResponse({"items": items}, headers={"Cache-Control": "no-store"})

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
        value, status = store.create_job(auth(request), body.upload_id, body.garment_id, request.headers.get("idempotency-key"))
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
