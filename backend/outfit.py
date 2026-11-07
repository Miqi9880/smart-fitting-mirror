"""Shared limits and material categories for photo outfit generation."""
import json
from pathlib import Path
from typing import Annotated

from pydantic import BaseModel, ConfigDict, Field

ACCESSORY_DIRECTORY = Path(__file__).parent / "assets" / "accessories"
MAX_MATERIALS = 5
MAX_OUTFIT_REFERENCE_BYTES = 8 * 1024 * 1024
MaterialId = Annotated[str, Field(min_length=1, max_length=128)]
LOWER_KINDS = {"lower_body", "skirt", "shorts"}
MATERIAL_KINDS = LOWER_KINDS | {"upper_body", "dress", "headwear", "belt", "earrings"}


class OutfitSelection(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    garment_ids: list[MaterialId] = Field(default_factory=list, max_length=3)
    accessory_ids: list[MaterialId] = Field(default_factory=list, max_length=2)


def valid_kinds(kinds: list[str]) -> bool:
    selected = set(kinds)
    return (
        1 <= len(kinds) <= MAX_MATERIALS
        and len(selected) == len(kinds)
        and selected <= MATERIAL_KINDS
        and len(selected & LOWER_KINDS) <= 1
        and not ("dress" in selected and selected & (LOWER_KINDS | {"upper_body"}))
    )


def accessory_catalog() -> dict:
    manifest = ACCESSORY_DIRECTORY / "manifest.json"
    return {item["accessory_id"]: item for item in json.loads(manifest.read_text(encoding="utf-8"))["items"]}
