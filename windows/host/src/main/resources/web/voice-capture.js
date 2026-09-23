"use strict";
window.MCastTalkVoiceCapture=class {
  constructor(send,onError){this.send=send;this.onError=onError;this.epoch=0;this.sequence=0;}
  async setTrack(track){
    if(this.track===track)return;this.close();if(!track)return;
    this.track=track;const epoch=this.epoch;
    try{
      const context=new AudioContext({sampleRate:16000});this.context=context;
      if(context.sampleRate!==16000)throw new Error('16 kHz 오디오 캡처를 지원하지 않습니다.');
      await context.audioWorklet.addModule('/voice-worklet.js');
      if(epoch!==this.epoch){await context.close();return;}
      const source=context.createMediaStreamSource(new MediaStream([track]));
      const processor=new AudioWorkletNode(context,'mcasttalk-pcm');const silence=context.createGain();silence.gain.value=0;
      source.connect(processor).connect(silence).connect(context.destination);
      processor.port.onmessage=e=>{
        if(epoch!==this.epoch)return;
        const pcm=e.data;const frame=new ArrayBuffer(16+pcm.byteLength);const header=new DataView(frame);
        header.setUint32(0,0x3154434d,true);header.setInt32(4,this.sequence++,true);header.setUint32(8,16000,true);header.setUint32(12,pcm.byteLength/2,true);
        new Uint8Array(frame,16).set(new Uint8Array(pcm));this.send(frame);
      };
      await context.resume();
    }catch(error){if(epoch===this.epoch){this.close();this.onError(error.message);}}
  }
  close(){this.epoch++;this.track=null;this.context?.close().catch(()=>{});this.context=null;}
};
