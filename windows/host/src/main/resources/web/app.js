"use strict";

// Authenticated WebRTC and configured local inference are separate capabilities.
const MEDIA_ENABLED = Boolean(window.RTCPeerConnection && window.isSecureContext);
let INFERENCE_ENABLED = false;
let serverCapabilities = {};
let capacityReport = null, capacityBusy = false, languagePreflightBusy = false;

const byId = (id) => document.getElementById(id);
const elements = {
  form: byId("join-form"), roomId: byId("room-id"), displayName: byId("display-name"),
  inputLanguage: byId("input-language"), publishLanguage: byId("publish-language"),
  listenLanguage: byId("listen-language"), displayLanguage: byId("display-language"),
  secondaryOriginalLanguage: byId("secondary-original-language"),
  audioMode: byId("audio-mode"), duckingMode: byId("ducking-mode"),
  duckingSlider: byId("ducking-slider"), duckingDisplay: byId("ducking-display"),
  subtitleOrig: byId("subtitle-orig"), subtitleTrans: byId("subtitle-trans"),
  subtitleLangChip: byId("subtitle-lang-chip"), micButton: byId("microphone-button"),
  micLabel: byId("mic-label"), captionsButton: byId("captions-button"),
  captionLabel: byId("caption-label"), listenerQuickButton: byId("listener-quick-button"),
  loginListenerButton: byId("login-listener-button"), listenerInviteLink: byId("listener-invite-link"),
  copyListenerButton: byId("copy-listener-button"), roleBadge: byId("role-badge"),
  joinButton: byId("join-button"), disconnectButton: byId("disconnect-button"),
  connectionPill: byId("connection-pill"), connectionLabel: byId("connection-label"),
  participantList: byId("participant-list"), participantCount: byId("participant-count"),
  conversationFeed: byId("conversation-feed"), prejoin: byId("prejoin"),
  meeting: byId("meeting"), gallery: byId("participant-gallery"),
  workspace: byId("meeting-workspace"), panel: byId("side-panel"),
  panelHeading: byId("side-panel-heading"), preferenceStatus: byId("preference-status"),
  chatForm: byId("chat-form"), chatInput: byId("message-input"), recipient: byId("chat-recipient"),
  chatFeed: byId("chat-feed"), sendButton: byId("send-button"), chatStatus: byId("chat-status"),
};
const preferenceControls = [elements.inputLanguage, elements.publishLanguage,
  elements.listenLanguage, elements.secondaryOriginalLanguage, elements.displayLanguage, elements.audioMode, elements.duckingMode].filter(Boolean);
const languageNames = new Map([["ko", "한국어"], ["en", "English"], ["ja", "日本語"], ["zh-CN", "简体中文"]]);
const panels = {
  participants: { title: "참가자", button: byId("participants-button"), content: byId("participants-panel") },
  preferences: { title: "내 언어", button: byId("preferences-button"), content: byId("preferences-panel") },
  chat: { title: "채팅", button: byId("chat-button"), content: byId("chat-panel") },
  activity: { title: "활동", button: byId("activity-button"), content: byId("activity-panel") },
};
const state = {
  socket: null, participantId: createParticipantId(), presenceId: null, participants: new Map(), joined: false,
  manualDisconnect: false, panel: null, layout: "gallery", focusedId: null,
  joinedAt: null, clock: null, connectionTimeout: null, preferenceTimeout: null,
  chatTimeout: null, pendingChat: null, recipientSelection: null, chatIds: new Set(), unread: 0,
  user: null, authEpoch: 0, accounts: new Map(), editedAccountId: null, accountTrigger: null,
  loginBarrier: 0, diagnosticsEpoch: 0, invitationEpoch: 0, invitationTrigger: null,
  isListener: false, serviceMode: "multilingual", duckingRatio: 0.2, isTranslating: false, captionsVisible: true, micActive: false,
};

let mediaStream = null;
let audioCaptureContext = null;
let audioCaptureNode = null;
let audioPlaybackContext = null;
let originalGainNode = null;
let translatedGainNode = null;
const mediaVideos = new Map();
const meetingMedia = new MCastTalkMedia({
  send: message => { if(state.joined && state.socket?.readyState === WebSocket.OPEN) state.socket.send(JSON.stringify(message)); },
  onError: message => showFeedback(message),
  onLocal: stream => {
    state.micActive = stream.getAudioTracks().length > 0;
    updateMicUi(state.micActive);
    voiceCapture.setTrack(INFERENCE_ENABLED ? stream.getAudioTracks()[0] : null);
    attachMeetingVideo(state.participantId, stream, true);
    byId('camera-button').setAttribute('aria-pressed', String(stream.getVideoTracks().length > 0));
    if(state.joined) renderParticipants();
  },
  onRemote: (id,stream) => { attachMeetingVideo(id,stream,false); if(state.joined) renderParticipants(); },
});
let voiceBusy = false, voiceTimer = null;
const voiceQueue = [];
function pumpVoice(){
  if(voiceBusy || state.pendingChat || !state.joined || state.socket?.readyState!==WebSocket.OPEN)return;
  while(voiceQueue.length && performance.now()-voiceQueue[0].at>20000){voiceQueue.shift();showFeedback('대기 시간이 긴 발화를 취소했습니다. 짧게 다시 말해 주세요.');}
  if(!voiceQueue.length)return;
  if(state.socket.bufferedAmount>256000){voiceQueue.length=0;showFeedback('전송이 지연되어 발화를 보내지 못했습니다. 연결을 확인하세요.');return;}
  voiceBusy=true;state.socket.send(voiceQueue.shift().frame);updateChatControls();
  elements.subtitleOrig.textContent='음성 인식·번역 처리 중…';
  voiceTimer=window.setTimeout(()=>{voiceQueue.length=0;finishVoice();showFeedback('음성 처리 응답 시간이 초과되었습니다. 호스트 상태를 확인하세요.');},185000);
}
function finishVoice(){window.clearTimeout(voiceTimer);voiceTimer=null;voiceBusy=false;updateChatControls();queueMicrotask(pumpVoice);}
const voiceCapture = new MCastTalkVoiceCapture(frame=> {
  if(!state.joined || state.socket?.readyState!==WebSocket.OPEN)return;
  if(voiceQueue.length>=2){showFeedback('음성 대기열이 가득 차 이번 발화를 보내지 못했습니다. 통역 완료 후 짧게 말해 주세요.');return;}
  voiceQueue.push({frame,at:performance.now()});pumpVoice();
},showFeedback);
const speechQueue=[];let speechAudio=null, speechBlocked=false;
byId('resume-speech-button').addEventListener('click',()=>{speechBlocked=false;byId('resume-speech-button').hidden=true;playNextSpeech();});
function playbackPreferences(){
  return {listenLanguage:elements.listenLanguage.value,secondaryOriginalLanguage:elements.secondaryOriginalLanguage.value,
    audioMode:elements.audioMode.value,duckingMode:elements.duckingMode.value};
}
function knowsOriginal(language){return MCastTalkAudioPolicy.knows(playbackPreferences(),language);}
function audioPolicy(sourceLanguage){
  if(!INFERENCE_ENABLED)return {originalVolume:elements.audioMode.value==='captions_only'?0:1,translated:false};
  return MCastTalkAudioPolicy.policy(playbackPreferences(),sourceLanguage,state.duckingRatio);
}
function updateOriginalPlayback(){
  mediaVideos.forEach((v,id)=>{
    const policy=audioPolicy(state.participants.get(id)?.inputLanguage);
    v.muted=id===state.participantId||policy.originalVolume===0;v.volume=policy.originalVolume;
  });
  if(elements.duckingDisplay)elements.duckingDisplay.textContent='모르는 원음 '+Math.round(state.duckingRatio*100)+'% · 통역/이해하는 원음 100%';
}
function playNextSpeech(){
  if(speechBlocked || speechAudio || !speechQueue.length || !state.joined)return;
  const item=speechQueue.shift();
  if(!audioPolicy(item.sourceLanguage).translated||item.audioLanguage!==elements.listenLanguage.value){queueMicrotask(playNextSpeech);return;}
  const binary=atob(item.audioWav);const bytes=Uint8Array.from(binary,c=>c.charCodeAt(0));
  const url=URL.createObjectURL(new Blob([bytes],{type:'audio/wav'}));
  const audio=new Audio(url);audio.volume=1;audio.mcastItem=item;speechAudio=audio;state.isTranslating=true;
  updateOriginalPlayback();
  const done=()=>{URL.revokeObjectURL(url);if(speechAudio!==audio)return;speechAudio=null;state.isTranslating=false;updateOriginalPlayback();playNextSpeech();};
  audio.onended=done;audio.onerror=done;audio.play().catch(()=>{
    if(speechAudio!==audio)return;
    speechBlocked=true;speechQueue.unshift(item);byId('resume-speech-button').hidden=false;
    done();showFeedback('브라우저가 자동 재생을 차단했습니다. «번역 음성 재생 허용»을 눌러 주세요.');
  });
}
fetch('/api/v1/capabilities').then(r=>r.json()).then(cap=>{
  serverCapabilities=cap;INFERENCE_ENABLED=cap.sttIntegrated&&cap.translationIntegrated&&cap.ttsIntegrated;
  byId('model-terms').hidden=!INFERENCE_ENABLED;
  elements.duckingSlider.disabled=!INFERENCE_ENABLED;
  elements.captionLabel.textContent=INFERENCE_ENABLED?'켜짐':'모델 필요';
  byId('indicator-sub').textContent=INFERENCE_ENABLED?'로컬 모델 준비·성능 측정 중':'통역 모델 미설정';
  elements.captionsButton.disabled=!INFERENCE_ENABLED;
  updateOriginalPlayback();
  elements.captionsButton.title=INFERENCE_ENABLED?'기계 통역 자막 켜기/끄기':'로컬 모델 설정이 필요합니다';
  elements.subtitleOrig.textContent=INFERENCE_ENABLED?'마이크를 켜고 짧게 말하세요.':'로컬 음성 모델을 설정하세요.';
  elements.subtitleTrans.textContent=INFERENCE_ENABLED?'AI 통역 · 중요한 내용은 원문과 확인하세요.':'원음·영상·원문 채팅 사용 가능';
  byId('form-note').textContent=cap.lanHttps?'LAN HTTPS 접속 가능 · 참가 기기에 인증서 신뢰 설정이 필요합니다.':'이 PC 전용입니다. 다른 기기는 호스트의 LAN HTTPS 설정 후 초대하세요.';
}).catch(()=>showFeedback('호스트 기능 상태를 확인하지 못했습니다.'));
function attachMeetingVideo(id,stream,local) {
  if(!stream) { const v=mediaVideos.get(id); if(v) v.srcObject=null; mediaVideos.delete(id); return; }
  let video=mediaVideos.get(id);
  if(!video) { video=document.createElement('video'); video.autoplay=true;video.playsInline=true;video.className='meeting-video';mediaVideos.set(id,video); }
  if(video.srcObject!==stream) video.srcObject=stream;
  updateOriginalPlayback();
  video.play().catch(()=>{});
}
byId('camera-button').addEventListener('click',()=>meetingMedia.toggle('video'));
byId('share-button').addEventListener('click',()=>meetingMedia.toggle('video',true));
function applyAudioPreferences() {
  for(let i=speechQueue.length-1;i>=0;i--)if(!audioPolicy(speechQueue[i].sourceLanguage).translated||speechQueue[i].audioLanguage!==elements.listenLanguage.value)speechQueue.splice(i,1);
  if(speechAudio&&(!audioPolicy(speechAudio.mcastItem?.sourceLanguage).translated||speechAudio.mcastItem?.audioLanguage!==elements.listenLanguage.value)) {
    speechAudio.pause();URL.revokeObjectURL(speechAudio.src);speechAudio=null;state.isTranslating=false;
  }
  updateOriginalPlayback();mediaVideos.forEach(v=>v.play().catch(()=>{}));
  if(!speechQueue.length){speechBlocked=false;byId('resume-speech-button').hidden=true;}
  playNextSpeech();
}
elements.audioMode.addEventListener('change',applyAudioPreferences);
elements.duckingMode.addEventListener('change',applyAudioPreferences);
for(const control of [elements.listenLanguage,elements.secondaryOriginalLanguage])control.addEventListener('change',applyAudioPreferences);
for(const control of preferenceControls)control.addEventListener('change',renderCapacity);

