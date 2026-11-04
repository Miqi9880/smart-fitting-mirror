import * as THREE from 'three';
import {OrbitControls} from 'three/addons/controls/OrbitControls.js';
import {GLTFLoader} from 'three/addons/loaders/GLTFLoader.js';
import {setPose} from './template.mjs';

const host=document.querySelector('#viewport'),status=document.querySelector('#load-status');
let renderer,mesh,bones,pose='rest',cameraView='threequarter';
const garmentMeshes=[];
const body=new THREE.Group(), scene=new THREE.Scene();
scene.background=new THREE.Color('#e9eeea');
const camera=new THREE.PerspectiveCamera(35,1,.02,30);
try {renderer=new THREE.WebGLRenderer({antialias:true,alpha:false});}
catch(error){status.textContent='无法启动 WebGL，请使用支持 WebGL 的浏览器。';throw error;}
renderer.setPixelRatio(Math.min(devicePixelRatio,2));renderer.shadowMap.enabled=true;
renderer.shadowMap.type=THREE.PCFSoftShadowMap;renderer.outputColorSpace=THREE.SRGBColorSpace;
renderer.toneMapping=THREE.ACESFilmicToneMapping;renderer.toneMappingExposure=1.25;
host.append(renderer.domElement);
const orbit=new OrbitControls(camera,renderer.domElement);orbit.enableDamping=true;
orbit.minDistance=.85;orbit.maxDistance=5;orbit.minPolarAngle=.20;orbit.maxPolarAngle=Math.PI*.68;
orbit.target.set(0,.93,0);
scene.add(new THREE.HemisphereLight('#ffffff','#90a292',2.1));
const key=new THREE.DirectionalLight('#fff8ed',3.3);key.position.set(-2,4,4);key.castShadow=true;
key.shadow.mapSize.set(1024,1024);key.shadow.camera.left=-1.5;key.shadow.camera.right=1.5;key.shadow.camera.top=2;key.shadow.camera.bottom=-.5;key.shadow.normalBias=.012;scene.add(key);
const rim=new THREE.DirectionalLight('#e1f0ff',1.9);rim.position.set(2,2,-3);scene.add(rim);
const ground=new THREE.Mesh(new THREE.CircleGeometry(20,80),new THREE.MeshStandardMaterial({color:'#e9eeea',roughness:1}));
ground.rotation.x=-Math.PI/2;ground.position.y=-.012;ground.receiveShadow=true;scene.add(ground);
const pedestal=new THREE.Mesh(new THREE.CylinderGeometry(.39,.40,.045,64),new THREE.MeshStandardMaterial({color:'#d3ddd5',roughness:.95}));
pedestal.position.y=.012;pedestal.receiveShadow=true;scene.add(pedestal);
scene.add(body);

