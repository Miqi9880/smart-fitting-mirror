import test from 'node:test';
import assert from 'node:assert/strict';
import * as THREE from 'three';
import {buildGarment,createRig,setPose,sampleSkinned} from '../template.mjs';

const g=buildGarment(),p=g.attributes.position;
test('finite nondegenerate volume, UVs and normalized skin weights',()=>{
  assert.ok(g.boundingBox.max.z-g.boundingBox.min.z>.25);
  for(const attr of Object.values(g.attributes))for(const v of attr.array)assert.ok(Number.isFinite(v));
  for(const v of g.attributes.uv.array)assert.ok(v>=0&&v<=1);
  const v=new THREE.Vector3(),a=new THREE.Vector3(),b=new THREE.Vector3(),c=new THREE.Vector3();
  for(let i=0;i<p.count;i++){
    assert.ok(Math.abs(v.fromBufferAttribute(g.attributes.normal,i).length()-1)<1e-5);
    assert.ok(Math.abs(Array.from(g.attributes.skinWeight.array.slice(i*4,i*4+4)).reduce((x,y)=>x+y,0)-1)<1e-6);
    for(let j=0;j<4;j++)assert.ok(g.attributes.skinIndex.array[i*4+j]<5);
  }
  for(let i=0;i<g.index.count;i+=3){
    a.fromBufferAttribute(p,g.index.array[i]);b.fromBufferAttribute(p,g.index.array[i+1]);c.fromBufferAttribute(p,g.index.array[i+2]);
    assert.ok(b.sub(a).cross(c.sub(a)).length()>1e-10,`degenerate face ${i/3}`);
  }
});
test('sewn shell has exactly four open loops: neck, hem and two cuffs',()=>{
  const edges=new Map();
  for(let i=0;i<g.index.count;i+=3){const t=g.index.array.slice(i,i+3);
    for(let j=0;j<3;j++){const a=t[j],b=t[(j+1)%3],k=a<b?`${a}:${b}`:`${b}:${a}`;
      const value=edges.get(k)||{count:0,direction:0,a,b};value.count++;value.direction+=a<b?1:-1;edges.set(k,value);}}
  const graph=new Map();
  for(const edge of edges.values()){
    assert.ok(edge.count===1||edge.count===2,'non-manifold edge');
    if(edge.count===2){assert.equal(edge.direction,0,'inconsistent face winding');continue;}
    for(const [a,b] of [[edge.a,edge.b],[edge.b,edge.a]]){if(!graph.has(a))graph.set(a,[]);graph.get(a).push(b);}
  }
  for(const neighbors of graph.values())assert.equal(neighbors.length,2);
  const visited=new Set();let loops=0;
  for(const vertex of graph.keys()){if(visited.has(vertex))continue;loops++;const pending=[vertex];
    while(pending.length){const v=pending.pop();if(visited.has(v))continue;visited.add(v);pending.push(...graph.get(v));}}
  assert.equal(loops,4);
});
test('sleeves follow shoulder bones while torso remains stable',()=>{
  const mesh=createRig(g,[]);let sleeve=0,torso=0;
  for(let i=0;i<p.count;i++){
    if(p.getX(i)>p.getX(sleeve))sleeve=i;
    if(Math.abs(p.getX(i))<.02&&p.getY(i)>.1&&p.getY(i)<.4)torso=i;
  }
  setPose(mesh.skeleton.bones,'rest');
  const before=sampleSkinned(mesh,sleeve).clone(),core=sampleSkinned(mesh,torso).clone();
  setPose(mesh.skeleton.bones,'raise');
  assert.ok(sampleSkinned(mesh,sleeve).distanceTo(before)>.15);
  assert.ok(sampleSkinned(mesh,torso).distanceTo(core)<1e-7);
  setPose(mesh.skeleton.bones,'rest');
  assert.ok(sampleSkinned(mesh,sleeve).distanceTo(before)<1e-7);
});
