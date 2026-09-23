package app.guidecast.transmitter

import app.guidecast.core.stream.PcmAudioFrame
import app.guidecast.core.translation.RecognizedUtterance
import app.guidecast.core.translation.SpeechRecognitionConfig
import app.guidecast.core.translation.SpeechRecognitionEngine
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal data class VoiceNoteLiveSnapshot(val lines: List<VoiceNoteLine>, val partial: String)

/** One recording owns one reducer. Provider retries cannot append the same final sequence twice. */
internal class VoiceNoteLiveTranscript(private val startedAtNanos: Long, private val language: String) {
    private val committed = linkedSetOf<Long>()
    private val pending = linkedMapOf<Long, RecognizedUtterance>()
    private val lines = mutableListOf<VoiceNoteLine>()
    fun accept(value: RecognizedUtterance, durationMs: Long): VoiceNoteLiveSnapshot {
        if (value.sequence in committed) return snapshot()
        if (value.isRetracted) pending.remove(value.sequence)
        else if (value.isFinal) {
            pending.remove(value.sequence)
            append(value, durationMs)
        } else pending[value.sequence] = value
        check(pending.size <= 32) { "Too many pending recognition sequences" }
        return snapshot()
    }
    fun finish(durationMs: Long): VoiceNoteLiveSnapshot {
        pending.values.toList().forEach { append(it, durationMs) }
        pending.clear()
        return snapshot()
    }
    fun snapshot() = VoiceNoteLiveSnapshot(lines.toList(), pending.values.joinToString("\n") { it.text })
    private fun append(value: RecognizedUtterance, durationMs: Long) {
        if (durationMs <= 0 || value.text.isBlank() || value.sequence in committed) return
        check(lines.size < 2_000) { "Voice-note transcript limit reached" }
        val previousEnd = lines.lastOrNull()?.endMs ?: 0L
        val capturedMs = ((value.capturedAtElapsedRealtimeNanos - startedAtNanos) / 1_000_000).coerceAtLeast(0)
        val start = maxOf(previousEnd, capturedMs).coerceAtMost(durationMs)
        // Providers expose utterance onset, not an exact media end. Keep that limitation explicit.
        lines += VoiceNoteLine(start, durationMs.coerceAtLeast(start), value.text.trim(), language, timingEstimated = true)
        committed += value.sequence
    }
}

/** Shared preparation/lease path for microphone recording and real-PCM device verification. */
internal suspend fun <T> withPreparedVoiceNoteRecognition(
    app: GuideCastApplication,
    language: String,
    block: suspend (SpeechRecognitionEngine) -> T,
): T = withPreparedLocalSpeechRecognition(app, language, block)

internal suspend fun <T> withPreparedLocalSpeechRecognition(
    app: GuideCastApplication,
    language: String,
    block: suspend (SpeechRecognitionEngine) -> T,
): T = app.withTranslationBackendUse {
    val capability = app.speechRecognitionEngine.capability(language)
    if (!capability.available) throw FileTranscriptionException(
        "선택한 언어의 기기 내 음성 인식을 사용할 수 없습니다. 지원 언어와 시스템 오프라인 언어팩을 확인하세요.",
    )
    val owner = app.beginSettingsPreparation(language, emptySet())
    try {
        val ready = try {
            withTimeout(10 * 60_000L) {
                app.prepareSpeechRecognitionWithProcessAdmission(language, serializeWithAllColdLoads = true, owner = owner)
            }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw FileTranscriptionException("음성 인식 준비 시간이 초과됐습니다. 인터넷·저장 공간을 확인한 뒤 다시 시도하세요.")
        }
        if (!ready.isReady) throw FileTranscriptionException(
            "음성 인식을 준비하지 못했습니다. 인터넷·저장 공간·오프라인 언어팩을 확인하고 다시 시도하세요.",
        )
        block(app.speechRecognitionEngine)
    } finally { app.endPreparation(owner) }
}

/** EOF, provider failure and cancellation all retain the last usable hypothesis once. */
internal suspend fun collectVoiceNoteLiveTranscript(
    engine: SpeechRecognitionEngine,
    frames: Flow<PcmAudioFrame>,
    language: String,
    startedAtNanos: Long,
    durationMs: () -> Long,
    availability: Flow<Boolean>? = null,
    onSnapshot: suspend (VoiceNoteLiveSnapshot) -> Unit,
): Unit = coroutineScope {
    val transcript = VoiceNoteLiveTranscript(startedAtNanos, language)
    // Galaxy waits for operator reconnect after repeated failures instead of throwing. A note
    // must surface that failure and preserve its WAV, rather than displaying "listening" forever.
    val statusMonitor = availability?.let { states -> launch {
        states.first { !it }
        throw IllegalStateException("Voice-note recognition requires recovery")
    } }
    try {
        engine.recognize(frames, SpeechRecognitionConfig(language)).collect { value ->
            onSnapshot(transcript.accept(value, durationMs()))
        }
    } finally {
        statusMonitor?.cancel()
        withContext(NonCancellable) { onSnapshot(transcript.finish(durationMs())) }
    }
}
