# RE-USE research evaluation

This code is a **research harness**, not an enabled MCastTalk noise-reduction option.
An independent review requires measured ASR improvement and Android
latency/thermal evidence before a user-facing option. Current production choices
remain device processing and RNNoise. No RE-USE inference is inserted in the
source broadcast, native relay, microphone capture or normal STT path.

The implemented app improvement is independent: live STT admission never waits
for its consumer, stale live copies are checked again at the final Galaxy provider
queue, and the operator sees missed-input warnings. Untagged finite/file input is
not expired. Recognition may lose words under overload; it no longer holds up the
source broadcaster for up to 100 ms per frame.

## Model and rights

`provenance.json` pins the **Real-time_RE-USE** research weights and CPU port.
The separate, noncausal NemotronLabs RE-USE is not used here. The weights are not
included in the repository, APK or package and are not automatically downloaded.
The operator must obtain the fixed model under its NVIDIA terms independently.
Both Kotlin and Rust verify its 54,055,764-byte size and SHA-256 before loading.
Read `NVIDIA-NSCL.txt` (or `licenses/NVIDIA-NSCL.txt` in the distributed research
folder): the model is restricted to noncommercial research and
education. The source-code license does not change those terms.

`reuse-rs` is pinned, unmodified upstream inference code under Apache-2.0.
MCastTalk adds JNI ownership, checksum checks, a two-thread inference pool, the
offline evaluator and a bounded STT-copy processor. Dependency license texts and
Rust standard-library attribution are included in the research assets.
The `jni-sys-macros` license copies come from its published crate's VCS revision
`64d77b7a5f119d7b55b4e2c169a4668067ff59e6`. `cesu8`'s published manifest,
README, Rust copyright notice and the complete selected Apache-2.0 text are retained.

## Windows reproduction

Tested toolchain: Rust 1.99.1, Windows gnullvm host, LLVM-MinGW 20261104,
Android NDK 28.2.13676358, JDK 17, Android SDK 36, Gradle wrapper 8.13.
Use the exact checked-in Cargo.lock. On a host with an installed Rust toolchain:

```powershell
cargo test --locked --release --manifest-path third_party/reuse/native/Cargo.toml
$env:REUSE_TEST_MODEL = 'C:\research\model.safetensors'
cargo test --locked --release --manifest-path third_party/reuse/native/Cargo.toml -- --include-ignored
cargo build --locked --release --bin reuse-eval --target x86_64-pc-windows-gnullvm --target-dir third_party/reuse/native/target --manifest-path third_party/reuse/native/Cargo.toml
./scripts/build-reuse-research.ps1 -AndroidNdk 'C:\Android\ndk\28.2.13676358' -RustToolchain 1.99.1
./gradlew.bat :core:audio:assembleDebugAndroidTest -PreuseResearch=true
```

The local build used `-RustToolchain stable-x86_64-pc-windows-gnullvm` (resolved
Rust 1.99.1). The Windows evaluator also needs the accompanying `libunwind.dll`
when built this way. Its LLVM license accompanies the binary package.

Standard app build (without the research property):

```powershell
./gradlew.bat :app:testDebugUnitTest :core:audio:testDebugUnitTest
./gradlew.bat :app:assembleAlpha :app:verifyPackagedThirdPartyLicenseAssets :app:assembleDebug
```

The Alpha build is unsigned without the developer's private signing configuration.
Do not present it as a signed release. The debug build is for internal testing.

After both builds, package with PowerShell 7, with the same Rust environment:

```powershell
./scripts/package-reuse-research.ps1 -OutputDirectory C:\research\new-package -LlvmMingw C:\toolchains\llvm-mingw -RustToolchain stable-x86_64-pc-windows-gnullvm
```

The packager rebuilds the desktop evaluator for an explicit Windows target and directory,
checks research library hashes and standard APK separation, includes
the Windows runtime and additional dependency licenses, and emits a SHA-256 manifest.
It refuses to overwrite an existing output directory. No weights are copied.

## Actual inference and quality comparison

```powershell
reuse-eval.exe C:\research\model.safetensors C:\research\mono16k.wav C:\research\new-result
python scripts/evaluate-reuse-transcripts.py matched-transcripts.json new-quality-report.json
```

In the distributed `research` folder, use `./reuse-eval.exe` and
`python ./evaluate-reuse-transcripts.py` instead; the `scripts/` prefix is for the
source checkout. `manifest.json` records the payload checksums; the outer package
manifest additionally includes the research ZIP checksum.

The evaluator accepts up to five minutes of 16 kHz mono PCM16 WAV, preserves the
original and emits `reuse.wav` plus timing JSON. It refuses to replace an existing
output directory. Timing alone is not a quality result. The transcript comparator
requires matched reference/original/RNNoise/RE-USE ASR text for each of cafe, wind,
traffic, crowd and near-mic conditions. Empty recognized text counts as an error;
missing evidence cannot be treated as zero error. Keep the ASR model/version,
source recording hashes and listening assessment with the report.

The library instrumentation package has a distinct package ID
`app.guidecast.core.audio.test`; it does not replace MCastTalk or record a microphone.
After a policy-compliant installation and manual model placement:

```powershell
adb install -t core/audio/build/outputs/apk/androidTest/debug/audio-debug-androidTest.apk
adb push C:\research\model.safetensors /data/local/tmp/mcast-realtime-reuse.safetensors
adb shell run-as app.guidecast.core.audio.test mkdir -p files/reuse
adb shell run-as app.guidecast.core.audio.test cp /data/local/tmp/mcast-realtime-reuse.safetensors files/reuse/model.safetensors
adb shell am instrument -w -r -e class app.guidecast.core.audio.ReuseResearchProbeTest -e reuseResearchAccepted true app.guidecast.core.audio.test/androidx.test.runner.AndroidJUnitRunner
adb shell run-as app.guidecast.core.audio.test cat files/reuse-timing.json
```

Read the model license before passing the acceptance argument. The probe uses
500 synthetic 20 ms frames, excludes 50 warm-up frames, rejects battery saver or
severe thermal state and fails if p95 exceeds 12 ms. It does not certify a 30-minute
thermal run or CER/WER improvement. A device installation-policy refusal must be
resolved through the device owner's approved process, not by disabling verification.

## Remaining gates

- Device installation/execution and p95 budget; 30-minute thermal/battery trial.
- Matched multilingual ASR improvement and three-person consonant-loss listening review.
- Before live STT wiring: moving-window timing admission, transition smoothing,
  thermal/power monitoring and native failure isolation. The bounded processor is
  currently unconnected research code, with an 80 ms deadline, four-frame queue,
  sticky original-audio fallback and worker-owned cleanup.
- PM review of those measurements before adding any settings option. No automatic retry.

The existing JNI implementation catches Rust panics and frees failed handles;
native aborts/allocator exhaustion still require process isolation before a live integration.
