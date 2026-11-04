import {poseGeometry,bodyColliders} from './rig.mjs';

const edgeKey=(a,b)=>a<b?`${a}:${b}`:`${b}:${a}`;
const clamp=(x,a,b)=>Math.max(a,Math.min(b,x));

// Project a material sample, not only a mesh vertex. A triangle can cut
// through an arm even when all three of its vertices are outside the arm.
function projectContact(px,py,pz,c,sign){
  if(c.type==='ellipsoid'){
    const [cx,cy,cz]=c.center,[rx,ry,rz]=c.radii,dx=px-cx,dy=py-cy,dz=pz-cz;
    if(Math.abs(dx)>rx||Math.abs(dy)>ry||Math.abs(dz)>rz)return null;
    const q=Math.sqrt((dx/rx)**2+(dy/ry)**2+(dz/rz)**2);
    if(q>=1)return null;
    return q<1e-10?[0,0,cz+sign*rz-pz]:[dx/q-dx,dy/q-dy,dz/q-dz];
  }
  const [ax,ay,az]=c.a,[bx,by,bz]=c.b,r=c.radius;
  if(px<Math.min(ax,bx)-r||px>Math.max(ax,bx)+r||py<Math.min(ay,by)-r||py>Math.max(ay,by)+r||pz<Math.min(az,bz)-r||pz>Math.max(az,bz)+r)return null;
  const vx=bx-ax,vy=by-ay,vz=bz-az,t=clamp(((px-ax)*vx+(py-ay)*vy+(pz-az)*vz)/(vx*vx+vy*vy+vz*vz),0,1);
  const dx=px-ax-vx*t,dy=py-ay-vy*t,dz=pz-az-vz*t,d=Math.sqrt(dx*dx+dy*dy+dz*dz);
  if(d>=r)return null;
  return d<1e-10?[0,0,sign*r]:[dx*(r/d-1),dy*(r/d-1),dz*(r/d-1)];
}

function constraintSet(pairs,rest,compliance){
  const indices=new Uint32Array(pairs.flat()),lengths=new Float64Array(pairs.length);
  for(let k=0;k<pairs.length;k++){
    const a=pairs[k][0]*3,b=pairs[k][1]*3;
    lengths[k]=Math.hypot(rest[a]-rest[b],rest[a+1]-rest[b+1],rest[a+2]-rest[b+2]);
  }
  return {indices,lengths,lambda:new Float64Array(pairs.length),compliance};
}

export function penetration(point,collider){
  if(collider.type==='ellipsoid'){
    const q=Math.hypot(...point.map((v,j)=>(v-collider.center[j])/collider.radii[j]));
    return Math.max(0,1-q)*Math.min(...collider.radii);
  }
  const d=collider.b.map((v,j)=>v-collider.a[j]);
  const t=clamp(d.reduce((sum,v,j)=>sum+(point[j]-collider.a[j])*v,0)/d.reduce((sum,v)=>sum+v*v,0),0,1);
  return Math.max(0,collider.radius-Math.hypot(...point.map((v,j)=>v-collider.a[j]-d[j]*t)));
}

/** Pose-guided correction: XPBD edge/bending/attachment constraints and
 * mannequin collision proxies. No gravity, self-collision, or measured fabric calibration.
 * Vertices move independently of the skinning targets, including the free hem.
 */
export class ClothSimulation {
  constructor(geometry,{iterations=128,follow=.020}={}){
    this.rest=Float64Array.from(geometry.attributes.position.array);
    this.positions=this.rest.slice();this.previous=this.rest.slice();
    this.mass=new Float64Array(this.rest.length/3).fill(1);
    this.iterations=iterations;this.follow=follow;this.colliders=[];
    const map=new Map(),indices=geometry.index.array;
    for(let i=0;i<indices.length;i+=3)for(let j=0;j<3;j++){
      const a=indices[i+j],b=indices[i+(j+1)%3],other=indices[i+(j+2)%3],key=edgeKey(a,b);
      if(!map.has(key))map.set(key,{a,b,opposite:[]});map.get(key).opposite.push(other);
    }
    const metric=this.rest;
    this.edges=constraintSet(Array.from(map.values(),e=>[e.a,e.b]),metric,1e-8);this.edges.tensionOnly=true;
    // Body and arm junction samples. The lower body is already far from the
    // mannequin; limiting this set keeps the offline bake small.
    const nearJunction=vertices=>vertices.some(v=>this.rest[v*3+1]>.35&&Math.abs(this.rest[v*3])>.1);
    this.surfaceSamples=Array.from(map.values(),e=>[e.a,e.b]).filter(nearJunction);
    for(let i=0;i<indices.length;i+=3){const face=Array.from(indices.slice(i,i+3));if(nearJunction(face))this.surfaceSamples.push(face);}
    this.bending=constraintSet(Array.from(map.values()).filter(e=>e.opposite.length===2).map(e=>e.opposite),this.rest,1e-3);
    const graph=new Map();
    for(const e of map.values())if(e.opposite.length===1)for(const [a,b] of [[e.a,e.b],[e.b,e.a]]){
      if(!graph.has(a))graph.set(a,[]);graph.get(a).push(b);
    }
    const visited=new Set(),loops=[];
    for(const v of graph.keys()){
      if(visited.has(v))continue;const pending=[v],loop=[];
      while(pending.length){const n=pending.pop();if(visited.has(n))continue;visited.add(n);loop.push(n);pending.push(...graph.get(n));}
      loops.push(loop);
    }
    if(loops.length!==4)throw new Error('Cloth requires four intact garment openings');
    this.cuffs=[];this.neck=[];
    for(const loop of loops){
      const center=[0,0,0];for(const v of loop)for(let j=0;j<3;j++)center[j]+=this.rest[v*3+j]/loop.length;
      if(Math.abs(center[0])>.2){this.cuffs.push(...loop);for(const v of loop)this.mass[v]=0;}
      else if(center[1]>.5){this.neck.push(...loop);for(const v of loop)this.mass[v]=0;}
    }
    if(!this.neck.length||!this.cuffs.length)throw new Error('Missing neck or cuff attachments');
  }

