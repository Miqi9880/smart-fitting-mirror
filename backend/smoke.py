"""Exercise the complete mock protocol over real HTTP using an in-memory synthetic image."""
import argparse
import io
import json
import time
import uuid
from pathlib import Path

import httpx
from PIL import Image, ImageDraw


def run(base_url: str, expected: str = 'succeeded', output: Path | None = None) -> dict:
    with httpx.Client(base_url=base_url.rstrip('/'), timeout=10, follow_redirects=False, trust_env=False) as client:
        health = client.get('/health')
        health.raise_for_status()
        assert health.json()['is_mock'] is True, 'Smoke test requires the local mock service'
        session_response = client.post('/api/v1/sessions')
        assert session_response.status_code == 201
        session = session_response.json()
        auth = {'Authorization': 'Bearer ' + session['session_token']}
        peer_auth = None
        try:
            catalog_response = client.get('/api/v1/garments', headers=auth)
            catalog_response.raise_for_status()
            garment = next(g for g in catalog_response.json()['items'] if g['generation_reference_url'])
            asset_url = httpx.URL(garment['preview_image']['url'])
            assert (asset_url.scheme, asset_url.host, asset_url.port) == (client.base_url.scheme, client.base_url.host, client.base_url.port)
            asset_response = client.get(asset_url, headers=auth)
            asset_response.raise_for_status()
            with Image.open(io.BytesIO(asset_response.content)) as asset:
                assert asset.mode == 'RGBA' and asset.getchannel('A').getextrema() == (0, 255)
            with Image.new('RGB', (480, 640), '#E4EDF6') as picture:
                draw = ImageDraw.Draw(picture)
                draw.rectangle((140, 120, 340, 500), fill='#808EA0')
                encoded = io.BytesIO()
                picture.save(encoded, 'PNG')
            uploaded = client.post('/api/v1/uploads', headers=auth,
                                   files={'file': ('synthetic.png', encoded.getvalue(), 'image/png')})
            assert uploaded.status_code == 201, uploaded.status_code
            request = {'upload_id': uploaded.json()['upload_id'], 'garment_id': garment['garment_id']}

            def create(key):
                for _ in range(3):
                    response = client.post('/api/v1/tryon-jobs', headers={**auth, 'Idempotency-Key': key}, json=request)
                    if response.status_code != 429:
                        assert response.status_code in (200, 202), response.status_code
                        return response.json()
                    pause = int(response.headers['retry-after'])
                    assert 0 < pause <= 60
                    time.sleep(pause)
                raise AssertionError('Too many rate-limit responses')

            key = str(uuid.uuid4())
            job = create(key)
            assert create(key)['job_id'] == job['job_id'], 'Retry created a duplicate job'
            path = '/api/v1/tryon-jobs/' + job['job_id']
            deadline = time.monotonic() + 125
            while job['status'] not in {'succeeded', 'failed', 'cancelled'}:
                assert time.monotonic() < deadline, 'Mock job exceeded its deadline'
                time.sleep(2)
                response = client.get(path, headers=auth)
                response.raise_for_status()
                job = response.json()
                assert job['is_mock'] is True
            assert job['status'] == expected, job['status']
            assert (job['result'] is not None) == (expected == 'succeeded')
            assert (job['error'] is not None) == (expected == 'failed')

            peer_response = client.post('/api/v1/sessions')
            peer_response.raise_for_status()
            peer_auth = {'Authorization': 'Bearer ' + peer_response.json()['session_token']}
            assert client.get(path, headers=peer_auth).status_code == 404
            result_bytes = 0
            result_url = None
            if job['result']:
                result_url = httpx.URL(job['result']['image_url'])
                assert (result_url.scheme, result_url.host, result_url.port) == (client.base_url.scheme, client.base_url.host, client.base_url.port)
                response = client.get(result_url, headers=auth)
                response.raise_for_status()
                result_bytes = len(response.content)
                with Image.open(io.BytesIO(response.content)) as result:
                    assert result.size == (480, 640)
                    assert result.getpixel((0, 0)) == (23, 45, 67)
                assert client.get(result_url, headers=peer_auth).status_code == 404
                assert client.get(result_url).status_code == 401
                if output:
                    output.parent.mkdir(parents=True, exist_ok=True)
                    output.write_bytes(response.content)

            another = create(str(uuid.uuid4()))
            cancel_path = '/api/v1/tryon-jobs/' + another['job_id']
            cancelled = client.post(cancel_path + '/cancel', headers=auth)
            cancelled.raise_for_status()
            assert cancelled.json()['status'] == 'cancelled'
            assert client.get(cancel_path, headers=auth).json() == cancelled.json()
            assert client.delete('/api/v1/sessions/current', headers=auth).status_code == 204
            assert client.delete('/api/v1/sessions/current', headers=auth).status_code == 204
            assert client.get(path, headers=auth).status_code == 401
            assert client.get(asset_url, headers=auth).status_code == 401
            if result_url:
                assert client.get(result_url, headers=auth).status_code == 401
            return {'mode': health.json()['mode'], 'terminal': expected, 'is_mock': True,
                    'result_bytes': result_bytes, 'idempotency': 'passed', 'cancellation': 'passed',
                    'isolation': 'passed', 'revocation': 'passed'}
        finally:
            # Cleanup also runs on assertion or transport failure. No token or image is logged.
            for headers in (auth, peer_auth):
                if headers:
                    client.delete('/api/v1/sessions/current', headers=headers).raise_for_status()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', default='http://127.0.0.1:8000')
    parser.add_argument('--expect', choices=['succeeded', 'failed'], default='succeeded')
    parser.add_argument('--output', type=Path, help='Optional path for the synthetic mock result')
    args = parser.parse_args()
    print(json.dumps(run(args.url, args.expect, args.output), ensure_ascii=False))


if __name__ == '__main__':
    main()
