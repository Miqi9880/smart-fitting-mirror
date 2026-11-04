import test from 'node:test';
import assert from 'node:assert/strict';
import {bakeMotion} from '../bake-motion.mjs';

test('all fixed action frames avoid the reported triangle stretch and stay outside mannequin proxies',()=>{
  const {array,manifest}=bakeMotion();
  for(const value of array)assert.ok(Number.isFinite(value));
  const samples=[manifest.report.rest,...manifest.report.raise,...manifest.report.reach];
  assert.equal(samples.length,183);
  for(const sample of samples){
    assert.ok(sample.p99<1.10,`bulk stretch at frame ${sample.frame}: ${sample.p99}`);
    assert.ok(sample.max<1.30,`localized stretch at frame ${sample.frame}: ${sample.max}`);
    assert.ok(sample.maxPenetration<.002,`proxy surface intersection: ${JSON.stringify(sample.worstContact)}`);
    assert.ok(sample.contactSamples>12000);
    assert.ok(sample.maxCuffError<1e-6,'sleeve rim must not fold into an unaligned pointed opening');
  }
  assert.ok(manifest.report.raise.at(-1).max<1.01,'full raised-arm end pose must no longer pull a triangular wing');
  for(const clip of [manifest.clips.raise,manifest.clips.reach]){
    for(let f=1;f<clip.count;f++){
      let displacement=0;
      for(let i=0;i<manifest.stride;i+=3){
        const a=(clip.start+f-1)*manifest.stride+i,b=a+manifest.stride;
        displacement=Math.max(displacement,Math.hypot(array[b]-array[a],array[b+1]-array[a+1],array[b+2]-array[a+2]));
      }
      assert.ok(displacement<.025,`discontinuous motion at frame ${f}: ${displacement}`);
    }
  }
});
