"""HTTP integration tests use synthetic metadata and never fetch video media."""

from fastapi.testclient import TestClient
import pytest

from backend.service import create_app
from backend.video_contracts import VideoContent, VideoRecommendationList, VideoTag
from backend.video_library import VideoCatalogRecord, VideoLibrary


def record(video_id, tags, *, published=True, playable=True, source_kind="synthetic_test", authorized=False):
    content = VideoContent(
        video_id=video_id,
        title=f"测试内容 {video_id}",
        duration_seconds=90,
        orientation="landscape",
        cover_url=None,
        playback_url=f"https://media.example.invalid/{video_id}.mp4",
        tags=[VideoTag(**tag) for tag in tags],
        garment_ids=["upper_blue"],
        makeup_ids=[],
        source={
            "kind": source_kind,
            "attribution": "自动化测试合成元数据",
            "authorization": "测试专用；没有真实媒体，不计授权内容",
        },
    )
    return VideoCatalogRecord(
        content=content,
        published=published,
        authorization_verified=authorized,
        playable_verified=playable,
    )


def client_with(records=(), *, allow_synthetic=True, clock=lambda: 1000.0, ttl=1800):
    library = VideoLibrary(records, allow_synthetic_for_tests=allow_synthetic)
    return TestClient(create_app(
        catalog_profile="regression", clock=clock, ttl=ttl, video_library=library
    ))


def open_session(client):
    session = client.post("/api/v1/sessions").json()
    return {"Authorization": "Bearer " + session["session_token"]}


def post_recommendations(client, headers, body=None):
    return client.post("/api/v1/video-recommendations", headers=headers, json=body or {})


def test_rules_filter_unpublished_unplayable_and_unapproved_sources_and_sort_stably():
    records = [
        record("two-dimensions", [
            {"dimension": "category", "value": "upper_body"},
            {"dimension": "style", "value": "casual"},
            {"dimension": "color", "value": "blue"},
        ]),
        record("z-category", [{"dimension": "category", "value": "upper_body"}]),
        record("a-category", [{"dimension": "category", "value": "upper_body"}]),
        record("draft", [{"dimension": "category", "value": "upper_body"}], published=False),
        record("unplayable", [{"dimension": "category", "value": "upper_body"}], playable=False),
        record("license-pending", [{"dimension": "category", "value": "upper_body"}],
               source_kind="licensed", authorized=False),
        record("unrelated", [{"dimension": "scene", "value": "street"}]),
    ]
    with client_with(records) as client:
        headers = open_session(client)
        body = {"garment_ids": ["upper_blue"], "user_tags": [{"dimension": "style", "value": "casual"}], "limit": 5}
        first = post_recommendations(client, headers, body)
        second = post_recommendations(client, headers, body)

    assert first.status_code == 200 and first.headers["cache-control"] == "no-store"
    result = first.json()
    assert VideoRecommendationList.model_validate(result).model_dump(mode="json") == result
    assert result == second.json()
    assert result["requested_limit"] == 5 and result["available_count"] == 3
    assert [item["video"]["video_id"] for item in result["items"]] == [
        "two-dimensions", "a-category", "z-category"
    ]
    assert len(result["items"]) == 3  # Shortfall is reported, never padded.
    assert result["items"][0]["matches"] == [
        {"dimension": "category", "value": "upper_body", "source": "garment_metadata", "related_id": "upper_blue"},
        {"dimension": "color", "value": "blue", "source": "garment_metadata", "related_id": "upper_blue"},
        {"dimension": "style", "value": "casual", "source": "user_selection", "related_id": None},
    ]


def test_candidate_is_returned_once_with_only_context_supported_match_evidence():
    video = record("one-video", [
        {"dimension": "category", "value": "upper_body"},
        {"dimension": "color", "value": "blue"},
    ])
    with client_with([video]) as client:
        response = post_recommendations(client, open_session(client), {"garment_ids": ["upper_blue"]})

    assert response.status_code == 200
    result = response.json()
    assert result["available_count"] == 1 and len(result["items"]) == 1
    assert {match["dimension"] for match in result["items"][0]["matches"]} == {"category", "color"}


def test_empty_context_and_default_library_return_empty_without_generic_recommendations():
    with client_with([record("matching", [{"dimension": "category", "value": "upper_body"}])]) as client:
        empty_context = post_recommendations(client, open_session(client), {})
    with TestClient(create_app(catalog_profile="regression", video_library=VideoLibrary())) as empty_library_client:
        garment_context = post_recommendations(
            empty_library_client, open_session(empty_library_client), {"garment_ids": ["upper_blue"]}
        )

    for response in (empty_context, garment_context):
        assert response.status_code == 200
        assert response.json()["items"] == [] and response.json()["available_count"] == 0


