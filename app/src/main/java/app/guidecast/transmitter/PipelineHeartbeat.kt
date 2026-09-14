package app.guidecast.transmitter

/** Fixed schema only: never include transcript strings, URLs, app identifiers or error messages. */
internal fun pipelineHeartbeat(current: BroadcastSnapshot, previous: BroadcastSnapshot): String {
    fun delta(now: Long, before: Long) = (now - before).coerceAtLeast(0)
    val channels = current.translationChannels.take(8).mapIndexed { index, channel ->
        "$index:${channel.lastAcceptedSequence ?: -1},${channel.lastTranslatedSequence ?: -1}," +
            "${channel.lastPublishedSequence ?: -1},${channel.translationFailures},${channel.synthesisFailures}"
    }.joinToString(";")
    return "mode=${current.runMode} phase=${current.phase} input=${current.inputPhase} " +
        "frames=${delta(current.inputFrameCount, previous.inputFrameCount)} " +
        "audible=${delta(current.inputAudibleFrameCount, previous.inputAudibleFrameCount)} " +
        "sttDrop=${delta(current.recognitionDroppedFrameCount, previous.recognitionDroppedFrameCount)} " +
        "channels=$channels"
}
