import test from 'node:test';
import assert from 'node:assert/strict';
import {ViewerLifecycle} from '../viewer-lifecycle.mjs';

test('background stops rendering, resume excludes the paused wall-clock interval',()=>{
  let callback;const deltas=[];
  const view=new ViewerLifecycle({setLoop:value=>{callback=value;},render:(_,dt)=>deltas.push(dt)});
  view.start();callback(1000);callback(1020);
  view.setHostActive(false);assert.equal(callback,null);
  view.setHostActive(true);callback(20000);callback(20020);
  assert.deepEqual(deltas,[0,.02,0,.02]);
  view.setVisible(false);assert.equal(callback,null);
  view.setHostActive(true);assert.equal(callback,null,'native resume must not override hidden document');
  view.setVisible(true);assert.equal(typeof callback,'function');
});

test('closing is terminal even if a late resume or frame callback arrives',()=>{
  let callback,count=0;const states=[];
  const view=new ViewerLifecycle({setLoop:value=>{callback=value;},render:()=>count++,changed:s=>states.push(s)});
  view.start();const late=callback;view.dispose();view.setHostActive(true);view.setVisible(true);late(5000);
  assert.equal(callback,null);assert.equal(count,0);assert.equal(states.at(-1),'disposed');
});