function capacityDescription(c){
  if(!c)return '측정 결과 없음';
  if(c.calibrationStatus!=='measured')return c.calibrationStatus==='pending'?'모델 준비·실측 중 · 원음/영상 사용 가능':'실행 장치가 변경됐습니다. 서버 재시작 후 재측정이 필요합니다.';
  const memory=Number.isFinite(c.availableMemoryBytes)?(c.availableMemoryBytes/1073741824).toFixed(1)+' GiB':'확인 불가';
  return '설치 모델 지원 '+c.supportedLanguages.length+'개 · 상호 통역 권장 최대 '+c.recommendedMeetingLanguages+'개 언어 · 운영 허용 한도 '+c.effectiveMeetingLanguageLimit+'개 · '+(c.mode==='quality-first'?'품질 우선':'고급 지연 허용')+
    ' · '+c.backend+' · 사용 가능 메모리 '+memory+' · '+c.logicalProcessors+' 논리 CPU. '+
    (c.recommendedInterpretedLanguages===0?'현재 측정으로는 목표 5초 이내 통역을 권장할 수 없습니다. ':'')+
    (c.memoryPressure?'메모리 부족 경고. ':'')+
    (c.lastRequestOverTarget?'최근 처리가 목표 시간을 초과했습니다. ':'')+
    (c.originalCaptionOverTarget?'원문 자막도 목표 지연을 초과할 수 있습니다. 원음·영상은 별도로 전달됩니다. ':'')+
    '운영 허용 개수는 모든 통역 조합의 성능 보장이 아닙니다. 서로 이해하는 언어는 원음으로 사용하며, 통역이 필요한 참가·변경은 사전에 부하를 검사합니다. '+
    '3초 합성 발화 '+c.calibrationSamples+'건과 최근 실측 기반 추정이며 지연 보장은 아닙니다.';
}
function renderCapacity(){
  const c=capacityReport?.capacity;
  byId('capacity-summary').textContent=capacityReport?.inferenceStatus==='not-configured'?'통역 모델 미설정 · 원음/영상 사용 가능':
    capacityReport?.inferenceStatus==='warmup-failed'?'모델 준비/성능 측정 실패 · 통역 사용 불가. 호스트 설정을 확인하세요.':capacityDescription(c);
  byId('capacity-operator-summary').textContent=capacityDescription(c);
  renderCapacityChoices();
  if(!c||c.calibrationStatus!=='measured'){byId('capacity-demand').textContent='측정 완료 전에는 통역 권장 수를 확정하지 않습니다.';return;}
  const source=elements.inputLanguage.value, translations=new Set(), audio=new Set();
  const own={...joinPayload('UPDATE_PREFERENCES'),participantId:state.participantId};
  const people=state.joined?[...state.participants.values()].filter(p=>p.participantId!==state.participantId).concat(own):[own];
  for(const p of people){
    if(!MCastTalkAudioPolicy.knows(p,source))translations.add(p.displayLanguage);
    if(p.participantId!==state.participantId&&MCastTalkAudioPolicy.policy(p,source).translated){audio.add(p.listenLanguage);translations.add(p.listenLanguage);}
  }
  translations.add(own.publishLanguage);translations.delete(source);
  let stage=Number(c.asrMs)||0;
  for(const l of translations)stage+=c.languageCosts[l]?.translationMs||0;
  for(const l of audio)stage+=c.languageCosts[l]?.speechMs||0;
  const estimate=800+Math.ceil(1.2*stage);
  const plannedLanguages=new Set(people.flatMap(p=>[p.inputLanguage,p.publishLanguage,p.listenLanguage,p.displayLanguage]).filter(Boolean));
  const excluded=[...plannedLanguages].filter(l=>c.selectedLanguages.length&&!c.selectedLanguages.includes(l));
  byId('capacity-demand').textContent=(state.joined?'현재 참석자 기준 내 발화':'참가 전 내 설정 기준')+': 통역 출력 '+translations.size+'개 ('+
    ([...translations].map(languageName).join(', ')||'없음')+') · 음성 합성 '+audio.size+'개 · 3초 발화 예상 '+(estimate/1000).toFixed(1)+'초. '+
    (excluded.length?'운영 설정에서 허용하지 않은 언어: '+excluded.map(languageName).join(', ')+'. ':'')+
    (translations.size===0?'통역 없음 · 원음/영상은 자막 처리와 별개입니다. '+(estimate>c.targetLatencyMs?'원문 자막은 목표보다 늦을 수 있습니다. ':''):
      estimate>c.targetLatencyMs?'목표 초과: 언어를 줄이거나 원음 유지 옵션을 확인하세요.':'짧은 단독 발화 기준 추정입니다. 다른 발화·채팅·앱 부하가 있으면 늘어납니다.');
}
function renderCapacityChoices(){
  const c=capacityReport?.capacity;if(!c)return;
  const select=byId('capacity-count'),value=select.value,delay=byId('capacity-mode').value==='allow-delay';
  select.replaceChildren(new Option('자동 · 설치 언어 중 최대 5개, 통역 부하 별도 검사','0'));
  for(const item of c.countOptions){
    const option=new Option(item.count+'개 언어'+(!item.modelSupported?' · 언어 모델 추가 필요':!item.recommended?(delay?' · 자원 권장 초과':' · 원음 조건부 / 통역 사전검사'):''),String(item.count));
    option.disabled=!item.modelSupported;select.add(option);
  }
  select.value=value;if(!select.value)select.value='0';
  const limit=Number(select.value)||Math.min(5,c.supportedLanguages.length);
  const checked=document.querySelectorAll('input[name="capacity-language"]:checked').length;
  document.querySelectorAll('input[name="capacity-language"]').forEach(e=>{e.disabled=!e.checked&&checked>=limit;});
  byId('capacity-selection-status').textContent='선택 '+checked+' / '+limit+'개. '+(checked>limit?'개수를 줄여야 적용할 수 있습니다. ':'')+
    (!delay&&limit>c.recommendedMeetingLanguages?'상호 통역 권장 '+c.recommendedMeetingLanguages+'개 초과: 원음 조건부 허용입니다. 미이해 언어의 통역 부하가 초과하면 참가·변경 전에 거부됩니다. ':'')+
    '허용 개수는 동시 통역 보장이 아닙니다. 5·7·10개는 언어 모델 추가와 부하 검증이 필요하며 기존 참가자의 통역을 임의로 제외하지 않습니다.';
}
byId('capacity-count').addEventListener('change',renderCapacityChoices);
byId('capacity-mode').addEventListener('change',renderCapacityChoices);
document.querySelectorAll('input[name="capacity-language"]').forEach(e=>e.addEventListener('change',renderCapacityChoices));
async function preflightLanguagePlan(listener=false){
  if(!INFERENCE_ENABLED)return true;
  if(languagePreflightBusy)return false;
  languagePreflightBusy=true;const epoch=state.authEpoch;
  try{await api('/api/v1/language-plan',{method:'POST',body:{...joinPayload('JOIN_ROOM'),roomId:elements.roomId.value,listener}});return epoch===state.authEpoch&&Boolean(state.user);}
  catch(error){if(epoch===state.authEpoch){showFeedback(error.detail||accountError(error));elements.preferenceStatus.textContent='언어 설정을 확인하세요. 회의 참가 전 자원 한도 검사에서 적용되지 않았습니다.';}return false;}
  finally{languagePreflightBusy=false;}
}
async function refreshCapacity(){
  if(!state.user||capacityBusy)return;
  capacityBusy=true;const epoch=state.authEpoch;
  try{
    const result=await api('/api/v1/language-capacity');
    if(epoch!==state.authEpoch||!state.user)return;
    capacityReport=result;renderCapacity();
    byId('indicator-sub').textContent=result.inferenceStatus==='ready'?'로컬 통역 준비됨 · 자원 권장 확인':
      result.inferenceStatus==='processing'?'로컬 통역 처리 중':result.inferenceStatus==='warming-up'?'모델 준비·성능 측정 중':'통역 상태: '+result.inferenceStatus;
  }catch(error){if(epoch===state.authEpoch){capacityReport=null;byId('capacity-summary').textContent='자원 권장 상태를 확인하지 못했습니다. 연결 상태를 확인하세요.';}}
  finally{capacityBusy=false;}
}
window.setInterval(()=>{if(!document.hidden)refreshCapacity();},5000);
byId('capacity-form').addEventListener('submit',async event=>{
  event.preventDefault();if(state.user?.role!=='ADMIN')return;
  const epoch=state.authEpoch;byId('capacity-save').disabled=true;
  try{
    const languages=[...document.querySelectorAll('input[name="capacity-language"]:checked')].map(e=>e.value).join(',');
    await api('/api/v1/admin/language-capacity',{method:'POST',body:{languages,meetingLanguageLimit:Number(byId('capacity-count').value),allowDelay:byId('capacity-mode').value==='allow-delay',acknowledgeDelay:byId('capacity-ack').checked}});
    if(epoch!==state.authEpoch)return;
    byId('capacity-save-status').textContent='통역 정책을 이번 서버 실행에 적용했습니다.';byId('capacity-ack').checked=false;await refreshCapacity();
  }catch(error){if(epoch===state.authEpoch)byId('capacity-save-status').textContent=error.detail||accountError(error);}
  finally{byId('capacity-save').disabled=false;}
});
const invitation = MCastTalkInvitation.parseEntry(window.location.pathname, window.location.search);
renderInvitationNotice();
byId("invite-prejoin-button").addEventListener("click", event => openInvitation(event.currentTarget));
byId("invite-button").addEventListener("click", event => openInvitation(event.currentTarget));
byId("close-invite-button").addEventListener("click", () => closeInvitation());
byId("invite-dialog").addEventListener("close", () => {
  clearInvitation();
  const trigger = state.invitationTrigger;
  if (state.user && trigger && trigger.getClientRects().length && !trigger.disabled) trigger.focus();
});
byId("copy-invite-button").addEventListener("click", () => copyInvitation());
byId("invite-manage-accounts").addEventListener("click", () => {
  if (!state.user || state.user.role !== "ADMIN") return;
  state.invitationTrigger = null;
  closeInvitation();
  openAccountDialog(true);
});

elements.form.addEventListener("submit", async (event) => {
  event.preventDefault();
  elements.displayName.value = elements.displayName.value.trim();
  elements.roomId.value = elements.roomId.value.trim();
  if (!elements.form.reportValidity() || state.socket) return;
  if (await refreshSession() && !state.socket) {
    if (invitation.listener) connectListener(elements.roomId.value); else connect();
  }
});
elements.displayName.addEventListener("input", () => {
  byId("preview-avatar").textContent = initials(elements.displayName.value || "나");
  byId("preview-name").textContent = elements.displayName.value.trim() || "내 참가 화면";
});
elements.disconnectButton.addEventListener("click", () => {
  stopMicrophone();
  state.manualDisconnect = true;
  if (state.socket) state.socket.close(1000, "user left");
});
preferenceControls.forEach((control) => control.addEventListener("change", sendPreferenceUpdate));
Object.entries(panels).forEach(([key, panel]) => {
  panel.button.addEventListener("click", () => setPanel(state.panel === key ? null : key));
});
byId("close-panel-button").addEventListener("click", () => setPanel(null));
byId("gallery-button").addEventListener("click", () => setLayout("gallery"));
byId("speaker-button").addEventListener("click", () => setLayout("speaker"));
byId("dismiss-feedback").addEventListener("click", () => {
  byId("feedback").hidden = true;
  (state.joined ? byId("meeting-heading") : state.user ? elements.joinButton : byId("login-username")).focus();
});
if (elements.duckingSlider) {
  elements.duckingSlider.addEventListener("input", (e) => {
    state.duckingRatio = Number(e.target.value) / 100;
    updateGainDucking();
  });
}
if (elements.micButton) {
  elements.micButton.addEventListener("click", () => meetingMedia.toggle('audio'));
}
if (elements.captionsButton) {
  elements.captionsButton.addEventListener("click", () => {
    state.captionsVisible = !state.captionsVisible;
    const card = byId("subtitle-card");
    if (card) card.hidden = !state.captionsVisible;
    if (elements.captionLabel) elements.captionLabel.textContent = state.captionsVisible ? "켜짐" : "꺼짐";
  });
}
async function triggerQuickListener() {
  if (state.socket || !await refreshSession() || state.socket) return;
  const roomId = elements.roomId.value.trim() || "room-101";
  if (!MCastTalkInvitation.validRoom(roomId)) {
    showFeedback("올바른 회의실 ID를 입력하세요.");
    return;
  }
  connectListener(roomId);
}
if (elements.listenerQuickButton) elements.listenerQuickButton.addEventListener("click", triggerQuickListener);
if (elements.loginListenerButton) elements.loginListenerButton.addEventListener("click", triggerQuickListener);
if (elements.copyListenerButton && elements.listenerInviteLink) {
  elements.copyListenerButton.addEventListener("click", () => copyInvitation(true));
}
document.querySelectorAll(".service-tab").forEach((tab) => {
  tab.addEventListener("click", () => {
    document.querySelectorAll(".service-tab").forEach(t => {
      t.classList.remove("is-active");
      t.setAttribute("aria-selected", "false");
    });
    tab.classList.add("is-active");
    tab.setAttribute("aria-selected", "true");
    state.serviceMode = tab.dataset.mode || "multilingual";
    if (state.joined) sendPreferenceUpdate();
  });
});
elements.chatForm.addEventListener("submit", (event) => { event.preventDefault(); sendChat(); });
elements.chatInput.addEventListener("input", updateChatControls);
elements.recipient.addEventListener("change", selectRecipient);
byId("participant-search").addEventListener("input", renderParticipants);
elements.chatInput.addEventListener("keydown", (event) => {
  if (event.key === "Enter" && !event.shiftKey && !event.isComposing && event.keyCode !== 229) {
    event.preventDefault();
    sendChat();
  }
});
document.addEventListener("keydown", (event) => {
  if (!state.joined || byId("account-dialog").open || byId("invite-dialog").open) return;
  if (event.key === "Escape" && state.panel) { setPanel(null); event.preventDefault(); }
  if (!event.altKey || event.ctrlKey || event.metaKey || event.isComposing) return;
  const key = event.key.toLowerCase();
  if (key === "p" || key === "l") {
    const panel = key === "p" ? "participants" : "preferences";
    setPanel(state.panel === panel ? null : panel);
    event.preventDefault();
  }
});
window.addEventListener("beforeunload", () => {
  stopMicrophone();
  if (state.socket) state.socket.close(1000, "page closed");
});

