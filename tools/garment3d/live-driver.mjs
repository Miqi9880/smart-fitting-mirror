import * as THREE from 'three';
import {BODY,poseGeometry} from './rig.mjs';
import {ClothSimulation} from './cloth.mjs';

const names=['left_shoulder','right_shoulder','left_elbow','right_elbow','left_wrist','right_wrist','left_hip','right_hip'];
const clamp=(v,a,b)=>Math.max(a,Math.min(b,v));
const point=(p,w,h,z0)=>new THREE.Vector3((p[0]-.5)*w,(.5-p[1])*h,-clamp(p[2]-z0,-.8,.8)*w);
export function validLiveFrame(p) {
  return p?.version===1 && ['camera','replay'].includes(p.source) && typeof p.session==='string' && p.session.length>0 && p.session.length<=80 &&
    Number.isSafeInteger(p.sequence) && p.sequence>=0 && Number.isInteger(p.width) && Number.isInteger(p.height) &&
    p.width>=64 && p.height>=64 && p.width<=1280 && p.height<=1280 && typeof p.mirrored==='boolean' &&
    p.points && typeof p.points==='object' && !Array.isArray(p.points);
}
const usable=p=>Array.isArray(p)&&p.length===4&&p.every(Number.isFinite)&&p[0]>=0&&p[0]<=1&&p[1]>=0&&p[1]<=1&&p[3]>=.65&&p[3]<=1;

/** A weak-perspective image fit. z is the detector's experimental relative cue,
 * not measured body depth. No cached rest/raise/reach motion is selected here.
 */
export function solveLivePose(packet) {
  if(!validLiveFrame(packet))return {valid:false,reason:'输入无效'};
  if(!names.every(name=>usable(packet.points[name])))return {valid:false,reason:'请让双肩、髋部和双手臂清楚入镜'};
  const z0=(packet.points.left_hip[2]+packet.points.right_hip[2])/2;
  const points=Object.fromEntries(names.map(name=>[name,point(packet.points[name],packet.width,packet.height,z0)]));
  const ls=points.left_shoulder,rs=points.right_shoulder;
  const hip=points.left_hip.clone().add(points.right_hip).multiplyScalar(.5);
  const shoulder=ls.clone().add(rs).multiplyScalar(.5),right=ls.clone().sub(rs),up=shoulder.clone().sub(hip);
  // A common shoulder-vs-hip z bias is NOT a measured torso pitch. Keeping it
  // here tipped the neck opening toward the camera and exposed the back collar.
  up.z=0;right.z=clamp(right.z,-Math.hypot(right.x,right.y)*.6,Math.hypot(right.x,right.y)*.6);
  const projectedWidth=Math.hypot(ls.x-rs.x,ls.y-rs.y),height=up.length();
  if(projectedWidth<packet.width*.045 || height<packet.height*.09 || right.length()/height<.30 || right.length()/height>1.9)
    return {valid:false,reason:'请正对摄像头，让上半身完整入镜'};
  const front=new THREE.Vector3().crossVectors(right,up);
  if(front.length()<projectedWidth*height*.5 || front.z<0)return {valid:false,reason:'请正面入镜，暂不支持背身试穿'};
  // Shear in the torso plane retains a leaning person's shoulder/hip alignment.
  const sx=right.length()/(BODY.shoulder[0]*2),sy=height/(BODY.shoulder[1]-BODY.hip.center[1]);
  const matrix=new THREE.Matrix4().makeBasis(right.clone().multiplyScalar(1/(BODY.shoulder[0]*2)),
    up.clone().multiplyScalar(1/(BODY.shoulder[1]-BODY.hip.center[1])),front.normalize().multiplyScalar(Math.sqrt(sx*sy)));
  const anchor=new THREE.Vector3(0,BODY.hip.center[1]+BODY.offset,0).applyMatrix4(matrix);
  matrix.setPosition(hip.clone().sub(anchor));
  const inverse=matrix.clone().invert(),arms={},armDepth={};
  for(const [side,sign] of [['left',1],['right',-1]]){
    const s=points[`${side}_shoulder`].clone().applyMatrix4(inverse),e=points[`${side}_elbow`].clone().applyMatrix4(inverse);
    const w=points[`${side}_wrist`].clone().applyMatrix4(inverse),upper=e.clone().sub(s),lower=w.clone().sub(e);
    // Relative z is only a directional cue. It cannot stretch the nominal
    // bones or overwhelm an already full-length projected arm.
    for(const [v,length] of [[upper,Math.hypot(...BODY.elbow)],[lower,Math.hypot(...BODY.wrist)]]){
      const xy=Math.hypot(v.x,v.y),depth=Math.sqrt(Math.max(0,length*length-xy*xy));
      v.z=clamp(v.z,-depth,depth);
    }
    if(upper.length()<.035||lower.length()<.03||Math.hypot(upper.x,upper.y)>.9||Math.hypot(lower.x,lower.y)>.9)return {valid:false,reason:'手臂位置不稳定，请缓慢移动'};
    const q=new THREE.Quaternion().setFromUnitVectors(new THREE.Vector3(sign*BODY.elbow[0],BODY.elbow[1],0).normalize(),upper.normalize());
    const localLower=lower.normalize().applyQuaternion(q.clone().invert());
    const elbow=new THREE.Quaternion().setFromUnitVectors(new THREE.Vector3(sign*BODY.wrist[0],BODY.wrist[1],BODY.wrist[2]).normalize(),localLower);
    arms[side]={shoulder:q,elbow};
    const fittedElbow=new THREE.Vector3(sign*BODY.shoulder[0],BODY.shoulder[1]+BODY.offset,0)
      .add(new THREE.Vector3(sign*BODY.elbow[0],BODY.elbow[1],0).applyQuaternion(q));
    const fittedWrist=fittedElbow.clone().add(new THREE.Vector3(sign*BODY.wrist[0],BODY.wrist[1],BODY.wrist[2]).applyQuaternion(q.clone().multiply(elbow)));
    armDepth[side]={elbow:fittedElbow.applyMatrix4(matrix).z,wrist:fittedWrist.applyMatrix4(matrix).z};
  }
  return {valid:true,matrix,arms,armDepth,points,projectedWidth,hip,shoulder};
}

