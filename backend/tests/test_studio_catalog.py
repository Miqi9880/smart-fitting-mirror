"""New public wardrobe behavior; tests do not need the private brand-image pack."""
import json
import sqlite3

from fastapi.testclient import TestClient

from backend.catalog import initial_catalog, regression_catalog
from backend.catalog_repository import CatalogRepository
from backend.service import create_app


def test_default_catalog_replaces_all_old_items_and_has_explicit_placement():
    old=set(regression_catalog()); current=initial_catalog()
    assert not old & set(current)
    assert len(current)==5
    with TestClient(create_app()) as c:
        h={'Authorization':'Bearer '+c.post('/api/v1/sessions').json()['session_token']}
        items=c.get('/api/v1/garments',headers=h).json()['items']
        assert {i['category'] for i in items}=={'upper_body','lower_body','headwear'}
        for item in items:
            image=item['preview_image']
            if item['category']=='upper_body':
                assert image['anchors'] and item['generation_reference_url']
            else:
                assert image['anchors'] is None and image['attachment_anchors']
                assert item['generation_reference_url'] is None
        invalid={'garment_id':'bad_hat','name':'Wrong region','category':'upper_body','asset_id':'studio_black_beanie'}
        assert c.post('/api/v1/garments',headers=h,json=invalid).status_code==422
        valid={**invalid,'category':'headwear'}
        assert c.post('/api/v1/garments',headers=h,json=valid).status_code==201


def test_old_database_is_upgraded_once_and_custom_and_empty_wardrobes_are_preserved(tmp_path):
    path=tmp_path/'old.sqlite3'
    # Genuine pre-migration schema, with no studio revision key.
    with sqlite3.connect(path) as db:
        db.execute('CREATE TABLE catalog(id TEXT PRIMARY KEY,payload TEXT NOT NULL)')
        db.execute('CREATE TABLE catalog_meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)')
        db.execute("INSERT INTO catalog_meta VALUES ('initialized','1')")
        for item in regression_catalog().values():db.execute('INSERT INTO catalog VALUES (?,?)',(item.garment_id,item.model_dump_json()))
        item=next(iter(regression_catalog().values())).model_copy(update={'garment_id':'my_saved_item'})
        db.execute('INSERT INTO catalog VALUES (?,?)',(item.garment_id,item.model_dump_json()))
    repository=CatalogRepository(path)
    assert set(repository.snapshot())==set(initial_catalog()) | {'my_saved_item'}
    for gid in list(repository.snapshot()):repository.delete(gid)
    assert CatalogRepository(path).snapshot()=={}
    empty=tmp_path/'empty.sqlite3'
    with sqlite3.connect(empty) as db:
        db.execute('CREATE TABLE catalog(id TEXT PRIMARY KEY,payload TEXT NOT NULL)')
        db.execute('CREATE TABLE catalog_meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)')
        db.execute("INSERT INTO catalog_meta VALUES ('initialized','1')")
    assert CatalogRepository(empty).snapshot()=={}


def test_missing_brand_pack_returns_actionable_error_instead_of_internal_failure(monkeypatch,tmp_path):
    from backend import service
    monkeypatch.setattr(service,'ASSETS',tmp_path)
    with TestClient(create_app()) as c:
        h={'Authorization':'Bearer '+c.post('/api/v1/sessions').json()['session_token']}
        response=c.get('/api/v1/assets/studio_black_tee.png',headers=h)
        assert response.status_code==503
        assert response.json()['error']['code']=='ASSET_NOT_INSTALLED'


def test_upgrading_full_regression_database_preserves_custom_entries_and_capacity(tmp_path):
    path=tmp_path/'full.sqlite3';repository=CatalogRepository(path,profile='regression')
    sample=next(iter(regression_catalog().values()))
    for i in range(46):repository.put(sample.model_copy(update={'garment_id':f'custom_{i}'}))
    upgraded=CatalogRepository(path).snapshot()
    assert len(upgraded)==50
    assert {f'custom_{i}' for i in range(46)} <= upgraded.keys()
    assert not set(regression_catalog()) & upgraded.keys()
    assert len(set(initial_catalog()) & upgraded.keys())==4
