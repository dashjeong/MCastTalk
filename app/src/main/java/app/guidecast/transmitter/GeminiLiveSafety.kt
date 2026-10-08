package app.guidecast.transmitter

import app.guidecast.core.stream.StreamPublishResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

internal enum class LiveAudioLoss { BEFORE_READY, INPUT_OVERFLOW, INPUT_ABANDONED, OUTPUT_BLOCKED, LISTENER_OVERFLOW, INPUT_SEND_UNCONFIRMED }
internal data class LiveAudioLossTotals(val frames: Long = 0, val bytes: Long = 0)

/** Activity is only a liveness hint; it does not acknowledge recognition or PCM consumption. */
internal data class GeminiLiveSourceStall(val sourceProgressAgeMillis: Long, val activeInputBytesSinceSource: Long)
internal enum class GeminiLiveSourceDelayKind { ENERGY_SOURCE_UNCONFIRMED, OUTPUT_PENDING }
internal data class GeminiLiveSourceDelayNotice(val progress: GeminiLiveSourceStall,
    val sourceObservation: Long, val providerResponseAgeMillis: Long?,
    val kind: GeminiLiveSourceDelayKind = GeminiLiveSourceDelayKind.ENERGY_SOURCE_UNCONFIRMED,
    val pendingOutputObservation: Long? = null, val pendingOutputProgressObservation: Long? = null,
    val pendingOutputAgeMillis: Long? = null)
internal class GeminiLiveSourceStalledFailure(val progress: GeminiLiveSourceStall) :
    IllegalStateException("Live source transcription stalled while active audio was sent")

/** Active PCM is an energy hint. Accepted original captions also create a separate lane output obligation. */
internal class GeminiLiveSourceProgress(private val deadlineNanos: Long = 60_000_000_000L,
    private val quietNanos: Long = 2_000_000_000L) {
    private var episodeStart: Long? = null
    private var lastActiveSent: Long? = null
    private var lastSource: Long? = null
    private var lastProviderResponse: Long? = null
    private var sourceObservation = 0L
    private var pendingOutputObservation: Long? = null
    private var pendingOutputStartedAt: Long? = null
    private var lastPendingOutputProgressAt: Long? = null
    private var outputProgressObservation = 0L
    private var pendingHasOutput = false
    private var lastPendingNoticeObservation: Long? = null
    private var lastPendingNoticeProgress: Long? = null
    private var lastNoticeAt: Long? = null
    private var lastNoticeSource: Long? = null
    private var lastNoticeEpisode: Long? = null
    private var activeBytesSinceSource = 0L
    private var inputEnded = false
    private var terminalClaimed = false
    init { require(deadlineNanos > quietNanos && quietNanos > 0) }
    @Synchronized fun sent(now: Long, bytes: Int, active: Boolean) {
        require(bytes >= 0)
        if (inputEnded || terminalClaimed || !active || bytes == 0) return
        if (lastActiveSent?.let { now - it < quietNanos } != true) {
            episodeStart = now
            activeBytesSinceSource = 0
        }
        lastActiveSent = now
        activeBytesSinceSource = Math.addExact(activeBytesSinceSource, bytes.toLong())
    }
    @Synchronized fun source(now: Long, text: String?) {
        if (inputEnded || terminalClaimed || text.isNullOrBlank()) return
        lastSource = now; activeBytesSinceSource = 0; sourceObservation++
        if (pendingOutputObservation == null) {
            pendingOutputObservation = sourceObservation
            pendingOutputStartedAt = now
            lastPendingOutputProgressAt = null
            pendingHasOutput = false
        }
    }
    @Synchronized fun providerResponse(now: Long, event: GeminiLiveEvent) {
        if (inputEnded || terminalClaimed) return
        val hasOutput = !event.translation.isNullOrBlank() || event.audio.isNotEmpty()
        if (!event.source.isNullOrBlank() || hasOutput || event.finished || event.interrupted) lastProviderResponse = now
        if (pendingOutputObservation != null && hasOutput) {
            lastPendingOutputProgressAt = now
            outputProgressObservation++
            pendingHasOutput = true
        }
        // A control-only completion is not evidence that the accepted source produced output.
        if (event.interrupted || (event.finished && pendingHasOutput)) clearPendingOutput()
    }
    private fun clearPendingOutput() {
        pendingOutputObservation = null
        pendingOutputStartedAt = null
        lastPendingOutputProgressAt = null
        pendingHasOutput = false
    }
    /** Quiet PCM cannot erase an accepted source's pending output. Notices never replay input. */
    @Synchronized fun delayNotice(now: Long): GeminiLiveSourceDelayNotice? {
        if (inputEnded || terminalClaimed) return null
        val pending = pendingOutputObservation
        val pendingAge = pendingOutputStartedAt?.let { now - maxOf(it, lastPendingOutputProgressAt ?: it) }
        val kind: GeminiLiveSourceDelayKind
        val progress: GeminiLiveSourceStall
        if (pending != null) {
            if (pendingAge == null || pendingAge < deadlineNanos) return null
            if (lastPendingNoticeObservation == pending && lastPendingNoticeProgress == outputProgressObservation) return null
            kind = GeminiLiveSourceDelayKind.OUTPUT_PENDING
            progress = GeminiLiveSourceStall((now - requireNotNull(lastSource)).coerceAtLeast(0L) / 1_000_000,
                activeBytesSinceSource)
        } else {
            progress = stalled(now) ?: return null
            if (lastNoticeSource == sourceObservation && lastNoticeEpisode == episodeStart) return null
            kind = GeminiLiveSourceDelayKind.ENERGY_SOURCE_UNCONFIRMED
        }
        if (lastNoticeAt?.let { now - it < 60_000_000_000L } == true) return null
        lastNoticeAt = now
        if (kind == GeminiLiveSourceDelayKind.OUTPUT_PENDING) {
            lastPendingNoticeObservation = pending
            lastPendingNoticeProgress = outputProgressObservation
        } else {
            lastNoticeSource = sourceObservation
            lastNoticeEpisode = episodeStart
        }
        return GeminiLiveSourceDelayNotice(progress, sourceObservation,
            lastProviderResponse?.let { (now - it).coerceAtLeast(0L) / 1_000_000 }, kind,
            pending, outputProgressObservation.takeIf { pending != null },
            pendingAge?.coerceAtLeast(0L)?.div(1_000_000))
    }
    @Synchronized fun endInput() { inputEnded = true; clearPendingOutput() }
    @Synchronized fun stalled(now: Long): GeminiLiveSourceStall? {
        if (inputEnded || terminalClaimed) return null
        val active = lastActiveSent ?: return null
        if (now - active >= quietNanos) return null
        val start = episodeStart ?: return null
        val elapsed = now - maxOf(start, lastSource ?: start)
        return if (elapsed >= deadlineNanos) GeminiLiveSourceStall(elapsed / 1_000_000, activeBytesSinceSource) else null
    }
    /** Revalidate and claim under the source/end monitor. Socket teardown belongs outside this lock. */
    @Synchronized fun claimStall(now: Long, failure: java.util.concurrent.atomic.AtomicReference<Throwable?>): GeminiLiveSourceStalledFailure? {
        val progress = stalled(now) ?: return null
        val candidate = GeminiLiveSourceStalledFailure(progress)
        if (!failure.compareAndSet(null, candidate)) return null
        terminalClaimed = true
        return candidate
    }
}

