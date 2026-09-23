'use strict';
const {chromium}=require('playwright');const assert=require('node:assert/strict');const fs=require('node:fs');const path=require('node:path');
async function main(){
 const base=process.env.MCASTTALK_TEST_URL,output=process.env.MCASTTALK_TEST_OUTPUT,checks=[],errors=[],pages=[],events=[];
 const pass=name=>{checks.push(name);console.log('PASS',name);fs.appendFileSync(path.join(output,'progress.log'),new Date().toISOString()+' PASS '+name+'\n');};
 const loopbackWebRtc=process.env.MCASTTALK_TEST_LOOPBACK_WEBRTC==='1';
 if(loopbackWebRtc)assert.equal(base,'http://127.0.0.1:18791');
 // A no-NIC Sandbox needs Chromium's explicit loopback ICE test mode. This
 // does not test normal LAN ICE discovery and never changes the installed app.
 const browserArgs=['--autoplay-policy=no-user-gesture-required','--use-fake-device-for-media-stream','--use-fake-ui-for-media-stream'];
 if(loopbackWebRtc)browserArgs.push('--allow-loopback-in-peer-connection');
 const browser=await chromium.launch({channel:'msedge',headless:true,args:browserArgs});
 async function join(user,lang,secondary=null){
  const context=await browser.newContext({ignoreHTTPSErrors:base.startsWith('https:'),permissions:['camera','microphone'],viewport:{width:1440,height:960}});const p=await context.newPage();p.on('pageerror',e=>errors.push(e.message));
  pages.push(p);p.on('websocket',socket=>socket.on('framereceived',event=>{try{const m=JSON.parse(event.payload);events.push({user,type:m.type,code:m.code,status:m.status,translationStatus:m.translationStatus,targetLanguage:m.targetLanguage});}catch{}}));
  await p.goto(base);await p.locator('#login-username').fill(user);await p.locator('#login-password').fill(process.env.MCASTTALK_TEST_PASSWORD);await p.locator('#login-button').click();await p.locator('#prejoin').waitFor({state:'visible'});
  await p.locator('#room-id').fill('inference-room');await p.locator('#input-language').selectOption(lang);await p.locator('#listen-language').selectOption(lang);await p.locator('#display-language').selectOption(lang);
  if(secondary)await p.locator('#secondary-original-language').selectOption(secondary);
  await p.waitForFunction(()=>INFERENCE_ENABLED);
  if(user==='alice'&&secondary){
   await p.locator('#input-language').selectOption('ja');
   await p.waitForFunction(()=>{const text=document.querySelector('#capacity-demand').textContent;return text.includes('허용하지 않은 언어')&&text.includes('日本語');});
   pass('prejoin warning includes disallowed input language even when it is not a translation target');
   await p.locator('#input-language').selectOption(lang);
  }
  if(user==='alice'&&!secondary){
   await p.locator('#join-button').click();assert.equal(await p.locator('#meeting').isVisible(),false);pass('model terms require explicit participant acceptance');
  }
  await p.locator('#model-terms-accepted').check();
  await p.locator('#join-button').click();await p.locator('#meeting').waitFor({state:'visible'});return p;
 }
 try{
  const adminContext=await browser.newContext({ignoreHTTPSErrors:base.startsWith('https:'),viewport:{width:1440,height:1100}});
  const adminPage=await adminContext.newPage();pages.push(adminPage);adminPage.on('pageerror',e=>errors.push(e.message));
  await adminPage.goto(base);await adminPage.locator('#login-username').fill('smoke-admin');await adminPage.locator('#login-password').fill(process.env.MCASTTALK_TEST_PASSWORD);
  await adminPage.locator('#login-button').click();await adminPage.locator('#prejoin').waitFor({state:'visible'});
  await adminPage.waitForFunction(()=>capacityReport?.capacity?.calibrationStatus==='measured'||capacityReport?.inferenceStatus==='warmup-failed',null,{timeout:180000});
  const calibration=await adminPage.evaluate(()=>capacityReport);assert.equal(calibration.capacity.calibrationStatus,'measured');
  assert.equal(calibration.capacity.calibrationSamples,4);assert.equal(calibration.capacity.mode,'quality-first');
  fs.writeFileSync(path.join(output,'calibration.json'),JSON.stringify(calibration,null,2));pass('real installed-model calibration before admission; quality-first default');
  await adminPage.locator('#operator-button').click();await adminPage.locator('#capacity-operator-summary').waitFor({state:'visible'});
  for(const count of [5,7,10])assert.equal(await adminPage.locator('#capacity-count option[value="'+count+'"]').evaluate(e=>e.disabled),true);
  const unsupported=await adminContext.request.post(base+'/api/v1/admin/language-capacity',{headers:{Origin:base},data:{languages:'',meetingLanguageLimit:5}});assert.equal(unsupported.status(),400);
  pass('5 7 10 language counts require installed model support; server rejects forged unsupported count');
  assert.equal(await adminPage.locator('#capacity-count option[value="2"]').evaluate(e=>e.disabled),false);
  await adminPage.locator('#capacity-count').selectOption('2');
  for(const l of ['ko','en'])await adminPage.locator('input[name="capacity-language"][value="'+l+'"]').check();
  if(calibration.capacity.recommendedMeetingLanguages<2)assert.match(await adminPage.locator('#capacity-selection-status').textContent(),/원음 조건부/);
  await adminPage.locator('#capacity-save').click();
  await adminPage.waitForFunction(()=>capacityReport?.capacity?.configuredMeetingLanguageLimit===2&&capacityReport.capacity.mode==='quality-first');
  pass('quality-first accepts two conditional original languages without enabling delay override');
  const originalAlice=await join('alice','en','ko'),originalBob=await join('bob','ko','en');
  pass('bilingual original-only participants join under measured quality-first policy');
  const deniedShrink=await adminContext.request.post(base+'/api/v1/admin/language-capacity',{headers:{Origin:base},data:{languages:'ko',meetingLanguageLimit:1,allowDelay:false}});
  assert.equal(deniedShrink.status(),400);assert.equal(await originalBob.evaluate(()=>state.joined),true);
  pass('original-only meeting still preserves explicit admin cap and policy rollback');
  const conditionalPreview=await originalAlice.request.post(base+'/api/v1/language-plan',{headers:{Origin:base},data:{roomId:'inference-room',participantId:'prospective-listener',inputLanguage:'ko',publishLanguage:'ko',listenLanguage:'ko',displayLanguage:'ko',listener:true}});
  if(calibration.capacity.languageCosts.ko.estimatedOneLanguageMs>calibration.capacity.targetLatencyMs){
   assert.equal(conditionalPreview.status(),400);pass('new non-understanding listener is rejected before adding excessive translation workload');
  }else{assert.equal(conditionalPreview.status(),200);pass('new listener preflight fits measured translation workload');}
  await originalAlice.locator('#camera-button').click();
  await originalBob.waitForFunction(()=>[...mediaVideos.values()].some(v=>v.videoWidth>0),null,{timeout:30000});
  assert.equal(await originalBob.evaluate(()=>[...mediaVideos.entries()].filter(([id])=>id!==state.participantId).every(([,v])=>v.volume===1&&!v.muted)),true);
  pass('quality-first bilingual meeting keeps video and original media at full volume');
  await originalBob.evaluate(()=>{window.__originalTtsCount=0;const play=HTMLMediaElement.prototype.play;HTMLMediaElement.prototype.play=function(){if(this.tagName==='AUDIO')window.__originalTtsCount++;return play.apply(this,arguments);};});
  const originalEventStart=events.length;
  await speak(originalAlice,'en');
  // Placeholder copy also contains "AI". Require an actual authenticated server
  // caption event before inspecting the DOM; placeholder text is not ASR proof.
  const originalDeadline=Date.now()+180000;
  while(!events.slice(originalEventStart).some(e=>e.user==='bob'&&e.type==='INTERPRETATION'&&e.translationStatus==='original'&&e.targetLanguage==='en')){
   if(Date.now()>originalDeadline)throw new Error('Original-only server caption was not received');
   await new Promise(resolve=>setTimeout(resolve,100));
  }
  assert.match(await originalBob.locator('#subtitle-trans').textContent(),/[A-Za-z]/);
  await originalAlice.waitForFunction(()=>!voiceBusy,null,{timeout:30000});
  assert.equal(await originalBob.evaluate(()=>window.__originalTtsCount),0);
  pass('actual ASR provides original captions without redundant translated speech in quality-first mode');
  await originalBob.screenshot({path:path.join(output,'bilingual-quality-first.png'),fullPage:true});
  for(const p of [originalAlice,originalBob]){await p.locator('#disconnect-button').click();await p.context().close();}
  await adminPage.screenshot({path:path.join(output,'conditional-language-settings.png'),fullPage:true});
  await adminPage.locator('#capacity-mode').selectOption('allow-delay');await adminPage.locator('#capacity-count').selectOption('2');
  for(const l of ['ko','en'])await adminPage.locator('input[name="capacity-language"][value="'+l+'"]').check();
  assert.equal(await adminPage.locator('input[name="capacity-language"][value="ja"]').isDisabled(),true);pass('language checkbox selection is bounded before applying settings');
  await adminPage.locator('#capacity-save').click();
  await adminPage.waitForFunction(()=>document.querySelector('#capacity-save-status').textContent.includes('확인해야'));
  pass('delay override requires explicit acknowledgement');
  await adminPage.locator('#capacity-ack').check();await adminPage.locator('#capacity-save').click();
  await adminPage.waitForFunction(()=>capacityReport?.capacity?.mode==='allow-delay');pass('acknowledged operator choice enables advanced multilingual trial');
  await adminPage.screenshot({path:path.join(output,'resource-policy.png'),fullPage:true});
  const alice=await join('alice','en'), bob=await join('bob','ko');pass('configured offline model capability reaches both clients');
  const forbidden=await bob.request.post(base+'/api/v1/admin/language-capacity',{headers:{Origin:base},data:{languages:'',allowDelay:true,acknowledgeDelay:true}});
  assert.equal(forbidden.status(),403);pass('ordinary attendee cannot override server capacity policy');
  const preview=await alice.request.post(base+'/api/v1/language-plan',{headers:{Origin:base},data:{roomId:'inference-room',participantId:'prospective-carol',inputLanguage:'ja',publishLanguage:'ja',listenLanguage:'ja',displayLanguage:'ja'}});
  assert.equal(preview.status(),400);pass('third language refused at prejoin settings, not during speech');
  await alice.locator('#preferences-button').click();await alice.locator('#input-language').selectOption('ja');
  await alice.waitForFunction(()=>elements.inputLanguage.value==='en'&&state.participants.get(state.participantId)?.inputLanguage==='en');
  assert.equal(await alice.evaluate(()=>state.joined&&state.socket.readyState===WebSocket.OPEN),true);pass('rejected language change rolls back without disconnecting existing attendee');
  for(const p of [alice,bob])await p.locator('#chat-button').click();
  await alice.locator('#message-input').fill('The meeting starts at three in the afternoon.');await alice.locator('#send-button').click();
  await bob.waitForFunction(()=>document.querySelector('.chat-translation')?.textContent.length>0,null,{timeout:180000});pass('real translated room chat rendered');
  const translated=await bob.locator('.chat-translation').first().textContent();assert.match(translated,/[가-힣]/);pass('recipient receives Korean text');
  await alice.waitForFunction(()=>!state.pendingChat);assert.equal(await alice.locator('#message-input').inputValue(),'');pass('sender receives exact delivery acknowledgement');
  const expand=await adminContext.request.post(base+'/api/v1/admin/language-capacity',{headers:{Origin:base},data:{languages:'ko,en,ja,zh-CN',meetingLanguageLimit:4,allowDelay:true,acknowledgeDelay:true}});assert.equal(expand.status(),200);
  const carol=await join('carol','ja');await carol.locator('#chat-button').click();
  await alice.locator('#chat-recipient').selectOption({label:await alice.locator('#chat-recipient option').filter({hasText:'Bob'}).textContent()});
  await alice.locator('#message-input').fill('Please keep this meeting private.');await alice.locator('#send-button').click();
  await bob.waitForFunction(()=>document.querySelectorAll('.chat-message').length===2,null,{timeout:180000});
  assert.equal(await bob.locator('.chat-translation').count(),2,'Private translation must succeed, not just original fallback');pass('private chat also translated');
  assert.equal(await carol.locator('.chat-message').count(),0);pass('private translation not sent to third participant');
  const chinese=await join('other','zh-CN');
  await alice.waitForFunction(()=>!state.pendingChat);
  const shrink=await adminContext.request.post(base+'/api/v1/admin/language-capacity',{headers:{Origin:base},data:{languages:'ko,en',meetingLanguageLimit:2,allowDelay:true,acknowledgeDelay:true}});assert.equal(shrink.status(),400);
  const preserved=await(await adminContext.request.get(base+'/api/v1/language-capacity')).json();assert.equal(preserved.capacity.effectiveMeetingLanguageLimit,4);
  pass('operator cannot remove languages of existing participants; accepted plan remains intact');
  await bob.locator('#chat-button').click();
  await alice.locator('#camera-button').click();
  await bob.waitForFunction(()=>[...mediaVideos.values()].some(v=>v.videoWidth>0&&v.srcObject?.getVideoTracks().length),null,{timeout:30000});
  pass('video frames remain connected in the same multilingual room');
  assert.equal(await bob.evaluate(()=>[...mediaVideos.entries()].filter(([id])=>id!==state.participantId&&state.participants.get(id)?.inputLanguage==='en').every(([,v])=>v.volume===0.2&&!v.muted)),true);
  pass('unknown English original uses actual media volume 20 percent');
  for(const p of [alice,bob,carol,chinese])await p.evaluate(()=>{window.__speechPlayed=[];const play=HTMLMediaElement.prototype.play;HTMLMediaElement.prototype.play=function(){if(this.tagName==='AUDIO'){
   if(window.__blockSpeechOnce){window.__blockSpeechOnce=false;return Promise.reject(new DOMException('Test autoplay denial','NotAllowedError'));}
   window.__speechPlayed.push({src:this.src,at:performance.now(),volume:this.volume,sourceLanguage:this.mcastItem?.sourceLanguage});
  }return play.apply(this,arguments);};});
  await chinese.evaluate(()=>{window.__blockSpeechOnce=true;});
  async function speak(p,language){
   const wav=fs.readFileSync(path.join(process.env.MCASTTALK_TEST_AUDIO_ROOT||'.run/media-20260922/engine/cpu',`${language}.wav`)).toString('base64');
   await p.evaluate(async b64=>{
    if(window.__testAudio){await voiceCapture.setTrack(null);await window.__testAudio.context.close();}
    const bytes=Uint8Array.from(atob(b64),c=>c.charCodeAt(0));const context=new AudioContext({sampleRate:16000});
    const buffer=await context.decodeAudioData(bytes.buffer);const source=context.createBufferSource();source.buffer=buffer;
    const destination=context.createMediaStreamDestination();source.connect(destination);window.__testAudio={context,source,destination};
    await voiceCapture.setTrack(destination.stream.getAudioTracks()[0]);await context.resume();source.start();
   },wav);
  }
  async function waitVoiceComplete(page,user,from){
   // First recipient playback is progressive; other language outputs may still
   // be running. Functional completion is not the five-second latency target.
   const deadline=Date.now()+180000;
   while(!events.slice(from).some(e=>e.user===user&&e.type==='VOICE_STATUS'&&e.status==='complete')){
    assert.equal(events.slice(from).some(e=>e.user===user&&e.type==='ERROR'),false,'Voice request failed before completion');
    if(Date.now()>deadline)throw new Error('Server did not complete full voice fan-out for '+user);
    await new Promise(resolve=>setTimeout(resolve,100));
   }
   await page.waitForFunction(()=>!voiceBusy,null,{timeout:5000});
  }
  const firstEventStart=events.length,started=Date.now();
  await speak(alice,'en');
  await bob.waitForFunction(()=>window.__speechPlayed.length>0,null,{timeout:180000});pass('AudioWorklet → authenticated voice frame → real STT/MT/TTS → recipient playback');
  const voiceLatencyMs=Date.now()-started;
  const subtitle=await bob.locator('#subtitle-trans').textContent();assert.match(subtitle,/[가-힣]/);pass('Korean voice caption rendered from server inference');
  await carol.waitForFunction(()=>window.__speechPlayed.length>0,null,{timeout:30000});
  assert.match(await carol.locator('#subtitle-trans').textContent(),/[\u3040-\u30ff]/);pass('same utterance fans out to Japanese caption and speech');
  await chinese.locator('#resume-speech-button').waitFor({state:'visible'});pass('autoplay denial exposes an explicit speech resume action');
  await chinese.locator('#resume-speech-button').click();await chinese.waitForFunction(()=>window.__speechPlayed.length>0);
  assert.match(await chinese.locator('#subtitle-trans').textContent(),/[\u4e00-\u9fff]/);pass('Chinese caption and queued speech survive autoplay retry');
  await waitVoiceComplete(alice,'alice',firstEventStart);pass('voice work admission released after completion');
  const reverseEventStart=events.length,reverseStarted=Date.now();await speak(bob,'ko');
  await alice.waitForFunction(()=>window.__speechPlayed.length>0,null,{timeout:180000});
  const reverseSubtitle=await alice.locator('#subtitle-trans').textContent();assert.match(reverseSubtitle,/[A-Za-z]/);pass('Korean participant speaks and English participant receives translated caption and audio');
  const reverseVoiceLatencyMs=Date.now()-reverseStarted;
  await waitVoiceComplete(bob,'bob',reverseEventStart);
  for(const user of ['alice','carol','other'])assert.equal(events.slice(reverseEventStart).some(e=>e.user===user&&e.type==='INTERPRETATION_AUDIO'),true,'Missing reverse voice recipient '+user);
  await bob.locator('#preferences-button').click();await bob.locator('#secondary-original-language').selectOption('en');
  await bob.waitForFunction(()=>state.participants.get(state.participantId)?.secondaryOriginalLanguage==='en');
  assert.equal(await bob.evaluate(()=>[...mediaVideos.entries()].filter(([id])=>id!==state.participantId&&state.participants.get(id)?.inputLanguage==='en').every(([,v])=>v.volume===1&&!v.muted)),true);
  pass('secondary understood language restores normal original audio');
  await bob.waitForFunction(()=>!speechAudio&&speechQueue.length===0,null,{timeout:20000});
  const bobAudioBefore=await bob.evaluate(()=>window.__speechPlayed.length),carolBefore=await carol.evaluate(()=>window.__speechPlayed.length);
  const secondaryEventStart=events.length;
  await speak(alice,'en');await carol.waitForFunction(n=>window.__speechPlayed.length>n,carolBefore,{timeout:180000});
  await waitVoiceComplete(alice,'alice',secondaryEventStart);
  assert.equal(await bob.evaluate(()=>window.__speechPlayed.length),bobAudioBefore);assert.match(await bob.locator('#subtitle-trans').textContent(),/[A-Za-z]/);
  pass('English-understanding Korean attendee gets original caption and no redundant TTS');
  assert.equal(await carol.evaluate(()=>window.__speechPlayed.every(item=>item.volume===1)),true);pass('interpreted HTML audio remains at full volume');
  assert.equal(await alice.evaluate(()=>meetingMedia.peers.size),3);pass('four-party video signaling survives bidirectional interpretation');
  const operator=await browser.newContext({ignoreHTTPSErrors:base.startsWith('https:')});
  const login=await operator.request.post(base+'/api/v1/auth/login',{headers:{Origin:base},data:{username:'smoke-admin',password:process.env.MCASTTALK_TEST_PASSWORD}});
  assert.equal(login.status(),200);const diagnostics=await(await operator.request.get(base+'/api/v1/admin/diagnostics')).json();
  assert.ok(['cpu','vulkan'].includes(diagnostics.inference.translationBackend));pass('operator diagnostics report the actual translation backend and fallback state');
  await bob.screenshot({path:path.join(output,'interpreted-meeting.png'),fullPage:true});
  assert.deepEqual(errors,[]);pass('zero browser exceptions during inference');
  fs.writeFileSync(path.join(output,'inference-results.json'),JSON.stringify({checks,translated,subtitle,voiceLatencyMs,reverseSubtitle,reverseVoiceLatencyMs,inference:diagnostics.inference,capacity:diagnostics.languageCapacity,errors,loopbackWebRtc,scope:'real local models; synthetic audio and camera; four languages with explicitly acknowledged delay override on one Windows host, not human language-quality or physical-device UAT'},null,2));
 }catch(error){
  if(pages[0])fs.writeFileSync(path.join(output,'capacity-dom.json'),JSON.stringify(await pages[0].evaluate(()=>[...document.querySelectorAll('#capacity-count option')].map(e=>({value:e.value,disabled:e.disabled,text:e.textContent}))).catch(()=>[]),null,2));
  fs.writeFileSync(path.join(output,'failure.json'),JSON.stringify({checks,errors,events,error:error.message},null,2));
  for(let i=0;i<pages.length;i++)await pages[i].screenshot({path:path.join(output,`failure-${i}.png`),fullPage:true}).catch(()=>{});
  throw error;
 }finally{await browser.close();}
}
main().catch(e=>{console.error(e);process.exitCode=1;});
