package app.guidecast.core.server

/** Public lifecycle and readiness contain fixed values only, never provider diagnostics. */
enum class GuideCastBroadcastPhase { IDLE, PREPARING, LIVE, PAUSED, COMPLETED, FAILED }

enum class GuideCastChannelReadiness { READY, PREPARING, SUBTITLES_ONLY, RECOVERING, UNAVAILABLE }

enum class GuideCastListenerNextAction {
    NONE, WAIT_FOR_BROADCASTER, ASK_BROADCASTER_TO_RETRY, SELECT_ANOTHER_CHANNEL, USE_REPLAY,
}

data class GuideCastChannelStatus(
    val audioReadiness: GuideCastChannelReadiness,
    val transcriptReadiness: GuideCastChannelReadiness = GuideCastChannelReadiness.READY,
    val nextAction: GuideCastListenerNextAction = GuideCastListenerNextAction.NONE,
)

data class GuideCastBroadcastStatus(
    val phase: GuideCastBroadcastPhase,
    val channels: Map<String, GuideCastChannelStatus> = emptyMap(),
    val nextAction: GuideCastListenerNextAction = GuideCastListenerNextAction.NONE,
)

internal fun readBroadcastStatus(
    provider: (() -> GuideCastBroadcastStatus)?,
): GuideCastBroadcastStatus? {
    if (provider == null) return null
    return runCatching { provider().let { it.copy(channels = it.channels.toMap()) } }
        .getOrElse {
            GuideCastBroadcastStatus(
                phase = GuideCastBroadcastPhase.FAILED,
                nextAction = GuideCastListenerNextAction.ASK_BROADCASTER_TO_RETRY,
            )
        }
}

internal fun GuideCastBroadcastStatus.channelStatus(channelId: String): GuideCastChannelStatus =
    channels[channelId] ?: GuideCastChannelStatus(
        audioReadiness = when (phase) {
            GuideCastBroadcastPhase.PREPARING -> GuideCastChannelReadiness.PREPARING
            GuideCastBroadcastPhase.LIVE, GuideCastBroadcastPhase.PAUSED,
            GuideCastBroadcastPhase.COMPLETED -> GuideCastChannelReadiness.READY
            GuideCastBroadcastPhase.FAILED -> GuideCastChannelReadiness.RECOVERING
            GuideCastBroadcastPhase.IDLE -> GuideCastChannelReadiness.UNAVAILABLE
        },
        transcriptReadiness = when (phase) {
            GuideCastBroadcastPhase.PREPARING -> GuideCastChannelReadiness.PREPARING
            GuideCastBroadcastPhase.FAILED -> GuideCastChannelReadiness.RECOVERING
            GuideCastBroadcastPhase.IDLE -> GuideCastChannelReadiness.UNAVAILABLE
            else -> GuideCastChannelReadiness.READY
        },
        nextAction = nextAction,
    )
