# Security and privacy

This policy describes the current source. The latest downloadable prerelease is
[0.2.48-beta](https://github.com/dashjeong/MCastTalk/releases/tag/v0.2.48-beta),
for field testing. Publication is not full service acceptance or a security certification.
Actual interpretation, LAN listening, five-second latency, continuous operation and
domain-quality improvement remain pending acceptance; see [follow-up #24](https://github.com/dashjeong/MCastTalk/issues/24).

## Offline and online processing

Prepared offline models process speech and translation on the device. Downloading
models and using Android speech services can involve network requests. Selecting
offline processing does not isolate Android from all network traffic.

Online sentence translation sends the current recognized text and bounded prior
context to the selected provider after consent. OpenAI-compatible custom endpoints
must use HTTPS. Changing the destination or model resets consent. Credentials are
sent for authentication, not as translation content.

Live interpretation uses the selected Gemini or OpenAI audio service. **Microphone
audio is sent to that provider.** Returned audio and available source/translated
captions can play on the phone and, when enabled, reach network listeners.
Supported models have different language, prompt and reference capabilities; the
app shows their supported options. Professional context can send the chosen domain,
interpretation instructions and an enabled reference excerpt after consent. Reference
selection is limited to four items in a combined payload of at most 600 characters;
these are excerpts, not full document uploads. Audio-only models cannot apply text
references. Review the provider's data policy and paid usage before starting.

Optional online comparison/review sends selected text and translation pairs after
separate consent. Suggestions require review before activation. This stores local
correction memory; it does not retrain model weights. A provider/model without a
reliable comparison path does not silently claim to improve the offline model.

Keys can be used for the current session or explicitly stored encrypted with Android
Keystore. Portable exports exclude credentials. Do not put keys, personal information
or confidential content in prompts, references or recordings. Credential-pattern
checks are limited heuristics, not a complete classifier of sensitive information.

## Listener access

The listener server runs on the Android device. Users who can reach its network
address can receive broadcast content according to the selected access settings.
PINs and QR tokens control access; they do not encrypt HTTP/WebSocket traffic.
HTTPS requires certificate trust. Browser microphone access also requires a secure
context and explicit permission. Use a trusted network and review access before
sharing a link or exposing the server beyond that network.

## Stored recordings and exports

Broadcast history and transcripts are held in app-private storage. A page size does
not limit a recording's downloadable transcript. Retention and recording capacity
depend on the selected storage options and available space; review warnings and
recording gaps. Listeners can retain their own copies.

Recording exports provide MP3 audio and text scripts. File conversion, voice notes,
professional references, dictionaries and approved corrections can contain private
content. Explicit portable backups may include scripts and voice recordings but
exclude API credentials. Exported files are not automatically encrypted. Download,
backup and share actions should be checked before sending files to another app or
person. Model files and external source audio have separate storage locations.

Diagnostic exports aim to exclude raw speech, transcripts and credentials. Android
logcat, third-party logs and screenshots may still contain sensitive information.
Never attach API keys, access tokens, PINs, signing keys, private recordings or user
conversations to public issues. Updating the app does not intentionally clear models
or settings.

## Reporting a vulnerability

Send a private report to `dash.jeong@gmail.com` with subject
`[SECURITY] MCastTalk Vulnerability Report`. Include the affected app/Android versions,
impact and minimal reproduction steps. Exclude credentials and private recordings.