@pytest.mark.parametrize("color", ["x" * 65, " blue "])
def test_valid_wardrobe_display_color_does_not_invalidate_recommendation_context(color):
    category = {"dimension": "category", "value": "upper_body"}
    color_tag = {"dimension": "color", "value": "blue"}
    with client_with([record("category-only", [category]), record("not-normalized", [color_tag])]) as client:
        headers = open_session(client)
        garment = {
            "garment_id": "valid_custom", "name": "测试上衣", "category": "upper_body",
            "asset_id": "upper_blue", "details": {"colors": [color]},
        }
        assert client.post("/api/v1/garments", headers=headers, json=garment).status_code == 201
        response = post_recommendations(client, headers, {"garment_ids": ["valid_custom"]})
    assert response.status_code == 200
    assert response.json()["available_count"] == 1
    assert [i["video"]["video_id"] for i in response.json()["items"]] == ["category-only"]
    assert response.json()["items"][0]["matches"] == [
        {**category, "source": "garment_metadata", "related_id": "valid_custom"}
    ]


def test_synthetic_content_is_never_enabled_by_the_production_default():
    with client_with([record("synthetic", [{"dimension": "category", "value": "upper_body"}])],
                     allow_synthetic=False) as client:
        response = post_recommendations(client, open_session(client), {"garment_ids": ["upper_blue"]})
    assert response.status_code == 200 and response.json()["available_count"] == 0


def test_catalog_file_retains_internal_review_flags_and_rejects_duplicate_ids(tmp_path):
    import json

    licensed = record(
        "licensed-entry", [{"dimension": "category", "value": "upper_body"}],
        source_kind="licensed", authorized=True,
    )
    payload = {"items": [licensed.model_dump(mode="json")]}
    path = tmp_path / "catalog.json"
    path.write_text(json.dumps(payload), encoding="utf-8")
    loaded = VideoLibrary.from_file(path)
    assert len(loaded.eligible()) == 1
    assert loaded.records[0].published and loaded.records[0].playable_verified
    assert loaded.records[0].authorization_verified

    with pytest.raises(ValueError, match="IDs must be unique"):
        VideoLibrary([licensed, licensed], allow_synthetic_for_tests=True)


def test_unknown_and_non_session_garment_ids_and_unconnected_makeup_are_rejected():
    with client_with() as client:
        first = open_session(client)
        second = open_session(client)
        custom = {
            "garment_id": "first_session_only", "name": "测试上衣", "category": "upper_body",
            "tags": ["synthetic"], "asset_id": "upper_blue", "details": {"colors": ["blue"]},
        }
        assert client.post("/api/v1/garments", headers=first, json=custom).status_code == 201
        for headers, body in [
            (first, {"garment_ids": ["missing_garment"]}),
            (second, {"garment_ids": ["first_session_only"]}),
            (first, {"makeup_ids": ["natural"]}),
        ]:
            response = post_recommendations(client, headers, body)
            assert response.status_code == 400
            assert response.json()["error"]["code"] == "INVALID_REQUEST"


def test_invalid_request_shape_and_duplicate_context_use_shared_error_response():
    with client_with() as client:
        headers = open_session(client)
        for body in [
            {"garment_ids": ["upper_blue", "upper_blue"]},
            {"limit": 0},
            {"user_tags": [{"dimension": "style", "value": "casual "}]},
        ]:
            response = post_recommendations(client, headers, body)
            assert response.status_code == 400
            assert set(response.json()) == {"error"}
            assert response.json()["error"]["code"] == "INVALID_REQUEST"


def test_authentication_expiry_and_revocation_are_enforced():
    now = [1000.0]
    with client_with(clock=lambda: now[0], ttl=2) as client:
        assert post_recommendations(client, {}, {}).status_code == 401
        headers = open_session(client)
        assert post_recommendations(client, headers, {}).status_code == 200
        now[0] += 3
        expired = post_recommendations(client, headers, {})
        assert expired.status_code == 401 and expired.json()["error"]["code"] == "SESSION_INVALID"

    with client_with() as client:
        headers = open_session(client)
        assert client.delete("/api/v1/sessions/current", headers=headers).status_code == 204
        revoked = post_recommendations(client, headers, {})
        assert revoked.status_code == 401 and revoked.json()["error"]["code"] == "SESSION_INVALID"


def test_recommendation_route_has_an_independent_rate_limit():
    with client_with() as client:
        headers = open_session(client)
        responses = [post_recommendations(client, headers, {}) for _ in range(31)]

    assert all(response.status_code == 200 for response in responses[:30])
    blocked = responses[-1]
    assert blocked.status_code == 429
    assert blocked.json()["error"]["code"] == "RATE_LIMITED"
    assert int(blocked.headers["retry-after"]) >= 1
