import * as THREE from 'three';
import Delaunator from 'delaunator';
import Constrainautor from '@kninnug/constrainautor';
export {createRig,setPose,sampleSkinned} from './rig.mjs';

// Authored nominal pattern matched to the inspected source framing, not CAD.
const OUTLINE=[
  [263,1240,'hem'],[938,1240,'side'],[938,711,'underarm'],
  [958,768,'cuff'],[1194,651,'sleeve'],[1002,242,'shoulder'],
  [895,129,'shoulder'],[730,55,'neck'],[720,120,'neck'],
  [690,172,'neck'],[650,202,'neck'],[603,211,'neck'],
  [555,201,'neck'],[515,175,'neck'],[487,130,'neck'],
  [475,55,'shoulder'],[310,128,'shoulder'],[202,240,'sleeve'],
  [10,650,'cuff'],[251,768,'underarm'],[271,711,'side'],
];
export const TEMPLATE_ID='crewneck-short-sleeve-v3';
export const SOURCE_SIZE=[1206,1304];
export const worldPoint=([x,y])=>[(x-603)*.0007,(1240-y)*.00058];
const smooth=(a,b,x)=>{const t=THREE.MathUtils.clamp((x-a)/(b-a),0,1);return t*t*(3-2*t);};
const key=(a,b)=>a<b?`${a}:${b}`:`${b}:${a}`;
const sewn=p=>!['neck','hem','cuff'].includes(p[2]);

function distance(p,a,b){
  const dx=b[0]-a[0],dy=b[1]-a[1],t=THREE.MathUtils.clamp(((p[0]-a[0])*dx+(p[1]-a[1])*dy)/(dx*dx+dy*dy),0,1);
  return Math.hypot(p[0]-a[0]-t*dx,p[1]-a[1]-t*dy);
}
function inside([x,y],polygon){
  let result=false;
  for(let i=0,j=polygon.length-1;i<polygon.length;j=i++){
    const a=polygon[i],b=polygon[j];
    if((a[1]>y)!==(b[1]>y)&&x<(b[0]-a[0])*(y-a[1])/(b[1]-a[1])+a[0])result=!result;
  }
  return result;
}
function panel(boundary,spacing){
  const points=boundary.map(p=>p.slice()),n=points.length;
  const boundaryDistance=p=>Math.min(...boundary.map((a,i)=>distance(p,a,boundary[(i+1)%n])));
  let row=0;
  for(let y=.008;y<.69;y+=spacing*Math.sqrt(3)/2,row++)for(let x=-.42+(row%2)*spacing/2;x<.42;x+=spacing){
    const p=[x,y];if(inside(p,boundary)&&boundaryDistance(p)>spacing*.34)points.push(p);
  }
  const triangulation=Delaunator.from(points);
  new Constrainautor(triangulation,Array.from({length:n},(_,i)=>[i,(i+1)%n]));
  const triangles=triangulation.triangles,faces=[];
  for(let i=0;i<triangles.length;i+=3){
    let [a,b,c]=triangles.slice(i,i+3);const p=points[a],q=points[b],r=points[c];
    if(Math.abs((q[0]-p[0])*(r[1]-p[1])-(q[1]-p[1])*(r[0]-p[0]))<1e-12)continue;
    const centroid=[(p[0]+q[0]+r[0])/3,(p[1]+q[1]+r[1])/3];
    if(!inside(centroid,boundary))continue;
    if((q[0]-p[0])*(r[1]-p[1])-(q[1]-p[1])*(r[0]-p[0])<0)[b,c]=[c,b];
    faces.push([a,b,c]);
  }
  // Fail if filtering did not preserve every sampled outline edge exactly.
  const edges=new Map();
  for(const t of faces)for(let j=0;j<3;j++){const k=key(t[j],t[(j+1)%3]);edges.set(k,(edges.get(k)||0)+1);}
  for(let i=0;i<n;i++)if(edges.get(key(i,(i+1)%n))!==1)throw new Error(`Missing pattern boundary ${i}, count ${edges.get(key(i,(i+1)%n))}, points ${JSON.stringify([points[i],points[(i+1)%n]])}`);
  for(const [k,count] of edges)if(count===1){const [a,b]=k.split(':').map(Number);if(a>=n||b>=n||!([1,n-1].includes(Math.abs(a-b))))throw new Error('Unexpected pattern tear');}
  return {points,faces};
}
function materialPattern(source){
  const flat=source.map(p=>p.slice());
  // Nominal flat T-pose panels include the sleeve and body ease that folds in
  // an A pose. The folded 3D bind pose is not a fabric rest-length measurement.
  for(const [i,x,y] of [[0,-.30,0],[1,.30,0],[2,.30,.46],[3,.50,.42],[4,.50,.67],[5,.28,.645],
    [17,-.28,.645],[18,-.50,.67],[19,-.50,.42],[20,-.30,.46]])flat[i]=[x,y];
  return flat;
}
function cagePoint(point,source,target){
  const n=source.length,v=source.map(p=>[p[0]-point[0],p[1]-point[1]]),r=v.map(p=>Math.hypot(...p));
  const at=r.findIndex(length=>length<1e-9);if(at>=0)return target[at].slice();
  for(let i=0;i<n;i++)if(distance(point,source[i],source[(i+1)%n])<1e-9){
    const j=(i+1)%n,t=r[i]/Math.hypot(source[i][0]-source[j][0],source[i][1]-source[j][1]);
    return target[i].map((x,k)=>x+(target[j][k]-x)*t);
  }
  const tan=v.map((a,i)=>{const b=v[(i+1)%n];return (a[0]*b[1]-a[1]*b[0])/(r[i]*r[(i+1)%n]+a[0]*b[0]+a[1]*b[1]);});
  const w=r.map((length,i)=>(tan[(i+n-1)%n]+tan[i])/length),sum=w.reduce((a,b)=>a+b,0);
  return [0,1].map(k=>target.reduce((s,p,i)=>s+p[k]*w[i]/sum,0));
}

