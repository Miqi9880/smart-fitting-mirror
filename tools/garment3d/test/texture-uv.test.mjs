import test from 'node:test';
import assert from 'node:assert/strict';
import * as THREE from 'three';
import {padFrontUV} from '../texture-uv.mjs';

test('front UV padding stays in opaque cloth without editing PNG pixels or back UVs',()=>{
  const data=new Uint8Array(32*32*4);
  for(let y=6;y<26;y++)for(let x=6;x<26;x++)data[(y*32+x)*4+3]=255;
  const original=data.slice(),g=new THREE.BufferGeometry();
  g.setAttribute('uv',new THREE.Float32BufferAttribute([.01,.01,.5,.5,.98,.98,0,0],2));g.setIndex([0,1,2]);g.addGroup(0,3,0);
  const result=padFrontUV(g,{width:32,height:32,data},{margin:1});
  assert.equal(result.adjusted_vertices,2);assert.deepEqual(data,original);
  for(let i=0;i<3;i++){
    const x=Math.floor(g.attributes.uv.getX(i)*32),y=Math.floor(g.attributes.uv.getY(i)*32);
    for(let dy=-1;dy<=1;dy++)for(let dx=-1;dx<=1;dx++)assert.equal(data[((y+dy)*32+x+dx)*4+3],255);
  }
  assert.equal(g.attributes.uv.getX(3),0);assert.equal(g.attributes.uv.getY(3),0);
});
