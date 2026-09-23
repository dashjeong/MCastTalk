"use strict";
// Energy VAD is a conservative segmentation aid, not a speech-recognition model.
class MCastTalkPcm extends AudioWorkletProcessor {
  constructor(){super();this.parts=[];this.count=0;this.quiet=0;this.speech=false;this.pre=[];}
  process(inputs){
    const input=inputs[0]?.[0];if(!input||sampleRate!==16000)return true;
    const rms=Math.sqrt(input.reduce((s,v)=>s+v*v,0)/input.length);
    const pcm=new Int16Array(input.length);for(let i=0;i<input.length;i++)pcm[i]=Math.max(-32768,Math.min(32767,input[i]*32767));
    if(!this.speech){
      this.pre.push(pcm);if(this.pre.length>25)this.pre.shift();
      if(rms<0.012)return true;
      this.speech=true;this.parts=this.pre;this.pre=[];this.count=this.parts.reduce((n,p)=>n+p.length,0);
    }else{this.parts.push(pcm);this.count+=pcm.length;}
    this.quiet=rms<0.009?this.quiet+pcm.length:0;
    if(this.count>=96000||(this.count>=4000&&this.quiet>=9600)){
      const total=Math.min(96000,this.count), output=new Int16Array(total);let offset=0;
      for(const p of this.parts){const n=Math.min(p.length,total-offset);output.set(p.subarray(0,n),offset);offset+=n;if(offset>=total)break;}
      this.port.postMessage(output.buffer,[output.buffer]);this.parts=[];this.count=0;this.quiet=0;this.speech=false;
    }
    return true;
  }
}
registerProcessor('mcasttalk-pcm',MCastTalkPcm);
