"""Create bounded Aidge inputs without changing the stored/uploaded original."""
from __future__ import annotations

import io
import warnings
from pathlib import Path

from PIL import Image, ImageOps, UnidentifiedImageError

from .provider_control import GenerationBudget, ProviderFailure

MIN_EDGE = 256
MAX_EDGE = 2049
MAX_ENCODED_BYTES = 10_000_000
MAX_SOURCE_PIXELS = 16_000_000
MAX_SOURCE_BYTES = 64 * 1024 * 1024


def prepare_input(data: bytes, destination: Path, role: str, budget: GenerationBudget) -> None:
    """Resize down, flatten transparency on white, strip metadata and encode JPEG.

    Never stretch a person, upscale a tiny input or crop off parts of a garment.
    Both images are prepared before either is transmitted to COS.
    """
    budget.check()
    try:
        if len(data) > MAX_SOURCE_BYTES:
            raise ValueError("source too large")
        with warnings.catch_warnings():
            warnings.simplefilter("error", Image.DecompressionBombWarning)
            with Image.open(io.BytesIO(data)) as source:
                if source.format not in {"JPEG", "PNG", "WEBP", "BMP"} or source.width * source.height > MAX_SOURCE_PIXELS:
                    raise ValueError("unsupported image")
                with ImageOps.exif_transpose(source) as upright:
                    if min(upright.size) < MIN_EDGE:
                        raise ValueError("input too small")
                    with ImageOps.contain(upright, (MAX_EDGE, MAX_EDGE), Image.Resampling.LANCZOS) as fitted:
                        if min(fitted.size) < MIN_EDGE:
                            raise ValueError("aspect ratio too extreme")
                        with fitted.convert("RGBA") as rgba, Image.new("RGB", fitted.size, "white") as rgb:
                            rgb.paste(rgba, mask=rgba.getchannel("A"))
                            rgb.info.clear()
                            for quality in (95, 85):
                                budget.check()
                                output = io.BytesIO()
                                rgb.save(output, "JPEG", quality=quality)
                                encoded = output.getvalue()
                                if len(encoded) <= MAX_ENCODED_BYTES:
                                    budget.check()
                                    destination.write_bytes(encoded)
                                    return
                            raise ValueError("encoded image too large")
    except ProviderFailure:
        raise
    except (UnidentifiedImageError, OSError, ValueError, SyntaxError, Image.DecompressionBombError, Image.DecompressionBombWarning) as error:
        raise ProviderFailure("PROVIDER_INVALID_INPUT", f"{role}图片不符合生成要求，请使用清晰的常规比例图片重试") from error
