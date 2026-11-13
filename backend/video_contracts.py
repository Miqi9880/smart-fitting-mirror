"""HTTP 0.4.0 video DTOs; no database, routes, media fetching or AI calls.

The catalog/rule implementation consumes these models. Test content remains
explicitly synthetic and never establishes the five licensed clips criterion.
"""
from typing import Annotated, Literal
from urllib.parse import urlsplit

from pydantic import BaseModel, ConfigDict, Field, model_validator

Identifier = Annotated[str, Field(pattern=r"^[a-z][a-z0-9_-]{0,63}$")]
GarmentId = Annotated[str, Field(pattern=r"^[a-z][a-z0-9_]{0,63}$")]
ShortText = Annotated[str, Field(min_length=1, max_length=64, pattern=r"^\S(?:[\s\S]*\S)?$")]
Dimension = Literal["category", "style", "color", "scene"]


class VideoModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, frozen=True)


class VideoTag(VideoModel):
    dimension: Dimension
    value: ShortText


class VideoRecommendationRequest(VideoModel):
    garment_ids: list[GarmentId] = Field(default_factory=list, max_length=6)
    makeup_ids: list[Identifier] = Field(default_factory=list, max_length=8)
    user_tags: list[VideoTag] = Field(default_factory=list, max_length=20)
    limit: int = Field(default=5, ge=1, le=20)
    semantic_job_id: Identifier | None = Field(default=None, exclude_if=lambda value: value is None)

    @model_validator(mode="after")
    def unique_context(self):
        for values in [self.garment_ids, self.makeup_ids, [(t.dimension, t.value) for t in self.user_tags]]:
            if len(values) != len(set(values)):
                raise ValueError("Duplicate context")
        return self


def media_url(value: str) -> str:
    parsed = urlsplit(value)
    # Metadata never authorizes sending the guest token to a media host.
    if (len(value) > 2048 or any(c.isspace() for c in value) or
            parsed.scheme not in {"http", "https"} or not parsed.hostname or
            not parsed.hostname.isascii() or parsed.username is not None or
            parsed.password is not None or '#' in value):
        raise ValueError("Expected an absolute HTTP(S) media URL without credentials or fragment")
    parsed.port  # Validate an explicitly supplied port.
    return value


class VideoSource(VideoModel):
    kind: Literal["synthetic_test", "licensed"]
    attribution: Annotated[str, Field(min_length=1, max_length=500, pattern=r"^\S(?:[\s\S]*\S)?$")]
    authorization: Annotated[str, Field(min_length=1, max_length=500, pattern=r"^\S(?:[\s\S]*\S)?$")]


class VideoContent(VideoModel):
    video_id: Identifier
    title: Annotated[str, Field(min_length=1, max_length=100, pattern=r"^\S(?:[\s\S]*\S)?$")]
    duration_seconds: int = Field(ge=1, le=86400)
    orientation: Literal["landscape", "portrait"]
    cover_url: str | None
    playback_url: str
    tags: list[VideoTag] = Field(min_length=1, max_length=40)
    garment_ids: list[GarmentId] = Field(max_length=100)
    makeup_ids: list[Identifier] = Field(max_length=100)
    source: VideoSource

    @model_validator(mode="after")
    def content_consistent(self):
        media_url(self.playback_url)
        if self.cover_url is not None:
            media_url(self.cover_url)
        for values in [self.garment_ids, self.makeup_ids, [(t.dimension, t.value) for t in self.tags]]:
            if len(values) != len(set(values)):
                raise ValueError("Duplicate video metadata")
        return self


class VideoMatch(VideoModel):
    dimension: Dimension
    value: ShortText
    source: Literal["garment_metadata", "makeup_metadata", "user_selection", "model_semantics"]
    related_id: Identifier | None

    @model_validator(mode="after")
    def provenance(self):
        if (self.source in {"garment_metadata", "makeup_metadata"}) != (self.related_id is not None):
            raise ValueError("Metadata matches need a context id; user/model matches have no resource id")
        return self


class VideoRecommendation(VideoModel):
    video: VideoContent
    matches: list[VideoMatch] = Field(min_length=1, max_length=40)

    @model_validator(mode="after")
    def explainable(self):
        tags = {(t.dimension, t.value) for t in self.video.tags}
        if any((m.dimension, m.value) not in tags for m in self.matches):
            raise ValueError("A match must refer to a returned video tag")
        keys = [(m.dimension, m.value, m.source, m.related_id) for m in self.matches]
        if len(keys) != len(set(keys)):
            raise ValueError("Duplicate match")
        return self


class VideoSemanticAnalysis(VideoModel):
    job_id: Identifier
    status: Literal["used", "no_tags", "unavailable"]
    tags: list[VideoTag] = Field(max_length=4)

    @model_validator(mode="after")
    def status_and_tags(self):
        keys = {(tag.dimension, tag.value) for tag in self.tags}
        if ((self.status == "used") != bool(self.tags) or len(keys) != len(self.tags) or
                any(tag.dimension == "category" for tag in self.tags)):
            raise ValueError("Inconsistent semantic analysis")
        return self


class VideoRecommendationList(VideoModel):
    items: list[VideoRecommendation] = Field(max_length=20)
    requested_limit: int = Field(ge=1, le=20)
    available_count: int = Field(ge=0, le=100000)
    semantic: VideoSemanticAnalysis | None = Field(default=None, exclude_if=lambda value: value is None)

    @model_validator(mode="after")
    def counts_and_unique_ids(self):
        if len(self.items) != min(self.requested_limit, self.available_count):
            raise ValueError("Return the actual available candidates up to the requested limit")
        ids = [item.video.video_id for item in self.items]
        if len(ids) != len(set(ids)):
            raise ValueError("Recommendations must not repeat videos")
        return self

    def validate_context(self, request: VideoRecommendationRequest):
        """Validate request/response linkage before returning from the route."""
        if self.requested_limit != request.limit:
            raise ValueError("Response limit differs from request")
        if (self.semantic.job_id if self.semantic else None) != request.semantic_job_id:
            raise ValueError("Semantic analysis differs from the requested job")
        tags = self.semantic.tags if self.semantic else []
        if not (request.garment_ids or request.makeup_ids or request.user_tags or tags) and self.available_count:
            raise ValueError("Empty context must not yield generic recommendations")
        for item in self.items:
            for match in item.matches:
                valid = (
                    match.source == 'garment_metadata' and match.related_id in request.garment_ids or
                    match.source == 'makeup_metadata' and match.related_id in request.makeup_ids or
                    match.source == 'user_selection' and VideoTag(dimension=match.dimension, value=match.value) in request.user_tags or
                    match.source == 'model_semantics' and VideoTag(dimension=match.dimension, value=match.value) in tags
                )
                if not valid:
                    raise ValueError("Unsupported source or match not justified by the request context")
        return self
