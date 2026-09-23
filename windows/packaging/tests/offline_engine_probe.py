"""Real-model probe for the network-disabled Windows Sandbox acceptance run.

Uses only the installed bundle; not a human speech/translation quality score.
The invoking Sandbox script separately proves networking is disabled.
"""
from __future__ import annotations

import argparse
import base64
import io
import json
import re
import sys
import time
import wave
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--app", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    offline = args.app / "offline"
    sys.path.insert(0, str(offline / "worker"))
    import numpy as np
    from mcasttalk_worker.meeting_engine import MeetingEngine

    args.output.mkdir(parents=True, exist_ok=True)
    hashes = dict(line.split("=", 1) for line in (offline / "asset-hashes.properties").read_text().splitlines() if line)
    config = {"assetRoot": str(offline / "assets"), "tempRoot": str(args.output / "temp"),
              "threads": 4, "backend": "cpu", "sttModel": "ggml-small-q5_1.bin", "sha256": hashes}
    config_path = args.output / "engine.json"
    config_path.write_text(json.dumps(config), encoding="utf-8")
    started = time.monotonic()
    result = {"passed": False, "scope": "real installed CPU models; synthetic speech; not human quality or LAN UAT"}
    engine = None
    try:
        engine = MeetingEngine(config_path)
        wav = engine.speak("Hello. The meeting starts now.", "en")
        with wave.open(io.BytesIO(wav)) as audio:
            assert audio.getnchannels() == 1 and audio.getsampwidth() == 2
            source_rate = audio.getframerate()
            samples = np.frombuffer(audio.readframes(audio.getnframes()), dtype="<i2").astype(np.float32)
        count = min(96000, int(len(samples) * 16000 / source_rate))
        converted = np.interp(np.arange(count) * source_rate / 16000, np.arange(len(samples)), samples)
        pcm = np.clip(converted, -32768, 32767).astype("<i2").tobytes()
        assert len(pcm) >= 8000
        reply = engine.execute({"op": "voice", "sourceLanguage": "en", "publishLanguage": "en",
                                "targets": "en,ko,ja,zh-CN", "audioTargets": "en,ko,ja,zh-CN",
                                "pcm": base64.b64encode(pcm).decode()})
        assert reply["status"] == "complete" and reply["originalText"].strip()
        result["originalText"] = reply["originalText"]
        result["translations"] = {}
        for language, pattern in (("en", r"[A-Za-z]"), ("ko", r"[가-힣]"),
                                  ("ja", r"[\u3040-\u30ff\u4e00-\u9fff]"), ("zh-CN", r"[\u4e00-\u9fff]")):
            text = reply["text_" + language]
            assert re.search(pattern, text), (language, text)
            spoken = base64.b64decode(reply["audio_" + language], validate=True)
            with wave.open(io.BytesIO(spoken)) as audio:
                assert audio.getnframes() > 0 and audio.getframerate() >= 8000
            (args.output / (language + ".wav")).write_bytes(spoken)
            result["translations"][language] = text
        result.update(passed=True, elapsedMs=round((time.monotonic() - started) * 1000), backend=reply["backend"])
    except Exception as error:
        result["error"] = str(error)
        raise
    finally:
        if engine:
            engine.close()
        (args.output / "engine-results.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
