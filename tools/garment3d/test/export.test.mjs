import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import crypto from 'node:crypto';
import {validateBytes} from 'gltf-validator';

test('local exported GLB passes Khronos validation and preserves the original PNG',async()=>{
  const bytes=await fs.readFile(new URL('../.local/blue-tee.glb',import.meta.url));
  const report=await validateBytes(new Uint8Array(bytes),{maxIssues:1000});
  assert.equal(report.issues.numErrors,0,JSON.stringify(report.issues));
  assert.equal(report.issues.numWarnings,0,JSON.stringify(report.issues));
  const jsonLength=bytes.readUInt32LE(12),data=JSON.parse(bytes.subarray(20,20+jsonLength).toString());
  assert.equal(data.skins[0].joints.length,5);assert.equal(data.animations,undefined);
  assert.match(data.asset.extras.geometry,/not a scan or AI/);
  assert.match(data.asset.extras.unseen_surfaces,/approximate/);
  assert.match(data.asset.extras.motion,/Neutral T-pose/);
  assert.equal(data.images[0].mimeType,'image/png');
  assert.ok(data.materials.every(m=>m.alphaMode===undefined||m.alphaMode==='OPAQUE'),'3D cloth must not inherit photo cutout holes');
  assert.ok(data.asset.extras.texture_uv_padding.adjusted_vertices>0);
  const imageView=data.bufferViews[data.images[0].bufferView],start=20+jsonLength+8+imageView.byteOffset;
  const image=bytes.subarray(start,start+imageView.byteLength);
  assert.equal(crypto.createHash('sha256').update(image).digest('hex'),data.asset.extras.source_sha256);
});