const bodyMat=new THREE.MeshStandardMaterial({color:'#b4c0b8',roughness:.92});
const pantsMat=new THREE.MeshStandardMaterial({color:'#66796f',roughness:.96});
function ellipsoid(rx,ry,rz,x,y,z,material=bodyMat){
  const m=new THREE.Mesh(new THREE.SphereGeometry(1,32,24),material);m.scale.set(rx,ry,rz);m.position.set(x,y,z);m.castShadow=true;m.receiveShadow=true;body.add(m);return m;
}
// Neutral synthetic mannequin. This is not a scanned or measured human body.
ellipsoid(.19,.275,.110,0,1.09,0);
ellipsoid(.105,.135,.105,0,1.60,0);
ellipsoid(.060,.084,.065,0,1.435,0);
ellipsoid(.18,.11,.12,0,.80,0,pantsMat);
for(const sign of [-1,1]){
  ellipsoid(.083,.25,.083,sign*.092,.558,0,pantsMat);
  ellipsoid(.060,.23,.065,sign*.112,.255,0,pantsMat);
  ellipsoid(.065,.042,.135,sign*.112,.065,.05,pantsMat);
}
const segments=[];
for(const side of ['left','right']){
  const sign=side==='left'?1:-1;
  const upper=new THREE.Mesh(new THREE.CapsuleGeometry(.043,1,8,16),bodyMat);
  const lower=new THREE.Mesh(new THREE.CapsuleGeometry(.042,1,8,16),bodyMat);
  upper.castShadow=lower.castShadow=true;body.add(upper,lower);
  const hand=ellipsoid(.043,.07,.028,0,0,0);
  segments.push({side,sign,upper,lower,hand});
}
const a=new THREE.Vector3(),b=new THREE.Vector3(),c=new THREE.Vector3(),up=new THREE.Vector3(0,1,0);
function segmentBetween(object,p,q,radius){
  const delta=new THREE.Vector3().subVectors(q,p),length=delta.length();
  object.position.copy(p).add(q).multiplyScalar(.5);object.scale.set(1,Math.max(.01,length/(1+2*radius)),1);object.quaternion.setFromUnitVectors(up,delta.normalize());
}
function syncBody(){
  mesh.updateMatrixWorld(true);
  for(const s of segments){
    const shoulder=bones.find(v=>v.name===`${s.side}_shoulder`),elbow=bones.find(v=>v.name===`${s.side}_elbow`);
    shoulder.getWorldPosition(a);elbow.getWorldPosition(b);
    c.set(s.sign*.035,-.255,.018);elbow.localToWorld(c);
    segmentBetween(s.upper,a,b,.043);segmentBetween(s.lower,b,c,.042);
    s.hand.position.copy(c).add(new THREE.Vector3(0,-.045,.005));s.hand.quaternion.copy(s.lower.quaternion);
  }
}
const viewDirections={front:[0,.18,1],threequarter:[.55,.22,1],side:[1,.16,.03],back:[0,.18,-1]};
function setView(view){
  cameraView=view;
  // Drain drag/pan inertia through the public API before applying a preset.
  const damping=orbit.enableDamping;orbit.enableDamping=false;orbit.update();
  orbit.target.set(0,.93,0);
  const dir=new THREE.Vector3(...viewDirections[view]).normalize();camera.position.copy(orbit.target).addScaledVector(dir,3.15);orbit.update();
  orbit.enableDamping=damping;
  document.querySelectorAll('[data-view]').forEach(button=>button.setAttribute('aria-pressed',String(button.dataset.view===view)));
  if(mesh)status.textContent=view==='back'?'背面近似补全 · 尚无商品背面参考':view==='side'?'侧面体积为近似 · 未核对实物尺寸':'3D 模型已加载 · 合成人台';
}
setView(cameraView);
orbit.addEventListener('start',()=>document.querySelectorAll('[data-view]').forEach(button=>button.setAttribute('aria-pressed','false')));
document.querySelectorAll('[data-view]').forEach(button=>button.addEventListener('click',()=>setView(button.dataset.view)));
document.querySelectorAll('[data-pose]').forEach(button=>button.addEventListener('click',()=>{
  pose=button.dataset.pose;document.querySelector('#animate').checked=false;
  document.querySelectorAll('[data-pose]').forEach(b=>b.setAttribute('aria-pressed',String(b===button)));
}));
document.querySelector('#body').addEventListener('change',e=>{body.visible=e.target.checked;});
document.querySelector('#wireframe').addEventListener('change',e=>{for(const part of garmentMeshes)for(const mat of [part.material].flat())mat.wireframe=e.target.checked;});
document.querySelector('#animate').addEventListener('change',e=>{
  document.querySelectorAll('[data-pose]').forEach(b=>b.setAttribute('aria-pressed',String(!e.target.checked&&b.dataset.pose===pose)));
});
new ResizeObserver(()=>{const w=host.clientWidth,h=host.clientHeight;renderer.setSize(w,h);camera.aspect=w/h;camera.updateProjectionMatrix();}).observe(host);
try{
  const [gltf,summary]=await Promise.all([new GLTFLoader().loadAsync('/assets/blue-tee.glb'),fetch('/assets/sample.json').then(r=>{if(!r.ok)throw new Error('Missing summary');return r.json();})]);
  gltf.scene.traverse(o=>{if(o.isSkinnedMesh)garmentMeshes.push(o);});
  if(!garmentMeshes.length)throw new Error('No skinned garment found');
  mesh=garmentMeshes[0];
  // Mesh is loaded directly from the exported GLB, rather than re-created in the viewer.
  gltf.scene.position.y=.78;scene.add(gltf.scene);bones=mesh.skeleton.bones;
  for(const part of garmentMeshes){part.castShadow=true;part.receiveShadow=true;part.frustumCulled=false;}
  document.querySelector('#controls').disabled=false;
  document.querySelector('#mesh-info').textContent=`${summary.vertices.toLocaleString()} 个顶点 · ${summary.triangles.toLocaleString()} 个三角面 · GLB ${(summary.glb_bytes/1024/1024).toFixed(2)} MB`;
  status.textContent='3D 模型已加载 · 合成人台';
}catch(error){console.error(error);status.textContent='模型加载失败，请先运行 npm run build，再检查本地服务。';}
let frames=0,lastStats=performance.now(),start=lastStats;
renderer.setAnimationLoop(now=>{
  if(mesh){const anim=document.querySelector('#animate').checked;
    setPose(bones,anim?'raise':pose,anim?(1-Math.cos((now-start)*.0012))*.5:1);syncBody();}
  orbit.update();renderer.render(scene,camera);frames++;
  if(now-lastStats>1200){document.querySelector('#fps').textContent=`桌面 ${Math.round(frames*1000/(now-lastStats))} FPS`;frames=0;lastStats=now;}
});
