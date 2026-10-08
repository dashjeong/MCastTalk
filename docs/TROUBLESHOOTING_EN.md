# Troubleshooting

## No microphone input

Check Android microphone permission, the selected input device and other apps using
the microphone. Check whether microphone input is on and whether source captions appear. Microphone and web broadcast controls are separate. Online
interpretation needs internet access and a key for the selected service. Gemini and
OpenAI keep separate key profiles: select the intended service and check its key status.

## No interpreted audio

Check volume, output device, local playback and the model's spoken output languages.
Verify source captions, translated captions and audio separately. Captions alone do
not prove audio completion. Review provider errors, billing and usage limits before
restarting. Offline speech requires installed voice data for the selected language.

## Listener page cannot connect

Start the web broadcast in the selected menu and use its displayed address or QR. Turning the microphone on alone does not start a broadcast. For relay, also check the LAN listener setting.
The phone and listener must be on mutually reachable networks. Check router isolation,
VPN/firewall rules and PIN/QR access settings. Select a language on the page and press
Listen. The page distinguishes paused broadcasting, waiting for audio and connection errors. HTTPS certificate trust requires a separate decision.

## Professional context is missing

Save the domain, instructions and materials, then enable the desired references.
Check transmission consent and the model's prompt/reference capabilities. Review the
shown excerpt and omitted glossary terms. Text references are not applied on models that do not support them.
Adding materials does not guarantee accurate translation.

## Recording history and downloads

Select a broadcast to preview scripts and available audio. Check recording gaps and
storage warnings. Choose a destination when downloading MP3 or scripts. Sharing passes
the exported content to another app.

## Offline correction memory

Review and approve candidates from a supported online comparison path before activating
correction memory. Gemini audio relay captions are not automatically paired. Use
“직접 확인한 예문 비교” in the relay screen to edit and confirm both the original and
translation, then compare with a prepared offline model after broadcasting ends. Review
the result before saving locally; rollback is available. This makes no additional API
request and does not retrain model weights.

Report the app version and reproduction steps. Never publish API keys, PINs or private
recordings/transcripts. [User guide](USER_GUIDE_EN.md) · [Privacy](../SECURITY.md)
