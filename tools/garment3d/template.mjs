import * as THREE from 'three';

// Authored sample pattern. Pixel coordinates describe the inspected blue tee's
// front framing, not measured garment dimensions or a recovered sewing pattern.
const OUTLINE = [
  [263,1240,'hem'], [938,1240,'side'], [938,711,'underarm'],
  [958,768,'cuff'], [1194,651,'sleeve'], [1002,242,'shoulder'],
  [895,129,'shoulder'], [730,55,'neck'], [720,120,'neck'],
  [690,172,'neck'], [650,202,'neck'], [603,211,'neck'],
  [555,201,'neck'], [515,175,'neck'], [487,130,'neck'],
  [475,55,'shoulder'], [310,128,'shoulder'], [202,240,'sleeve'],
  [10,650,'cuff'], [251,768,'underarm'], [271,711,'side'],
];
export const TEMPLATE_ID = 'crewneck-short-sleeve-v1';
export const SOURCE_SIZE = [1206,1304];
const smooth = (a,b,x) => { const t=THREE.MathUtils.clamp((x-a)/(b-a),0,1); return t*t*(3-2*t); };
const key = (a,b) => a<b ? `${a}:${b}` : `${b}:${a}`;
export const worldPoint = ([x,y]) => [(x-603)*.0007,(1240-y)*.00058];
const seamSegments=OUTLINE.flatMap((p,i)=>['neck','hem','cuff'].includes(p[2])?[]:[[worldPoint(p),worldPoint(OUTLINE[(i+1)%OUTLINE.length])]]);
function seamDistance(x,y){
  return Math.min(...seamSegments.map(([a,b])=>{
    const dx=b[0]-a[0],dy=b[1]-a[1];
    const t=THREE.MathUtils.clamp(((x-a[0])*dx+(y-a[1])*dy)/(dx*dx+dy*dy),0,1);
    return Math.hypot(x-a[0]-t*dx,y-a[1]-t*dy);
  }));
}

function depth(x,y) {
  const torso = .145*Math.sqrt(Math.max(.13,1-(x/.267)**2))*(1-.24*smooth(.50,.69,y));
  const ax=Math.abs(x)-.225, ay=y-.563;
  const across=(ax*.27+ay*.145)/Math.hypot(.27,.145);
  const sleeve=.079*Math.sqrt(Math.max(.10,1-(across/.103)**2));
  const blend=smooth(.19,.30,Math.abs(x));
  const fold=.004*Math.sin(x*40+y*9)*Math.sin(y*12)+.0018*Math.sin(x*78-y*17);
  const volume=torso*(1-blend)+sleeve*blend+fold;
  // Round the front/back surfaces toward the sewn boundaries, leaving a thin
  // seam rather than a box-like wall. Open cuffs/neck/hem retain their volume.
  return .003+(volume-.003)*smooth(0,.060,seamDistance(x,y));
}