/** Serialize lane output observations and warning ownership; late callbacks cannot restore recovered notices. */
internal class GeminiLiveSourceDelayWarningOwner(private val allowed: () -> Boolean,
    private val changed: (String?, String?) -> Unit) {
    private var sourceObservation = 0L
    private var pendingOutputObservation: Long? = null
    private var outputProgressObservation = 0L
    private var pendingHasOutput = false
    private var ended = false
    private var ownedKind: GeminiLiveSourceDelayKind? = null
    private var ownedWarning: String? = null
    @Synchronized fun sourceReceived(text: String?) {
        if (ended || text.isNullOrBlank()) return
        sourceObservation++
        if (pendingOutputObservation == null) {
            pendingOutputObservation = sourceObservation
            pendingHasOutput = false
        }
        if (ownedKind == GeminiLiveSourceDelayKind.ENERGY_SOURCE_UNCONFIRMED) clearWarning()
    }
    @Synchronized fun providerResponse(event: GeminiLiveEvent) {
        if (ended) return
        val hasOutput = !event.translation.isNullOrBlank() || event.audio.isNotEmpty()
        if (pendingOutputObservation != null && hasOutput) {
            outputProgressObservation++
            pendingHasOutput = true
            if (ownedKind == GeminiLiveSourceDelayKind.OUTPUT_PENDING) clearWarning()
        }
        if (event.interrupted || (event.finished && pendingHasOutput)) {
            pendingOutputObservation = null
            pendingHasOutput = false
            if (ownedKind == GeminiLiveSourceDelayKind.OUTPUT_PENDING) clearWarning()
        }
    }
    private fun clearWarning() {
        val previous = ownedWarning ?: return
        if (!allowed()) return
        if (runCatching { changed(previous, null) }.isSuccess) {
            ownedWarning = null
            ownedKind = null
        }
    }
    @Synchronized fun endInput() { ended = true; pendingOutputObservation = null; clearWarning() }
    @Synchronized fun show(notice: GeminiLiveSourceDelayNotice, message: String): Boolean {
        if (ended || !allowed() || ownedWarning == message) return false
        val current = when (notice.kind) {
            GeminiLiveSourceDelayKind.ENERGY_SOURCE_UNCONFIRMED -> notice.sourceObservation == sourceObservation
            GeminiLiveSourceDelayKind.OUTPUT_PENDING -> notice.pendingOutputObservation != null &&
                notice.pendingOutputObservation == pendingOutputObservation &&
                notice.pendingOutputProgressObservation == outputProgressObservation
        }
        if (!current) return false
        val previous = ownedWarning
        if (runCatching { changed(previous, message) }.isFailure) return false
        ownedWarning = message
        ownedKind = notice.kind
        return true
    }
}

