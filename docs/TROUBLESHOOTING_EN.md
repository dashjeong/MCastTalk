# MCastTalk Troubleshooting Guide

<p align="left">
  <img src="https://img.shields.io/badge/Language-English-blue.svg" alt="English">
  <a href="TROUBLESHOOTING_KO.md"><img src="https://img.shields.io/badge/Language-한국어-lightgrey.svg" alt="한국어"></a>
  <a href="USER_GUIDE_EN.md"><img src="https://img.shields.io/badge/Guide-User_Guide-green.svg" alt="User Guide"></a>
</p>

[English User Guide](USER_GUIDE_EN.md) · [Portal Hub](USER_GUIDE.md) · [한국어 문제 해결](TROUBLESHOOTING_KO.md) · [Repository Home](../README.md)

When an issue occurs, read the exact status message and check the pipeline in this order: **input → speech recognition → translation → speech → web delivery**. During a live session, avoid resetting channels that are still working.

## Quick checks

1. Confirm that the operator and listener are on the same hotspot or Wi‑Fi network.
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

- app version: `0.2.39-alpha`;
- Android version, manufacturer, and model;
- input and output device names;
- source language and affected interpretation language;
- complete on-screen error message;
- time of occurrence and reproduction steps.

Diagnostic logs are designed to exclude audio, interpreted sentences, and PINs. Review the file before sharing it.
