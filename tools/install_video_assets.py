"""Install the lead's fixed, licensed reference-video pack. Never fetches URLs."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
from zipfile import ZipFile

ASSETS = Path(__file__).resolve().parents[1] / 'backend/assets/videos'


def wanted_files(manifest):
    wanted = {}
    for entry in manifest['entries']:
        for name, digest, size in [(entry['file'], entry['sha256'], entry['bytes']),
                                   (entry['poster_file'], entry['poster_sha256'], entry['poster_bytes'])]:
            if (not re.fullmatch(r'[a-z][a-z0-9_-]{0,63}\.(mp4|jpg)', name) or name in wanted or
                    not re.fullmatch(r'[0-9a-f]{64}', digest) or not 0 < size <= 8 * 1024 * 1024):
                raise ValueError('Invalid fixed video pack manifest')
            wanted[name] = (digest, size)
    return wanted


def validate(data, name, wanted):
    digest, size = wanted[name]
    if len(data) != size or hashlib.sha256(data).hexdigest() != digest:
        raise ValueError(f'Video asset hash/size mismatch: {name}')


def media_directory(assets):
    directory = assets / 'media'
    if directory.is_symlink() or directory.resolve().parent != assets.resolve():
        raise ValueError('Video media directory must remain inside the assets directory')
    return directory.resolve()


def install_pack(pack: Path, assets: Path = ASSETS):
    manifest = json.loads((assets / 'manifest.json').read_text(encoding='utf-8'))
    wanted = wanted_files(manifest)
    validated = {}
    with ZipFile(pack) as archive:
        names = archive.namelist()
        if len(set(names)) != len(names) or set(names) != set(wanted) | {'manifest.json', 'README.txt'}:
            raise ValueError('Video pack contains missing, unknown or duplicate paths')
        for name in wanted:
            if archive.getinfo(name).file_size != wanted[name][1]:
                raise ValueError(f'Unexpected asset size: {name}')
            with archive.open(name) as source:
                data = source.read(8 * 1024 * 1024 + 1)
            validate(data, name, wanted)
            validated[name] = data
    # Validate all assets before changing installed files; never extract archive paths.
    destination = media_directory(assets)
    destination.mkdir(parents=True, exist_ok=True)
    targets = []
    for name, data in validated.items():
        target, temporary = destination / name, destination / (name + '.installing')
        if target.is_symlink() or temporary.is_symlink() or target.resolve().parent != destination or temporary.resolve().parent != destination:
            raise ValueError('Video install path must remain inside the media directory')
        targets.append((target, temporary, data))
    for target, temporary, data in targets:
        temporary.write_bytes(data)
        os.replace(temporary, target)
    return len(manifest['entries'])


def check_installed(assets: Path = ASSETS):
    manifest = json.loads((assets / 'manifest.json').read_text(encoding='utf-8'))
    wanted = wanted_files(manifest)
    destination = media_directory(assets)
    for name in wanted:
        path = destination / name
        if path.is_symlink() or not path.is_file() or path.stat().st_size != wanted[name][1]:
            raise ValueError(f'Video asset missing or invalid: {name}')
        validate(path.read_bytes(), name, wanted)
    return len(manifest['entries'])


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('pack', type=Path, nargs='?')
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    if not args.check and args.pack is None:
        parser.error('Provide the local ZIP pack or use --check')
    print(f"PASS: {check_installed() if args.check else install_pack(args.pack)} licensed reference videos")
