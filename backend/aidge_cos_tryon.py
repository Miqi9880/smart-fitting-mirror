"""Opt-in end-to-end COS-to-Aidge virtual try-on runner.

Signed COS URLs and credentials stay in memory and are never printed. The two
temporary COS inputs are deleted after the Aidge task reaches a terminal state.
"""

from __future__ import annotations

import argparse
import sys
from dataclasses import replace
from pathlib import Path

from backend.aliyun_tryon_smoke import RemoteCallError, run as run_aidge, settings_from_env as aidge_settings_from_env
from backend.cos_storage import build_client as build_cos_client
from backend.cos_storage import delete_tryon_input, settings_from_env as cos_settings_from_env, upload_tryon_input
from backend.volc_tryon_smoke import ConfigurationError


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_MODEL_IMAGE = REPO_ROOT.parent / "private" / "tryon-test-assets" / "synthetic-full-body-model-v1.png"
DEFAULT_GARMENT_IMAGE = REPO_ROOT / "backend" / "assets" / "real" / "studio_pique_polo_69.png"
PLACEHOLDER_MODEL_URL = "https://example.invalid/model.png"
PLACEHOLDER_GARMENT_URL = "https://example.invalid/garment.png"


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Upload two private inputs to COS and run one Aidge try-on task.")
    parser.add_argument("--cos-env-file", type=Path, default=REPO_ROOT / ".env.cos")
    parser.add_argument("--aliyun-env-file", type=Path, default=REPO_ROOT / ".env.aliyun")
    parser.add_argument("--model-image", type=Path, default=DEFAULT_MODEL_IMAGE)
    parser.add_argument("--garment-image", type=Path, default=DEFAULT_GARMENT_IMAGE)
    parser.add_argument("--poll-interval", type=float, default=5.0)
    parser.add_argument("--max-wait-seconds", type=int, default=240)
    parser.add_argument("--execute", action="store_true", help="Perform real COS and Aidge requests exactly once.")
    return parser.parse_args(argv)


def validate_local_image(path: Path, label: str) -> Path:
    resolved = path.resolve()
    if not resolved.is_file():
        raise ConfigurationError(f"Missing {label}: {resolved}")
    if resolved.suffix.lower() not in {".jpg", ".jpeg", ".png", ".webp"}:
        raise ConfigurationError(f"Unsupported {label} format: {resolved.suffix}")
    if resolved.stat().st_size > 10 * 1024 * 1024:
        raise ConfigurationError(f"{label} exceeds the 10 MiB Aidge input limit.")
    return resolved


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    uploaded_keys: list[str] = []
    cos_client = None
    cos_settings = None
    try:
        model_path = validate_local_image(args.model_image, "model image")
        garment_path = validate_local_image(args.garment_image, "garment image")
        cos_settings = cos_settings_from_env(args.cos_env_file.resolve())
        base_aidge_settings = aidge_settings_from_env(
            args.aliyun_env_file.resolve(),
            model_image_url=PLACEHOLDER_MODEL_URL,
            garment_image_url=PLACEHOLDER_GARMENT_URL,
        )
        if base_aidge_settings.cloth_type != "tops":
            raise ConfigurationError("The selected Polo shirt requires ALIYUN_TRYON_CLOTH_TYPE=tops.")
        print("Local COS-to-Aidge preflight passed.", flush=True)
        print(f"- model image: {model_path.name}", flush=True)
        print(f"- garment image: {garment_path.name}", flush=True)
        print(f"- garment type / resolution: {base_aidge_settings.cloth_type} / {base_aidge_settings.resolution}", flush=True)
        if not args.execute:
            print("No network request was made. Add --execute for one real Aidge task.", flush=True)
            return 0

        cos_client = build_cos_client(cos_settings)
        model_object = upload_tryon_input(cos_client, cos_settings, model_path, "model")
        uploaded_keys.append(model_object.object_key)
        garment_object = upload_tryon_input(cos_client, cos_settings, garment_path, "garment")
        uploaded_keys.append(garment_object.object_key)
        print("Two private COS inputs uploaded and signed in memory.", flush=True)
        aidge_settings = replace(
            base_aidge_settings,
            model_image_url=model_object.download_url,
            garment_image_url=garment_object.download_url,
        )
        return run_aidge(
            aidge_settings,
            execute=True,
            resume_last=False,
            poll_interval=args.poll_interval,
            max_wait_seconds=args.max_wait_seconds,
        )
    except (ConfigurationError, RemoteCallError, OSError, ValueError) as error:
        print(f"ERROR: {error}", file=sys.stderr, flush=True)
        return 2
    except Exception as error:
        print(f"ERROR: end-to-end request failed ({type(error).__name__}); sensitive details suppressed.", file=sys.stderr)
        return 2
    finally:
        if cos_client is not None and cos_settings is not None:
            cleanup_failures = 0
            for object_key in reversed(uploaded_keys):
                try:
                    delete_tryon_input(cos_client, cos_settings, object_key)
                except Exception:
                    cleanup_failures += 1
            if uploaded_keys and cleanup_failures == 0:
                print("Temporary COS inputs deleted.", flush=True)
            elif cleanup_failures:
                print(
                    f"WARNING: {cleanup_failures} COS input(s) could not be deleted; the 1-day lifecycle rule remains active.",
                    file=sys.stderr,
                    flush=True,
                )


if __name__ == "__main__":
    raise SystemExit(main())
