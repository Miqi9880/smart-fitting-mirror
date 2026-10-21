"""Course adaptation of SmartMirror Clothing/List/Detail/AddClothes.

Bundled assets are immutable. Each temporary guest may edit its own wardrobe;
ending/expiring the session removes that metadata with the rest of the session.
See third_party/SmartMirror/NOTICE.md for source and permission.
"""
from copy import deepcopy
import json
from pathlib import Path
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


class GarmentDetails(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    brand: str = Field(default="", max_length=100)
    material: str = Field(default="", max_length=100)
    description: str = Field(default="", max_length=1000)
    sizes: list[str] = Field(default_factory=list, max_length=20)
    colors: list[str] = Field(default_factory=list, max_length=20)
    season: str = Field(default="", max_length=100)
    care_instructions: str = Field(default="", max_length=500)
    price_cents: int | None = Field(default=None, ge=0, le=100_000_000)


class GarmentWrite(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    garment_id: str = Field(pattern=r"^[a-z][a-z0-9_]{0,63}$")
    name: str = Field(min_length=1, max_length=100)
    category: Literal["upper_body", "lower_body", "headwear", "dress", "skirt", "shorts"] = "upper_body"
    tags: list[str] = Field(default_factory=list, max_length=20)
    asset_id: str = Field(pattern=r"^[a-z][a-z0-9_]{0,63}$")
    details: GarmentDetails = Field(default_factory=GarmentDetails)


ANCHORS = {"left_shoulder": {"x": .75, "y": .15}, "right_shoulder": {"x": .25, "y": .15},
           "left_hip": {"x": .7, "y": .85}, "right_hip": {"x": .3, "y": .85}}
ASSET_SPECS = {name: {"file": name + ".png", "width": 600, "height": 800, "category": "upper_body",
                     "anchors": ANCHORS, "generatable": name != "upper_preview"}
               for name in ("upper_blue", "upper_sand", "upper_preview")}
ASSET_SPECS["real_white_tee"] = {"file":"real/white-tee-cutout.png", "width":1448, "height":1086, "category":"upper_body",
    "anchors":{"left_shoulder":{"x":.72,"y":.13},"right_shoulder":{"x":.25,"y":.13},
               "left_hip":{"x":.70,"y":.78},"right_hip":{"x":.30,"y":.78}}, "generatable":True}


CATALOG_CAPACITY = 100
STUDIO_MANIFEST = json.loads((Path(__file__).parent / "assets/studio/manifest.json").read_text(encoding="utf-8"))
for _item in STUDIO_MANIFEST["items"]:
    ASSET_SPECS[_item["garment_id"]] = {**_item, "file": "studio/" + _item["file"]}


def regression_catalog():
    """Historical synthetic fixtures, explicitly opt-in for deterministic regression only."""
    items = {name: GarmentWrite(garment_id=name, name=title, tags=["demo", color], asset_id=name,
                details=GarmentDetails(description="绘制演示服装，用于联调，不是实物摄影素材。", colors=[color]))
            for name, title, color in (("upper_blue", "蓝色休闲上衣", "blue"),
                                      ("upper_sand", "沙色休闲上衣", "sand"),
                                      ("upper_preview", "绿色上衣（仅预览）", "green"))}
    items["real_white_tee"] = GarmentWrite(garment_id="real_white_tee",name="实拍白色 T 恤（辅助去背景）",
        tags=["photo", "white", "background-edited"], asset_id="real_white_tee",
        details=GarmentDetails(colors=["白色"], description="源自 Elkagye 的公有领域实拍照片；使用 imagegen 辅助去背景，非逐像素无损抠图。用于试衣原型，非商品精确展示。"))
    return items


def initial_catalog():
    return {item["garment_id"]: GarmentWrite(garment_id=item["garment_id"], name=item["name"],
        category=item["category"], asset_id=item["garment_id"], tags=["photo", "official-studio", item["category"]],
        details=GarmentDetails(brand=item["brand"],colors=[item["color"]],
            description="品牌官网商品展示图，辅助去背景的课程预览素材。" + item["source_url"]))
        for item in STUDIO_MANIFEST["items"]}


def public_item(item: GarmentWrite, root: str):
    asset = ASSET_SPECS[item.asset_id]
    url = root + item.asset_id + ".png"
    return {"garment_id": item.garment_id, "name": item.name, "category": item.category,
            "tags": item.tags, "thumbnail_url": url,
            "preview_image": {"url": url, "width": asset["width"], "height": asset["height"],
                              "anchors": deepcopy(asset["anchors"]),
                              **({"attachment_anchors":deepcopy(asset["attachment_anchors"])} if asset.get("attachment_anchors") else {}),
                              **({"hem_ratio":asset["hem_ratio"]} if asset.get("hem_ratio") is not None else {})},
            "generation_reference_url": url if asset["generatable"] else None,
            "details": item.details.model_dump()}


def matches(item: GarmentWrite, query: str, tag: str | None):
    words = query.casefold().split()
    haystack = " ".join([item.name, *item.tags, item.details.brand, item.details.material,
                         item.details.description, item.details.season]).casefold()
    def term(word):
        return word in haystack or (len(word) > 1 and all('\u4e00' <= c <= '\u9fff' for c in word)
                                   and all(c in haystack for c in word))
    return (tag is None or tag in item.tags) and all(term(word) for word in words)
