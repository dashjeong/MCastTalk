# MCastTalk meeting UX and media architecture

Status: working authenticated control/chat UI; media architecture proposal. Updated 2026-09-19.

## Product contract

The room follows the familiar prejoin → meeting stage → side panel → bottom control bar flow. The primary task is meeting participation. Language settings supplement that flow instead of replacing it with an administration screen. The current stage uses real room membership, initials, and language metadata; it does not simulate a camera feed or active audio.

Current working scope:

- Session check and account login before prejoin. The server account supplies the readonly participant display name; prejoin configures room ID, input/publish/listen/caption languages, and preferred receive-audio mode.
- Personal password change and logout. Administrators can create USER/GUEST/ADMIN accounts, list accounts, change another account's role/activation, and reset passwords.
- Publish-language default resolves to the selected input language when sent to the existing protocol.
- Actual WebSocket membership, participant language updates, leave, connection feedback, and participant-local elapsed time.
- Gallery layout and a large-participant layout. The latter is explicitly manual until active-speaker media events exist.
- Participant, language, chat, and activity side panels. Opening moves keyboard focus to the panel heading; Escape returns it to the originating button.
- Plain-text room chat and private chat to a current participant. Message language and unavailable translation are explicit.
- Search participants by display name or username; start private chat from a participant row. Recipient labels include username and connection suffix; changing a nonempty draft through the shortcut requires confirmation.
- ADMIN-only operator dashboard with aggregate room/connections, actual JVM heap/processors and workspace volume free space. Capability declarations remain separate from measurements, with inference and GPU calibration explicitly unverified.
- Disabled microphone, camera, screen-sharing, and translated-caption controls, with an explanation visible in the stage and prejoin.
- Local assets only. There are no CDN fonts, external scripts, synthetic participants, automatic device permission requests, or browser storage of transcripts.

This is a development preview, not a production or defect-free release. An avatar tile indicates room membership only; server acceptance of a chat is not a recipient read receipt.

## Current interaction details

| Situation | Behavior |
| --- | --- |
| Initial load | GET auth/session determines the view. No room controls are exposed before an authenticated account is returned. The first administrator is created in Windows initial setup, never by an HTTP signup/bootstrap route. |
| Guest login | Display and lock the account's allowed room; show the expiration in the local timezone. Server authorization remains mandatory even if client controls are modified. |
| Account mutation | Send same-origin JSON with the server HttpOnly cookie. Clear submitted password inputs immediately; do not persist credentials/tokens in localStorage, sessionStorage, logs, or generated artifacts. |
| Session revoked/expired | Close the WebSocket, invalidate pending async account responses, clear room roster/private chat/timers immediately, close account dialogs, and require login again. Check session after an unexpected WebSocket close. |
| Own password change/reset | Require a new password of 8–128 characters and matching confirmation for a personal change. Reauthentication follows successful change; block a new login while logout/password mutation response cookies are still pending. |
| Admin account management | ADMIN-only entry and server authorization. Guest creation requires a room and future expiration (default local UI suggestion: 24 hours); converting to a non-guest clears guest restrictions. Local controls prevent changing the current administrator's role/activation, and last-admin failures have explanatory text. |
| Joining | Lock room/name/preferences until the server acknowledges; return to editable prejoin on failure; a bounded timeout prevents permanent loading. |
| Language change | Send current preferences and show saving; only an echo matching all requested fields confirms success. |
| Room chat | Default recipient is Everyone. A visible recipient line remains next to the composer. |
| Private chat | Only current other participants are selectable. Bind the choice to participant ID plus the server-generated presence ID for that exact joined connection. Sender and recipient see a private badge and destination identity. |
| Private recipient leaves or changes connection | Preserve the original choice as an unavailable option and block sending. A later join that reuses the participant ID cannot reactivate it; the user must explicitly select the new connection. Never silently convert a private draft into a public message. |
| Message composition | Enter sends; Shift+Enter inserts a newline; IME composition does not trigger sending. The limit is 2,000 UTF-16 code units, matching the server's JVM string limit. |
| Message confirmation | Wait for sender echo matching the current sender presence, recipient ID, recipient presence, and text before clearing a matching draft. Timeout warns about uncertain delivery and possible duplication; it never auto-retries. |
| Incoming messages | Append safe text nodes; keep the reader's scroll position unless already at the bottom; show unread count outside chat. Announce arrival without reading a private message aloud. |
| Connection lost | Close the meeting state, disable chat, clear transient messages, return to prejoin, and announce connection loss. No automatic rejoin or replay. |
| Page lifetime | Retain at most 300 chat messages and 50 activity entries in memory. Leaving clears chat. Reloading does not retrieve old messages. |
| Keyboard | Native form and button behavior, visible focus, skip link, Alt+P participants, Alt+L languages, Escape closes a panel. No destructive leave shortcut. |
| Narrow viewport | Stage and side panel stack vertically; controls wrap without relying on hover. The layout targets 320 CSS pixels and above. |

