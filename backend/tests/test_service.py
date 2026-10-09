"""Contract and state-machine checks using synthetic images, never user photographs."""
import asyncio
import io
import json
import random
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from openapi_schema_validator import OAS30Validator
from PIL import Image, ImageDraw

from backend import service
from tools.check_contracts import expand_refs, job_semantics


SPEC = json.loads((Path(__file__).resolve().parents[2] / 'contracts/openapi.json').read_text(encoding='utf-8'))


def validate(response, status, name):
    assert response.status_code == status, response.text
    schema = expand_refs(SPEC['components']['schemas'][name], SPEC)
    data = response.json()
    OAS30Validator(schema, format_checker=OAS30Validator.FORMAT_CHECKER).validate(data)
    if name == 'Job':
        job_semantics(data)
        assert data['is_mock'] is True
    assert response.headers['cache-control'] == 'no-store'
    return data


class Clock:
    now = 1_790_409_600.0

    def __call__(self):
        return self.now

    def advance(self, seconds):
        self.now += seconds


def photo_bytes(size=(480, 640), format='PNG', **options):
    with Image.new('RGB', size, '#E4EDF6') as picture:
        draw = ImageDraw.Draw(picture)
        draw.rectangle((size[0] // 4, size[1] // 5, size[0] * 3 // 4, size[1] * 4 // 5), fill='#808EA0')
        out = io.BytesIO()
        picture.save(out, format, **options)
        return out.getvalue()


@pytest.fixture
def server():
    clock = Clock()
    app = service.create_app(clock=clock)
    with TestClient(app) as client:
        yield app, client, clock


def new_session(client):
    data = validate(client.post('/api/v1/sessions'), 201, 'Session')
    return data, {'Authorization': 'Bearer ' + data['session_token']}


def upload(client, headers, data=None, mime='image/png'):
    return client.post('/api/v1/uploads', headers=headers,
                       files={'file': ('synthetic.png', data if data is not None else photo_bytes(), mime)})


def new_job(client, headers, upload_id=None, garment='upper_blue', key=None):
    upload_id = upload_id or validate(upload(client, headers), 201, 'Upload')['upload_id']
    return client.post('/api/v1/tryon-jobs', headers={**headers, 'Idempotency-Key': key or str(uuid.uuid4())},
                       json={'upload_id': upload_id, 'garment_id': garment})


def test_complete_flow_catalog_assets_and_revocation(server):
    app, client, clock = server
    session, auth = new_session(client)
    garments = validate(client.get('/api/v1/garments', headers=auth), 200, 'GarmentList')['items']
    assert len(garments) >= 2
    for garment in garments:
        preview = garment['preview_image']
        result = client.get(preview['url'], headers=auth)
        assert result.status_code == 200
        assert result.headers['cache-control'] == 'no-store'
        with Image.open(io.BytesIO(result.content)) as image:
            assert image.mode == 'RGBA'
            assert image.size == (preview['width'], preview['height'])
            alpha = image.getchannel('A')
            assert alpha.getextrema() == (0, 255)
            for anchor in preview['anchors'].values():
                point = round(anchor['x'] * (image.width - 1)), round(anchor['y'] * (image.height - 1))
                assert alpha.getpixel(point) > 0
        validate(client.get(preview['url']), 401, 'ErrorResponse')
    original = photo_bytes()
    item = validate(upload(client, auth, original), 201, 'Upload')
    assert item['expires_at'] == session['expires_at']
    job = validate(new_job(client, auth, item['upload_id']), 202, 'Job')
    assert job['status'] == 'queued'
    path = '/api/v1/tryon-jobs/' + job['job_id']
    clock.advance(.6)
    assert validate(client.get(path, headers=auth), 200, 'Job')['status'] == 'running'
    clock.advance(1.5)
    done = validate(client.get(path, headers=auth), 200, 'Job')
    assert done['status'] == 'succeeded'
    result_url = done['result']['image_url']
    result = client.get(result_url, headers=auth)
    assert result.status_code == 200 and result.content != original
    with Image.open(io.BytesIO(result.content)) as picture:
        assert picture.size == (480, 640)
        assert picture.getpixel((0, 0)) == (23, 45, 67)  # Explicit mock banner.
    validate(client.get(result_url), 401, 'ErrorResponse')
    ended = client.delete('/api/v1/sessions/current', headers=auth)
    assert ended.status_code == 204 and not ended.content
    assert session['session_id'] not in app.state.store.guests
    assert app.state.store.bytes_stored() == 0
    assert client.delete('/api/v1/sessions/current', headers=auth).status_code == 204
    for url in [path, result_url, garments[0]['preview_image']['url'], '/api/v1/garments']:
        validate(client.get(url, headers=auth), 401, 'ErrorResponse')


def test_idempotency_replay_conflict_and_concurrent_retry(server):
    app, client, clock = server
    _, auth = new_session(client)
    item = validate(upload(client, auth), 201, 'Upload')
    key = str(uuid.uuid4())
    with ThreadPoolExecutor(max_workers=6) as pool:
        responses = list(pool.map(lambda _: new_job(client, auth, item['upload_id'], key=key), range(12)))
    assert sorted(r.status_code for r in responses) == [200] * 11 + [202]
    assert len({r.json()['job_id'] for r in responses}) == 1
    assert len(next(iter(app.state.store.guests.values())).jobs) == 1
    replay = validate(new_job(client, auth, item['upload_id'], key=key.upper()), 200, 'Job')
    assert replay['job_id'] == responses[0].json()['job_id']
    conflict = validate(new_job(client, auth, item['upload_id'], garment='upper_sand', key=key), 409, 'ErrorResponse')
    assert conflict['error']['code'] == 'IDEMPOTENCY_CONFLICT'
    clock.advance(3)
    done = validate(new_job(client, auth, item['upload_id'], key=key), 200, 'Job')
    assert done['status'] == 'succeeded'


@pytest.mark.parametrize('winner', ['cancelled', 'succeeded'])
def test_cancel_and_completion_preserve_first_terminal_state(server, winner):
    app, client, clock = server
    _, auth = new_session(client)
    job = validate(new_job(client, auth), 202, 'Job')
    path = '/api/v1/tryon-jobs/' + job['job_id']
    if winner == 'succeeded':
        clock.advance(3)
        first = validate(client.get(path, headers=auth), 200, 'Job')
    else:
        first = validate(client.post(path + '/cancel', headers=auth), 200, 'Job')
    clock.advance(130)
    for _ in range(2):
        assert validate(client.get(path, headers=auth), 200, 'Job') == first
        assert validate(client.post(path + '/cancel', headers=auth), 200, 'Job') == first
    assert first['status'] == winner
    if winner == 'cancelled':
        assert next(iter(app.state.store.guests.values())).jobs[job['job_id']].result_bytes is None


@pytest.mark.parametrize('mode,ready_after,terminal,error', [
    ('failure', 2, 'failed', 'MOCK_GENERATION_FAILED'),
    ('delayed', 15, 'succeeded', None),
    ('timeout', 120, 'failed', 'GENERATION_TIMEOUT'),
])
def test_simulated_job_modes(mode, ready_after, terminal, error):
    clock = Clock()
    with TestClient(service.create_app(mode, clock=clock)) as client:
        _, auth = new_session(client)
        job = validate(new_job(client, auth), 202, 'Job')
        path = '/api/v1/tryon-jobs/' + job['job_id']
        clock.advance(ready_after - .1)
        assert validate(client.get(path, headers=auth), 200, 'Job')['status'] == 'running'
        clock.advance(.2)
        done = validate(client.get(path, headers=auth), 200, 'Job')
        assert done['status'] == terminal
        assert (done['error'] or {}).get('code') == error


def test_rate_limit_mode_retries_original_key_without_duplicate_job():
    clock = Clock()
    app = service.create_app('rate-limit', clock=clock)
    with TestClient(app) as client:
        _, auth = new_session(client)
        item = validate(upload(client, auth), 201, 'Upload')
        key = str(uuid.uuid4())
        response = new_job(client, auth, item['upload_id'], key=key)
        assert validate(response, 429, 'ErrorResponse')['error']['retryable'] is True
        clock.advance(int(response.headers['retry-after']))
        job = validate(new_job(client, auth, item['upload_id'], key=key), 202, 'Job')
        assert validate(new_job(client, auth, item['upload_id'], key=key), 200, 'Job')['job_id'] == job['job_id']
        for _ in range(20):
            validate(new_job(client, auth, item['upload_id']), 429, 'ErrorResponse')
        guest = next(iter(app.state.store.guests.values()))
        assert len(guest.throttled_keys) <= 6
        assert len(guest.jobs) == 1


def test_cross_session_resources_and_preview_only_garment(server):
    _, client, clock = server
    _, alice = new_session(client)
    _, bob = new_session(client)
    item = validate(upload(client, alice), 201, 'Upload')
    validate(new_job(client, bob, item['upload_id']), 404, 'ErrorResponse')
    job = validate(new_job(client, alice, item['upload_id']), 202, 'Job')
    path = '/api/v1/tryon-jobs/' + job['job_id']
    validate(client.get(path, headers=bob), 404, 'ErrorResponse')
    validate(client.post(path + '/cancel', headers=bob), 404, 'ErrorResponse')
    clock.advance(3)
    done = validate(client.get(path, headers=alice), 200, 'Job')
    validate(client.get(done['result']['image_url'], headers=bob), 404, 'ErrorResponse')
    response = validate(new_job(client, alice, item['upload_id'], garment='upper_preview'), 422, 'ErrorResponse')
    assert response['error']['code'] == 'GARMENT_NOT_GENERATABLE'


def test_session_expiry_and_idle_sweeper_remove_images():
    clock = Clock()
    app = service.create_app(clock=clock, ttl=3)
    with TestClient(app) as client:
        _, auth = new_session(client)
        validate(new_job(client, auth), 202, 'Job')
        clock.advance(4)
        # No request triggers cleanup: the lifespan sweeper must do it itself.
        deadline = time.monotonic() + 3
        while app.state.store.guests and time.monotonic() < deadline:
            time.sleep(.02)
        assert not app.state.store.guests
        assert app.state.store.bytes_stored() == 0
        validate(client.get('/api/v1/garments', headers=auth), 401, 'ErrorResponse')
        assert client.delete('/api/v1/sessions/current', headers=auth).status_code == 204
    assert not app.state.store.guests


def test_end_session_while_photo_is_being_decoded_does_not_restore_upload(server, monkeypatch):
    app, client, _ = server
    _, auth = new_session(client)
    started, finish = threading.Event(), threading.Event()
    original = service.decode_photo

    def slow_decode(*args):
        started.set()
        assert finish.wait(5)
        return original(*args)

    monkeypatch.setattr(service, 'decode_photo', slow_decode)
    with ThreadPoolExecutor(max_workers=1) as pool:
        pending = pool.submit(upload, client, auth)
        try:
            assert started.wait(3)
            assert client.delete('/api/v1/sessions/current', headers=auth).status_code == 204
        finally:
            finish.set()
        validate(pending.result(timeout=5), 401, 'ErrorResponse')
    assert not app.state.store.guests and app.state.store.bytes_stored() == 0


def test_orientation_and_private_metadata_are_stripped(server):
    app, client, _ = server
    _, auth = new_session(client)
    exif = Image.Exif()
    exif[274] = 6
    exif[270] = 'private location metadata'
    exif[315] = 'private photographer'
    item = validate(upload(client, auth, photo_bytes((640, 480), 'JPEG', exif=exif), 'image/jpeg'), 201, 'Upload')
    assert (item['width'], item['height']) == (480, 640)
    saved = next(iter(app.state.store.guests.values())).uploads[item['upload_id']]
    with Image.open(io.BytesIO(saved.data)) as decoded:
        assert not decoded.getexif() and not decoded.info
        assert decoded.size == (480, 640)


@pytest.mark.parametrize('mime,data,status', [
    ('text/plain', b'hello', 415),
    ('image/png', b'not an image', 422),
    ('image/jpeg', photo_bytes(), 415),
    ('image/png', photo_bytes()[:50], 422),
    ('image/png', photo_bytes(format='GIF'), 415),
])
def test_invalid_image_is_rejected_without_storing(server, mime, data, status):
    app, client, _ = server
    _, auth = new_session(client)
    validate(upload(client, auth, data, mime), status, 'ErrorResponse')
    assert app.state.store.bytes_stored() == 0


@pytest.mark.parametrize('kind', ['missing', 'wrong_name', 'duplicate', 'extra_field'])
def test_multipart_requires_one_file_field(server, kind):
    app, client, _ = server
    _, auth = new_session(client)
    file = ('synthetic.png', photo_bytes(), 'image/png')
    files = {'missing': {}, 'wrong_name': {'photo': file}, 'duplicate': [('file', file), ('file', file)],
             'extra_field': {'file': file}}[kind]
    data = {'extra': 'not allowed'} if kind == 'extra_field' else None
    response = client.post('/api/v1/uploads', headers=auth, files=files, data=data)
    validate(response, 400, 'ErrorResponse')
    assert app.state.store.bytes_stored() == 0


def test_byte_and_pixel_limits_including_boundary(server):
    _, client, _ = server
    _, auth = new_session(client)
    data = photo_bytes()
    padded = data + b'\0' * (service.MAX_FILE - len(data))
    validate(upload(client, auth, padded), 201, 'Upload')
    validate(upload(client, auth, padded + b'\0'), 413, 'ErrorResponse')
    validate(upload(client, auth, photo_bytes((4000, 4000))), 201, 'Upload')
    validate(upload(client, auth, photo_bytes((4001, 4000))), 413, 'ErrorResponse')


def test_valid_jpeg_is_not_rejected_for_larger_normalized_png(server):
    app, client, _ = server
    _, auth = new_session(client)
    with Image.frombytes('RGB', (2048, 2048), random.Random(7).randbytes(2048 * 2048 * 3)) as image:
        output = io.BytesIO()
        image.save(output, 'JPEG', quality=90)
    data = output.getvalue()
    assert len(data) < service.MAX_FILE
    item = validate(upload(client, auth, data, 'image/jpeg'), 201, 'Upload')
    saved = next(iter(app.state.store.guests.values())).uploads[item['upload_id']]
    assert len(saved.data) > service.MAX_FILE


def test_chunked_request_limit_is_checked_before_parser():
    seen = []

    async def downstream(scope, receive, send):
        seen.append(True)

    async def scenario():
        chunks = iter([{'type': 'http.request', 'body': b'a' * service.MAX_BODY, 'more_body': True},
                       {'type': 'http.request', 'body': b'b', 'more_body': False}])
        responses = []

        async def receive():
            return next(chunks)

        async def send(message):
            responses.append(message)

        await service.BodyLimit(downstream)({'type': 'http', 'method': 'POST', 'path': '/api/v1/uploads',
                                            'headers': []}, receive, send)
        assert responses[0]['status'] == 413
        assert json.loads(responses[1]['body'])['error']['code'] == 'IMAGE_TOO_LARGE'

    asyncio.run(scenario())
    assert not seen


def test_storage_cap_and_session_creation_limit(server, monkeypatch):
    app, client, _ = server
    _, auth = new_session(client)
    item = service.decode_photo(photo_bytes(), 'image/png')
    monkeypatch.setattr(service, 'MAX_STORED', len(item.data) * 2)
    validate(upload(client, auth), 201, 'Upload')
    validate(upload(client, auth), 201, 'Upload')
    validate(upload(client, auth), 429, 'ErrorResponse')
    for _ in range(19):
        new_session(client)
    limited = client.post('/api/v1/sessions')
    validate(limited, 429, 'ErrorResponse')
    assert int(limited.headers['retry-after']) > 0
    assert app.state.store.bytes_stored() <= service.MAX_STORED


def test_concurrent_upload_capacity_rejects_before_reading_and_releases_slot():
    async def scenario():
        entered, finish = asyncio.Event(), asyncio.Event()

        async def downstream(scope, receive, send):
            entered.set()
            await finish.wait()

        app = service.BodyLimit(downstream, max_uploads=1)
        scope = {'type': 'http', 'method': 'POST', 'path': '/api/v1/uploads', 'headers': []}
        responses = []

        async def receive():
            return {'type': 'http.request', 'body': b'', 'more_body': False}

        async def forbidden_receive():
            raise AssertionError('Over-capacity upload must not read its body')

        async def send(message):
            responses.append(message)

        first = asyncio.create_task(app(scope, receive, send))
        await entered.wait()
        await app(scope, forbidden_receive, send)
        assert responses[0]['status'] == 429
        assert json.loads(responses[1]['body'])['error']['code'] == 'REQUEST_CAPACITY'
        finish.set()
        await first
        assert app.uploads == 0
        await app(scope, receive, send)
        assert app.uploads == 0

    asyncio.run(scenario())


@pytest.mark.parametrize('authorization', [None, '', 'Basic xyz', 'Bearer nonsense', 'Bearer café'])
def test_invalid_tokens_are_uniform_401(server, authorization):
    app, _, _ = server
    with pytest.raises(service.Problem) as error:
        app.state.store.token_id(authorization)
    assert error.value.status == 401


def test_missing_key_invalid_json_and_unknown_route_use_contract_errors(server):
    _, client, _ = server
    _, auth = new_session(client)
    item = validate(upload(client, auth), 201, 'Upload')
    response = client.post('/api/v1/tryon-jobs', headers=auth,
                           json={'upload_id': item['upload_id'], 'garment_id': 'upper_blue'})
    assert validate(response, 400, 'ErrorResponse')['error']['code'] == 'IDEMPOTENCY_KEY_REQUIRED'
    response = client.post('/api/v1/tryon-jobs', headers=auth, json={'arbitrary_url': 'https://example.invalid/private'})
    validate(response, 400, 'ErrorResponse')
    validate(client.get('/api/v1/missing', headers=auth), 404, 'ErrorResponse')
