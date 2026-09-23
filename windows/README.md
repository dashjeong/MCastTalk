# MCastTalk Windows

## 0.4.1 r8 bilingual admission fix — 2026-09-23

Administrative language slots/allowlists now remain independent of measured
translation workload. A mutually understood bilingual meeting can use original
audio/video without delay override, even while ASR is warming up or captions are
slow. Translation-dependent joins/changes still require pre-admission calibration
and cost checks; invalid policy changes preserve existing participants.

The UI identifies above-recommendation slots as conditional original-language use,
not a simultaneous-translation guarantee, and warns about disallowed input languages.
The automatic cap remains five installed languages (four in the current bundle).
See [the r8 verification report](BILINGUAL_ADMISSION_VERIFICATION_2026-09-23.md)
for the exact installer hash, 183 host tests, 38 real-model browser scenarios,
53 network-disabled Windows browser checks, fresh installation/relaunch/uninstall
with test workspace preservation, and outstanding performance limits.
This is a functional preview, not five-second or production-quality acceptance.

## 0.4.1 r7 resource-aware interpretation candidate — 2026-09-23

The latest changes deliver captions/audio progressively per language, preserve
full-volume original speech for the listener's primary and one additional
understood language, and check meeting language counts before join/preferences
changes. Administrative language settings cannot exclude existing participants.
Startup calibration and recent timings inform recommendations; they are not a
latency guarantee. Policies are session-scoped and reset to quality-first on restart.

Only Korean, English, Japanese and Simplified Chinese are installed. The 5/7/10
options require additional tested model packs and are currently disabled. This
laptop did not meet the five-second goal; the four-language browser trial uses an
explicitly acknowledged delay override. Local synthetic voice playback measured
8.3/11.1 seconds including the input utterance, not a five-second acceptance pass.
See [the r7 verification report](RESOURCE_LANGUAGE_VERIFICATION_2026-09-23.md)
for the exact new installer hash, network-disabled Windows result and limitations.

## 0.4.1 r6 offline media baseline — 2026-09-22

The current source adds real authenticated WebRTC audio/video and screen-track
replacement, explicit private-LAN HTTPS/WSS, and local STT → translation → TTS.
The 0.4.1 offline packaging path includes Python, models and CPU/Vulkan runtimes;
the old `package.ps1 -Type exe` path alone still produces a host-only package.
See `packaging/README.md` and `POSTBOOT_OFFLINE_VERIFICATION_2026-09-22.md` for exact
artifact/test status. The sections below describe the earlier foundation and
0.4.0 review, not the current 0.4.1 capability boundary.

Current language support is Korean, English, Japanese and Simplified Chinese.
Whisper small Q5_1 performs CPU STT, Qwen3-4B Q4_K_M uses llama.cpp Vulkan with
CPU startup fallback, and sherpa-onnx performs CPU TTS. Supertonic model terms
are OpenRAIL-M and must be accepted by meeting participants; they are not MIT.
An eight-participant mesh limit is not an eight-speaker performance guarantee.
Internet NAT traversal/SFU, Open WebUI integration, production signing, physical
LAN/GPU acceptance and human language-quality approval remain incomplete. The
first real network-disabled Windows execution found missing MSVC/OpenMP DLLs.
The final r6 installer with statically linked x64 AVX2 CPU/Vulkan executables
passed fresh network-disabled Windows Sandbox installation, actual local models,
33 browser checks, relaunch and uninstall with test workspace preservation.
Voice delivery still measured 31–33 seconds, not a low-latency acceptance pass.
The manual first-run wizard and physical devices were not tested by that run.

## Historical foundation through 0.4.0

This directory contains the Windows migration foundation. It is intentionally
separate from the Android modules until the cross-platform contracts have
passed the Gate 1 benchmarks.

## Current vertical foundation

- A deterministic MCastTalkData directory contract
- Offline model-pack manifest and integrity verification
- Hardware discovery with a provisional capacity profile
- AMD, Intel, NVIDIA, and Qualcomm accelerator discovery with portable Vulkan
  and DirectML candidates plus vendor-specific optional backends
- A dependency-free bootstrap CLI that can later be called by the native
  Windows launcher
- A loopback-only Ktor room host with WebSocket control messages
- First-run Windows setup in which the person launching the program chooses
  the workspace and creates its first administrator; no default credentials
- Persistent local ADMIN/USER/GUEST accounts, HttpOnly-cookie sessions, and
  authenticated HTTP account operations and WebSocket room admission
- Login/logout, personal password changes, and administrator UI for account
  creation, activation, role changes, and password resets; guests require an
  expiration and one allowed room
- A bundled, dependency-free participant PWA for independent input, publish,
  listen, and display language preferences
- Room-wide and per-recipient original-text chat, with server-side routing
- Participant search and private-chat shortcuts with account/connection labels
  to distinguish duplicate names; changing a draft's target through the shortcut
  requires confirmation
- ADMIN-only operator diagnostics: real aggregate room/connection counts, JVM
  heap/processor and workspace free-space observations, and explicit unsupported
  inference/media/network capabilities; no private chat, room names or paths
- A separate hash-pinned whisper.cpp CPU adapter with one real English smoke
  run; it is not wired into the host or included in the preview installer

