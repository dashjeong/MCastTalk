'use strict';
const {chromium}=require('playwright');
const assert=require('node:assert/strict');
const fs=require('node:fs');const path=require('node:path');
const base=process.env.MCASTTALK_TEST_URL, output=process.env.MCASTTALK_TEST_OUTPUT;
async function main(){
 const browser=await chromium.launch({channel:'msedge',headless:true,args:['--use-fake-device-for-media-stream','--use-fake-ui-for-media-stream','--autoplay-policy=no-user-gesture-required']});
 const errors=[],checks=[];const pass=name=>{checks.push(name);console.log('PASS',name);};
 async function participant(username,room='media-test',listener=false){
  const context=await browser.newContext({permissions:['camera','microphone'],ignoreHTTPSErrors:base.startsWith('https:'),viewport:{width:1440,height:960}});
  const p=await context.newPage();p.on('pageerror',e=>errors.push(e.message));
  await p.goto(base);await p.locator('#login-username').fill(username);await p.locator('#login-password').fill(process.env.MCASTTALK_TEST_PASSWORD);
  await p.locator('#login-button').click();await p.locator('#prejoin').waitFor({state:'visible'});
  await p.locator('#room-id').fill(room);await p.locator('#audio-mode').selectOption('original');
  await p.locator(listener?'#listener-quick-button':'#join-button').click();await p.locator('#meeting').waitFor({state:'visible'});return p;
 }
 async function connected(p,count){await p.waitForFunction(n=>meetingMedia.peers.size===n&&[...meetingMedia.peers.values()].every(e=>e.pc.connectionState==='connected'),count,{timeout:30000});}
 try{
  const a=await participant('alice');pass('authenticated speaker enters');
  assert.equal(await a.locator('#camera-button').isEnabled(),true);pass('camera controls enabled in secure context');
  assert.equal(await a.evaluate(()=>meetingMedia.stream.getTracks().length),0);pass('no camera or microphone captured before consent');
  const b=await participant('bob');await connected(a,1);await connected(b,1);pass('two authenticated browsers establish WebRTC');
  assert.equal(await a.evaluate(()=>[...meetingMedia.peers.values()][0].pc.getConfiguration().iceServers.length),0);pass('LAN media has no public STUN dependency');
  await a.locator('#camera-button').click();await a.waitForFunction(()=>meetingMedia.stream.getVideoTracks().length===1);pass('camera toggle acquires video track');
  await b.waitForFunction(()=>[...mediaVideos.values()].some(v=>v.videoWidth>0&&v.srcObject?.getVideoTracks().length));pass('remote video decoded and rendered');
  const stats=await b.evaluate(async()=>{const s=await [...meetingMedia.peers.values()][0].pc.getStats();return [...s.values()].filter(x=>x.type==='inbound-rtp').map(x=>({kind:x.kind,bytesReceived:x.bytesReceived,framesDecoded:x.framesDecoded}));});
  assert.ok(stats.some(s=>s.kind==='video'&&s.framesDecoded>0&&s.bytesReceived>0));pass('real RTP video frames received');
  await b.locator('#camera-button').click();await a.waitForFunction(()=>[...mediaVideos.values()].filter(v=>v.videoWidth>0).length>=2);pass('bidirectional video');
  await a.locator('#microphone-button').click();await a.waitForFunction(()=>state.micActive);pass('microphone track publishes');
  await b.waitForFunction(async()=>{const s=await [...meetingMedia.peers.values()][0].pc.getStats();return [...s.values()].some(x=>x.type==='inbound-rtp'&&x.kind==='audio'&&x.bytesReceived>0);});pass('real RTP audio packets received');
  await b.locator('#preferences-button').click();await b.locator('#audio-mode').selectOption('captions_only');
  assert.equal(await b.evaluate(()=>[...mediaVideos.entries()].filter(([id])=>id!==state.participantId).every(([,v])=>v.muted)),true);pass('captions-only mutes original audio');
  await b.locator('#audio-mode').selectOption('original');pass('original audio preference restores playback');
  const c=await participant('carol');await connected(c,2);await connected(a,2);pass('third participant mesh negotiates');
  const outsider=await participant('other','outside-media');
  assert.equal(await outsider.evaluate(()=>meetingMedia.peers.size),0);pass('separate room receives no media peers');
  await a.screenshot({path:path.join(output,'media-connected.png')});
  await a.locator('#camera-button').click();assert.equal(await a.evaluate(()=>meetingMedia.stream.getVideoTracks().length),0);pass('camera off releases source');
  await a.locator('#microphone-button').click();assert.equal(await a.evaluate(()=>meetingMedia.stream.getAudioTracks().length),0);pass('microphone off releases source');
  await a.locator('#disconnect-button').click();await a.locator('#prejoin').waitFor({state:'visible'});
  assert.equal(await a.evaluate(()=>meetingMedia.peers.size),0);pass('leave closes all peer connections');
  await connected(b,1);pass('other participants remove departed peer');
  await a.locator('#join-button').click();await a.locator('#meeting').waitFor({state:'visible'});await connected(a,2);pass('rejoin uses fresh media generation');
  assert.equal(await a.evaluate(()=>meetingMedia.stream.getTracks().length),0);pass('rejoin does not auto-open camera');
  const listener=await participant('mallory','media-test',true);await connected(listener,3);pass('authenticated listener negotiates receive-only media');
  assert.equal(await listener.locator('#camera-button').isDisabled(),true);assert.equal(await listener.locator('#microphone-button').isDisabled(),true);pass('listener cannot publish from UI');
  assert.equal(await listener.evaluate(()=>[...meetingMedia.peers.values()].every(e=>e.pc.getTransceivers().every(t=>t.direction==='recvonly'))),true);pass('listener transceivers are receive-only');
  assert.deepEqual(errors,[]);pass('zero browser runtime exceptions');
  fs.writeFileSync(path.join(output,'media-results.json'),JSON.stringify({checks,stats,errors,environment:'headless Edge, simulated camera/mic, real WebRTC/host transport; not physical-device LAN UAT'},null,2));
 }finally{await browser.close();}
}
main().catch(e=>{console.error(e);process.exitCode=1;});
