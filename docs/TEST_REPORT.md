# 0.2.40 Alpha validation

## Status — 2026-09-14

This report identifies the exact `0.2.40-alpha` APK prepared for public Alpha
distribution. Results from `0.2.39-alpha` are not evidence for this version.
The APK includes local backup export, cloud-consent revocation and constrained-screen
fixes. Source and packaging results are recorded below; physical field validation
remains separate from this public Alpha's software checks.

## Candidate scope

- Recovery from stalled recognition input, transcript loading failures, duplicate
  requests and retries, while keeping input and broadcast controls independent.
- App selection populates its package name; an explicit manual edit takes priority
  until another app is selected. Standalone operation provides a device-only mode
  without starting the broadcast server.
- Live scripts and file playback provide source/translation reading, manual scroll
  with explicit follow-resume, a head-up screen, and separate playback controls.
  File conversion supports individual files, batches, folders and retries of
  incomplete items. Ordinary screens hide developer metrics by default.
- Saved broadcasts are queried by session start and date range, with pages of up
  to 1,000 rows, an active retention limit of 500,000 rows, and optional daily
  backups. Existing confirmed wording remains authoritative during migration.
- Portable settings, dictionaries and scripts use validated app-private staging.
  Imports check versions, integrity, record relationships, duplicate IDs and size
  budgets before merging. File audio must be relinked by matching its hash;
  importing a URI does not restore permission. File library limits are separate
  from the broadcast archive: 1,000 files, with bounded metadata and script sizes.
- Explicit export saves a new ZIP in the device's fixed
  `Download/MCastTalk/Backups` folder. A pending MediaStore item becomes visible
  only after the complete write succeeds. Cancellation and partial-write failure
  clean up only the new pending item, preserve previous backups and offer retry.
- Optional developer controls for expressive speech, wording review and sentence
  memory remain experimental. Cloud review requires explicit opt-in; settings
  migration excludes credentials and resets cloud/automatic-learning consent.
  No ML Kit GenAI production dependency was added.

## Source regression evidence

Environment: JDK 17, Android SDK/build tools 36. The closing gate covers
**1,511 test executions across 229 XML suites**, with **0 failures, 0 errors and
0 skipped tests**. Counts include Debug and Alpha executions of shared cases;
they are not counts of distinct user scenarios. Only result directories belonging
to tasks in the command below are counted; historical XML from other variants is
excluded. Unchanged tasks reused their passing Gradle results. Alpha lint reported
**0 errors, 43 warnings and 4 hints**. The closing Gradle gate, including APK and
test APK packaging, completed successfully in 2 min 9 s.

The four web/public regression scripts and the nine snapshot-checker unit tests
passed. The final public snapshot scan passed for 556 source files, including
credential/artifact/archive checks. Staged and working-tree whitespace checks
also passed. The closing gate includes
three new consent-revocation regressions: revocation during connection setup sends
no text body, revocation during response reading discards the result, and queued
AI writes recheck developer/cloud/automatic-learning consent before storing.

Relevant gate commands:

```sh
./gradlew --offline --no-parallel --max-workers=2 \
  testDebugUnitTest :core:stream:test :core:translation:test \
  :app:testAlphaUnitTest :app:lintAlpha
node scripts/verify-listener-player.mjs
node scripts/verify-listener-i18n.mjs
node scripts/verify-speaker-mic.mjs
node scripts/verify-public-branding.mjs
python3 scripts/test-public-snapshot.py
python3 scripts/verify-public-snapshot.py
```

Regression coverage includes bounded audio queues, stalled-pipe recovery,
recognition finalization, per-language failure isolation, transcript retry scope,
manual package selection, playback/word selection, and settings/consent handling.
Migration and UI device cases additionally exercise synthetic scripts, session
filters, manual-follow behavior, large text, ID collisions, malicious archives,
capacity limits and preservation of existing data. Adding or compiling a device
case is not recorded as a passed final-artifact instrumentation run.

## Final artifact verification

Version: `0.2.40-alpha`, versionCode `46`, package
`app.guidecast.transmitter.alpha`. The current R8/shrink-resources candidate was
installed over the earlier code46 candidate using the same release certificate.
Earlier candidate hashes and runs are not substituted for the checks below.

| Check | Final status |
|---|---|
| R8/shrink-resources distributable build | Passed |
| APK size | 95,485,838 bytes |
| APK SHA-256 | `1c432e90ec46ef8c6d333007f867561f16f5ec3f5bf4b91a9d174d95fd3153fa` |
| Signing certificate SHA-256 | `afd9d964c7161f0052d16b0065e6dec14861900cfb876b18df639734ccf29ff3` |
| APK signature | v3 verified |
| 4-byte/16-KiB alignment | Passed |
| Exact installed/pulled-back APK hash | Matches the candidate SHA-256 above |
| Packaged listener/source correspondence | Four listener assets byte-identical to current source |
| Packaged licenses and notices | Packaging gate passed; 40 assets included |
| Public source snapshot scan | 556 source files passed credential/artifact/archive checks |
| API35 arm64 AOSP device cases | 51/51 passed in 50.619 s |
| Independent backup export | 4/4 passed in 4.502 s |
| Independent TTS worker failure isolation | 1/1 passed in 9.004 s; independent installed APK hash matches |
| Independent operator/standalone/TTS-web regression | 10/10 passed in 28.078 s |

