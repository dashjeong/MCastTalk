package app.guidecast.transmitter

import app.guidecast.core.audio.PlaybackCaptureDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackCaptureUiPolicyTest {
    @Test
    fun actualPcmOverridesVendorPlaybackMetadata() {
        val status = playbackCaptureUiStatus(
            diagnostics = PlaybackCaptureDiagnostics(
                activePlaybackCount = 1,
                policyBlockedPlaybackCount = 1,
            ),
            signalActive = true,
        )

        assertEquals(PlaybackCaptureUiTone.SUCCESS, status.tone)
        assertTrue(status.message.contains("PCM 수신 확인"))
    }

    @Test
    fun activeEligiblePlaybackWaitsForPcmWithoutFalseFailure() {
        val status = playbackCaptureUiStatus(
            diagnostics = PlaybackCaptureDiagnostics(
                activePlaybackCount = 1,
                potentiallyCapturablePlaybackCount = 1,
            ),
            signalActive = false,
        )

        assertEquals(PlaybackCaptureUiTone.NEUTRAL, status.tone)
        assertTrue(status.message.contains("캡처 가능성"))
    }

    @Test
    fun blockedPolicyIsExplainedWithoutClaimingAnInputCrash() {
        val status = playbackCaptureUiStatus(
            diagnostics = PlaybackCaptureDiagnostics(
                activePlaybackCount = 1,
                policyBlockedPlaybackCount = 1,
            ),
            signalActive = false,
        )

        assertEquals(PlaybackCaptureUiTone.WARNING, status.tone)
        assertTrue(status.message.contains("차단"))
    }

    @Test
    fun mixedAnonymousPlaybackNeverClaimsTheTargetAppIsCapturable() {
        val status = playbackCaptureUiStatus(
            diagnostics = PlaybackCaptureDiagnostics(
                activePlaybackCount = 2,
                potentiallyCapturablePlaybackCount = 1,
                policyBlockedPlaybackCount = 1,
            ),
            signalActive = false,
        )

        assertEquals(PlaybackCaptureUiTone.WARNING, status.tone)
        assertTrue(status.message.contains("대상 판정 불가"))
    }
}
