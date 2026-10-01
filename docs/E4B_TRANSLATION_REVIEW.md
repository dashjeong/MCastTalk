# E4B translation and broadcast-session context review

This work compares actual native E4B output with the user's Korean source and an
independently generated Gemini reference. It does not train model weights. E2B
selection and its existing prompt remain available. Source audio segmentation,
PCM pacing and active playback queues are unchanged.

## Scope and reproducibility

The user supplied 22 Korean sentences about the first-investment IR program and
selected English, Japanese and Simplified Chinese: 66 requests per article run.
`ArticleTranslationComparisonDeviceTest` reads an explicitly installed local
`benchmark/ir-corpus.json`, selects the verified E4B model, performs real native
inference and writes every result and elapsed time. No Gemini answer is sent to
E4B. Successful execution alone is not a semantic pass. These text-input tests
do not establish ASR segmentation, TTS naturalness or physical-device latency.

The reference was generated in Antigravity, whose UI identified the model as
Gemini 3.8 Flash High, before the E4B results were provided. Reference and raw
E4B results are preserved separately. Korean source meaning is authoritative:
Gemini's `more than 300`/`300余` for an inclusive lower bound, for example, must
also be reviewed. AI-assisted judgments are not bilingual human certification
or a general population error rate.

## Corrections and safeguards

- E4B receives the current source unchanged, with optional, quoted source-only
  lexical evidence for inclusive/exclusive numeric bounds and fundraising terms.
  It must preserve surrounding negations and conditions.
  Numeric hints use words, without comparator symbols: an actual Chinese
  candidate copied a parenthesized comparator into its output, so that candidate
  was rejected and the same corpus was run again after removing the symbols.
- The Korean Ministry of SMEs and Startups name is resolved for the requested
  language using government usage, rather than substituted with a different
  country's agency. Source references: [MSS English](https://www.mss.go.kr/site/eng/ex/bbs/View.do?bcIdx=1061813&cbIdx=244),
  [Korea.net Japanese](https://japanese.korea.net/NewsFocus/Business/view?articleId=289065),
  [Korea.net Chinese](https://chinese.korea.net/NewsFocus/Business/view?articleId=289066).
- Target-language instructions are repeated after the quoted input. A narrow
  guard rejects a full Latin-script sentence returned for a Japanese/Chinese
  Korean-sentence request. This guard is not general language identification.
  Existing provider failure isolation/fallback remains responsible for recovery.
- E4B explicitly requests a direct answer without a thinking channel using the
  existing SDK's [ThinkingConfig API](https://raw.githubusercontent.com/google-ai-edge/LiteRT-LM/v0.16.1/kotlin/java/com/google/ai/edge/litertlm/Config.kt).
  Whether this changes latency or quality is determined by the recorded runs,
  not by assuming a speedup from an API setting.
- Additional runtime diagnostics contain first-visible-output timing, total
  decode timing and auxiliary character count only; no source, translation or
  conversation memory is logged by this change.
- Conditional source hints distinguish reported events from announced plans and
  preserve modifier scope and ownership in matching clauses. They do not force
  all reported events into the past or replace the current source. Their actual
  effectiveness and remaining failures must be read in the comparison results.

## Candidate history

The initial 66-request native baseline completed 65 requests; IR16-English timed
out after 10,007 ms. Candidate 1 was rejected: five requests failed, and 14
Japanese/Chinese requests silently returned English. Repeating the requested
target after the input and adding the narrow script guard addressed that observed
failure class in subsequent complete runs.

Candidate 2 completed 66/66, but the reviewer identified semantic and formatting
regressions. Candidate 3 also completed 66/66 (280.470 seconds for the test), but
still copied a comparator into IR01-Chinese and was not handed off. It corrected
the English beneficiary error introduced by candidate 2 in IR21, while IR02's
event tense and IR03's modifier scope remained weak. Failed and rejected runs
are retained; successful execution is not a translation-quality verdict.

## Per-broadcast memory

Each translation pipeline owns a fresh in-memory store. Successful final
source/translation pairs are separated by source and target language, with at
most two pairs, 400 raw characters and 600 encoded characters. Individual texts
over 200 characters are omitted whole. No sentence is cut to fit this memory.
Stop/cancellation closes the store and removes retained references; subsequent
record attempts are ignored. A new pipeline starts empty. This is reference
removal, not a claim of secure erasure from JVM memory.

Only local E4B consumes these hints. They are not written to persistent storage
or sent to a cloud API. Current source takes precedence over previous model
output, which can itself be wrong. The feature performs new translation for
every utterance; it does not replay an earlier translation for identical text.
Bounded recent context does not promise indefinite terminology retention.
The existing prior-source context and optional bilingual hint together have a
400-character request budget. If the complete hint does not fit, it is omitted;
the authoritative prior-source context remains intact. This avoids doubling
prefill cost or silently cutting a pair inside a sentence. Direct E4B translation
consumes this memory; the optional draft-review path keeps its existing context.

## Latency interpretation

Initial model loading and per-sentence inference are separate costs. The existing
pipeline overlaps translation work with speech output; this change does not
claim gap-free simultaneous interpretation. Emulator translation durations are
not the Galaxy S23 prepared-model first-PCM p95 gate of 2,000 ms. Physical-device
latency, vendor voice downloads, long-duration stability and voice naturalness
remain separate validation requirements. See [test evidence](TEST_REPORT.md)
for the actual tested artifacts and results.