export function applyLiveRig(bones,solution) {
  if(!solution.valid)return false;
  for(const bone of bones){bone.quaternion.identity();bone.scale.set(1,1,1);}
  for(const side of ['left','right']){
    bones.find(b=>b.name===`${side}_shoulder`).quaternion.copy(solution.arms[side].shoulder);
    bones.find(b=>b.name===`${side}_elbow`).quaternion.copy(solution.arms[side].elbow);
  }
  bones[0].updateMatrixWorld(true);return true;
}

/** Small runtime constraint solve on the actual continuously driven mesh.
 * This first experiment has proxy contact/edge correction, not a full cloth sim.
 */
export class LiveGarmentDeformer {
  constructor(proxy,standing=null){
    this.proxy=proxy;this.solver=new ClothSimulation(proxy.geometry,{iterations:32,follow:.10,limitIterations:10});
    this.standing=standing;this.fresh=true;this.poses=null;
  }
  update(solution,seconds=.1){
    const bones=this.proxy.skeleton.bones;
    if(this.fresh){
      this.poses=Object.fromEntries(['left','right'].map(side=>[side,{shoulder:new THREE.Quaternion(),elbow:new THREE.Quaternion()}]));
      const initial={...solution,arms:this.poses};applyLiveRig(bones,initial);
      const p=this.standing??poseGeometry(this.proxy);this.solver.positions.set(p);this.solver.previous.set(p);this.fresh=false;
    }
    const dt=Math.min(.16,Math.max(.02,seconds));
    for(const side of ['left','right'])for(const joint of ['shoulder','elbow']){
      const eased=this.poses[side][joint].clone().slerp(solution.arms[side][joint],1-Math.exp(-dt*4));
      this.poses[side][joint].rotateTowards(eased,dt*3.0);
    }
    applyLiveRig(bones,{...solution,arms:this.poses});
    return this.solver.step(this.proxy,1/90);
  }
}

/** Session/order gates are independent of image decode and renderer timing. */
export class LiveFrameOrder {
  begin(session,source){this.session=session;this.source=source;this.sequence=-1;this.active=true;}
  accepts(packet){
    if(!this.active||!validLiveFrame(packet)||packet.session!==this.session||packet.source!==this.source||packet.sequence<=this.sequence)return false;
    this.sequence=packet.sequence;return true;
  }
  end(){this.active=false;this.session=null;this.sequence=-1;}
}