Required final packaging and installation checks:

```sh
./gradlew --offline --no-parallel --max-workers=2 \
  :app:assembleAlpha :app:verifyPackagedThirdPartyLicenseAssets \
  :app:assembleAlphaAndroidTest
apksigner verify --verbose --print-certs MCastTalk-0.2.40-alpha.apk
zipalign -c -P 16 4 MCastTalk-0.2.40-alpha.apk
adb install -r MCastTalk-0.2.40-alpha.apk
```

The 51 cases use `DataTransferExportDeviceTest`, `FileTranscriptScreensDeviceTest`, `TranscriptScreensDeviceTest`,
`SentenceMemoryScreenDeviceTest`, `FileAudioDecoderDeviceTest`,
`FileAudioPlaybackDeviceTest`, `FileBatchSelectionDeviceTest`,
`FileTranscriptLibraryDeviceTest`, `DataTransferDeviceTest`,
`OperatorOptionsDeviceTest`, `DeveloperCloudMemoryDeviceTest`,
`UiDisplaySettingsDeviceTest` and `BroadcastTranscriptArchiveDeviceTest`.
They cover actual MediaCodec decoding and foreground MediaPlayer transport,
SQLite persistence/import, synthetic document selection, screen return and follow
behavior, developer visibility, and archive retention. Synthetic 360dp/2x-font,
320dp-height and developer-settings screenshots were also visually reviewed.
Short screens preserve a readable script line and playback controls; secondary
controls remain available through playback settings.

The four new export cases publish only synthetic settings, dictionary and script
ZIPs to the fixed device folder and run the existing ZIP validator on them.
They check pending/completed states, complete bytes, cancellation, partial-write
failure, preservation of previous backup hashes, and the actual UI retry button.
Each case removes only its own synthetic output. No real operator data was exported.
Antigravity independently reran all four on the same product APK.

An independent `MoonshineTtsLanguageFailureIsolationDeviceTest` run on the same
API35 emulator killed the English worker, confirmed the Japanese worker process
remained unchanged, and collected non-silent Japanese PCM afterwards. This is a
specific worker-isolation test, not a claim of multilingual speech naturalness.

Ten selected `BroadcastEmulatorIntegrationTest` methods independently passed:
app/operator branding, protected original-audio web/PCM, test tone without input,
translation readiness preserving operator authority, seven translated channels
plus original without microphone, rapid stop/restart, cancelling deferred restart,
standalone operation without listener sockets, app/manual-package selection, and
explicit Moonshine English TTS reaching the WebSocket as non-silent PCM.
The independent run used test APK SHA-256
`ac519b9b015479f230d27f262dd9a49edefe696370d2aa5dfed280a6607e2041`.
Its installed product APK was pulled back and matched the artifact hash above.

The first 51-case run had one screen-test failure: after a persona transition,
waiting for shared playback text could read the previous developer-enabled frame.
The same test passed alone without changing the product. The regression now waits
for the current persona's unique source text and retains its developer OFF/ON and
layout assertions. Only this test fixture changed; the final test APK SHA-256 is
`d764264ffcbcfc0ef4285f40b6babc3e8511c245f80a6bf86a10e0ea05f724d0`.
The final 51-case rerun passed in 50.619 s. Together with the 11 additional
independent operator/TTS cases, **62 distinct emulator cases passed**. The four
independent export reruns are not double-counted. No earlier release results are
counted.

Test duration is not first-audio latency. Synthetic fixtures prove only the
exercised software behavior; they are not recordings of actual users or
physical-device field tests. No physical handset or actual cloud API request was
used in these checks. Signing material is kept outside the public source and APK.
The available AOSP emulator runs API35 arm64 and has no Google on-device speech
service or speech language packs. API29 is below this app's minSdk30 and was not
used as an installation target.

## Release limits and remaining field validation

The requested **eight-hour continuous operation has not been demonstrated** on a
physical target handset. A source simulation or short emulator run cannot establish
that input, recognition, translation and speech remain reliable for eight hours.
The physical Galaxy S23 prepared-model first-audio **p95 ≤2,000 ms** gate has not
been measured or passed. No completed physical two-hour or eight-hour stability
result is claimed for `.40`.

Actual live/file speech-recognition accuracy, multilingual end-to-end operation,
cloud API translation quality, voice naturalness, source-emotion preservation and
faithful voice cloning have not been validated. Optional engine controls and
structural automatic checks do not certify those qualities. Device-only operation
still requires supported, prepared local engines; it does not imply every engine
or language works without a network connection.

Physical Galaxy/One UI capture behavior, screen-lock/projection revocation,
Android/iPhone browser playback, outdoor noise and hotspot load still require
testing in those exact environments. User field validation should exercise the
intended source apps and languages, offline/device-only mode, stopping and recovery,
and at least eight hours of continuous input. Record interruptions, recognition
gaps, translation/TTS failures, measured latency, crashes and ANRs without publishing
private audio or transcript content.

Local backup export and validated import are implemented. Portable user-requested
backups may contain private sentences; they are distinct from minimal diagnostic
exports. Private originals, transcripts, credentials and signing material remain
excluded from diagnostic exports and public release assets. This public Alpha is
not a field-stability certification.
