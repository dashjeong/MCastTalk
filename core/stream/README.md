# GuideCast stream sessions

Every broadcast owns one immutable `StreamSession`, returned by
`AudioStreamRegistry.configure(descriptors)`. The local server, original-audio producer, test-tone
producer, and every language TTS worker must retain that same handle. They must not look up a
session again by channel ID while the broadcast is running.

Calling `configure` is the generation boundary. It synchronously cancels every subscription from
the prior generation, including subscriptions whose channel IDs also exist in the replacement.
`StreamSession.tryPublish` returns `STALE_SESSION` for a prior producer, so prior PCM can never enter
a replacement same-ID channel. A successful subscription contains the descriptor selected at the
same atomic admission point.

## Listener admission

The production ceiling is 50 listeners across the session. Admission reserves one slot for every
other configured channel that currently has zero listeners. For five channels, the first channel
may therefore take at most 46 places until each of the other four channels has its first listener.
After every channel is represented, any channel may use free capacity. If a channel's last listener
disconnects, its one-place reservation is restored immediately.

`AudioStreamRegistry.observability` is the authoritative, immutable current-generation snapshot.
It contains the generation, active state, total and per-channel listener counts, and total and
per-channel bounded-queue drops. A drop counts one discarded listener-frame delivery; the same PCM
discarded for two slow listeners counts twice. Session replacement and explicit close are not
reported as congestion drops.
