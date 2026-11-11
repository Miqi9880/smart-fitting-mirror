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
from contextlib import ExitStack

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, Response
from PIL import Image, ImageOps
from python_multipart.exceptions import MultipartParseError
from starlette.datastructures import UploadFile
from starlette.exceptions import HTTPException
from starlette.requests import ClientDisconnect

from .provider_images import MAX_SOURCE_BYTES, MAX_SOURCE_PIXELS
from .outfit import LOWER_KINDS, MAX_MATERIALS, MAX_OUTFIT_REFERENCE_BYTES, valid_kinds
from .flux_fidelity import PersonParser, neutralize_clothes, protected_face

MODEL_ID = "flux2-klein-4b-e7b7dc2"
MAX_REQUEST_BYTES = 2 * MAX_SOURCE_BYTES + 64 * 1024
MAX_OUTFIT_REQUEST_BYTES = MAX_SOURCE_BYTES + MAX_MATERIALS * MAX_OUTFIT_REFERENCE_BYTES + 64 * 1024
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
    "no badges, pockets, lettering, graphics or buttons absent from the garment itself. "
    "Take all garment design and construction from the product reference, including its silhouette and hem. "
    "Do not retain the original top's design or merge the two tops."
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


def create_app(infer, infer_outfit=None) -> FastAPI:
    app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)
    slot = Lock()

    @app.get("/internal/health")
    async def health():
        payload = {"ready": True, "is_mock": False, "model_id": MODEL_ID}
        if infer_outfit is not None:
            payload["outfit_supported"] = True
        return payload

    def run_job(person_bytes: bytes, garment_bytes: list[bytes], kinds: list[str] | None, deadline: float) -> Response:
        started = time.monotonic()
        outcome = "failed"
        try:
            check_deadline(deadline)
            with ExitStack() as images:
                person = images.enter_context(decode_image(person_bytes))
                garments = [images.enter_context(decode_image(data)) for data in garment_bytes]
                check_deadline(deadline)
                result = infer(person, garments[0], deadline) if kinds is None else infer_outfit(person, list(zip(kinds, garments)), deadline)
                with result as image:
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
    @app.post("/internal/outfit")
    async def tryon(request: Request):
        outfit = request.url.path.endswith("/outfit")
        if outfit and infer_outfit is None:
            return error(503, "MODEL_NOT_READY", "搭配生成服务尚未启用")
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
            if not 0 < int(length) <= (MAX_OUTFIT_REQUEST_BYTES if outfit else MAX_REQUEST_BYTES):
                return error(400, "INVALID_INPUT", "请求图片过大")

            async with request.form(max_files=1 + (MAX_MATERIALS if outfit else 1), max_fields=2, max_part_size=96) as form:
                kind_field = "kinds" if outfit else "cloth_type"
                garments = form.getlist("garment_image")
                if len(form.multi_items()) != 3 + len(garments) or set(form) != {"person_image", "garment_image", kind_field, "budget_ms"}:
                    return error(400, "INVALID_INPUT", "试衣请求字段不完整")
                person = form["person_image"]
                material_limit = MAX_OUTFIT_REFERENCE_BYTES if outfit else MAX_SOURCE_BYTES
                if (not isinstance(person, UploadFile) or not 0 < (person.size or 0) <= MAX_SOURCE_BYTES
                        or not all(isinstance(item, UploadFile) and 0 < (item.size or 0) <= material_limit for item in garments)):
                    return error(400, "INVALID_INPUT", "试衣图片缺失或过大")
                kind_value, budget = form[kind_field], form["budget_ms"]
                if not isinstance(kind_value, str) or not isinstance(budget, str) or not budget.isascii() or not budget.isdecimal():
                    return error(400, "INVALID_INPUT", "试衣参数无效")
                kinds = kind_value.split(",") if outfit else None
                if kinds is not None and (len(kinds) != len(garments) or not valid_kinds(kinds)):
                    return error(400, "INVALID_INPUT", "搭配参考图片和品类不匹配")
                if not outfit and kind_value != "tops":
                    return error(422, "UNSUPPORTED_CLOTH_TYPE", "本地模型目前仅支持上衣")
                if not 1 <= int(budget) <= MAX_BUDGET_MS:
                    return error(400, "INVALID_INPUT", "等待时限无效")
                deadline = received_at + int(budget) / 1000
                person_bytes = await person.read()
                garment_bytes = [await garment.read() for garment in garments]
            job = asyncio.create_task(asyncio.to_thread(run_job, person_bytes, garment_bytes, kinds, deadline))
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


OUTFIT_EDITS = {
    "lower_body": "Replace the visible lower garment with the exact long trousers in reference {index}.",
    "skirt": "Replace the visible lower garment with the exact skirt in reference {index}, keeping its length and drape.",
    "shorts": "Replace the visible lower garment with the exact shorts in reference {index}, keeping their length.",
    "upper_body": "Replace only the upper garment with the exact top in reference {index}, including its collar, closures and cuffs.",
    "dress": "Replace the upper and lower garments with the single dress in reference {index}, keeping its neckline, cut and hem length.",
    "headwear": "Place the exact hat in reference {index} on the head, keeping the eyes visible.",
    "belt": "Place the belt in reference {index} around the waist, on the outer clothing layer, copying the buckle and color.",
    "earrings": "Copy the exact matching pair in reference {index}, including its shape and finish. Place them at natural scale on visible earlobes behind any overlapping hair.",
}


