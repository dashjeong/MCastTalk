# 0.2.45 Beta: sentence timing and mobile usability evidence

Validation date: 2026-09-24. This report separates source/unit checks, recorded speech,
synthetic UI journeys and remaining quality failures. It is not a commercial-quality,
physical-device or eight-hour reliability certificate.

## Recorded speech comparison

Source: [침착맨 — 의식이 흘러가는 대로 푸는 썰](https://www.youtube.com/watch?v=-dK9vHf0Y_0),
00:30–02:46, 136 seconds. Both versions receive the same 16 kHz mono signed 16-bit PCM,
SHA-256 `a4e9e17fe04a3d11c23f7504e5f9b762765e9ad6c4c4f5f089058b761066982f`.
The second condition multiplies amplitude by 0.125 (−18.06 dB). It is digital attenuation,
not a physical whisper, noisy-room or microphone-distance test. Source audio and full
transcripts are retained locally for analysis and are not redistributed.

Baseline: published 0.2.44 Alpha, APK SHA-256
`4c344a8375a7ebb0f5724eac79b18b3b8c3a808c21a3e97ade8de2092c0efa0c`.
Final signed Beta: APK SHA-256
`64e994a7d0e1c128ae4c131f05f609cbad208f6757119636b97edc289eaa3239`.
This includes the note-tools layout correction and the additional explanatory/contracted-past
ending regression. Measurements from earlier candidates are retained separately below;
they must not be presented as this APK's measurements.

The metric below is the first accepted ASR hypothesis containing a unique selected phrase
to its semantic final event, measured on the host-independent Android elapsed clock.
It is **not** acoustic sentence-end latency, TTS latency, or listener first-audio latency.
Both phrases are present in exactly one final unit in each compared run.

| Selected phrase | Input condition | 0.2.44 wait | Final 0.2.45 Beta wait |
| --- | --- | ---: | ---: |
| 그러잖아 | Original amplitude | 25,640 ms | 2,295 ms |
| 그러잖아 | Attenuated | 28,672 ms | 3,376 ms |
| 기량이 떨어져 | Original amplitude | 14,442 ms | 1,244 ms |
| 기량이 떨어져 | Attenuated | 16,592 ms | 2,904 ms |

Original/attenuated baseline runs delivered 26/24 final units; the corrected runs delivered
34/26. Every unit completed translation and all four runs ended with zero pending units.
The 136-second inputs took 181.149–187.618 seconds of harness wall time because of input
backpressure. These are execution-completion and selected timing observations, not overall
semantic accuracy or a real-time acoustic benchmark. Unit-count differences do not prove
better or worse segmentation without a verified reference transcript.

Remaining behavior is visible in the final runs. In the original-amplitude run, a long pending
unit still completed at 110,476 ms after the previous final at 79,091 ms (a 31,385 ms inter-final
gap, **not** acoustic sentence-end latency). The recognizer alternated the sentence prefix and
the complete/incomplete ending, so the complete form did not stabilize. Adding the ending to
the rule set does not repair unstable ASR. Repeated short responses also remain: repeated text
already exists in raw ASR hypotheses in both baseline and Beta. The Beta may publish those as
separate units. This report does not claim that all long waits or recognition repetitions are fixed.

An earlier candidate replayed a question ending after the recognizer changed digit notation
to spoken-number notation. The committed-prefix alignment regression reproduced that defect.
In both corrected runs, both number spellings occurred in raw accepted hypotheses and the
isolated replay did not occur. This is two observed runs, not a guarantee for arbitrary ASR
revisions. Synthetic tests additionally preserve a genuinely repeated new question.

Local result-file SHA-256 identifiers (full transcripts intentionally excluded):

| Result | SHA-256 |
| --- | --- |
| Baseline original | `2c01eafc081ed3c54fb9fe5705c1f34113a4a0b9693c91918f7514318faee23c` |
| Baseline attenuated | `f978dbbc46d145d76ddf0e575d372d26e45c2dbf5242eab34bc43e739dbe785c` |
| Final Beta original | `dd0ab2b59d1324e15364ec35b90a16646112b40208d942cea9219e44a25e1b42` |
| Final Beta attenuated | `1789f0ca468ab73ab03fef7655605c507c1174fd33f1b69463c69d2cd6a566ea` |

An earlier candidate (`714b1ce323523f2d21b14b3fb86929f4525ee879d316414f0b42ea156abcd766`)
completed 24/21 units. Its selected waits were 2,156/3,594 ms and 2,080/3,602 ms in the table's
order. Its original/attenuated result hashes are
`78b7abcb87ba6930172975f70fba404aca5d7e311d1b7aeb44abe7c54ea305a1` and
`a21c45b8b2a7e0f0eff9e5a3344b1f9b24badb50d633b5d5f5d6866ad74c26fe`.
They are retained as intermediate evidence, not substituted for the final artifact.

## Failure accounting

- First UI candidate: seven completed Android tests, six passed and one failed. At 320 dp
  height, the long options form preceded sentence actions and the bounded scroll search could
  not reach **여기서 듣기**. Note tools were separated from sentence detail in response.
  The original failed receipt is retained; final rerun results belong in `TEST_REPORT.md`.
- Real-model functional group on the earlier `714b…` candidate: six completed tests,
  five passed and one failed. Broadcast PCM delivery, the actual test button's repeat run,
  file conversion/playback, operator dictionary priority and repeated public speech passed.
- `SemanticTranslationCorpusDeviceTest` retained incomplete negation/condition/number/time
  clauses and passed their exact assembled-source assertions. Its translation-quality gate
  remains **FAIL**: one of four authored sentences failed the lexical check in both repetitions.
  The Korean prohibition of entering a place became “you should not get here.” The negation
  survives, but entry versus arrival and unnatural wording remain concerns. Neither the test
  expressions nor production output were patched just to make this case pass.
  The identical output was already recorded in [round 6](MAINTENANCE_REPAIR_0_2_43.md).
  Two repetitions of one sentence are not two independent quality samples.
  The final APK reruns the five passing functional journeys plus four home/navigation tests;
  that 9/9 result does not overwrite the separate quality failure. The unchanged translation
  engine's failed corpus gate is not counted as a pass or silently included in a green total.
- Antigravity's review is AI-assisted independent review, not a human translation study.
  Earlier overbroad claims about perfect segmentation were rejected and corrected.

## Scope and reproducibility

`scripts/run-device-evidence.py` verifies the installed target and instrumentation APK hashes,
records the selected classes and Android environment, and cross-checks per-test terminal
statuses against the JUnit totals. A successful shell exit alone cannot pass. Both public
speech runs use `SpeechCorpusBenchmarkDeviceTest` with their explicit corpus manifests.

The UI tests use authored synthetic notes at 360×640 dp with 2× fonts and 360×320 dp with
normal fonts, actual overflow navigation, translation visibility, reachable export actions,
editor dialogs and repository persistence. These are scenario checks, not measured user
satisfaction. See [design rationale and references](CONTENT_FIRST_MOBILE_DESIGN.md).

There is no new all-language 30-clip qualification, independent ground-truth WER/semantic
error rate, physical Galaxy/One UI test, human voice-naturalness approval, Galaxy S23 p95
first-audio measurement, or elapsed eight-hour stability result. Known translation weaknesses
from 0.2.44 remain disclosed. Automatic source-language detection is not newly implemented
for the local live recognizer. Beta scope is the described timing and interface changes.
