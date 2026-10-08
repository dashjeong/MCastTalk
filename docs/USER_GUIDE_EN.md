# MCastTalk user guide

[한국어](USER_GUIDE_KO.md) · [APK downloads](https://github.com/dashjeong/MCastTalk/releases) · [Troubleshooting](TROUBLESHOOTING_EN.md)

Use an ARM64 device running Android 11 or later. Compare the APK checksum with the SHA-256 published in the same release. Quality and latency depend on the language, model, device and network. Available options depend on the installed version.

## Choose interpretation

- On-device interpretation: prepare recognition, translation and voice assets before use. Prepared local models process speech on the device.
- Online sentence translation: recognize speech into sentences, translate through the selected API and play synthesized speech.
- Interpretation relay (통역 중계): send microphone audio to a selected real-time API and receive interpreted audio and any available captions. Internet access, an API key and transmission consent are required. Provider charges may apply.

Review the selected model, languages and permitted data. Enter keys in app settings; never include them in public posts or shared files.

Relay selection shows five common language choices first. More and search provide a catalog of 75 language/region choices. Select one spoken language and up to five interpreted channels. A listed choice does not guarantee audio, captions or quality with every model. Each interpreted language uses a separate API connection, so selecting more languages can increase costs. If the provider returns no translated captions, follow the app’s missing-caption status.

## Broadcast and listen

1. Choose a service menu. Use Settings for common options and ‘이 메뉴 설정’ for that task. When using voice input, allow microphone access and choose languages. Prepare local model and voice assets when using on-device processing.
2. Connect listeners to the same Wi-Fi or hotspot. Client isolation may prevent access.
3. Start the web broadcast in that menu. Microphone on/off is a separate control; turning it on alone does not broadcast to listeners. For interpretation relay, check the LAN listener broadcast setting.
4. Copy/share the displayed listener address or show its QR. Preserve the access information included in the address.
5. Listeners open it in a browser and press Listen, then choose the available language, original/interpreted audio and captions. Check the status when speech is being prepared or the broadcast is paused.

Use headphones to prevent playback from re-entering the microphone. Check source captions, translations and audio before operation. Local addresses work on the shared network; internet-wide access requires a separate network setup.

## Professional context

Supported relay models offer ‘전문 분야·내 통역 지침’ for a domain and interpretation instructions. Add lecture material, articles or terminology to the reference library, select materials and approve use after reviewing the excerpt preview. The full library is not sent on every request.

Instruction and text-reference capabilities vary by model. Follow the model-specific notice and confirm consent when the transmission scope changes. Remove confidential or personal information before cloud transmission.

## History and export

Select a broadcast in ‘방송 이력’ to inspect saved source/translated scripts and audio. Choose an audio track, listen, then download or share its MP3. Download the full script as TXT. Voice notes and broadcast history are managed in their respective menus.

Web replay also provides stored scripts. Replay HUD scripts are not automatically synchronized to the audio timeline.

Back up required data before deletion or changing versions. Check audio and transcripts for personal information before sharing.

## Quality improvement and limits

Supported routes use reviewed and approved online/offline correction examples as reference material. A prepared offline model is required; this does not retrain model weights. Check activation and rollback states. Speech-relay and comparison/correction capabilities vary by model.

Recognition errors, translation errors and latency may occur. Test actual speech and listening in your environment. HTTP broadcasts are not encrypted; use a trusted network. See [Troubleshooting](TROUBLESHOOTING_EN.md), [Security](../SECURITY.md) and [Licenses](THIRD_PARTY_LICENSES.md).
