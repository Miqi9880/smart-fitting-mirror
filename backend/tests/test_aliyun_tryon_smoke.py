from pathlib import Path

import pytest

from backend.aliyun_tryon_smoke import (
    ConfigurationError,
    RemoteCallError,
    build_request,
    is_public_http_url,
    read_task_state,
    result_image_url,
    settings_from_env,
    write_task_state,
)


def write_env(path: Path, extra: str = "") -> None:
    path.write_text(
        "\n".join(
            [
                "ALIYUN_ACCESS_KEY_ID=test-id",
                "ALIYUN_ACCESS_KEY_SECRET=test-secret",
                "ALIYUN_TRYON_MODEL_IMAGE_URL=https://example.com/model.png",
                "ALIYUN_TRYON_GARMENT_IMAGE_URL=https://example.com/shirt.png",
                "ALIYUN_TRYON_CLOTH_TYPE=tops",
                "ALIYUN_TRYON_RESOLUTION=1K",
                extra,
            ]
        ),
        encoding="utf-8",
    )


def test_settings_builds_aidge_request_without_network(tmp_path: Path):
    env_file = tmp_path / ".env.aliyun"
    write_env(env_file)
    request = build_request(settings_from_env(env_file))
    assert request.model_image_url == "https://example.com/model.png"
    assert request.cloth_image_url == "https://example.com/shirt.png"
    assert request.cloth_type == "tops"
    assert request.resolution == "1K"
    assert request.__class__.__name__ == "VirtualTryOnRequest"


def test_signed_url_overrides_do_not_need_urls_in_env(tmp_path: Path):
    env_file = tmp_path / ".env.aliyun"
    env_file.write_text(
        "ALIYUN_ACCESS_KEY_ID=test-id\nALIYUN_ACCESS_KEY_SECRET=test-secret\n",
        encoding="utf-8",
    )
    settings = settings_from_env(
        env_file,
        model_image_url="https://cos.example/model.png?signature=hidden",
        garment_image_url="https://cos.example/garment.png?signature=hidden",
    )
    assert settings.model_image_url.startswith("https://cos.example/model.png")
    assert settings.garment_image_url.startswith("https://cos.example/garment.png")
    assert "test-secret" not in repr(settings)
    assert "signature=hidden" not in repr(settings)


@pytest.mark.parametrize("value", ["backend/assets/model.png", "data:image/png;base64,abc", "file:///private/model.png"])
def test_local_or_data_urls_are_rejected(value: str):
    assert is_public_http_url(value) is False


def test_invalid_cloth_type_is_rejected_before_network(tmp_path: Path):
    env_file = tmp_path / ".env.aliyun"
    write_env(env_file, "ALIYUN_TRYON_CLOTH_TYPE=shirt")
    with pytest.raises(ConfigurationError, match="ALIYUN_TRYON_CLOTH_TYPE"):
        settings_from_env(env_file)


def test_task_state_round_trip_stores_no_credentials(tmp_path: Path):
    state_file = tmp_path / "private" / "last-task.json"
    state = {"task_id": "task-123", "submitted_at": "2026-10-22T00:00:00+00:00", "status": "submitted"}
    write_task_state(state_file, state)
    assert read_task_state(state_file) == state


def test_completed_result_url_is_read_from_provider_json():
    assert result_image_url('{"ImageUrl":"https://example.com/result.png"}') == "https://example.com/result.png"
    assert result_image_url('{"url":"https://example.com/async-result.png"}') == "https://example.com/async-result.png"
    with pytest.raises(RemoteCallError, match="result image URL"):
        result_image_url("{}")
