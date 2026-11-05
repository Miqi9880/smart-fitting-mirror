import test from 'node:test';
import assert from 'node:assert/strict';
import * as THREE from 'three';
import {buildGarment,createRig} from '../template.mjs';
import {BODY} from '../rig.mjs';
import {solveLivePose,applyLiveRig,LiveFrameOrder,LiveGarmentDeformer} from '../live-driver.mjs';

function fixture({left=0,right=0,forward=0,shift=0,scale=300,sequence=1}={}){
  const points={},point=v=>[.5+shift+v.x*scale/640,.76-(v.y-.02)*scale/480,-v.z*scale/640,.99];
  for(const [side,sign,raise] of [['left',1,left],['right',-1,right]]){
    const s=new THREE.Vector3(sign*BODY.shoulder[0],BODY.shoulder[1],0);
    const q=new THREE.Quaternion().setFromEuler(new THREE.Euler(-forward,0,sign*raise));
    const e=s.clone().add(new THREE.Vector3(sign*BODY.elbow[0],BODY.elbow[1],0).applyQuaternion(q));
    const w=e.clone().add(new THREE.Vector3(sign*BODY.wrist[0],BODY.wrist[1],BODY.wrist[2]).applyQuaternion(q));
    points[`${side}_shoulder`]=point(s);points[`${side}_elbow`]=point(e);points[`${side}_wrist`]=point(w);
    points[`${side}_hip`]=point(new THREE.Vector3(sign*.15,.02,0));
  }
  return {version:1,session:'test',source:'replay',sequence,width:640,height:480,mirrored:false,points};
}
test('continuous independent shoulders and forward depth drive bones rather than selecting an action clip',()=>{
  const base=solveLivePose(fixture()),raised=solveLivePose(fixture({left:.73})),reach=solveLivePose(fixture({forward:.58}));
  assert.ok(base.valid&&raised.valid&&reach.valid);
  assert.ok(base.arms.left.shoulder.angleTo(raised.arms.left.shoulder)>.7);
  assert.ok(base.arms.right.shoulder.angleTo(raised.arms.right.shoulder)<1e-6);
  assert.ok(Math.abs(reach.arms.left.shoulder.x)>.1);
  const geometry=buildGarment(),mesh=createRig(geometry);
  applyLiveRig(mesh.skeleton.bones,raised);
  assert.ok(mesh.skeleton.bones.find(b=>b.name==='left_shoulder').quaternion.angleTo(raised.arms.left.shoulder)<1e-6);
});
test('image-space fitting matches both shoulders/hips and preserves source aspect on mirror toggles',()=>{
  const packet=fixture({shift:.07,scale:260}),s=solveLivePose(packet);
  assert.ok(s.valid);
  for(const sign of [-1,1]){
    const expected=new THREE.Vector3(sign*BODY.shoulder[0],BODY.shoulder[1]+BODY.offset,0).applyMatrix4(s.matrix);
    const actual=s.points[sign===1?'left_shoulder':'right_shoulder'];assert.ok(expected.distanceTo(actual)<1e-6);
  }
  assert.deepEqual(s.matrix.elements,solveLivePose({...packet,mirrored:true}).matrix.elements,'mirror is applied once to the whole image/garment, not to anatomical labels');
});
test('incomplete, low-confidence, nonfinite and side-collapsed poses hide the garment instead of freezing it',()=>{
  for(const change of [p=>delete p.points.left_wrist,p=>p.points.left_hip[3]=.2,p=>p.points.right_elbow[2]=NaN,
    p=>p.points.left_shoulder[0]=p.points.right_shoulder[0]]){
    const packet=fixture();change(packet);assert.equal(solveLivePose(packet).valid,false);
  }
});
test('frame order refuses old sessions, duplicate frames and arrivals after stop',()=>{
  const order=new LiveFrameOrder(),p=fixture();order.begin('test','replay');assert.ok(order.accepts(p));assert.ok(!order.accepts(p));
  assert.ok(!order.accepts({...p,sequence:2,session:'other'}));assert.ok(order.accepts({...p,sequence:3}));
  order.end();assert.ok(!order.accepts({...p,sequence:4}));order.begin('new','camera');assert.ok(!order.accepts({...p,sequence:5}));
});
test('runtime deformation stays finite with independently moving arms across continuous unbaked poses',()=>{
  const geometry=buildGarment(),mesh=createRig(geometry),deformer=new LiveGarmentDeformer(mesh);
  let previous,maxMove=0;
  for(let f=0;f<45;f++){
    const solution=solveLivePose(fixture({left:.8*Math.sin(f/44*Math.PI/2),right:.3*Math.sin(f/44*Math.PI),forward:.45*f/44}));
    assert.ok(solution.valid);const current=deformer.update(solution);
    for(let i=0;i<current.length;i++){assert.ok(Number.isFinite(current[i]));assert.ok(Math.abs(current[i])<1.5);}
    if(previous)for(let i=0;i<current.length;i+=3)maxMove=Math.max(maxMove,Math.hypot(current[i]-previous[i],current[i+1]-previous[i+1],current[i+2]-previous[i+2]));
    previous=current.slice();
  }
  assert.ok(maxMove<.06,`mesh jumped ${maxMove}m within a smooth fixture`);
});
