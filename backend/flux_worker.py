"""Private, single-GPU FLUX.2 klein 4B inference worker."""

from __future__ import annotations

import argparse
import asyncio
import io
import logging
import os
from pathlib import Path
from threading import Lock
import time
import warnings

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, Response
from PIL import Image, ImageOps
from python_multipart.exceptions import MultipartParseError
from starlette.datastructures import UploadFile
from starlette.exceptions import HTTPException
from starlette.requests import ClientDisconnect

from .provider_images import MAX_SOURCE_BYTES, MAX_SOURCE_PIXELS

MODEL_ID = "flux2-klein-4b-e7b7dc2"
MAX_REQUEST_BYTES = 2 * MAX_SOURCE_BYTES + 64 * 1024
MAX_RESULT_BYTES = 16 * 1024 * 1024
MAX_BUDGET_MS = 120_000
LOGGER = logging.getLogger(__name__)
PROMPT = (
    "Edit the photograph in the first image. Put the exact upper-body garment from the second product photo "
    "on the person in the first image, replacing their original top. Preserve the reference garment's color, "
    "looseness, neckline, sleeve length, cuffs, buttons, and any graphics or lettering as shown. Keep the person's "
    "face, expression, body shape, pose, hands, accessories, other clothing, and surrounding scene exactly as in "
    "the first image. Retain the camera framing, background, objects and lighting. Output only the edited first photo."
    " Copy only design elements that are visible on the outside fabric of the reference garment. Ignore "
    "retailer logos, labels, watermarks and all other elements in the reference photo's margins and background. "
    "Keep plain fabric areas completely blank. Keep the reference's exact closure and neckline. Add "
    "no badges, pockets, lettering, graphics or buttons absent from the garment itself."
)


class InvalidImage(ValueError):
    pass


