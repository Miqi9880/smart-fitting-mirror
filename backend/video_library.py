"""Curated video catalog metadata and deterministic rule-based recommendations."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Iterable

from pydantic import BaseModel, ConfigDict, ValidationError, Field

from .catalog import GarmentWrite
from .video_contracts import (
    VideoContent,
    VideoMatch,
    VideoRecommendation,
    VideoRecommendationList,
    VideoRecommendationRequest,
    VideoTag,
)

DEFAULT_VIDEO_CATALOG = Path(__file__).parent / "assets" / "videos" / "catalog.json"
DEFAULT_VIDEO_MEDIA = DEFAULT_VIDEO_CATALOG.parent / "media"


class VideoCatalogRecord(BaseModel):
    """Internal review flags never leave the service response."""

    model_config = ConfigDict(extra="forbid", strict=True, frozen=True)

    content: VideoContent
    published: bool = False
    authorization_verified: bool = False
    playable_verified: bool = False
    media_file: str | None = Field(default=None, pattern=r"^[a-z][a-z0-9_-]{0,63}\.mp4$")
    poster_file: str | None = Field(default=None, pattern=r"^[a-z][a-z0-9_-]{0,63}\.jpg$")


class VideoLibrary:
    def __init__(self, records: Iterable[VideoCatalogRecord] = (), *, allow_synthetic_for_tests: bool = False,
                 media_root: str | Path = DEFAULT_VIDEO_MEDIA):
        self.records = tuple(records)
        self.allow_synthetic_for_tests = allow_synthetic_for_tests
        self.media_root = Path(media_root).resolve()
        ids = [record.content.video_id for record in self.records]
        if len(ids) != len(set(ids)):
            raise ValueError("Video catalog IDs must be unique")

    @classmethod
    def from_file(cls, path: str | Path = DEFAULT_VIDEO_CATALOG, *, media_root: str | Path = DEFAULT_VIDEO_MEDIA) -> "VideoLibrary":
        catalog_path = Path(path)
        if not catalog_path.is_file():
            return cls()
        value = json.loads(catalog_path.read_text(encoding="utf-8"))
        if not isinstance(value, dict) or set(value) != {"items"} or not isinstance(value["items"], list):
            raise ValueError("Video catalog must contain only an items array")
        return cls((VideoCatalogRecord.model_validate(record) for record in value["items"]), media_root=media_root)

    def _file(self, name: str | None) -> Path | None:
        if name is None:
            return None
        path = (self.media_root / name).resolve()
        return path if path.parent == self.media_root and path.is_file() and path.stat().st_size > 0 else None

    def media_path(self, video_id: str, *, poster: bool = False) -> Path | None:
        eligible_ids = {video.video_id for video in self.eligible()}
        for record in self.records:
            if record.content.video_id == video_id and video_id in eligible_ids:
                return self._file(record.poster_file if poster else record.media_file)
        return None

    def public_urls(self, result: VideoRecommendationList, origin: str) -> VideoRecommendationList:
        records = {record.content.video_id: record for record in self.records}
        items = []
        for item in result.items:
            record = records[item.video.video_id]
            video = item.video
            if record.media_file is not None:
                ident = video.video_id
                value = video.model_dump()
                value['playback_url'] = origin + f'api/v1/video-media/{ident}.mp4'
                value['cover_url'] = origin + f'api/v1/video-covers/{ident}.jpg' if self._file(record.poster_file) else None
                video = VideoContent.model_validate(value)
            items.append(VideoRecommendation(video=video, matches=item.matches))
        return VideoRecommendationList(items=items, requested_limit=result.requested_limit, available_count=result.available_count)

    def eligible(self) -> tuple[VideoContent, ...]:
        result = []
        for record in self.records:
            if not record.published or not record.playable_verified:
                continue
            if record.media_file is not None and self._file(record.media_file) is None:
                continue
            kind = record.content.source.kind
            if kind == "licensed" and record.authorization_verified:
                result.append(record.content)
            elif kind == "synthetic_test" and self.allow_synthetic_for_tests:
                result.append(record.content)
        return tuple(result)

    def recommend(
        self,
        request: VideoRecommendationRequest,
        garments: dict[str, GarmentWrite],
    ) -> VideoRecommendationList:
        if request.makeup_ids:
            raise ValueError("makeup_ids is unavailable until the face-group catalog is connected")

        context: dict[tuple[str, str], list[VideoMatch]] = {}

        def add_context(tag: VideoTag, source: str, related_id: str | None) -> None:
            match = VideoMatch(dimension=tag.dimension, value=tag.value, source=source, related_id=related_id)
            matches = context.setdefault((tag.dimension, tag.value), [])
            if match not in matches:
                matches.append(match)

        for garment_id in request.garment_ids:
            garment = garments.get(garment_id)
            if garment is None:
                raise ValueError(f"Unknown garment context: {garment_id}")
            add_context(VideoTag(dimension="category", value=garment.category), "garment_metadata", garment_id)
            for color in garment.details.colors:
                # The existing wardrobe accepts longer/noncanonical display text.
                # Ignore an unsupported video tag; the garment and its category
                # remain valid context. Never truncate it into a false match.
                try:
                    tag = VideoTag(dimension="color", value=color)
                except ValidationError:
                    continue
                add_context(tag, "garment_metadata", garment_id)
        for tag in request.user_tags:
            add_context(tag, "user_selection", None)

        candidates: list[VideoRecommendation] = []
        for video in self.eligible():
            matches = []
            for tag in video.tags:
                matches.extend(context.get((tag.dimension, tag.value), ()))
            if not matches:
                continue
            matches.sort(key=lambda match: (
                match.dimension, match.value, match.source, match.related_id or ""
            ))
            if len(matches) > 40:
                # Preserve evidence from every supported dimension within the DTO cap.
                prioritized = []
                seen_dimensions = set()
                for match in matches:
                    if match.dimension not in seen_dimensions:
                        prioritized.append(match)
                        seen_dimensions.add(match.dimension)
                selected = {(m.dimension, m.value, m.source, m.related_id) for m in prioritized}
                remaining = [m for m in matches if (m.dimension, m.value, m.source, m.related_id) not in selected]
                matches = sorted(
                    [*prioritized, *remaining[:40 - len(prioritized)]],
                    key=lambda match: (match.dimension, match.value, match.source, match.related_id or ""),
                )
            candidates.append(VideoRecommendation(video=video, matches=matches))

        # A video matching several tags remains one candidate. Relevance is the
        # number of distinct matched dimensions; video ID makes ties repeatable.
        by_id: dict[str, VideoRecommendation] = {}
        for candidate in candidates:
            previous = by_id.get(candidate.video.video_id)
            if previous is None or len({m.dimension for m in candidate.matches}) > len({m.dimension for m in previous.matches}):
                by_id[candidate.video.video_id] = candidate
        ordered = sorted(
            by_id.values(),
            key=lambda item: (-len({match.dimension for match in item.matches}), item.video.video_id),
        )
        response = VideoRecommendationList(
            items=ordered[:request.limit],
            requested_limit=request.limit,
            available_count=len(ordered),
        )
        return response
