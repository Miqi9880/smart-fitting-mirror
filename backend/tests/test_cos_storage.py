from pathlib import Path

import pytest

from backend.cos_storage import (
    CosSettings,
    build_client,
    build_object_key,
    delete_tryon_input,
    settings_from_env,
    upload_tryon_input,
)
from backend.volc_tryon_smoke import ConfigurationError


class FakeCosClient:
    def __init__(self) -> None:
        self.calls: list[tuple[str, dict]] = []

    def put_object_from_local_file(self, **kwargs):
        self.calls.append(("upload", kwargs))
        return {"ETag": '"test-etag"'}

    def head_object(self, **kwargs):
        self.calls.append(("head", kwargs))
        return {"Content-Length": "3"}

    def get_presigned_download_url(self, **kwargs):
        self.calls.append(("presign", kwargs))
        return "https://example.cos.ap-guangzhou.myqcloud.com/object?q-signature=test"

    def delete_object(self, **kwargs):
        self.calls.append(("delete", kwargs))
        return {}


def write_env(path: Path, extra: str = "") -> None:
    path.write_text(
        "\n".join(
            [
                "COS_SECRET_ID=test-id",
                "COS_SECRET_KEY=test-secret",
                "COS_REGION=ap-guangzhou",
                "COS_BUCKET=smartmirror-tryon-01-1514304052",
                "COS_INPUT_PREFIX=tryon-input/",
                "COS_PRESIGN_EXPIRES_SECONDS=1800",
                extra,
            ]
        ),
        encoding="utf-8",
    )


def test_settings_load_without_exposing_credentials_in_repr(tmp_path: Path):
    env_file = tmp_path / ".env.cos"
    write_env(env_file)
    settings = settings_from_env(env_file)
    assert settings.bucket == "smartmirror-tryon-01-1514304052"
    assert settings.presign_seconds == 1800
    assert "test-id" not in repr(settings)
    assert "test-secret" not in repr(settings)


def test_settings_reject_prefix_outside_cam_policy(tmp_path: Path):
    env_file = tmp_path / ".env.cos"
    write_env(env_file, "COS_INPUT_PREFIX=other/")
    with pytest.raises(ConfigurationError, match="current CAM policy"):
        settings_from_env(env_file)


def test_official_sdk_client_builds_without_network():
    settings = CosSettings(secret_id="test-id", secret_key="test-secret")
    assert type(build_client(settings)).__name__ == "CosS3Client"


def test_object_key_is_opaque_and_under_authorized_prefix():
    key = build_object_key(Path("person photo.PNG"), "model", unique_id="fixed-id")
    assert key == "tryon-input/model/fixed-id.png"


def test_upload_head_and_presign_use_same_scoped_key(tmp_path: Path):
    image = tmp_path / "person.png"
    image.write_bytes(b"png")
    client = FakeCosClient()
    settings = CosSettings(secret_id="test-id", secret_key="test-secret")

    result = upload_tryon_input(client, settings, image, "model", unique_id="fixed-id")

    assert result.object_key == "tryon-input/model/fixed-id.png"
    assert result.expires_seconds == 1800
    assert result.etag == '"test-etag"'
    assert "q-signature" not in repr(result)
    assert [name for name, _ in client.calls] == ["upload", "head", "presign"]
    assert all(call["Key"] == result.object_key for _, call in client.calls)
    assert client.calls[2][1]["Expired"] == 1800


def test_delete_refuses_key_outside_authorized_prefix():
    client = FakeCosClient()
    settings = CosSettings(secret_id="test-id", secret_key="test-secret")
    with pytest.raises(ValueError, match="tryon-input"):
        delete_tryon_input(client, settings, "results/result.png")
    assert client.calls == []
