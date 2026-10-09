"""Start the documented CLI and verify real loopback HTTP, independently of TestClient."""
import os
import socket
import subprocess
import sys
import time
from pathlib import Path

import httpx
import pytest

from backend.smoke import run


@pytest.mark.parametrize('mode,expected', [('success', 'succeeded'), ('failure', 'failed'), ('rate-limit', 'succeeded')])
def test_cli_over_real_http(mode, expected, tmp_path):
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        port = listener.getsockname()[1]
    root = Path(__file__).resolve().parents[2]
    with (tmp_path / 'server.log').open('wb') as log:
        process = subprocess.Popen([sys.executable, '-m', 'backend', '--port', str(port), '--mode', mode], cwd=root,
                                   stdout=log, stderr=log,
                                   creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        try:
            url = f'http://127.0.0.1:{port}'
            deadline = time.monotonic() + 10
            while True:
                assert process.poll() is None, 'Server CLI exited during startup'
                try:
                    if httpx.get(url + '/health', timeout=.5, trust_env=False).status_code == 200:
                        break
                except httpx.TransportError:
                    pass
                assert time.monotonic() < deadline, 'Server CLI did not become ready'
                time.sleep(.05)
            summary = run(url, expected, tmp_path / 'mock.png')
            assert summary['terminal'] == expected and summary['mode'] == mode
            assert summary['revocation'] == 'passed'
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
