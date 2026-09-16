# 0.2.41 Alpha validation

## Status — 2026-09-16

This report covers the exact signed `0.2.41-alpha` APK, versionCode `47`.
Earlier release results are not substituted for the checks below. This public
Alpha fixes a reproduced transcript-assembly freeze and adds app search, a live
reading HUD and an operator-controlled recognition reconnect. It is not an
eight-hour physical-device stability certification.

## Failure and regression evidence

Local diagnostic inspection showed audible input continuing while the published
translation sequence stopped advancing. Repeated attempts failed with
`ProviderTranscriptAssemblyOverflowException`. No original diagnostic archive,
audio, transcript or credentials are included in source or release assets.

The assembler retained already committed provider lines while any unfinished tail
remained. Continuous speech could therefore reach the bounded line/character
limit. A rejected oversized revision also mutated the retained buffer before
validation, allowing the same failure to recur during finalization and retries.
The recognition loop reset its failure count before accepting the callback.

A synthetic continuous-provider regression failed with the same overflow before
the fix. It now processes 1,000 completed English provider lines without a
capacity recovery, duplicate final IDs or lost text. This is a deterministic
source regression, not 1,000 real speech-recognition attempts or a timed soak.

The fix retires only exact, immutable, committed prefixes. Candidate updates are
validated before mutation. At exceptional capacity boundaries, the entire usable
tail is preserved once before the next provider line is accepted. Oversized
revisions leave the original buffer recoverable. Ordinary provider restarts keep
their semantic tail; an explicit operator reconnect can finish it once.

Recognition retry accounting now resets after successful acceptance. Repeated
provider failures retain the recognition flow and wait for an explicit reconnect
instead of silently ending the listening pipeline. Reconnect cancels the current
recognizer under the same serialization lock and leaves broadcast/input controls,
transcript IDs and translated-audio queues intact. It does not rewind audio.
New diagnostic records contain only fixed recovery reasons/states, not content.

The real staging/translation pipeline regression uses deterministic translator
and TTS doubles. It verifies three distinct non-silent PCM outputs across a
capacity boundary and reset, unchanged stream generation, zero translation/TTS
failures, and continued original-audio publishing. Existing semantic-boundary,
late-callback, Korean-tail and per-language queue tests also pass.

## Source gates

Environment: JDK 17, Android SDK/build tools 36. Closing tasks passed in 4 min 44 s.
The strengthened PCM continuation test was subsequently rerun with
`:core:translation:test` and passed. The final relevant XML contains **1,516 test
executions across 229 suites**, **0 failures, 0 errors and 0 skipped tests**.
Debug and Alpha executions of shared tests are included; these are not distinct
user scenarios. Unchanged Gradle tasks reused passing results. Historical XML
from tasks/variants outside the command is excluded.

Alpha lint: **0 errors, 43 warnings and 5 hints**. Four web/public regression
scripts, nine snapshot-checker tests, staged whitespace checks and the public
snapshot scan passed. The scan covered **560 source files**. No production
dependency or model was added.

```sh
./gradlew --offline --no-parallel --max-workers=2 \
  testDebugUnitTest :core:stream:test :core:translation:test \
  :app:testAlphaUnitTest :app:lintAlpha :app:assembleAlpha \
  :app:verifyPackagedThirdPartyLicenseAssets :app:assembleAlphaAndroidTest
node scripts/verify-listener-player.mjs
node scripts/verify-listener-i18n.mjs
node scripts/verify-speaker-mic.mjs
node scripts/verify-public-branding.mjs
python3 scripts/test-public-snapshot.py
python3 scripts/verify-public-snapshot.py
```

## Exact artifact

| Check | Result |
|---|---|
| Package | `app.guidecast.transmitter.alpha` |
| Version | `0.2.41-alpha` / `47` |
| Distributable | R8 and resource-shrunk Alpha |
| Size | 95,551,374 bytes |
| APK SHA-256 | `41ca94901d59b75487c9ab437f03a008b3c94b9253f88dab88b7959a63fd1e41` |
| Certificate SHA-256 | `afd9d964c7161f0052d16b0065e6dec14861900cfb876b18df639734ccf29ff3` |
| Signature / alignment | v3 verified; 4-byte and 16-KiB alignment passed |
| Installed artifact | Updated from code46; pulled-back APK byte-identical |
| Listener assets | All four byte-identical to current source |
| License assets | Packaging gate passed; 40 included |
| Final test APK SHA-256 | `c4bec0fc88013b3c5a98b47169e3b115cf2bcff05d242439aa31cec355a7f36b` |