export function buildGarment({subdivisions=4}={}) {
  if (!Number.isInteger(subdivisions)||subdivisions<0||subdivisions>5) throw new Error('Invalid refinement');
  const front=OUTLINE.map(p=>p.slice(0,2));
  const back=front.map(p=>p.slice());
  [67,80,87,90,86,78,65].forEach((y,i)=>{back[i+8][1]=y;});
  let faces=THREE.ShapeUtils.triangulateShape(front.map(p=>new THREE.Vector2(...worldPoint(p))),[]);
  faces=faces.map(t=>{
    const [a,b,c]=t.map(i=>worldPoint(front[i]));
    return (b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0])>0?t:[t[0],t[2],t[1]];
  });
  let edges=new Map(OUTLINE.map((p,i)=>[key(i,(i+1)%OUTLINE.length),p[2]]));
  for(let pass=0;pass<subdivisions;pass++) {
    const mids=new Map(), nextEdges=new Map();
    const midpoint=(a,b)=>{
      const k=key(a,b); if(mids.has(k))return mids.get(k);
      const index=front.length;
      front.push(front[a].map((n,j)=>(n+front[b][j])/2));
      back.push(back[a].map((n,j)=>(n+back[b][j])/2));
      mids.set(k,index);
      if(edges.has(k)) {nextEdges.set(key(a,index),edges.get(k));nextEdges.set(key(index,b),edges.get(k));}
      return index;
    };
    faces=faces.flatMap(([a,b,c])=>{const ab=midpoint(a,b),bc=midpoint(b,c),ca=midpoint(c,a);return [[a,ab,ca],[ab,b,bc],[ca,bc,c],[ab,bc,ca]];});
    edges=nextEdges;
  }
  const count=front.length, positions=[],uv=[],joints=[],weights=[];
  for(let side=0;side<2;side++) for(let i=0;i<count;i++) {
    const [x,y]=worldPoint((side?back:front)[i]);
    const z=depth(x,y)*(side?-1:1);
    positions.push(x,y,z);
    // glTF's texture origin is the top-left; GLTFLoader uses flipY=false.
    uv.push(front[i][0]/SOURCE_SIZE[0],front[i][1]/SOURCE_SIZE[1]);
    const seamX=.203-(y-.307)*.065;
    const w=smooth(-.025,.025,Math.abs(x)-seamX)*smooth(.21,.28,y);
    joints.push(0,w>0?(x>=0?1:2):0,0,0); weights.push(1-w,w,0,0);
  }
  const fi=faces.flat(), bi=faces.flatMap(([a,b,c])=>[c+count,b+count,a+count]),si=[];
  // Find oriented boundary edges from the triangulation, avoiding guessed seam winding.
  const directed=new Map();
  for(const [a,b,c] of faces) for(const [p,q] of [[a,b],[b,c],[c,a]]) {
    const k=key(p,q); if(directed.has(k))directed.delete(k);else directed.set(k,[p,q]);
  }
  for(const [k,[a,b]] of directed) {
    const type=edges.get(k);
    if(!type)throw new Error('Unclassified pattern boundary');
    if(['neck','hem','cuff'].includes(type))continue;
    si.push(a,a+count,b,b,a+count,b+count);
  }
  const geometry=new THREE.BufferGeometry();
  geometry.setAttribute('position',new THREE.Float32BufferAttribute(positions,3));
  geometry.setAttribute('uv',new THREE.Float32BufferAttribute(uv,2));
  geometry.setAttribute('skinIndex',new THREE.Uint16BufferAttribute(joints,4));
  geometry.setAttribute('skinWeight',new THREE.Float32BufferAttribute(weights,4));
  geometry.setIndex([...fi,...bi,...si]);
  geometry.addGroup(0,fi.length,0);geometry.addGroup(fi.length,bi.length,1);geometry.addGroup(fi.length+bi.length,si.length,1);
  geometry.computeVertexNormals();geometry.computeBoundingBox();geometry.computeBoundingSphere();
  geometry.userData={templateId:TEMPLATE_ID,patternVertices:count,sourceSize:SOURCE_SIZE,openings:['neck','hem','left_cuff','right_cuff']};
  return geometry;
}

export function createRig(geometry,materials) {
  const root=new THREE.Bone();root.name='garment_root';
  const left=new THREE.Bone();left.name='left_shoulder';left.position.set(.210,.563,0);
  const right=new THREE.Bone();right.name='right_shoulder';right.position.set(-.210,.563,0);
  const le=new THREE.Bone();le.name='left_elbow';le.position.set(.12,-.285,0);
  const re=new THREE.Bone();re.name='right_elbow';re.position.set(-.12,-.285,0);
  root.add(left,right);left.add(le);right.add(re);
  const mesh=new THREE.SkinnedMesh(geometry,materials);mesh.name='blue_tee_template';mesh.add(root);mesh.updateMatrixWorld(true);
  mesh.bind(new THREE.Skeleton([root,left,right,le,re]));mesh.normalizeSkinWeights();
  return mesh;
}

export function setPose(bones,pose='rest',phase=1) {
  const byName=Object.fromEntries(bones.map(b=>[b.name,b]));
  for(const b of bones)b.rotation.set(0,0,0);
  if(pose==='raise') {
    byName.left_shoulder.rotation.z=phase*1.05;
    byName.right_shoulder.rotation.z=-phase*1.05;
  } else if(pose==='reach') {
    for(const side of ['left','right']) {
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
