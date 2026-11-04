import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import crypto from 'node:crypto';

test('local motion data matches the exported geometry and declared clip boundaries',async()=>{
  const json=async file=>JSON.parse(await fs.readFile(new URL(`../.local/${file}`,import.meta.url),'utf8'));
  const [motion,model,bytes]=await Promise.all([json('motion.json'),json('sample.json'),fs.readFile(new URL('../.local/motion.bin',import.meta.url))]);
  assert.equal(motion.version,2);assert.equal(motion.geometry_sha256,model.geometry_sha256);
  assert.equal(motion.vertices,model.vertices);assert.equal(motion.stride,motion.vertices*3);
  assert.equal(bytes.length,motion.byte_length);assert.equal(bytes.length,motion.frame_count*motion.stride*4);
  assert.equal(crypto.createHash('sha256').update(bytes).digest('hex'),motion.motion_sha256);
  assert.equal(motion.clips.rest.start,0);assert.equal(motion.clips.rest.count,1);
  assert.equal(motion.clips.raise.start,1);assert.equal(motion.clips.reach.start,1+motion.clips.raise.count);
  assert.equal(motion.frame_count,1+motion.clips.raise.count+motion.clips.reach.count);
  assert.match(motion.method,/Precomputed pose-guided/);
});
