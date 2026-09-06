# Public Alpha validation

## Scope — 2026-09-07

This update improves transcript recovery after rate limiting or network failures
and preserves sentence completion across partial recognition updates.
Transient transcript failures retain the same-session text and show a delay notice.
Retries respect both Retry-After formats; manual refreshes share the same backoff.
Authentication failures clear cached text, and changing channel/credentials resets
the retry scope. Existing authentication, Host checks and server limits remain.

## Source regression evidence

JDK 17; Android SDK/build tools 36; local unit tests and Alpha lint:

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

The runtime revision passed 1,344 test executions across 194 XML suites, with no
failures, errors or skipped tests. This includes Debug and Alpha executions of
shared cases. Alpha lint: 0 errors, 26 warnings and 1 hint. All four web/public
regression scripts and nine snapshot-checker unit tests passed.

Coverage includes empty-cache 429/network failures, repeated manual refresh,
HTTP-date Retry-After, channel/credential isolation, stale transcript retention,
401/403 clearing, bounded PCM queues and monotonically scheduled audio frames.
Sentence tests cover measured silence, incomplete Korean clauses, earlier usable
boundaries, retained context and exactly-once finalization.

## Final APK

Version: `0.2.39-alpha`, versionCode `45`, package `app.guidecast.transmitter.alpha`.
The R8/shrink-resources Alpha APK was built with the reviewed local glossary.
All 454 compared source/build inputs match public source commit
`bb912d1c15a0c925735734575f465fa786e3936d` (documentation and workflow files excluded).
Remote source CI: [Android CI run 34061189758](https://github.com/dashjeong/MCastTalk/actions/runs/34061189758).

| Check | Result |
|---|---|
| APK size | 94,042,392 bytes |
| SHA-256 | `90e98764989249d1cee6a03be3ccd9168467488ca0c5179993056a2eb6ece7f0` |
| Signing certificate SHA-256 | `afd9d964c7161f0052d16b0065e6dec14861900cfb876b18df639734ccf29ff3` |
| Signature/alignment | APK v3 verified; 4-byte/16-KiB alignment passed |
| Installed artifact | Pulled-back APK SHA-256 exactly matches the release candidate |
| Packaged listener | All four listener assets exactly match the public source |
| License assets | 40 files included |

Commands used for packaging and installation checks:

```sh
./gradlew --offline --no-parallel --max-workers=2 \
  :app:assembleAlpha :app:verifyPackagedThirdPartyLicenseAssets \
  :app:assembleAlphaAndroidTest
apksigner verify --verbose --print-certs MCastTalk-0.2.39-alpha.apk
zipalign -c -P 16 4 MCastTalk-0.2.39-alpha.apk
adb install -r MCastTalk-0.2.39-alpha.apk
```

Signing uses the existing external private release key; no key or password is
included in the source or release assets. Public CI builds without the separately
obtained glossary; the distributable APK packaging check uses that local data.

## Final APK device regression

Nine instrumentation cases passed on the API35 arm64 emulator with the exact
APK hash above, installed over the prior same-signer code44 candidate:

| Cases | Result |
|---|---|
| App/operator branding; protected original-audio web/PCM; test tone without input; translation readiness preserves broadcast control; seven translated channels plus original without microphone; rapid stop/restart; stop cancels deferred restart | 7/7 passed, 19.143 s |
| Explicit Moonshine English TTS reaches the listener WebSocket as non-silent PCM | 1/1 passed, 6.622 s |
| Killing the English worker leaves the Japanese worker and non-silent PCM alive | 1/1 passed, 8.554 s |

Durations are test-run times, not first-audio latency measurements. Instrumentation
uses `BroadcastEmulatorIntegrationTest` and
`MoonshineTtsLanguageFailureIsolationDeviceTest` with AndroidJUnitRunner. The
English test explicitly selects Moonshine and restores the user's prior preference;
AUTO engine selection is not treated as proof that Moonshine was exercised.

## Limits

API35 arm64 emulator checks establish behavior only in that environment. Physical
Galaxy/One UI, Android/iPhone browser playback, outdoor noise, hotspot load,
human-rated voice naturalness, four-language end-to-end STT pipelines and a
two-hour stability run have not been verified for this release. The Galaxy S23
prepared-model first-audio p95 ≤2,000 ms gate has not been measured or passed.
This is an experimental Alpha prerelease, not a field-stability certification.
