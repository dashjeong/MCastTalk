# Maintenance validation follow-up — 2026-09-24

Status: **VALIDATION RECORDED / RELEASE HELD — QUALITY GATES NOT MET**. The user's release
authorization remains valid; it does not turn failed verification into a pass. This record follows the
maintenance baseline `2dc5142eea10a1d37ee44bdfffe9b15e83818b35`; it does not replace
the earlier failed runs. A host command returning zero is never sufficient evidence.

## Fixes under verification

- Preserve complete committed Gemma reference context up to the existing 400-character
  input contract. The previous three 300-character prefix truncations could discard
  the newest qualifier or negation. Oversized unstructured optional context is omitted
  rather than cut through a sentence; the current source remains intact.
- Strengthen the real microphone note journey: six public-fixture keywords must appear
  while recording, committed live/saved text must match, the saved note must reopen,
  and the actual WAV must have valid PCM16 mono/16 kHz headers, length and signal.
- Tie the virtual microphone endpoint to the tested emulator serial. Parse complete
  instrumentation results and record transport completion separately from bytes
  submitted and audio actually captured.

## Completed execution in this follow-up

| Test | Environment / result | What it establishes |
| --- | --- | --- |
| Round 13 build gates | `testDebugUnitTest :core:stream:test :core:translation:test :app:testAlphaUnitTest :app:lintAlpha :app:assembleAlpha :app:assembleAlphaAndroidTest :app:verifyPackagedThirdPartyLicenseAssets --offline --max-workers=2`; successful in 3m15s | Build, lint, license assets and requested unit gates. Snapshot contains 1,791 tests, no failures/errors/skips; includes up-to-date suites, not 1,791 newly executed tests. |
| Unchanged round 12 microphone baseline | API 35 ARM64 AOSP, 3 GiB, public 7.02-second FLEURS virtual microphone; 1 passed | Earlier limited live/save/reopen assertions pass once. No physical microphone claim. |
| Updated host helper, unchanged round 12 APK | Same AVD while build was active; 1 failed | All 224,640 fixture bytes were submitted and RPC completed, but the saved WAV contained only an approximately 3.27-second audible prefix. Submission alone does not prove reception. Build contention is a candidate, not an established cause. |
| Strengthened round 13 microphone journey | Same AVD after build ended; 1 passed in 43.063s | Six keywords live/saved/reopened; exact committed text retained; valid 10.816-second saved WAV with 133,052 nonzero samples and RMS 0.0199275. Does not prove exact transcription or sample continuity. |
| Host regression checks | 4 microphone evidence + 16 instrumentation evidence + 8 waveform verifier tests passed | Checked false-success, wrong-endpoint, truncated/reordered/duplicated signal and protocol scenarios only. |
| Antigravity independent review | Six scoped public source hashes matched; 4 + 16 host tests actually executed and passed | Independent tool/schema review. No ADB, model inference, physical-device or release approval result. Incorrect initial bidirectional-RPC and causal claims were corrected before acceptance. |

Round 13 signed distributable: SHA-256
`7cc6fcddc48a85be1c94546a7fe34bcf577f3e4b6c5e42c3e87207cd461c6033`,
96,309,312 bytes. Matching instrumentation APK:
`fc99733a600aab8f63fc31cb9029c5374e69cea443d6fef38f00bbc093d42450`.
Signature, 16 KiB alignment and installed identities were verified. This is a
maintenance artifact, not a published fix release.

## Waveform continuity remains distinct from UI success

The host verifier fixed its criteria before inspecting these recordings: global
correlation >= 0.95, 100 ms windows every 50 ms, active-window correlation >= 0.90,
and relative alignment error <= 8 samples. Positive gain/DC and initial delay are
allowed. Silence cannot establish sample identity.

| Run | Global correlation | Failed active windows / 140 | Strict result |
| --- | ---: | ---: | --- |
| Unchanged round 12 | 0.92043 | 34 | FAIL |
| Updated helper with concurrent build | 0.76718 | 76 | FAIL |
| Strengthened round 13 | 0.99017 | 26 | FAIL |