function createParticipantId() {
  return "p-" + (window.crypto && typeof window.crypto.randomUUID === "function"
    ? window.crypto.randomUUID() : Date.now().toString(36) + "-" + Math.random().toString(36).slice(2));
}

function renderInvitationNotice() {
  const notice = byId("invitation-notice");
  const target = MCastTalkInvitation.resolve(invitation, state.user);
  notice.hidden = state.joined || (!invitation.invalid && !invitation.roomId);
  notice.textContent = invitation.invalid
    ? "초대 링크의 회의실 정보가 올바르지 않습니다. 보낸 사람에게 새 링크를 요청하세요."
    : target.blocked
      ? "이 초대 회의실에 대한 게스트 권한이 없습니다. 관리자에게 문의하세요. 아래에서는 계정에 지정된 회의실만 참가할 수 있습니다."
      : invitation.roomId
        ? "초대받은 회의실: " + invitation.roomId + " · " + (state.user ? "언어 설정을 확인한 뒤 회의 참가를 누르세요." : "로그인한 뒤 참가할 수 있습니다. 계정이 없으면 관리자에게 발급을 요청하세요.")
        : "";
}

function openInvitation(trigger) {
  if (!state.user || (state.socket && !state.joined)) return;
  const roomId = elements.roomId.value.trim();
  if (!MCastTalkInvitation.validRoom(roomId) || (state.user.role === "GUEST" && state.user.guestRoomId !== roomId)) {
    showFeedback("초대할 수 있는 회의실 ID를 확인하세요. 게스트는 지정된 회의실만 사용할 수 있습니다.");
    return;
  }
  let link;
  const invitationOrigin=serverCapabilities.lanHttps && serverCapabilities.lanHosts?.length
    ? 'https://'+serverCapabilities.lanHosts[0]+':'+window.location.port : window.location.origin;
  try { link = MCastTalkInvitation.build(invitationOrigin, roomId, serverCapabilities.lanHosts); }
  catch (_error) { showFeedback("현재 호스트에서 초대 링크를 만들 수 없습니다."); return; }
  state.invitationEpoch += 1;
  state.invitationTrigger = trigger;
  byId("invite-room-label").textContent = "회의실 · " + roomId;
  byId("invite-link").value = link;
  if (elements.listenerInviteLink) {
    try {
      const lUrl = new URL(invitationOrigin);
      lUrl.pathname = "/listen/" + encodeURIComponent(roomId);
      elements.listenerInviteLink.value = lUrl.toString();
    } catch (_e) {
      elements.listenerInviteLink.value = "";
    }
  }
  byId("invite-status").textContent = "계정 정보는 링크에 포함되지 않습니다.";
  byId('invite-scope').textContent=serverCapabilities.lanHttps?'같은 LAN의 초대 링크입니다. 처음 접속할 때 호스트 인증서 지문을 확인하고 기기에 신뢰 설정하세요.':'이 PC에서만 사용할 수 있습니다. 다른 기기는 LAN HTTPS 설정이 필요합니다.';
  byId("copy-invite-button").disabled = false;
  if (elements.copyListenerButton) elements.copyListenerButton.disabled = false;
  byId("invite-manage-accounts").hidden = state.user.role !== "ADMIN";
  byId("invite-dialog").showModal();
  byId("invite-link").focus();
  byId("invite-link").select();
}

function clearInvitation() {
  state.invitationEpoch += 1;
  byId("invite-link").value = "";
  if (elements.listenerInviteLink) elements.listenerInviteLink.value = "";
  byId("invite-room-label").textContent = "";
  byId("invite-status").textContent = "";
  byId("invite-manage-accounts").hidden = true;
  byId("copy-invite-button").disabled = false;
  if (elements.copyListenerButton) elements.copyListenerButton.disabled = false;
}

function closeInvitation() {
  clearInvitation();
  if (byId("invite-dialog").open) byId("invite-dialog").close();
}

async function copyInvitation(listener = false) {
  const field = listener ? elements.listenerInviteLink : byId("invite-link");
  const button = listener ? elements.copyListenerButton : byId("copy-invite-button");
  if (!state.user || !byId("invite-dialog").open || !field.value) return;
  const epoch = state.invitationEpoch;
  const current = () => epoch === state.invitationEpoch && state.user && byId("invite-dialog").open;
  button.disabled = true;
  try {
    if (!navigator.clipboard?.writeText) throw new Error("Clipboard unavailable");
    await navigator.clipboard.writeText(field.value);
    if (current()) byId("invite-status").textContent = "초대 링크를 복사했습니다. 받는 사람에게 전달하세요.";
  } catch (_error) {
    if (current()) {
      field.focus(); field.select();
      byId("invite-status").textContent = "자동 복사를 사용할 수 없습니다. 선택된 링크를 Ctrl+C로 복사하세요.";
    }
  } finally {
    if (current()) button.disabled = false;
  }
}

async function connect() {
  if (!state.user) return;
  if(INFERENCE_ENABLED&&!byId('model-terms-accepted').checked){showFeedback('음성 모델 이용 조건을 먼저 확인해 주세요.');return;}
  if(state.socket||!await preflightLanguagePlan(false)||state.socket)return;
  byId("feedback").hidden = true;
  setConnection("connecting", "연결 중");
  setFormBusy(true);
  state.manualDisconnect = false;
  state.isListener = false;
  const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
  let socket;
  try {
    socket = new WebSocket(protocol + "//" + window.location.host + "/ws/v1/rooms/" + encodeURIComponent(elements.roomId.value));
  } catch (_error) {
    setFormBusy(false);
    setConnection("error", "연결 실패");
    showFeedback("회의실에 연결할 수 없습니다. 호스트가 실행 중인지 확인하세요.");
    return;
  }
  socket.binaryType = "arraybuffer";
  state.socket = socket;
  state.connectionTimeout = window.setTimeout(() => {
    if (state.socket !== socket || state.joined) return;
    showFeedback("서버의 참가 응답이 늦어지고 있습니다. 호스트 상태를 확인하고 다시 참가하세요.");
    socket.close(1000, "join timed out");
  }, 12000);
  socket.addEventListener("open", () => {
    if (state.socket === socket) socket.send(JSON.stringify(joinPayload("JOIN_ROOM")));
  });
  socket.addEventListener("message", (event) => {
    if (state.socket !== socket) return;
    if (event.data instanceof ArrayBuffer) {
      handleIncomingAudioBinary(event.data);
    } else {
      handleServerMessage(event.data);
    }
  });
  socket.addEventListener("error", () => {
    if (state.socket !== socket) return;
    setConnection("error", "연결 오류");
    showFeedback("서버에 연결할 수 없습니다. 호스트 상태와 회의실 주소를 확인하세요.");
  });
  socket.addEventListener("close", async (event) => {
    if (state.socket !== socket) return;
    stopMicrophone();
    const wasJoined = state.joined;
    const manual = state.manualDisconnect;
    state.socket = null;
    state.joined = false;
    renderInvitationNotice();
    state.presenceId = null;
    closeInvitation();
    window.clearTimeout(state.connectionTimeout);
    window.clearTimeout(state.preferenceTimeout);
    window.clearTimeout(state.chatTimeout);
    window.clearInterval(state.clock);
    state.pendingChat = null;
    state.participants.clear();
    state.focusedId = null;
    setPanel(null, false);
    renderParticipants();
    elements.meeting.hidden = true;
    elements.prejoin.hidden = !state.user;
    byId("prejoin-preferences").append(byId("language-preferences"));
    elements.preferenceStatus.textContent = "";
    setFormBusy(false);
    setConnection("offline", "연결 안 됨");
    clearChat();
    document.title = "MCastTalk · 회의";
    if (state.user) elements.joinButton.focus();
    announce(manual ? "회의에서 나왔습니다." : "연결이 종료되었습니다.");
    if (wasJoined && !manual) showFeedback("서버 연결이 종료되어 회의에서 나왔습니다. 설정을 확인한 뒤 다시 참가하세요.");
    if (!manual || event.code === 1008) await refreshSession({ quiet: true });
  });
}

async function connectListener(roomId) {
  if(INFERENCE_ENABLED&&!byId('model-terms-accepted').checked){showFeedback('음성 모델 이용 조건을 먼저 확인해 주세요.');return;}
  if (!state.user || state.socket || !MCastTalkInvitation.validRoom(roomId)) return;
  if (state.user.role === "GUEST" && state.user.guestRoomId !== roomId) {
    showFeedback("이 회의실에 대한 게스트 권한이 없습니다."); return;
  }
  elements.roomId.value=roomId;
  if(!await preflightLanguagePlan(true)||state.socket)return;
  state.isListener = true;
  byId("feedback").hidden = true;
  setConnection("connecting", "청취 연결 중");
  setFormBusy(true);
  state.manualDisconnect = false;
  elements.roomId.value = roomId;
  const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
  let socket;
  try {
    socket = new WebSocket(protocol + "//" + window.location.host + "/ws/v1/rooms/" + encodeURIComponent(roomId) + "/listen");
  } catch (_error) {
    setFormBusy(false);
    setConnection("error", "연결 실패");
    showFeedback("회의실 청취 스트림에 연결할 수 없습니다.");
    return;
  }
  socket.binaryType = "arraybuffer";
  state.socket = socket;
  state.connectionTimeout = window.setTimeout(() => {
    if (state.socket !== socket || state.joined) return;
    showFeedback("서버의 청취 참가 응답이 늦어지고 있습니다.");
    socket.close(1000, "listener join timed out");
  }, 12000);
  socket.addEventListener("open", () => {
    if (state.socket === socket) socket.send(JSON.stringify(joinPayload("JOIN_ROOM")));
  });
  socket.addEventListener("message", (event) => {
    if (state.socket !== socket) return;
    if (event.data instanceof ArrayBuffer) {
      handleIncomingAudioBinary(event.data);
    } else {
      handleServerMessage(event.data);
    }
  });
  socket.addEventListener("error", () => {
    if (state.socket !== socket) return;
    setConnection("error", "연결 오류");
    showFeedback("청취 스트림 연결 오류");
  });
  socket.addEventListener("close", async (event) => {
    if (state.socket !== socket) return;
    stopMicrophone();
    const wasJoined = state.joined;
    const manual = state.manualDisconnect;
    state.socket = null;
    state.joined = false;
    state.isListener = false;
    renderInvitationNotice();
    state.presenceId = null;
    closeInvitation();
    window.clearTimeout(state.connectionTimeout);
    window.clearTimeout(state.preferenceTimeout);
    window.clearTimeout(state.chatTimeout);
    window.clearInterval(state.clock);
    state.pendingChat = null;
    state.participants.clear();
    state.focusedId = null;
    setPanel(null, false);
    renderParticipants();
    elements.meeting.hidden = true;
    elements.prejoin.hidden = !state.user;
    byId("prejoin-preferences").append(byId("language-preferences"));
    elements.preferenceStatus.textContent = "";
    setFormBusy(false);
    setConnection("offline", "연결 안 됨");
    clearChat();
    document.title = "MCastTalk · 회의";
    if (state.user) elements.joinButton.focus();
    announce(manual ? "청취를 종료했습니다." : "청취 연결이 종료되었습니다.");
    if (wasJoined && !manual) showFeedback("회의 청취 연결이 종료되었습니다.");
    if (!manual || event.code === 1008) await refreshSession({ quiet: true });
  });
}

