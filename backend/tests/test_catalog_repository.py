from fastapi.testclient import TestClient
from backend.catalog import GarmentWrite
from backend.catalog_repository import CatalogRepository
from backend.service import create_app


def test_metadata_persists_across_repository_reopen_and_empty_is_not_reseeded(tmp_path):
    path = tmp_path/'catalog.sqlite3'
    first = CatalogRepository(path)
    first.put(GarmentWrite(garment_id='custom',name='Persistent tee',asset_id='upper_blue'))
    assert CatalogRepository(path).snapshot()['custom'].name == 'Persistent tee'
    for gid in list(first.snapshot()):
        first.delete(gid)
    assert CatalogRepository(path).snapshot() == {}


def test_persistent_base_changes_apply_to_new_sessions_and_guest_edits_are_isolated(tmp_path):
    path = tmp_path/'catalog.sqlite3'; repository = CatalogRepository(path)
    with TestClient(create_app(catalog_path=path)) as client:
        def session():
            return {'Authorization':'Bearer '+client.post('/api/v1/sessions').json()['session_token']}
        old = session()
        repository.put(GarmentWrite(garment_id='custom',name='Persistent tee',asset_id='upper_blue'))
        new = session()
        assert client.get('/api/v1/garments/custom',headers=old).status_code == 404
        assert client.get('/api/v1/garments/custom',headers=new).status_code == 200
        client.delete('/api/v1/garments/custom',headers=new)
        assert 'custom' in repository.snapshot()
        assert client.get('/api/v1/garments/custom',headers=session()).status_code == 200