  reset(){this.positions.set(this.rest);this.previous.set(this.rest);}

  step(mesh,dt=1/90){
    if(!Number.isFinite(dt)||dt<=0||dt>1/30)throw new Error('Invalid cloth timestep');
    const targets=poseGeometry(mesh),x=this.positions,previous=this.previous;this.targets=targets;
    this.colliders=bodyColliders(mesh);
    for(let v=0;v<this.mass.length;v++)for(let j=0;j<3;j++){
      const i=v*3+j,old=x[i];
      x[i]=this.mass[v]===0?targets[i]:old+(old-previous[i])*.30+(targets[i]-old)*this.follow;
      previous[i]=old;
    }
    this.edges.lambda.fill(0);this.bending.lambda.fill(0);
    for(let pass=0;pass<this.iterations;pass++){
      this.solveDistances(this.edges,dt,pass%2===1);
      this.solveDistances(this.bending,dt,pass%2===1);
      this.solveCollisions();
      if(pass%4===3)this.solveSurfaceCollisions();
    }
    // Resolve intersections with the union of overlapping collision proxies.
    this.solveCollisions();this.solveSurfaceCollisions();this.solveCollisions();
    return x;
  }

  solveDistances(set,dt,reverse){
    const x=this.positions,{indices,lengths,lambda}=set,alpha=set.compliance/(dt*dt),n=lengths.length;
    for(let k=0;k<n;k++){
      const index=reverse?n-1-k:k,a=indices[index*2],b=indices[index*2+1],ia=a*3,ib=b*3;
      const dx=x[ia]-x[ib],dy=x[ia+1]-x[ib+1],dz=x[ia+2]-x[ib+2],length=Math.sqrt(dx*dx+dy*dy+dz*dz),wa=this.mass[a],wb=this.mass[b];
      if(length<1e-10||wa+wb===0)continue;
      let change=(-(length-lengths[index])-alpha*lambda[index])/(wa+wb+alpha);
      if(set.tensionOnly&&lengths[index]>.008){const next=Math.min(0,lambda[index]+change);change=next-lambda[index];lambda[index]=next;}
      else lambda[index]+=change;
      const s=change/length;
      x[ia]+=wa*s*dx;x[ia+1]+=wa*s*dy;x[ia+2]+=wa*s*dz;
      x[ib]-=wb*s*dx;x[ib+1]-=wb*s*dy;x[ib+2]-=wb*s*dz;
    }
  }

  solveCollisions(){
    const x=this.positions,previous=this.previous;
    for(let v=0;v<this.mass.length;v++){
      if(!this.mass[v])continue;const i=v*3,ox=x[i],oy=x[i+1],oz=x[i+2];
      for(let cIndex=0;cIndex<this.colliders.length;cIndex++){
        const delta=projectContact(x[i],x[i+1],x[i+2],this.colliders[cIndex],this.rest[i+2]>=0?1:-1);
        if(delta){x[i]+=delta[0];x[i+1]+=delta[1];x[i+2]+=delta[2];}
      }
      previous[i]+=(x[i]-ox)*.85;previous[i+1]+=(x[i+1]-oy)*.85;previous[i+2]+=(x[i+2]-oz)*.85;
    }
  }

  solveSurfaceCollisions(){
    const x=this.positions;
    for(const vertices of this.surfaceSamples){
      const n=vertices.length,weight=1/n,denominator=vertices.reduce((sum,v)=>sum+this.mass[v]*weight*weight,0);
      if(denominator===0)continue;
      for(const collider of this.colliders){
        let px=0,py=0,pz=0,restZ=0;
        for(const v of vertices){px+=x[v*3]*weight;py+=x[v*3+1]*weight;pz+=x[v*3+2]*weight;restZ+=this.rest[v*3+2];}
        const delta=projectContact(px,py,pz,collider,restZ>=0?1:-1);if(!delta)continue;
        for(const v of vertices){
          const scale=this.mass[v]*weight/denominator;
          for(let j=0;j<3;j++){const change=delta[j]*scale;x[v*3+j]+=change;this.previous[v*3+j]+=change*.85;}
        }
      }
    }
  }
}
