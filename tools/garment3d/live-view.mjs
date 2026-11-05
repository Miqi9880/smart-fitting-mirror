import * as THREE from 'three';
import {LiveFrameOrder,LiveGarmentDeformer,solveLivePose} from './live-driver.mjs';
import {BODY} from './rig.mjs';

/** Local native-to-page frames only. No browser camera or network requests. */
export class LiveView {
  constructor({host,scene,avatar,body,ground,pedestal,orbit,status,renderer}){
    Object.assign(this,{host,scene,avatar,body,ground,pedestal,orbit,status,renderer});
    this.camera=new THREE.OrthographicCamera(-1,1,1,-1,.1,10000);this.camera.position.z=3000;
    this.background=document.createElement('div');this.background.className='live-background';host.prepend(this.background);
    this.keypoints=document.createElement('canvas');this.keypoints.className='live-keypoints';host.append(this.keypoints);
    this.order=new LiveFrameOrder();this.epoch=0;this.active=false;
    const material=new THREE.MeshBasicMaterial({colorWrite:false,depthWrite:true});
    // Only depth is drawn: the real neck/chest remains the camera background.
    // Previously the hidden mannequin also removed all torso/neck occlusion.
    this.torsoDepth=new THREE.Group();this.torsoDepth.visible=false;avatar.add(this.torsoDepth);
    for(const part of [BODY.torso,BODY.clavicle,BODY.neck]){
      const mesh=new THREE.Mesh(new THREE.SphereGeometry(1,24,18),material);
      mesh.scale.set(...part.radii);mesh.position.set(part.center[0],part.center[1]+BODY.offset,part.center[2]);
      mesh.renderOrder=-1;this.torsoDepth.add(mesh);
    }
    this.occluders=new THREE.Group();this.occluders.visible=false;scene.add(this.occluders);
    this.arms=['left','right'].map(side=>{
      const cylinder=new THREE.Mesh(new THREE.CylinderGeometry(1,1,1,16),material);
      const ends=[0,1].map(()=>new THREE.Mesh(new THREE.SphereGeometry(1,16,12),material));
      for(const mesh of [cylinder,...ends]){mesh.renderOrder=-1;this.occluders.add(mesh);}
      return {side,cylinder,ends};
    });
  }
  ready(proxy,garment,standing){this.proxy=proxy;this.garment=garment;this.standing=standing;}
  begin(config){
    if(!this.proxy||!['camera','replay'].includes(config?.source)||typeof config.session!=='string'||!config.session.length||config.session.length>80)return false;
    let deformer;try{deformer=new LiveGarmentDeformer(this.proxy,this.standing);}catch{return false;}
    this.end();this.active=true;this.order.begin(config.session,config.source);this.source=config.source;
    this.lastArrival=performance.now();this.started=this.lastArrival;this.hasFrame=false;this.lastSolve=null;this.deformer=deformer;
    this.avatar.visible=false;this.avatar.matrixAutoUpdate=false;this.body.visible=false;
    this.ground.visible=false;this.pedestal.visible=false;this.scene.background=null;this.orbit.enabled=false;
    this.renderer.shadowMap.enabled=false;this.renderer.setPixelRatio(1);
    document.documentElement.classList.add('live-mode');this.host.dataset.driverMode=config.source;
    this.status.textContent=config.source==='replay'?'姿态回放（非真人）· 等待输入':'本地真人驱动 · 等待摄像头画面';
    return true;
  }
  push(packet){
    if(!this.active||this.decoding||!this.order.accepts(packet))return false;
    if(typeof packet.jpeg!=='string'||packet.jpeg.length>600000||!/^[A-Za-z0-9+/=]+$/.test(packet.jpeg))return false;
    const epoch=this.epoch,image=new Image();this.decoding=image;
    let bytes;try{bytes=Uint8Array.from(atob(packet.jpeg),c=>c.charCodeAt(0));}catch{this.decoding=null;return false;}
    const url=URL.createObjectURL(new Blob([bytes],{type:'image/jpeg'}));this.pendingUrl=url;
    const metadata={...packet};delete metadata.jpeg;
    image.onload=()=>{
      URL.revokeObjectURL(url);if(this.pendingUrl===url)this.pendingUrl=null;
      if(epoch!==this.epoch||!this.active){image.src='';return;}
      this.decoding=null;
      if(image.naturalWidth!==metadata.width||image.naturalHeight!==metadata.height){image.src='';return;}
      this.lastArrival=performance.now();
      this.hasFrame=true;
      const old=this.background.firstElementChild;this.background.replaceChildren(image);if(old)old.src='';
      image.style.transform=metadata.mirrored?'scaleX(-1)':'';
      this.packet=metadata;this.pending=true;
    };
    image.onerror=()=>{image.onerror=null;image.onload=null;URL.revokeObjectURL(url);if(this.pendingUrl===url)this.pendingUrl=null;if(epoch===this.epoch)this.decoding=null;image.src='';};
    image.src=url;return true;
  }
  clearImage(){
    this.epoch++;if(this.decoding){this.decoding.onload=null;this.decoding.onerror=null;this.decoding.src='';}this.decoding=null;
    if(this.pendingUrl)URL.revokeObjectURL(this.pendingUrl);this.pendingUrl=null;
    for(const image of this.background.children)image.src='';this.background.replaceChildren();
    this.packet=null;this.pending=false;this.avatar.visible=false;this.occluders.visible=false;this.host.dataset.tracked='false';
    this.torsoDepth.visible=false;
    this.keypoints.getContext('2d').clearRect(0,0,this.keypoints.width,this.keypoints.height);
  }
  end(){
    this.clearImage();this.active=false;this.order.end();this.deformer=null;
    this.avatar.matrixAutoUpdate=true;this.avatar.position.set(0,0,0);this.avatar.rotation.set(0,0,0);this.avatar.scale.set(1,1,1);this.avatar.updateMatrix();
    this.avatar.visible=true;this.body.visible=document.querySelector('#body').checked;
    this.ground.visible=true;this.pedestal.visible=true;this.scene.background=new THREE.Color('#e9eeea');this.orbit.enabled=true;
    this.renderer.shadowMap.enabled=true;this.renderer.setPixelRatio(Math.min(devicePixelRatio,2));
    document.documentElement.classList.remove('live-mode');this.host.dataset.driverMode='sample';delete this.host.dataset.liveSequence;
  }
  render(now){
    if(!this.active)return false;
    if(now-this.lastArrival>(this.hasFrame?1200:15000)){
      this.clearImage();this.status.textContent='画面已中断，上衣与旧画面已清除；请停止后重试';return true;
    }
    if(!this.packet)return true;
    const p=this.packet,scale=Math.min(this.host.clientWidth/p.width,this.host.clientHeight/p.height);
    this.camera.left=-this.host.clientWidth/(2*scale);this.camera.right=-this.camera.left;
    this.camera.top=this.host.clientHeight/(2*scale);this.camera.bottom=-this.camera.top;this.camera.updateProjectionMatrix();
    if(!this.pending)return true;this.pending=false;
    this.drawKeypoints(p);
    const solution=solveLivePose(p);this.host.dataset.liveSequence=String(p.sequence);this.host.dataset.tracked=String(solution.valid);
    this.host.dataset.activePose='live';this.host.dataset.frame=String(p.sequence);
    const prefix=p.source==='replay'?'姿态回放（非真人）':'本地真人驱动（实验）';
    if(!solution.valid){this.avatar.visible=false;this.occluders.visible=false;this.torsoDepth.visible=false;this.status.textContent=`${prefix} · ${solution.reason}`;this.deformer.fresh=true;return true;}
    this.avatar.matrix.copy(solution.matrix);
    if(p.mirrored)this.avatar.matrix.premultiply(new THREE.Matrix4().makeScale(-1,1,1));
    this.avatar.matrixWorldNeedsUpdate=true;this.avatar.visible=true;
    this.avatar.updateMatrixWorld(true);this.torsoDepth.visible=true;
    let output;
    try{output=this.deformer.update(solution,Math.min(.16,(now-(this.lastSolve??now-100))/1000));this.lastSolve=now;}
    catch(error){console.error('3D pose solve failed');this.end();this.status.textContent='3D 驱动暂不可用，已清除画面，请停止后重试';return true;}
    const positions=this.garment.geometry.attributes.position;
    positions.array.set(output);positions.needsUpdate=true;this.garment.geometry.computeVertexNormals();
    this.host.dataset.mirrored=String(p.mirrored);
    this.host.dataset.liveHipX=String(solution.hip.x*(p.mirrored?-1:1));
    this.host.dataset.leftArm=String(solution.arms.left.shoulder.z);
    this.host.dataset.rightArm=String(solution.arms.right.shoulder.z);
    this.occluders.visible=true;const up=new THREE.Vector3(0,1,0);
    for(const arm of this.arms){
      const a=solution.points[`${arm.side}_elbow`].clone(),b=solution.points[`${arm.side}_wrist`].clone();
      a.z=solution.armDepth[arm.side].elbow;b.z=solution.armDepth[arm.side].wrist;
      if(p.mirrored){a.x=-a.x;b.x=-b.x;}
      const radius=solution.projectedWidth*.065,d=b.clone().sub(a);
      arm.cylinder.position.copy(a).add(b).multiplyScalar(.5);arm.cylinder.quaternion.setFromUnitVectors(up,d.clone().normalize());
      arm.cylinder.scale.set(radius,d.length(),radius);arm.ends[0].position.copy(a);arm.ends[1].position.copy(b);
      for(const end of arm.ends)end.scale.setScalar(radius);
    }
    this.status.textContent=`${prefix} · 上衣随人体姿态变形 · 手臂遮挡为近似`;
    return true;
  }
  drawKeypoints(p){
    const canvas=this.keypoints;canvas.width=p.width;canvas.height=p.height;const ctx=canvas.getContext('2d');
    this.host.dataset.keypoints=String(p.showKeypoints===true);
    if(p.showKeypoints!==true)return;
    const points={};
    for(const [name,v] of Object.entries(p.points))if(Array.isArray(v)&&v.length===4&&v.every(Number.isFinite)&&v[3]>=.65)
      points[name]=[p.mirrored?(1-v[0])*p.width:v[0]*p.width,v[1]*p.height];
    ctx.strokeStyle='#ffd84e';ctx.fillStyle='#ffd84e';ctx.lineWidth=2;
    for(const pair of [['left_shoulder','right_shoulder'],['left_hip','right_hip'],...['left','right'].flatMap(side=>[[`${side}_shoulder`,`${side}_hip`],[`${side}_shoulder`,`${side}_elbow`],[`${side}_elbow`,`${side}_wrist`]])]){
      const a=points[pair[0]],b=points[pair[1]];if(!a||!b)continue;ctx.beginPath();ctx.moveTo(...a);ctx.lineTo(...b);ctx.stroke();
    }
    for(const value of Object.values(points)){ctx.beginPath();ctx.arc(...value,3,0,Math.PI*2);ctx.fill();}
  }
}
