# Room and private chat — development contract

This is part of the authenticated, loopback-only control preview, not a public
messaging service. Local account authentication and room admission are implemented;
TLS, LAN/WAN acceptance, and public deployment hardening remain incomplete. Do not
expose it through a reverse proxy or change the loopback bind restriction.

## Account and session boundary

The Windows user creates the workspace's first administrator in the native
first-run setup before the host starts. There is no default administrator,
anonymous guest, public signup, or HTTP bootstrap. ADMIN accounts manage users
and guests through authenticated account APIs and the browser management UI.
USER accounts can join rooms. GUEST accounts require an expiration and exactly
one allowed room; admission checks both server-side. The UI suggests a 24-hour
guest duration, but does not decide authorization.

The browser first checks `/api/v1/auth/session`, then uses the login endpoint to
obtain an opaque HttpOnly cookie. HTTP account mutations require JSON and exact
same-origin checks; administrator routes also check the current ADMIN role.
WebSocket upgrades require a valid session and authorized room. The host ignores
client display-name claims and uses the authenticated account's display name.

Logout revokes the browser's token; other independent sessions of the same account
remain valid. Successful login replaces/revokes an existing browser-cookie token,
including a prior account's connected WebSockets. Failed login preserves the prior
session. Account changes and password reset/change invalidate affected sessions.
Expired or revoked connected sessions close with policy code `1008`; the UI discards
private room state and rechecks the server session. Idle/absolute expiry and guest
expiry are enforced by the server; outbound messages and passive checks do not
extend idle time.

Cross-tab `session-changed` broadcasts contain only an invalidation hint, never
credentials, cookies, tokens, or account identities. The receiving UI clears old
private state and queries the server rather than trusting a broadcast identity.

## Recipient identity

The server issues each joined participant a `presenceId`. A participant ID or
display name alone is not a private-message address: the same participant ID
can be used by a later connection. The roster and participant events include
both IDs plus server-derived `accountId`, `username`, and `displayName`. Account
identity and a connection generation serve different purposes. The UI fixes a
private selection to that exact presence, and requires
explicit selection again after departure/rejoin.

Recipient labels now include `username` and a connection suffix, and participant
search/private-chat shortcuts use the same presence-bound recipient selection.
Changing an existing draft via the shortcut requires confirmation. The shortened
connection suffix is a visual aid only; authorization still uses the full IDs.

```json
{"type":"CHAT_SEND","text":"Hello everyone","recipientId":null}
```

```json
{
  "type":"CHAT_SEND",
  "text":"Hello privately",
  "recipientId":"participant-from-current-roster",
  "recipientPresenceId":"server-issued-presence-from-current-roster"
}
```

The server ignores client claims about sender identity, language, time, scope
or display name. These come from the joined connection and server state.
The server emits `CHAT_MESSAGE` with `messageId`, `roomId`, `sentAt`,
`senderId`, `senderAccountId`, `senderUsername`, `senderPresenceId`,
`senderDisplayName`, `scope`, `recipientId`,
`recipientPresenceId`, `recipientDisplayName`, `recipientUsername`, `originalText`,
`sourceLanguage`, and `translationStatus: "unavailable"`.

A room message goes only to current sockets in that room. A private message
goes only to the matching recipient presence and its sender. A stale or
missing private recipient causes `ERROR` with `code: "INVALID_CHAT"`; it must
never become a room message. A missing presence on a private request is an
error, not backward-compatible best effort.

## Input, delivery and retention

- Nonblank text, up to 2,000 UTF-16 code units. Control characters other than
  tab/newline/carriage return are rejected. Content is rendered as text nodes.
- Sender echo confirms server acceptance/queueing, **not delivery, reading or
  translation**. There is no recipient receipt, durable delivery, replay or
  client-message-ID deduplication yet. Uncertain delivery must not auto-retry.
- Each socket has a bounded outgoing queue and timeout. Slow or failed sockets
  do not hold up all other recipients.
- The current server does not persist chat. The browser holds bounded transient
  history and clears it on leaving; retention/export requires a separate policy.
- Browser WebSocket requests must have a loopback Host and a matching Origin
  on the actual serving port. Missing, null or foreign origins are rejected.
  These origin checks complement cookie/account authorization; an Origin value
  or a client participant ID alone is never an authentication credential.

## Translation extension boundary

Keep the immutable original text and its recipient set. Translate directly
from the original into each authorized recipient's display language. Any
publication-language text must be distinguished from original and translated
display text. Private content, translation caches, captions, TTS tracks and
diagnostics must all retain the same room and presence authorization boundary.
Do not send private synthesis into a public meeting audio track.

Original/translated text, pending/failed/unavailable translation, cancellation,
language changes, terminology and late-result handling need explicit protocol
fields and tests before chat translation is enabled. Current language labels
are user-selected metadata, not automatic language detection.
