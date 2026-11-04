import {poseGeometry,bodyColliders} from './rig.mjs';

const edgeKey=(a,b)=>a<b?`${a}:${b}`:`${b}:${a}`;
const clamp=(x,a,b)=>Math.max(a,Math.min(b,x));

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
  constructor(geometry,{iterations=64,cuffCompliance=2e-6,follow=.02}={}){
    this.rest=Float64Array.from(geometry.attributes.position.array);
    this.positions=this.rest.slice();this.previous=this.rest.slice();
    this.mass=new Float64Array(this.rest.length/3).fill(1);
    this.iterations=iterations;this.cuffCompliance=cuffCompliance;this.follow=follow;this.colliders=[];
    const map=new Map(),indices=geometry.index.array;
    for(let i=0;i<indices.length;i+=3)for(let j=0;j<3;j++){
      const a=indices[i+j],b=indices[i+(j+1)%3],other=indices[i+(j+2)%3],key=edgeKey(a,b);
      if(!map.has(key))map.set(key,{a,b,opposite:[]});map.get(key).opposite.push(other);
    }
    const metric=this.rest;
    this.edges=constraintSet(Array.from(map.values(),e=>[e.a,e.b]),metric,1e-8);this.edges.tensionOnly=true;
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
      if(Math.abs(center[0])>.2)this.cuffs.push(...loop);
      else if(center[1]>.5){this.neck.push(...loop);for(const v of loop)this.mass[v]=0;}
    }
    this.cuffLambda=new Float64Array(this.cuffs.length*3);
    if(!this.neck.length||!this.cuffs.length)throw new Error('Missing neck or cuff attachments');
  }

  reset(){this.positions.set(this.rest);this.previous.set(this.rest);}

  step(mesh,dt=1/90){
    if(!Number.isFinite(dt)||dt<=0||dt>1/30)throw new Error('Invalid cloth timestep');
    const targets=poseGeometry(mesh),x=this.positions,previous=this.previous;
    this.colliders=bodyColliders(mesh);
    for(let v=0;v<this.mass.length;v++)for(let j=0;j<3;j++){
      const i=v*3+j,old=x[i];
      x[i]=this.mass[v]===0?targets[i]:old+(old-previous[i])*.30+(targets[i]-old)*this.follow;
      previous[i]=old;
    }
    this.edges.lambda.fill(0);this.bending.lambda.fill(0);this.cuffLambda.fill(0);
    for(let pass=0;pass<this.iterations;pass++){
      this.solveDistances(this.edges,dt,pass%2===1);
      this.solveDistances(this.bending,dt,pass%2===1);
      this.solveCuffs(targets,dt);
      this.solveCollisions();
    }
    // Resolve intersections with the union of overlapping collision proxies.
    this.solveCollisions();this.solveCollisions();
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

  solveCuffs(targets,dt){
    const alpha=this.cuffCompliance/(dt*dt),x=this.positions;
    for(let k=0;k<this.cuffs.length;k++){
      const v=this.cuffs[k],w=this.mass[v];
      for(let j=0;j<3;j++){
        const i=v*3+j,l=k*3+j,change=(-(x[i]-targets[i])-alpha*this.cuffLambda[l])/(w+alpha);
        this.cuffLambda[l]+=change;x[i]+=w*change;
      }
    }
  }

  solveCollisions(){
    const x=this.positions,previous=this.previous;
    for(let v=0;v<this.mass.length;v++){
      if(!this.mass[v])continue;const i=v*3,ox=x[i],oy=x[i+1],oz=x[i+2];
      for(let cIndex=0;cIndex<this.colliders.length;cIndex++){
        const c=this.colliders[cIndex];
        if(c.type==='ellipsoid'){
          const [cx,cy,cz]=c.center,[rx,ry,rz]=c.radii,dx=x[i]-cx,dy=x[i+1]-cy,dz=x[i+2]-cz;
          if(Math.abs(dx)>rx||Math.abs(dy)>ry||Math.abs(dz)>rz)continue;
          const q=Math.sqrt((dx/rx)**2+(dy/ry)**2+(dz/rz)**2);
          if(q>=1)continue;
          if(q<1e-10)x[i+2]=cz+(this.rest[i+2]>=0?rz:-rz);
          else{x[i]=cx+dx/q;x[i+1]=cy+dy/q;x[i+2]=cz+dz/q;}
        }else{
          const [ax,ay,az]=c.a,[bx,by,bz]=c.b,r=c.radius;
          if(x[i]<Math.min(ax,bx)-r||x[i]>Math.max(ax,bx)+r||x[i+1]<Math.min(ay,by)-r||x[i+1]>Math.max(ay,by)+r||x[i+2]<Math.min(az,bz)-r||x[i+2]>Math.max(az,bz)+r)continue;
          const vx=bx-ax,vy=by-ay,vz=bz-az,t=clamp(((x[i]-ax)*vx+(x[i+1]-ay)*vy+(x[i+2]-az)*vz)/(vx*vx+vy*vy+vz*vz),0,1);
          const cx=ax+vx*t,cy=ay+vy*t,cz=az+vz*t,dx=x[i]-cx,dy=x[i+1]-cy,dz=x[i+2]-cz,d=Math.sqrt(dx*dx+dy*dy+dz*dz);
          if(d>=r)continue;
          if(d<1e-10)x[i+2]=cz+(this.rest[i+2]>=0?r:-r);
          else{x[i]=cx+dx*r/d;x[i+1]=cy+dy*r/d;x[i+2]=cz+dz*r/d;}
        }
      }
      previous[i]+=(x[i]-ox)*.85;previous[i+1]+=(x[i+1]-oy)*.85;previous[i+2]+=(x[i+2]-oz)*.85;
    }
  }
}
