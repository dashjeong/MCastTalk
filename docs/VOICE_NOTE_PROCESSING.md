# Voice note review and translation

Saved notes expose a separate workbench without changing the 0.2.48 input-source,
input-control or broadcast-settings components. The streaming menu is **On-통**;
the native live relay is **On-통 Live(AI 통역)**. Active input has fixed pause,
resume and stop controls above the scrolling workspace, wired to input actions
only. Broadcast pause/stop remain separate. Standalone operation remains in the
existing run-mode selector.

## Source preservation and consent

`originalTranscript` is the recognition baseline. Existing `original` is the
editable working copy, retained for legacy callers and exports. Old records
without the new field use their stored text as the baseline; lost pre-edit text
cannot be reconstructed. Explicit repeat transcription remains a confirmed
operation that creates a new baseline from the recording.

Proofreading returns proposals only. Bounded code-point alignment turns the
AI's corrected sentence into separate selectable spans, including insertions and
deletions. Compose uses UTF-16 offsets for highlighting. The source hash and
range are rechecked at apply time; stale, duplicated or overlapping selections
cannot write. Approval changes only the working copy and invalidates working-copy
translations. Raw-source translations retain their source hash. Undo is limited
to ten corrections in the open-note session and preserves subsequently created
language results. Timings, speakers and recorded audio remain intact.

The local Gemma request uses a fixed request-local proofreading mode over the
existing worker protocol. It permits same-language input only in that mode and
does not alter normal translation prompts. It supports the existing Gemma
languages (ko/en/ja/zh/es/ar). Online proofreading uses text OpenAI, Gemini or
compatible APIs through existing credential, consent, usage, cancellation and
meaning-protection checks, without shadow comparison. AI suggestions can still
be incorrect; users compare the recording and choose each accepted change.

## Translation behavior

- Target choices: Korean, English, Japanese, Simplified Chinese, Russian,
  Vietnamese, Taiwan Traditional Chinese, Spanish, French and German.
  Taiwan Chinese is distinct from Taiwanese Hokkien; Hokkien is not implemented.
- Explicit offline ML Kit, offline ML Kit + Gemma review, or configured online
  text API. Offline selections do not inherit an online global provider or
  background cloud review. Missing models and unsupported Gemma language pairs
  fail visibly; exact AI selection does not silently fall back.
- Choose raw transcript or corrected working copy. Stored variants record the
  source hash, source choice, model label and manual edit provenance.
- Switch languages to restore a current cached result; stale results remain in
  storage with their provenance but are not presented as current translations.
- Resume unfinished segments with bounded durable checkpoints. Language/source
  replacement and confirmed full retranslation publish only after all segments
  finish. Cancellation, engine failure or commit failure keep the previous result.
- AI register choices: automatic, formal or conversational. Plain ML Kit does
  not pretend to offer a register feature.
- Copy/share raw, corrected or translated text; listen to original audio or
  read translations with installed offline TTS voices. Stop TTS on background,
  unavailable workspace, processing or exit.

Additive JSON metadata is bounded and included in note storage, portable backup,
and JSON text export. Other text/subtitle exports keep their existing selected
working-copy and active-language behavior. No API key is stored in note metadata.

## Benchmarks and validation

The choices were informed by official product guidance:

- [Google Translate offline language preparation](https://support.google.com/translate/answer/6142473?hl=ko)
- [Google Translate text translation](https://support.google.com/translate/answer/6142478?hl=ko)
- [DeepL Write selectable phrasing and undo](https://support.deepl.com/hc/en-us/articles/9710730337820-Customize-your-text-with-DeepL-Write)
- [DeepL translation formality](https://support.deepl.com/hc/en-us/articles/4406432804498-Use-the-formality-feature)

Regression tests cover accepted subsets, unchanged raw text, repeated phrases,
emoji boundaries, stale selections, cached languages, cancellation/replacement
transactions, raw/working source choice, additive metadata and isolated prompts.
Device acceptance still needs actual Gemma inference, text API execution,
installed model/voice behavior, highlighting/scrolling, accessibility and
recording-versus-playback checks. This source change does not publish an APK or
claim that existing live-broadcast acceptance issues have been resolved.
