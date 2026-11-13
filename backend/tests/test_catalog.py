"""Real HTTP contract behavior of the adapted temporary wardrobe, using bundled assets."""
import io
import uuid

import pytest
from fastapi.testclient import TestClient
from PIL import Image

from backend.service import create_app


@pytest.fixture
def wardrobe():
    now = [1_790_409_600.0]
    with TestClient(create_app(clock=lambda: now[0],catalog_profile="regression")) as client:
        def session():
            token = client.post('/api/v1/sessions').json()['session_token']
            return {'Authorization': 'Bearer ' + token}
        yield client, session, now


def entry(**changes):
    return {'garment_id': 'my_tee', 'name': 'Cotton Shirt', 'asset_id': 'upper_blue',
            'tags': ['cotton', 'daily'], 'details': {'brand': 'Course', 'material': 'Cotton',
            'description': 'A class wardrobe item', 'sizes': ['S', 'M'], 'colors': ['blue'],
            'price_cents': 12345}, **changes}


def test_create_search_detail_edit_delete_and_session_isolation(wardrobe):
    c, session, _ = wardrobe
    first, other = session(), session()
    created = c.post('/api/v1/garments', headers=first, json=entry())
    assert created.status_code == 201
    assert created.json()['details']['price_cents'] == 12345
    found = c.get('/api/v1/garments?q=course%20cotton&tag=daily', headers=first).json()['items']
    assert [i['garment_id'] for i in found] == ['my_tee']
    assert c.get('/api/v1/garments?q=course%20missing', headers=first).json()['items'] == []
    assert c.get('/api/v1/garments/my_tee', headers=other).status_code == 404
    changed = c.put('/api/v1/garments/my_tee', headers=first, json=entry(name='Edited', asset_id='upper_sand'))
    assert changed.status_code == 200 and changed.json()['preview_image']['url'].endswith('/upper_sand.png')
    assert c.delete('/api/v1/garments/my_tee', headers=first).status_code == 204
    assert c.get('/api/v1/garments/my_tee', headers=first).status_code == 404
    assert c.get('/api/v1/garments/upper_blue', headers=other).status_code == 200


def test_guest_capacity_allows_one_hundred_and_rejects_new_item_without_blocking_updates(wardrobe):
    c, session, _=wardrobe; headers=session()
    for i in range(96):
        assert c.post('/api/v1/garments',headers=headers,json=entry(garment_id=f'custom_{i}')).status_code==201
    assert len(c.get('/api/v1/garments',headers=headers).json()['items'])==100
    response=c.post('/api/v1/garments',headers=headers,json=entry(garment_id='too_many'))
    assert response.status_code==429 and response.json()['error']['code']=='CATALOG_CAPACITY'
    assert c.put('/api/v1/garments/custom_0',headers=headers,json=entry(garment_id='custom_0',name='Edited')).status_code==200
    assert c.delete('/api/v1/garments/custom_1',headers=headers).status_code==204
    assert c.post('/api/v1/garments',headers=headers,json=entry(garment_id='replacement')).status_code==201
    assert len(c.get('/api/v1/garments',headers=session()).json()['items'])==4


@pytest.mark.parametrize('changes,status', [({'garment_id': '../escape'},400),
    ({'name': '   '},400), ({'asset_id':'missing'},422), ({'tags':['x'*101]},400),
    ({'details':{'price_cents':-1}},400)])
def test_invalid_catalog_values_do_not_modify_wardrobe(wardrobe, changes, status):
    c, session, _ = wardrobe; headers = session()
    before = c.get('/api/v1/garments', headers=headers).json()
    response = c.post('/api/v1/garments', headers=headers, json=entry(**changes))
    assert response.status_code == status
    assert c.get('/api/v1/garments', headers=headers).json() == before


def test_duplicate_missing_path_mismatch_and_authentication(wardrobe):
    c, session, _ = wardrobe; h = session()
    assert c.post('/api/v1/garments', json=entry()).status_code == 401
    assert c.post('/api/v1/garments', headers=h, json=entry()).status_code == 201
    assert c.post('/api/v1/garments', headers=h, json=entry()).status_code == 409
    assert c.put('/api/v1/garments/no_such_id', headers=h, json=entry()).status_code == 400
    assert c.put('/api/v1/garments/no_such_id', headers=h, json=entry(garment_id='no_such_id')).status_code == 404
    assert c.delete('/api/v1/garments/no_such_id', headers=h).status_code == 404


def test_editing_or_deleting_a_garment_does_not_change_an_inflight_job(wardrobe):
    c, session, now = wardrobe; h = session()
    c.post('/api/v1/garments', headers=h, json=entry())
    out = io.BytesIO(); Image.new('RGB',(128,128),'white').save(out,'PNG')
    upload = c.post('/api/v1/uploads', headers=h, files={'file':('sample.png',out.getvalue(),'image/png')}).json()
    job = c.post('/api/v1/tryon-jobs',headers={**h,'Idempotency-Key':str(uuid.uuid4())},
                 json={'upload_id':upload['upload_id'],'garment_id':'my_tee'}).json()
    c.delete('/api/v1/garments/my_tee', headers=h)
    now[0] += 3
    assert c.get('/api/v1/tryon-jobs/'+job['job_id'], headers=h).json()['status'] == 'succeeded'


def test_empty_wardrobe_and_expiry_reset(wardrobe):
    c, session, now = wardrobe; h = session()
    for item in c.get('/api/v1/garments', headers=h).json()['items']:
        assert c.delete('/api/v1/garments/'+item['garment_id'],headers=h).status_code == 204
    assert c.get('/api/v1/garments',headers=h).json()['items'] == []
    assert c.post('/api/v1/garments',headers=h,json=entry()).status_code == 201
    now[0] += 1801
    assert c.get('/api/v1/garments',headers=h).status_code == 401
    assert len(c.get('/api/v1/garments',headers=session()).json()['items']) >= 3
