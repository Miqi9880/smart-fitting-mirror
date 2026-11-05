import * as THREE from 'three';
import {OrbitControls} from 'three/addons/controls/OrbitControls.js';
import {GLTFLoader} from 'three/addons/loaders/GLTFLoader.js';
import {BODY,setPose} from './rig.mjs';
import {MotionPlayer} from './motion-player.mjs';
import {ViewerLifecycle} from './viewer-lifecycle.mjs';
import {LiveView} from './live-view.mjs';

// The source is an already-lit photo, not calibrated albedo. Keep its colour
// and add only bounded matte shape cues instead of PBR highlights and ACES.
function photoClothMaterial(source){
  const material=new THREE.MeshBasicMaterial({map:source.map,color:source.color,side:THREE.DoubleSide,toneMapped:false});
  material.name=source.name+'_photo_colour';
  material.onBeforeCompile=shader=>{
    shader.vertexShader='varying vec3 vClothNormal;\n'+shader.vertexShader;
    shader.vertexShader=shader.vertexShader.replace('#include <begin_vertex>',
      '#include <begin_vertex>\nvClothNormal=normalize(normalMatrix*normal);');
    shader.fragmentShader='varying vec3 vClothNormal;\n'+shader.fragmentShader;
    shader.fragmentShader=shader.fragmentShader.replace('#include <color_fragment>',
      '#include <color_fragment>\nfloat clothShade=0.80+0.20*abs(dot(normalize(vClothNormal),normalize(vec3(0.15,0.25,1.0))));\ndiffuseColor.rgb*=clothShade;');
  };
  material.customProgramCacheKey=()=> 'photo-cloth-bounded-light-v1';return material;
}

