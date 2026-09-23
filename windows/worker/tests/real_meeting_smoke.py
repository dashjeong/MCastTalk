"""Real local model test; records output/latency. No claim of human listening UAT."""
import argparse
import json
from pathlib import Path
import time
import wave
import base64
from mcasttalk_worker.meeting_engine import MeetingEngine

def main():
    p=argparse.ArgumentParser();p.add_argument('--config',required=True);p.add_argument('--output',required=True);args=p.parse_args()
    out=Path(args.output);out.mkdir(parents=True,exist_ok=True)
    engine=MeetingEngine(args.config);results=[]
    samples={'ko':'안녕하세요. 회의는 오후 세 시에 시작합니다.','en':'Hello. The meeting starts at three in the afternoon.',
             'ja':'こんにちは。会議は午後三時に始まります。','zh-CN':'你好。会议下午三点开始。'}
    try:
        for lang,text in samples.items():
            started=time.perf_counter();wav=engine.speak(text,lang);(out/(lang+'.wav')).write_bytes(wav)
            results.append({'stage':'tts','language':lang,'elapsedMs':round((time.perf_counter()-started)*1000),'wavBytes':len(wav)})
            print(json.dumps(results[-1]),flush=True)
        for lang in ['en','ja','zh-CN']:
            started=time.perf_counter();translated=engine.translate(samples['ko'],'ko',lang)
            results.append({'stage':'translation','source':'ko','target':lang,'text':translated,'elapsedMs':round((time.perf_counter()-started)*1000)})
            print(json.dumps(results[-1],ensure_ascii=False),flush=True)
        # Resample a generated English utterance for a real STT -> MT -> TTS path.
        import numpy as np
        with wave.open(str(out/'en.wav'),'rb') as audio:
            rate=audio.getframerate();data=np.frombuffer(audio.readframes(audio.getnframes()),dtype='<i2')
        data=np.interp(np.arange(0,len(data),rate/16000),np.arange(len(data)),data).astype('<i2')[:96000]
        result=engine.execute({'op':'voice','pcm':base64.b64encode(data.tobytes()).decode(), 'sourceLanguage':'en',
            'publishLanguage':'en','targets':'ko','audioTargets':'ko'})
        (out/'interpreted-ko.wav').write_bytes(base64.b64decode(result.pop('audio_ko')))
        results.append({'stage':'end-to-end',**result});print(json.dumps(results[-1],ensure_ascii=False),flush=True)
    finally:
        engine.close();(out/'results.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8')

if __name__=='__main__': main()
