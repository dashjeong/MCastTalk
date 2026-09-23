# Offline model workers

## Integrated meeting worker — 0.4.1

`mcasttalk_worker.meeting_engine` now implements the host's serialized JSON-lines
inference process. Requests use only locally verified model/runtime files. Voice
requests are bounded 16 kHz PCM utterances, not continuous streaming recognition.
The host deduplicates language targets and keeps private chat delivery private.

- STT: pinned whisper.cpp CPU, multilingual small Q5_1, beam/best-of 1 and audio
  context 512 for the meeting's maximum six-second chunks. The standalone adapter
  retains its original defaults; too-long input for a reduced context is rejected.
- Translation: local authenticated llama-server, Qwen3-4B-Instruct-2507 Q4_K_M,
  Vulkan with CPU startup fallback in `auto` mode. Runtime errors are explicit.
- TTS: sherpa-onnx Supertonic 3 for ko/en/ja; MeloTTS for zh-CN, CPU. The model's
  OpenRAIL-M conditions differ from the surrounding example-code license.
- Output WAV, input, deadlines, queue and temporary paths are bounded. Normal
  temporary audio is removed; abrupt termination can leave owner-only residue.
- An owned-host watcher stops the worker's process tree if its Windows host dies.

See `../MEDIA_LAN_VERIFICATION_2026-09-22.md` for the real-model and browser
evidence. Synthetic fixtures do not establish human intelligibility, accent
coverage or sustained p95 latency. In particular, a Chinese synthetic fixture
contains a known recognition error. No perfect-quality claim is made.

The following section records the earlier standalone adapter and its original
20-test smoke milestone. It does not describe the current integrated scope.

## Historical first whisper.cpp adapter

This directory contains a real subprocess adapter for a locally supplied
`whisper-cli.exe`, not a model, a speech service, or a simulated inference engine.
It completed **one real English CPU smoke run** on 2026-09-19 using locally
verified upstream development assets. No runtime/model is bundled in this
package. Unit tests replace `subprocess.Popen` with explicit fixtures and prove
adapter contracts only. Neither the unit tests nor that single smoke establish
four-language STT quality, GPU support, sustained latency or production readiness.

## Pinned CLI contract

Only whisper.cpp **v1.9.4**, source commit
`927cfce34f31707e17f2bff35c349632fb9e2c3a`, is accepted. The official annotated tag
object was `7d75b14994ae7f59623e2471445e2355fe506ed2` when checked on 2026-09-19.
The adapter requires the caller's explicit executable SHA-256 and commit, then
checks both the actual file hash and `--version` output. Matching a declared
commit is not proof of reproducible compilation: the release pack manager must
authenticate the build, its dependency DLLs, and their provenance separately.

Reviewed primary sources:

