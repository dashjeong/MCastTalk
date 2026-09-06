# Security and Privacy Policy

## 1. Supported Versions

Security review targets the following candidate. This is not a service-level
agreement or certification that all vulnerabilities have been removed:

| Version | Supported |
|---|---|
| `0.2.38-alpha` | Under review; experimental |
| `< 0.2.38` | :x: |

---

## 2. On-Device Privacy & Offline Architecture

MCastTalk is designed with offline-first and privacy-focused principles:

1. **Local Audio Processing**:
   - Microphone audio is processed in local memory for real-time feature extraction, STT, and broadcast.
   - MCastTalk's intended inference path uses prepared on-device engines. Model downloads and third-party Android/Google SDK services can make network requests; offline inference is not enforced network isolation.
2. **Local Wi-Fi / Hotspot Distribution**:
   - The web streaming server runs on the host Android device (`Ktor CIO`).
   - Listeners connect directly via the host's local Wi-Fi or mobile hotspot.
3. **Local Setting & Glossary Storage**:
   - User correction data and glossaries are stored locally on the device (SQLite/files) and are not synced to remote cloud storage.

### Listener access and transport

- Broadcast access defaults to **public** within the network that can reach the
  server. Operators can select supported PIN/QR access controls.
- HTTP/WebSocket listener traffic is not end-to-end encrypted. A PIN or QR token
  is access control, not encryption. Use a trusted, protected local network; do not
  forward broadcast ports to the internet.
- Remote web microphone capture requires browser permission and a secure context.
  Certificate trust is a separate decision; a PIN does not waive these requirements.

### Stored content and diagnostics

- Finalized source/translation transcripts are stored in app-private SQLite,
  subject to 30-day / 5,000-line retention limits. Operators can view and delete
  entries. Corrections, glossaries and settings also persist locally.
- Authorized listeners receive audio/text and may retain their own copies.
  Consider consent before broadcasting or sharing content.
- Processing uses audio buffers and may use temporary synthesis files. Offline
  does not imply no disk I/O or immediate erasure of all artifacts.
- The bounded app diagnostic writer is designed to exclude raw audio, transcript
  text and credentials. Android logcat, third-party SDK logs, screenshots and
  exported reports are not guaranteed to be sanitized.
- Review exports before sharing. Never attach recordings, user conversations,
  tokens, PINs or signing keys to public issues.
- Updating the app does not intentionally clear models or settings. Android's
  cache-reset advice alone is not evidence of cache corruption.

---

## 3. Reporting a Vulnerability

If you discover a security vulnerability or privacy concern in MCastTalk, please report it responsibly:

- **Contact**: `dash.jeong@gmail.com`
- **Subject**: `[SECURITY] MCastTalk Vulnerability Report`

### What to Include:
- A description of the vulnerability and its potential impact.
- Exact steps to reproduce or a minimal proof-of-concept (PoC).
- Relevant device models, Android OS versions, and MCastTalk build numbers.

We appreciate responsible disclosure and will review all submitted reports promptly to coordinate necessary fixes.
