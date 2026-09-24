# Content-first mobile design — 0.2.45 Beta

These are implementation and acceptance criteria, not a claim of completed usability research.
Executed device checks and screenshots belong in `TEST_REPORT.md`.

## Product decisions

- One **실시간 방송·통역** entry opens the existing broadcasting workspace. Opening it must
  preserve the operator's original-audio/translation and standalone/network choices.
- Voice notes prioritize reading the live or saved transcript. The compact header contains back,
  the screen title and an accessible overflow button. Start/stop and play/pause remain reachable
  while the transcript scrolls.
- The normal transcript uses paragraphs rather than a metadata card for each sentence. Time,
  speaker, translation-waiting state and sentence editing belong to **상세보기** in overflow.
  Language, exports and backup live in **노트 도구**, reachable directly from overflow;
  they must not push the detailed sentence actions below a long settings form.
- Failures, microphone permission problems and unsaved-work warnings remain visible. Hiding
  routine technical metadata must never disguise a stopped recognizer as successful dictation.
- Reading earlier text suspends automatic following. A visible return-to-live action restores it.
  Rendering uses lazy items, including earlier live lines, instead of only the last eight lines.
- Default language uses existing settings. Current local live recognition requires a specific
  source language; it must not be advertised as automatic detection. The existing supported
  post-recording detection path remains available in options.

## Scenario-based acceptance

| Scenario | Required behavior |
| --- | --- |
| First-time note user | Start dictation without navigating a wall of settings; real text appears before stop. |
| Long-session reader | Earlier paragraphs remain reachable; new text never steals a deliberately moved scroll position. |
| One-handed operator | Stop remains on screen while reading the oldest or newest paragraph. |
| Reader using large type | At 360 dp width and 2× font scale, primary controls and overflow remain reachable without horizontal clipping. |
| Short/landscape viewport | Text has its own scroll area; controls do not consume the whole viewport. |
| User correcting a note | Overflow → details exposes speaker/edit/listen actions; saving updates the actual repository. |
| User exporting a note | Overflow → note tools exposes language/export operations; sentence view and Back return to the content. |
| Recognition failure | Recording/transcription state and recovery message remain truthful; saved material is preserved. |
| Existing broadcast operator | Unified menu preserves saved mode and independent input/broadcast stop controls. |

## Reference principles

- [Samsung Galaxy voice-recorder instructions](https://www.samsung.com/ie/support/mobile-devices/how-to-record-play-back-and-share-voice-recordings-on-your-samsung-galaxy-phone/)
  (updated 2026-03-16): record, stop, save and select a recording to play. The core recording and
  listening journey stays direct; file-management actions are contextual. MCastTalk uses this
  familiar order while keeping live transcription visible during recording.
- [Samsung One UI basic layout](https://developer.samsung.com/one-ui/layout/basic.html): separate
  reading and interaction areas and group related content. MCastTalk applies this to a transcript
  viewport with nearby essential controls, without reserving an oversized decorative header.
- [Apple menus](https://developer.apple.com/design/human-interface-guidelines/menus) and
  [context menus](https://developer.apple.com/design/human-interface-guidelines/context-menus):
  group relevant commands and avoid deep hidden navigation. The visible overflow entry and
  direct primary controls make the extra actions discoverable.
- [NAVER CLOVA Note recording guide](https://help.naver.com/service/24269/contents/12819?lang=ko&osType=PC):
  recording, note creation and later reading form a connected user journey. MCastTalk additionally
  requires its own live transcript to appear before recording stops; the reference is not evidence
  that the two products use the same live recognition behavior.
- [Android recognition contract](https://developer.android.com/reference/android/speech/RecognizerIntent#EXTRA_ENABLE_LANGUAGE_DETECTION):
  language detection/switching depends on recognizer implementation. A UI label cannot substitute
  for supported and tested recognition behavior.

No external app's branding, screenshots or proprietary assets are copied. Existing Material 3
theme tokens, minimum 48 dp touch targets, explicit accessibility labels and system font scaling
remain the implementation basis. These scenario personas are review tools, not real user-study
participants or measured satisfaction scores.

## Observed design correction

The first signed Beta UI candidate passed six of seven Android UI/repository tests but failed
the 320 dp viewport path to **여기서 듣기**. The settings/export form preceded the sentence
actions and exhausted the bounded scroll search. The failure is retained as evidence, not
relabeled as a pass. Separating note tools from sentence detail is the resulting product
correction. The rerun passed all seven selected UI/repository tests, including the same
accessibility and actual edit/save assertions at both viewport configurations. Antigravity
executed the rerun; Codex independently checked its installed-APK receipt and screenshots.
The tested UI candidate hash and the final APK's separate journeys are recorded in
`TEST_REPORT.md`; the two artifacts are not conflated.
