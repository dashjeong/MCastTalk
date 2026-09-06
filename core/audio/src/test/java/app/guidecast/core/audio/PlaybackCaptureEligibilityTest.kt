package app.guidecast.core.audio

import android.media.AudioAttributes
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackCaptureEligibilityTest {
    @Test
    fun `media game and unknown with all policy are eligible`() {
        val result = PlaybackCaptureEligibility.summarize(
            listOf(
                stream(AudioAttributes.USAGE_MEDIA, AudioAttributes.ALLOW_CAPTURE_BY_ALL),
                stream(AudioAttributes.USAGE_GAME, AudioAttributes.ALLOW_CAPTURE_BY_ALL),
                stream(AudioAttributes.USAGE_UNKNOWN, AudioAttributes.ALLOW_CAPTURE_BY_ALL),
            ),
        )

        assertEquals(3, result.activePlaybackCount)
        assertEquals(3, result.potentiallyCapturablePlaybackCount)
        assertEquals(0, result.policyBlockedPlaybackCount)
        assertEquals(0, result.unsupportedUsagePlaybackCount)
    }

    @Test
    fun `system-only media is reported as policy blocked`() {
        val result = PlaybackCaptureEligibility.summarize(
            listOf(stream(AudioAttributes.USAGE_MEDIA, AudioAttributes.ALLOW_CAPTURE_BY_SYSTEM)),
        )

        assertEquals(0, result.potentiallyCapturablePlaybackCount)
        assertEquals(1, result.policyBlockedPlaybackCount)
        assertEquals(0, result.unsupportedUsagePlaybackCount)
    }

    @Test
    fun `assistant and accessibility usages remain unsupported even with all policy`() {
        val result = PlaybackCaptureEligibility.summarize(
            listOf(
                stream(AudioAttributes.USAGE_ASSISTANT, AudioAttributes.ALLOW_CAPTURE_BY_ALL),
                stream(
                    AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY,
                    AudioAttributes.ALLOW_CAPTURE_BY_ALL,
                ),
            ),
        )

        assertEquals(0, result.potentiallyCapturablePlaybackCount)
        assertEquals(0, result.policyBlockedPlaybackCount)
        assertEquals(2, result.unsupportedUsagePlaybackCount)
    }

    private fun stream(usage: Int, policy: Int) = PlaybackStreamPolicy(
        usage = usage,
        allowedCapturePolicy = policy,
    )
}
