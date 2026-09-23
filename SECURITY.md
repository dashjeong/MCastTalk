# Security and Privacy Policy

## 1. Supported Versions

Security review targets the following Alpha version. This is not a service-level
agreement or certification that all vulnerabilities have been removed:

| Version | Supported |
|---|---|
| `0.2.43-alpha` | Withdrawn on 2026-09-23; maintenance only, do not distribute |
| `0.2.42-alpha` | Current Alpha; experimental |
| `0.2.41-alpha` | Previous public Alpha |
| `0.2.40-alpha` | Superseded: continuous transcript buffer recovery fix in 0.2.41 |
| `0.2.39-alpha` | Previous public Alpha |
| `< 0.2.39` | :x: |

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
4. **Optional Developer API Review**:
   - Developer display, online review consent and a stored API key must all be enabled before one original sentence, its translation and language/style information can be sent to the selected official OpenAI or Google endpoint. Audio, diagnostics, filenames and previous conversation are excluded.
   - This is optional paid API use; provider terms and data policies apply. Turning it off keeps local translation. Keys are encrypted with Android Keystore and excluded from portable settings. Imports reset online consent and automatic learning. See [the request contract](docs/DEVELOPER_LAB.md).
5. **Standalone Use**:
   - The standalone option does not open listener HTTP/WebSocket sockets. Prepared local models can be used without Wi-Fi. It does not disable Android system networking or override optional API consent.
6. **Optional Primary Translation API**:
   - Settings can select OpenAI, Gemini or a user-specified HTTPS OpenAI-compatible endpoint. Separate consent permits the current source text and up to 1,000 characters of preceding context. No audio, diagnostic logs or credentials enter the body. Credentials are encrypted and scoped to the provider/base URL. Changing the destination or model resets consent; redirects are rejected.
   - Responses are bounded by time, size and JSON nesting depth. The app never executes model-generated tools, commands or code. Recognizable credential-shaped text is blocked locally; this heuristic is not a complete personal-data classifier. Do not send sensitive speech to a third-party service without reviewing its terms.
7. **Approval-Based Improvement**:
   - Teacher learning first selects likely problem cases locally. Normal repeated sentences are not sent simply because they occur often. Improved candidates remain pending until the user approves; holding a proposal preserves current behavior. Existing confirmed corrections take precedence.
   - Up to 200 selected before/after reports are stored privately and may be explicitly exported or migrated with dictionary data. The local Astra assistant uses counters and fixed actions, with no permission to change security policy or deploy code. No claim of immunity to attacks by AI agents or humans is made.

### Listener access and transport

- Broadcast access defaults to **public** within the network that can reach the
  server. Operators can select supported PIN/QR access controls.
- HTTP/WebSocket listener traffic is not end-to-end encrypted. A PIN or QR token
  is access control, not encryption. Use a trusted, protected local network; do not
  forward broadcast ports to the internet.
- Remote web microphone capture requires browser permission and a secure context.
  Certificate trust is a separate decision; a PIN does not waive these requirements.

### Stored content and diagnostics

- Finalized source/translation transcripts are stored in app-private SQLite, with
  up to 500,000 active lines and pages of 1,000. The operator selects oldest-line
  overwrite or per-day private backup on overflow. There is no automatic 30-day
  expiry. Daily backups need available storage; a backup failure retains source
  records and warns instead of deleting them.
- Hiding archived items removes them from normal queries but preserves physical
  daily backup copies. Portable script backups contain visible retained records;
  they are content exports, unlike minimal diagnostic exports.
- User-initiated settings, dictionary and script exports create ZIP files only in
  the device's `Download/MCastTalk/Backups` folder. They are not automatically sent
  to a server. These portable files are not encrypted and can contain private
  sentences and file metadata. Credentials, certificates and original audio are
  excluded. Existing backups are not overwritten; incomplete new exports are
  kept pending until complete and cleaned up on cancellation or failure.
- File conversion stores transcripts, translations, timing and the selected
  file's name, location and SHA-256 in the private library. The source audio stays
  at its selected location. Imported locations require explicit file selection
  and matching SHA-256 before playback. Sentence memory stores up to 50,000 entries;
  AI entries cannot overwrite user-confirmed wording.
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
