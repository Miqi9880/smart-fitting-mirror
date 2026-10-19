"""Validate contracts and synthetic fixtures; does not run an app or server."""
import copy
import json
import re
from pathlib import Path

from jsonschema import Draft202012Validator
from jsonschema.exceptions import ValidationError
from openapi_schema_validator import OAS30Validator
from openapi_spec_validator import validate_spec

ROOT = Path(__file__).resolve().parents[1]
CON = ROOT / 'contracts'
EX = CON / 'examples'


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def require(condition, message):
    if not condition:
        raise ValueError(message)


def expand_refs(value, document):
    if isinstance(value, dict):
        if '$ref' in value:
            ref = value['$ref']
            require(ref.startswith('#/'), 'Only local contract references are allowed')
            target = document
            for token in ref[2:].split('/'):
                target = target[token.replace('~1', '/').replace('~0', '~')]
            return expand_refs(target, document)
        return {key: expand_refs(item, document) for key, item in value.items()}
    if isinstance(value, list):
        return [expand_refs(item, document) for item in value]
    return value


def vision_semantics(frame):
    torso = [f'{side}_{part}' for part in ['shoulder', 'hip'] for side in ['left', 'right']]
    if frame['tracking_state'] == 'tracking':
        require(all(frame['keypoints'][p] is not None and frame['keypoints'][p]['confidence'] >= .5
                    for p in torso), 'Tracking requires four reliable torso points')
    if frame['tracking_state'] == 'lost':
        require(all(p is None for p in frame['keypoints'].values()), 'Lost must not carry stale points')
    for name, bytes_per_pixel in [('mask', 1), ('depth', 2)]:
        plane = frame[name]
        if plane is not None:
            require(plane['row_stride_bytes'] >= plane['width'] * bytes_per_pixel, 'Invalid plane stride')
    if frame['depth'] is not None:
        require(all(frame['depth'][key] == frame['image'][key] for key in ['width', 'height']),
                'Aligned depth must match RGB dimensions')


def job_semantics(job):
    status = job['status']
    require((job['result'] is not None) == (status == 'succeeded'), 'Result must exist only on success')
    require((job['error'] is not None) == (status == 'failed'), 'Error must exist only on failure')


def expect_rejected(fn, value):
    try:
        fn(value)
    except (ValueError, ValidationError):
        return
    raise AssertionError('Invalid contract fixture was accepted')


def main():
    spec = read(CON / 'openapi.json')
    validate_spec(spec)
    vision_schema = read(CON / 'vision.schema.json')
    caps_schema = read(CON / 'capabilities.schema.json')
    for schema in [vision_schema, caps_schema]:
        Draft202012Validator.check_schema(schema)
    vv = Draft202012Validator(vision_schema)

    def validate_vision(frame):
        vv.validate(frame)
        vision_semantics(frame)

    fixture_schemas = {'garments.json': 'GarmentList', 'session.json': 'Session', 'upload.json': 'Upload',
                       'create-job.json': 'CreateJob', 'error.json': 'ErrorResponse',
                       'garment-details.json': 'Garment', 'garment-write.json': 'GarmentWrite'}
    fixture_schemas.update({f'job-{s}.json': 'Job' for s in ['queued', 'running', 'succeeded', 'failed', 'cancelled']})
    seen = set()
    for path in sorted(EX.glob('vision-*.json')):
        validate_vision(read(path))
        seen.add(path.name)
    Draft202012Validator(caps_schema).validate(read(EX / 'capabilities-rgb.json'))
    seen.add('capabilities-rgb.json')
    for name, schema_name in fixture_schemas.items():
        schema = expand_refs(spec['components']['schemas'][schema_name], spec)
        data = read(EX / name)
        OAS30Validator(schema, format_checker=OAS30Validator.FORMAT_CHECKER).validate(data)
        if schema_name == 'Job':
            job_semantics(data)
            require(data['is_mock'], 'Repository result examples must be explicitly synthetic')
        seen.add(name)
    require(seen == {p.name for p in EX.glob('*.json')}, 'Every fixture must have a validator mapping')

    frame = read(EX / 'vision-tracking.json')
    bad = copy.deepcopy(frame)
    bad['image']['mirrored'] = True
    expect_rejected(validate_vision, bad)
    bad = copy.deepcopy(frame)
    bad['keypoints']['left_shoulder']['x'] = 2
    expect_rejected(validate_vision, bad)
    bad = copy.deepcopy(frame)
    bad['keypoints']['left_shoulder'] = None
    expect_rejected(validate_vision, bad)
    bad = copy.deepcopy(frame)
    bad['depth'] = {'buffer_id': 'bad', 'width': 1, 'height': 1, 'row_stride_bytes': 2,
                    'format': 'depth_uint16_le_mm', 'invalid_value': 0}
    expect_rejected(validate_vision, bad)
    bad = read(EX / 'job-succeeded.json')
    bad['result'] = None
    expect_rejected(job_semantics, bad)

    markdowns = [ROOT / 'README.md', ROOT / 'CONTRIBUTING.md', *sorted((ROOT / 'docs').rglob('*.md')),
                 EX / 'README.md']
    for path in markdowns:
        for target in re.findall(r'\]\(([^)]+)\)', path.read_text(encoding='utf-8')):
            if '://' not in target and not target.startswith('#'):
                require((path.parent / target.split('#')[0]).exists(), f'Broken link: {path}: {target}')
    print(f'PASS: OpenAPI 3.0.3, 2 JSON schemas, {len(seen)} fixtures, 5 rejection cases, local doc links.')
    print('No Android build, camera, mock server or real AI service was tested.')


if __name__ == '__main__':
    main()