Chat protocol is additive to control protocol v1:

```text
PARTICIPANT { participantId, presenceId: serverGeneratedUuid, ... }
CHAT_SEND {
  text, recipientId: null | participantId,
  recipientPresenceId: null | expectedPresenceId
}
CHAT_MESSAGE {
  roomId, messageId, senderId, senderPresenceId, senderDisplayName, sentAt,
  scope: room | private, recipientId, recipientPresenceId, recipientDisplayName,
  originalText, sourceLanguage, translationStatus: unavailable
}
ERROR { code: INVALID_CHAT, message }
```

The server authorizes recipients and determines sender identity and presence from the joined connection. Each successful join receives a new server-generated presence ID; a client-supplied presence value cannot select or reuse a generation. Private sends require the expected recipient presence and reject missing, invalid, or stale values with INVALID_CHAT and no delivery. Frontend filtering compares both identity and generation for sender echoes and incoming private messages. This is defense in depth, not access control. No text from a participant or protocol message is inserted as HTML.

## Media architecture — prototype retained, release gates closed (2026-09-22)

The handoff's raw binary relay and subtitle/mixer UI are prototypes, not a verified
media service. Live binary input is rejected with `MEDIA_NOT_READY`, and all
client-authored `SUBTITLE_CHUNK` input is rejected with `INFERENCE_NOT_READY`.
Being an administrator does not establish inference provenance.

The listener endpoint and direct URL remain useful as authenticated read-only
text participation. They require the normal account and guest room authorization,
use server account identity, and join the revocation/expiry registry. No automatic
join or anonymous admission is performed; an invitation is only a room locator.
Read-only participation is not enforced as an account-wide role: a normal USER may
explicitly rejoin the ordinary authorized room as a participant.

Before media can be enabled, define and test bounded frames/rate/queue age,
sample-format negotiation and resampling, speaker/utterance/sequence identifiers,
per-stream playback scheduling, reconnect/discard rules, device permission races,
and authenticated worker-produced subtitles. TTS ducking must occur only while
verified translated audio is playing; preferences must control actual playback.
The retained code alone proves none of these complete end-to-end behaviors.

`MULTILINGUAL`, `VOICE`, `NOTES`, and `FILES` are planned service identifiers.
Additional tabs are unavailable until they have their own functional workflows,
storage/privacy contracts and acceptance evidence. Recording, local note storage,
file transcription/export and mobile parity are not implemented Windows features.
There is no measured 1-second join or inference latency claim.

Browser capture needs an appropriate secure context. Add HTTPS/WSS certificate onboarding and clear trust instructions before LAN camera/microphone enablement. Define authenticated room admission, scoped capabilities, host ownership, waiting room, lock, eject, and end-for-all semantics before moderation controls appear. Show actual microphone/camera state, permission failures, missing devices, track-ended events, and device changes; a successful permission prompt alone must not imply published media.

The SFU should support bandwidth adaptation and per-subscriber quality. Validate simulcast or scalable video coding on the chosen runtime and browsers; do not assume all hardware can encode every layer. Subscribe only to visible video tiles where appropriate, preserve pinned and shared content, and prioritize speech over camera resolution under congestion. Active-speaker selection needs measured audio activity, hysteresis, and user pinning that overrides automatic switching.

Screen share is a distinct track with a persistent presenter indicator and a visible stop button. The browser's native capture picker must remain user initiated. Camera and shared content can coexist. A share ending or presenter leaving restores the previous layout without losing chat or language preferences.

## Interpretation and caption synchronization

Each utterance needs a stable ID, speaker/room identity, monotonic capture timestamps, source-language tag, partial/final revision number, and cancellation state. Translations and TTS jobs reference that utterance and target language. Render source and translation as separate revisions; do not append every partial result as a new message. Late results from a prior room, removed participant, changed language subscription, or canceled utterance must be discarded.