/** An advisory may replace only its own value or an empty warning slot. */
internal fun geminiSourceDelayWarning(current: String?, previous: String?, next: String?): String? =
    if (current.isNullOrBlank() || (previous != null && current == previous)) next else current

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
    @Synchronized fun endInput() { closed = true; ready = false; channel.close() }
    @Synchronized fun close() { closed = true; ready = false; channel.cancel() }
}

/** Queue admission is not playback; per-listener congestion must not be reported as success. */
internal fun recordLivePublication(result: StreamPublishResult?, bytes: Int, lost: (LiveAudioLoss, Int) -> Unit) {
    if (result == null || !result.accepted) lost(LiveAudioLoss.OUTPUT_BLOCKED, bytes)
    else repeat(result.droppedFrames) { lost(LiveAudioLoss.LISTENER_OVERFLOW, bytes) }
}

/** Provider segments have no common microphone utterance ID. Audio without a transcript still has a row. */
internal class GeminiLiveSegments(private val target: String, private val sourceLanguage: String, initialSequence: Long,
    private val sessionId: Long? = null) {
    private var next = initialSequence
    private var source = ""
    private var translation = ""
    private var firstEventNanos: Long? = null
    private var sourceFinal: Boolean? = null
    fun accept(event: GeminiLiveEvent, now: Long): TranslationTranscriptLine? {
        if (event.interrupted) {
            val row = if (firstEventNanos != null) TranslationTranscriptLine(next, source, requireNotNull(firstEventNanos),
                translations = visibleTranslations(terminal = true),
                sourceLanguageTag = sourceLanguage, liveSegmentLanguage = target, liveSourceFinal = sourceFinal,
                nativeAudioSessionId = sessionId, liveOutputState = LiveOutputState.CANCELLED) else null
            advance(); return row
        }
        event.source?.let { source = (source + it).takeLast(8_000) }
        event.translation?.let { translation = (translation + it).takeLast(8_000) }
        val visible = source.isNotBlank() || translation.isNotBlank() || event.audio.isNotEmpty() || firstEventNanos != null
        if (!visible) return null
        if (firstEventNanos == null) firstEventNanos = now
        val needsConfirmation = event.finished && geminiTerminalCaptionNeedsConfirmation(translation, target)
        val row = TranslationTranscriptLine(next, source, requireNotNull(firstEventNanos), event.finished && !needsConfirmation,
            translations = visibleTranslations(terminal = event.finished),
            sourceLanguageTag = sourceLanguage, liveSegmentLanguage = target, liveSourceFinal = sourceFinal,
            nativeAudioSessionId = sessionId,
            liveOutputState = when {
                needsConfirmation -> LiveOutputState.INCOMPLETE
                event.finished -> LiveOutputState.GENERATED
                else -> LiveOutputState.GENERATING
            })
        if (event.finished) advance()
        return row
    }
    private fun visibleTranslations(terminal: Boolean): Map<String, String> =
        geminiVisibleTranslation(translation, target, terminal).let { text ->
            if (text.isNotBlank()) mapOf(target to text) else emptyMap()
        }
    private fun advance() { next++; source = ""; translation = ""; firstEventNanos = null; sourceFinal = null }
}

/** Counts only. No key, transcript, PCM content or provider error is retained in diagnostics. */
internal class GeminiLiveCounters {
    private var frames = 0L
    private var bytes = 0L
    private var sentFrames = 0L
    private var sentBytes = 0L
    private var sourceEvents = 0L
    private var translationEvents = 0L
    private var outputBytes = 0L
    private var turns = 0L
    private var interruptions = 0L
    private val losses = mutableMapOf<LiveAudioLoss, Long>()
    @Synchronized fun capture(size: Int) { frames++; bytes += size }
    @Synchronized fun sent(size: Int) { sentFrames++; sentBytes += size }
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
            "sentFrames=$sentFrames sentBytes=$sentBytes " +
            "outputBytes=$outputBytes turns=$turns interruptions=$interruptions " +
            LiveAudioLoss.entries.joinToString(" ") { "${it.name}Bytes=${losses[it] ?: 0}" }
}
