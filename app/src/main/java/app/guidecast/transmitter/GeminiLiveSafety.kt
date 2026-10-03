package app.guidecast.transmitter

import app.guidecast.core.stream.StreamPublishResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

internal enum class LiveAudioLoss { BEFORE_READY, INPUT_OVERFLOW, INPUT_ABANDONED, OUTPUT_BLOCKED, LISTENER_OVERFLOW }
internal data class LiveAudioLossTotals(val frames: Long = 0, val bytes: Long = 0)

/** No pre-connection buffering or replay. Every rejected/abandoned capture is observable. */
internal class GeminiLiveInput(private val lost: (LiveAudioLoss, Int) -> Unit) {
    private val channel = Channel<ByteArray>(5, onUndeliveredElement = { lost(LiveAudioLoss.INPUT_ABANDONED, it.size) })
    private var ready = false
    private var closed = false
    val frames = channel.receiveAsFlow()
    @Synchronized fun markReady() { if (!closed) ready = true }
    @Synchronized fun offer(bytes: ByteArray): Boolean {
        if (!ready || closed) { lost(if (closed) LiveAudioLoss.INPUT_ABANDONED else LiveAudioLoss.BEFORE_READY, bytes.size); return true }
        if (channel.trySend(bytes.copyOf()).isSuccess) return true
        lost(LiveAudioLoss.INPUT_OVERFLOW, bytes.size)
        close()
        return false
    }
    @Synchronized fun close() { closed = true; ready = false; channel.cancel() }
}

/** Queue admission is not playback; per-listener congestion must not be reported as success. */
internal fun recordLivePublication(result: StreamPublishResult?, bytes: Int, lost: (LiveAudioLoss, Int) -> Unit) {
    if (result == null || !result.accepted) lost(LiveAudioLoss.OUTPUT_BLOCKED, bytes)
    else repeat(result.droppedFrames) { lost(LiveAudioLoss.LISTENER_OVERFLOW, bytes) }
}

/** Provider segments have no common microphone utterance ID. Audio without a transcript still has a row. */
internal class GeminiLiveSegments(private val target: String, private val sourceLanguage: String, initialSequence: Long) {
    private var next = initialSequence
    private var source = ""
    private var translation = ""
    private var firstEventNanos: Long? = null
    fun accept(event: GeminiLiveEvent, now: Long): TranslationTranscriptLine? {
        if (event.interrupted) { advance(); return null }
        event.source?.let { source = (source + it).takeLast(8_000) }
        event.translation?.let { translation = (translation + it).takeLast(8_000) }
        val visible = source.isNotBlank() || translation.isNotBlank() || event.audio.isNotEmpty() || firstEventNanos != null
        if (!visible) return null
        if (firstEventNanos == null) firstEventNanos = now
        val row = TranslationTranscriptLine(next, source, requireNotNull(firstEventNanos), event.finished,
            translations = if (translation.isNotBlank()) mapOf(target to translation) else emptyMap(),
            sourceLanguageTag = sourceLanguage, liveSegmentLanguage = target)
        if (event.finished) advance()
        return row
    }
    private fun advance() { next++; source = ""; translation = ""; firstEventNanos = null }
}

/** Counts only. No key, transcript, PCM content or provider error is retained in diagnostics. */
internal class GeminiLiveCounters {
    private var frames = 0L
    private var bytes = 0L
    private var sourceEvents = 0L
    private var translationEvents = 0L
    private var outputBytes = 0L
    private var turns = 0L
    private var interruptions = 0L
    private val losses = mutableMapOf<LiveAudioLoss, Long>()
    @Synchronized fun capture(size: Int) { frames++; bytes += size }
    @Synchronized fun loss(reason: LiveAudioLoss, size: Int) { losses[reason] = (losses[reason] ?: 0) + size }
    @Synchronized fun event(event: GeminiLiveEvent) {
        if (!event.source.isNullOrBlank()) sourceEvents++
        if (!event.translation.isNullOrBlank()) translationEvents++
        outputBytes += event.audio.sumOf { it.size }.toLong()
        if (event.finished) turns++
        if (event.interrupted) interruptions++
    }
    @Synchronized fun summary(): String =
        "captureFrames=$frames captureBytes=$bytes sourceEvents=$sourceEvents translationEvents=$translationEvents " +
            "outputBytes=$outputBytes turns=$turns interruptions=$interruptions " +
            LiveAudioLoss.entries.joinToString(" ") { "${it.name}Bytes=${losses[it] ?: 0}" }
}
