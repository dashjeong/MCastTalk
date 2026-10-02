# 0.2.46-beta-c translation, session memory and voice preparation

Date: 2026-10-01 KST. Code 56. This scoped beta keeps E2B/E4B selection,
the existing ASR sentence-boundary policy and PCM pacing. It adds bounded local
E4B broadcast context, adjusts translation instructions, and makes Android
offline voice preparation failures actionable. See [translation review](E4B_TRANSLATION_REVIEW.md)
and [voice preparation](TTS_VOICE_PREPARATION_FIX.md).

## Build and exact artifact

- Final full local gate: **SUCCESS, 4m 18s**, 610 tasks (46 executed,
  564 up-to-date). Command: `./gradlew --no-daemon --max-workers=1
  testDebugUnitTest :core:stream:test :core:translation:test :app:lintAlpha
  :app:assembleAlpha :app:assembleAlphaAndroidTest
  :app:verifyPackagedThirdPartyLicenseAssets`.
- 205 Debug/core XML reports: **1,448 tests, 0 failures/errors/skips**.
  Up-to-date/cached test results are included, not presented as all freshly run.
  Alpha lint: **0 errors, 84 warnings, 6 hints**. Packaged third-party assets PASS.
- `MCastTalk-0.2.46-beta-c.apk`: **96,473,152 bytes**, SHA-256
  `b73844cad224e733ccea843cbcb1d47b9d56494b82c118e4d16a6c7d9a4bd330`.
- Test APK: **4,940,009 bytes**, SHA-256
  `df5ddc62317dd431c2706b9070a61f0bc4cb0091ca215538d3d0eaf51ed79b64`.
- Both passed existing-release-signer v3 verification and 16 KiB alignment;
  installed hashes on API 35 ARM64 matched these exact files. Signer certificate
  SHA-256: `afd9d964c7161f0052d16b0065e6dec14861900cfb876b18df639734ccf29ff3`.
  Temporary emulator storage-threshold changes were restored; model caches and
  app data were preserved. No dependency or model weight was added or changed.

## Exact-artifact native validation

- `SystemVoiceSetupDeviceTest`: **3/3 PASS, 43.338s**. Real settings flow with
  Chinese/Google preference and no installed engine, actionable error/recheck,
  independent idle input/broadcast, installer routing, cancelled installer return
  and 2x-font component controls. External installer activity was intercepted in
  the cancellation test; vendor download success is not claimed.
- `E4BQualityCorrectionDeviceTest`: **2/2 PASS, 63.081s**, with new assertions
  for sign compliance, cargo loading and traffic-signal compliance. All 23 native
  outputs exactly match beta-b: its AI-assisted assessment of **20 PASS, 2 WARN,
  1 FAIL (C2 causative)** is retained, not promoted to 23 semantic passes.
- `RepeatedSemanticSpeechDeviceTest`: **4/4 PASS, 127.761s**. Real native STT and
  E2B/E4B/delayed-PCM E4B translation repeat the public recorded FLEURS utterance;
  the safety-sign meaning assertion passes. Audio provenance: [fixture notice](../app/src/androidTest/assets/fixtures/FLEURS_NOTICE.txt),
  Google FLEURS `ko_kr/test`, row 7, sample 1959, revision
  `70bb2e84b976b7e960aa89f1c648e09c59f894dd`, CC-BY-4.0, 7.02 seconds.
  These short repeated inputs are not 30 two-minute recordings or a long soak.
- Article comparison: **1/1 PASS, 273.076s**, 22 Korean sentences into English,
  Japanese and Simplified Chinese, **66/66 completed**. AI-assisted source/Gemini
  review: baseline **46 PASS, 13 MINOR, 6 MAJOR, 1 NO_OUTPUT**; final **52 PASS,
  11 MINOR, 3 MAJOR**. Completion is not semantic approval. All source/reference/
  actual outputs and remaining issues are in the [comparison report](validation/0.2.46-beta-c/COMPARISON_REPORT.md).
- Separate authored syntax/session-memory controls: **1/1 PASS, 100.617s**,
  **30/30 completed**. Codex/Antigravity review found the targeted core meanings
  preserved; 27 nonempty memory hints stayed within the combined context budget.
  There is no memory-OFF control, so this does not prove a causal quality gain.
- Operator E4B broadcast integration: **1/1 PASS, 119.147s**. Actual controls,
  playback capture, native STT, E4B, TTS and non-silent English WebSocket PCM.
  Stopping the broadcast preserved independent input control.
- First PCM after semantic final: **5,205ms**; synthesis observer completion:
  **8,397ms**. Beta-b measured 5,117ms in one emulator run: **no first-audio
  speedup is demonstrated**. These single samples are not physical S23 p95.
- Total final instrumentation: **12/12 PASS** across six runs. After all runs,
  both installed APK hashes still matched the exact artifacts above. The crash
  buffer restricted to the final test window contained no entries.
