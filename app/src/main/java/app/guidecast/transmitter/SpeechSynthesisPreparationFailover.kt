package app.guidecast.transmitter

import app.guidecast.core.stream.PcmAudioFrame
import app.guidecast.core.stream.MAX_SIMULTANEOUS_TRANSLATED_CHANNELS
import app.guidecast.core.translation.MIN_AUDIBLE_PCM_PEAK
import app.guidecast.core.translation.MIN_AUDIBLE_PCM_RMS
import app.guidecast.core.translation.ExecutionAwareSpeechSynthesisEngine
import app.guidecast.core.translation.SpeechSynthesisEngine
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal enum class SpeechSynthesisPreparationBackend {
    MOONSHINE,
    ANDROID_OFFLINE,
}

internal data class SpeechSynthesisLanguagePreparation(
    val languageTag: String,
    val backend: SpeechSynthesisPreparationBackend,
    val moonshineError: Throwable? = null,
)

internal class SpeechSynthesisPreparationException(
    val languageTag: String,
    val moonshineError: Throwable,
    val androidOfflineError: Throwable,
) : IllegalStateException(
    "통역 음성을 준비하지 못했습니다: $languageTag" +
        " · Moonshine: ${moonshineError.conciseMessage()}" +
        " · Galaxy 오프라인 음성: ${androidOfflineError.conciseMessage()}",
    androidOfflineError,
) {
    init {
        addSuppressed(moonshineError)
    }
}

/**
 * Prepares every selected language independently. A native-worker failure for one Moonshine voice
 * therefore only switches that language to the installed Android offline voice. Cancellation is
 * never treated as a voice failure, including when a provider wrapped it in another exception.
 */
internal suspend fun prepareSpeechSynthesisLanguages(
    languageTags: Collection<String>,
    prepareMoonshine: suspend (String) -> Unit,
    prepareAndroidOffline: suspend (String) -> Unit,
    moonshinePreparationTimeoutMillis: Long = DEFAULT_MOONSHINE_PREPARATION_TIMEOUT_MILLIS,
    androidFallbackPreparationTimeoutMillis: Long = DEFAULT_ANDROID_FALLBACK_PREPARATION_TIMEOUT_MILLIS,
    onPrepared: (SpeechSynthesisLanguagePreparation) -> Unit = {},
): List<SpeechSynthesisLanguagePreparation> {
    require(languageTags.size in 1..MAX_SIMULTANEOUS_TRANSLATED_CHANNELS) { "Prepare one to $MAX_SIMULTANEOUS_TRANSLATED_CHANNELS TTS languages" }
    require(moonshinePreparationTimeoutMillis > 0L)
    require(androidFallbackPreparationTimeoutMillis > 0L)
    return languageTags.distinct().map { languageTag ->
        val result = prepareSpeechSynthesisLanguage(
            languageTag = languageTag,
            prepareMoonshine = prepareMoonshine,
            prepareAndroidOffline = prepareAndroidOffline,
            moonshinePreparationTimeoutMillis = moonshinePreparationTimeoutMillis,
            androidFallbackPreparationTimeoutMillis = androidFallbackPreparationTimeoutMillis,
        )
        onPrepared(result)
        result
    }
}

