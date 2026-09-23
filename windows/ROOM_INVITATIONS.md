# Room invitations — local preview contract

Version 0.3.1 adds a **room locator link**, not a bearer admission token.

The 0.4.0 review candidate also exposes `/listen/<room-id>` for authenticated
read-only text participation. It prefills the room after login, never auto-joins,
and does not bypass guest scope or account revocation. A path/query room conflict,
invalid encoding, extra path segment, or invalid room identifier is rejected.
The read-only session cannot send chat, media, or subtitles. It currently receives
original-text chat, not audio/translated captions. Anonymous and LAN admission
remain unavailable; the shared link is not a scoped bearer token.

## Participant flow

An authenticated user can choose a valid room ID before joining and press
`이 회의실에 초대`, or open `초대` during a meeting. The modal shows the room,
read-only URL, copy action, account requirement and explicit same-PC-only limit.
The URL is rebuilt from the current trusted loopback origin and a room parameter;
existing queries, fragments, credentials, account IDs and session tokens are not copied.

Opening `/?room=<room-id>` shows an invitation notice and the ordinary login flow.
After authentication, the room is prefilled, but **no automatic join occurs**.
The recipient reviews language preferences and explicitly joins through the
existing authenticated WebSocket/room pipeline. The link does not issue an account,
create an anonymous guest, alter account role/expiry, or authorize forbidden rooms.

## Administrator and guest flow

Only ADMIN sees the invitation modal's account-management shortcut. It opens the
existing account administration UI; credentials are never embedded in the URL.
Guests still require an issued account, future expiration and one assigned room.
A conflicting room locator displays a clear warning, retains the guest's assigned
room and does not join the invited room. The server independently enforces scope.
Account disable/expiry/revocation invalidates the existing session and room connection.
Invitation content is cleared when its modal, meeting or authenticated session ends.

Invalid/duplicate/overlong room parameters are rejected and are never rendered as HTML.
Clipboard failure selects the read-only text and offers Ctrl+C instead of reporting
false success. An asynchronous copy result cannot restore content after close/logout.
The modal supports Escape, focus restoration and narrow screens.

## Scope and exclusions

This release remains loopback-only. Sharing the URL with a different computer or
phone will not connect it to this host; the UI says so prominently. No email, SMS,
third-party message, public endpoint, LAN binding or firewall change is performed.
There is no per-link expiration or revocation because the link itself conveys no
permission. Account expiry/revocation remains authoritative. Future internet/LAN
invitations require TLS, routable host configuration, room admission tokens and
media/network acceptance gates. This local locator is not a claim those exist.

## Verification

`tests/room-invitation.test.cjs`: pure identifier/parser/builder/scope boundaries.
`tests/invitation-smoke.cjs`: real local host, isolated account/browser contexts,
recipient login/join, guest conflict, administrator account shortcut/revocation,
operator capability display, keyboard/narrow UI and clipboard UI contracts.
Clipboard API success/failure is deliberately simulated within that browser so
tests do not read or overwrite the operator's desktop clipboard. Report this
separately from actual room/authentication transport and from Windows VM/UAT.