The bootstrap does not download models and does not contact the network. The
current host is an authenticated control/chat preview. Audio/video capture,
host-integrated STT, NMT, TTS, SFU media delivery, TLS, and LAN/WAN binding are
not enabled. The complete offline model/runtime bundle is not assembled.
See `packaging/README.md` for the single-EXE preview boundary and
`worker/README.md` for the exact scope of actual STT evidence.

The chat-only `0.1.0`, account `0.2.0`, and operator-improvement `0.3.0` builds
are distinct development artifacts. Consult the root verification records for
the exact tested app-image and installer hash. Clean-machine installer execution
and manual first-run Swing dialogs remain unverified.

## Run

Use Python 3.11 or newer:

~~~powershell
cd windows/bootstrap
python -m unittest discover -s tests -v
python -m mcasttalk_bootstrap init-data
python -m mcasttalk_bootstrap probe-hardware
python -m mcasttalk_bootstrap verify-pack C:\path\to\offline-model-pack
python -m mcasttalk_bootstrap summarize-benchmark C:\path\to\evidence.json
~~~

The default data directory is MCastTalkData beside the packaged executable. In
source mode it is under the current working directory. First launch offers a
folder selector; an explicit `--data-dir` supplies the development data root.

## Safety and licensing

Every imported model pack must include model-pack.json. Files are checked for
size, SHA-256, safe relative paths, declared languages, and license policy.
Non-commercial or research-only assets are blocked. Copyleft assets are marked
for legal review rather than silently accepted.

Hardware tiers are provisional until the calibration benchmark has run. They
must never be presented as guaranteed concurrency or latency.

`backend-calibration.schema.json` records the self-test and sustained benchmark
for a specific model, backend, device, driver, and hardware fingerprint.
Portable backends are considered first, but the measured fastest passing route
wins; CPU remains a mandatory fallback. Detection alone never enables a GPU.

Gate 1 candidates and their current license posture are tracked in
`MODEL_CANDIDATES.md`. Benchmark evidence uses
`contracts/benchmark-evidence.schema.json`; the summarizer refuses incomplete
language coverage and cross-hardware comparisons.

Evidence v2 also pins model/runtime/settings/dataset/evaluator/workload hashes,
requires all directed NMT language pairs, and rejects different case sets or
evaluation conditions. TTS RTF uses generated audio duration. Summarization is
not a release approval. See `QUALITY_GATES.md` for measurable release gates,
chat privacy, and the distinction between contract tests and real model runs.

`USER_OPERATOR_BENCHMARK_2026-09-19.md` records the official-source competitor
review, newer model challengers and measured acceptance plan. Its GitHub counts
are sourced from `RESEARCH_SNAPSHOT_2026-09-19.json`, not a quality ranking.

## Run the room-host preview

Run these commands from the repository root (`source/` in the shared workspace).
Initialize a development data root, then launch the host interactively:

~~~powershell
$env:PYTHONPATH = (Resolve-Path windows\bootstrap).Path
python -m mcasttalk_bootstrap init-data --data-dir .run\MCastTalkData
.\gradlew.bat :windows:host:run --args="--data-dir=$((Resolve-Path '.run\MCastTalkData').Path) --port=8787"
~~~

For a new workspace, the Windows setup dialog asks for an administrator username,
display name, password, and password confirmation before starting the server.
There is no HTTP bootstrap, anonymous guest, self-registration, or default
administrator. An uninitialized workspace cannot start headlessly. Canceling
setup leaves the server stopped; a damaged account store fails closed rather
than recreating credentials.

Open `http://127.0.0.1:8787/` and sign in. The account display name is authoritative
in the meeting. ADMIN accounts manage users and guests; USER accounts participate
in rooms; GUEST accounts can enter only their assigned room before expiration.
The guest-creation UI suggests 24 hours, with server-side validation. Password
changes/resets and account security changes invalidate affected sessions.
Successful account switching replaces the browser's prior session; logout
revokes that session rather than every other login of the same account.

### Windows 0.4.0 review candidate — not a complete media release

- `/listen/{roomId}` is an **authenticated, explicit, read-only join**. The same
  room scope, account identity, expiration, logout and administrator revocation
  checks apply to `/ws/v1/rooms/{roomId}/listen`. Links never grant permission.
- Read-only participants can receive room/private text chat but cannot publish
  chat, audio or subtitle events. The listener role is fixed by the server route.
- Raw PCM and client-authored inference subtitles are **disabled** with
  `MEDIA_NOT_READY` / `INFERENCE_NOT_READY`. Prototype transport, mixer and UI
  code are retained, but do not constitute integrated STT/NMT/TTS.
- Extra service tabs are marked unavailable. The four service enums are planned
  contracts, not four completed independent workspaces.
- Loopback only: no LAN/WAN access, mobile QR onboarding, 1-second admission
  guarantee, real translated speech, video, recording or file transcription.
- The September 22 review supersedes earlier media-completion claims. Read
  `REVIEW_2026-09-22.md` for evidence, limitations and the commit approval gate.

## Verify

~~~powershell
python -m unittest discover -s windows\bootstrap\tests -v
.\gradlew.bat :windows:host:test
~~~
