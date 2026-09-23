"use strict";
// No public STUN/TURN: offline LAN candidates only. Host binds signals to presence.
window.MCastTalkMedia = class {
  constructor({send,onError,onLocal,onRemote}) {
    Object.assign(this,{send,onError,onLocal,onRemote});
    this.peers=new Map(); this.stream=new MediaStream(); this.epoch=0; this.pending=new Set(); this.closed=true;
  }
  join(id,listener) { this.close(); this.closed=false; this.id=id; this.listener=listener; }
  sync(participants) {
    if(this.closed) return;
    const others=participants.filter(p=>p.participantId!==this.id);
    for(const [id,e] of this.peers) if(!others.some(p=>p.participantId===id&&p.presenceId===e.presence)) {
      e.pc.close(); this.peers.delete(id); this.onRemote(id,null);
    }
    for(const p of others) {
      if(this.peers.has(p.participantId)) continue;
      if(this.peers.size>=7) {this.onError('영상·원음은 최대 8명 소규모 회의를 지원합니다.');break;}
      const pc=new RTCPeerConnection({iceServers:[],bundlePolicy:'max-bundle'});
      const e={pc,presence:p.presenceId,chain:Promise.resolve()}; this.peers.set(p.participantId,e);
      if(this.id<p.participantId) for(const kind of ['audio','video']) pc.addTransceiver(this.stream.getTracks().find(t=>t.kind===kind)||kind,
        {direction:this.listener?'recvonly':'sendrecv',streams:[this.stream]});
      pc.ontrack=event=>{
        if(this.peers.get(p.participantId)!==e) return;
        e.remote ||= new MediaStream(); e.remote.addTrack(event.track); this.onRemote(p.participantId,e.remote);
      };
      pc.onconnectionstatechange=()=>{if(pc.connectionState==='failed')this.onError(p.displayName+' 님과 연결 실패: 같은 LAN인지 확인하세요.');};
      // Permanent audio/video transceivers avoid renegotiation when devices toggle.
      if(this.id<p.participantId) this.enqueue(p.participantId,async()=>{
        await pc.setLocalDescription(await pc.createOffer()); await this.gather(pc); this.signal(p.participantId,e);
      });
    }
  }
  enqueue(id,fn) {
    const e=this.peers.get(id); if(!e)return;
    e.chain=e.chain.then(()=>this.peers.get(id)===e?fn():null).catch(error=>{
      if(!this.closed&&this.peers.get(id)===e)this.onError('미디어 연결 오류: '+error.message);
    });
  }
  async gather(pc) {
    if(pc.iceGatheringState==='complete')return;
    await new Promise((resolve,reject)=>{
      const timer=setTimeout(()=>finish(new Error('LAN 후보 수집 시간 초과')),10000);
      const check=()=>{if(pc.iceGatheringState==='complete')finish();};
      const finish=error=>{clearTimeout(timer);pc.removeEventListener('icegatheringstatechange',check);error?reject(error):resolve();};
      pc.addEventListener('icegatheringstatechange',check);check();
    });
  }
  signal(id,e) {
    if(this.closed||this.peers.get(id)!==e)return;
    this.send({type:'RTC_SIGNAL',targetId:id,targetPresenceId:e.presence,signalType:e.pc.localDescription.type,sdp:e.pc.localDescription.sdp});
  }
  receive(m) {
    const e=this.peers.get(m.senderId);if(!e||e.presence!==m.senderPresenceId)return;
    this.enqueue(m.senderId,async()=>{
      if(!['offer','answer'].includes(m.signalType))return;
      if(m.signalType==='offer'&&this.id<m.senderId)return;
      await e.pc.setRemoteDescription({type:m.signalType,sdp:m.sdp});
      if(m.signalType==='offer') {
        for(const t of e.pc.getTransceivers()) {
          t.direction=this.listener?'recvonly':'sendrecv';
          await t.sender.replaceTrack(this.stream.getTracks().find(track=>track.kind===t.receiver.track.kind)||null);
        }
        await e.pc.setLocalDescription(await e.pc.createAnswer());await this.gather(e.pc);this.signal(m.senderId,e);
      }
    });
  }
  async toggle(kind,screen=false) {
    if(this.closed||this.listener||this.pending.has(kind))return;
    const old=this.stream.getTracks().find(t=>t.kind===kind);
    if(old){await this.setTrack(kind,null);old.stop();return;}
    const epoch=this.epoch;this.pending.add(kind);let stream;
    try {
      stream=screen?await navigator.mediaDevices.getDisplayMedia({video:true,audio:false}):
        await navigator.mediaDevices.getUserMedia(kind==='audio'?{audio:{echoCancellation:true,noiseSuppression:true},video:false}:
          {video:{width:{ideal:640},height:{ideal:360},frameRate:{ideal:15,max:24}},audio:false});
      if(this.closed||epoch!==this.epoch){stream.getTracks().forEach(t=>t.stop());return;}
      const track=stream.getTracks().find(t=>t.kind===kind);await this.setTrack(kind,track);
      track.onended=()=>{if(this.stream.getTracks().includes(track))this.setTrack(kind,null).catch(e=>this.onError(e.message));};
    } catch(e){stream?.getTracks().forEach(t=>t.stop());if(epoch===this.epoch)this.onError('장치 접근 실패: '+e.message);}
    finally{if(epoch===this.epoch)this.pending.delete(kind);}
  }
  async setTrack(kind,track) {
    const epoch=this.epoch;const old=this.stream.getTracks().find(t=>t.kind===kind);if(old)this.stream.removeTrack(old);
    if(track)this.stream.addTrack(track);
    await Promise.all([...this.peers.values()].map(({pc})=>pc.getTransceivers().find(t=>t.receiver.track.kind===kind)?.sender.replaceTrack(track)));
    if(epoch!==this.epoch){track?.stop();return;}this.onLocal(this.stream);
  }
  close() {
    this.closed=true;this.epoch++;this.pending.clear();this.peers.forEach(e=>e.pc.close());this.peers.clear();
    this.stream.getTracks().forEach(t=>t.stop());this.stream=new MediaStream();this.onLocal?.(this.stream);
  }
};