export function buildGarment({spacing=.021}={}){
  if(!Number.isFinite(spacing)||spacing<.012||spacing>.035)throw new Error('Invalid mesh spacing');
  const fp=OUTLINE.map(worldPoint),bp=OUTLINE.map(p=>p.slice(0,2));
  [67,80,87,90,86,78,65].forEach((y,i)=>{bp[i+8][1]=y;});
  const backPattern=bp.map(worldPoint),frontBoundary=[],backBoundary=[],types=[];
  for(let i=0;i<fp.length;i++){
    const j=(i+1)%fp.length,steps=Math.ceil(Math.hypot(fp[i][0]-fp[j][0],fp[i][1]-fp[j][1])/spacing);
    for(let s=0;s<steps;s++){
      const t=s/steps;
      frontBoundary.push(fp[i].map((n,k)=>n+(fp[j][k]-n)*t));
      backBoundary.push(backPattern[i].map((n,k)=>n+(backPattern[j][k]-n)*t));types.push(OUTLINE[i][2]);
    }
  }
  const front=panel(frontBoundary,spacing),back=panel(backBoundary,spacing),frontCount=front.points.length;
  const positions=[],uv=[],joints=[],weights=[];
  for(const [side,part,polygon] of [[1,front,fp],[-1,back,backPattern]])for(const original of part.points){
    const flat=materialPattern(polygon),[mx,my]=cagePoint(original,polygon,flat),abs=Math.abs(mx),sign=mx<0?-1:1;
    const theta=THREE.MathUtils.clamp(mx/.30,-1,1)*Math.PI/2;
    const blend=smooth(.22,.32,abs)*(1-(1-smooth(.30,.36,abs))*(1-smooth(.39,.48,my)));
    const upper=abs>=.28?.645+(abs-.28)*.025/.22:
      abs>=.2044?.64438+(abs-.2044)*.00062/(.28-.2044):
      .6873+(abs-.0889)*(.64438-.6873)/(.2044-.0889);
    const lower=.46-Math.max(0,abs-.30)*.04/.20;
    const sleeveAngle=THREE.MathUtils.clamp((my-(upper+lower)/2)/((upper-lower)/2),-1,1)*Math.PI/2;
    let x=.25*Math.sin(theta)*(1-blend)+sign*(.23+(abs-.28)*.20/.22)*blend;
    let y=my*(1-blend)+(.595+.080*Math.sin(sleeveAngle))*blend;
    const neck=smooth(.55,.64,my)*(1-smooth(.08,.13,abs));
    x*=1-.15*neck;if(side===1)y+=.008*neck*(1-smooth(.62,.66,my));
    const seamDistance=Math.min(...OUTLINE.flatMap((p,i)=>sewn(p)?[distance([mx,my],flat[i],flat[(i+1)%flat.length])]:[]));
    const round=smooth(0,.050,seamDistance);
    const z=Math.max(.0006,((.140*Math.cos(theta)*(1-blend)*round+.080*Math.cos(sleeveAngle)*blend)*(1-neck)+.078*neck*round));
    positions.push(x,y,side*z);
    const photoUV=[(original[0]/.0007+603)/SOURCE_SIZE[0],(1240-original[1]/.00058)/SOURCE_SIZE[1]];
    const fabricUV=[.5-original[0]/.84*.4,.28+(.69-original[1])/.69*.60];
    // The plain blue sleeve uses cloth albedo sampled from the body, rather
    // than carrying the flat photograph's immovable dark sleeve-fold shadows.
    uv.push(...(side===1?photoUV.map((v,k)=>v*(1-blend)+fabricUV[k]*blend):fabricUV));
    const w=smooth(.16,.29,Math.abs(x))*smooth(.39,.49,y);
    joints.push(0,w>0?(x>=0?1:2):0,0,0);weights.push(1-w,w,0,0);
  }
  const fi=front.faces.flat(),bi=back.faces.flatMap(([a,b,c])=>[c+frontCount,b+frontCount,a+frontCount]),si=[];
  for(let a=0;a<types.length;a++){
    if(['neck','hem','cuff'].includes(types[a]))continue;
    const b=(a+1)%types.length;si.push(a,a+frontCount,b,b,a+frontCount,b+frontCount);
  }
  const geometry=new THREE.BufferGeometry();
  geometry.setAttribute('position',new THREE.Float32BufferAttribute(positions,3));
  geometry.setAttribute('uv',new THREE.Float32BufferAttribute(uv,2));
  geometry.setAttribute('skinIndex',new THREE.Uint16BufferAttribute(joints,4));
  geometry.setAttribute('skinWeight',new THREE.Float32BufferAttribute(weights,4));
  geometry.setIndex([...fi,...bi,...si]);
  geometry.addGroup(0,fi.length,0);geometry.addGroup(fi.length,bi.length,1);geometry.addGroup(fi.length+bi.length,si.length,1);
  geometry.computeVertexNormals();geometry.computeBoundingBox();geometry.computeBoundingSphere();
  geometry.userData={templateId:TEMPLATE_ID,frontCount,spacing,sourceSize:SOURCE_SIZE,openings:['neck','hem','left_cuff','right_cuff']};
  return geometry;
}
