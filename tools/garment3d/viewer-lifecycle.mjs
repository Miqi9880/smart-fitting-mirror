/** Per-view lifecycle: no background animation and no wall-clock jump on resume. */
export class ViewerLifecycle {
  constructor({setLoop,render,changed=()=>{}}){
    this.setLoop=setLoop;this.render=render;this.changed=changed;
    this.visible=true;this.hostActive=true;this.disposed=false;this.running=false;this.lastTime=null;
    this.tick=time=>{
      if(!this.running)return;
      const delta=this.lastTime===null?0:Math.max(0,Math.min(.1,(time-this.lastTime)/1000));
      this.lastTime=time;this.render(time,delta);
    };
  }
  start(){this.sync();}
  setVisible(value){this.visible=value;this.sync();}
  setHostActive(value){this.hostActive=value;this.sync();}
  dispose(){this.disposed=true;this.sync();}
  sync(){
    const next=this.visible&&this.hostActive&&!this.disposed;
    if(next!==this.running){this.running=next;this.lastTime=null;this.setLoop(next?this.tick:null);}
    this.changed(this.disposed?'disposed':next?'running':'paused');
  }
}
