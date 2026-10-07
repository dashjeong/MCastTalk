# MCastTalk user guide

[한국어](USER_GUIDE_KO.md) · [APK downloads](https://github.com/dashjeong/MCastTalk/releases) · [Troubleshooting](TROUBLESHOOTING_EN.md)

Use an ARM64 device running Android 11 or later. Compare the APK checksum with the SHA-256 published in the same release. Quality and latency depend on the language, model, device and network. Available options depend on the installed version.

## Choose interpretation

- On-device interpretation: prepare recognition, translation and voice assets before use. Prepared assets work without internet access.
- Online sentence translation: recognize speech into sentences, translate through the selected API and play synthesized speech.
- Interpretation relay (On-통 Live(AI 통역)): send microphone audio to a selected real-time API and receive interpreted audio and captions. Internet access, an API key and transmission consent are required. Provider charges may apply.

Review the selected model, languages and permitted data. Enter keys in app settings; never include them in public posts or shared files.

## Broadcast and listen

1. Allow microphone access and choose the input and output languages. For on-device use, check model and voice readiness.
2. Connect listeners to the same Wi-Fi or hotspot. Client isolation may prevent access.
3. Start the broadcast. For interpretation relay, enable ‘LAN 청취자에게 방송’.
4. Copy/share the displayed listener address or show its QR. Preserve the access information included in the address.
5. Listeners open it in a browser and press Listen, then choose the available language, original/interpreted audio and captions.

Use headphones to prevent playback from re-entering the microphone. Check source captions, translations and audio before operation. Local addresses work on the shared network; internet-wide access requires a separate network setup.

## Professional context

Supported relay models offer ‘전문 분야·내 통역 지침’ for a domain and interpretation instructions. Add lecture material, articles or terminology to the reference library, select materials and inspect the transmission preview. The full library is not sent on every request.

Audio-only translation models may not accept separate instructions or text references. Follow the model-specific notice and confirm consent when the transmission scope changes. Remove confidential or personal information before cloud transmission.

## History and export

Select a broadcast in ‘방송 이력’ to inspect saved source/translated scripts and audio. Choose an audio track, listen, then download or share its MP3. Download the full script as TXT. Voice notes and broadcast history are managed in their respective menus.

Back up required data before deletion or changing versions. Check audio and transcripts for personal information before sharing.

## Quality improvement and limits

Reviewed online/offline comparisons require a supported route and a prepared offline model. Correction examples assist interpretation; this does not mean automatic model-weight training. Check activation and rollback states. Speech-relay support and comparison-learning support can differ by model.

Recognition errors, translation errors and latency may occur. Test actual speech and listening in your environment. HTTP broadcasts are not encrypted; use a trusted network. See [Troubleshooting](TROUBLESHOOTING_EN.md), [Security](../SECURITY.md) and [Licenses](THIRD_PARTY_LICENSES.md).
