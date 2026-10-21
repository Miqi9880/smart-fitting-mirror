"""New public wardrobe behavior; tests do not need the private brand-image pack."""
import json
import sqlite3

from fastapi.testclient import TestClient

from backend.catalog import CATALOG_CAPACITY, STUDIO_MANIFEST, initial_catalog, regression_catalog
from backend.catalog_repository import CatalogRepository
from backend.service import create_app


def test_default_catalog_replaces_all_old_items_and_has_explicit_placement():
    old=set(regression_catalog()); current=initial_catalog()
    assert not old & set(current)
    assert len(current)==51
    with TestClient(create_app()) as c:
        h={'Authorization':'Bearer '+c.post('/api/v1/sessions').json()['session_token']}
        items=c.get('/api/v1/garments',headers=h).json()['items']
        assert {i['category'] for i in items}=={'upper_body','lower_body','headwear','dress','skirt','shorts'}
        for item in items:
            image=item['preview_image']
            if item['category']=='upper_body':
                assert image['anchors'] and item['generation_reference_url']
            elif item['category']=='dress':
                assert image['anchors'] and image['attachment_anchors'] and .1<=image['hem_ratio']<=2
                assert item['generation_reference_url'] is None
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
    for i in range(CATALOG_CAPACITY-4):repository.put(sample.model_copy(update={'garment_id':f'custom_{i}'}))
    upgraded=CatalogRepository(path).snapshot()
    assert len(upgraded)==CATALOG_CAPACITY
    assert {f'custom_{i}' for i in range(CATALOG_CAPACITY-4)} <= upgraded.keys()
    assert not set(regression_catalog()) & upgraded.keys()
    assert len(set(initial_catalog()) & upgraded.keys())==4


def test_revision_two_adds_only_new_defaults_without_restoring_deletions_or_overwriting_edits(tmp_path):
    path=tmp_path/'v2.sqlite3'
    old={'studio_black_tee','studio_blue_tee','studio_red_tee','studio_black_pants','studio_black_beanie'}
    removed='studio_red_tee'
    with sqlite3.connect(path) as db:
        db.execute('CREATE TABLE catalog(id TEXT PRIMARY KEY,payload TEXT NOT NULL)')
        db.execute('CREATE TABLE catalog_meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)')
        db.executemany('INSERT INTO catalog_meta VALUES (?,?)',[('initialized','1'),('studio_revision','2')])
        for gid,item in initial_catalog().items():
            if gid not in old or gid==removed:continue
            if gid=='studio_blue_tee':item=item.model_copy(update={'name':'My edited default'})
            db.execute('INSERT INTO catalog VALUES (?,?)',(gid,item.model_dump_json()))
    repository=CatalogRepository(path)
    upgraded=repository.snapshot()
    assert set(upgraded)==set(initial_catalog())-{removed}
    assert upgraded['studio_blue_tee'].name=='My edited default'
    new=next(gid for gid in initial_catalog() if gid not in old)
    repository.delete(new)
    assert new not in CatalogRepository(path).snapshot()
    with sqlite3.connect(path) as db:
        assert db.execute("SELECT value FROM catalog_meta WHERE key='studio_revision'").fetchone()==(str(STUDIO_MANIFEST['revision']),)


def test_catalog_capacity_is_one_hundred_and_existing_entries_can_still_be_edited(tmp_path):
    import pytest
    repository=CatalogRepository(tmp_path/'capacity.sqlite3')
    sample=next(iter(initial_catalog().values()))
    for i in range(CATALOG_CAPACITY-len(initial_catalog())):
        repository.put(sample.model_copy(update={'garment_id':f'custom_{i}'}))
    assert len(repository.snapshot())==100
    with pytest.raises(ValueError,match='100 garments'):
        repository.put(sample.model_copy(update={'garment_id':'too_many'}))
    repository.put(sample.model_copy(update={'name':'Edit at capacity'}))
    assert repository.snapshot()[sample.garment_id].name=='Edit at capacity'
