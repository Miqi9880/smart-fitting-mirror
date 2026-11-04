/** Traverse verified clips via standing. Never crossfade unrelated garment
 * poses while independently snapping the body skeleton to its new pose. */
export class MotionPlayer {
  constructor(clips){this.clips=clips;this.pose='rest';this.frame=0;this.desired='rest';this.direction=1;this.loop=false;}
  choose(pose){
    if(!['rest','raise','reach','turn'].includes(pose))throw new Error('Unknown action');
    this.desired=pose;this.loop=false;
    if(this.pose==='rest')this.activate();
    else if(this.pose!==pose)this.direction=-1;
  }
  activate(){
    this.pose=['rest','turn'].includes(this.desired)?'rest':this.desired;
    this.frame=0;this.direction=1;
  }
  advance(seconds){
    if(!Number.isFinite(seconds)||seconds<0)throw new Error('Invalid elapsed time');
    let remaining=Math.min(seconds,5);
    for(let step=0;step<12&&remaining>1e-8;step++){
      if(this.pose==='rest'){this.activate();if(this.pose==='rest')break;}
      const clip=this.clips[this.pose],end=clip.count-1;
      const distance=this.direction>0?end-this.frame:this.frame,time=distance/clip.fps;
      if(remaining<time){this.frame+=this.direction*remaining*clip.fps;break;}
      this.frame=this.direction>0?end:0;remaining-=time;
      if(this.direction<0){
        if(this.desired===this.pose&&this.loop)this.direction=1;
        else{this.pose='rest';this.activate();}
      }else if(this.desired!==this.pose)this.direction=-1;
      else if(this.loop)this.direction=-1;
      else break;
    }
    return {pose:this.pose,frame:this.frame};
  }
}