Group inference work by distinct target language and model/configuration rather than by recipient. Only authorized participants receive each utterance. A private chat's translation must remain confined to its sender and recipient. Cache keys must include room/privacy scope, original text or utterance identity, target language, model version, and glossary version. Keep private content out of general event logs and telemetry.

Provide source-audio, translated-audio, mixed, and captions-only modes with explicit mixing/ducking rules. Include stop/cancel, queue age limits, and overload behavior to prevent stale translated speech accumulating behind the conversation. Show a degraded-mode indicator if the selected hardware cannot maintain the chosen quality target; report measured latency and load instead of pretending every model is real time.

Caption layout must preserve readable line length, adjustable font size, contrast, speaker identity, language, and a stable visual region. A screen reader may opt into final captions; do not repeatedly announce partial tokens. Retention/recording/transcript export require explicit meeting policy and participant-visible state before implementation.

## Acceptance plan

| Area | Required evidence |
| --- | --- |
| Account UI | First-launch setup prerequisite; initial session/login view; invalid credentials; readonly server display name; ADMIN-only account controls; create USER/GUEST/ADMIN; guest future expiration and room restriction; activation/role changes; last-admin/self guard; personal password mismatch/change; admin reset; revoked-session cleanup; late async response after logout; no credentials in browser storage or logs. |
| Control and chat now | Three participants: join, change languages, room message to all, private A→B with no event/body at C, departed recipient, reused participant ID with a new presence, mandatory explicit reselection, stale-generation send rejection, stale sender/recipient echo rejection, leave, disconnected host, rejoin, duplicate sender echo, malformed message, and HTML/script text rendered literally. |
| UI now | Desktop and 320/390/768-pixel widths; 200% zoom; long names/room IDs; 20+ participants; long multiline and CJK text; keyboard-only prejoin, panels, chat, focus restoration, and leave. |
| Accessibility | Real screen-reader testing on Windows, heading/landmark review, contrast measurement, high-contrast mode, reduced motion, IME composition, and visible disabled-feature explanations. |
| Media first | Camera/mic permission granted/denied/revoked; absent/busy device; USB device removal; mute/track-stop consistency; echo cancellation; device selection; original audio before STT integration. |
| Meeting media | Two and multi-party video, pinning/active speaker, screen share start/stop, presenter exit, bandwidth drop, packet loss, reconnect, stale track cleanup, background tab and sleep/wake. |
| Network | Offline LAN without DNS/internet assumptions, HTTPS trust onboarding, NAT/TURN relay, hostile origin rejection, unauthorized room access, expired credential, and cross-room privacy isolation. |
| Interpretation | Korean/English/Japanese/Chinese bidirectional and N:M tests; partial/final ordering; numbers/names/terminology; interruption; language-switch cancellation; untranslated fallback; privacy-preserving chat translation. |
| Performance | p50/p95/p99 end-to-end timing measured at capture, STT, translation, TTS and playback; sustained thermal tests on Radeon 560X; bounded memory and queue growth; multi-hour session; CPU/GPU fallback with visible capability changes. |
| Installer/offline | Clean Windows machine, one installer file, chosen data folder, Unicode/space paths, no-network first launch with bundled artifacts, missing/corrupt model pack, restart, upgrade and rollback. |

Release evidence must distinguish unit tests, protocol integration tests, actual browser interactions, real audio/video devices, and human multilingual listening tests. Passing syntax or control tests cannot certify media quality or a defect-free service.

## Verification in this change

- JavaScript syntax checked with the bundled Node runtime.
- Cross-file ID references and duplicate HTML IDs checked locally.
- Actual browser and device checks remain separate evidence to record after execution; they are not implied by this document.

The operator diagnostics endpoint is `/api/v1/admin/diagnostics`, schema v1.
It uses the same Host/Origin/account checks as account management, but passive
reads do not renew session idle time. It does not return individual room/account
identities, transcripts, credentials, filesystem paths, environment variables or
raw logs. The UI refreshes only on demand, drops stale responses, clears data on
close/logout and never treats a GPU's presence as calibrated inference support.

Antigravity review request: inspect privacy-boundary behavior, keyboard/focus flow, missing meeting-state transitions, and the media acceptance matrix. Record an independent result with exact artifact/version and reproducible steps; incorporate fixes only after resolving conflicting requirements with the shared plan.
