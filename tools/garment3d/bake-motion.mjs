import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import crypto from 'node:crypto';
import {buildGarment,createRig,setPose} from './template.mjs';
import {NEUTRAL_ARM_ANGLE as neutral,bodyColliders} from './rig.mjs';
import {ClothSimulation} from './cloth.mjs';
import {edgeStrain,surfaceContacts} from './quality.mjs';

export const SOLVER_OPTIONS=Object.freeze({iterations:128,follow:.020});

export function bakeMotion({onProgress=()=>{},checkSurfaces=true}={}){
  const geometry=buildGarment(),mesh=createRig(geometry,[]),stride=geometry.attributes.position.array.length;
  const options={...SOLVER_OPTIONS},sim=new ClothSimulation(geometry,options);
  const dt=1/240,frames=[],clips={},report={};
  const measure=()=>{
    let maxCuffError=0;
    for(const vertex of sim.cuffs){const i=vertex*3;maxCuffError=Math.max(maxCuffError,Math.hypot(sim.positions[i]-sim.targets[i],sim.positions[i+1]-sim.targets[i+1],sim.positions[i+2]-sim.targets[i+2]));}
    return {...edgeStrain(geometry,sim.positions),maxCuffError,...(checkSurfaces?surfaceContacts(geometry,sim.positions,bodyColliders(mesh,0)):{})};
  };
  // Lower from neutral T before saving the standing drape. This is a bounded,
  // pose-guided correction pipeline, not a free-running physical fabric model.
  for(let f=0;f<90;f++){
    const t=Math.min(1,f/60),s=t*t*(3-2*t);setPose(mesh.skeleton.bones,'raise',(1-s)*neutral/1.05);
    for(let sub=0;sub<4;sub++)sim.step(mesh,dt);
  }
  const standing=sim.positions.slice();frames.push(Float32Array.from(standing));
  clips.rest={start:0,count:1,fps:60};report.rest=measure();onProgress('rest',report.rest);
  for(const pose of ['raise','reach']){
    sim.positions.set(standing);sim.previous.set(standing);const start=frames.length,samples=[];
    for(let f=0;f<=90;f++){
      const t=Math.min(1,f/60),phase=t*t*(3-2*t);setPose(mesh.skeleton.bones,pose,phase);
      for(let sub=0;sub<4;sub++)sim.step(mesh,dt);
      frames.push(Float32Array.from(sim.positions));samples.push({frame:f,phase,...measure()});
    }
    clips[pose]={start,count:frames.length-start,fps:60};report[pose]=samples;onProgress(pose,samples.at(-1));
  }
  const array=new Float32Array(frames.length*stride);frames.forEach((frame,i)=>array.set(frame,i*stride));
  const digest=bytes=>crypto.createHash('sha256').update(bytes).digest('hex');
  const manifest={version:2,vertices:stride/3,stride,clips,options,dt,frame_count:frames.length,byte_length:array.byteLength,
    geometry_sha256:digest(Buffer.from(geometry.attributes.position.array.buffer)),
    motion_sha256:digest(Buffer.from(array.buffer)),
    method:'Precomputed pose-guided distance/bending correction with mannequin collision proxies; no calibrated fabric dynamics or self-collision',report};
  return {geometry,array,manifest};
}

if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
  const {array,manifest}=bakeMotion({onProgress:(pose,report)=>console.log(pose,report)});
  const directory=new URL('./.local/',import.meta.url);await fs.mkdir(directory,{recursive:true});
  await fs.writeFile(new URL('motion.bin',directory),Buffer.from(array.buffer));
  await fs.writeFile(new URL('motion.json',directory),JSON.stringify(manifest,null,2));
  console.log(`Baked ${manifest.frame_count} frames, ${array.byteLength} bytes`);
}
