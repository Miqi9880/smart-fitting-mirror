/** Inset front UV boundary samples into the source's opaque cloth region.
 * Only texture coordinates change. PNG pixels and garment topology are untouched.
 * A 3D cloth surface must not inherit cutout transparency from a 2D product photo.
 */
export function padFrontUV(geometry,{width,height,data},{margin=6,alphaThreshold=240}={}){
  const stride=width+1,integral=new Uint32Array(stride*(height+1));
  for(let y=0;y<height;y++){
    let row=0;
    for(let x=0;x<width;x++){
      row+=data[(y*width+x)*4+3]<alphaThreshold?1:0;
      integral[(y+1)*stride+x+1]=integral[y*stride+x+1]+row;
    }
  }
  const safe=(x,y)=>{
    if(x<margin||y<margin||x>=width-margin||y>=height-margin)return false;
    const l=x-margin,r=x+margin+1,t=y-margin,b=y+margin+1;
    return integral[b*stride+r]-integral[t*stride+r]-integral[b*stride+l]+integral[t*stride+l]===0;
  };
  const group=geometry.groups[0],uv=geometry.attributes.uv.array;
  const vertices=new Set(geometry.index.array.slice(group.start,group.start+group.count));
  let changed=0,maxShift=0;
  for(const vertex of vertices){
    const x=Math.max(0,Math.min(width-1,Math.floor(uv[vertex*2]*width)));
    const y=Math.max(0,Math.min(height-1,Math.floor(uv[vertex*2+1]*height)));
    if(safe(x,y))continue;
    let best;
    for(let radius=1;radius<=128&&!best;radius++){
      let distance=Infinity;
      const consider=(cx,cy)=>{if(!safe(cx,cy))return;const d=(cx-x)**2+(cy-y)**2;if(d<distance){distance=d;best=[cx,cy];}};
      for(let d=-radius;d<=radius;d++){consider(x+d,y-radius);consider(x+d,y+radius);}
      for(let d=-radius+1;d<radius;d++){consider(x-radius,y+d);consider(x+radius,y+d);}
    }
    if(!best)throw new Error(`No opaque cloth UV neighborhood at vertex ${vertex}`);
    uv[vertex*2]=(best[0]+.5)/width;uv[vertex*2+1]=(best[1]+.5)/height;
    changed++;maxShift=Math.max(maxShift,Math.hypot(best[0]-x,best[1]-y));
  }
  geometry.attributes.uv.needsUpdate=true;
  return {margin_pixels:margin,alpha_threshold:alphaThreshold,adjusted_vertices:changed,max_shift_pixels:maxShift};
}
