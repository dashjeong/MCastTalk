package app.guidecast.transmitter

import org.junit.Assert.*
import org.junit.Test

class RelayOperationsTest {
    @Test fun delayedMicrophonePermissionCannotStartAStoppedOrDifferentBroadcast() {
        val live = BroadcastSnapshot(isInterpreterRelay = true, phase = BroadcastPhase.LIVE, recordingId = "first")
        assertTrue(relayMicrophoneRequestMatches("first", live))
        assertTrue(relayMicrophoneRequestMatches("first", live.copy(phase = BroadcastPhase.PAUSED)))
        assertFalse(relayMicrophoneRequestMatches("first", live.copy(phase = BroadcastPhase.IDLE)))
        assertFalse(relayMicrophoneRequestMatches("first", live.copy(recordingId = "next")))
        assertFalse(relayMicrophoneRequestMatches(null, live))
    }

    @Test fun echoReductionIsRequestedForRelayPlaybackAndOffModeRemainsUnprocessed() {
        val profile = MicrophoneInputProfile(app.guidecast.core.audio.MicrophoneNoiseMode.DEVICE, true)
        assertTrue(relayMicrophoneCaptureConfig(profile, true, true).enableEchoCanceler)
        assertTrue(relayMicrophoneCaptureConfig(profile, true, true).nearSpeakerFocus)
        assertFalse(relayMicrophoneCaptureConfig(profile, false, true).enableEchoCanceler)
        assertFalse(relayMicrophoneCaptureConfig(profile, true, false).enableEchoCanceler)
        assertFalse(relayMicrophoneCaptureConfig(profile.copy(noiseMode = app.guidecast.core.audio.MicrophoneNoiseMode.OFF), true, true).enableEchoCanceler)
    }

    @Test fun pausedLanRelayStillAcceptsMicrophoneButPausedTextStreamingDoesNot() {
        val paused = BroadcastSnapshot(phase = BroadcastPhase.PAUSED, inputPhase = InputPhase.ACTIVE)
        assertFalse(relayInputProcessingEnabled(paused))
        assertTrue(relayInputProcessingEnabled(paused.copy(isInterpreterRelay = true)))
        assertFalse(relayInputProcessingEnabled(paused.copy(isInterpreterRelay = true, phase = BroadcastPhase.IDLE)))
    }

    @Test fun fiveLanguageMicrophoneRestartsNeverReuseEarlierCaptionIdentities() {
        val identities = (0L..30L).flatMap { session -> (0..4).map { index -> relayNativeSequenceBase(session, index) } }
        assertEquals(identities.size, identities.distinct().size)
        identities.zipWithNext().forEach { (first, next) -> assertTrue(next - first >= 1_000_000_000L) }
    }

    @Test fun visibleAddressOmitsCredentialsWhileKeepingTheOriginalInviteUnchanged() {
        val invite = "http://192.168.43.1:8787/#token=synthetic-test-only"
        assertEquals("http://192.168.43.1:8787/", listenerDisplayAddress(invite))
        assertTrue(invite.contains("#token="))
        assertEquals("청취 주소 확인 필요", listenerDisplayAddress("https://username:password@192.168.43.1/"))
        assertEquals("청취 주소 확인 필요", listenerDisplayAddress("javascript:alert(1)"))
    }
}
