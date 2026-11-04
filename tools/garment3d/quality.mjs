export function edgeStrain(geometry, positions) {
  const rest=geometry.attributes.position.array, indices=geometry.index.array, edges=new Set(), ratios=[];
  for(let k=0;k<indices.length;k+=3)for(let j=0;j<3;j++){
    const a=indices[k+j],b=indices[k+(j+1)%3],key=a<b?`${a}:${b}`:`${b}:${a}`;
    if(edges.has(key))continue;edges.add(key);
    const i=a*3,l=b*3,length=Math.hypot(rest[i]-rest[l],rest[i+1]-rest[l+1],rest[i+2]-rest[l+2]);
    if(length<.008)continue;
    ratios.push(Math.hypot(positions[i]-positions[l],positions[i+1]-positions[l+1],positions[i+2]-positions[l+2])/length);
  }
  ratios.sort((a,b)=>a-b);
  return {edges:ratios.length,p95:ratios[Math.floor(ratios.length*.95)],p99:ratios[Math.floor(ratios.length*.99)],max:ratios.at(-1),over110:ratios.filter(r=>r>1.1).length};
}
import {penetration} from './cloth.mjs';


export function surfaceContacts(geometry,positions,colliders){
  let maxPenetration=0,worstContact,samples=0;
  const inspect=(x,y,z,kind,index)=>{
    samples++;const point=[x,y,z];
    for(let c=0;c<colliders.length;c++){
      const depth=penetration(point,colliders[c]);
      if(depth>maxPenetration){maxPenetration=depth;worstContact={kind,index,collider:c,point};}
    }
  };
  for(let i=0;i<positions.length;i+=3)inspect(positions[i],positions[i+1],positions[i+2],'vertex',i/3);
  const indices=geometry.index.array,edges=new Set();
  for(let k=0;k<indices.length;k+=3){
    const a=indices[k]*3,b=indices[k+1]*3,c=indices[k+2]*3;
    inspect((positions[a]+positions[b]+positions[c])/3,(positions[a+1]+positions[b+1]+positions[c+1])/3,(positions[a+2]+positions[b+2]+positions[c+2])/3,'face',k/3);
    for(const [p,q] of [[a,b],[b,c],[c,a]]){
      const key=p<q?`${p}:${q}`:`${q}:${p}`;if(edges.has(key))continue;edges.add(key);
      inspect((positions[p]+positions[q])/2,(positions[p+1]+positions[q+1])/2,(positions[p+2]+positions[q+2])/2,'edge',key);
    }
  }
  return {maxPenetration,worstContact,contactSamples:samples};
}