/** A best-effort standby voice must never hold the entire multi-language preparation barrier. */
internal suspend fun prepareOptionalSpeechStandby(
    languageTag: String,
    timeoutMillis: Long,
    prepare: suspend (String) -> Unit,
): Throwable? {
    require(languageTag.isNotBlank())
    require(timeoutMillis > 0L)
    return try {
        val completed = withTimeoutOrNull(timeoutMillis) {
            prepare(languageTag)
            true
        } ?: false
        if (completed) null else {
            IllegalStateException("Galaxy 오프라인 대체 음성 준비 시간 초과: $languageTag")
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        error
    }
}

/**
 * Keeps a translated sentence alive across a stalled/empty native TTS attempt. Only an explicit
 * outer session cancellation stops it; attempt deadlines switch the same text to the prepared
 * Android offline voice before any audible primary PCM. If primary speech was already audible,
 * the sentence is not replayed because duplicated/overlapping PCM is a release-blocking defect.
 */
internal fun synthesizeSpeechWithFallback(
    primary: SpeechSynthesisEngine,
    fallback: SpeechSynthesisEngine,
    text: String,
    languageTag: String,
    onPrimaryAudibleFrame: (PcmAudioFrame) -> Unit = {},
    onPrimaryFailure: (Throwable) -> Unit = {},
    onFallbackAudibleFrame: (PcmAudioFrame) -> Unit = {},
    onFallbackFailure: (Throwable) -> Unit = {},
    onExecutionWaitStarted: () -> Unit = {},
    onExecutionStarted: () -> Unit = {},
    primaryFirstFrameTimeoutMillis: Long = DEFAULT_PRIMARY_FIRST_FRAME_TIMEOUT_MILLIS,
    fallbackFirstFrameTimeoutMillis: Long = DEFAULT_FALLBACK_FIRST_FRAME_TIMEOUT_MILLIS,
): Flow<PcmAudioFrame> = flow {
    // This state must be per collector. A SpeechSynthesisEngine can be reused by successive
    // sentences and one sentence's partial output must never influence the next sentence.
    var primaryEmittedAudiblePcm = false
    emitAll(
        primary.synthesizeWithFirstAudibleFrameWithin(
            text, languageTag, primaryFirstFrameTimeoutMillis, "고품질 TTS",
            onExecutionWaitStarted, onExecutionStarted,
        )
            .requireNonSilentPcm("고품질 TTS")
            .onEach { frame ->
                if (frame.hasAudiblePcm16()) {
                    primaryEmittedAudiblePcm = true
                    onPrimaryAudibleFrame(frame)
                }
            }
            .catch { primaryError ->
                primaryError.findCancellation()?.let { throw it }
                onPrimaryFailure(primaryError)

                // Replaying the whole translated sentence after part of it was already audible
                // produces overlapping/duplicated speech in the browser. Preserve what was sent,
                // report this channel as degraded, and let its next utterance retry normally.
                if (primaryEmittedAudiblePcm) {
                    throw PartialSpeechSynthesisException(primaryError)
                }

                emitAll(
                    fallback.synthesizeWithFirstAudibleFrameWithin(
                        text, languageTag, fallbackFirstFrameTimeoutMillis,
                        "Galaxy 오프라인 TTS",
                        onExecutionWaitStarted, onExecutionStarted,
                    )
                        .requireNonSilentPcm("Galaxy 오프라인 TTS")
                        .onEach { frame ->
                            if (frame.hasAudiblePcm16()) onFallbackAudibleFrame(frame)
                        }
                        .catch { fallbackError ->
                            fallbackError.findCancellation()?.let { throw it }
                            onFallbackFailure(fallbackError)
                            throw IllegalStateException(
                                "통역 음성 생성 실패 · 고품질 음성: " +
                                    primaryError.conciseMessage() +
                                    " · Galaxy 오프라인 음성: " +
                                    fallbackError.conciseMessage(),
                                fallbackError,
                            ).also { it.addSuppressed(primaryError) }
                        },
                )
            },
    )
}

/**
 * Once a language has proved that only its prepared Galaxy voice is usable, keep subsequent
 * sentences on that voice until an explicit preparation succeeds for Moonshine again. Retrying a
 * known-failed native primary in five channels at once would bypass constrained-memory startup and
 * add avoidable latency before the same fallback.
 */
internal fun synthesizeSpeechWithStickyFallback(
    primary: SpeechSynthesisEngine,
    fallback: SpeechSynthesisEngine,
    text: String,
    languageTag: String,
    usePreparedFallback: () -> Boolean,
    onPrimaryAudibleFrame: (PcmAudioFrame) -> Unit = {},
    onPrimaryFailure: (Throwable) -> Unit = {},
    onFallbackAudibleFrame: (PcmAudioFrame) -> Unit = {},
    onFallbackFailure: (Throwable) -> Unit = {},
    onExecutionWaitStarted: () -> Unit = {},
    onExecutionStarted: () -> Unit = {},
    primaryFirstFrameTimeoutMillis: Long = DEFAULT_PRIMARY_FIRST_FRAME_TIMEOUT_MILLIS,
    fallbackFirstFrameTimeoutMillis: Long = DEFAULT_FALLBACK_FIRST_FRAME_TIMEOUT_MILLIS,
): Flow<PcmAudioFrame> = if (!usePreparedFallback()) {
    synthesizeSpeechWithFallback(
        primary = primary,
        fallback = fallback,
        text = text,
        languageTag = languageTag,
        onPrimaryAudibleFrame = onPrimaryAudibleFrame,
        onPrimaryFailure = onPrimaryFailure,
        onFallbackAudibleFrame = onFallbackAudibleFrame,
        onFallbackFailure = onFallbackFailure,
        primaryFirstFrameTimeoutMillis = primaryFirstFrameTimeoutMillis,
        fallbackFirstFrameTimeoutMillis = fallbackFirstFrameTimeoutMillis,
        onExecutionWaitStarted = onExecutionWaitStarted,
        onExecutionStarted = onExecutionStarted,
    )
} else {
    fallback.synthesizeWithFirstAudibleFrameWithin(
        text, languageTag, fallbackFirstFrameTimeoutMillis,
        "Galaxy 오프라인 TTS",
        onExecutionWaitStarted, onExecutionStarted,
    )
        .requireNonSilentPcm("Galaxy 오프라인 TTS")
        .onEach { frame ->
            if (frame.hasAudiblePcm16()) onFallbackAudibleFrame(frame)
        }
        .catch { fallbackError ->
            fallbackError.findCancellation()?.let { throw it }
            onFallbackFailure(fallbackError)
            throw IllegalStateException(
                "통역 음성 생성 실패 · Galaxy 오프라인 음성: " +
                    fallbackError.conciseMessage(),
                fallbackError,
            )
        }
}

internal class PartialSpeechSynthesisException(
    cause: Throwable,
) : IllegalStateException(
    "고품질 TTS가 일부 음성을 송출한 뒤 중단되었습니다. 중복 재생하지 않으며 다음 문장에서 복구 음성을 확인합니다: " +
        cause.conciseMessage(),
    cause,
)

private fun SpeechSynthesisEngine.synthesizeWithFirstAudibleFrameWithin(
    text: String,
    languageTag: String,
    timeoutMillis: Long,
    label: String,
    onExecutionWaitStarted: () -> Unit = {},
    onExecutionStarted: () -> Unit = {},
): Flow<PcmAudioFrame> = if (this is ExecutionAwareSpeechSynthesisEngine) {
    flow {
        val executionStarted = CompletableDeferred<Unit>()
        emitAll(
            synthesize(
                text = text,
                languageTag = languageTag,
                onExecutionWaitStarted = onExecutionWaitStarted,
                onExecutionStarted = {
                    executionStarted.complete(Unit)
                    onExecutionStarted()
                },
            )
                .requireFirstAudibleFrameWithin(
                    timeoutMillis = timeoutMillis,
                    label = label,
                    executionStarted = executionStarted,
                    maximumExecutionStartWaitMillis = maximumExecutionStartWaitMillis,
                ),
        )
    }
} else {
    synthesize(text, languageTag).requireFirstAudibleFrameWithin(timeoutMillis, label)
}

private fun Flow<PcmAudioFrame>.requireFirstAudibleFrameWithin(
    timeoutMillis: Long,
    label: String,
    executionStarted: CompletableDeferred<Unit>? = null,
    maximumExecutionStartWaitMillis: Long = 0L,
): Flow<PcmAudioFrame> = flow {
    coroutineScope {
        // Keep the producer failure as the channel close cause. Cancellation type/cause is what
        // decides whether the broadcast stopped; an engine failure must instead retry the same
        // translated text through the fallback voice.
        val frames = Channel<PcmAudioFrame>(capacity = 1)
        val producer = launch {
            try {
                this@requireFirstAudibleFrameWithin.collect(frames::send)
                if (executionStarted?.isCompleted == false) {
                    executionStarted.completeExceptionally(
                        IllegalStateException("$label ended before execution started"),
                    )
                }
                frames.close()
            } catch (error: Throwable) {
                if (executionStarted?.isCompleted == false) {
                    executionStarted.completeExceptionally(error)
                }
                frames.close(error)
            }
        }
        try {
            if (executionStarted != null) {
                require(maximumExecutionStartWaitMillis > 0L)
                val started = withTimeoutOrNull(maximumExecutionStartWaitMillis) {
                    executionStarted.await()
                    true
                } ?: false
                check(started) { "$label execution queue wait timed out" }
            }
            // A native worker can emit silence/noise indefinitely after opening its stream. Keep
            // the attempt deadline absolute across those frames and use the exact same audible
            // boundary as the browser and pipeline. Leading frames are preserved for diagnostics,
            // but they cannot suppress the same-text fallback voice.
            val deadlineNanos = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
            var receivedAnyFrame = false
            while (true) {
                val remainingNanos = deadlineNanos - System.nanoTime()
                if (remainingNanos <= 0L) {
                    throw IllegalStateException("$label 첫 가청 PCM 응답 시간 초과")
                }
                val remainingMillis =
                    ((remainingNanos + NANOS_PER_MILLISECOND - 1L) / NANOS_PER_MILLISECOND)
                        .coerceAtLeast(1L)
                // withTimeoutOrNull returns null only for this local attempt deadline. Cancellation
                // from the surrounding broadcast/session still propagates and must not start fallback.
                val result = withTimeoutOrNull(remainingMillis) { frames.receiveCatching() }
                    ?: throw IllegalStateException("$label 첫 가청 PCM 응답 시간 초과")
                if (result.isClosed) {
                    result.exceptionOrNull()?.let { throw it }
                    throw IllegalStateException(
                        if (receivedAnyFrame) "$label 비가청 PCM 출력" else "$label PCM 출력 없음",
                    )
                }
                val frame = result.getOrThrow()
                frame.requireValidPcm16(label)
                receivedAnyFrame = true
                emit(frame)
                if (frame.hasAudiblePcm16()) break
            }
            for (frame in frames) emit(frame)
        } finally {
            producer.cancel()
            frames.cancel()
        }
    }
}

private fun Flow<PcmAudioFrame>.requireNonSilentPcm(label: String): Flow<PcmAudioFrame> = flow {
    var nonSilent = false
    collect { frame ->
        frame.requireValidPcm16(label)
        if (!nonSilent) nonSilent = frame.hasAudiblePcm16()
        emit(frame)
    }
    check(nonSilent) { "$label 비가청 PCM 출력" }
}

private fun PcmAudioFrame.hasAudiblePcm16(): Boolean {
    if (bytes.isEmpty() || bytes.size % Short.SIZE_BYTES != 0) return false
    var sampleCount = 0L
    var sumSquares = 0.0
    var peak = 0
    var offset = 0
    while (offset < bytes.size) {
        val sample = (
            (bytes[offset + 1].toInt() shl 8) or
                (bytes[offset].toInt() and 0xff)
            ).toShort().toInt()
        val magnitude = if (sample == Short.MIN_VALUE.toInt()) 32_768 else abs(sample)
        sampleCount += 1
        sumSquares += sample.toDouble() * sample.toDouble()
        if (magnitude > peak) peak = magnitude
        offset += Short.SIZE_BYTES
    }
    if (sampleCount == 0L) return false
    val rms = (sqrt(sumSquares / sampleCount) / 32_768.0).toFloat()
    val normalizedPeak = peak / 32_768f
    return rms > MIN_AUDIBLE_PCM_RMS || normalizedPeak > MIN_AUDIBLE_PCM_PEAK
}

private fun PcmAudioFrame.requireValidPcm16(label: String) {
    check(bytes.isNotEmpty() && bytes.size % Short.SIZE_BYTES == 0) {
        "$label PCM 형식 오류"
    }
}

private suspend fun prepareSpeechSynthesisLanguage(
    languageTag: String,
    prepareMoonshine: suspend (String) -> Unit,
    prepareAndroidOffline: suspend (String) -> Unit,
    moonshinePreparationTimeoutMillis: Long,
    androidFallbackPreparationTimeoutMillis: Long,
): SpeechSynthesisLanguagePreparation {
    var providerError: Throwable? = null
    val completed = withTimeoutOrNull(moonshinePreparationTimeoutMillis) {
        try {
            prepareMoonshine(languageTag)
            true
        } catch (error: Throwable) {
            // Keep ordinary provider failures inside this timeout boundary. Besides preserving the
            // exact diagnostic instance, this ensures only cancellation escapes the child scope.
            error.findCancellation()?.let { throw it }
            providerError = error
            false
        }
    }
    val moonshineError = when {
        completed == true -> return SpeechSynthesisLanguagePreparation(
            languageTag = languageTag,
            backend = SpeechSynthesisPreparationBackend.MOONSHINE,
        )
        completed == null -> IllegalStateException(
            "Moonshine TTS 준비 시간 초과: $languageTag " +
                "(${moonshinePreparationTimeoutMillis}ms)",
        )
        else -> requireNotNull(providerError)
    }

    var androidProviderError: Throwable? = null
    val androidCompleted = withTimeoutOrNull(androidFallbackPreparationTimeoutMillis) {
        try {
            prepareAndroidOffline(languageTag)
            true
        } catch (error: Throwable) {
            error.findCancellation()?.let { throw it }
            androidProviderError = error
            false
        }
    }
    if (androidCompleted != true) {
        throw SpeechSynthesisPreparationException(
            languageTag = languageTag,
            moonshineError = moonshineError,
            androidOfflineError = androidProviderError ?: IllegalStateException(
                "$languageTag Galaxy 오프라인 음성 준비 시간 초과 " +
                    "(${androidFallbackPreparationTimeoutMillis}ms). " +
                    "기기 TTS 설정에서 이 언어의 오프라인 음성을 설치한 뒤 다시 준비하세요.",
            ),
        )
    }
    return SpeechSynthesisLanguagePreparation(
        languageTag = languageTag,
        backend = SpeechSynthesisPreparationBackend.ANDROID_OFFLINE,
        moonshineError = moonshineError,
    )
}

private const val DEFAULT_MOONSHINE_PREPARATION_TIMEOUT_MILLIS = 60_000L
private const val DEFAULT_ANDROID_FALLBACK_PREPARATION_TIMEOUT_MILLIS = 15_000L

internal fun Throwable.findCancellation(): CancellationException? {
    var current: Throwable? = this
    repeat(MAX_CAUSE_DEPTH) {
        if (current is CancellationException) return current
        val next = current?.cause
        if (next == null || next === current) return null
        current = next
    }
    return null
}

internal fun Throwable.conciseMessage(): String = message ?: javaClass.simpleName

/**
 * Splits only after translation has completed, so the translator still sees the whole source
 * sentence. The voice engine receives bounded clauses, preferring sentence punctuation, then a
 * comma, then whitespace; CJK text without spaces remains lossless through a character boundary.
 */
internal fun splitTranslatedTextForSpeech(
    text: String,
    maximumCharacters: Int = DEFAULT_TTS_CLAUSE_CHARACTERS,
): List<String> {
    require(maximumCharacters in 40..600)
    var remaining = text.trim()
    require(remaining.isNotEmpty()) { "TTS text is empty" }
    val chunks = mutableListOf<String>()
    while (remaining.length > maximumCharacters) {
        val minimumPreferredIndex = maximumCharacters / 2
        val window = remaining.substring(0, maximumCharacters)
        val splitAfter = sequenceOf(
            window.lastBoundaryAfter(minimumPreferredIndex, STRONG_SPEECH_BOUNDARIES),
            window.lastBoundaryAfter(minimumPreferredIndex, WEAK_SPEECH_BOUNDARIES),
            window.lastWhitespaceAfter(minimumPreferredIndex),
        ).firstOrNull { it > 0 } ?: maximumCharacters
        chunks += remaining.substring(0, splitAfter).trim()
        remaining = remaining.substring(splitAfter).trimStart()
    }
    if (remaining.isNotEmpty()) chunks += remaining
    check(chunks.isNotEmpty() && chunks.all { it.isNotBlank() && it.length <= maximumCharacters })
    check(chunks.joinToString(" ").filterNot(Char::isWhitespace) ==
        text.filterNot(Char::isWhitespace)) {
        "TTS clause splitting changed translated text"
    }
    return chunks
}

private fun String.lastBoundaryAfter(minimumIndex: Int, boundaries: Set<Char>): Int {
    for (index in lastIndex downTo minimumIndex) {
        if (this[index] in boundaries) return index + 1
    }
    return -1
}

private fun String.lastWhitespaceAfter(minimumIndex: Int): Int {
    for (index in lastIndex downTo minimumIndex) {
        if (this[index].isWhitespace()) return index + 1
    }
    return -1
}

private const val MAX_CAUSE_DEPTH = 8
private const val NANOS_PER_MILLISECOND = 1_000_000L
internal const val DEFAULT_PRIMARY_FIRST_FRAME_TIMEOUT_MILLIS = 5_000L
internal const val DEFAULT_FALLBACK_FIRST_FRAME_TIMEOUT_MILLIS = 4_000L
internal const val DEFAULT_TTS_CLAUSE_CHARACTERS = 480
private val STRONG_SPEECH_BOUNDARIES = setOf('.', '!', '?', '。', '！', '？')
private val WEAK_SPEECH_BOUNDARIES = setOf(',', ';', ':', '，', '；', '：', '、')