function joinPayload(type) {
  return {
    type,
    aiTermsAccepted: INFERENCE_ENABLED && byId('model-terms-accepted').checked,
    participantId: state.participantId,
    displayName: elements.displayName.value.trim() || (state.isListener ? "청취자" : "참가자"),
    inputLanguage: elements.inputLanguage.value,
    publishLanguage: elements.publishLanguage.value === "auto" ? elements.inputLanguage.value : elements.publishLanguage.value,
    listenLanguage: elements.listenLanguage.value,
    secondaryOriginalLanguage: elements.secondaryOriginalLanguage.value===elements.listenLanguage.value?'':elements.secondaryOriginalLanguage.value,
    displayLanguage: elements.displayLanguage.value,
    audioMode: elements.audioMode.value,
    duckingMode: elements.duckingMode ? elements.duckingMode.value : "ducked_original",
    serviceMode: state.serviceMode,
    role: state.isListener ? "listener" : "speaker",
  };
}

function initAudioPlayback() {
  if (audioPlaybackContext) return;
  try {
    audioPlaybackContext = new (window.AudioContext || window.webkitAudioContext)({ sampleRate: 16000 });
    originalGainNode = audioPlaybackContext.createGain();
    translatedGainNode = audioPlaybackContext.createGain();
    updateGainDucking();
    originalGainNode.connect(audioPlaybackContext.destination);
    translatedGainNode.connect(audioPlaybackContext.destination);
  } catch (e) {
    console.warn("Web Audio API not supported", e);
  }
}

function updateGainDucking() {
  updateOriginalPlayback();
  if (!originalGainNode || !translatedGainNode) return;
  // Antigravity handoff: untranslated original audio must not be attenuated.
  originalGainNode.gain.value = state.duckingRatio;
  translatedGainNode.gain.value = 1.0;
}

function handleIncomingAudioBinary(arrayBuffer) {
  if (!MEDIA_ENABLED || !state.user || !state.joined) return;
  initAudioPlayback();
  if (!audioPlaybackContext) return;
  if (audioPlaybackContext.state === "suspended") {
    audioPlaybackContext.resume();
  }
  const int16 = new Int16Array(arrayBuffer);
  if (int16.length === 0) return;
  const float32 = new Float32Array(int16.length);
  for (let i = 0; i < int16.length; i++) {
    float32[i] = int16[i] / (int16[i] < 0 ? 0x8000 : 0x7FFF);
  }
  const audioBuffer = audioPlaybackContext.createBuffer(1, float32.length, 16000);
  audioBuffer.getChannelData(0).set(float32);

  const source = audioPlaybackContext.createBufferSource();
  source.buffer = audioBuffer;
  source.connect(originalGainNode);
  source.start();
}

async function toggleMicrophone() {
  if (state.micActive) {
    stopMicrophone();
  } else {
    await startMicrophone();
  }
}

async function startMicrophone() {
  if (!MEDIA_ENABLED || !state.user || !state.joined || !state.socket) {
    showFeedback("음성 송출은 미디어 검증 후 제공합니다."); return;
  }
  if (state.isListener) {
    showFeedback("청취자 모드에서는 마이크를 사용할 수 없습니다.");
    return;
  }
  if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
    showFeedback("브라우저에서 마이크를 지원하지 않습니다.");
    return;
  }
  const captureSocket = state.socket;
  const captureEpoch = state.authEpoch;
  try {
    const stream = await navigator.mediaDevices.getUserMedia({
      audio: {
        echoCancellation: true,
        noiseSuppression: true,
        autoGainControl: true,
        sampleRate: 16000,
      }
    });
    // Preserve Antigravity's pending-permission cleanup; also reject a replacement room/session.
    if (!state.joined || state.socket !== captureSocket || state.authEpoch !== captureEpoch || state.socket.readyState !== WebSocket.OPEN) {
      stream.getTracks().forEach(t => t.stop());
      return;
    }
    mediaStream = stream;
    if (!audioCaptureContext) {
      audioCaptureContext = new (window.AudioContext || window.webkitAudioContext)({ sampleRate: 16000 });
    }
    if (audioCaptureContext.state === "suspended") {
      await audioCaptureContext.resume();
    }
    const source = audioCaptureContext.createMediaStreamSource(stream);
    const processor = audioCaptureContext.createScriptProcessor(4096, 1, 1);
    processor.onaudioprocess = (e) => {
      if (!state.micActive || !state.socket || state.socket.readyState !== WebSocket.OPEN) return;
      const input = e.inputBuffer.getChannelData(0);
      const int16 = new Int16Array(input.length);
      for (let i = 0; i < input.length; i++) {
        const s = Math.max(-1, Math.min(1, input[i]));
        int16[i] = s < 0 ? s * 0x8000 : s * 0x7FFF;
      }
      state.socket.send(int16.buffer);
    };
    source.connect(processor);
    processor.connect(audioCaptureContext.destination);
    audioCaptureNode = { source, processor };
    state.micActive = true;
    updateMicUi(true);
    appendEvent("마이크 켜짐", "실시간 음성 전송이 시작되었습니다.");
  } catch (err) {
    stopMicrophone();
    showFeedback("마이크 연결 실패: " + (err.message || err.name));
  }
}

function stopMicrophone() {
  voiceCapture.close();window.clearTimeout(voiceTimer);voiceBusy=false;voiceQueue.length=0;speechQueue.length=0;speechBlocked=false;byId('resume-speech-button').hidden=true;
  if(speechAudio){speechAudio.pause();URL.revokeObjectURL(speechAudio.src);speechAudio=null;}state.isTranslating=false;
  meetingMedia.close();
  mediaVideos.forEach(video=>{video.srcObject=null;}); mediaVideos.clear();
  state.micActive = false;
  if (mediaStream) {
    mediaStream.getTracks().forEach(t => t.stop());
    mediaStream = null;
  }
  if (audioCaptureNode) {
    try {
      audioCaptureNode.source.disconnect();
      audioCaptureNode.processor.disconnect();
    } catch (_e) {}
    audioCaptureNode = null;
  }
  updateMicUi(false);
  appendEvent("마이크 꺼짐", "음성 전송이 중지되었습니다.");
}

function updateMicUi(active) {
  if (!elements.micButton) return;
  if (active) {
    elements.micButton.classList.add("is-recording");
    if (elements.micLabel) elements.micLabel.textContent = "송출 중";
  } else {
    elements.micButton.classList.remove("is-recording");
    if (elements.micLabel) elements.micLabel.textContent = "꺼짐";
  }
}

function handleSubtitleChunk(chunk) {
  if (!INFERENCE_ENABLED || !state.user || !state.joined) return;
  if (elements.subtitleOrig && chunk.originalText) {
    elements.subtitleOrig.textContent = chunk.originalText;
  }
  if (elements.subtitleTrans && chunk.translatedText) {
    elements.subtitleTrans.textContent = chunk.translatedText;
    elements.subtitleTrans.classList.add("highlight-pulse");
    window.setTimeout(() => elements.subtitleTrans.classList.remove("highlight-pulse"), 400);
  }
  if (elements.subtitleLangChip && chunk.sourceLanguage && chunk.targetLanguage) {
    const src = languageNames.get(chunk.sourceLanguage) || chunk.sourceLanguage;
    const tgt = languageNames.get(chunk.targetLanguage) || chunk.targetLanguage;
    elements.subtitleLangChip.textContent = src + " ➔ " + tgt + (Number.isFinite(chunk.elapsedMs)?' · 처리 '+(chunk.elapsedMs/1000).toFixed(1)+'초':'');
  }
}

function sendPreferenceUpdate() {
  if (!state.joined || !state.socket || state.socket.readyState !== WebSocket.OPEN) return;
  state.socket.send(JSON.stringify(joinPayload("UPDATE_PREFERENCES")));
  elements.preferenceStatus.textContent = "언어 설정 저장 중…";
  window.clearTimeout(state.preferenceTimeout);
  state.preferenceTimeout = window.setTimeout(() => {
    elements.preferenceStatus.textContent = "설정 확인이 지연됩니다. 연결 상태를 확인하세요.";
  }, 8000);
}

