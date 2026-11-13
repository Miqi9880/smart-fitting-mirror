import test from 'node:test';
import assert from 'node:assert/strict';
import {MotionPlayer} from '../motion-player.mjs';
const clips={rest:{count:1,fps:60},raise:{count:91,fps:60},reach:{count:91,fps:60}};

test('switching actions lowers through the current verified clip before the next action',()=>{
  const player=new MotionPlayer(clips);player.choose('raise');player.advance(1);
  assert.equal(player.frame,60);player.choose('reach');
  let result=player.advance(.1);assert.equal(result.pose,'raise');assert.equal(result.frame,54);
  result=player.advance(1);assert.equal(result.pose,'reach');assert.ok(result.frame>0&&result.frame<10);
  result=player.advance(2);assert.equal(result.pose,'reach');assert.equal(result.frame,90);
});
test('standing and turning wait for arms to lower rather than snapping the body',()=>{
  const player=new MotionPlayer(clips);player.choose('reach');player.advance(2);player.choose('turn');
  assert.equal(player.advance(.1).pose,'reach');assert.equal(player.advance(2).pose,'rest');
  assert.equal(player.desired,'turn');assert.equal(player.frame,0);
});
test('loop uses the same clip forwards and backwards and stays within sample bounds',()=>{
  const player=new MotionPlayer(clips);player.choose('raise');player.loop=true;let previous=0;
  for(let k=0;k<400;k++){
    const state=player.advance(1/60);assert.equal(state.pose,'raise');assert.ok(state.frame>=0&&state.frame<=90);
    assert.ok(Math.abs(state.frame-previous)<=1.000001);previous=state.frame;
  }
});
