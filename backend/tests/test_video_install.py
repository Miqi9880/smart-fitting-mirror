import hashlib
import json
from zipfile import ZipFile
import pytest
from tools.install_video_assets import install_pack, check_installed


def pack(tmp_path, mutate=None):
    assets=tmp_path/'assets'; assets.mkdir()
    payload={'example.mp4':b'synthetic transport bytes','example.jpg':b'synthetic cover bytes'}
    entry=dict(file='example.mp4',sha256=hashlib.sha256(payload['example.mp4']).hexdigest(),bytes=len(payload['example.mp4']),
               poster_file='example.jpg',poster_sha256=hashlib.sha256(payload['example.jpg']).hexdigest(),poster_bytes=len(payload['example.jpg']))
    manifest={'entries':[entry]}
    (assets/'manifest.json').write_text(json.dumps(manifest),encoding='utf-8')
    data={**payload,'manifest.json':b'ignored archive manifest','README.txt':b'synthetic fixture only'}
    if mutate: mutate(data)
    path=tmp_path/'pack.zip'
    with ZipFile(path,'w') as archive:
        for name,value in data.items(): archive.writestr(name,value)
    return assets,path


def test_fixed_pack_installs_only_verified_assets_and_detects_later_corruption(tmp_path):
    assets,path=pack(tmp_path)
    assert install_pack(path,assets)==1 and check_installed(assets)==1
    (assets/'media/example.mp4').write_bytes(b'changed')
    with pytest.raises(ValueError):check_installed(assets)


@pytest.mark.parametrize('mutate',[
    lambda d:d.update({'example.mp4':b'wrong bytes'}),
    lambda d:d.update({'../escape.mp4':b'synthetic bytes'}),
    lambda d:d.pop('example.jpg'),
])
def test_entire_pack_is_validated_before_writing_any_asset(tmp_path,mutate):
    assets,path=pack(tmp_path,mutate)
    with pytest.raises(ValueError):install_pack(path,assets)
    assert not (assets/'media').exists()


def test_media_directory_link_cannot_redirect_install(tmp_path):
    assets,path=pack(tmp_path)
    outside=tmp_path/'outside'; outside.mkdir()
    try:
        (assets/'media').symlink_to(outside, target_is_directory=True)
    except OSError:
        pytest.skip('Creating directory symlinks is not permitted on this Windows host')
    with pytest.raises(ValueError): install_pack(path,assets)
    with pytest.raises(ValueError): check_installed(assets)
    assert not list(outside.iterdir())