function handleServerMessage(rawMessage) {
  if (!state.user && !state.isListener) return;
  let message;
  try { message = JSON.parse(rawMessage); } catch (_error) {
    showFeedback("서버 응답을 읽을 수 없습니다. 다시 참가해 주세요.");
    return;
  }
  if (!message || typeof message !== "object") return;
  if (message.type === "ROOM_JOINED" && !state.joined) {
    const participants = (Array.isArray(message.participants) ? message.participants : []).filter(isParticipant);
    const self = participants.find((participant) => participant.participantId === state.participantId);
    if (!self) {
      showFeedback("서버의 참가 확인 정보를 검증하지 못했습니다. 호스트를 업데이트한 뒤 다시 참가하세요.");
      if (state.socket) state.socket.close(1002, "missing participant presence");
      return;
    }
    window.clearTimeout(state.connectionTimeout);
    state.joined = true;
    renderInvitationNotice();
    state.presenceId = self.presenceId;
    meetingMedia.join(state.participantId, state.isListener);
    state.joinedAt = performance.now();
    state.participants.clear();
    participants.forEach((participant) => state.participants.set(participant.participantId, participant));
    elements.conversationFeed.replaceChildren();
    clearChat();
    elements.prejoin.hidden = true;
    elements.meeting.hidden = false;
    byId("meeting-preferences").append(byId("language-preferences"));
    preferenceControls.forEach((control) => { control.disabled = false; });
    byId("meeting-heading").textContent = elements.roomId.value;
    document.title = elements.roomId.value + " · MCastTalk";
    setConnection("online", state.isListener ? "청취 모드 연결됨" : "회의실 연결됨");
    if (elements.roleBadge) {
      elements.roleBadge.textContent = state.isListener ? "읽기 전용 참가자" : "회의 참가자";
    }
    if (elements.micButton) {
      elements.micButton.disabled = !MEDIA_ENABLED || state.isListener;
      if (state.isListener && elements.micLabel) elements.micLabel.textContent = "청취 전용";
    }
    byId('camera-button').disabled = !MEDIA_ENABLED || state.isListener;
    byId('share-button').disabled = !MEDIA_ENABLED || state.isListener;
    renderParticipants();
    updateClock();
    state.clock = window.setInterval(updateClock, 1000);
    appendEvent("회의 참가", "카메라·마이크는 직접 켜세요. 같은 LAN의 미디어 연결을 사용합니다. 자동 통역은 로컬 모델 준비 상태를 확인하세요.");
    byId("meeting-heading").focus();
    announce("회의실에 참가했습니다. 참석자 " + state.participants.size + "명.");
    return;
  }
  if (!state.joined && message.type !== "ERROR") return;
  if (message.type === 'VOICE_STATUS') {
    if(message.status==='no_speech'){finishVoice();elements.subtitleOrig.textContent='인식된 발화가 없습니다.';}
    if(message.status==='complete')finishVoice();
    return;
  }
  if (message.type === 'INTERPRETATION') {
    if(message.roomId!==elements.roomId.value)return;
    if(message.targetLanguage===elements.displayLanguage.value||(message.translationStatus==='original'&&knowsOriginal(message.sourceLanguage)))handleSubtitleChunk(message);
    appendEvent('AI 통역 · '+message.senderDisplayName, message.translatedText);
    if(message.audioWav && message.audioLanguage===elements.listenLanguage.value){
      if(speechQueue.length<3){speechQueue.push(message);playNextSpeech();}
      else showFeedback('번역 음성 재생 대기열이 가득 찼습니다. 이번 발화는 자막으로 확인하세요.');
    }
    return;
  }
  if(message.type==='INTERPRETATION_AUDIO'){
    if(message.roomId!==elements.roomId.value||!audioPolicy(message.sourceLanguage).translated||message.audioLanguage!==elements.listenLanguage.value)return;
    if(speechQueue.length<3){speechQueue.push(message);playNextSpeech();}
    else showFeedback('통역 음성 대기열이 가득 찼습니다. 호스트의 권장 언어 수를 확인하세요.');
    return;
  }
  if(message.type==='INTERPRETATION_STATUS'&&message.status==='failed'){
    appendEvent('일부 통역 실패','이 발화의 일부 통역을 제공하지 못했습니다. 원음과 확인하세요.','error');return;
  }
  if (message.type === 'RTC_SIGNAL') { meetingMedia.receive(message); return; }
  if (message.type === "SUBTITLE_CHUNK") {
    handleSubtitleChunk(message);
    return;
  }
  if ((message.type === "PARTICIPANT_JOINED" || message.type === "PARTICIPANT_UPDATED") && isParticipant(message.participant)) {
    const participant = message.participant;
    const current = state.participants.get(participant.participantId);
    if (message.type === "PARTICIPANT_UPDATED" && (!current || current.presenceId !== participant.presenceId)) return;
    const existed = current && current.presenceId === participant.presenceId;
    state.participants.set(participant.participantId, participant);
    renderParticipants();updateOriginalPlayback();
    if (message.type === "PARTICIPANT_JOINED" && !existed) {
      appendEvent("참가", participant.displayName + " 님이 참가했습니다.");
      announce(participant.displayName + " 님이 참가했습니다.");
    } else if (message.type === "PARTICIPANT_UPDATED") {
      if (participant.participantId === state.participantId && preferencesMatch(participant)) {
        window.clearTimeout(state.preferenceTimeout);
        elements.preferenceStatus.textContent = "언어 설정이 저장되었습니다.";
      }
      appendEvent("언어 설정 변경", participant.displayName + " · 전달 " + languageName(participant.publishLanguage) + " · 청취 " + languageName(participant.listenLanguage));
    }
    return;
  }
  if (message.type === "PARTICIPANT_LEFT" && isParticipant(message.participant)) {
    const participant = message.participant;
    const current = state.participants.get(participant.participantId);
    if (current && current.presenceId === participant.presenceId && state.participants.delete(participant.participantId)) {
      renderParticipants();
      appendEvent("퇴장", participant.displayName + " 님이 나갔습니다.");
      announce(participant.displayName + " 님이 나갔습니다.");
    }
    return;
  }
  if (message.type === "CHAT_MESSAGE") { receiveChat(message); return; }
  if (message.type === "ERROR") {
    if(message.code==='PREFERENCES_REJECTED'){
      const saved=state.participants.get(state.participantId);
      if(saved){
        for(const [key,control] of Object.entries({inputLanguage:elements.inputLanguage,publishLanguage:elements.publishLanguage,listenLanguage:elements.listenLanguage,
          secondaryOriginalLanguage:elements.secondaryOriginalLanguage,displayLanguage:elements.displayLanguage,audioMode:elements.audioMode,duckingMode:elements.duckingMode}))control.value=saved[key]||'';
        if(saved.publishLanguage===saved.inputLanguage)elements.publishLanguage.value='auto';
        applyAudioPreferences();renderCapacity();
      }
      window.clearTimeout(state.preferenceTimeout);
      elements.preferenceStatus.textContent='설정이 적용되지 않았습니다. 기존 언어 설정을 유지합니다.';
      showFeedback(message.message||elements.preferenceStatus.textContent);return;
    }
    if(['INFERENCE_FAILED','VOICE_REJECTED'].includes(message.code)){voiceQueue.length=0;window.clearTimeout(state.chatTimeout);state.pendingChat=null;finishVoice();}
    const detail = typeof message.message === "string" ? message.message : "요청을 처리하지 못했습니다.";
    showFeedback(detail);
    appendEvent("요청 실패", detail, "error");
    if (message.code === "INVALID_CHAT") {
      window.clearTimeout(state.chatTimeout);
      state.pendingChat = null;
      elements.chatStatus.textContent = "메시지를 보내지 못했습니다. 내용과 받는 사람을 확인하세요.";
      updateChatControls();
    } else {
      window.clearTimeout(state.preferenceTimeout);
      if (state.joined) elements.preferenceStatus.textContent = "요청에 오류가 발생했습니다. 설정과 연결 상태를 확인하세요.";
    }
  }
}

function preferencesMatch(participant) {
  const payload = joinPayload("UPDATE_PREFERENCES");
  return ["inputLanguage", "publishLanguage", "listenLanguage", "secondaryOriginalLanguage", "displayLanguage", "audioMode", "duckingMode", "serviceMode"].every((key) => payload[key] === participant[key]);
}
function isParticipant(value) {
  return value && typeof value.participantId === "string" && typeof value.displayName === "string" &&
    typeof value.presenceId === "string" && value.presenceId.length > 0;
}
function renderParticipants() {
  updateOriginalPlayback();renderCapacity();
  const participants = Array.from(state.participants.values());
  meetingMedia.sync(participants);
  const activeTile = document.activeElement && document.activeElement.dataset.focusParticipant;
  const activeDm = document.activeElement && document.activeElement.dataset.dmPresence;
  const search = byId("participant-search").value.trim().toLocaleLowerCase();
  let visibleCount = 0;
  elements.participantCount.textContent = String(participants.length);
  byId("stage-summary").textContent = "참석자 " + participants.length + "명";
  panels.participants.button.setAttribute("aria-label", "참가자 " + participants.length + "명");
  elements.participantList.replaceChildren();
  elements.gallery.replaceChildren();
  if (!state.participants.has(state.focusedId)) state.focusedId = participants.length ? participants[0].participantId : null;
  const ordered = state.layout === "speaker" ? participants.slice().sort((a, b) => Number(b.participantId === state.focusedId) - Number(a.participantId === state.focusedId)) : participants;
  participants.forEach((participant) => {
    if (search && !participantIdentity(participant).toLocaleLowerCase().includes(search)) return;
    visibleCount += 1;
    const item = create("li", "participant-item");
    const avatar = create("div", "avatar", initials(participant.displayName));
    avatar.setAttribute("aria-hidden", "true");
    const identity = create("div", "participant-identity");
    identity.append(create("strong", "", participantName(participant)),
      create("span", "", "@" + (participant.username || "계정 미확인") + " · 연결 " + participant.presenceId.slice(0, 8)),
      create("span", "", "전달 " + languageName(participant.publishLanguage) + " · 청취 " + languageName(participant.listenLanguage)));
    item.append(avatar, identity);
    if (participant.participantId !== state.participantId) {
      const button = create("button", "secondary-button participant-dm", "비공개 채팅");
      button.type = "button";
      button.dataset.dmPresence = participant.presenceId;
      button.setAttribute("aria-label", participantIdentity(participant) + "에게 비공개 채팅");
      button.addEventListener("click", () => startPrivateChat(participant));
      item.append(button);
    }
    elements.participantList.append(item);
  });
  byId("participant-search-status").textContent = search ? "검색 결과 " + visibleCount + "명 / 전체 " + participants.length + "명" : "전체 " + participants.length + "명";
  ordered.forEach((participant) => {
    const focused = participant.participantId === state.focusedId;
    const tile = create("article", "participant-tile" + (focused ? " is-focused" : ""));
    tile.setAttribute("aria-label", participantName(participant));
    const avatar = create("div", "tile-avatar", initials(participant.displayName));
    avatar.setAttribute("aria-hidden", "true");
    const video=mediaVideos.get(participant.participantId);
    tile.append(create("span", "tile-language", languageName(participant.publishLanguage)), avatar,
      create("strong", "tile-name", participantName(participant)));
    if(video) tile.append(video);
    if (state.layout === "speaker") {
      const button = create("button", "focus-button", focused ? "크게 보는 중" : "크게 보기");
      button.type = "button";
      button.dataset.focusParticipant = participant.participantId;
      button.setAttribute("aria-pressed", String(focused));
      button.setAttribute("aria-label", participantName(participant) + " 크게 보기");
      button.addEventListener("click", () => { state.focusedId = participant.participantId; renderParticipants(); });
      tile.append(button);
    }
    elements.gallery.append(tile);
  });
  if (activeTile) {
    const replacement = Array.from(elements.gallery.querySelectorAll("[data-focus-participant]")).find((button) => button.dataset.focusParticipant === activeTile);
    if (replacement) replacement.focus();
    else if (state.joined) byId("speaker-button").focus();
  }
  if (activeDm) {
    const replacement = Array.from(elements.participantList.querySelectorAll("[data-dm-presence]")).find(button => button.dataset.dmPresence === activeDm);
    if (replacement) replacement.focus();
    else if (state.joined) byId("participant-search").focus();
  }
  updateRecipients();
}

