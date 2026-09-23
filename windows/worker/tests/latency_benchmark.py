"""Actual local model timings with synthetic fixture audio; no human quality claim.

Separate model startup, ASR, per-language translation and speech synthesis. Never
point --audio at recordings of real participants when publishing the output.
"""
from __future__ import annotations
import argparse
import base64
import json
import sys
import time
import threading
import wave
from pathlib import Path


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--app', type=Path, required=True)
    parser.add_argument('--worker', type=Path)
    parser.add_argument('--audio', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--backend', choices=['cpu', 'vulkan', 'auto'], default='cpu')
    parser.add_argument('--threads', type=int, default=4)
    parser.add_argument('--progressive', action='store_true')
    parser.add_argument('--warmup', action='store_true')
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError('Use a fresh evidence directory')
    args.output.mkdir(parents=True)
    offline = args.app.resolve() / 'offline'
    sys.path.insert(0, str((args.worker or offline / 'worker').resolve()))
    import numpy as np
    import mcasttalk_worker.meeting_engine as module
    config = {'assetRoot': str(offline / 'assets'), 'tempRoot': str(args.output.resolve() / 'temp'),
              'threads': args.threads, 'backend': args.backend, 'sttModel': 'ggml-small-q5_1.bin',
              'sha256': dict(line.split('=', 1) for line in (offline / 'asset-hashes.properties').read_text().splitlines() if line)}
    config_path = args.output / 'engine.json'
    config_path.write_text(json.dumps(config), encoding='utf-8')
    records = []
    record_lock = threading.Lock()
    def record(**item):
        with record_lock:
            records.append(item)
            (args.output / 'stages.json').write_text(json.dumps(records, ensure_ascii=False, indent=2), encoding='utf-8')
            print(json.dumps(item, ensure_ascii=False), flush=True)

    class TimedEngine(module.MeetingEngine):
        def _start_translation(self, backend):
            begin = time.perf_counter()
            try: return super()._start_translation(backend)
            finally: record(stage='translation-startup', backend=backend, ms=round((time.perf_counter()-begin)*1000))
        def translate(self, text, source, target):
            begin = time.perf_counter()
            result = super().translate(text, source, target)
            record(stage='translate', source=source, target=target, ms=round((time.perf_counter()-begin)*1000), text=result)
            return result
        def speak(self, text, target):
            begin = time.perf_counter()
            result = super().speak(text, target)
            record(stage='speak', target=target, ms=round((time.perf_counter()-begin)*1000), bytes=len(result))
            return result

    original_transcribe = module.transcribe
    def timed_transcribe(*params, **kwargs):
        result = original_transcribe(*params, **kwargs)
        record(stage='asr', source=kwargs['language'], ms=round(result['evidence']['totalLatencyMs']),
               evidence=result['evidence'], text=result['text'])
        return result
    module.transcribe = timed_transcribe
    engine = None
    try:
        begin = time.perf_counter()
        engine = TimedEngine(config_path)
        record(stage='integrity-startup', ms=round((time.perf_counter()-begin)*1000))
        if args.warmup:
            engine.warmup()
        for source in ('en', 'ko'):
            with wave.open(str(args.audio / (source + '.wav'))) as audio:
                assert audio.getnchannels() == 1 and audio.getsampwidth() == 2
                rate = audio.getframerate()
                samples = np.frombuffer(audio.readframes(audio.getnframes()), dtype='<i2').astype(np.float32)
            count = min(96000, round(len(samples)*16000/rate))
            pcm = np.interp(np.arange(count)*rate/16000, np.arange(len(samples)), samples).astype('<i2').tobytes()
            begin = time.perf_counter()
            request = {'op':'voice', 'sourceLanguage':source, 'publishLanguage':source,
                'targets':'en,ko,ja,zh-CN', 'audioTargets':','.join(t for t in ('en','ko','ja','zh-CN') if t != source),
                'pcm':base64.b64encode(pcm).decode()}
            def delivered(event):
                record(stage='delivery-'+event['kind'], source=source, target=event['targetLanguage'], ms=event['elapsedMs'])
            result = engine.execute(request,on_event=delivered) if args.progressive else engine.execute(request)
            record(stage='utterance', source=source, inputMs=count/16, ms=round((time.perf_counter()-begin)*1000),
                   backend=result.get('backend'), fallbackReason=result.get('fallbackReason'), asrMs=result.get('asrMs'),
                   translations={k:v for k,v in result.items() if k.startswith('text_')})
    finally:
        if engine: engine.close()
        module.transcribe = original_transcribe


if __name__ == '__main__':
    main()
