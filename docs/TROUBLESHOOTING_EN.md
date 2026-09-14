# MCastTalk Troubleshooting Guide

<p align="left">
  <img src="https://img.shields.io/badge/Language-English-blue.svg" alt="English">
  <a href="TROUBLESHOOTING_KO.md"><img src="https://img.shields.io/badge/Language-한국어-lightgrey.svg" alt="한국어"></a>
  <a href="USER_GUIDE_EN.md"><img src="https://img.shields.io/badge/Guide-User_Guide-green.svg" alt="User Guide"></a>
</p>

[English User Guide](USER_GUIDE_EN.md) · [Portal Hub](USER_GUIDE.md) · [한국어 문제 해결](TROUBLESHOOTING_KO.md) · [Repository Home](../README.md)

When an issue occurs, read the exact status message and check the pipeline in this order: **input → speech recognition → translation → speech → web delivery**. During a live session, avoid resetting channels that are still working.

## Quick checks

1. For broadcasting to other devices, confirm that the operator and listener share a hotspot or Wi‑Fi network. **Use on this device (이 기기에서 사용)** works without that network.
2. Confirm that the input meter reacts to speech.
3. Confirm that every selected language shows **Ready**.
4. On the listener device, select **Play** and raise the media volume.
5. Test the complete join flow on a separate device.

## Symptoms and actions

| Symptom | Check | Action |
| --- | --- | --- |
| QR page does not open | Different networks, disabled hotspot, client isolation | Join the same network, temporarily disable VPN, and create the QR code again. Check AP/client isolation on the router. |
| PIN is rejected | Mistyped PIN, expired session, attempt limit | Reopen the latest QR code and confirm the 4–8 digit PIN. Wait before retrying if a rate-limit message appears. |
| Connected but no sound | Browser autoplay, media volume, silent operator input | Select **Play**, check media volume and earphones, then check the operator input meter. |
| One language is silent | Translation model, voice, or channel not ready | Prepare and test translation and TTS for that language. Other channels can remain active. |
| Translation does not start | Same source/output language, model not applied, low memory | Choose different source and output languages. Complete preparation and self-test. Close other apps and test again. |
| Input stops | Microphone permission, screen lock, another recording/call app | Keep the screen on, close recording and call apps, then select **Start input** again. |
| Bluetooth microphone sounds poor | Headset/SCO call profile | This may be expected. Test a USB‑C or built-in microphone when quality is important. |
| Captured app playback is silent | Source app blocks capture, protected or call audio | Confirm that the source app permits third-party capture. A normal APK cannot bypass protected audio; use a physical input. |
| Feedback or ringing | Loudspeaker audio is entering the microphone | Lower or stop local monitoring immediately, use earphones, and separate the microphone and speaker. |
| Delay keeps increasing | Long speech segments, many languages, limited resources | Speak in shorter sentences and reduce simultaneous languages. Listeners can select **Go live**. |
| Model download fails | Internet or storage shortage | Check a stable internet connection and free space, then retry. The download resumes when supported. |
| Remote web microphone is denied | Plain HTTP, missing certificate, fingerprint mismatch | Follow security setup. Install the local CA only after the fingerprints match, then use the HTTPS `/mic` page. |
| Update will not install | Signing mismatch or downgrade | Do not uninstall the existing app. Ask the distributor to verify the APK version and signing certificate. |

## On-site recovery sequence

1. Tell participants that the broadcast is being paused.
2. If the input meter moves, keep input active and inspect only the affected language.
3. If there is no input, close other recording apps and select the input device again.
4. Prepare and test translation and speech for the affected language.
5. Confirm playback on a separate listener device.
6. Resume only after the complete path works.

Do not immediately clear app storage or reinstall the app. Doing so may remove corrections and downloaded models.

## Information to include in a report

Export the diagnostic log and include:

- the actual installed app version, for example `0.2.40-alpha`;
- Android version, manufacturer, and model;
- input and output device names;
- source language and affected interpretation language;
- complete on-screen error message;
- time of occurrence and reproduction steps.

Diagnostic logs are designed to exclude audio, interpreted sentences, and PINs. Review the file before sharing it.

## Checks for the 0.2.40 additions

| Symptom | Check and action |
| --- | --- |
| You want to interpret without Wi-Fi | Select **Usage mode → Use on this device (이 기기에서 사용)** and start standalone use. Prepare models and language packs first. Broadcasting to other devices still requires a shared network. |
| No QR code in standalone mode | Standalone use does not open a listener address. Stop it and select **Broadcast to other devices (다른 기기에 방송)** when sharing is required. |
| App selection and manual package name seem inconsistent | Selecting an app fills its package name. Editing it manually afterward takes priority when input starts. Source-app capture restrictions still apply. |
| File conversion cannot start | Stop live input, broadcasting and interpretation tests, then follow permission guidance. New transcription needs Android 13+ and a compatible on-device service supporting file input. |
| Automatic language is unavailable | Select the source language manually on Android 13. On Android 14+, engine or language-pack limitations can still require manual selection. |
| Only some files or languages failed | Completed results remain saved. Resolve access, language-pack, model or storage issues, then use **Retry failed items (실패 항목 재작업)** or **Retry this file (이 파일 재작업)**. |
| Some files are missing from a selected folder | Check audio support and provider access. Split very large folders and follow selection-limit messages. |
| Saved transcript audio will not play | Use **Find original file again (원본 파일 다시 찾기)** and select identical file content. A matching name alone is insufficient. Saved text remains available if the original cannot be opened. |
| No word highlight, only a sentence highlight | The recognizer did not provide usable word times. **Estimated timing (시각 추정)** does not promise precise boundaries; compare the script with the audio. |
| Processing times and technical numbers disappeared | Enable **Settings → Show developer information (개발자 정보 표시)** if needed. Actionable errors and diagnostic export remain available in the normal view. |
| Paraphrasing makes no apparent difference | ML Kit alone has no register control. Check that a prepared Gemma engine is used by the selected path, or that the API has a key and transmission consent. Live API learning affects later matching sentences. |
| Expression sounds similar to baseline | This is a small rate/pitch experiment with installed Android voices. Compare the same sentence. Moonshine does not receive these controls, and emotion cloning is unsupported. |
| Cloud review or learning is not applied | Check developer display, key, transmission consent and automatic learning for live use. Timeouts, failures and conservative checks retain the draft. AI cannot overwrite human-confirmed entries. |

See the [developer lab and sentence memory guide](DEVELOPER_LAB.md) for review and data-transfer scope. Do not publish API keys, private source text/audio or file access addresses in issue reports. Diagnostic collection does not depend on enabling developer display.

Eight-hour stability and physical Galaxy/One UI, outdoor or Android/iPhone behavior require tests in those exact environments. Automated and emulator checks do not establish those results or guarantee long-running operation. Consult the [test report](TEST_REPORT.md) for the exact scope.
