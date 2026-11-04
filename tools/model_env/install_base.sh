#!/usr/bin/env bash
# Only the generic CUDA/PyTorch environment. No model repository or weights.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/activate.sh"
exec 9>"$SFM_ROOT/tmp/env-base-install.lock"
flock -n 9 || { printf 'An environment installation is already running.\n' >&2; exit 75; }

# Use the publishers' Linux wheels on PyPI. The pinned torch release declares
# CUDA 12.8 dependencies there; the actual GPU runtime is checked separately.
python - <<'PY'
import json
import os
import re
from pathlib import Path
from urllib.parse import urlsplit
from urllib.request import urlopen

requirements = []
for package, version in [('torch', '2.8.0'), ('torchvision', '0.23.0')]:
    with urlopen(f'https://pypi.org/pypi/{package}/{version}/json', timeout=30) as response:
        metadata = json.load(response)
    filename = f'{package}-{version}-cp310-cp310-manylinux_2_28_x86_64.whl'
    wheels = [item for item in metadata['urls'] if item['filename'] == filename]
    if len(wheels) != 1:
        raise RuntimeError(f'Expected exactly one official wheel: {filename}')
    item = wheels[0]
    url = item['url']
    wheel = urlsplit(url)
    digest = item['digests']['sha256']
    if (wheel.scheme != 'https' or wheel.hostname != 'files.pythonhosted.org'
            or not re.fullmatch(r'[a-f0-9]{64}', digest)):
        raise RuntimeError('Official wheel URL/hash validation failed')
    if package == 'torch' and not any(
            dependency.startswith('nvidia-cuda-runtime-cu12==12.8.90;')
            for dependency in metadata['info']['requires_dist']):
        raise RuntimeError('Expected CUDA 12.8 runtime dependency for this baseline')
    requirements.append(f'{package} @ {url}#sha256={digest}')
target = Path(os.environ['SFM_ROOT']) / 'src/environment/requirements-base.txt'
target.write_text('\n'.join(requirements) + '\n', encoding='utf-8')
PY

python -m pip install --disable-pip-version-check --progress-bar off \
    --timeout 60 --retries 8 --index-url https://pypi.org/simple \
    --report "$SFM_ROOT/logs/env-base-pip-report.json" \
    -r "$SFM_ROOT/src/environment/requirements-base.txt"
python -m pip check
python -m pip freeze > "$SFM_ROOT/src/environment/requirements-base.lock.txt"
python -m pip list --format=json > "$SFM_ROOT/logs/env-base-packages.json"