function updateRecipients() {
  const selected = state.recipientSelection;
  if (selected && !recipientIsCurrent(selected)) selected.invalidated = true;
  elements.recipient.replaceChildren(new Option("모두에게", ""));
  state.participants.forEach((participant) => {
    if (participant.participantId === state.participantId) return;
    if (selected && selected.invalidated && participant.participantId === selected.participantId &&
        participant.presenceId === selected.presenceId) return;
    const option = new Option(participantIdentity(participant) + "에게 · 비공개", recipientKey(participant.participantId, participant.presenceId));
    option.dataset.participantId = participant.participantId;
    option.dataset.presenceId = participant.presenceId;
    elements.recipient.add(option);
  });
  if (selected && selected.invalidated) {
    // A departed connection stays unavailable even if its participant ID is reused.
    const missing = new Option(selected.displayName + "에게 · 이전 연결 종료", recipientKey(selected.participantId, selected.presenceId));
    missing.disabled = true;
    elements.recipient.add(missing);
    elements.recipient.value = missing.value;
  } else {
    elements.recipient.value = selected ? recipientKey(selected.participantId, selected.presenceId) : "";
  }
  updateChatControls();
}
function recipientKey(participantId, presenceId) {
  return JSON.stringify([participantId, presenceId]);
}
function recipientIsCurrent(selected) {
  const participant = state.participants.get(selected.participantId);
  return !selected.invalidated && participant && participant.presenceId === selected.presenceId;
}
function selectRecipient() {
  const option = elements.recipient.selectedOptions[0];
  if (option && option.value === "") {
    state.recipientSelection = null;
  } else if (option && !option.disabled) {
    const participant = state.participants.get(option.dataset.participantId);
    if (participant && participant.presenceId === option.dataset.presenceId) {
      state.recipientSelection = {
        participantId: participant.participantId, presenceId: participant.presenceId,
        displayName: participantIdentity(participant), invalidated: false,
      };
    }
  }
  // Rebuild from the bound selection so an unknown/stale option cannot become public.
  updateRecipients();
}
function participantIdentity(participant) {
  return participant.displayName + (participant.username ? " (@" + participant.username + ")" : "") +
    " · 연결 " + participant.presenceId.slice(0, 8);
}
function startPrivateChat(participant) {
  const current = state.participants.get(participant.participantId);
  if (!state.joined || !current || current.presenceId !== participant.presenceId) {
    showFeedback("상대의 연결이 변경되었습니다. 참가자 목록에서 다시 선택하세요.");
    return;
  }
  if (state.pendingChat) { showFeedback("현재 메시지의 전송 확인이 끝난 뒤 받는 사람을 변경하세요."); return; }
  const previous = state.recipientSelection;
  if (elements.chatInput.value.trim() && (!previous || previous.presenceId !== current.presenceId) &&
      !window.confirm("작성 중인 메시지의 받는 사람을 " + participantIdentity(current) + "로 바꾸시겠습니까?")) return;
  state.recipientSelection = { participantId: current.participantId, presenceId: current.presenceId,
    displayName: participantIdentity(current), invalidated: false };
  updateRecipients();
  setPanel("chat");
  elements.chatInput.focus();
}
function updateChatControls() {
  const online = state.user && !state.isListener && state.joined && state.socket && state.socket.readyState === WebSocket.OPEN;
  const selected = state.recipientSelection;
  const missing = Boolean(selected) && !recipientIsCurrent(selected);
  const text = elements.chatInput.value.trim();
  elements.chatInput.disabled = !online;
  elements.recipient.disabled = !online || Boolean(state.pendingChat);
  elements.sendButton.disabled = !online || !text || text.length > 2000 || missing || Boolean(state.pendingChat) || (INFERENCE_ENABLED&&voiceBusy);
  byId("chat-counter").textContent = elements.chatInput.value.length.toLocaleString("en-US") + " / 2,000";
  const hint = byId("recipient-hint");
  hint.classList.toggle("is-private", Boolean(selected));
  hint.textContent = missing ? "선택한 참가자의 연결이 종료되거나 변경되었습니다. 받는 사람을 직접 다시 선택하세요."
    : selected ? "비공개 · " + selected.displayName + "에게만 전송"
      : "전체 공개 · 회의실의 모두에게 전송";
}
function sendChat() {
  updateChatControls();
  if (elements.sendButton.disabled) return;
  const text = elements.chatInput.value.trim();
  const selected = state.recipientSelection;
  const recipientId = selected ? selected.participantId : null;
  const recipientPresenceId = selected ? selected.presenceId : null;
  state.pendingChat = { text, recipientId, recipientPresenceId };
  try {
    state.socket.send(JSON.stringify({ type: "CHAT_SEND", text, recipientId, recipientPresenceId }));
  } catch (_error) {
    state.pendingChat = null;
    elements.chatStatus.textContent = "전송하지 못했습니다. 연결 상태를 확인하세요.";
    updateChatControls();
    return;
  }
  elements.chatStatus.textContent = "서버 전송 확인 중…";
  updateChatControls();
  state.chatTimeout = window.setTimeout(() => {
    state.pendingChat = null;
    elements.chatStatus.textContent = "전송 여부를 확인하지 못했습니다. 채팅 기록을 확인하세요. 다시 보내면 중복될 수 있습니다.";
    updateChatControls();
  }, INFERENCE_ENABLED ? 185000 : 8000);
}
function receiveChat(message) {
  if (message.roomId !== elements.roomId.value ||
      typeof message.messageId !== "string" || typeof message.originalText !== "string" ||
      typeof message.senderId !== "string" || typeof message.senderDisplayName !== "string" ||
      (message.scope !== "room" && message.scope !== "private") || state.chatIds.has(message.messageId)) return;
  const own = message.senderId === state.participantId && message.senderPresenceId === state.presenceId;
  const addressedToSelf = message.recipientId === state.participantId && message.recipientPresenceId === state.presenceId;
  if (message.scope === "private" && !own && !addressedToSelf) return;
  state.chatIds.add(message.messageId);
  if (state.chatIds.size > 500) state.chatIds.delete(state.chatIds.values().next().value);
  const pending = state.pendingChat;
  if (own && pending && pending.text === message.originalText && pending.recipientId === (message.recipientId || null) &&
      pending.recipientPresenceId === (message.recipientPresenceId || null)) {
    window.clearTimeout(state.chatTimeout);
    if (elements.chatInput.value.trim() === pending.text) elements.chatInput.value = "";
    state.pendingChat = null;
    elements.chatStatus.textContent = "서버가 메시지를 전송했습니다.";
    updateChatControls();
    queueMicrotask(pumpVoice);
  }
  const nearBottom = elements.chatFeed.scrollHeight - elements.chatFeed.scrollTop - elements.chatFeed.clientHeight < 80;
  const item = create("li", "chat-message" + (message.scope === "private" ? " is-private" : "") + (own ? " is-own" : ""));
  item.append(create("strong", "", message.senderDisplayName + (message.senderUsername ? " (@" + message.senderUsername + ")" : "") + (own ? " (나)" : "")));
  const meta = create("div", "chat-meta");
  meta.append(create("span", "", message.scope === "private" ? "비공개 → " + (message.recipientId === state.participantId ? "나" : (message.recipientDisplayName || "참가자") + (message.recipientUsername ? " (@" + message.recipientUsername + ")" : "")) : "모두에게"));
  const sentAt = new Date(message.sentAt);
  if (!Number.isNaN(sentAt.getTime())) {
    const time = create("time", "", sentAt.toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit" }));
    time.dateTime = sentAt.toISOString();
    meta.append(time);
  }
  item.append(meta, create("p", "", message.originalText));
  if(message.translationStatus==='translated') {
    item.append(create('p','chat-translation',message.translatedText),create('span','chat-language','AI 번역 · '+languageName(message.targetLanguage)));
    if(message.publishedText && message.publishedText!==message.originalText)item.append(create('p','chat-published','전달문: '+message.publishedText));
  }else item.append(create("span", "chat-language", "원문 · " + languageName(message.sourceLanguage) + (message.translationStatus==='failed'?" · 번역 실패 — 원문 전달":" · 로컬 번역 모델 미설정")));
  elements.chatFeed.append(item);
  while (elements.chatFeed.childElementCount > 300) elements.chatFeed.firstElementChild.remove();
  if (nearBottom || own) elements.chatFeed.scrollTop = elements.chatFeed.scrollHeight;
  if (!own) {
    if (state.panel !== "chat") { state.unread += 1; updateUnread(); }
    announce(message.scope === "private" ? "새 비공개 메시지가 도착했습니다." : "새 전체 메시지가 도착했습니다.");
  }
}
function clearChat() {
  byId("participant-search").value = "";
  elements.chatFeed.replaceChildren();
  elements.chatInput.value = "";
  elements.chatStatus.textContent = "";
  elements.recipient.replaceChildren(new Option("모두에게", ""));
  state.chatIds.clear();
  state.pendingChat = null;
  state.recipientSelection = null;
  state.unread = 0;
  updateUnread();
  updateChatControls();
}
function updateUnread() {
  byId("chat-unread").hidden = state.unread === 0;
  byId("chat-unread").textContent = state.unread > 99 ? "99+" : String(state.unread);
  panels.chat.button.setAttribute("aria-label", state.unread ? "채팅, 읽지 않은 메시지 " + state.unread + "개" : "채팅");
}
function setLayout(layout) {
  state.layout = layout;
  elements.gallery.dataset.layout = layout;
  byId("gallery-button").setAttribute("aria-pressed", String(layout === "gallery"));
  byId("speaker-button").setAttribute("aria-pressed", String(layout === "speaker"));
  byId("layout-note").textContent = layout === "speaker"
    ? "참가자를 선택해 크게 볼 수 있습니다. 실제 발언자 자동 감지는 아직 지원하지 않습니다."
    : "카메라·마이크는 직접 켜세요. 영상·원음은 같은 LAN에서 연결합니다.";
  renderParticipants();
}
function setPanel(key, restoreFocus = true) {
  const previous = state.panel;
  state.panel = key;
  elements.panel.hidden = !key;
  elements.workspace.classList.toggle("has-panel", Boolean(key));
  Object.entries(panels).forEach(([name, panel]) => {
    panel.content.hidden = key !== name;
    panel.button.setAttribute("aria-expanded", String(key === name));
  });
  if (key) {
    elements.panelHeading.textContent = panels[key].title;
    elements.panelHeading.focus();
    if (key === "chat") { state.unread = 0; updateUnread(); }
  } else if (previous && restoreFocus) panels[previous].button.focus();
}
function updateClock() {
  const elapsed = Math.max(0, Math.floor((performance.now() - state.joinedAt) / 1000));
  const seconds = String(elapsed % 60).padStart(2, "0");
  const minutes = String(Math.floor(elapsed / 60) % 60).padStart(2, "0");
  const hours = Math.floor(elapsed / 3600);
  byId("meeting-duration").textContent = (hours ? hours + ":" : "") + minutes + ":" + seconds;
}
function setConnection(name, label) {
  elements.connectionPill.dataset.state = name;
  elements.connectionLabel.textContent = label;
}
function setFormBusy(busy) {
  elements.form.setAttribute("aria-busy", String(busy));
  elements.joinButton.disabled = busy || !state.user;
  byId("invite-prejoin-button").disabled = busy || !state.user;
  elements.joinButton.textContent = busy ? "연결 중…" : "회의 참가 →";
  elements.roomId.disabled = busy;
  elements.displayName.disabled = busy;
  elements.roomId.readOnly = Boolean(state.user && state.user.role === "GUEST");
  preferenceControls.forEach((control) => { control.disabled = busy; });
}
function appendEvent(title, message, tone) {
  const item = create("li", "event-card");
  if (tone) item.dataset.tone = tone;
  const time = create("time", "", new Date().toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit" }));
  time.dateTime = new Date().toISOString();
  item.append(time, create("strong", "", title), create("p", "", message));
  elements.conversationFeed.append(item);
  while (elements.conversationFeed.childElementCount > 50) elements.conversationFeed.firstElementChild.remove();
}
function showFeedback(message) {
  byId("feedback").hidden = false;
  byId("feedback-message").textContent = message;
}
function announce(message) { byId("announcement").textContent = message; }
function create(tag, className, text) {
  const node = document.createElement(tag);
  node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}
function languageName(tag) { return languageNames.get(tag) || String(tag || "설정 없음"); }
function participantName(participant) { return participant.displayName + (participant.participantId === state.participantId ? " (나)" : ""); }
function initials(name) { return Array.from(String(name || "?").trim()).slice(0, 2).join("").toUpperCase(); }

// Authentication is held only by the server's HttpOnly cookie; no credentials or tokens are persisted here.
byId("login-form").addEventListener("submit", submitLogin);
byId("logout-button").addEventListener("click", submitLogout);
byId("my-account-button").addEventListener("click", () => openAccountDialog(false));
byId("admin-button").addEventListener("click", () => openAccountDialog(true));
byId("operator-button").addEventListener("click", openOperatorDialog);
byId("refresh-diagnostics-button").addEventListener("click", loadDiagnostics);
byId("close-account-button").addEventListener("click", () => byId("account-dialog").close());
byId("account-dialog").addEventListener("close", () => {
  clearPasswordFields();
  clearDiagnostics();
  if (state.accountTrigger && state.user) state.accountTrigger.focus();
});
byId("password-form").addEventListener("submit", submitPasswordChange);
byId("create-account-form").addEventListener("submit", submitCreateAccount);
byId("edit-account-form").addEventListener("submit", submitAccountUpdate);
byId("reset-password-form").addEventListener("submit", submitPasswordReset);
byId("create-role").addEventListener("change", () => updateGuestFields("create"));
byId("edit-role").addEventListener("change", () => updateGuestFields("edit"));
byId("refresh-accounts-button").addEventListener("click", () => loadAccounts());
document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "visible") refreshSession({ quiet: true });
});
window.addEventListener("focus", () => refreshSession({ quiet: true }));
const sessionChannel = createSessionChannel();
["login-username", "create-username"].forEach((id) => {
  byId(id).maxLength = 32;
  byId(id).pattern = "[A-Za-z0-9][A-Za-z0-9_.\\-]{2,31}";
});
["login-password", "current-password", "new-password", "confirm-password", "create-password", "reset-password"].forEach((id) => {
  byId(id).maxLength = 128;
  if (id !== "login-password" && id !== "current-password") {
    byId(id).minLength = 8;
    byId(id).placeholder = "8~128자";
  }
});
refreshSession({ initial: true });

// No automatic room admission: links identify a room, never grant access or user consent.

function createSessionChannel() {
  if (typeof BroadcastChannel !== "function") return null;
  try {
    const channel = new BroadcastChannel("mcasttalk-session-state-v1");
    channel.addEventListener("message", (event) => {
      if (!event.data || event.data.type !== "session-changed") return;
      // The event is only an invalidation hint, never proof of identity or authorization.
      // Clear old private content before querying the HttpOnly-cookie session again.
      endAuthentication("다른 탭에서 로그인 상태가 변경되었습니다. 현재 계정을 확인하고 있습니다.");
      refreshSession({ quiet: true });
    });
    return channel;
  } catch (_error) {
    return null; // Focus/visibility checks and server-side revocation remain active.
  }
}

function announceSessionChange() {
  // Intentionally no account identity, cookie, token, password or credential metadata.
  try { if (sessionChannel) sessionChannel.postMessage({ type: "session-changed" }); } catch (_error) { /* Closed page/channel. */ }
}

