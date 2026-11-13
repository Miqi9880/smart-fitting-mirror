"""Extract a bounded set of outfit tags with a local vision-language model."""

from base64 import b64encode
from io import BytesIO
import json
from typing import Literal, Protocol

from PIL import Image, ImageOps
from pydantic import BaseModel, ConfigDict
import requests

from .provider_images import MAX_SOURCE_BYTES, MAX_SOURCE_PIXELS
from .video_contracts import VideoTag

MODEL = "qwen3-vl:2b-instruct"
ENDPOINT = "http://127.0.0.1:11434/api/chat"
MAX_RESPONSE_BYTES = 32 * 1024

PROMPT = (
    "只分析模特身上实际可见的服装。忽略脸、肤色、头发、帽子、配饰、鞋和背景。"
    "main_color 是上衣或连衣裙的面料主色，bottom_color 是独立的裤子或半裙面料主色。"
    "连衣裙是单件，bottom_color 必须为 null。看不到下装或颜色不在选项内时填 null。"
    "style 按可见剪裁和装饰判断。低装饰且线条干净可选简约，宽松便装或印花可选休闲，"
    "精致收腰或垂坠设计可选优雅。scene 是适合的穿着场景，别根据背景推断。"
    "风格或场景没有足够证据就填 null。只返回约定的 JSON。"
)

Color = Literal["黑色", "白色", "蓝色", "灰色", "绿色", "红色", "棕色", "米色", "紫色", "黄色", "橙色", "粉色"]

class SemanticUnavailable(RuntimeError):
    """Model or result was unavailable. Callers may use ordinary rule-based recall."""


class OutfitTagger(Protocol):
    def tags(self, image: bytes) -> list[VideoTag]: ...


class OutfitTags(BaseModel):
    model_config = ConfigDict(strict=True, extra="forbid")
    style: Literal["休闲", "简约", "优雅"] | None
    scene: Literal["日常", "通勤", "街头", "户外", "聚会"] | None
    main_color: Color | None
    bottom_color: Color | None

    def for_video(self) -> list[VideoTag]:
        tags = []
        if self.style is not None:
            tags.append(VideoTag(dimension="style", value=self.style))
        if self.scene is not None:
            tags.append(VideoTag(dimension="scene", value=self.scene))
        tags.extend(VideoTag(dimension="color", value=value)
                    for value in dict.fromkeys((self.main_color, self.bottom_color)) if value is not None)
        return tags


def _image_for_model(data: bytes) -> str:
    if not data or len(data) > MAX_SOURCE_BYTES:
        raise SemanticUnavailable("Unsupported image size")
    with Image.open(BytesIO(data)) as source:
        if source.format not in {"PNG", "JPEG"} or source.width * source.height > MAX_SOURCE_PIXELS:
            raise SemanticUnavailable("Unsupported image format")
        with ImageOps.exif_transpose(source) as upright:
            upright.thumbnail((512, 512), Image.Resampling.LANCZOS)
            with upright.convert("RGBA") as rgba, Image.new("RGB", upright.size, "white") as rgb:
                rgb.paste(rgba, mask=rgba.getchannel("A"))
                output = BytesIO()
                rgb.save(output, "JPEG", quality=86)
    return b64encode(output.getvalue()).decode("ascii")


class OllamaOutfitTagger:
    def tags(self, image: bytes) -> list[VideoTag]:
        try:
            payload = {
                "model": MODEL,
                "messages": [{"role": "user", "content": PROMPT, "images": [_image_for_model(image)]}],
                "format": OutfitTags.model_json_schema(),
                "stream": False,
                "think": False,
                "keep_alive": "60s",
                "options": {"temperature": 0, "seed": 42, "num_ctx": 4096, "num_predict": 96},
            }
            with requests.Session() as session:
                session.trust_env = False
                with session.post(ENDPOINT, json=payload, allow_redirects=False, timeout=(2, 35), stream=True) as response:
                    if response.status_code != 200:
                        raise SemanticUnavailable("Local model unavailable")
                    data = bytearray()
                    for chunk in response.iter_content(4096):
                        data.extend(chunk)
                        if len(data) > MAX_RESPONSE_BYTES:
                            raise SemanticUnavailable("Model response too large")
            content = json.loads(data)["message"]["content"]
            return OutfitTags.model_validate_json(content).for_video()
        except (requests.RequestException, OSError, Image.DecompressionBombError,
                KeyError, TypeError, ValueError) as error:
            raise SemanticUnavailable("Local vision tagging failed") from error
