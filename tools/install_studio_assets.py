"""Install the local course product-image pack without publishing its images in Git."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
from zipfile import ZipFile

from PIL import Image

ASSETS = Path(__file__).resolve().parents[1] / "backend/assets/studio"


def validate_image(data, item):
    if len(data) > 8 * 1024 * 1024 or hashlib.sha256(data).hexdigest() != item["sha256"]:
        raise ValueError(f"素材校验值不一致：{item['file']}")
    with Image.open(io.BytesIO(data)) as image:
        if image.size != (item["width"], item["height"]) or image.mode != "RGBA":
            raise ValueError(f"素材尺寸或透明通道不一致：{item['file']}")
        image.verify()


def install_pack(pack: Path, destination: Path = ASSETS):
    manifest = json.loads((destination / "manifest.json").read_text(encoding="utf-8"))
    wanted = {i["file"]: i for i in manifest["items"]}
    # Accept the old course pack, but never install its retired hoodie files.
    retired_files = {asset_id + ".png" for asset_id in manifest.get("retired_asset_ids", [])}
    validated = {}
    with ZipFile(pack) as archive:
        names = archive.namelist()
        if len(set(names)) != len(names) or set(names) - (set(wanted) | retired_files | {"manifest.json", "README.txt"}):
            raise ValueError("素材包包含未知路径或重复文件")
        for name, item in wanted.items():
            if name not in names or archive.getinfo(name).file_size > 8 * 1024 * 1024:
                raise ValueError(f"素材缺失或过大：{name}")
            with archive.open(name) as stream:
                data = stream.read(8 * 1024 * 1024 + 1)
            validate_image(data, item)
            validated[name] = data
    # Validate the entire pack before writing anything; never extract user paths.
    for name, data in validated.items():
        temporary = destination / (name + ".installing")
        temporary.write_bytes(data)
        os.replace(temporary, destination / name)
    return len(validated)


def check_installed(destination: Path = ASSETS):
    manifest = json.loads((destination / "manifest.json").read_text(encoding="utf-8"))
    for item in manifest["items"]:
        validate_image((destination / item["file"]).read_bytes(), item)
    return len(manifest["items"])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pack", type=Path, nargs="?")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    if not args.check and args.pack is None:
        parser.error("请选择素材 ZIP，或使用 --check 只读检查已安装素材")
    print(f"PASS: {check_installed() if args.check else install_pack(args.pack)} studio assets")
