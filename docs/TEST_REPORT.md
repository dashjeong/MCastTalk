# Public candidate validation — 2026-09-06

Status: local source/build checks completed. Source-only publication is authorized;
APK distribution requires separate artifact validation. See the source-only check
below, `PUBLIC_RELEASE_CHECKLIST.md` and `THIRD_PARTY_LICENSES.md`.

## Executed checks

- Public-boundary synthetic regression: 8 tests passed. Fixtures cover plaintext
  credentials, compressed credentials, excluded artifacts, archive links,
  logcat files and unreviewed or modified databases.
- Web checks: listener scheduling, 8-language localization/transcript safety,
  remote microphone state machines, and public branding all passed.
- JVM/Android host tests: 1,016 tests, zero failures/errors/skips. This includes
  68 server tests and 188 stream/translation JVM tests.
- Alpha lint: zero errors, 26 warnings, one hint. Warnings are not a clean-lint
  or security-certification claim.
- Unsigned Alpha build and exact packaged license/glossary checks passed using
  the generated output metadata. No private signing key was used.
- Bundled terminology SQLite integrity check passed; its only table is `terms`
  with 97,371 rows. Integrity is not proof of redistribution rights or the absence
  of every kind of personal data.

Commands used:

```sh
python3 scripts/test-public-snapshot.py
python3 scripts/verify-public-snapshot.py
node scripts/verify-public-branding.mjs
node scripts/verify-listener-player.mjs
node scripts/verify-listener-i18n.mjs
node scripts/verify-speaker-mic.mjs
./gradlew testDebugUnitTest :core:stream:test :core:translation:test \
  :app:lintAlpha :app:verifyPackagedThirdPartyLicenseAssets \
  --offline --no-parallel --max-workers=2
```

## Fixes verified

The packaging check previously required `app-alpha.apk` and failed for an unsigned
public build. It now selects the one output identified by the Alpha metadata,
validates the variant, output filename and directory, and checks the packaged
license/glossary assets. CI now runs this check and the two JVM modules' tests.
CI has read-only repository permissions, pinned actions, no persisted checkout
credentials, no Gradle cache upload, and no APK/log artifact-upload step.

## Not established

### User-reported physical testing (2026-09-06)

The user reported physical testing on Galaxy Note9, S21 Ultra, S23+ and S26 Ultra.
They specifically reported five translated languages in addition to original audio
on S21 Ultra and S26 Ultra, and latency on Note9. They recommend Galaxy S20 or newer
and research/development use on a dedicated test phone until publisher signing and
installation verification are complete.

This is attributed user evidence, not a test executed by Codex. The tested APK
hash/version, OS versions, timing measurements and test durations were not supplied.
It does not establish that this exact final APK passed, nor change the current
Android 11/API 30 minimum. A fully unsigned APK is not directly installable; a
locally signed debug build is distinct from a verified publisher release.

### Remaining unverified release gates

- Individual redistribution rights still listed as unresolved in the license
  inventory, including terminology sources and some model/voice data.
- Signed release APK, signature continuity, exact-artifact emulator installation,
  physical Galaxy/One UI behavior, Android/iPhone browser listening, outdoor
  noise, hotspot load, voice naturalness, S23 first-audio p95, or two-hour stability.
- Complete absence of unknown vulnerabilities, personal data, third-party SDK
  logging, or outbound network activity. See `SECURITY.md` for access defaults,
  plaintext transport and locally retained transcripts.

No public upload was performed as part of this validation. Raw diagnostic logs
and private incident evidence are intentionally excluded from this source tree.
# GitHub source-only verification — 2026-09-06

The public Git snapshot excludes the original terminology spreadsheets and the
converted database. Source/download notices remain, and the reviewed database is
retained only as ignored local input for complete app builds.

On macOS with JDK 17, Gradle 8.13 and the configured Android SDK, an isolated copy
without the database passed `testDebugUnitTest`, `:core:stream:test`,
`:core:translation:test`, `:app:lintAlpha`, `:app:assembleAlpha` and
`:app:assembleRelease`: 1,016 tests, zero failures/errors/skips; build successful.
The public-snapshot regression suite passed 9 tests, including database and
spreadsheet exclusion. Branding, listener scheduling, listener i18n and speaker
microphone Node regression gates also passed.

These source-only APKs are not distributed and do not include the base glossary.
This check does not replace full local `:app:verifyPackagedThirdPartyLicenseAssets`
with the reviewed glossary, nor signature, installation or physical-device tests
for an eventual distributable APK. Earlier measurements below refer to their
stated test artifacts and environments.