- [Pinned CLI argument parser and JSON writer](https://github.com/ggml-org/whisper.cpp/blob/927cfce34f31707e17f2bff35c349632fb9e2c3a/examples/cli/cli.cpp)
- [Official v1.9.4 release](https://github.com/ggml-org/whisper.cpp/releases/tag/v1.9.4)
- [Official Vulkan build instructions](https://github.com/ggml-org/whisper.cpp#vulkan-gpu-support)

The adapter supplies `-ng` for CPU, explicit decoder settings, `-oj` for the
JSON result and `-of` for an isolated output stem. It never uses Whisper's
English-translation flag. It parses `transcription` segments with millisecond
`offsets`, checks the returned model/language/translation parameters, and
requires a multilingual model. A successful exit with no JSON is a failure:
the upstream CLI can exit zero for some argument errors.

The pinned source defines `-dev` as a GPU device index, but that alone does not
prove Vulkan execution or map the index to the hardware profiler's adapter ID.
**Only CPU is currently enabled.** A `vulkan`, `cuda`, or other backend request
fails with `UNSUPPORTED_BACKEND`; it is not silently reported as accelerated.
Vulkan requires a verified runtime build, exact device mapping, backend-use
evidence, correctness comparison, and sustained calibration before enabling.

## Inputs and outputs

- Explicit absolute local paths and expected SHA-256 for executable and model.
  No PATH lookup, shell invocation, network retrieval, or automatic installation.
- Existing private workspace temp directory, normally `MCastTalkData/temp`.
- PCM16, mono, 16 kHz WAV with actual complete frames, up to 120 seconds and
  8 MiB. Each call snapshots the input into its own temporary directory, hashes
  exactly those bytes, and validates the snapshot before inference.
- Product languages `ko`, `en`, `ja`, `zh-CN` (`zh` alias). The CLI uses `zh`
  for Chinese. `jp`, auto-detection and arbitrary languages are not accepted.
- Fixed CPU settings are recorded with a settings hash. Each request launches
  a fresh process and loads a model. This is an initial offline adapter, **not
  the eventual streaming/persistent worker**.
- The response contains transcript/segments and runtime binary, model, source
  WAV, output JSON, settings hashes. It includes process launch-to-exit latency
  and total adapter latency. Model loading is included; queue/network latency
  and quality scoring are not. These values are not Gate 1 quality evidence on
  their own. Hardware and dataset/evaluator provenance belong to the caller's
  benchmark record.
- Valid empty segments yield `no_speech`. Missing assets, hash/version mismatch,
  unsupported settings, malformed output, process failure, timeout and cancel
  never yield a successful transcript or a canned fallback.

The deadline covers hashing, input staging, version check and inference. A
`threading.Event` cancels the Python API. Timeout/cancel kills and reaps the
direct CLI process, with a bounded terminate/kill grace period; that cleanup
may extend beyond the inference deadline. The supported CLI must not spawn
unmanaged helper children. Stdout/stderr from transcription are discarded to
avoid retaining raw content in logs. Output JSON is size-bounded and must stay
in the invocation's private directory, which is removed after every outcome.
The adapter is not an OS sandbox for an untrusted executable; only approved
runtime/model packs may be supplied.

## Actual CPU smoke evidence

`evidence/CPU_SMOKE_2026-09-19.json` records the real adapter response, not a test
fixture. The supplied official b5130 Windows CPU runtime reports version 1.9.4
and the reviewed source commit. The separately supplied `ggml-tiny.bin`
multilingual model was hash-verified; **tiny is a smoke-test model, not a
selected product-quality model**. Upstream `samples/jfk.wav` has an 11-second
duration and produced an English transcript through the actual subprocess.

This single run measured 1,815.56 ms process launch-to-exit, 2,286.33 ms total
adapter latency and 0.16505 process RTF. Model loading is included. The
`coldStartPerRequest` field means a fresh CLI process/model instance, not a
guarantee of cold OS filesystem caches. Cache state was uncontrolled. There is
no p95 estimate, independent quality score, four-language coverage or Vulkan
result. Retain these limits when referencing the result.

## Run when approved local assets are available

From `source/`, set `PYTHONPATH` to `windows/worker`. Supply actual hashes from
the approved pack manifest, not placeholder values:

```powershell
$env:PYTHONPATH = (Resolve-Path 'windows/worker').Path
python -m mcasttalk_worker --help
```

Required arguments are `--executable`, `--runtime-sha256`, `--runtime-commit`,
`--model`, `--model-sha256`, `--wav`, `--workspace-temp`, and `--language`.
Optional arguments are `--backend cpu`, `--threads 4`, and
`--timeout-seconds 120`. Success is JSON on stdout. Failure is JSON on stderr
and a nonzero exit status. There is no product dry-run/mock option and no
claim that a runtime or a model is licensed for redistribution merely because
its SHA-256 matches.

The Python API is `transcribe(RuntimeSpec(...), ModelSpec(...), source_wav,
workspace_temp, language="ko", cancel=threading.Event())`.

## Verify adapter contracts

```powershell
$env:PYTHONPATH = (Resolve-Path 'windows/worker').Path
python -m unittest discover -s windows/worker/tests -v
```

All subprocesses and model/executable bytes in these 20 unit tests are explicitly
fake. Full real inference acceptance remains pending: all four languages,
independent quality evaluation, resource/latency measurements and offline egress
verification. NMT, TTS, SFU, and host IPC are not implemented here.
