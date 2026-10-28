"""Shared synthetic fixtures and invalid cross-field cases; no routes or media calls."""
import copy
import json
from pathlib import Path

import pytest
from pydantic import ValidationError
from backend.video_contracts import VideoRecommendationRequest, VideoRecommendationList

EX = Path(__file__).resolve().parents[2] / 'contracts/examples'


def fixture(name):
    return json.loads((EX / name).read_text(encoding='utf-8'))


@pytest.mark.parametrize('name', ['video-matched.json', 'video-shortfall.json', 'video-empty.json'])
def test_shared_responses_round_trip_without_losing_provenance(name):
    value = fixture(name)
    result = VideoRecommendationList.model_validate(value)
    assert result.model_dump() == value
    assert all(i.video.source.kind == 'synthetic_test' for i in result.items)


def test_request_has_bounded_explicit_context_and_backwards_safe_defaults():
    value = fixture('video-request.json')
    assert VideoRecommendationRequest.model_validate(value).model_dump() == value
    assert VideoRecommendationRequest.model_validate({}).model_dump() == {
        'garment_ids': [], 'makeup_ids': [], 'user_tags': [], 'limit': 5}


@pytest.mark.parametrize('value', [
    {'garment_ids': ['upper_blue', 'upper_blue']}, {'garment_ids': ['bad/id']},
    {'makeup_ids': ['natural', 'natural']}, {'limit': True}, {'limit': 0}, {'limit': 21},
    {'user_tags': [{'dimension': 'style', 'value': '   '}]},
    {'user_tags': [{'dimension': 'identity', 'value': 'person'}]},
    {'user_tags': [{'dimension': 'style', 'value': 'casual'}] * 2},
    {'photo_url': 'https://example.invalid/person.png'}, {'session_token': 'not-a-request-field'}])
def test_bad_context_never_silently_turns_into_a_generic_recommendation(value):
    with pytest.raises(ValidationError):
        VideoRecommendationRequest.model_validate(value)


@pytest.mark.parametrize('mutate', [
    lambda v: v.update(available_count=0),
    lambda v: v['items'][0]['matches'][0].update(value='unrelated'),
    lambda v: v['items'][0]['matches'][0].update(related_id=None),
    lambda v: v['items'][0]['matches'][0].update(source='user_selection'),
    lambda v: v['items'][0]['video']['source'].update(kind='unknown'),
    lambda v: v['items'][0]['video'].update(duration_seconds=0),
    lambda v: v['items'][0]['video'].update(playback_url='file:///tmp/private.mp4'),
    lambda v: v['items'][0]['video'].update(playback_url='https://token@example.invalid/x.mp4'),
    lambda v: v['items'][0]['video'].update(cover_url='https://example.invalid/x#fragment'),
    lambda v: v['items'][0]['video'].update(tags=[]),
])
def test_inconsistent_relevance_media_or_count_is_not_a_success(mutate):
    value = fixture('video-shortfall.json')
    mutate(value)
    with pytest.raises(ValidationError):
        VideoRecommendationList.model_validate(value)


def test_five_candidates_cannot_be_filled_by_repeating_one_video():
    value = fixture('video-shortfall.json')
    value['available_count'] = 5
    value['items'] = [copy.deepcopy(value['items'][0]) for _ in range(5)]
    with pytest.raises(ValidationError):
        VideoRecommendationList.model_validate(value)


def test_response_evidence_must_be_supported_by_the_actual_request():
    data = VideoRecommendationList.model_validate(fixture('video-shortfall.json'))
    request = VideoRecommendationRequest.model_validate(fixture('video-request.json'))
    assert data.validate_context(request) is data
    for invalid in [VideoRecommendationRequest(), request.model_copy(update={'limit': 1}),
                    request.model_copy(update={'garment_ids': ['upper_sand']})]:
        with pytest.raises(ValueError):
            data.validate_context(invalid)