def decode_image(data: bytes) -> Image.Image:
    try:
        with warnings.catch_warnings():
            warnings.simplefilter("error", Image.DecompressionBombWarning)
            with Image.open(io.BytesIO(data)) as source:
                if source.format not in {"JPEG", "PNG"} or source.width * source.height > MAX_SOURCE_PIXELS:
                    raise InvalidImage
                with ImageOps.exif_transpose(source) as upright:
                    upright.thumbnail((4096, 4096), Image.Resampling.LANCZOS)
                    if "A" in upright.getbands() or "transparency" in upright.info:
                        with upright.convert("RGBA") as rgba:
                            rgb = Image.new("RGB", rgba.size, "white")
                            rgb.paste(rgba, mask=rgba.getchannel("A"))
                    else:
                        rgb = upright.convert("RGB")
                    # FLUX aligns conditioning images to a VAE grid. Add a
                    # white margin for exceptionally narrow or tiny inputs.
                    width, height = rgb.size
                    min_edge = max(16, (max(width, height) + 15) // 16)
                    if min(width, height) < min_edge:
                        padded = Image.new("RGB", (max(min_edge, width), max(min_edge, height)), "white")
                        padded.paste(rgb, ((padded.width - width) // 2, (padded.height - height) // 2))
                        rgb.close()
                        rgb = padded
                    rgb.info.clear()
                    return rgb
    except (OSError, ValueError, SyntaxError, Image.DecompressionBombError, Image.DecompressionBombWarning) as error:
        raise InvalidImage from error


def output_size(person: Image.Image) -> tuple[int, int]:
    scale = 864 / max(person.size)
    width, height = person.size
    return max(16, round(width * scale / 16) * 16), max(16, round(height * scale / 16) * 16)


def check_deadline(deadline: float) -> None:
    if time.monotonic() >= deadline:
        raise TimeoutError


def error(status: int, code: str, message: str, *, retryable: bool = False) -> JSONResponse:
    return JSONResponse({"error": {"code": code, "message": message, "retryable": retryable}}, status_code=status)


def inference_timeout() -> JSONResponse:
    return error(504, "INFERENCE_TIMEOUT", "本地模型生成超时", retryable=True)


def create_app(infer) -> FastAPI:
    app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)
    slot = Lock()

    @app.get("/internal/health")
    async def health():
        return {"ready": True, "is_mock": False, "model_id": MODEL_ID}

    def run_job(person_bytes: bytes, garment_bytes: bytes, deadline: float) -> Response:
        started = time.monotonic()
        outcome = "failed"
        try:
            check_deadline(deadline)
            with decode_image(person_bytes) as person, decode_image(garment_bytes) as garment:
                check_deadline(deadline)
                with infer(person, garment, deadline) as image:
                    check_deadline(deadline)
                    encoded = io.BytesIO()
                    image.save(encoded, "PNG")
                    check_deadline(deadline)
            if encoded.tell() > MAX_RESULT_BYTES:
                return error(500, "MODEL_INVALID_RESULT", "生成结果过大")
            outcome = "succeeded"
            return Response(encoded.getvalue(), media_type="image/png", headers={"Cache-Control": "no-store"})
        except InvalidImage:
            return error(400, "INVALID_INPUT", "图片无法解码或尺寸超限")
        except TimeoutError:
            outcome = "timeout"
            return inference_timeout()
        except Exception as failure:
            LOGGER.error("inference failure class=%s", type(failure).__name__)
            return error(500, "MODEL_FAILED", "本地模型生成失败", retryable=True)
        finally:
            LOGGER.info("job outcome=%s worker_seconds=%.3f", outcome, time.monotonic() - started)
            slot.release()

    @app.post("/internal/tryon")
    async def tryon(request: Request):
        received_at = time.monotonic()
        if not slot.acquire(blocking=False):
            return error(503, "MODEL_BUSY", "本地模型正忙", retryable=True)
        job = None
        try:
            # The existing adapter always sends a fixed Content-Length. Reject
            # chunked requests so the HTTP parser bounds all multipart data.
            length = request.headers.get("content-length", "")
            if request.headers.get("transfer-encoding") or not length.isascii() or not length.isdecimal() or len(length) > 10:
                return error(400, "INVALID_INPUT", "需要固定长度的 multipart 请求")
            if not 0 < int(length) <= MAX_REQUEST_BYTES:
                return error(400, "INVALID_INPUT", "请求图片过大")

            async with request.form(max_files=2, max_fields=2, max_part_size=64) as form:
                if len(form.multi_items()) != 4 or set(form) != {"person_image", "garment_image", "cloth_type", "budget_ms"}:
                    return error(400, "INVALID_INPUT", "试衣请求字段不完整")
                person, garment = form["person_image"], form["garment_image"]
                cloth_type, budget = form["cloth_type"], form["budget_ms"]
                if not all(isinstance(item, UploadFile) and 0 < (item.size or 0) <= MAX_SOURCE_BYTES for item in (person, garment)):
                    return error(400, "INVALID_INPUT", "试衣图片缺失或过大")
                if not isinstance(cloth_type, str) or not isinstance(budget, str) or not budget.isascii() or not budget.isdecimal():
                    return error(400, "INVALID_INPUT", "试衣参数无效")
                if cloth_type != "tops":
                    return error(422, "UNSUPPORTED_CLOTH_TYPE", "本地模型目前仅支持上衣")
                if not 1 <= int(budget) <= MAX_BUDGET_MS:
                    return error(400, "INVALID_INPUT", "等待时限无效")
                deadline = received_at + int(budget) / 1000
                person_bytes, garment_bytes = await person.read(), await garment.read()
            job = asyncio.create_task(asyncio.to_thread(run_job, person_bytes, garment_bytes, deadline))
            # asyncio.wait does not cancel inference on a deadline or caller
            # disconnect. Only run_job releases the slot after GPU work ends.
            finished, _ = await asyncio.wait({job}, timeout=max(0, deadline - time.monotonic()))
            return job.result() if finished else inference_timeout()
        except (HTTPException, ClientDisconnect, MultipartParseError):
            return error(400, "INVALID_INPUT", "无法读取 multipart 图片")
        finally:
            if job is None:
                slot.release()

    return app


def load_flux(weights: Path):
    import torch
    from diffusers import Flux2KleinPipeline

    if torch.cuda.device_count() != 1 or not torch.cuda.is_bf16_supported():
        raise RuntimeError("Select one BF16-capable GPU with CUDA_VISIBLE_DEVICES")
    device = torch.device("cuda:0")
    started = time.monotonic()
    pipe = Flux2KleinPipeline.from_pretrained(str(weights), torch_dtype=torch.bfloat16, local_files_only=True).to(device)
    pipe.set_progress_bar_config(disable=True)
    torch.cuda.synchronize(device)
    LOGGER.info("model=%s load_seconds=%.3f", MODEL_ID, time.monotonic() - started)

    def infer(person: Image.Image, garment: Image.Image, deadline: float) -> Image.Image:
        def check_step(_pipe, _index, _timestep, values):
            check_deadline(deadline)
            return values

        width, height = output_size(person)
        torch.cuda.reset_peak_memory_stats(device)
        try:
            return pipe(
                image=[person, garment], prompt=PROMPT, width=width, height=height,
                num_inference_steps=4, guidance_scale=1.0,
                generator=torch.Generator(device=device).manual_seed(42),
                callback_on_step_end=check_step, callback_on_step_end_tensor_inputs=[],
            ).images[0]
        finally:
            torch.cuda.synchronize(device)
            LOGGER.info("peak_allocated_mib=%.1f", torch.cuda.max_memory_allocated(device) / 2**20)

    return infer


def main():
    import uvicorn

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--weights", type=Path, required=True)
    parser.add_argument("--port", type=int, default=8787)
    args = parser.parse_args()
    if not (args.weights / "model_index.json").is_file():
        parser.error("Expected the verified local FLUX weights directory")
    os.environ["HF_HUB_OFFLINE"] = "1"
    os.environ["TRANSFORMERS_OFFLINE"] = "1"
    logging.basicConfig(level=logging.INFO)
    uvicorn.run(
        create_app(load_flux(args.weights)), host="127.0.0.1", port=args.port,
        workers=1, access_log=False, proxy_headers=False, http="h11", server_header=False,
    )


if __name__ == "__main__":
    main()