The latest recording contains approximately the complete 7.02-second speech span,
but relative shifts up to 55 samples (3.4375 ms) remain. These results do not identify
whether guest capture, host injection or emulator resampling caused the changes.
They must not be reported as physical acoustic tests or lossless PCM proof.

### Round 15 — note journey passed; independent input integrity failed

On the same hash-verified API 35 ARM64 AOSP installation, the real note journey
passed one test: all six fixture keywords appeared live, saved and reopened, with
one committed sentence and identical live/saved text hashes. Its 9.776-second WAV
still failed unchanged waveform criteria: correlation 0.977890, 24/140 failed
windows (one waveform mismatch and 23 alignment failures, up to 60 samples/3.75 ms).

An independent 16-second AudioRecord memory capture then completed without note
UI, STT or periodic disk writes. This execution pass did not establish complete
input: correlation was 0.710745 and 88/140 windows failed, beginning at source time
2.6 seconds. Its actual buffer was 8,000 frames/500 ms; maximum read-call gap was
49.597 ms, maximum read duration 11.828 ms, and silenced observations zero. There were
62 successful timestamp samples with no reversals and one unavailable query.
The injector reported the complete pinned
224,640-byte fixture and normal RPC completion, while the saved waveform was incomplete.

The Activity was opened before instrumentation, but foreground state was not
asserted inside that reference run. Instrumentation may restart the app; therefore
this is not yet a controlled foreground comparison and cannot isolate the cause
from note work, background restrictions, injection, guest audio or scheduling.
The virtual-input integrity release gate remains blocked. Keep the observed user
journey pass distinct from lossless PCM, physical-microphone or long-duration claims.

## Real local translation comparison

The existing pinned STANDARD Gemma model was staged on a dedicated API 35 ARM64
emulator with 6 GiB RAM. The downloaded bytes and device file both matched:

- Revision: `6e5c4f1e395deb959c494953478fa5cec4b8008f`
- Bytes: 2,588,147,712
- SHA-256: `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c`

The first actual run used five previously recorded public-source ASR examples and
five independent authored counterexamples. ML Kit produced ten drafts. Actual
Gemma warmup succeeded in 6,190 ms, but all ten review attempts failed immediately;
**zero reviewed translations and zero review-quality passes** were recorded. The
first failure class was `IllegalStateException`; the test must preserve a safe cause
code in subsequent runs. No cloud AI API or private diagnostic content was used.

Source inspection then identified a wire-contract mismatch: the provider sends the
default `AUTO` style while the runtime accepts only empty, `FORMAL` and
`CONVERSATIONAL`. The warmup has no explicit style and therefore does not exercise
that contract. A regression and real-model rerun are required before calling it fixed.

A direct ten-second provider review is not the live selective-review path, whose
800 ms budget also includes queue wait. Their application rate, failures, fallback
and meaning checks must be reported separately.

### Round 14 — style contract corrected; quality still failed

The unchanged style allowlist failed the new default-style regression (1 of 5 style
tests failed); adding only the existing `AUTO` enum made all 106 provider tests pass.
The empty/FORMAL/CONVERSATIONAL contracts and rejection of arbitrary instructions
remain intact. No output-language or vocabulary answers were hardcoded.

The first full build stopped when five Alpha diagnostic-log tests could not create
JUnit temporary directories (`No space left on device`, before product assertions).
The failure XML was retained. Reclaiming 948,155,213 bytes of regenerable native build
and official diagnostic-model caches allowed the same gates to pass in 1m35s with
one worker. Sources, reports, test recordings and signed artifacts were retained.
The final snapshot has 1,819 test executions, zero failures/errors/skips, including
up-to-date results. Both app variants have 526 passing executions. Recovery's first
13-case suite passed in each variant; the later reservation-race regression is not
included in this count.

