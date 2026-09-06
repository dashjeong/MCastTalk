package app.guidecast.core.audio

import android.media.AudioAttributes

/**
 * Privacy-safe summary of currently active playback on the device.
 *
 * Android anonymizes playback configurations for ordinary apps. GuideCast deliberately keeps
 * only the information needed to explain why a virtual line input can or cannot receive PCM; it
 * never attempts to identify the playing package, title, or user content.
 */
data class PlaybackCaptureDiagnostics(
    val monitoringAvailable: Boolean = true,
    val activePlaybackCount: Int = 0,
    /** Usage/track policy look compatible, but manifest/profile restrictions remain unknown. */
    val potentiallyCapturablePlaybackCount: Int = 0,
    val policyBlockedPlaybackCount: Int = 0,
    val unsupportedUsagePlaybackCount: Int = 0,
    /** Expected MEDIA output routes (for example Galaxy Buds); informational, not capture inputs. */
    val mediaOutputRouteLabels: List<String> = emptyList(),
)

data class PlaybackStreamPolicy(
    val usage: Int,
    val allowedCapturePolicy: Int,
)

object PlaybackCaptureEligibility {
    private val eligibleUsages = setOf(
        AudioAttributes.USAGE_UNKNOWN,
        AudioAttributes.USAGE_MEDIA,
        AudioAttributes.USAGE_GAME,
    )

    fun summarize(streams: List<PlaybackStreamPolicy>): PlaybackCaptureDiagnostics {
        var eligible = 0
        var policyBlocked = 0
        var unsupportedUsage = 0

        streams.forEach { stream ->
            if (stream.usage !in eligibleUsages) {
                unsupportedUsage += 1
            } else if (stream.allowedCapturePolicy == AudioAttributes.ALLOW_CAPTURE_BY_ALL) {
                eligible += 1
            } else {
                policyBlocked += 1
            }
        }

        return PlaybackCaptureDiagnostics(
            activePlaybackCount = streams.size,
            potentiallyCapturablePlaybackCount = eligible,
            policyBlockedPlaybackCount = policyBlocked,
            unsupportedUsagePlaybackCount = unsupportedUsage,
        )
    }
}
