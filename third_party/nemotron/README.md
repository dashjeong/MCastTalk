# NemotronLabs on Galaxy S21 Ultra

This is a mobile evaluation adapter for **NVIDIA-NemotronLabs-RE-USE**, not the
previous **Real-time_RE-USE** Rust backend. See `provenance.json` for exact source,
weights/conversion revisions, hashes and separate licenses.

The requested realtime-first behavior is the deciding constraint. This model
needs a complete 2-second segment before inference; its original 30 bidirectional
layers cannot be reduced to the other model's 3-layer streaming API. The normal
MCastTalk app therefore keeps its existing noise-reduction path. The admission
policy is tested and used by the probe; it is not a live integration or a new
automatic fallback implementation in the shipped app.

## Mobile adaptations

- LiteRT 2.2.0 GPU or bounded 1–4-thread CPU execution, on a single owning worker.
- Verified local model path, full SHA-256 and exact tensor shape checks.
- Host STFT/iSTFT preserved byte-for-byte from the pinned Android reference.
- Reused input/output buffers, no queue and no loading or inference in capture.
- GPU timing includes reading the output; nonfinite values are rejected.
- Power, thermal and memory checks; cancellation cannot launch another chunk or
  overwrite a cancelled run with a successful result. A running native call is
  not forcibly freed; the worker closes it when the call returns.
- All model files and LiteRT code/assets stay outside the default build.

## Reproduce

Requirements: JDK 17, Android SDK 36, the repository's Gradle wrapper, authorized
ADB connection to the device. Obtain the hash-pinned converted model separately
under its OpenMDW terms. No network download occurs inside the APK.

```powershell
./gradlew.bat :core:audio:testDebugUnitTest :core:audio:assembleDebugAndroidTest -PnemotronResearch=true -PreuseResearch=true
adb install -r -t core/audio/build/outputs/apk/androidTest/debug/audio-debug-androidTest.apk
adb push C:\models\reuse_16k_2s_fp32.tflite /data/local/tmp/mcast-nemotron.tflite
adb shell run-as app.guidecast.core.audio.test mkdir -p files/nemotron
adb shell run-as app.guidecast.core.audio.test cp /data/local/tmp/mcast-nemotron.tflite files/nemotron/reuse_16k_2s_fp32.tflite
adb shell am instrument -w -r -e class app.guidecast.core.audio.nemotron.NemotronDeviceProbeTest -e nemotronEvaluation true -e accelerator gpu -e iterations 3 app.guidecast.core.audio.test/androidx.test.runner.AndroidJUnitRunner
adb shell run-as app.guidecast.core.audio.test cat files/nemotron-gpu-2.json
```

`accelerator` can be `gpu`, `gpu_fp32`, or `cpu`; `threads` defaults to 2 and is
bounded at 1–4. The report records the request, device build, model hash, phases,
loading time, every whole-chunk time, native heap/PSS and bypass reason. Use one
probe at a time. The package has a separate test application ID and never records
the microphone. Do not disable Android verification to install it.

The first private-model S21 test of Real-time_RE-USE measured p95 38.002 ms per
20 ms frame, failing the 12 ms budget. The initial NemotronLabs GPU test measured
114.439 s loading and 13.519–13.560 s per 2 s chunk (RTF about 6.78), around 2.8 GiB
PSS. These are short synthetic runs, not ASR, listening or sustained thermal tests.
Final signed probe hashes and results are kept in the developers' separate evidence records, which are not part of this repository.

No amount of RAM alone removes the graph's compute cost or future-audio requirement.
Shorter chunks, quantization, distillation or fused scan kernels would require a
separate equivalence/quality study; they are not claimed as verified optimizations.