Round 14 product SHA-256:
`42ac542d2c853317ca629733eb9574bd64e00af9d6a1b35af33cc62c201e1ba9`;
96,325,696 bytes. Instrumentation SHA-256:
`0b94dcdc6374f28bb8b8171734b7dae0eb9a78d3e329657a3535bf0c671b8515`.
Both exact artifacts were signed, aligned, installed and hash-checked.

| Actual route | Result | Interpretation |
| --- | --- | --- |
| Direct ML Kit draft → Gemma review | 10/10 inference completions; 0/10 meaning-asset passes; 26.300s total | `AUTO` no longer causes immediate rejection. All responses remained Korean instead of the requested English. Inference completion is not successful translation. |
| Default selective review budget and real queue/native providers | 10/10 requests completed using drafts; 0/10 reviews applied; 4/10 final meaning-asset passes | First review timed out at 800ms (wrapper 817ms); preparation became false and the next nine were unavailable. The four passes are unchanged ML Kit counterexamples, not improvement credit. |

The selective comparison intentionally did not call the new service recovery
coordinator or insert hidden warmups between cases. It establishes the real
timeout/invalidation path, not service automatic recovery. The direct review prompt
and untranslated-source guard need further real-model verification. A submitted or
nonempty response must not be counted as correct solely because the model completed.

### Round 15 — language correction and recovery executed

Round 15's full build gates succeeded in 2m18s. The preserved XML snapshot has
1,828 executions, zero failures/errors/skips, including up-to-date suites. The
provider's 113 tests passed and the recovery coordinator's 14 cases passed in each
app variant. The signed product is 96,325,696 bytes, SHA-256
`a1a2d4dd30101e73c58be93e92906aee28be816f0c16ed2931cc92e4c80a9ccc`;
matching test APK SHA-256
`42241f6242467da8861fe8095521d4b5fde13f8468e1dca17e46d02bef168fcf`.
Both installed artifacts matched their expected hashes.

| Actual execution on API 35 ARM64, 6 GiB | Result | Boundaries |
| --- | --- | --- |
| Direct contextual review after explicit output-language instructions | 10/10 completed in English; 7/10 fixed meaning-asset cases passed; JUnit FAIL; 31.144s | Wrong output language was corrected for these cases. Three meaning errors remain. This is a text-to-translation route, not a new ASR or live 800ms-budget test. |
| Real provider timeout → recovery helper → subsequent native request | JUnit PASS; 7.670s measured scenario | First review timed out (818ms), next probe was unavailable, one actual warmup recovered preparation, then a new request reached native inference. It also timed out (820ms) and returned its draft. This proves one recovery/readmission, not improved translation or complete BroadcastService E2E recovery. |
| Recorded speech → capture → STT → ML Kit → TTS → listener PCM; operator test button twice; DocumentsUI WAV selection → conversion → library playback | 3/3 PASS; 166.095s | Actual production paths on the exact round15 APK. No physical acoustics, prolonged stability, subjective voice quality or complete translation-meaning claim. |

The three remaining review errors involve the military compound meaning, people
versus elapsed minutes, and an ongoing ordinal year versus completed duration.
Independent analysis also identified one overly narrow daily-continuity matcher:
the model's wording preserved that sub-meaning, but the same case still failed its
ordinal relation. Neither the old result nor its fixed fixture was rewritten.

The copied-source guard rejects only sufficiently long Korean sentences nearly
copied into specified non-Korean targets; it is not a universal language detector
or semantic quality score. No production cloud API, private audio or diagnostic
text was sent for this comparison.

Antigravity's further review could not be dispatched while the Mac was locked.
Its accepted scope remains the previously completed 20 host-test executions and
six-file static review. No additional cross-check or release approval is implied.

### Round 16 — rejected prompt experiment and direct-route control

The next build succeeded in 2m06s. The signed product SHA-256 was
`0877842b5193327d111c117dc657f8ee4b13e39dead43d62a3adce0d9f099fcd`;
the matching test APK was
`eca49b73a02fbdf4aad5e4aabb9f99940d86b65496705b58d24fe6b802783c8f`.
Both installed hashes were checked before and after each run. The unchanged ten
fixtures, target language, model and semantic gates were used in both routes.

