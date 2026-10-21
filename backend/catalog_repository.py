"""Persistent, local catalog equivalent of upstream ClothingRepository.

Uses SQLite instead of adding a second JVM/MySQL service. Stores only garment
metadata, not session credentials, portraits or generated images.
"""
import json
import sqlite3
from contextlib import closing
from pathlib import Path

from .catalog import ASSET_SPECS, GarmentWrite, initial_catalog, regression_catalog


class CatalogRepository:
    def __init__(self, path: str | Path, *, profile="studio"):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with closing(sqlite3.connect(self.path, timeout=10)) as db, db:
            db.execute('CREATE TABLE IF NOT EXISTS catalog (id TEXT PRIMARY KEY, payload TEXT NOT NULL)')
            db.execute('CREATE TABLE IF NOT EXISTS catalog_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)')
            db.execute('BEGIN IMMEDIATE')
            if db.execute("SELECT value FROM catalog_meta WHERE key='initialized'").fetchone() is None:
                db.executemany('INSERT INTO catalog VALUES (?,?)',
                    [(item.garment_id,item.model_dump_json()) for item in (regression_catalog() if profile == "regression" else initial_catalog()).values()])
                db.execute("INSERT INTO catalog_meta VALUES ('initialized','1')")
                db.execute("INSERT INTO catalog_meta VALUES ('studio_revision',?)",('0' if profile == "regression" else '2',))
            elif profile == "studio" and db.execute("SELECT value FROM catalog_meta WHERE key='studio_revision'").fetchone() is None:
                # Replace the old bundled demo wardrobe once, preserving custom entries
                # and deliberately empty catalogs. Reopening never resurrects deletions.
                ids = {row[0] for row in db.execute('SELECT id FROM catalog')}
                old = {'upper_blue','upper_sand','upper_preview','real_white_tee'}
                if ids & old:
                    for gid in old:
                        db.execute('DELETE FROM catalog WHERE id=?',(gid,))
                    for item in initial_catalog().values():
                        db.execute('INSERT OR IGNORE INTO catalog VALUES (?,?)',(item.garment_id,item.model_dump_json()))
                db.execute("INSERT INTO catalog_meta VALUES ('studio_revision','2')")

    def snapshot(self) -> dict[str, GarmentWrite]:
        with closing(sqlite3.connect(self.path, timeout=10)) as db:
            rows = db.execute('SELECT payload FROM catalog ORDER BY rowid').fetchall()
        return {item.garment_id:item for (payload,) in rows for item in [GarmentWrite.model_validate_json(payload)]}

    def put(self, item: GarmentWrite):
        if item.asset_id not in ASSET_SPECS or not item.name.strip():
            raise ValueError('Choose an existing asset and a non-empty name')
        if item.category != ASSET_SPECS[item.asset_id]['category']:
            raise ValueError('Category must match the asset placement')
        if any(not value.strip() or len(value)>100 for value in [*item.tags,*item.details.sizes,*item.details.colors]):
            raise ValueError('Invalid tags, sizes or colors')
        with closing(sqlite3.connect(self.path, timeout=10)) as db, db:
            db.execute('BEGIN IMMEDIATE')
            exists = db.execute('SELECT 1 FROM catalog WHERE id=?',(item.garment_id,)).fetchone()
            if not exists and db.execute('SELECT COUNT(*) FROM catalog').fetchone()[0] >= 50:
                raise ValueError('Catalog supports at most 50 garments')
            db.execute('INSERT INTO catalog VALUES (?,?) ON CONFLICT(id) DO UPDATE SET payload=excluded.payload',
                       (item.garment_id,item.model_dump_json()))

    def delete(self, garment_id: str):
        with closing(sqlite3.connect(self.path, timeout=10)) as db, db:
            if not db.execute('DELETE FROM catalog WHERE id=?',(garment_id,)).rowcount:
                raise KeyError(garment_id)


def main():
    import argparse
    parser = argparse.ArgumentParser(description='Manage the persistent base garment catalog locally')
    parser.add_argument('--db',default='data/catalog.sqlite3')
    commands = parser.add_subparsers(dest='command',required=True)
    commands.add_parser('list')
    commands.add_parser('init')
    commands.add_parser('put').add_argument('json_file')
    commands.add_parser('delete').add_argument('garment_id')
    args = parser.parse_args()
    repository = CatalogRepository(args.db)
    if args.command == 'put':
        repository.put(GarmentWrite.model_validate_json(Path(args.json_file).read_text(encoding='utf-8')))
    elif args.command == 'delete':
        repository.delete(args.garment_id)
    print(json.dumps({'items':[item.model_dump() for item in repository.snapshot().values()]},ensure_ascii=False,indent=2))


if __name__ == '__main__':
    main()