- Product-source CI for `bf25bf1d36b6a249fd19984b237234b6ee9ec4df`:
  [completed successfully](https://github.com/dashjeong/MCastTalk/actions/runs/36810221777).
  Final report-only changes do not change the tested APK. Public-snapshot unit
  tests **9 PASS**; listener scheduling, resampling, localization and speaker-mic
  script checks **PASS**. Public-file contamination scan **PASS (758 tracked
  files)** and branding/version/license-parity check **PASS** on the final staged
  snapshot; `git diff --cached --check` is clean.

## Evidence and limitations

Baseline and rejected candidates are retained under the local
`build/evidence/tts-language-preparation/` directory; final runs are in
`fidelity-restored/`. Earlier test-harness failures are not counted as passing:
the installer monitor initially intercepted the test activity itself; a fake
speech interface and coroutine-context assertions in new tests needed correction.
The repaired tests were rerun. Candidate 1's wrong-target translations and
candidate 3's comparator leakage caused those APKs to be rejected. The faster
`e0468192...` candidate also failed actual repeated speech **2/4 in 95.082s**,
even though 66 article requests completed. It was not published. Restoring the
established instruction configuration recovered all 23 prior outputs with the
same non-thinking setting, and the strengthened regression tests passed.

These are emulator and automated checks, with AI-assisted translation review.
Physical Galaxy/One UI, actual Google/Samsung language-pack download, human voice
naturalness, Android/iPhone acoustic playback and 2/8-hour stability were not
tested. The physical S23 prepared-model first-PCM p95 <=2,000ms release gate
remains **unproven**. This beta is not a claim of commercial qualification,
zero latency, zero mistranslation, model-weight training, or complete validation
of every historical feature request. Minimum SDK remains 30; API 29 installation
is not claimed.

---

# 0.2.46-beta-b E4B quality correction validation

Date: 2026-10-01 KST. Code 55. The listening fix below is retained. E4B-only
translation instructions preserve spoken meaning without changing model weights,
source text, E2B prompts or sentence-completion thresholds. See the
[Gemini comparison, all 23 samples and remaining errors](E4B_TRANSLATION_QUALITY_REPORT.md).

## Build and exact distributable

- Full local Gradle gate: **SUCCESS, 4m 3s**, 610 tasks (63 executed, one from cache,
  546 up-to-date): `testDebugUnitTest :core:stream:test :core:translation:test
  :app:lintAlpha :app:assembleAlpha :app:assembleAlphaAndroidTest
  :app:verifyPackagedThirdPartyLicenseAssets`.
- 201 Debug/core XML reports: **1,409 tests, zero failures/errors/skips**. Cached and
  up-to-date results are not represented as freshly executed tests.
- Alpha lint: **0 errors, 83 warnings, 6 hints**; packaged license checks PASS.
  Public snapshot unit tests: **9 PASS**; branding, listener scheduling/resampling,
  localization and speaker-mic script gates PASS.
- A test-only semantic assertion was added after that build. The Android test APK
  was rebuilt successfully in 27s (225 tasks, six executed, 219 up-to-date).
- Product `MCastTalk-0.2.46-beta-b.apk`: **96,440,384 bytes**, SHA-256
  `759b0df098284ce73744199a754d6a2491b9f035002efee199d39bf08ce435b0`.
- Test APK: **4,907,241 bytes**, SHA-256
  `ec412696b6c4a224a40e7c675b55c25237de1b1b97144e8293dcacabb392514e`.
- Both passed v3 signature and 16 KiB alignment verification with the existing
  release signer. Both were installed on the API 35 ARM64 emulator and their
  installed hashes matched. A temporary storage threshold was restored to its
  original unset value in `finally`; model caches and app data were preserved.

## Exact-artifact native tests

- `RepeatedSemanticSpeechDeviceTest`: **PASS 4/4, 132.976s**. Native STT plus
  E2B, E4B and E4B with 800ms delayed PCM timestamps each processed three live
  repetitions of the public FLEURS fixture. All nine translations passed the new
  assertion that the instruction to follow signs survives recognition and translation.
  This is a narrow semantic regression check, not general translation certification.
- Operator E4B broadcast integration: **PASS 1/1, 119.857s**. Real app controls,
  MediaProjection playback capture, native STT, E4B, TTS and non-silent English
  WebSocket PCM passed. Broadcast stop preserved independent input operation.
- Operator first audio after semantic final: **5,117ms**; synthesis observer
  completion: **8,532ms**. This is one emulator sample, not S23 p95. The physical
  2,000ms p95 release qualification remains **unproven**; this beta must not be
  described as satisfying it. Prior beta-a's one-sample timing is retained below.
- Final E4B quality class: **PASS 2/2, 60.415s**, completing 23 actual native
  translations. All 23 outputs exactly matched the selected candidate's outputs;
  semantic assessment therefore remains **20 PASS, 2 WARN, 1 FAIL**. Completion
  assertions are not semantic approval. Provider latency mean/median was
  **2,383.50/2,452.50ms** for 16 samples and **2,512.57/2,417ms** for seven controls.
- After all tests, both installed APK hashes still matched the artifacts above.
  The crash buffer restricted to this final test window contained no crash entries.
- Antigravity supplied comparison references and earlier candidate work. Codex
  executed and reviewed these final beta-b tests while the Mac lock prevented
  further Antigravity UI approvals. Do not attribute the final run to Antigravity.

Local evidence: `build/evidence/e4b-quality-correction/final-beta-b/`.
Actual Galaxy/One UI, physical browser playback, human voice naturalness and 2/8-hour
stability were **not tested**. The existing product minimum SDK is 30, so API 29 is
not claimed as an installation target. This scoped beta does not re-certify all
four-language, physical-device or long-running product scenarios.

---

# 0.2.46-beta-a listening regression validation

Date: 2026-09-30. Code 54; base `b3f08cb`. This hotfix preserves E2B/E4B selection,
models and sentence-completion thresholds. See [cause and evidence](E4B_LISTENING_REGRESSION_REPORT.md).

## Reproduced defect and scope

An observation more than 500 ms behind the segmenter tick erased silence already measured
in contiguous PCM. A completed sentence therefore remained pending in the controlled lag
case. Both timestamps use the same monotonic clock. The change retains measured silence,
without adding unobserved time when observations are stale. Whether E4B contention caused
that delay on the reporting user's device is **not proven**.

- Before-fix regression: **FAIL 1/1** (`baseline-lagged-quiet-FAIL.xml`).
- After-fix segmenter suite: **43 PASS**, including six added cases for delayed observation,
  interrupted input, discontinuous frames, incomplete clauses, resumed speech and duplicate finals.
- Full Gradle gate: **BUILD SUCCESSFUL**, 610 tasks (48 executed, 562 up-to-date),
  `testDebugUnitTest :core:stream:test :core:translation:test :app:lintAlpha
  :app:assembleAlpha :app:assembleAlphaAndroidTest :app:verifyPackagedThirdPartyLicenseAssets`.
  Selected Debug/core XML totals: **1,406 tests, zero failures/errors/skips**; cached/up-to-date
  tasks are not claimed as fresh executions. Translation core: 261 tests.
- Alpha lint: **0 errors, 83 warnings, 6 hints**. Packaged license/native/glossary checks: PASS.
- Public snapshot unit tests: **9 PASS**. Public snapshot, branding, listener scheduling,
  resampling, localization and speaker-mic script checks: PASS.

## Final package and build after the provider-label correction

- Full unit/lint/distributable/license gate: **SUCCESS in 3m 52s**, 610 tasks
  (29 executed, 581 up-to-date), `provider-label-final-build.log`.
- Debug/core XML rechecked: **1,406 tests, 0 failures/errors/skips**, 201 XML reports.
  Alpha lint: **0 errors, 83 warnings, 6 hints**.
- Product APK: **96,440,384 bytes**; SHA-256
  `ae7ee7c7e8932f326af13a897530bfedce188823a690f156e6488ad21fb6bfc1`.
- Test APK: **4,894,953 bytes**; SHA-256
  `d7557b79a845230b21f13edc07d569bb938a3b56395ae75dce2c546f4c72c96a`.
- Both signature/alignment checks passed. Product APK installed over the earlier candidate;
  both installed file hashes match (`installed-final-apk-hashes.txt`).
- Installation initially failed for emulator space. Existing E4B XNNPACK cache was temporarily
  offloaded only after its on-device SHA matched the existing host backup, then restored with
  the same owner/mode/mtime/security context. Restored cache SHA-256:
  `4792a79795718ad1244daae2994232449687558586fbfb19ca1c78378b151372`.
  Models and user data preserved; `/data` returned to 404 MiB available. Temporary storage
  threshold changes were restored to the original unset value and were insufficient alone.
- Exact-artifact operator recheck by Antigravity, reviewed against stdout by Codex:
  **PASS 1/1, 165.083 s** (`device-e4b-operator-provider-label-fixed.txt`).
  Real operator start/stop, MediaProjection capture of recorded FLEURS speech, native STT,
  E4B translation, TTS and non-silent English WebSocket PCM all passed. Stopping broadcast
  preserved the independently active input. This is a WebSocket PCM assertion, not physical
  browser playback or human listening approval.
- Actual native translation: **2,994 ms** for the 52-character recognized utterance.
  First audio after semantic final: **3,714 ms**; synthesis observer completion: **8,045 ms**.
  One emulator sample exceeds 2,000 ms and does **not** satisfy the physical S23 p95 gate.
  Timing evidence: `operator-final-public-fixture-timing.log`.
- Exact-artifact `RepeatedSemanticSpeechDeviceTest`: **PASS 4/4, 127.972 s**
  (`final-repeated-speech.txt`). Includes baseline STT plus E2B, E4B and 800 ms delayed E4B;
  each case receives the recorded utterance three times. No skips. Nine actual translations
  are retained in `final-fixture-translations.txt`.
- Crash buffer collected after the final repeated-speech run: **empty (0 bytes)**.
  This describes that run window only; earlier tool collisions/interrupted runs remain documented.

An installation-environment recovery attempt failed **1/1 in 2.934 s**: cache bytes matched,
but the temporary offload restoration initially preserved seconds instead of milliseconds.
The existing completion marker correctly rejected the changed mtime and required cache-generation
space. The original mtime `1790642522472` was restored exactly from the unchanged marker;
no marker was forged or validation bypassed. Codex read the failure output before the
next run overwrote `device-e4b-operator-provider-label-fixed.txt`; the separate raw file was
not preserved. The observed failure remains recorded here and in the task's tool output,
and is not counted as a PASS or represented as a surviving raw log.
Antigravity subsequently copied its captured tool transcript into
`operator-mtime-mismatch-2.934s-FAIL.txt`. The file is explicitly marked transcript-derived;
it is not represented as the overwritten original file.

## Initial signed candidate (before the provider-label correction)

- Application: `MCastTalk-0.2.46-beta-a.apk`, 96,358,265 bytes, code 54.
  SHA-256: `f01b433c1717921be5621dbe6597fc1f7fb288630eaea74ed71bfaea1d11b49a`.
- Initial instrumentation (four speech checks and English TTS preparation): 4,825,122 bytes.
  SHA-256: `1f6ea7a0c864ca8952c51a6bf675a40c5aaa900da184d6a4eb7053ee151a1b83`.
- A subsequent test-only diagnostic build adds changed-state logging while waiting for broadcast
  preparation. It preserves the success predicate and production APK. Build: 44 s, 225 tasks
  (6 executed, 219 up-to-date). New test APK SHA-256:
  `923830cb7c89f89e60660ce8eda1497e1806010bb1235b533128198d3eea6fa6`.
  Signature/alignment passed and installed test hash matched. Original test APK is retained.
- Final test-only build recognizes the **whole** normal 6 GB memory-mode notice, rather than
  waiting indefinitely before playing the fixture. Errors are not matched by this added rule;
  actual Gemma output and non-silent audio assertions remain required. Build: 32 s, 225 tasks
  (6 executed, 219 up-to-date). Test APK: 4,894,953 bytes, SHA-256
  `d7557b79a845230b21f13edc07d569bb938a3b56395ae75dce2c546f4c72c96a`.
  Signature/alignment and installed hash checks passed; production APK hash unchanged.
- Both: v3 signature and 16 KiB alignment PASS. Existing certificate SHA-256:
  `afd9d964c7161f0052d16b0065e6dec14861900cfb876b18df639734ccf29ff3`.
- Both installed with `adb install --no-incremental -r` on dedicated ARM64 API 35 AVD.
  Installed `base.apk` hashes match the signed files above. Models and app data preserved.
  Emulator-only installation storage threshold was temporarily reduced to 200 MiB and restored
  to its original unset value; no production storage policy was changed.

## Initial candidate speech checks

All use the recorded public Google FLEURS `ko_kr/test` row 7, sample 1959: 7.02 seconds,
16 kHz mono S16LE, 224,640 bytes. Fixture SHA-256:
`b35aa5acf7ff72a4ec1b68415957ac9e7fe89268312cc8f5e5325a88acea841f`.
[Source](https://huggingface.co/datasets/google/fleurs), revision
`70bb2e84b976b7e960aa89f1c648e09c59f894dd`, CC-BY-4.0;
see `app/src/androidTest/assets/fixtures/FLEURS_NOTICE.txt` for attribution.

| Instrumentation method | Observed result |
|---|---|
| `repeatedPublicKoreanSpeechKeepsMeaningWordsInEachLiveSentence` | PASS, 29.277 s, three utterances |
| `e2bStandardSpeechToTranslationPipelineSucceeds` | PASS, 32.054 s, three utterances and translations |
| `e4bModelOptionSpeechToTranslationPipelineSucceeds` | PASS, 39.440 s, three utterances and translations |
| `e4bModelOptionWithDelayedPcmCaptureTimestampsSucceeds` | PASS, 35.487 s, three utterances and translations, 800 ms injected capture timestamp lag |

These four tests exercise real recognition/segmentation; the three model cases use actual
Gemma inference through a test channel consumer. They do not by themselves prove the
BroadcastService/operator UI journey. The final exact-artifact operator PASS is recorded above.

First operator UI attempt: **not passed; interrupted after a confirmed preparation error**.
The AOSP emulator had no available Android TTS engine and no prepared Moonshine English
voice. `SpeechSynthesisPreparationException` for `en` is preserved in
`operator-tts-unprepared-failure.log`. The helper waited for provider readiness instead of
surfacing this preparation failure promptly. This attempt does not prove translated audio
delivery. Voice preparation and the subsequent operator run are separate evidence.

Environment preparation: existing
`MoonshineTtsDeviceIntegrationTest#sequentialEnglishGuideUtterancesSynthesizePlayablePcmWithMonotonicFrames`
on the same installed artifacts **PASS, 38.597 s**. The production provider prepared English
assets and synthesized five test sentences, checking non-silent PCM and monotonic frames.
This is emulator buffer/PCM evidence, not a human listening judgment.

The next operator attempt also waited at preparation; it was interrupted for the diagnostic
test APK replacement. Interruption by `am force-stop` is not a spontaneous app crash or a
test PASS. A separate concurrent `uiautomator dump` process crashed with “UiAutomationService
already registered”; it was stopped and is not counted as successful application validation.

The diagnostic run identified a test readiness mismatch: the service reached `LIVE` with
`Gemma · 영어 · English`, but the expected-notice helper rejected its normal 6 GB memory
mode message. Therefore no speech fixture had yet played. This was interrupted and preserved
in `operator-memory-notice-diagnostics.log`; the final test helper accepts that exact normal
notice with an anchored whole-string match. Product behavior is unchanged by this test fix.

With that test fix, the operator journey reached actual translation and synthesis, but **FAILED
1/1 in 104.234 s** at the provider-label assertion (`operator-provider-label-FAIL.txt`). E4B
processed the 52-character recognized utterance in 2,999 ms. The voice-preparation callback
overwrote `channelSummary` with the default `ML Kit` label despite an active Gemma route.
`updateProviderFallbackUi` now derives an omitted label from its supplied active-route flag;
explicit failure/recovery labels remain authoritative. The package was rebuilt and the same
operator test passed as recorded above; initial candidate hashes are historical only.

## Translation quality finding — functional PASS does not mean semantic PASS

Reference: “그래도 관계자의 조언을 듣고 모든 표지판을 **지키고** 안전 경고에 세심한 주의를 기울여야 합니다.”
Observed ASR substituted **시키고** for **지키고**. E2B rendered “follow all signs”, while
E4B rendered **“order all signs”**, a contextual mistranslation. On the final APK this
occurred in all six E4B repetitions of the same recording, while all three E2B repetitions
rendered “follow all signs”. All nine tagged outputs were retained locally by Codex.
This one repeated sample is not a multilingual accuracy benchmark. No claim of improved
E4B translation quality is supported. E2B remains available and is the default selection.

Final provider translation times (not first-audio latency): E2B **1909/1233/1207 ms**;
ordinary E4B **2226/2136/2093 ms**; delayed E4B **2196/2211/2174 ms**.
These few emulator samples cannot establish a p95 or the Galaxy S23 2,000 ms gate.

## Limits

No physical Galaxy/Samsung One UI, physical microphone/browser, outdoor noise, voice-naturalness,
two-hour/eight-hour stability or S23 first-audio p95 result is claimed. The user's precise
device cause and broad multilingual behavior remain unproven. Local evidence is under
`build/evidence/e4b-listening-hotfix/`; private device logs and signing data are not published.

---

# Historical: 0.2.46-beta E4B validation (package and device gates completed)

Date: 2026-09-29. Base: public `v0.2.45-beta-c`, `ac8039f`.
Scope: preserve existing E2B models and add general E4B-IT LiteRT-LM as a separate option.
The Translate Sub E4B GGUF model is excluded. See [compatibility review](E4B_MODEL_COMPATIBILITY.md).

Completed checks for this release candidate:

- `python3 scripts/test-public-snapshot.py`: 9 tests, OK.
- `python3 scripts/verify-public-snapshot.py`: PASS (725 tracked source files at execution).
- Listener scheduling, resampling, localization, speaker-mic JavaScript regression scripts: PASS.
  These are script tests, not physical microphone/browser measurements.
- Public branding/version/license parity regression: PASS.
- Previous public C APK hash and extracted glossary size/hash matched their pinned records.
  The exact glossary was restored solely as an ignored local build input.

- Full Gradle gate (`final-gradle-gates-online.log`): **BUILD SUCCESSFUL in 4m 57s**, 610 tasks (89 executed,
  21 from cache, 500 up-to-date). Unit XML totals: **1,400 tests; 0 failures/errors/skips**. This includes
  app Debug 546, Gemma 134 (including 13 new pure-Kotlin storage policy and marker regression tests),
  translation core 255 and stream core 28. Cached task reuse is not claimed as fresh execution.
- Alpha lint: **0 errors, 83 warnings, 6 hints** (58 baseline warnings + 25 online notifications:
  15 NewerVersionAvailable + 10 GradleDependency). `verifyPackagedThirdPartyLicenseAssets`: PASS,
  including pinned Moonshine/ONNX binaries and glossary hash.
- Final production package: `app.guidecast.transmitter.alpha`, `0.2.46-beta`, code 53; 96,358,265 bytes.
- Final production APK SHA-256: `3575b5f40ed10496d0cc1683632ed0b2cd72c2ca3ced5a21d4f2fe636491c5ce`.
- Final androidTest package: `app.guidecast.transmitter.alpha.test`, code 0; 4,796,450 bytes.
- Final androidTest APK SHA-256: `1576907e7b778f1004530fd2023166b59bdbd00221448116771fdbb3d02a777b`.
- APK signature verification and 16 KiB alignment: PASS. Scheme v3 signed, certificate SHA-256 matches
  public release: `afd9d964c7161f0052d16b0065e6dec14861900cfb876b18df639734ccf29ff3`. 16 KiB `zipalign -c -P 16 -v 4`: Verification successful.
- On-device installation and hash verification on dedicated API 35 ARM64 AVD (`mcasttalk_043_api35`):
  - `adb install -r` succeeded for both APKs. Production: code 53 / `0.2.46-beta`; test: code 0.
  - Device file `sha256sum /data/app/.../app.guidecast.transmitter.alpha.../base.apk`:
    `3575b5f40ed10496d0cc1683632ed0b2cd72c2ca3ced5a21d4f2fe636491c5ce` (bit-for-bit match).
  - Device file `sha256sum /data/app/.../app.guidecast.transmitter.alpha.test.../base.apk`:
    `1576907e7b778f1004530fd2023166b59bdbd00221448116771fdbb3d02a777b` (bit-for-bit match).
- Dedicated on-device instrumentation gate (3 PASS executed individually on API 35 ARM64 emulator):
  1. Negative low-storage guard test (`GemmaE4BModelDeviceTest#e4bLowStorageRejectsBeforeNativeAndPreservesE2B`):
     - Result: **OK (1 test)**, elapsed **4.228 s** (`e4b-instrumentation-low-storage-negative.log`).
     - Condition: 540,307,456 bytes (~515 MiB) free space on `/data` with absent completion marker (< 2,528 MiB required).
     - Assertion: JNI entry rejected prior to native cache allocation, threw `IllegalStateException` with actionable
       Korean storage error ("저장 공간"), preserved E2B as `appliedVariant` and `selectedVariant`, and E2B recovery self-test cleanly returned `"Hello"`.
  2. Positive cache creation test (`GemmaE4BModelDeviceTest#appServicePreparesRunsE4BAndRestoresE2B` Run 1):
     - Result: **OK (1 test)**, elapsed **36.44 s** (`e4b-instrumentation-positive-run1.log`).
     - Condition: Host backup created (`/private/tmp/mcasttalk-e4b-cache-backup/`, 2,210,334,416 B, SHA-256 `4792a79795718ad1244daae2994232449687558586fbfb19ca1c78378b151372`)
       and emulator E4B cache cleared to provide 2.7 GiB free space.
     - Execution: Baseline E2B passed (`"Hello"`). E4B initialized and translated test Korean `안전하게 대피해 주시기 바랍니다.`
       to `Please evacuate safely.` in **1,237 ms**.
     - Cache & Marker: XNNPack cache generated (`gemma-4-E4B-it.litertlm_1790637169_3659530240.xnnpack_cache`, 2,210,334,416 bytes).
       Atomic completion marker written: `cache_completion_marker.json` (422 bytes, modelSha `0b2a8980...`, backend `CPU`,
       runtimeVersion `LiteRT-LM Android 0.16.1`, cache length 2,210,334,416 B).
     - Rollback: Restored E2B standard model, verified exact E2B checksum and size preserved, post-rollback self-test passed (`"Hello"`).
  3. Positive cache reuse test (`GemmaE4BModelDeviceTest#appServicePreparesRunsE4BAndRestoresE2B` Run 2):
     - Result: **OK (1 test)**, elapsed **17.468 s** (`e4b-instrumentation-positive-run2.log`).
     - Condition: Executed directly on 659 MiB free space (< 2,400 MiB cache budget).
     - Assertion: Valid marker verified, exempting cache regeneration and requiring only 128 MiB runtime safety.
       E4B initialization & translation completed in ~6.1 s (translation request elapsed: **1,417 ms**, output: `Please evacuate safely.`).
       Rollback cleanly restored E2B (`"Hello"`).
- Final crash-buffer verification: `adb logcat -d -b crash` was empty when collected after
  the two successful runs. This does not erase the earlier native failure or prove long-term stability.
- UI Verification Note:
  - Settings UI options for 3 models (`E2B 기본형`, `E2B GPU형 (실험용)`, `E4B 기본형 · LiteRT-LM (시험용)`) were verified
    with screen hierarchy dumps and screenshots (`10_model_options_dump.xml`, `10_screenshot_model_options.png`,
    `11_model_e4b_selected_dump.xml`, `11_screenshot_e4b_selected.png`). On the final APK, model switching and selection
    were verified through the production service/provider by the 3 instrumentation tests above.
    The 10/11 visual artifacts belong to the earlier candidate, not the final APK. A complete final
    model-card visual capture was not obtained; only its space-guidance text changed after that capture.
- Historical Failures Preserved in Local Evidence:
  - First test run: `FAIL, 1 initialization error` (JUnit rejected non-void return from runBlocking; fixed with `: Unit`).
  - Resumed download SHA mismatch: `FAIL, 1 test / 1 failure` (`e4b-instrumentation-resumed-range-mismatch.log`, 177.02 s). Cause remains unconfirmed; the upstream pinned hash matched metadata.
  - Low-storage native XNNPack buffer exhaustion: `SIGABRT` (`e4b-instrumentation-clean-download.log`, 299.143 s, 146 MiB free).
- Limitations & Release Boundary:
  - All device tests were conducted on a dedicated API 35 ARM64 AOSP emulator (`mcasttalk_043_api35`).
  - Never claim physical Galaxy S23, Samsung One UI, physical browser audio scheduling, outdoor-noise, or 2-hour stability.
  - S23 first-audio p95 release gate (2,000 ms) and human voice naturalness remain unproven on physical hardware.
  - GPU backend XNNPack cache is not supported/verified (policy restricts cache marker strictly to CPU backend).
  - Multilingual translation models beyond English/Korean are not tested for E4B.

Disposition: Package and device verification gates completed. Handoff to Codex for final review, commit, and release publication.

Local evidence is under `build/evidence/e4b-20260929/` (excluded from public source).

---

# Historical 0.2.45-beta-c validation

Validated product source: `7107e69`; version-check alignment: `dfbcfca`.
[Change scope](RELEASE_0_2_45_BETA_C.md). This is a **prerelease with an unresolved live-microphone gate**, not a stable-release qualification.

## Artifact and source gates

- Package `app.guidecast.transmitter.alpha`, version `0.2.45-beta-c`, code 52.
- Exact APK: 96,424,000 bytes; SHA-256 `837c9bbf99479b93aa365ed50529cb58b1dbaf25e850963810d046067c16d0d6`.
- Test APK: 4,829,417 bytes; SHA-256 `8f3c2b02f30669fa25d57659bb8452d04c34e203184cc9c665a828f6a557b541`.
- Existing public certificate SHA-256: `afd9d964c7161f0052d16b0065e6dec14861900cfb876b18df639734ccf29ff3`.
- Signature and 16 KiB alignment checks pass. The dedicated emulator installed the public Beta
  (SHA-256 `64e994a7d0e1c128ae4c131f05f609cbad208f6757119636b97edc289eaa3239`)
  and then updated with `adb install -r` to this exact C artifact; installed hashes match.
  This verifies update installation, not a new full settings/data-migration audit.
- New disk-stream regressions: **12 PASS**. Current Alpha XML: **546 tests**; translation core
  **255**; stream core **28**, all with zero failures/errors/skips. Unchanged Gradle tasks may
  reuse results; these counts are not all claimed as fresh executions.
- Alpha lint: **0 errors**. Packaged third-party license checks pass.
- [GitHub CI run 36000174735](https://github.com/dashjeong/MCastTalk/actions/runs/36000174735)
  passed on `dfbcfca`: public-source boundary, web audio/localization, unit tests, lint and assembly.
  The preceding run failed because the branding gate still expected code 51 / `-beta);
  it was updated to require code 52 / `-beta-c`, without weakening the version check.
- Kotlin and Gradle source hashes match the signed artifact's frozen build manifest.

Local commands used JDK 17, offline Gradle and one worker:

```sh
./gradlew testDebugUnitTest :core:stream:test :core:translation:test :app:lintAlpha --offline --no-daemon --max-workers=1
./gradlew :app:assembleAlpha :app:assembleAlphaAndroidTest :app:verifyPackagedThirdPartyLicenseAssets --offline --no-daemon --max-workers=1
node scripts/verify-public-branding.mjs
python3 scripts/test-public-snapshot.py
python3 scripts/verify-public-snapshot.py
git diff --check
```

## Exact-APK execution results — 2026-09-24 UTC

Antigravity executed these on a dedicated Android 15/API 35 ARM64 AOSP emulator.
Codex independently checked the terminal JUnit counters, installed APK hashes and receipt files.
The host microphone was disabled; only synthetic records and the public FLEURS Korean fixture
were used. These are not physical Galaxy, Samsung One UI or acoustic measurements.

| Group | Result and conditions | JUnit elapsed |
| --- | --- | ---: |
| Initial functional journeys | **7 PASS / 2 FAIL**. Home/navigation, file conversion/playback, dictionary priority and repeated speech passed. The two broadcast journeys failed during English TTS preparation on the fresh installation. | 205.061 s |
| English voice preparation | **1/1 PASS**. Actual Moonshine preparation and non-silent PCM reaching a listener WebSocket. | 45.457 s |
| Same two broadcast journeys after preparation | **2/2 PASS**. Recorded Korean speech to English listener; operator test button completion and repeat execution. Assertions and APK unchanged. | 134.003 s |
| Voice notes and HUD | **5/5 PASS**. Live transcription from supplied PCM, large-font/short-viewport controls, editing/persistence, HUD and app search. This supplied-PCM path does not prove microphone capture. | 455.734 s |
| First virtual-microphone attempt | **0/1 FAIL** due to test-output file write `EACCES`; microphone quality cannot be judged from this run. | 16.694 s |
| Virtual-microphone rerun after synthetic-output cleanup | **0/1 FAIL**: expected speech text did not appear while recording remained active. | 98.395 s |

Instrumentation used the existing `scripts/run-device-evidence.py` with the exact app/test
APKs and named classes/methods. The microphone wrapper checked installed hashes before and after
the real `VoiceNoteMicrophoneJourneyDeviceTest` execution. Its AOSP-sample-based injection
helper submitted 300 ms packets; this packet size is an experimental setting, not a proven
protocol requirement. Failed receipts remain retained, including the initial preparation failures.

The failed microphone rerun does not establish whether the fault is application capture,
recognition, injected audio delivery or the emulator environment. A new recorded WAV was not
retrieved for this exact rerun, so no earlier waveform's amplitude/correlation is attributed to it.

Full local receipt SHA-256 values (raw logs, recordings and local paths are not published):

| Group | Receipt SHA-256 |
| --- | --- |
| `functional-9` | `477213812583ce0bd16d72818452d7a8ad9ac3bd28f2243db3f18dbbea0d91c1` |
| `english-voice-preparation` | `e9200867514e253e4756b3f6bbb90dba7fe082827c01d1041429081ade81a60a` |
| `functional-2-post-tts` | `518dbb791f299ac24d132163186c945a807edb2fd6d4270f045ba6de4de107c0` |
| `voicenote-hud` | `c876853026d1be817e4c1348f57fc7206bd6ddd4e4d2de257a4544c629f8eaf8` |
| `beta045c-public-mic` | `33705eda0cde88faf64566636e602a617c71a0c14d3c90ed410a7e3919946c69` |
| `beta045c-public-mic-rerun` | `75a684bb573cfe45d02ef5bc813029f231732f3ec635af61e3ce283120aa7154` |

## Publication boundary

The user requested publication of the current work. C is published only as a **prerelease**,
with the microphone failure disclosed; the gate is not waived into a PASS.
This disk-queue fix does not prove that every reported 5–10 minute stop is resolved.
The existing ML Kit semantic-quality failure and sentence-finalization delay cases remain.
Physical Galaxy first-audio p95, human listening quality, the full multilingual 30-clip corpus,
and 8-hour endurance are unproven. No new physical-device or long-duration claim is made.

## Historical 0.2.45 Beta validation (code 51)

Validated source: conversational sentence finalization, committed-prefix revision alignment,
unified broadcast entry and content-first voice notes. Detailed before/after evidence and
failure accounting: [Beta validation report](BETA_0_2_45_VALIDATION.md).

## Artifact and build

- Package `app.guidecast.transmitter.alpha`, code `51`, installed version `0.2.45-beta`.
- Optimized ARM64 APK: **96,424,000 bytes**, SHA-256
  `64e994a7d0e1c128ae4c131f05f609cbad208f6757119636b97edc289eaa3239`.
- Matching test APK: **4,829,417 bytes**, SHA-256
  `8f3c2b02f30669fa25d57659bb8452d04c34e203184cc9c665a828f6a557b541`.
- Existing certificate SHA-256
  `afd9d964c7161f0052d16b0065e6dec14861900cfb876b18df639734ccf29ff3`.
  `apksigner verify`, 16 KiB `zipalign` and installed-version metadata checks pass.
- Final build succeeded in **6m 39s**, 615 tasks: 72 executed, 543 up-to-date.
  Command: `./gradlew testDebugUnitTest :core:stream:test :core:translation:test
  :app:testAlphaUnitTest :app:lintAlpha :app:assembleAlpha :app:assembleAlphaAndroidTest
  :app:verifyPackagedThirdPartyLicenseAssets --offline --max-workers=1` with JDK 17.
- Current-result XML: app Alpha **534**, translation core **255**, stream core **28**;
  zero failures/errors/skips. These are source-matching result counts; reused tasks are
  not claimed as freshly rerun or added to Debug counts as unique cases.
- The newly added explanatory/contracted-past regression failed before the ending fix.
  All 255 translation-core cases then passed, including conditional, noun and quotation
  counterexamples. No translation-quality assertion was weakened.
- GitHub verification for source commit `904ebb2` completed successfully:
  [run 35946865041](https://github.com/dashjeong/MCastTalk/actions/runs/35946865041).
  Source-boundary, web regression, unit tests, lint and assembly passed. This CI result
  does not substitute for the microphone journey or translation-quality gates below.

## Device verification status — APK handoff currently blocked

All current device runs use an Android 15/API 35 ARM64 AOSP emulator. They are not physical
Samsung/One UI measurements. The exact final artifact above was installed and hash-checked.

| Executed group | Result | JUnit elapsed |
| --- | --- | ---: |
| Final APK: four home/navigation journeys and five real-model broadcast/test/file/dictionary/repeated-speech journeys | 9/9 PASS | 307.070 s |
| Final APK: public 136-second monologue, original amplitude | Execution PASS; 34/34 translations completed, pending 0 | 188.212 s |
| Final APK: same monologue, amplitude ×0.125 | Execution PASS; 26/26 translations completed, pending 0 | 181.797 s |
| Antigravity UI cross-check: home, two note viewport configurations, actual editing/persistence and playback | 7/7 PASS on the UI candidate below | 586.641 s |
| Final APK: virtual-microphone live transcription/save/reopen, first attempt | FAIL; expected live text did not appear | 95.751 s |
| Final APK: same microphone journey after emulator restart | FAIL; same live-text assertion | 96.172 s |
| Final APK: client-paced microphone injection experiment | FAIL; live-text assertion retained | See retained receipt |
| Final APK: full Qt emulator instead of headless | FAIL; live text did not appear, host and JUnit failed | See retained receipt |

The UI candidate SHA-256 is
`1dfbae5753fa2c68d2a1ba2dfadd74056755156b3fe1fdcd86dd436fd4b1cac4`.
Its UI source and instrumentation match the final artifact; only the final explanatory/past-ending
core fix followed it. Its seven-test result is not relabeled as seven executions on the final APK.
The earlier UI candidate failed the short-viewport path; separating note tools fixed the same
test without weakening the edit/export assertions.

Microphone investigation: the two failed runs submitted the complete public 7.02-second FLEURS
fixture, but recorded nearly silent WAVs (peaks 8; 894/2,299 nonzero samples compared with the
fixture's 112,048). An independent foreground AudioRecord collector, without note UI or ASR,
also captured only 4,770 nonzero samples. Its read-gap maximum was 73.74 ms with an 8,000-frame
(500 ms) buffer and no observed Android silencing. Its collection test executed successfully,
but **input integrity is not proven and that result is not a microphone-quality PASS**.
Root cause remains under investigation; product capture code has not been changed speculatively.
The subsequent timing, delivery-mode and emulator-version controls, including incomplete
attempts, are recorded in [the microphone gate report](MICROPHONE_GATE_0_2_45.md).
Antigravity executed the full-Qt comparison with the same APK and original injection helper.
The resulting 90,320 ms WAV contained only 510 nonzero samples, peak 8 and RMS
0.0000033535768924956625. Changing the emulator window mode did not resolve this observation;
it does not establish a HAL defect or prove the product capture path correct.
The unchanged strict waveform verifier also failed: correlation 0.0294334, all 140 active
windows failed. Receipt SHA-256 and numeric waveform results are retained locally with this run.
APK publication is withheld until the live-transcription journey is resolved or explicitly
reported as an unresolved release blocker. Failed receipts are retained.

Separate existing ML Kit semantic-quality failure remains FAIL; see the Beta report. None of
the passing execution groups erases it. Physical Galaxy first-audio p95, human voice quality,
the full multilingual 30-clip corpus and eight-hour endurance remain unproven.

## Historical 0.2.44 Alpha validation and 0.2.43 maintenance

> **최신 공개 후보 0.2.44 Alpha / code50:** 사용자 요청에 따라 현재 수정본을 패키징했다.
> APK SHA-256 `4c344a8375a7ebb0f5724eac79b18b3b8c3a808c21a3e97ade8de2092c0efa0c`.
> 정확히 이 설치본의 음성 송출·시험 재실행·파일 변환/재생·사전 우선권 4건 통과(164.518초).
> 동일 APK에서 문맥/큐 검토 26건, ML Kit 21건, Gemma 15건 실행. 문맥 의미 검사는 **FAIL 유지**:
> 자동 항목 23/26, 동일 출력의 AI 대조는 의미 보존 24/명확 오류 1/인용 중의성 1이다.
> 전체 빌드·단위 회귀·lint·라이선스 검사 통과. 1,887개 기록의 JUnit 스냅샷은 재사용 결과를 포함한다.
> 서명·16KiB 정렬·업데이트 설치·전후 해시와 Antigravity의 독립 APK 무결성 확인을 완료했다.
> 알려진 오역을 해결했다고 표현하지 않으며 0.2.43은 철회 상태를 유지한다. [릴리스 범위](RELEASE_0_2_44.md).
>
> **Round20 — 실제 오역 수정 및 당시 품질 보류:** [오역 검토 및 에뮬레이터 검증](MISTRANSLATION_REVIEW_2026_09_24.md).
> 사용자 지시에 따라 다국어·실기기 검증 환경은 에뮬레이터로 대체했다. 제품 APK SHA-256은
> `59b789fd7b381a61cd2b3ac7fe023c517c9b4b23f0cc1339da82e599be89c3ea`다.
> 실제 선택적 검토·큐·공개 사전 경로 26/26 실행·채택, 시간 초과 0. 의미 항목 검사는 23/26으로 FAIL이며
> `4년째/만 3년`의 관계 오류가 남는다. 숫자 표기 거부·사람/시간 혼동·공개 사전 의미 충돌을 보완했다.
> 같은 APK에서 ML Kit 7원어 21건, Gemma 지원 5원어 15건을 실제 실행했다. 번역 전용 검사이며
> 다국어 음성인식·TTS·8시간 시험을 대신했다고 주장하지 않는다. 프랑스어·독일어 Gemma는 미실행이다.
> 아래 과거 `56년째` 판정과 7/6/8 항목 수는 당시 기준 기록으로 보존하며 최신 판정과 구분한다.

> **이전 Round15–17 기록 — 당시 품질 조건 미충족:** [문맥 계약·가상 마이크·실제 로컬 모델 시험](FIX_VALIDATION_2026_09_24.md).
> 최종 보수 APK는 round15와 동일한 SHA-256 `a1a2d4dd30101e73c58be93e92906aee28be816f0c16ed2931cc92e4c80a9ccc`다. 실제 방송·시험 버튼·파일 여정 3건과 강화 톡 노트 1건은 통과했다.
> 문맥 검토는 선별 10건 중 7건만 의미 검사를 통과했다. 추가 프롬프트 실험은 6건으로 회귀해 제거했고, 원문 직접 번역 대조는 8건이나 여전히 실패다. 실제 복구·재진입 1건 통과는 번역 품질 통과가 아니다.
> 최종 단위 결과 1,828/0/0/0은 캐시·기존 결과를 재사용한 스냅샷이다. 전면 상태를 보장한 독립 수집도 엄격한 파형 타이밍 검사는 실패했다. Antigravity 교차검토는 호스트 20건과 정적 검토 범위이며, 실기기·8시간·나머지 언어 코퍼스 입증은 아니다.
> 아래 round8–12 결과와 당시 보류 판단은 역사적 기록으로 보존한다. 최신 결과를 대체하지 않는다.

> **현재 보수 진행 기록:** [원인·수정·중간 시험·남은 검증](MAINTENANCE_REPAIR_0_2_43.md).
> **상세 품질 보고서:** [최신 실제 검증·네이티브 수정·남은 품질 조건](QUALITY_VALIDATION_2026_09_24.md).
> **2026-09-24 최신 round10:** 수정 네이티브 APK의 네이티브·숫자·EOF 6개(37.76초), 방송·시험 버튼·파일 여정 3개(202.194초) 실제 통과.
> 같은 강제 배출 메모리 검사의 heap 증가는 기준판 +19,776,000 bytes→수정판 −112 bytes. 합성 가속 시험이며 8시간 안정성 입증이 아니다.
> 빌드 1분 52초 성공. 보존 XML 1,787건 실패·오류·건너뜀 0 중 마지막 명령의 실제 재실행은 app Debug/Alpha 합계 1,026건, 나머지 761건은 UP-TO-DATE 이전 결과다.
> 본체 SHA-256 `fba37b600fc0519e07a7289bf0a0b9a63c05b5e7d9f51f05d52502eb09885610`, 96,309,312 bytes. 서명·16KB 정렬·실제 APK의 네이티브 3개 해시 검사 통과.
> 지정 영상 최종 재시험 2건은 완료: 번역 요청 34/34, 선별 결함은 부분 개선 2·동일 3·완전 해결 0이다.
> Antigravity 정적·해시 교차검토의 지적을 반영한 round12 강화 시험 5개가 통과했다. 이전 round11의 설정 누락 실패를 삭제하거나 합격으로 바꾸지 않는다. 상세 조건은 최신 품질 보고서에 있다.
> 마이크 전체 여정은 새 APK에서 재시험하지 않았으며 이전 실패·미입증 상태다.
> **이전 round8 기록:** 재빌드·단위시험 767회, 실제 EOF 2개·설치 가용성 기록 1개·방송 2개·DB 5개 통과.
> 한국어 긴 코퍼스 30건(합계 63분 15.6초)은 입력 해시 모두 일치, 23건 실행 완료·7건 무출력 실패.
> 원문 단위 305개·번역 305개 생성은 품질 통과가 아니다. 영상 발췌 2건의 꼬리 실패는 1→0이나 선택된 의미 오류 5개는 남았다.
> 직접 native 대조 2건도 무출력 실패해 watchdog 원인으로 확정하지 않는다. 나머지 6언어 각 30건은 미완료다.
> UI 최초 묶음 18 통과·5 실패, 해당 재시험 3 통과·2 실패, 최종 확인 2 통과·1 건너뜀(새 미지원 기기 검사 포함).
> 최종 가상 마이크 전체 여정은 실패다. JUnit 통과 시도도 호스트 주입기 종료 실패로 전체 통과가 아니었다.
> **공개 재배포 보류: 품질 게이트 미충족.** 사용자 공개 요청은 유지하며 보수 소스는 별도 브랜치에 보존한다. 공개본은 **0.2.42 유지**.
> 본체/시험 APK 해시와 실행별 한계는 위 보수 기록에 있다. **아래 본문은 철회 당시의 역사적 기록이며 현재판 통과 주장이 아니다.**

> **WITHDRAWN 2026-09-23 — NOT RELEASE APPROVAL.** Users reported failed audio broadcast
> and test flows. Voice notes record WAV but do not transcribe while recording, missing
> the required note-taking experience. The results below are historical component checks;
> they did not establish complete user journeys. See [maintenance review](MAINTENANCE_0_2_43.md).
> The public release and tag have been withdrawn; public product source is restored to 0.2.42.

Validated on 2026-09-22. Package `app.guidecast.transmitter.alpha`, versionCode `49`.
This was a public Alpha release and is now withdrawn. Physical-device latency, prolonged operation and human
translation/voice quality remain unverified; automated results are not substitutes.

## Product changes

- 녹톡 / 라이브톡 / 통역톡 / 스크립톡 home; local voice-note recording, editing,
  speaker labels, segment playback and document exports.
- Portable script backup now includes note audio and metadata. Import validates hashes,
  WAV structure, references, size and paths before merging; existing edits are preserved.
  Settings > script backup and the note screen provide export/import entry points.
- Service-scoped automatic preparation, cancelled-preview cleanup, stale-session rejection,
  file-translation checkpoints and browser resampling regression coverage.
- Optional comparative teacher review with independent providers, explicit transmission
  consent and approval, context-specific reuse, conflict checks and rollback.
  This updates approved correction memory, not model weights.

## Build and automated gates

Environment: macOS ARM64, JDK 17, Android SDK 36, build-tools 35.0.0, Gradle 8.13.
All commands below completed successfully against the release source:

```sh
./gradlew testDebugUnitTest :core:stream:test :core:translation:test \
  :app:testAlphaUnitTest :app:lintAlpha :app:assembleAlpha :app:assembleRelease \
  :app:assembleAlphaAndroidTest :app:verifyPackagedThirdPartyLicenseAssets \
  --offline --max-workers=4
node scripts/verify-listener-player.mjs
node scripts/verify-listener-resampling.mjs
node scripts/verify-listener-i18n.mjs
node scripts/verify-speaker-mic.mjs
node scripts/verify-public-branding.mjs
python3 scripts/test-public-snapshot.py
python3 scripts/verify-public-snapshot.py
git diff --check
```

- JVM: **1,664 executions, 0 failures/errors/skips**, 249 XML suites. Debug and Alpha
  executions are counted separately; this is not 1,664 unique cases.
- Alpha lint: **0 errors, 56 warnings, 6 hints** on this macOS build.
- Browser-script simulations: player ordering, resampling, localization and speaker capture pass.
- Public source boundary: 622 tracked files pass credential/artifact/archive checks;
  the gate's 9 self-tests pass. These checks are not an exhaustive penetration test.
- Packaged glossary and all 40 third-party license assets pass verification. The glossary
  database is 29,405,184 bytes, SHA-256
  `5f39f27faf0c68b305d06e67c09eb929398e0242dd0278eb8430c49070d2ccb3`.
- APK size: 96,174,053 bytes; DEX total 14,992,212 bytes, within 110 MiB / 18 MiB budgets.

## Exact distributable and update

`MCastTalk-0.2.43-alpha.apk` is the optimized, signed ARM64 Alpha artifact
(minimum Android API 30, target API 36). It passes APK signature verification and
`zipalign -c -P 16 4`. No debug signing key is used.

- APK SHA-256: `342f5383f31bbf070727669d9b17a6fa9ed1f7e51992abcdab9f46b261fe5838`
- Signing certificate SHA-256: `afd9d964c7161f0052d16b0065e6dec14861900cfb876b18df639734ccf29ff3`

The exact distributable was installed with `adb install -r` over the public 0.2.42 APK
on a dedicated Android 15 / API 35 AOSP ARM64 emulator. `AppUpdateDeviceTest` ran a
seed phase before installation and a verify phase afterwards. Settings, a user-confirmed
sentence correction, a file transcript translation, and all 23 downloaded English/Japanese
model-cache files (241,061,938 bytes, compared by SHA-256) survived the update.
These are synthetic records and actual downloaded models; no operator content was used.

The 9 new `VoiceNoteTransferDeviceTest` cases pass on the signed APK: real repository
export/import, note/WAV/edits/speaker round trip, preservation of current corrections,
tampered/missing/unreferenced/traversal audio rejection, invalid metadata/reference rejection,
interrupted notes, cancellation/retry cleanup, orphan-audio conflict, legacy script archives,
and comparison preferences without restoring cloud consent.

## Signed-APK regression on API 35

All **87 tests in 28 bounded instrumentation groups pass, with no skipped cases**:
home navigation; voice-note repository, playback, large-font/short-viewport editing;
teacher learning, API validation and approval UI; recognition reconnection; HUD and app
search; settings/dictionary/script exports and imports; file decoding, playback, batch
selection, checkpoint/library handling; transcript and sentence-memory screens; operator,
privacy and display preferences; transcript archive retention; and five broadcast scenarios.
The latter exercise original PCM/WebSocket delivery, operator start authority, seven
translation channels plus original audio, rapid stop/restart and deferred-restart cancellation.
The UI run captured 13 synthetic screenshots; no real recordings or user content were used.

The update seed/verify phases and 9 new migration cases above are additional to these 87.
Instrumentation uses `app.guidecast.transmitter.GuideCastTestRunner` against the same
signed APK, with each named `*DeviceTest` group or selected broadcast method passed via
`adb shell am instrument -w -r -e class ...`.

Four additional real-model gates pass on the exact signed APK: killing the English TTS
worker leaves Japanese non-silent PCM running; five first-PCM cancellations allow the
next complete synthesis; English Moonshine PCM reaches the listener WebSocket; and a
cancelled translation preview cannot supersede the new broadcast audio session.
These use actual downloaded Moonshine models, not mock synthesis. In total, **100 Android
regression executions plus the two update phases pass**. Emulator PCM assertions do not
establish physical loudspeaker sound or human voice-naturalness approval.

## Scope not proven

- No physical Galaxy/Samsung One UI, Android/iPhone browser, outdoor noise, hotspot load,
  heat/power, or elapsed 2-hour/8-hour reliability claim. Long virtual-time tests do not
  establish elapsed device uptime. The user will report prolonged real-device behavior.
- Prepared-model first-audio p95 <= 2,000 ms on Galaxy S23 remains unmeasured.
- No real OpenAI/Gemini comparison calls or semantic quality assurance without API credentials.
  Provider protocol/security tests use controlled responses; human meaning/voice assessment remains.
- Model preparation/reuse is not proof of full offline STT→translation→TTS interoperability.
- API 29 cannot install this API-30-minimum artifact. Historical API-29 evidence is not counted.
- No app can be guaranteed resistant to every AI-assisted or other attack. Backup validation,
  cloud-consent boundaries, authentication and bounded-resource regressions are tested controls.

Only public product source, documentation, signed APK and checksum are released. Raw diagnostic
archives, recordings, detailed private test logs, credentials and signing keys are excluded.
Earlier 0.2.42 counts and private candidate runs are not included in these results.

## 2026-10-02 — 0.2.46-beta-d candidate (not a published release)

This dated section supersedes neither the historical 0.2.43 evidence above nor its
device limitations. Tests below concern the new candidate only. The public release
remains beta-c while the candidate fails its latency gate.

### Changes and observed defects

- Final-sentence recovery uses real continuous PCM silence, stable hypotheses and
  conservative conversational endings. A usable unfinished tail has an 8-second
  silence ceiling; active speech and missing PCM do not trigger a forced cut.
  Late provider finals cannot replay a retired prefix. Input remains open.
- Local TXT domain profiles provide reviewed exact pairs and bounded reference
  examples to Gemma. This is retrieval/correction memory, not weight training.
  Disabled-domain prompts retain the historical byte-for-byte form.
- A slow document export previously held the live lookup lock. Export now snapshots
  bounded rows under the lock and performs external stream writes outside it.
  A deliberately stalled export regression verifies lookup and operator changes.
- Narrow currency/explicit-verbatim-typo guards apply only with domain references
  enabled. Ambiguous currency conversions and ordinary quoted dialogue are left
  unchanged. The domain prompt requests preservation of instructor, actor and
  personal promise; the Japanese MC09 result still fails that requirement.
- Microphone profiles store independent settings for built-in, wired/USB and Bluetooth
  input. Android microphone direction is a best-effort request, not speaker identity.
- Domain navigation now dismisses its overlay through both system Back and the
  operator menu. The idle domain screen avoids an oversized fixed status header;
  active input/broadcast retains operator status and controls.

### Build and exact artifacts

macOS ARM64, JDK 17, Gradle wrapper, SDK/build-tools 36.0.0. Gates completed:

```sh
./gradlew --no-daemon --max-workers=1 testDebugUnitTest :app:testAlphaUnitTest \
  :core:stream:test :core:translation:test \
  :provider:gemma-translation:testReleaseUnitTest :app:lintAlpha \
  :app:assembleAlpha :app:assembleAlphaAndroidTest \
  :app:verifyPackagedThirdPartyLicenseAssets
node scripts/verify-listener-player.mjs
node scripts/verify-public-branding.mjs
```

The full fidelity gates completed in 1m 3s; subsequent navigation gates completed
in 4m 4s. The Android-voice measurement test APK was then rebuilt successfully
in 29s; this last change affected instrumentation only. Alpha unit tests: 589,
core translation: 292, Gemma: 150, each zero failures/errors/skips. Debug/Alpha
duplicates are not counted as unique tests. Alpha lint: 0 errors, 88 warnings, 6 hints.
Core audio Release unit tests: 70; core stream: 28, also zero failures/errors/skips.

The final navigation candidate is 96,636,992 bytes, SHA-256
`1d097af80de6be6a53727885f055e38ae1b39d1017bc9f4458699df56c92a59e`.
Its matching instrumentation APK is 5,042,409 bytes, SHA-256
`38cec07a363984043fe5172119c3d3fbd89de1ee9b0f0294c07a95eab798ab76`.
Both pass v3 signature and 16 KiB ZIP alignment checks with the existing certificate
`afd9d964c7161f0052d16b0065e6dec14861900cfb876b18df639734ccf29ff3`.
Both exact files were installed with `adb install -r` on the S23+ and API-35 ARM64 emulator.
Raw models, corpus research, diagnostics, recordings, credentials and keys are excluded
from source control and APK assets.

### Exact-candidate device evidence

Instrumentation uses `GuideCastTestRunner`, `-e portableRunner true`, and explicit
`-e class` selections. Local evidence is retained under ignored
`build/evidence/domain-learning-20261002/`.

| Environment / named group | Actual result | Evidence file |
|---|---|---|
| S23+ SM-S916N, API 36; domain repository 4 + microphone profiles 1 + microphone focus 1 + domain UI 1 | 7 passed, 16.694s | `physical-navigation-native-seven.log` |
| S23+; final E4B sentence, no following speech, input kept open | 1 passed, 37.215s; final after 561ms, E4B text after a further 2,781ms | `physical-navigation-idle-final.log` |
| S23+; prepared E4B + installed Android offline English voice | 20/20 non-silent PCM; functional measurement completed in 108.556s; performance **failed** | `physical-navigation-android20-pcm.log`, `.json` |
| S23+; identical APK and Android offline voice, resource-observation rerun | 20/20 non-silent PCM; 108.620s; p95 2,049ms, performance **failed** | `physical-navigation-android20-resource-retest.log`, `.json` |
| S23+; frozen official final TEST, direct E4B OFF/ON | **Failed**, 135.824s; 21 outputs completed, next Chinese ON request exceeded the production 10s limit; cleanup completed | `physical-navigation-official-test28.log`, `physical-navigation-domain-nkinfo-e4b_it-1790954123651.json` |
| API-35 ARM64 emulator, 320dp viewport; same repository/microphone/UI group | 7 passed, 22.276s | `emulator-navigation-native-seven.log` |
| Same emulator, font scale 1.8; domain activation, SAF picker, Back/operator menu/new service entry | 1 passed, 32.647s; font restored | `emulator-navigation-large-font.log` |
| Same emulator; original authenticated HTTP/WebSocket PCM, operator start, standalone, unready translation, seven channels, rapid restart, deferred-stop cancellation | 7 passed, 13.164s | `emulator-navigation-broadcast-seven.log` |

The full emulator broadcast class was deliberately interrupted before the bounded
seven-case rerun; its interrupted runner output is not counted as a product crash
or passing full-class run. Earlier large-font assertions assumed offscreen labels
were visible; corrected tests now scroll to the actual retained viewport and pass.
Emulator-only storage reservation was temporarily adjusted for APK installation and
restored; the physical phone's privacy/security settings were not changed.

The final-sentence fixture is public FLEURS `ko_kr` test row 7/sample 1959 (CC BY 4.0),
7.02s, 16 kHz S16 mono, 224,640 bytes, SHA-256
`b35aa5acf7ff72a4ec1b68415957ac9e7fe89268312cc8f5e5325a88acea841f`.
Native STT produced one word error (시키고 versus 지키고); E4B retained the intended
“follow all signs” meaning. This is real paced PCM-to-native-STT-to-E4B testing,
not TV/noise isolation or physical speaker listening.

### Measured latency and release decision

Twenty English samples use five public synthetic phrases repeated four times,
session memory enabled, prepared native E4B and installed Android offline TTS.
Nearest-rank final-text-to-first-non-silent-native-PCM p50 = 1,788ms,
p95 = **2,041ms**, max = 2,277ms; 7/20 exceed 2,000ms. All have clipping ratio 0.
Translation p95 = 2,021ms; TTS first PCM p95 = 126ms. The measurement uses separate
15s translation / 30s first-audio test bounds; these do not relax the release gate.
No microphone, WebSocket scheduling or loudspeaker playback is measured by this test.
The native result already exceeds the target; **the S23 web-first-PCM p95 gate is
not passed**, and a beta-d public release is not approved by this report.

The Moonshine-prepared variant failed its precondition because its English model
was not installed. It yielded no 20-sample latency result. Android results are a
separate, explicitly named installed-offline-voice route, not a hidden Moonshine pass.

A second run used the same APK and conditions, with read-only resource observations.
Its final-text-to-first-native-PCM p50 = 1,790ms, p95 = **2,049ms**, max = 2,276ms;
5/20 exceed 2,000ms. Translation p95 = 2,019ms; TTS first PCM p95 = 122ms.
All 20 complete with non-silent PCM and clipping ratio 0. This is a repeated
baseline, not improvement after an optimization. Zero clipping does not establish
absence of buffer overflow, playback distortion or a listener-heard result.
Thirteen resource samples recorded thermal status 0, battery temperature 34.2–36.2°C
and Gemma-worker PSS 1,096,543–3,557,759 KiB. CPU-info windows were stale: CPU usage
during this run is unmeasured. GPU-frequency reads were made after completion and
do not establish inference backend, utilization or load-time GPU frequency.

Earlier S23 microphone-focus testing recorded valid ordered 16 kHz S16 mono,
non-silent PCM with focus OFF/ON, but the OEM direction request was unsupported.
It does not establish one-speaker isolation. S21 Ultra never appeared in ADB and
has no physical test result. The S23 batch starts at 14:34:58 UTC; suites are bounded
to respect the user's 25-minute device-work preference. No privacy control is bypassed.

### Translation quality scope

See `DOMAIN_CORPUS_VALIDATION.md` for frozen train/dev/test boundaries and actual
OFF/ON comparisons. Completion is separate from semantic accuracy. The initial
72 meeting outputs and 28 official DEV outputs predate the fidelity guard; the new
36-output DEV test uses the guard. No 99% human-equivalence, final heldout success,
8-hour stability, outdoor noise, iPhone/Android browser listening, or voice-naturalness
approval is established by these controlled tests.

The exact navigation candidate's meeting replay produced all 72 outputs and recorded
`functionalCompletion=true` and `cleanupCompleted=true` in the on-device JSON,
SHA-256 `cb07c8e4130124c4ee38f8bc7939cf164d8bceeb7b0dc644ee2622467bd0edf5`.
ADB transport changed during the run and the host runner received no final JUnit
token. Therefore this is a recovered complete app-side result, **not a JUnit PASS**.
On the original MC01/MC05 cases, ON preserves KRW and the explicitly quoted Korean
typo; Japanese MC09 still omits the instructor's personal-action subject in the
negated clause. Independent Codex AI review of all 72 outputs rates OFF 26 PASS /
5 MINOR / 5 MAJOR and ON 31 / 4 / 1. Compared with the initial ON run, five grades
improved, one minor title-precision grade worsened, and thirty were unchanged.
The remaining ON major error is Japanese MC09's instructor/personal-promise
relationship. Antigravity's independent review of the same 72 mapped outputs
rates OFF 26 / 5 / 5 and ON 32 / 3 / 1. Seven row-level grades differ between
reviewers; the shared ON major failure is Japanese MC09. Counts are reported
separately, not averaged into an inflated accuracy figure.
These are AI judgments on fixed authored text, not human-equivalence percentages.

The frozen official final TEST was subsequently attempted on the exact candidate.
It failed with `GemmaRealtimeTimeoutException` at the unchanged 10,000ms limit on
`NK-10719-0-TEST-POLICY-V2`, Chinese, domain ON (118 source characters and 1,134
hint characters). The recovered JSON has 21 completed rows and one RUNNING row,
no `functionalCompletion`, and `cleanupCompleted=true`; SHA-256
`87b9224f83cc7ccb80bac5cda3b6bccb97d5d4ca5035c60efa2dc441c8e592aa`.
Device fixtures matched the frozen local corpus and training-file hashes before
execution. The partial outputs do not establish full functional or semantic
success. Longer hints are a diagnostic hypothesis, not a proven timeout cause.
No release gate is waived or production watchdog extended by these results.

### 2026-10-03 — benchmark failure-state follow-up (instrumentation only)

The benchmark now saves terminal state (`TIMED_OUT`, `CANCELLED`, or `FAILED`),
elapsed milliseconds and exception class before rethrowing the original failure.
It does not save exception messages, change the native watchdog, substitute a
fallback success, or rewrite old result files. Existing cleanup still runs.
`:app:compileAlphaAndroidTestKotlin` completed successfully in 15s (133 tasks).
This establishes compilation only. The changed instrumentation has not yet been
packaged, installed or exercised on a failure path; the matching test-APK hash
above refers to the previous installed instrumentation, not this source update.

### 2026-10-03 — E4B execution and fidelity candidate follow-up

This section distinguishes the MTP-option-only APK from the subsequently changed
fidelity/reference candidate. Neither is a published release or a completed quality gate.

The MTP-option-only product SHA-256 is
`a5a494962a2a1de3a0542172bd74c2793c24ed5efc2df17908d529647883d924`.
On S23+ SM-S916N/API 36 its two prepared E4B/installed Android offline English
TTS runs each completed 20/20 non-silent samples. Native final-text-to-first-PCM
p95 was **1,698ms** and **1,687ms**, max 1,833ms and 1,834ms respectively.
The five fixed synthetic phrases were each repeated four times; session memory
was enabled. These tests do not measure microphone, ASR, WebSocket or speaker
playback. The earlier 2,041/2,049ms measurements used a different APK and thermal
conditions. The difference is observational, not a controlled MTP OFF/ON result.
Runtime metadata confirms the GPU backend and a speculative-decoding request,
not independent proof of a particular drafter's execution or identical translations.
The old test did not retain its translation text, so timing success is not meaning
approval. Evidence: `physical-mtp-android20-pcm.log/.json` and
`physical-mtp-android20-unobserved-retest.log/.json` in the ignored evidence directory.

During the first MTP-only run 12 read-only resource observations recorded thermal
status 0 and battery temperature 32.5–33.6°C (one intervening 32.4°C sample).
Worker PSS ranged from 2,544,593 to 3,671,654 KiB. Live CPU observations used a
second one-second `top` interval; worker values ranged from 0 to 90%, including
preparation and idle intervals. Neither CPU percentages nor PSS establish GPU
utilization, absence of memory pressure, or long-duration stability. The second
20-sample run had no resource sampling. MTP-only emulator inference failed at
the unchanged 10,000ms limit; that failure is not a passing CPU/model-switch result.

The newer recovery candidate requests speculative decoding for E4B/GPU only and
resets the process-wide option to SDK default for E2B or CPU. E2B's historical
prompt remains fixed by its golden test. Narrow currency/verbatim-typo guards
now also apply to native E4B when domain references are OFF. They do not infer
actors, repair ambiguous quotations, perform currency conversion, or modify
source/history/corpus/exact user translations. The generic E4B prompt separates
instructions to another person from the speaker's own promise. E4B optional
domain references are limited to 600 characters, retaining whole bilingual pairs
before optional description; CURRENT is unchanged. This is a character budget,
not a KV-token guarantee. The production native 10s deadline remains unchanged.

Final recovery gates completed in **3m 35s**, 420 tasks: Gemma Debug 152,
core translation 292, Alpha 589 tests, each zero failures/errors/skips;
Alpha lint, actual Alpha APK/test APK assembly and packaged license validation
also completed. Public branding, listener scheduling, resampling and eight-language
listener safety checks passed. Exact signed recovery artifacts:

- Product: 96,636,992 bytes, SHA-256
  `4ce3c120caa56b4419e4e0cbae4af8c71c162f7399fc5bd325bdfd9959b36a6c`.
- Instrumentation: 5,046,505 bytes, SHA-256
  `a08a8b9947bbfbfc9a94c1c1efde9bef2d800844bd6d7c9aebbaeb92a61189ff`.

Both pass v3 signature and 16 KiB alignment with the same certificate recorded
above. Both exact artifacts were update-installed on the API-35 ARM64 emulator.
For this install only, emulator global `sys_storage_threshold_max_bytes` was
changed from `null` to 20 MiB, then restored to `null` in `finally` and reread.
No app/model data was removed and no physical-device security setting changed.

Actual recovery-candidate emulator results:

| Named test group | Result | Local evidence |
|---|---|---|
| Domain repository, including whole-pair budget and escaping | **5 passed**, 0.225s | `emulator-recovery-domain-repository.log` |
| Original protected HTTP/WebSocket PCM, operator opening, standalone input independence, unready translation, seven channels, rapid restart and deferred-stop cancellation | **7 passed**, 12.914s | `emulator-recovery-broadcast-seven.log` |
| Frozen recovery DEV E4B native inference | **1 failed**, 31.375s; first English OFF case RC01 exceeded native 10s limit | `emulator-recovery-dev-inference.log` |

The eight-case runner fixture SHA-256 was verified on the emulator before execution:
`b351d7c1e93eb76d43b15b43e206fbebe0986f54aef688fe166c32a98f2034c2`.
The recovered failure JSON SHA-256 is
`4944e58d77c7a74b78abfe2131fffc5c564b8ef4a9d45adbae96ef8dcdf4e3ae`.
It records model preparation 21,136ms, RC01 elapsed 10,064ms, terminal
`TIMED_OUT`, zero completed translations and `cleanupCompleted=true`.
This exercises the terminal-state fix on the exact new instrumentation; it does
not establish 48 completed outputs or semantic recovery. The seven broadcast
tests ran after this attempt but do not prove live E4B-to-ML Kit fallback speech.

S23+ was absent from ADB at this follow-up, so the newer fidelity/reference APK
has no S23+ installation, semantic regression or new 20-sample latency result.
The Mac GUI was locked; CLI checks continued, but no Antigravity UI approval is
claimed during the lock. Unsupported latency estimates in its design draft were
not accepted as measured evidence. Earlier MTP-only phone measurements must not
be attributed to this newer APK. Actor/negation quality, the earlier long-reference
timeout, web-first-PCM p95, human listening and long-duration gates remain unproven.
