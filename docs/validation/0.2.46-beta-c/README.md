# Reproducible beta-c comparison records

The Korean IR article was supplied by the user for this comparison. It was split
into 22 complete sentences without replacing their text; each was translated
into English, Japanese and Simplified Chinese. The source is authoritative.
The Gemini reference is an independently generated model answer, not a gold
translation or a human certification. Its exact original bytes are preserved.

| File | Purpose |
| --- | --- |
| `ir-corpus.json` | Exact 22 source sentences and prior-source context |
| `gemini-reference.json` | 66 independent Gemini answers, UI model label and provenance |
| `e4b-baseline.json` | Actual native pre-change outputs; 65 completions and one timeout |
| `e4b-rejected-fast.json` | Faster candidate, 66 completions but subsequently rejected by spoken-safety regression |
| `e4b-final.json` | Restored-fidelity final candidate, 66 actual native outputs |
| `speech-controls-16.json`, `speech-controls-7.json` | Actual 23 native speech-correction controls; all outputs identical to beta-b |
| `syntax-controls.json` | Ten authored controls, fixed before their final native run |
| `syntax-final.json` | Thirty actual native memory-enabled outputs |
| `comparison.json`, `comparison.csv` | Per-row AI-assisted comparison, with raw text checked against the files above |
| `COMPARISON_REPORT.md` | Results, grading rubric, measured latency and remaining issues |

The baseline APK already carried the candidate version label but had the old
translation policy. Its SHA-256 was
`1105e05994642565ee26de4b10558531c56dbb13617b5b783dcad1c69a3ee3f5`.
The rejected faster candidate APK SHA-256 was
`e0468192b9ad69e48dafee49654bfb78ff470b3f5fe2df32c8198c27830d9e10`.
Both used the same E4B model SHA-256
`0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0`.
The restored-fidelity candidate APK SHA-256 is
`b73844cad224e733ccea843cbcb1d47b9d56494b82c118e4d16a6c7d9a4bd330`.

The article runs use style AUTO and no new bilingual session memory, so the
before/after policy comparison has the same source context. Separate authored
controls enable session memory. They test actual local inference with the memory
store, not model-weight training or an independent held-out human benchmark.
All recorded article/controls input is text: ASR segmentation and TTS are **not**
executed by this comparison class. See the main [test report](../../TEST_REPORT.md)
for separate native recorded-speech and WebSocket PCM checks.

## Reproduction

Use a dedicated ARM64 API 35 emulator with the verified E4B model already
prepared. Install the signed product and matching signed instrumentation APK;
verify their installed hashes before running. Do not substitute a source-only
CI APK or clear user data to create space.

```sh
adb push docs/validation/0.2.46-beta-c/ir-corpus.json /sdcard/Android/data/app.guidecast.transmitter.alpha/files/benchmark/ir-corpus.json
adb shell am instrument -w -r -e dedicatedCorpusDevice true -e comparisonRun reproduction -e class app.guidecast.transmitter.ArticleTranslationComparisonDeviceTest app.guidecast.transmitter.alpha.test/app.guidecast.transmitter.GuideCastTestRunner
```

For the authored controls, push `syntax-controls.json` as `syntax-holdout.json`
to the same directory and add `-e comparisonCorpus syntax-holdout.json
-e comparisonSessionMemory true`. Every request writes its actual output,
state and elapsed time; the final JUnit completion assertion is separate from
semantic review. The `sessionMemory` field records the store's candidate hint;
the provider may omit the whole hint when prior context plus hint exceeds 400
characters. This field alone is not proof that every hint reached the prompt.

Native timing is measured on an emulator hosted alongside the development
tools, one run per configuration. Nearest-rank p95 describes these samples only;
neither timing significance nor physical S23 first-audio performance is claimed.
Private diagnostic exports, personal recordings/photos, credentials and model
files are excluded from this directory.