```sh
apksigner verify --verbose --print-certs MCastTalk-0.2.41-alpha.apk
zipalign -c -P 16 4 MCastTalk-0.2.41-alpha.apk
adb install -r MCastTalk-0.2.41-alpha.apk
```

## Final-artifact emulator checks

API35 arm64 AOSP: **66 distinct cases passed** on the exact product APK above.

- **4 new cases, 12.957 s**: `RecognitionReconnectDeviceTest` (2) and
  `HudAndAppSearchDeviceTest` (2). Real recognition orchestration with a synthetic
  provider preserves pending text and increasing unique IDs through operator
  reconnects; three provider failures wait, then resume in the same flow. The
  actual Compose app picker searches a 1,001-item synthetic list by package terms,
  handles no matches and selects the correct app. The live HUD defaults to source
  only, reveals controls on touch, enables a selected translation, follows a new
  row, hides controls and returns without changing input/broadcast state.
- **51 existing cases, 52.878 s**: `DataTransferExportDeviceTest`,
  `FileTranscriptScreensDeviceTest`, `TranscriptScreensDeviceTest`,
  `SentenceMemoryScreenDeviceTest`, `FileAudioDecoderDeviceTest`,
  `FileAudioPlaybackDeviceTest`, `FileBatchSelectionDeviceTest`,
  `FileTranscriptLibraryDeviceTest`, `DataTransferDeviceTest`,
  `OperatorOptionsDeviceTest`, `DeveloperCloudMemoryDeviceTest`,
  `UiDisplaySettingsDeviceTest`, `BroadcastTranscriptArchiveDeviceTest`.
  These exercise MediaCodec/MediaPlayer, settings and backup migration, archive
  retention/session filters, developer visibility, playback/HUD layouts and
  preservation of existing confirmed data.
- **11 operator/native TTS cases, 41.003 s**: ten selected
  `BroadcastEmulatorIntegrationTest` methods cover branding, protected original
  PCM/web access, test tone without input, translation-readiness warnings keeping
  operator authority, seven translation channels plus original without microphone,
  rapid stop/restart, cancellation of deferred restart, standalone mode without
  listener sockets, app selection/manual override and native Moonshine English
  TTS reaching WebSocket as non-silent PCM. The remaining
  `MoonshineTtsLanguageFailureIsolationDeviceTest` case kills the English worker
  and verifies that the Japanese worker and its non-silent PCM continue.

Synthetic HUD screenshots were visually reviewed: black background, cyan source,
yellow selected translation, chronological upward accumulation and no timing,
sequence, diagnostics or persistent controls on the reading surface.

Initial UI-test failures were resolved in the fixture: Android's first immersive
education overlay had intercepted the HUD touch, and unpadded completion text was
under the system bar. A padding-helper fixture revision was incompatible with the
R8 test mapping and crashed; it was replaced by a plain-text completion fixture.
Final tests dismiss the system education overlay and retain all behavioral
assertions. No product APK change or broader keep rule was used to hide these
test-only issues. The final four-case rerun above passed.

## Limits and field validation

No physical handset, actual Google speech service, speech language packs or cloud
API request was used in these checks. The AOSP emulator cannot establish real
recognition accuracy. Native TTS checks demonstrate the specified PCM paths and
worker isolation, not voice naturalness or first-audio latency. API29 is below
the app's minSdk30 and was not used as an installation target.

**Physical eight-hour continuous operation and Galaxy S23 prepared-model
first-audio p95 ≤2,000 ms have not been demonstrated.** No physical two-hour soak,
Galaxy/One UI capture qualification, outdoor-noise, hotspot-load or Android/iPhone
browser result is claimed. As requested, extended handset use remains user field
validation; the reproduced software failure and recovery paths have automated
regressions, not a guarantee against every possible interruption.

Use prepared supported local engines for standalone/offline operation. Android
permission/capture denial requires restoring the permitted input. Reconnect
preserves recognized usable text; it cannot reconstruct speech never recognized
during an outage. Preserve the distinction between source regression, synthetic
provider orchestration and actual end-to-end speech quality.

Private source logs remain local. Release assets contain only the signed product
APK and its checksum. Signing material stays outside source and APK. Portable
user-requested backups are separate from minimal diagnostics and may contain
private sentences; they are not public release assets.