async function api(path, options = {}) {
  const controller = new AbortController();
  const timer = window.setTimeout(() => controller.abort(), 15000);
  try {
    const response = await fetch(path, {
      method: options.method || "GET", credentials: "same-origin", cache: "no-store",
      headers: options.body === undefined ? { Accept: "application/json" } : { Accept: "application/json", "Content-Type": "application/json" },
      body: options.body === undefined ? undefined : JSON.stringify(options.body), signal: controller.signal,
    });
    const payload = await response.json().catch(() => ({}));
    if (!response.ok) {
      if (response.status === 401 && options.expireOn401 !== false && state.user) endAuthentication("로그인 시간이 만료되었거나 계정이 변경되었습니다. 다시 로그인하세요.");
      const error = new Error("Account request failed");
      error.status = response.status;
      error.code = payload.error && payload.error.code;
      error.detail = payload.error && typeof payload.error.message === "string" ? payload.error.message : "";
      throw error;
    }
    return payload;
  } catch (error) {
    if (error.status) throw error;
    const unavailable = new Error("Host request unavailable");
    unavailable.code = "NETWORK_ERROR";
    throw unavailable;
  } finally {
    window.clearTimeout(timer);
  }
}

function accountError(error) {
  const detail = error.detail || "";
  if (/last.*admin|at least one.*admin/i.test(detail)) return "마지막 활성 관리자 계정은 비활성화하거나 다른 역할로 바꿀 수 없습니다. 먼저 다른 관리자 계정을 만드세요.";
  if (/self|your own|current account|own account/i.test(detail)) return "현재 사용 중인 관리자 계정은 여기에서 비활성화하거나 권한을 낮출 수 없습니다.";
  if (/already exists|duplicate|taken|username is unavailable/i.test(detail)) return "이미 사용 중인 사용자 이름입니다. 다른 이름을 입력하세요.";
  if (/password/i.test(detail) && error.code === "INVALID_REQUEST") return "비밀번호 조건 또는 현재 비밀번호를 확인하세요. 새 비밀번호는 8~128자이며 앞뒤 공백도 포함됩니다.";
  const messages = {
    UNAUTHENTICATED: "사용자 이름과 비밀번호를 확인하세요. 계정이 만료되거나 비활성화된 경우 관리자에게 문의하세요.",
    INVALID_CREDENTIALS: "사용자 이름과 비밀번호를 확인하세요. 계정이 만료되거나 비활성화된 경우 관리자에게 문의하세요.",
    FORBIDDEN: "이 작업을 수행할 권한이 없습니다. 현재 계정의 역할과 허용된 회의실을 확인하세요.",
    INVALID_REQUEST: "입력 내용을 확인하세요. 사용자 이름은 영문·숫자로 시작하는 3~32자이며, 게스트에는 미래의 만료 시각과 회의실이 필요합니다.",
    RATE_LIMITED: "요청이 너무 많습니다. 잠시 기다린 뒤 다시 시도하세요.",
    SETUP_REQUIRED: "Windows 프로그램의 초기 설정에서 최초 관리자 계정을 먼저 만들어 주세요.",
    BUSY: "서버가 다른 요청을 처리 중입니다. 잠시 후 다시 시도하세요.",
    INTERNAL_ERROR: "서버가 요청을 완료하지 못했습니다. 잠시 후 다시 시도하세요.",
    NETWORK_ERROR: "호스트에 연결하지 못했습니다. Windows 프로그램이 실행 중인지 확인하세요.",
    NOT_FOUND: "계정을 찾지 못했습니다. 목록을 새로고침해 주세요.",
  };
  return messages[error.code] || "요청을 완료하지 못했습니다. 계정 상태와 입력 내용을 확인하세요.";
}

async function refreshSession({ initial = false, quiet = false } = {}) {
  const epoch = state.authEpoch;
  try {
    const session = await api("/api/v1/auth/session", { expireOn401: false });
    if (epoch !== state.authEpoch) return Boolean(state.user);
    if (session.authenticated && session.user && session.user.enabled) {
      applyAuthenticatedUser(session.user);
      return true;
    }
    // Returning focus to an anonymous login form must not erase a partially typed password.
    if (initial || state.user || !quiet) endAuthentication(state.user ? "로그인이 만료되었거나 계정이 변경되었습니다. 다시 로그인하세요." : "");
  } catch (error) {
    if (epoch !== state.authEpoch) return Boolean(state.user);
    if (error.status === 401) endAuthentication("다시 로그인해 주세요.");
    else if (initial || !state.user) {
      endAuthentication(accountError(error));
    } else if (!quiet) showFeedback(accountError(error));
  } finally {
    if (initial) byId("auth-loading").hidden = true;
  }
  return false;
}

function applyAuthenticatedUser(user) {
  // Another tab may replace the shared cookie with a different account.
  // Its identity must never inherit this tab's existing private room session.
  if (state.user && state.user.id !== user.id) endAuthentication("");
  const changed = !state.user || JSON.stringify(state.user) !== JSON.stringify(user);
  if (changed) state.authEpoch += 1;
  const hadUser = Boolean(state.user);
  state.user = user;
  if(changed)refreshCapacity();
  byId("auth-loading").hidden = true;
  byId("login-view").hidden = true;
  byId("account-actions").hidden = false;
  byId("account-label").textContent = user.displayName + " · " + roleName(user.role);
  byId("admin-button").hidden = user.role !== "ADMIN";
  byId("operator-button").hidden = user.role !== "ADMIN";
  elements.displayName.value = user.displayName;
  byId("preview-name").textContent = user.displayName;
  byId("preview-avatar").textContent = initials(user.displayName);
  elements.roomId.readOnly = user.role === "GUEST";
  byId("guest-access-note").hidden = user.role !== "GUEST";
  if (user.role === "GUEST") {
    elements.roomId.value = user.guestRoomId || "";
    byId("guest-access-note").textContent = "게스트 · " + (user.guestRoomId || "지정 없음") + " 회의실 · " + localDate(user.expiresAt) + "까지 사용 가능";
  }
  if (!hadUser && !state.joined) {
    const target = MCastTalkInvitation.resolve(invitation, user);
    if (target.roomId) elements.roomId.value = target.roomId;
  }
  renderInvitationNotice();
  if (!state.joined && !state.socket) {
    elements.prejoin.hidden = false;
    setFormBusy(false);
  }
  if (user.role !== "ADMIN") {
    state.accounts.clear();
    state.editedAccountId = null;
    byId("accounts-list").replaceChildren();
    if (byId("account-dialog").open && (!byId("admin-section").hidden || !byId("operator-section").hidden)) byId("account-dialog").close();
    clearDiagnostics();
  }
  if (!hadUser) elements.roomId.focus();
}

function endAuthentication(message) {
  byId('model-terms-accepted').checked=false;
  stopMicrophone();
  if (audioPlaybackContext) { audioPlaybackContext.close().catch(() => {}); audioPlaybackContext = null; }
  if (audioCaptureContext) { audioCaptureContext.close().catch(() => {}); audioCaptureContext = null; }
  originalGainNode = null;
  translatedGainNode = null;
  state.isListener = false;
  if (elements.subtitleOrig) elements.subtitleOrig.textContent = INFERENCE_ENABLED ? "마이크를 켜고 짧게 말하세요." : "로컬 음성 모델을 설정하세요.";
  if (elements.subtitleTrans) elements.subtitleTrans.textContent = "AI 통역 · 중요한 내용은 원문과 확인하세요.";
  state.authEpoch += 1;
  state.user = null;
  capacityReport=null;renderCapacity();
  closeInvitation();
  renderInvitationNotice();
  state.accounts.clear();
  state.editedAccountId = null;
  state.manualDisconnect = true;
  const closingSocket = state.socket;
  state.socket = null;
  state.joined = false;
  state.presenceId = null;
  window.clearTimeout(state.connectionTimeout);
  window.clearTimeout(state.preferenceTimeout);
  window.clearTimeout(state.chatTimeout);
  window.clearInterval(state.clock);
  state.participants.clear();
  state.focusedId = null;
  setPanel(null, false);
  renderParticipants();
  if (closingSocket) closingSocket.close(1000, "authentication ended");
  byId("prejoin-preferences").append(byId("language-preferences"));
  elements.preferenceStatus.textContent = "";
  setFormBusy(false);
  setConnection("offline", "연결 안 됨");
  document.title = "MCastTalk · 회의";
  // Clear private room state before any later socket event or another login can arrive.
  elements.meeting.hidden = true;
  elements.prejoin.hidden = true;
  byId("account-actions").hidden = true;
  byId("admin-button").hidden = true;
  byId("operator-button").hidden = true;
  clearDiagnostics();
  byId("accounts-list").replaceChildren();
  byId("edit-account-section").hidden = true;
  if (byId("account-dialog").open) byId("account-dialog").close();
  clearPasswordFields();
  clearChat();
  elements.chatInput.disabled = true;
  elements.sendButton.disabled = true;
  byId("login-view").hidden = false;
  byId("login-button").disabled = state.loginBarrier > 0;
  byId("login-status").textContent = message;
  byId("login-username").focus();
}

async function submitLogin(event) {
  event.preventDefault();
  if (state.loginBarrier > 0 || byId("login-button").disabled || !byId("login-form").reportValidity()) return;
  const username = byId("login-username").value;
  const password = byId("login-password").value;
  byId("login-password").value = "";
  const epoch = state.authEpoch;
  byId("login-button").disabled = true;
  byId("login-status").textContent = "로그인 확인 중…";
  try {
    const session = await api("/api/v1/auth/login", { method: "POST", body: { username, password }, expireOn401: false });
    if (!session.authenticated || !session.user || !session.user.enabled) throw { code: "UNAUTHENTICATED" };
    announceSessionChange();
    if (epoch !== state.authEpoch) { await refreshSession({ quiet: true }); return; }
    applyAuthenticatedUser(session.user);
    byId("login-status").textContent = "";
    byId("feedback").hidden = true;
  } catch (error) {
    if (epoch === state.authEpoch) byId("login-status").textContent = accountError(error);
  } finally {
    byId("login-button").disabled = false;
  }
}

async function submitLogout() {
  holdLogin();
  byId("logout-button").disabled = true;
  try {
    await api("/api/v1/auth/logout", { method: "POST", body: {} });
    announceSessionChange();
    endAuthentication("로그아웃했습니다.");
  } catch (error) {
    if (state.user) showFeedback("로그아웃을 확인하지 못했습니다. " + accountError(error));
  } finally {
    byId("logout-button").disabled = false;
    releaseLogin();
  }
}

function clearPasswordFields() {
  ["login-password", "current-password", "new-password", "confirm-password", "create-password", "reset-password"].forEach((id) => { byId(id).value = ""; });
}

async function openAccountDialog(admin) {
  if (!state.user || (admin && state.user.role !== "ADMIN")) return;
  state.accountTrigger = admin ? byId("admin-button") : byId("my-account-button");
  byId("my-account-section").hidden = admin;
  byId("admin-section").hidden = !admin;
  byId("operator-section").hidden = true;
  byId("account-dialog-heading").textContent = admin ? "계정 관리" : "내 계정";
  byId("account-status").textContent = "";
  byId("my-account-description").textContent = state.user.displayName + " · " + state.user.username + " · " + roleName(state.user.role);
  clearPasswordFields();
  byId("account-dialog").showModal();
  if (admin) {
    updateGuestFields("create");
    await loadAccounts();
  }
}

function clearDiagnostics() {
  state.diagnosticsEpoch += 1;
  byId("diagnostics-metrics").replaceChildren();
  byId("diagnostics-capabilities").replaceChildren();
  byId("diagnostics-status").textContent = "";
  byId("refresh-diagnostics-button").disabled = false;
}

async function openOperatorDialog() {
  if (!state.user || state.user.role !== "ADMIN") return;
  state.accountTrigger = byId("operator-button");
  byId("my-account-section").hidden = true;
  byId("admin-section").hidden = true;
  byId("operator-section").hidden = false;
  byId("account-dialog-heading").textContent = "운영 상태";
  byId("account-status").textContent = "";
  clearPasswordFields();
  clearDiagnostics();
  byId("account-dialog").showModal();
  await loadDiagnostics();
}

