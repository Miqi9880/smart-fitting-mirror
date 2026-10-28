"""Validate contracts and synthetic fixtures; does not run an app or server."""
import copy
import json
import re
from pathlib import Path
from urllib.parse import urlsplit

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


def garment_semantics(item):
    image = item['preview_image']
    category = item['category']
    if category == 'upper_body':
        require(image['anchors'] is not None, 'Upper-body photo requires torso anchors')
    elif category in {'dress', 'skirt', 'shorts'}:
        require({'left_hip','right_hip','left_hem','right_hem'} <= image.get('attachment_anchors', {}).keys(),
                'Draped items and shorts require hip/hem controls')
        require((image['anchors'] is not None) == (category == 'dress'), 'Only dresses use additional torso anchors')
        ratio = image.get('hem_ratio')
        require(isinstance(ratio,(int,float)) and .1 <= ratio <= (1 if category == 'shorts' else 2),
                'Required hem ratio must describe the actual supported leg segment')
    else:
        required = {'left_ear', 'right_ear'} if item['category'] == 'headwear' else {
            'left_hip', 'right_hip', 'left_knee', 'right_knee', 'left_ankle', 'right_ankle'}
        require(image['anchors'] is None and required <= image.get('attachment_anchors', {}).keys(),
                'Non-torso item requires its own anatomical anchors')


def video_semantics(data):
    items = data['items']
    require(len(items) == min(data['requested_limit'], data['available_count']), 'Inconsistent candidate count')
    ids = [item['video']['video_id'] for item in items]
    require(len(ids) == len(set(ids)), 'Duplicate video ids')
    for item in items:
        video = item['video']
        for value in [video['playback_url'], video['cover_url']]:
            if value is None:
                continue
            url = urlsplit(value)
            require(url.scheme in {'http', 'https'} and url.hostname and url.hostname.isascii() and
                    url.username is None and url.password is None and '#' not in value and
                    not any(c.isspace() for c in value), 'Unsafe media URL')
            url.port
        for match in item['matches']:
            require({'dimension': match['dimension'], 'value': match['value']} in video['tags'], 'Match is not a video tag')
            require((match['source'] in {'garment_metadata', 'makeup_metadata'}) == (match['related_id'] is not None),
                    'Match provenance is inconsistent')


rejection_count = 0


def expect_rejected(fn, value):
    global rejection_count
    try:
        fn(value)
    except (ValueError, ValidationError):
        rejection_count += 1
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
                       'garment-details.json': 'Garment', 'garment-write.json': 'GarmentWrite',
                       'outfit-garments.json': 'GarmentList', 'extended-garments.json': 'GarmentList',
                       'video-request.json': 'VideoRecommendationRequest', 'video-matched.json': 'VideoRecommendationList',
                       'video-shortfall.json': 'VideoRecommendationList', 'video-empty.json': 'VideoRecommendationList'}
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
        for item in data['items'] if schema_name == 'GarmentList' else [data] if schema_name == 'Garment' else []:
            garment_semantics(item)
        if schema_name == 'Job':
            job_semantics(data)
            require(data['is_mock'], 'Repository result examples must be explicitly synthetic')
        if schema_name == 'VideoRecommendationList':
            video_semantics(data)
            require(all(i['video']['source']['kind'] == 'synthetic_test' for i in data['items']), 'Example videos must be synthetic')
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
    for category, mutate in [('dress', lambda image: image.pop('hem_ratio')),
                             ('shorts', lambda image: image.update(hem_ratio=1.7)),
                             ('skirt', lambda image: image.update(anchors=read(EX / 'outfit-garments.json')['items'][0]['preview_image']['anchors']))]:
        item = next(i for i in read(EX / 'extended-garments.json')['items'] if i['category'] == category)
        mutate(item['preview_image'])
        expect_rejected(garment_semantics, item)

    def validate_video(data):
        OAS30Validator(expand_refs(spec['components']['schemas']['VideoRecommendationList'], spec)).validate(data)
        video_semantics(data)

    for mutate in [lambda d: d.update(available_count=0),
                   lambda d: d['items'][0]['video'].update(playback_url='https://token@media.example.invalid/clip.mp4'),
                   lambda d: d['items'][0]['matches'][0].update(value='unrelated'),
                   lambda d: d['items'][0]['matches'][0].update(related_id=None),
                   lambda d: d['items'][0]['video'].update(duration_seconds=0),
                   lambda d: d['items'][0]['video']['source'].update(kind='unverified')]:
        bad = read(EX / 'video-shortfall.json')
        mutate(bad)
        expect_rejected(validate_video, bad)
    bad = read(EX / 'video-shortfall.json')
    bad['available_count'] = 2
    bad['items'].append(copy.deepcopy(bad['items'][0]))
    expect_rejected(validate_video, bad)
    request_schema = expand_refs(spec['components']['schemas']['VideoRecommendationRequest'], spec)
    for bad in [{'garment_ids': ['upper_blue', 'upper_blue']}, {'limit': 21}, {'photo_url': 'https://example.invalid/person.png'},
                {'user_tags': [{'dimension': 'style', 'value': '   '}]}]:
        expect_rejected(OAS30Validator(request_schema).validate, bad)

    markdowns = [ROOT / 'README.md', ROOT / 'CONTRIBUTING.md', *sorted((ROOT / 'docs').rglob('*.md')),
                 EX / 'README.md']
    for path in markdowns:
        for target in re.findall(r'\]\(([^)]+)\)', path.read_text(encoding='utf-8')):
            if '://' not in target and not target.startswith('#'):
                require((path.parent / target.split('#')[0]).exists(), f'Broken link: {path}: {target}')
    print(f'PASS: OpenAPI 3.0.3, 2 JSON schemas, {len(seen)} fixtures, {rejection_count} rejection cases, local doc links.')
    print('No Android build, camera, mock server or real AI service was tested.')


if __name__ == '__main__':
    main()
