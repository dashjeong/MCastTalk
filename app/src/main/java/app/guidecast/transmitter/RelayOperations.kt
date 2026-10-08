package app.guidecast.transmitter

import app.guidecast.core.audio.AudioCaptureConfig
import app.guidecast.core.audio.MicrophoneNoiseMode

internal fun relayMicrophoneRequestMatches(recordingId: String?, snapshot: BroadcastSnapshot): Boolean =
    recordingId != null && recordingId == snapshot.recordingId && snapshot.isInterpreterRelay &&
        snapshot.phase in setOf(BroadcastPhase.LIVE, BroadcastPhase.PAUSED)

internal fun relayMicrophoneCaptureConfig(profile: MicrophoneInputProfile, relay: Boolean, localPlayback: Boolean): AudioCaptureConfig =
    AudioCaptureConfig(noiseMode = profile.noiseMode, nearSpeakerFocus = profile.nearSpeakerFocus,
        enableEchoCanceler = relay && localPlayback && profile.noiseMode != MicrophoneNoiseMode.OFF)

/** Input processing and LAN transmission have separate lifetimes within one broadcast. */
internal fun relayInputProcessingEnabled(snapshot: BroadcastSnapshot): Boolean =
    snapshot.translationTestActive || snapshot.phase == BroadcastPhase.LIVE ||
        (snapshot.isInterpreterRelay && snapshot.phase == BroadcastPhase.PAUSED)

/** New microphone runs must not overwrite earlier language segments in the same history. */
internal fun relayNativeSequenceBase(sessionId: Long, languageIndex: Int): Long {
    require(sessionId in 0..Long.MAX_VALUE / 10_000_000_000L - 1 && languageIndex in 0..4)
    return (sessionId + 1) * 10_000_000_000L + languageIndex * 1_000_000_000L
}

internal fun listenerDisplayAddress(listenerUrl: String): String = runCatching {
    val uri = java.net.URI(listenerUrl)
    require(uri.scheme in setOf("http", "https") && uri.host != null && uri.userInfo == null)
    java.net.URI(uri.scheme, null, uri.host, uri.port, uri.path.ifBlank { "/" }, null, null).toString()
}.getOrDefault("청취 주소 확인 필요")
