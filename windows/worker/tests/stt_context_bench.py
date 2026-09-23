"""Synthetic four-language context comparison, not a human accuracy benchmark."""
import argparse,json,tempfile,wave
from pathlib import Path
import numpy as np
from mcasttalk_worker.whisper_cli import RuntimeSpec,ModelSpec,RUNTIME_COMMIT,transcribe
p=argparse.ArgumentParser();p.add_argument('--config',required=True);p.add_argument('--audio',required=True);p.add_argument('--output',required=True)
p.add_argument('--model');p.add_argument('--languages',default='en,ko,ja,zh-CN');p.add_argument('--contexts',default='0,512');args=p.parse_args()
config=json.loads(Path(args.config).read_text(encoding='utf-8'));root=Path(config['assetRoot']);results=[]
runtime=RuntimeSpec(root/'whisper/whisper-cli.exe',config['sha256']['whisper/whisper-cli.exe'],RUNTIME_COMMIT)
name=args.model or config['sttModel'];model=ModelSpec(root/name,config['sha256'][name])
with tempfile.TemporaryDirectory(dir=config['tempRoot']) as directory:
 for lang in args.languages.split(','):
  with wave.open(str(Path(args.audio)/(lang+'.wav')),'rb') as w:
   rate=w.getframerate();data=np.frombuffer(w.readframes(w.getnframes()),dtype='<i2')
  data=np.interp(np.arange(0,len(data),rate/16000),np.arange(len(data)),data).astype('<i2')[:96000]
  source=Path(directory)/(lang+'.wav')
  with wave.open(str(source),'wb') as w:w.setnchannels(1);w.setsampwidth(2);w.setframerate(16000);w.writeframes(data.tobytes())
  for context in map(int,args.contexts.split(',')):
   result=transcribe(runtime,model,source,Path(directory),language=lang,threads=4,beam_size=1,best_of=1,audio_context=context)
   row=dict(language=lang,audioContext=context,text=result['text'],elapsedMs=round(result['evidence']['totalLatencyMs']))
   results.append(row);print(json.dumps(row,ensure_ascii=False),flush=True)
Path(args.output).write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8')
