"""Opt-in real COS smoke test using a generated, non-personal PNG.

The signed URL and credentials are deliberately never printed. The uploaded
object is deleted in a finally block after the download check.
"""

from __future__ import annotations

import argparse
import sys
import tempfile
import urllib.error
import urllib.request
from pathlib import Path

from PIL import Image
from qcloud_cos import CosClientError, CosServiceError

from backend.cos_storage import build_client, delete_tryon_input, settings_from_env, upload_tryon_input


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_ENV_FILE = REPO_ROOT / ".env.cos"


def safe_error_summary(error: Exception) -> str:
    """Return diagnostic metadata that cannot contain keys or signed URLs."""
    if isinstance(error, CosServiceError):
        return f"CosServiceError HTTP {error.get_status_code()} code {error.get_error_code()}"
    if isinstance(error, CosClientError):
        return "CosClientError"
    if isinstance(error, urllib.error.HTTPError):
        return f"SignedDownloadError HTTP {error.code}"
    if isinstance(error, urllib.error.URLError):
        return "SignedDownloadNetworkError"
    return type(error).__name__


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Validate private COS upload, signed GET, and cleanup.")
    parser.add_argument("--env-file", type=Path, default=DEFAULT_ENV_FILE)
    parser.add_argument("--execute", action="store_true", help="Perform the real COS requests.")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    settings = settings_from_env(args.env_file.resolve())
    client = build_client(settings)
    if not args.execute:
        print("COS preflight passed. No network request was made. Add --execute for the real smoke test.")
        return 0

    object_key: str | None = None
    deleted = False
    try:
        with tempfile.TemporaryDirectory(prefix="smartmirror-cos-smoke-") as directory:
            image_path = Path(directory) / "non-personal-test.png"
            Image.new("RGB", (2, 2), color=(32, 96, 160)).save(image_path, format="PNG")
            uploaded = upload_tryon_input(client, settings, image_path, "model")
            object_key = uploaded.object_key
            request = urllib.request.Request(uploaded.download_url, method="GET")
            with urllib.request.urlopen(request, timeout=30) as response:
                payload = response.read(1024)
                status = response.status
            if status != 200 or not payload.startswith(b"\x89PNG\r\n\x1a\n"):
                raise RuntimeError("Signed download did not return the expected PNG response.")
            print("COS real smoke test: PASS")
            print("- private upload: PASS")
            print("- object check: PASS")
            print("- signed HTTPS download: PASS")
    except Exception as error:
        print(f"COS real smoke test: FAILED ({safe_error_summary(error)}).", file=sys.stderr)
        return 1
    finally:
        if object_key is not None:
            try:
                delete_tryon_input(client, settings, object_key)
                deleted = True
            except Exception:
                print("- immediate cleanup: FAILED; use the COS console to remove the test object.", file=sys.stderr)
        if deleted:
            print("- immediate cleanup: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
