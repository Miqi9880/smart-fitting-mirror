import * as THREE from 'three';

export const BODY = {
  offset:.78,
  torso:{center:[0,.31,0],radii:[.19,.275,.110]},
  clavicle:{center:[0,.535,0],radii:[.205,.105,.103]},
  neck:{center:[0,.655,0],radii:[.060,.084,.065]},
  hip:{center:[0,.02,0],radii:[.18,.11,.12]},
  upperRadius:.043,lowerRadius:.038,
  shoulder:[.205,.595,0],elbow:[.12,-.285,0],wrist:[.035,-.255,.018],
};
export const NEUTRAL_ARM_ANGLE=Math.atan2(-BODY.elbow[1],BODY.elbow[0]);

export function createRig(geometry,materials=[]) {
  const root=new THREE.Bone();root.name='garment_root';
  const bones=[root];
  for(const [side,sign] of [['left',1],['right',-1]]){
    const shoulder=new THREE.Bone();shoulder.name=`${side}_shoulder`;
    shoulder.position.set(sign*BODY.shoulder[0],BODY.shoulder[1],0);root.add(shoulder);bones.push(shoulder);
  }
  for(const [index,side,sign] of [[1,'left',1],[2,'right',-1]]){
    const elbow=new THREE.Bone();elbow.name=`${side}_elbow`;elbow.position.set(sign*BODY.elbow[0],BODY.elbow[1],0);
    bones[index].add(elbow);bones.push(elbow);
  }
  const mesh=new THREE.SkinnedMesh(geometry,materials);mesh.name='blue_tee_template';mesh.add(root);mesh.updateMatrixWorld(true);
  bones[1].rotation.z=NEUTRAL_ARM_ANGLE;bones[2].rotation.z=-NEUTRAL_ARM_ANGLE;mesh.updateMatrixWorld(true);
  mesh.bind(new THREE.Skeleton(bones));mesh.normalizeSkinWeights();return mesh;
}

export function setPose(bones,pose='rest',phase=1) {
  const byName=Object.fromEntries(bones.map(b=>[b.name,b]));
  for(const b of bones)b.rotation.set(0,0,0);
  if(pose==='neutral'){
    byName.left_shoulder.rotation.z=NEUTRAL_ARM_ANGLE;byName.right_shoulder.rotation.z=-NEUTRAL_ARM_ANGLE;
  }else if(pose==='raise'){
    byName.left_shoulder.rotation.z=phase*1.05;
    byName.right_shoulder.rotation.z=-phase*1.05;
  }else if(pose==='reach'){
    for(const side of ['left','right']){
      byName[`${side}_shoulder`].rotation.x=-phase*.60;
      byName[`${side}_elbow`].rotation.x=-phase*.65;
    }
  }
  byName.garment_root.updateMatrixWorld(true);
}

export function sampleSkinned(mesh,index) {
  mesh.updateMatrixWorld(true);mesh.skeleton.update();
  return mesh.applyBoneTransform(index,new THREE.Vector3().fromBufferAttribute(mesh.geometry.attributes.position,index));
}

export function poseGeometry(mesh) {
  mesh.updateMatrixWorld(true);mesh.skeleton.update();
  const positions=new Float64Array(mesh.geometry.attributes.position.array.length),v=new THREE.Vector3();
  for(let i=0;i<positions.length/3;i++){
    v.fromBufferAttribute(mesh.geometry.attributes.position,i);mesh.applyBoneTransform(i,v);v.toArray(positions,i*3);
  }
  return positions;
}

export function bodyColliders(mesh,margin=.004) {
  mesh.updateMatrixWorld(true);
  const colliders=Object.values(BODY).filter(v=>v?.radii).map(v=>({type:'ellipsoid',center:v.center,radii:v.radii.map(r=>r+margin)}));
  const inverse=mesh.matrixWorld.clone().invert();
  for(const [side,sign] of [['left',1],['right',-1]]){
    const shoulder=mesh.skeleton.bones.find(b=>b.name===`${side}_shoulder`),elbow=mesh.skeleton.bones.find(b=>b.name===`${side}_elbow`);
    const a=new THREE.Vector3().setFromMatrixPosition(shoulder.matrixWorld).applyMatrix4(inverse);
    const b=new THREE.Vector3().setFromMatrixPosition(elbow.matrixWorld).applyMatrix4(inverse);
    const c=new THREE.Vector3(sign*BODY.wrist[0],BODY.wrist[1],BODY.wrist[2]).applyMatrix4(elbow.matrixWorld).applyMatrix4(inverse);
    colliders.push({type:'capsule',a:a.toArray(),b:b.toArray(),radius:BODY.upperRadius+margin});
    colliders.push({type:'capsule',a:b.toArray(),b:c.toArray(),radius:BODY.lowerRadius+margin});
  }
  return colliders;
}
