import hashlib
import io
import json
from zipfile import ZipFile

import pytest
from PIL import Image

from tools.install_studio_assets import install_pack


def test_pack_validation_precedes_all_writes_and_rejects_unknown_paths(tmp_path):
    pixels=io.BytesIO();Image.new('RGBA',(2,2),(40,50,60,0)).save(pixels,'PNG');data=pixels.getvalue()
    item={'file':'one.png','width':2,'height':2,'sha256':hashlib.sha256(data).hexdigest()}
    (tmp_path/'manifest.json').write_text(json.dumps({'items':[item]}),encoding='utf-8')
    (tmp_path/'one.png').write_bytes(b'old')
    for filename,entries in [('bad.zip',{'one.png':b'wrong'}),('path.zip',{'one.png':data,'../escape.png':data})]:
        pack=tmp_path/filename
        with ZipFile(pack,'w') as archive:
            for name,content in entries.items():archive.writestr(name,content)
        with pytest.raises(ValueError):install_pack(pack,tmp_path)
        assert (tmp_path/'one.png').read_bytes()==b'old'
    pack=tmp_path/'valid.zip'
    with ZipFile(pack,'w') as archive:archive.writestr('one.png',data)
    assert install_pack(pack,tmp_path)==1
    assert (tmp_path/'one.png').read_bytes()==data
