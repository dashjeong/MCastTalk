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

Artifact identity, signing checks and device results are recorded with the
versioned GitHub prerelease. Source tests alone do not qualify an APK for handoff.

## Limits

API35 arm64 emulator checks establish behavior only in that environment. Physical
Galaxy/One UI, Android/iPhone browser playback, outdoor noise, hotspot load,
human-rated voice naturalness, four-language end-to-end STT pipelines and a
two-hour stability run have not been verified for this release. The Galaxy S23
prepared-model first-audio p95 ≤2,000 ms gate has not been measured or passed.
This is an experimental Alpha prerelease, not a field-stability certification.
