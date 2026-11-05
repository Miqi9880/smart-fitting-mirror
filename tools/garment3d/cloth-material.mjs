import * as THREE from 'three';

/** The source is an already-lit product photo, not measured linear albedo.
 * Avoid adding bright PBR/specular light and ACES on top of that photograph.
 * A bounded, gentle normal term keeps shape cues without bleaching the colour.
 */
export function photoClothMaterial(source){
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
