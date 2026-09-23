# 0.2.43 Alpha validation

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
