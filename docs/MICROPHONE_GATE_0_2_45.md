# 0.2.45 Beta microphone release gate

Date: 2026-09-24. Status: **unresolved; APK publication withheld**.

This records executed follow-up tests, including failures. It does not certify physical
microphone operation, identify a particular emulator defect, or establish eight-hour stability.
Antigravity operated the follow-up tests; Codex reviewed the scripts and numeric receipts.
Overbroad AI claims of a confirmed HAL/ring-buffer defect were rejected.

## Fixed inputs

- App APK SHA-256: `64e994a7d0e1c128ae4c131f05f609cbad208f6757119636b97edc289eaa3239`.
- Test APK SHA-256: `8f3c2b02f30669fa25d57659bb8452d04c34e203184cc9c665a828f6a557b541`.
- Public FLEURS fixture: `fleurs-ko-1959.pcm`, 7.02 seconds, 16 kHz mono PCM16LE,
  224,640 bytes; SHA-256 `b35aa5acf7ff72a4ec1b68415957ac9e7fe89268312cc8f5e5325a88acea841f`.
  The reference contains 112,048 nonzero samples. Counts below are diagnostics, not a
  substitute for waveform matching. Host physical microphone access remains disabled.
- Emulator 37.1.11, dedicated Android 15/API 35 ARM64 AOSP AVD. Product code and the
  strict waveform criteria were unchanged throughout these follow-ups.

## Observations

| Experiment | Executed outcome | Captured nonzero samples | Strict waveform |
| --- | --- | ---: | --- |
| Original note journey | FAIL: live transcription absent | 894 | Near-silence observed; no separate verifier pass claimed |
| Note journey after restart | FAIL: same live-text assertion | 2,299 | No pass claimed |
| Independent foreground collector, original delivery mode | Collector execution completed; input unproven | 4,770 | No pass claimed |
| Note journey with client pacing | FAIL: live-text assertion | Not reported here | No pass claimed |
| Note journey, full Qt instead of headless | Host/JUnit/device result FAIL | 510 | FAIL: correlation 0.0294334; 140/140 active windows failed |
| Note journey, additional two-second injection delay | Host/JUnit/device result FAIL | 582 | FAIL: correlation 0.0284369; 140/140 active windows failed |
| Independent collector, REAL_TIME with 20 ms client pacing | Collector execution completed; input integrity FAIL | 261 | FAIL: correlation 0.0007079; 140/140 active windows failed |
| Register virtual input before collector launch | INCOMPLETE: emulator disappeared before instrumentation could run | Not measured | Not measured |
| Same original AVD with isolated official emulator 36.5.11 | Collector execution completed; input integrity FAIL | 453 | FAIL: correlation 0.0154542; 140/140 active windows failed |
| Separate clean API 35 AVD | NOT RUN: first-boot userdata creation blocked by disk space | Not measured | Not measured |

The independent collector bypasses voice-note transcription, model queues and file writes
during recording. Its JUnit PASS means that collection code executed, **not** that the fixture
arrived intact. The device status is `CAPTURED_AWAITING_HOST_WAVEFORM_VERIFICATION`.
Neither that status nor a nonzero sample count is relabeled as a microphone-quality PASS.

For the pre-registration attempt, instrumentation reported `device 'emulator-5554' not found`.
There is no completed post-install hash check or waveform result for that attempt. A later
crash-handler message is insufficient to identify the initiating failure or a buffer defect.
The original incomplete receipt is preserved instead of being rewritten as a completed test.

The original helper hash matches the earlier round-15 run that recognized the fixture.
This eliminates an assumed helper-code change as an explanation; it does not establish which
environmental or runtime difference caused the observed loss. The earlier round-15 waveform
gate also had failures, so it is not an all-green reference.

The older emulator came from the [official archive](https://developer.android.com/studio/emulator_archive),
Apple Silicon build 15261927 (36.5.11); archive SHA-256
`60b8a501dce42008187e1fed4e77c164985b6789884991b934aa28f25cb75a3b`.
The installed SDK was not replaced. Its first launch was blocked by insufficient host disk
space and is not counted as an audio test. After freeing unused regenerable build cache,
the completed run retained matching app and test APK hashes before and after collection.
Changing emulator version alone did not restore the signal in this observed run.

The separate clean-AVD control could not execute. The launcher requested 7,372.80 MB for
initial userdata creation while approximately 3,980 MB was available. A unique 2 GiB
configuration value and then the documented `-partition-size 2048` option did not reduce
that observed requirement. These startup attempts are **not** microphone tests, and the
original AVD was neither reset nor deleted. Persistent AVD-state effects therefore remain
unresolved. Further qualification needs a working input test environment; a clean control
here additionally needs enough host storage for initialization. No product capture fix is
claimed from these environment experiments.

## Evidence identifiers

Receipts and audio stay in the local test archive. Public documentation includes only minimal
numeric results and identifiers. Failed note tests do not reach their screenshot steps;
pre-existing screenshot files on the emulator are **excluded** as evidence for those runs.

| Run directory suffix (UTC) | Receipt SHA-256 |
| --- | --- |
| `beta045-qt-window/20260924T023523Z` | `12cd32c12976e70e736aa92979539494f848561012ebe3e3245c2ad0736546eb` |
| `beta045-delayed-injection/20260924T025158Z` | `65ac24aa6eb53f00627cb86d10617f64bdd5413c30c2192180343ab7f33e5e29` |
| `beta045-reference-realtime-paced/20260924T025821Z` | `496fb499bcdeb7df072dd1f8874b833fb23a754662900a77f02a8a9aff72ab6d` |
| `beta045-prearm-injection/20260924T030346Z` (incomplete snapshot) | `e02c2ab4a9e8b8271c825c6e7f272aeea4eaaacb3b1404700c6e3919ffc85486` |
| `beta045-emu36511-reference/20260924T032008Z` | `a4fc89878f4ff5558c1627813468dc25516767999cbf638c983fcb3df1ab6963` |

See [overall validation](TEST_REPORT.md) and [sentence timing and quality limitations](BETA_0_2_45_VALIDATION.md).