const host=document.querySelector('#viewport'),status=document.querySelector('#load-status');
const scene=new THREE.Scene(),avatar=new THREE.Group(),body=new THREE.Group();
scene.background=new THREE.Color('#e9eeea');scene.add(avatar);avatar.add(body);
let renderer,proxy,bones,garment,motion,data,player,pose='rest';
const camera=new THREE.PerspectiveCamera(35,1,.02,30);
try{renderer=new THREE.WebGLRenderer({antialias:true,alpha:true});}
catch(error){host.dataset.sceneState='unsupported';status.textContent='无法启动 WebGL2，请更新浏览器／系统 WebView，或返回常规试衣。';throw error;}
renderer.setPixelRatio(Math.min(devicePixelRatio,2));renderer.shadowMap.enabled=true;
renderer.shadowMap.type=THREE.PCFSoftShadowMap;renderer.outputColorSpace=THREE.SRGBColorSpace;
renderer.toneMapping=THREE.ACESFilmicToneMapping;renderer.toneMappingExposure=1.25;host.append(renderer.domElement);
const orbit=new OrbitControls(camera,renderer.domElement);orbit.enableDamping=true;
orbit.minDistance=.85;orbit.maxDistance=5;orbit.minPolarAngle=.2;orbit.maxPolarAngle=Math.PI*.68;orbit.target.set(0,.93,0);
scene.add(new THREE.HemisphereLight('#ffffff','#90a292',2.1));
const key=new THREE.DirectionalLight('#fff8ed',3.3);key.position.set(-2,4,4);key.castShadow=true;key.shadow.mapSize.set(1024,1024);
Object.assign(key.shadow.camera,{left:-1.5,right:1.5,top:2,bottom:-.5});key.shadow.normalBias=.003;key.shadow.bias=-.0001;scene.add(key);
const rim=new THREE.DirectionalLight('#e1f0ff',1.9);rim.position.set(2,2,-3);scene.add(rim);
const ground=new THREE.Mesh(new THREE.CircleGeometry(20,80),new THREE.MeshStandardMaterial({color:'#e9eeea',roughness:1}));
ground.rotation.x=-Math.PI/2;ground.position.y=-.012;ground.receiveShadow=true;scene.add(ground);
const pedestal=new THREE.Mesh(new THREE.CylinderGeometry(.39,.4,.045,64),new THREE.MeshStandardMaterial({color:'#d3ddd5',roughness:.95}));
pedestal.position.y=.012;pedestal.receiveShadow=true;scene.add(pedestal);
const bodyMat=new THREE.MeshStandardMaterial({color:'#b4c0b8',roughness:.92});
const pantsMat=new THREE.MeshStandardMaterial({color:'#66796f',roughness:.96});
function ellipsoid(rx,ry,rz,x,y,z,material=bodyMat){
  const m=new THREE.Mesh(new THREE.SphereGeometry(1,32,24),material);m.scale.set(rx,ry,rz);m.position.set(x,y,z);
  m.castShadow=true;m.receiveShadow=true;body.add(m);return m;
}
for(const part of Object.values(BODY).filter(v=>v?.radii))ellipsoid(...part.radii,part.center[0],part.center[1]+BODY.offset,part.center[2],part===BODY.hip?pantsMat:bodyMat);
ellipsoid(.105,.135,.105,0,1.60,0);ellipsoid(.013,.018,.020,0,1.60,.098);
for(const sign of [-1,1]){
  ellipsoid(.083,.25,.083,sign*.092,.558,0,pantsMat);ellipsoid(.060,.23,.065,sign*.112,.255,0,pantsMat);
  ellipsoid(.065,.042,.135,sign*.112,.065,.05,pantsMat);
}
function capsule(radius){
  const group=new THREE.Group(),cylinder=new THREE.Mesh(new THREE.CylinderGeometry(radius,radius,1,20),bodyMat);
  const ends=[-1,1].map(()=>new THREE.Mesh(new THREE.SphereGeometry(radius,20,14),bodyMat));
  group.add(cylinder,...ends);for(const m of group.children){m.castShadow=true;m.receiveShadow=true;}body.add(group);return {group,cylinder,ends};
}
const segments=['left','right'].map(side=>({side,upper:capsule(BODY.upperRadius),lower:capsule(BODY.lowerRadius),hand:ellipsoid(.043,.07,.028,0,0,0)}));
const a=new THREE.Vector3(),b=new THREE.Vector3(),c=new THREE.Vector3(),up=new THREE.Vector3(0,1,0),delta=new THREE.Vector3();
function segmentBetween(part,p,q){
  delta.subVectors(q,p);const length=delta.length();part.group.position.copy(p).add(q).multiplyScalar(.5);
  part.group.quaternion.setFromUnitVectors(up,delta.normalize());part.cylinder.scale.y=length;
  part.ends[0].position.y=-length/2;part.ends[1].position.y=length/2;
}
function syncBody(){
  scene.updateMatrixWorld(true);
  for(const s of segments){
    const sign=s.side==='left'?1:-1,shoulder=bones.find(v=>v.name===`${s.side}_shoulder`),elbow=bones.find(v=>v.name===`${s.side}_elbow`);
    shoulder.getWorldPosition(a);body.worldToLocal(a);elbow.getWorldPosition(b);body.worldToLocal(b);
    c.set(sign*BODY.wrist[0],BODY.wrist[1],BODY.wrist[2]);elbow.localToWorld(c);body.worldToLocal(c);
    segmentBetween(s.upper,a,b);segmentBetween(s.lower,b,c);
    s.hand.position.copy(c).add(delta.subVectors(c,b).normalize().multiplyScalar(.045));s.hand.quaternion.copy(s.lower.group.quaternion);
  }
}
let activeView=null;
const views={front:[0,.18,1],threequarter:[.55,.22,1],side:[1,.16,.03],back:[0,.18,-1],detail:[.55,.18,1],top:[0,1,.21]};
function setView(view){
  activeView=view;
  const damping=orbit.enableDamping;orbit.enableDamping=false;orbit.update();orbit.target.set(0,view==='detail'?1.19:view==='top'?1.20:.93,0);
  const distance=view==='detail'?Math.max(1.65,1/(2*Math.tan(35*Math.PI/360)*camera.aspect)):view==='top'?Math.max(2.0,1/(2*Math.tan(35*Math.PI/360)*camera.aspect)):3.15;
  camera.position.copy(orbit.target).addScaledVector(new THREE.Vector3(...views[view]).normalize(),distance);orbit.update();orbit.enableDamping=damping;
  document.querySelectorAll('button[data-view]').forEach(b=>b.setAttribute('aria-pressed',String(b.dataset.view===view)));
}
setView('threequarter');orbit.addEventListener('start',()=>{activeView=null;document.querySelectorAll('button[data-view]').forEach(b=>b.setAttribute('aria-pressed','false'));});
document.querySelectorAll('button[data-view]').forEach(b=>b.addEventListener('click',()=>setView(b.dataset.view)));
function choosePose(value){
  pose=value;elapsed=0;document.querySelector('#animate').checked=false;player?.choose(value);
  document.querySelectorAll('button[data-pose]').forEach(b=>b.setAttribute('aria-pressed',String(b.dataset.pose===value)));
}
document.querySelectorAll('button[data-pose]').forEach(b=>b.addEventListener('click',()=>choosePose(b.dataset.pose)));
document.querySelector('#yaw').addEventListener('input',e=>{document.querySelector('#yaw-value').value=`${e.target.value}°`;if(pose!=='turn')choosePose('turn');});
document.querySelector('#animate').addEventListener('change',e=>{elapsed=0;if(player)player.loop=e.target.checked;});
document.querySelector('#body').addEventListener('change',e=>{body.visible=e.target.checked;});
document.querySelector('#wireframe').addEventListener('change',e=>{if(garment)for(const m of garment.material)m.wireframe=e.target.checked;});
const resize=new ResizeObserver(()=>{
  if(host.clientWidth<=0||host.clientHeight<=0)return;
  renderer.setSize(host.clientWidth,host.clientHeight);camera.aspect=host.clientWidth/host.clientHeight;camera.updateProjectionMatrix();
  if(activeView)setView(activeView);
});resize.observe(host);
let frames=0,lastStats=performance.now(),elapsed=0;
const live=new LiveView({host,scene,avatar,body,ground,pedestal,orbit,status,renderer});
window.garment3dLive=Object.freeze({
  begin:config=>live.begin(config),push:packet=>live.push(packet),active:()=>live.active,end:()=>{live.end();return true;},
});
const lifecycle=new ViewerLifecycle({setLoop:callback=>renderer.setAnimationLoop(callback),render:renderFrame,changed:state=>{
  host.dataset.playback=state;frames=0;lastStats=performance.now();
  if(state!=='running'&&live.active)live.end();
}});
lifecycle.setVisible(!document.hidden);lifecycle.setHostActive(window.garment3dHostActive!==false);
document.addEventListener('visibilitychange',()=>lifecycle.setVisible(!document.hidden));
window.addEventListener('garment3d-host-state',()=>lifecycle.setHostActive(window.garment3dHostActive!==false));
function disposeScene(){
  if(lifecycle.disposed)return;
  live.end();delete window.garment3dLive;
  lifecycle.dispose();resize.disconnect();orbit.dispose();
  scene.traverse(object=>{
    object.geometry?.dispose();
    for(const material of object.material?(Array.isArray(object.material)?object.material:[object.material]):[]){
      for(const value of Object.values(material))if(value?.isTexture){value.source?.data?.close?.();value.dispose();}
      material.dispose();
    }
  });
  renderer.dispose();renderer.forceContextLoss();host.dataset.sceneState='disposed';
}
window.addEventListener('garment3d-host-destroy',disposeScene);
window.addEventListener('pagehide',event=>{if(event.persisted)lifecycle.setVisible(false);else disposeScene();});
window.addEventListener('pageshow',event=>{if(event.persisted)lifecycle.setVisible(!document.hidden);});
lifecycle.start();
try{
  const [gltf,summary,manifest,buffer]=await Promise.all([
    new GLTFLoader().loadAsync('./assets/blue-tee.glb'),fetch('./assets/sample.json').then(r=>r.json()),
    fetch('./assets/motion.json').then(r=>r.json()),fetch('./assets/motion.bin').then(r=>r.arrayBuffer()),
  ]);
  if(lifecycle.disposed)throw new Error('Viewer already closed');
  const parts=[];gltf.scene.traverse(o=>{if(o.isSkinnedMesh)parts.push(o);});if(!parts.length)throw new Error('Missing rig');
  proxy=parts[0];bones=proxy.skeleton.bones;const geometry=proxy.geometry.clone(),indices=[],materials=[];geometry.clearGroups();
  for(const p of parts){geometry.addGroup(indices.length,p.geometry.index.count,materials.length);indices.push(...p.geometry.index.array);materials.push(photoClothMaterial(p.material));p.visible=false;}
  geometry.setIndex(indices);geometry.setAttribute('position',geometry.attributes.position.clone());
  // GLTF primitives have separate index buffers. Runtime constraints need the
  // whole sewn shell, and a rest-position copy that output deformation cannot mutate.
  proxy.geometry=geometry.clone();
  garment=new THREE.Mesh(geometry,materials);garment.castShadow=true;garment.receiveShadow=true;garment.frustumCulled=false;
  gltf.scene.add(garment);gltf.scene.position.y=BODY.offset;avatar.add(gltf.scene);
  motion=manifest;data=new Float32Array(buffer);
  if(motion.version!==2||motion.stride!==motion.vertices*3||motion.vertices!==geometry.attributes.position.count||buffer.byteLength!==motion.byte_length||data.length!==motion.frame_count*motion.stride)throw new Error('Motion topology mismatch');
  if(summary.geometry_sha256!==motion.geometry_sha256)throw new Error('Stale motion data; rebuild model and motions together');
  player=new MotionPlayer(motion.clips);
  live.ready(proxy,garment,data.slice(motion.clips.rest.start*motion.stride,(motion.clips.rest.start+1)*motion.stride));
  host.dataset.sceneState='ready';
  document.querySelector('#controls').disabled=false;status.textContent='合成人台 · 预计算姿态约束修形';
  document.querySelector('#mesh-info').textContent=`${summary.vertices.toLocaleString()} 顶点 · ${summary.triangles.toLocaleString()} 三角面 · 站立／抬臂／前伸固定动作复核`;
}catch(error){if(!lifecycle.disposed){console.error(error);host.dataset.sceneState='error';status.textContent='动作数据加载失败，请重新打包模型与动作资源。';}}
function renderFrame(now,seconds){
  elapsed+=seconds;
  const liveFrame=live.render(now);
  if(!liveFrame&&garment&&motion){
    const current=player.advance(seconds),isTurn=pose==='turn',clip=motion.clips[current.pose],loop=document.querySelector('#animate').checked,frame=current.frame;
    host.dataset.activePose=current.pose;host.dataset.frame=frame.toFixed(3);
    const low=Math.floor(frame),high=Math.min(clip.count-1,low+1),mix=frame-low,output=garment.geometry.attributes.position.array;
    const offset0=(clip.start+low)*motion.stride,offset1=(clip.start+high)*motion.stride;
    for(let i=0;i<output.length;i++)output[i]=data[offset0+i]*(1-mix)+data[offset1+i]*mix;
    garment.geometry.attributes.position.needsUpdate=true;garment.geometry.computeVertexNormals();
    const t=Math.min(1,frame/60),phase=t*t*(3-2*t);setPose(bones,current.pose,phase);
    const yaw=Number(document.querySelector('#yaw').value)*Math.PI/180;
    const targetYaw=isTurn&&current.pose==='rest'?(loop?Math.sin(elapsed*.6)*yaw:yaw):0;
    avatar.rotation.y+=(targetYaw-avatar.rotation.y)*(1-Math.exp(-seconds*5));
    syncBody();const message=isTurn?`合成人台转身 ${Math.round(avatar.rotation.y*180/Math.PI)}° · 背面近似`:'合成人台 · 预计算姿态约束修形';
    if(status.textContent!==message)status.textContent=message;
  }
  if(!liveFrame)orbit.update();renderer.render(scene,liveFrame?live.camera:camera);frames++;
  if(now-lastStats>1200){document.querySelector('#fps').textContent=`${document.documentElement.classList.contains('android-host')?'当前渲染':'桌面'} ${Math.round(frames*1000/(now-lastStats))} FPS`;frames=0;lastStats=now;}
}