def outfit_stages(references):
    # Resolve the lower garment against the original hem before changing the
    # upper garment, so the waistband reference does not pull the top upwards.
    groups = (LOWER_KINDS, {"upper_body", "dress", "headwear"}, {"belt"}, {"earrings"})
    return [stage for group in groups if (stage := [(kind, image) for kind, image in references if kind in group])]


def outfit_prompt(stage):
    edits = " ".join(OUTFIT_EDITS[kind].format(index=index) for index, (kind, _) in enumerate(stage, 2))
    waist = ""
    if any(kind in LOWER_KINDS for kind, _ in stage):
        waist = (
            "Keep the unselected top and its visible waist overlap unchanged. "
            "An outside hem remains over the waistband, and a tucked hem stays behind it. "
        )
    if any(kind == "upper_body" for kind, _ in stage):
        waist = (
            "Keep the reference top's hem length and wearing style. If the reference shows only a product, "
            "let the entire hem hang outside the waistband. Do not invent a partial tuck. "
        )
    return (
        "Edit photo 1 using the numbered product references. " + edits +
        " Keep the original person's face, pose, hands, body proportions, shoes, framing and background. "
        "Leave every unselected area, item and occlusion in photo 1 unchanged. " + waist +
        "Use the reference as the only source for each replacement garment's silhouette, color, fabric, "
        "neckline, sleeve length, closure, pockets, pattern and hem. Remove the original garment's design "
        "and layers completely within each selected region. Do not blend old and new garment details. "
        "Add only the selected items and only the design elements visible on the reference garments."
    )


def generate_stages(person, stages, deadline, infer_photos, parser=None):
    size = output_size(person)
    with ExitStack() as images:
        original = person
        if parser is not None:
            check_deadline(deadline)
            original = images.enter_context(person.resize(size, Image.Resampling.LANCZOS))
            segmentation = images.enter_context(parser(original))
            face = images.enter_context(protected_face(segmentation, parser.labels))
        current = original
        try:
            for references, prompt in stages:
                with ExitStack() as stage:
                    conditioned = current
                    if parser is not None:
                        conditioned = stage.enter_context(neutralize_clothes(
                            current, segmentation, parser.labels, (kind for kind, _ in references)
                        ))
                    result = infer_photos([conditioned, *(image for _, image in references)], prompt, size, deadline)
                    if parser is not None:
                        with result:
                            result = Image.composite(original, result, face)
                if current is not original:
                    current.close()
                current = result
            return current
        except BaseException:
            if current is not original:
                current.close()
            raise


def load_flux(weights: Path, parser_weights: Path | None = None):
    import torch
    from diffusers import Flux2KleinPipeline

    if torch.cuda.device_count() != 1 or not torch.cuda.is_bf16_supported():
        raise RuntimeError("Select one BF16-capable GPU with CUDA_VISIBLE_DEVICES")
    device = torch.device("cuda:0")
    started = time.monotonic()
    pipe = Flux2KleinPipeline.from_pretrained(str(weights), torch_dtype=torch.bfloat16, local_files_only=True).to(device)
    pipe.set_progress_bar_config(disable=True)
    parser = PersonParser(parser_weights, device) if parser_weights is not None else None
    torch.cuda.synchronize(device)
    LOGGER.info("model=%s load_seconds=%.3f", MODEL_ID, time.monotonic() - started)

    def infer_photos(photos, prompt, size, deadline):
        def check_step(_pipe, _index, _timestep, values):
            check_deadline(deadline)
            return values

        check_deadline(deadline)
        width, height = size
        torch.cuda.reset_peak_memory_stats(device)
        try:
            return pipe(
                image=photos, prompt=prompt, width=width, height=height,
                num_inference_steps=4, guidance_scale=1.0,
                generator=torch.Generator(device=device).manual_seed(42),
                callback_on_step_end=check_step, callback_on_step_end_tensor_inputs=[],
            ).images[0]
        finally:
            torch.cuda.synchronize(device)
            LOGGER.info("peak_allocated_mib=%.1f", torch.cuda.max_memory_allocated(device) / 2**20)

    def infer(person: Image.Image, garment: Image.Image, deadline: float) -> Image.Image:
        return generate_stages(person, [([("upper_body", garment)], PROMPT)], deadline, infer_photos, parser)

    def infer_outfit(person, references, deadline):
        stages = [(stage, outfit_prompt(stage)) for stage in outfit_stages(references)]
        return generate_stages(person, stages, deadline, infer_photos, parser)

    return infer, infer_outfit


def main():
    import uvicorn

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--weights", type=Path, required=True)
    parser.add_argument("--person-parser", type=Path)
    parser.add_argument("--port", type=int, default=8787)
    args = parser.parse_args()
    if not (args.weights / "model_index.json").is_file():
        parser.error("Expected the verified local FLUX weights directory")
    if args.person_parser is not None and not (args.person_parser / "model.safetensors").is_file():
        parser.error("Expected the verified local person parsing weights directory")
    os.environ["HF_HUB_OFFLINE"] = "1"
    os.environ["TRANSFORMERS_OFFLINE"] = "1"
    logging.basicConfig(level=logging.INFO)
    uvicorn.run(
        create_app(*load_flux(args.weights, args.person_parser)), host="127.0.0.1", port=args.port,
        workers=1, access_log=False, proxy_headers=False, http="h11", server_header=False,
    )


if __name__ == "__main__":
    main()
