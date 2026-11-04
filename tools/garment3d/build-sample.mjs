import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import crypto from 'node:crypto';
import {PNG} from 'pngjs';
import {buildGarment,createRig,TEMPLATE_ID,SOURCE_SIZE} from './template.mjs';
import {padFrontUV} from './texture-uv.mjs';

const here=path.dirname(fileURLToPath(import.meta.url)), root=path.resolve(here,'../..');
const manifest=JSON.parse(await fs.readFile(path.join(root,'backend/assets/studio/manifest.json'),'utf8'));
const source=manifest.items.find(i=>i.garment_id==='studio_blue_tee');
const inspectedHash='41a701078e72da7e5c544d2dbfea72e062fa55225b0bbfac49c787156ac733c8';
if(!source||source.sha256!==inspectedHash||source.width!==SOURCE_SIZE[0]||source.height!==SOURCE_SIZE[1]){
  throw new Error('The blue tee source changed or is missing. Revalidate the template framing before exporting.');
}
const image=await fs.readFile(path.join(root,'backend/assets/studio',source.file));
if(crypto.createHash('sha256').update(image).digest('hex')!==source.sha256)throw new Error('Source image hash mismatch');
const geometry=buildGarment(),texturePadding=padFrontUV(geometry,PNG.sync.read(image)),mesh=createRig(geometry,[]);
const binParts=[],views=[],accessors=[];let offset=0;
function view(bytes,target) {
  const padding=(4-offset%4)%4;if(padding){binParts.push(Buffer.alloc(padding));offset+=padding;}
  const index=views.length;views.push({buffer:0,byteOffset:offset,byteLength:bytes.byteLength,...(target?{target}:{})});
  binParts.push(Buffer.from(bytes.buffer,bytes.byteOffset,bytes.byteLength));offset+=bytes.byteLength;return index;
}
function accessor(array,type,componentType,target,minMax=false) {
  const width={SCALAR:1,VEC2:2,VEC3:3,VEC4:4,MAT4:16}[type], a={bufferView:view(array,target),componentType,count:array.length/width,type};
  if(minMax){a.min=Array.from({length:width},(_,j)=>Math.min(...array.filter((_,i)=>i%width===j)));a.max=Array.from({length:width},(_,j)=>Math.max(...array.filter((_,i)=>i%width===j)));}
  const index=accessors.length;accessors.push(a);return index;
}
const attributes={
  POSITION:accessor(geometry.attributes.position.array,'VEC3',5126,34962,true),
  NORMAL:accessor(geometry.attributes.normal.array,'VEC3',5126,34962),
  TEXCOORD_0:accessor(geometry.attributes.uv.array,'VEC2',5126,34962),
  JOINTS_0:accessor(geometry.attributes.skinIndex.array,'VEC4',5123,34962),
  WEIGHTS_0:accessor(geometry.attributes.skinWeight.array,'VEC4',5126,34962),
};
const primitives=geometry.groups.map(g=>({attributes,indices:accessor(geometry.index.array.slice(g.start,g.start+g.count),'SCALAR',5123,34963),material:g.materialIndex}));
const inverses=accessor(new Float32Array(mesh.skeleton.boneInverses.flatMap(m=>m.toArray())),'MAT4',5126);
const imageView=view(image);
const nodes=[{name:mesh.name,mesh:0,skin:0,children:[1]},...mesh.skeleton.bones.map((b,i)=>({name:b.name,translation:b.position.toArray(),rotation:b.quaternion.toArray(),...(b.children.length?{children:b.children.map(c=>mesh.skeleton.bones.indexOf(c)+1)}:{})}))];
const extras={template_id:TEMPLATE_ID,source_garment_id:source.garment_id,source_sha256:source.sha256,
  source_url:source.source_url,geometry:'Authored approximate template, not a scan or AI reconstruction',
  unseen_surfaces:'Back/side approximate fabric sampled from the front; no verified back reference',motion:'Neutral T-pose GLB; separate precomputed pose-guided constrained corrections, not full cloth dynamics',
  texture_mapping:'Opaque cloth with front UV boundary inset; PNG bytes unchanged',texture_uv_padding:texturePadding,
  size_basis:'Nominal template proportions, not product measurements',rights:source.rights};
const binary=Buffer.concat(binParts);const binPadding=(4-binary.length%4)%4;
const gltf={asset:{version:'2.0',generator:'smart-fitting-mirror garment3d sample v0.1',extras},scene:0,scenes:[{nodes:[0]}],nodes,
  skins:[{name:'tee_rig',joints:mesh.skeleton.bones.map((_,i)=>i+1),skeleton:1,inverseBindMatrices:inverses}],
  meshes:[{name:'crewneck_tee',primitives}],
  materials:[{name:'official_blue_front',pbrMetallicRoughness:{baseColorTexture:{index:0},metallicFactor:0,roughnessFactor:.88},doubleSided:true},
    {name:'approximate_fabric_back_and_seams',pbrMetallicRoughness:{baseColorTexture:{index:0},metallicFactor:0,roughnessFactor:.88},doubleSided:true}],
  textures:[{source:0,sampler:0}],samplers:[{magFilter:9729,minFilter:9987,wrapS:33071,wrapT:33071}],images:[{bufferView:imageView,mimeType:'image/png'}],
  accessors,bufferViews:views,buffers:[{byteLength:binary.length}]};
const json=Buffer.from(JSON.stringify(gltf)), jsonPad=(4-json.length%4)%4;
const jsonChunk=Buffer.concat([json,Buffer.alloc(jsonPad,0x20)]),binChunk=Buffer.concat([binary,Buffer.alloc(binPadding)]);
const header=Buffer.alloc(12),jh=Buffer.alloc(8),bh=Buffer.alloc(8);
header.writeUInt32LE(0x46546c67);header.writeUInt32LE(2,4);header.writeUInt32LE(12+8+jsonChunk.length+8+binChunk.length,8);
jh.writeUInt32LE(jsonChunk.length);jh.writeUInt32LE(0x4e4f534a,4);bh.writeUInt32LE(binChunk.length);bh.writeUInt32LE(0x004e4942,4);
const glb=Buffer.concat([header,jh,jsonChunk,bh,binChunk]);
const output=path.join(here,'.local');await fs.mkdir(output,{recursive:true});
await fs.writeFile(path.join(output,'blue-tee.glb'),glb);
await fs.copyFile(path.join(root,'backend/assets/studio',source.file),path.join(output,'source.png'));
const summary={...extras,name:source.name,vertices:geometry.attributes.position.count,triangles:geometry.index.count/3,
  glb_bytes:glb.length,glb_sha256:crypto.createHash('sha256').update(glb).digest('hex'),geometry_sha256:crypto.createHash('sha256').update(Buffer.from(geometry.attributes.position.array.buffer)).digest('hex')};
await fs.writeFile(path.join(output,'sample.json'),JSON.stringify(summary,null,2)+'\n');
console.log(JSON.stringify(summary,null,2));