async function loadDiagnostics() {
  if (!state.user || state.user.role !== "ADMIN" || byId("operator-section").hidden) return;
  const epoch = state.authEpoch;
  const request = ++state.diagnosticsEpoch;
  byId("refresh-diagnostics-button").disabled = true;
  byId("diagnostics-metrics").replaceChildren();
  byId("diagnostics-capabilities").replaceChildren();
  byId("diagnostics-status").textContent = "호스트 상태 확인 중…";
  const current = () => epoch === state.authEpoch && request === state.diagnosticsEpoch &&
    state.user && state.user.role === "ADMIN" && byId("account-dialog").open && !byId("operator-section").hidden;
  try {
    const report = await api("/api/v1/admin/diagnostics");
    if (!current()) return;
    if (report.schemaVersion !== 1 || !report.runtime || !report.membership || !report.capabilities) throw { code: "INVALID_REPORT" };
    capacityReport={inferenceStatus:report.inference?.status,capacity:report.languageCapacity};renderCapacity();
    document.querySelectorAll('input[name="capacity-language"]').forEach(e=>{e.checked=Boolean(report.languageCapacity?.selectedLanguages?.includes(e.value));});
    byId('capacity-mode').value=report.languageCapacity?.mode||'quality-first';byId('capacity-ack').checked=false;
    byId('capacity-count').value=String(report.languageCapacity?.configuredMeetingLanguageLimit||0);renderCapacityChoices();
    const bytes = value => typeof value === "number" && Number.isFinite(value) && value >= 0
      ? (value / 1024 / 1024 / 1024).toFixed(2) + " GiB" : "확인 불가";
    const count = value => Number.isSafeInteger(value) && value >= 0 ? value.toLocaleString("ko-KR") : "확인 불가";
    const metrics = [
      ["활성 회의실", count(report.membership.activeRooms)],
      ["참가 연결 · 같은 계정 중복 포함", count(report.membership.joinedConnections)],
      ["호스트 실행 시간", count(report.uptimeSeconds) + "초"],
      ["JVM 사용 / 최대 힙", bytes(report.runtime.jvmHeapUsedBytes) + " / " + bytes(report.runtime.jvmHeapMaxBytes)],
      ["JVM 사용 가능 논리 프로세서", count(report.runtime.availableProcessors)],
      ["작업 공간 볼륨의 사용 가능 용량", bytes(report.runtime.workspaceUsableBytes)],
      ["통역 작업 상태", report.inference?.status || '확인 불가'],
      ["최근 번역 실행 장치", report.inference?.translationBackend || '아직 실행하지 않음'],
      ["최근 모델 처리 시간", Number.isFinite(report.inference?.lastRequestLatencyMs)?(report.inference.lastRequestLatencyMs/1000).toFixed(1)+'초':'측정 전'],
      ["GPU 대체 사유", report.inference?.fallbackReason || '없음 / 측정 전'],
    ];
    metrics.forEach(([name, value]) => {
      const card = create("div", "");
      card.append(create("dt", "", name), create("dd", "", value));
      byId("diagnostics-metrics").append(card);
    });
    [["전체 채팅", "roomChat"], ["비공개 채팅", "privateChat"], ["계정 관리", "accountManagement"],
      ["STT 연결", "sttIntegrated"], ["번역 연결", "translationIntegrated"], ["TTS 연결", "ttsIntegrated"],
      ["외부망 접속", "publicNetworkReady"]].forEach(([name, key]) => {
      const item = create("li", "");
      item.append(create("strong", "", name), create("span", "", report.capabilities[key] === true ? "사용 가능" : "미제공"));
      byId("diagnostics-capabilities").append(item);
    });
    byId("diagnostics-status").textContent = "확인 완료 · " + localDate(report.observedAt) + " · 자동 갱신 없음";
  } catch (error) {
    if (current()) byId("diagnostics-status").textContent = accountError(error);
    if (error.status === 403) await refreshSession({ quiet: true });
  } finally {
    if (current()) byId("refresh-diagnostics-button").disabled = false;
  }
}

async function submitPasswordChange(event) {
  event.preventDefault();
  if (!state.user || !byId("password-form").reportValidity()) return;
  const currentPassword = byId("current-password").value;
  const newPassword = byId("new-password").value;
  const confirmation = byId("confirm-password").value;
  clearPasswordFields();
  if (newPassword !== confirmation) { byId("account-status").textContent = "새 비밀번호와 확인 값이 다릅니다. 다시 입력해 주세요."; return; }
  const epoch = state.authEpoch;
  holdLogin();
  byId("password-button").disabled = true;
  try {
    await api("/api/v1/auth/password", { method: "POST", body: { currentPassword, newPassword } });
    announceSessionChange();
    if (epoch === state.authEpoch || !state.user) endAuthentication("비밀번호를 변경했습니다. 새 비밀번호로 다시 로그인하세요.");
  } catch (error) {
    if (epoch === state.authEpoch) byId("account-status").textContent = error.status === 403 ? "현재 비밀번호를 확인한 뒤 다시 입력하세요." : accountError(error);
  } finally { byId("password-button").disabled = false; releaseLogin(); }
}

async function loadAccounts() {
  if (!state.user || state.user.role !== "ADMIN") return;
  const epoch = state.authEpoch;
  byId("refresh-accounts-button").disabled = true;
  try {
    const result = await api("/api/v1/admin/accounts");
    if (epoch !== state.authEpoch || !state.user || state.user.role !== "ADMIN") return;
    state.accounts = new Map((Array.isArray(result.accounts) ? result.accounts : []).map((account) => [account.id, account]));
    renderAccounts();
    if (state.editedAccountId && !state.accounts.has(state.editedAccountId)) {
      state.editedAccountId = null;
      byId("edit-account-section").hidden = true;
    }
  } catch (error) {
    if (epoch === state.authEpoch) byId("account-status").textContent = accountError(error);
    if (error.status === 403) await refreshSession({ quiet: true });
  } finally { byId("refresh-accounts-button").disabled = false; }
}

function renderAccounts() {
  byId("accounts-list").replaceChildren();
  state.accounts.forEach((account) => {
    const item = create("li", "account-item");
    const button = create("button", "");
    button.type = "button";
    button.setAttribute("aria-pressed", String(account.id === state.editedAccountId));
    button.append(create("strong", "", account.displayName + (state.user && account.id === state.user.id ? " (나)" : "")));
    button.append(create("span", "", account.username + " · " + roleName(account.role) + " · " + (account.enabled ? "활성" : "비활성")));
    if (account.role === "GUEST") button.append(create("span", "", (account.guestRoomId || "회의실 미지정") + " · " + localDate(account.expiresAt)));
    button.addEventListener("click", () => selectAccount(account.id));
    item.append(button);
    byId("accounts-list").append(item);
  });
}

function selectAccount(id) {
  const account = state.accounts.get(id);
  if (!account || !state.user || state.user.role !== "ADMIN") return;
  state.editedAccountId = id;
  byId("edit-account-section").hidden = false;
  byId("create-account-details").open = false;
  byId("edit-account-heading").textContent = account.displayName + " · " + account.username;
  byId("edit-role").value = account.role;
  byId("edit-enabled").checked = account.enabled;
  byId("edit-role").disabled = account.id === state.user.id;
  byId("edit-enabled").disabled = account.id === state.user.id;
  byId("edit-account-button").disabled = account.id === state.user.id;
  byId("edit-account-note").textContent = account.id === state.user.id ? "본인의 비활성화와 권한 변경은 허용되지 않습니다. 비밀번호를 초기화하면 다시 로그인해야 합니다." : "계정 비활성화·권한 변경·비밀번호 초기화는 진행 중인 로그인을 종료할 수 있습니다.";
  byId("edit-guest-room").value = account.guestRoomId || "";
  byId("edit-expires-at").value = toLocalInput(account.expiresAt);
  byId("reset-password").value = "";
  updateGuestFields("edit");
  renderAccounts();
}

function updateGuestFields(prefix) {
  const guest = byId(prefix + "-role").value === "GUEST";
  byId(prefix + "-guest-fields").hidden = !guest;
  byId(prefix + "-guest-room").required = guest;
  byId(prefix + "-expires-at").required = guest;
  if (guest && !byId(prefix + "-expires-at").value) byId(prefix + "-expires-at").value = toLocalInput(new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString());
}

function accountChanges(prefix) {
  const role = byId(prefix + "-role").value;
  let expiresAt = null;
  let guestRoomId = null;
  if (role === "GUEST") {
    const date = new Date(byId(prefix + "-expires-at").value);
    guestRoomId = byId(prefix + "-guest-room").value;
    const requireFuture = prefix !== "edit" || byId("edit-enabled").checked;
    if (!guestRoomId || Number.isNaN(date.getTime()) || (requireFuture && date.getTime() <= Date.now())) throw { code: "INVALID_REQUEST" };
    expiresAt = date.toISOString();
  }
  return { role, expiresAt, guestRoomId };
}

async function submitCreateAccount(event) {
  event.preventDefault();
  if (!state.user || state.user.role !== "ADMIN" || !byId("create-account-form").reportValidity()) return;
  const password = byId("create-password").value;
  byId("create-password").value = "";
  const epoch = state.authEpoch;
  byId("create-account-button").disabled = true;
  try {
    const body = { username: byId("create-username").value, displayName: byId("create-display-name").value, password, ...accountChanges("create") };
    const result = await api("/api/v1/admin/accounts", { method: "POST", body });
    if (epoch !== state.authEpoch) return;
    byId("account-status").textContent = "계정을 만들었습니다. 초기 비밀번호는 저장하거나 다시 표시하지 않습니다.";
    byId("create-account-form").reset();
    updateGuestFields("create");
    await loadAccounts();
    if (result.account && epoch === state.authEpoch) selectAccount(result.account.id);
  } catch (error) {
    if (epoch === state.authEpoch) byId("account-status").textContent = accountError(error);
    if (error.status === 403) await refreshSession({ quiet: true });
  } finally { byId("create-account-button").disabled = false; }
}

async function submitAccountUpdate(event) {
  event.preventDefault();
  if (!state.user || state.user.role !== "ADMIN" || !state.editedAccountId || !byId("edit-account-form").reportValidity()) return;
  const epoch = state.authEpoch;
  const id = state.editedAccountId;
  if (id === state.user.id) { byId("account-status").textContent = "현재 사용 중인 계정의 활성 상태와 역할은 여기서 변경할 수 없습니다."; return; }
  byId("edit-account-button").disabled = true;
  try {
    await api("/api/v1/admin/accounts/" + encodeURIComponent(id) + "/update", { method: "POST", body: { ...accountChanges("edit"), enabled: byId("edit-enabled").checked } });
    if (epoch !== state.authEpoch) return;
    byId("account-status").textContent = "계정 변경을 저장했습니다.";
    await loadAccounts();
    if (epoch === state.authEpoch) selectAccount(id);
  } catch (error) {
    if (epoch === state.authEpoch) byId("account-status").textContent = accountError(error);
    if (error.status === 403) await refreshSession({ quiet: true });
  } finally { byId("edit-account-button").disabled = false; }
}

async function submitPasswordReset(event) {
  event.preventDefault();
  if (!state.user || state.user.role !== "ADMIN" || !state.editedAccountId || !byId("reset-password-form").reportValidity()) return;
  const password = byId("reset-password").value;
  byId("reset-password").value = "";
  const epoch = state.authEpoch;
  const id = state.editedAccountId;
  const resettingSelf = id === state.user.id;
  if (resettingSelf) holdLogin();
  byId("reset-password-button").disabled = true;
  try {
    await api("/api/v1/admin/accounts/" + encodeURIComponent(id) + "/password", { method: "POST", body: { password } });
    if (resettingSelf) announceSessionChange();
    if (resettingSelf && (epoch === state.authEpoch || !state.user)) endAuthentication("비밀번호를 초기화했습니다. 새 비밀번호로 다시 로그인하세요.");
    else if (epoch !== state.authEpoch) return;
    else byId("account-status").textContent = "비밀번호를 초기화했습니다. 해당 계정은 새 비밀번호로 다시 로그인해야 합니다.";
  } catch (error) {
    if (epoch === state.authEpoch) byId("account-status").textContent = accountError(error);
    if (error.status === 403) await refreshSession({ quiet: true });
  } finally { byId("reset-password-button").disabled = false; if (resettingSelf) releaseLogin(); }
}

function holdLogin() { state.loginBarrier += 1; byId("login-button").disabled = true; }
function releaseLogin() { state.loginBarrier = Math.max(0, state.loginBarrier - 1); byId("login-button").disabled = state.loginBarrier > 0; }

function toLocalInput(value) {
  const date = new Date(value);
  if (!value || Number.isNaN(date.getTime())) return "";
  const pad = (part) => String(part).padStart(2, "0");
  return date.getFullYear() + "-" + pad(date.getMonth() + 1) + "-" + pad(date.getDate()) + "T" + pad(date.getHours()) + ":" + pad(date.getMinutes());
}
function localDate(value) {
  const date = new Date(value);
  return value && !Number.isNaN(date.getTime()) ? date.toLocaleString("ko-KR") : "만료 미지정";
}
function roleName(role) { return ({ ADMIN: "관리자", USER: "사용자", GUEST: "게스트" })[role] || "알 수 없는 역할"; }
