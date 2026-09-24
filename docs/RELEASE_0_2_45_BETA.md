# 0.2.45 Beta — conversational finalization and content-first voice notes

This update targets delayed sentence finalization during a single speaker's continuing Korean
conversation. It retains the existing package ID and signing identity for an in-place update
from 0.2.44 Alpha (`versionCode 51`, `versionName 0.2.45-beta`). The internal distributable build
task remains `assembleAlpha`; the installed version and public artifact are Beta.

## Change

- Recognize narrowly defined conversational endings as well as formal endings.
- Recognize the explanatory ending `-거든요` and past-tense contractions before `-구나`;
  keep conditional `-거든`, similar nouns and reported-speech continuations pending.
- Release the earliest stable complete sentence with two stable words of right context, while
  retaining the unfinished tail and preserving translation order and preceding context.
- Reduce the measured-pause requirement for a repeated complete sentence from 1,200 to 800 ms.
  This excludes the audio detector's hangover, ASR processing, translation and TTS time.
- Let repeated complete text use the short pause when low-volume speech never activates the
  advisory energy detector. A single partial or a dependent phrase cannot use this shortcut.
- Protect late negative auxiliaries, numeral/counter combinations, quoted speech and selected
  postposed modifiers. Ambiguous new `-지` endings require right context instead of silence alone.
  A short modifier such as “아주 많이” stays with its predicate after a confirmed pause.
- Keep a completed finite sentence moving when the next clause begins a negative explanation,
  while retaining dependent `-지` / `-고` auxiliary constructions together.
- Align revisions across an already committed boundary when number formatting changes
  (`11번` / `열 한 번`). A unique matching ending and three pending words prevent replay of
  the old predicate without deleting a genuinely repeated question in the new continuation.
- Merge the duplicated home entries into **실시간 방송·통역** without changing saved audio or
  standalone settings. Voice notes use a content-first normal view, overflow details and
  separate recording/playback controls. Language/export tools have their own view so they
  cannot push sentence actions below a long settings form. See [mobile design criteria](CONTENT_FIRST_MOBILE_DESIGN.md).

No audio is cut into fixed-duration chunks. No model weights are trained. The case review changes
the offline boundary rules and regression corpus. Translation engines and the optional Gemma
review queue are unchanged, so their own processing time is not eliminated by this update.

## Validation scope

The selected real input is [침착맨 — 의식이 흘러가는 대로 푸는 썰](https://www.youtube.com/watch?v=-dK9vHf0Y_0),
original video time 00:30–02:46 (136 seconds). The same extracted 16 kHz mono S16 PCM is used before
and after the change. A second condition scales amplitude by 0.125 (−18.06 dB); this is a controlled
digital attenuation, not a real whispered microphone recording. Audio is not redistributed.

The public 0.2.44 APK completed both baseline runs: 26 and 24 final translation units. In the
original-volume run, a selected conversational ending was already present in an accepted ASR
hypothesis long before its containing unit was finalized. This is an ASR-hypothesis-to-final
measurement, not a ground-truth acoustic sentence-end or listener first-audio measurement.

Candidate artifact hashes, executed test results and before/after observations are recorded in
the final validation entry in [TEST_REPORT.md](TEST_REPORT.md).

## Remaining limits

These tests do not establish an eight-hour soak, physical Galaxy latency, arbitrary speaker
turn detection, or an overall semantic-error rate. The source lacks an independently verified
reference transcript; ASR recognition errors and repeated hypotheses remain visible in the
benchmark and are not relabeled as correct speech. The known translation issues documented for
[0.2.44](MISTRANSLATION_REVIEW_2026_09_24.md) are not claimed fixed here. Ambiguous endings and
unclosed quotes may wait longer to protect meaning.
