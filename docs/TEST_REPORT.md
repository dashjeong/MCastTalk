# 0.2.42 Alpha validation

## Status — 2026-09-19

This report covers signed `0.2.42-alpha`, versionCode `48`. It adds automatic
selected-language preparation, primary translation APIs, contextual register,
selective teacher proposals with approval and comparison reports, and a local
Astra assistant. Existing transcript recovery, HUD, file, archive and standalone
paths remain under regression. This is an experimental public Alpha, not a
physical eight-hour reliability or semantic translation-quality certification.

The requirement trace, personas and dated benchmarks are in
[PRODUCT_EVOLUTION.md](PRODUCT_EVOLUTION.md). Previous incident evidence remains
in the [0.2.41 report](https://github.com/dashjeong/MCastTalk/blob/v0.2.41-alpha/docs/TEST_REPORT.md).
No private diagnostic archive, source speech, credentials or original transcript
is included in source or release assets.

## Defects fixed and security boundaries

- A real TTS shutdown race was reproduced by the unit gate: the last operation
  could complete between a concurrent collection's size check and `toList()`'s
  single-element iterator access. Shutdown now takes a weakly consistent iterator
  snapshot without that unsafe shortcut. A 1,000-iteration completion/shutdown
  regression passes. This is a concurrency check, not 1,000 spoken utterances.
- Imported report metadata cannot grant local approval or rollback rights.
  Import normalizes reports to evidence-only status and removes the application
  receipt. A forged approved report cannot erase a confirmed sentence.
- Approval and undo compare the stored report inside a transaction. Approval
  preserves a sentence confirmed while the report was open, including an existing
  formal sentence when automatic register is selected. Undo leaves later human
  edits intact and cannot be replayed.
- API keys are Keystore-encrypted and destination-scoped. Portable settings omit
  keys/consent. Provider, endpoint, model or key changes invalidate authorization;
  settings import does not re-enable online or teacher use. Late responses after
  consent changes cannot write learning results.
- Synthetic attacks exercise endpoint/header injection, redirect rejection,
  excessive/deep/trailing JSON, tool-call and incomplete outputs, credential-like
  speech, approval replay and malicious backup metadata. Existing server access,
  Host validation, script-output escaping and bounded transfer regressions pass.
  Model output has no tool, shell, URL-execution or settings-execution authority.

These are code review and synthetic regression results. No claim is made that
commercial Gemini/Claude/DeepSeek/GLM attack agents conducted a penetration test,
or that all possible attacks have been eliminated.

## Source gates

JDK17, Android SDK/build tools 36; final full gate passed in **3 min 55 s** with
four Gradle workers. The relevant XML contains **1,549 test executions across
237 suites: 0 failures, 0 errors, 0 skipped**. Debug and Alpha variants of shared
tests count separately; these are not 1,549 distinct user scenarios. Unchanged
tasks reused passing results. Historical results outside these tasks are excluded.

Alpha lint: **0 errors, 50 warnings, 6 hints**. Added advisories concern KTX helper
preferences and boxed Compose state. Four web/public regression scripts and nine
public-snapshot checker tests passed. The staged source scan passed for **582
files**, and staged whitespace checks passed. No production dependency or model
was added. Existing bundled terminology and 40 license assets passed the actual
APK packaging gate.

```sh
./gradlew --offline --parallel --max-workers=4 \
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

New source regressions cover coalescing language changes and deferring preparation
during active audio; bounded teacher admission/cooldowns; normal repetition not
triggering review; unchanged/no-lesson rejection; asynchronous live proposals;
master/online authorization revocation; persistent duplicate suppression; honest
local advice; fixed assistant actions and cloud parser boundaries.

## Exact artifact

| Check | Result |
| --- | --- |
| Package | `app.guidecast.transmitter.alpha` |
| Version | `0.2.42-alpha` / `48` |
| Distributable | R8 and resource-shrunk Alpha |
| Size | 95,764,366 bytes |
| APK SHA-256 | `e91b2d79e97b52eadad3619ab958031a0a150546e66b4f7658098797d770e6ab` |
| Certificate SHA-256 | `afd9d964c7161f0052d16b0065e6dec14861900cfb876b18df639734ccf29ff3` |
| Signature/alignment | v3 verified; 4-byte and 16-KiB alignment passed |
| Update/install | Installed over code47; pulled-back APK byte-identical |
| Web assets | All seven packaged HTML/JS assets byte-identical to source |
| License assets | 40 packaged, license verification passed |
| Test APK SHA-256 | `32d5bd04dee2303f1d827ebe41e5e87d94b2ed4ee3c410473a355475a6c537a2` |

## Exact-artifact emulator checks

API35 arm64 AOSP: **78 distinct cases passed** on the signed APK above.
The test runner disables incidental model downloads; targeted preparation
orchestration is tested separately. Real model-download services are not mocked
as an eight-hour handset qualification.

- **16 cases, 26.907 s**: `TeacherLearningDeviceTest` (6),
  `TranslationApiDeviceTest` (3), `TeacherLearningScreenDeviceTest` (2),
  `RecognitionReconnectDeviceTest` (3), `HudAndAppSearchDeviceTest` (2).
  These exercise real SQLite transactions, Keystore storage, settings migration,
  approval/hold/undo and forged-import protection. Synthetic HTTPS connections
  verify primary API request/response and fallback without sending credentials
  externally; three protocol parsers and bounded context are checked. Actual
  Compose screens show before/after text, save an approved correction, return to
  the lab, display Astra and navigate only after a tap. Existing continuous
  recognition recovery, finite-input drain, 1,001-app search and live HUD behavior
  pass again. The synthetic approved-report screenshot was visually reviewed.
- **51 cases, 51.563 s**: `DataTransferExportDeviceTest`,
  `FileTranscriptScreensDeviceTest`, `TranscriptScreensDeviceTest`,
  `SentenceMemoryScreenDeviceTest`, `FileAudioDecoderDeviceTest`,
  `FileAudioPlaybackDeviceTest`, `FileBatchSelectionDeviceTest`,
  `FileTranscriptLibraryDeviceTest`, `DataTransferDeviceTest`,
  `OperatorOptionsDeviceTest`, `DeveloperCloudMemoryDeviceTest`,
  `UiDisplaySettingsDeviceTest`, `BroadcastTranscriptArchiveDeviceTest`.
  Covers real codec/playback/hash paths, 360dp/large-text and constrained layouts,
  developer-only details, retry, archive/session retention, backup/import and
  preservation of user-confirmed data.
- **11 cases, 41.419 s**: ten selected `BroadcastEmulatorIntegrationTest` methods
  cover branding, protected original PCM/web access, test tone without input,
  readiness warnings preserving operator authority, seven translation channels,
  rapid stop/restart and cancellation, standalone without listener sockets, app
  selection/manual override and real native English TTS reaching WebSocket as
  non-silent PCM. `MoonshineTtsLanguageFailureIsolationDeviceTest` kills the
  English worker and verifies that Japanese worker output continues.

## Limits and user field validation

No physical phone was connected. **Eight-hour continuous operation and Galaxy S23
prepared-model single-language first-audio p95 ≤2,000 ms remain unverified.**
No physical two-hour soak, Samsung/One UI capture, outdoor-noise, hotspot-load,
Android/iPhone browser acoustic result or voice-naturalness rating is claimed.
API29 is below minSdk30 and was not an installation target.

No paid external API or real Google speech-recognition service was called.
OpenAI-compatible means the implemented standard protocols, not measured
compatibility with every vendor/model. Teacher comparison uses selected examples,
fixed structural checks and user approval; it is not model-weight reinforcement
learning or measured semantic accuracy improvement. Astra uses current local
counters and fixed actions, and cannot autonomously rewrite or deploy the app.

Saved local assets are reused by existing providers; first-time downloads still
need network access. ML Kit does not gain a register-control API; automatic tone
is passed to API/Gemma and sentence memory. Native TTS tests establish the tested
PCM path and worker isolation, not the physical latency gate.

As requested, extended handset use remains user field validation. Diagnostics
stay minimal; portable backups and selected learning reports are user content,
not public diagnostic/release assets. Release assets contain only the signed
product APK and checksum; signing material stays outside source and APK.
