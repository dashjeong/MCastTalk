package app.guidecast.transmitter

import kotlinx.coroutines.withTimeoutOrNull

/** Reuse time already spent receiving after an ordered pause, without certifying completion. */
internal class NativeDrainWaitBudget(private val nowNanos: () -> Long = System::nanoTime) {
    private var pausedAt: Long? = null
    private var progressAt: Long? = null
    private var previousSource: String? = null
    private var previousTranslation: String? = null
    private var generationValue = 0L
    val generation: Long @Synchronized get() = generationValue

    @Synchronized fun pauseSent(expectedGeneration: Long) {
        if (expectedGeneration != generationValue) return
        pausedAt = nowNanos()
        progressAt = null
    }

    @Synchronized fun invalidate() {
        generationValue++
        pausedAt = null
        progressAt = null
        previousSource = null
        previousTranslation = null
    }

    @Synchronized fun turnEnded() {
        previousSource = null
        previousTranslation = null
    }

    @Synchronized fun observe(source: String?, translation: String?, hasAudio: Boolean) {
        val sourceText = source?.trim()?.takeIf(String::isNotEmpty)
        val translatedText = translation?.trim()?.takeIf(String::isNotEmpty)
        val changed = hasAudio || sourceText != null && sourceText != previousSource ||
            translatedText != null && translatedText != previousTranslation
        if (sourceText != null) previousSource = sourceText
        if (translatedText != null) previousTranslation = translatedText
        if (changed && pausedAt != null) progressAt = nowNanos()
    }

    @Synchronized fun remainingMillis(maximumMillis: Long): Long {
        require(maximumMillis > 0)
        val pause = pausedAt ?: return maximumMillis
        val origin = maxOf(pause, progressAt ?: pause)
        val elapsed = (nowNanos() - origin).coerceAtLeast(0L) / 1_000_000L
        return (maximumMillis - elapsed).coerceAtLeast(0L)
    }

    suspend fun awaitCompletion(maximumMillis: Long, completion: suspend () -> Boolean): Boolean? {
        require(maximumMillis > 0)
        val started = nowNanos()
        while (true) {
            val elapsed = (nowNanos() - started).coerceAtLeast(0L) / 1_000_000L
            val hardRemaining = (maximumMillis - elapsed).coerceAtLeast(0L)
            val endSignalRemaining = (minOf(maximumMillis, 3_000L) - elapsed).coerceAtLeast(0L)
            val wait = minOf(hardRemaining, maxOf(remainingMillis(maximumMillis), endSignalRemaining))
            if (wait <= 0) return null
            val result = withTimeoutOrNull(wait) { completion() }
            if (result != null) return result
            // A late response may have progressed during this wait. Re-evaluate it without
            // extending the original final-stop bound or acknowledging unprocessed input.
        }
    }
}