| Experiment | Actual result | Decision |
| --- | --- | --- |
| Additional grammar instructions and DRAFT-before-ORIGINAL review layout | 10 inference completions; 6/10 semantic cases; 31.959s scenario; JUnit FAIL | Regressed from 7/10. The experimental prompt and its structure-only assertion were removed. Failed artifacts and output evidence were retained. |
| Existing direct source/context translation, without giving Gemma the ML Kit draft | 10 inference completions; 8/10 semantic cases; 23.658s scenario; JUnit FAIL | Useful route contrast, not proof of a single anchoring cause: the production direct prompt also differs. People/time and ordinal/duration errors remained. No automatic routing change was made from this small sample. |

The five authored counterexamples passed both routes. Among the five selected
previous ASR outputs, the review experiment passed one and the direct route passed
three. These selected-case proportions are not a general translation error rate or
a fresh full-audio, sentence-boundary, multilingual-corpus or ChatGPT runtime test.
No fixture, output or threshold was changed to turn either run into a pass.

### Round 17 — final maintenance artifact and foreground-controlled capture

After rejecting the regression, final gates completed in 19s. All unit-test tasks
were UP-TO-DATE or FROM-CACHE: the preserved 1,828/0/0/0 XML snapshot is reused
passing evidence, not 1,828 fresh executions. APK, instrumentation, lint and packaged
license gates completed. Final signed product SHA-256 is exactly the round15 hash
`a1a2d4dd30101e73c58be93e92906aee28be816f0c16ed2931cc92e4c80a9ccc`.
It was reinstalled and hash-verified. The matching updated test APK is 4,763,881
bytes, SHA-256 `ced62ae66da2be2f0115eff618468ac038d68a1f699044e9d99f11e6d5294bf7`.
Signature and 16 KiB alignment passed for both. The exact product bytes already
have round15's functional, recovery and 7/10 failed-quality evidence; unchanged
journeys were not rerun merely to increase counts.

The independent reference now opens MainActivity inside instrumentation, asserts
RESUMED and monitors lifecycle changes throughout capture. That test completed
with foreground verified and no foreground loss or silenced observation. Actual
buffer duration was 500ms, maximum read gap 50.152ms, maximum read call 0.993ms;
62 successful timestamps were monotonic and one query was unavailable. The WAV
contained 15.968s, 172,214 nonzero samples, RMS 0.0164008, SHA-256
`3d1a98ecbfb252df658032c3a2cb10ddac9eefdd714512906c0b13ed57caca6b`.

The strict waveform check still failed: correlation 0.988734; 23/140 windows
exceeded the unchanged timing tolerance, maximum 57 samples/3.5625ms. All local
waveform correlations met their threshold; the failures were timing shifts from
source time 5.85s onward, not the earlier large prefix-only capture. This isolates
those observed shifts from the note STT/periodic-write path, but does not determine
which injection, guest-audio or resampling layer caused them. The original
foreground-unverified run remains unsuitable for a controlled causal comparison.
Neither run establishes physical microphone continuity.

The public-source boundary scan passed 696 tracked files. It checks known
credential patterns, forbidden artifacts and nested archives; it is not an
exhaustive security or penetration-test claim. Public 0.2.42 remains the released
baseline. No fix release, tag promotion or public APK upload was performed because
the semantic and remaining environment/corpus gates are not met.

## Previous corpus limitations retained

The seven Korean corpus cases with no raw recognition callbacks remain failed
executions, not successes. Full-audio signal metrics show non-silent input, while an
independent diagnostic recognizer inspected only 120 seconds of their combined
892.94 seconds (13.44%). That does not establish all seven as non-speech. Other six
languages' requested 30 long samples each have not been completed in this AOSP
environment. No physical Galaxy latency, microphone, listening quality or eight-hour
result is established by the work here.

Raw public test outputs, recordings and diagnostic reports remain local. Public
source control receives code, fixed-source references, aggregate outcomes and hashes;
user diagnostic archives, source recordings, credentials and signing keys are excluded.
