package app.guidecast.transmitter

import android.os.SystemClock
import app.guidecast.core.stream.PcmAudioFrame
import app.guidecast.core.translation.RecognizedUtterance
import app.guidecast.core.translation.SpeechRecognitionConfig
import app.guidecast.core.translation.SpeechRecognitionEngine
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import java.util.ArrayDeque
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** Bounded script accumulator; provider sequence IDs, never text equality, prevent duplicates. */
internal class PreparedFileTranscript(
    private val originNanos: Long,
    private val language: String,
    private val mediaOnset: ((Long) -> Long?)? = null,
) {
    private val finalized = mutableSetOf<Long>()
    private val pending = linkedMapOf<Long, RecognizedUtterance>()
    private val segments = mutableListOf<FileSpeechSegment>()
    private val pendingOnsets = mutableMapOf<Long, Long>()
    private var characters = 0L
    fun accept(value: RecognizedUtterance, processedMs: Long) {
        if (value.sequence in finalized) return
        if (value.isRetracted) {
            pending.remove(value.sequence)
            pendingOnsets.remove(value.sequence)
        } else {
            if (value.sequence !in pendingOnsets && value.text.isNotBlank()) {
                mediaOnset?.invoke(value.capturedAtElapsedRealtimeNanos)?.let { pendingOnsets[value.sequence] = it }
            }
            if (value.isFinal) { pending.remove(value.sequence); append(value, processedMs) }
            else pending[value.sequence] = value
        }
        check(pending.size <= 32) { "Too many pending file recognition sequences" }
    }
    fun count() = segments.size
    fun hasPendingText() = pending.values.any { it.text.isNotBlank() }
    fun finish(processedMs: Long): List<FileSpeechSegment> {
        pending.values.forEach { append(it, processedMs) }
        pending.clear()
        pendingOnsets.clear()
        return segments.toList()
    }
    private fun append(value: RecognizedUtterance, processedMs: Long) {
        if (processedMs <= 0 || value.sequence in finalized || value.text.isBlank()) return
        if (segments.size >= 20_000 || characters + value.text.length > 20_000_000) {
            throw FileTranscriptionException("전사 결과가 저장 한도를 넘었습니다. 파일을 나누어 변환하세요.")
        }
        val previous = segments.lastOrNull()
        val onset = pendingOnsets.remove(value.sequence) ?: if (mediaOnset != null) {
            // A provider returning a first result older than the bounded map has no reliable
            // onset. Keep an estimated contiguous start instead of inventing a wall-clock offset.
            mediaOnset.invoke(value.capturedAtElapsedRealtimeNanos) ?: (previous?.endMs ?: 0)
        } else ((value.capturedAtElapsedRealtimeNanos - originNanos) / 1_000_000).coerceAtLeast(0)
        val start = maxOf(previous?.startMs ?: 0, onset).coerceAtMost(processedMs)
        // End times are callback-time estimates. A later result may establish that the next
        // utterance began earlier; do not clip its beginning to the previous inference delay.
        if (previous != null && previous.endMs > start) {
            segments[segments.lastIndex] = previous.copy(endMs = start)
        }
        segments += FileSpeechSegment(segments.size.toLong(), start, processedMs, value.text.trim(), language,
            timingEstimated = true)
        characters += value.text.length
        finalized += value.sequence
    }
}

/** Map real capture timestamps to media positions without changing the recognizer's real clock. */
internal class FileMediaTimeline(private val maximumFrames: Int = 6_000) {
    private data class Frame(val capturedAtNanos: Long, val startMs: Long, val endMs: Long)
    private val frames = ArrayDeque<Frame>()
    private var evicted = false
    init { require(maximumFrames > 0) }

    @Synchronized fun record(capturedAtNanos: Long, startMs: Long, endMs: Long) {
        require(startMs >= 0 && endMs >= startMs)
        require(frames.peekLast()?.capturedAtNanos?.let { capturedAtNanos >= it } != false)
        frames.addLast(Frame(capturedAtNanos, startMs, endMs))
        while (frames.size > maximumFrames) { frames.removeFirst(); evicted = true }
    }

    @Synchronized fun mediaMillis(capturedAtNanos: Long): Long? {
        val iterator = frames.descendingIterator()
        while (iterator.hasNext()) {
            val frame = iterator.next()
            if (capturedAtNanos >= frame.capturedAtNanos) {
                return (frame.startMs + (capturedAtNanos - frame.capturedAtNanos) / 1_000_000)
                    .coerceAtMost(frame.endMs)
            }
        }
        return if (evicted) null else frames.peekFirst()?.startMs
    }
}

/**
 * Reuses the prepared app recognizer for decoded file PCM, at a bounded real-time rate. Some live
 * providers never close on EOF, so input completion explicitly bounds the final-result drain.
 * Synthetic endpoint silence is not counted as original media duration or written to the file.
 */
internal suspend fun transcribePreparedFileFrames(
    decoded: Flow<FilePcmFrame>,
    engine: SpeechRecognitionEngine,
    language: String,
    durationMs: () -> Long?,
    availability: Flow<Boolean>? = null,
    nowNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
    finalDrainMillis: Long = 8_000,
    onProgress: (FileTranscriptionProgress) -> Unit = {},
): FileTranscriptionResult = coroutineScope {
    require(finalDrainMillis > 0)
    val origin = nowNanos()
    val mediaTimeline = FileMediaTimeline()
    val transcript = PreparedFileTranscript(origin, language, mediaTimeline::mediaMillis)
    val processed = AtomicLong(0)
    val segmentCount = AtomicInteger(0)
    val inputFinished = CompletableDeferred<Unit>()
    val frames = channelFlow {
        var lastProgress = -1L
        suspend fun deliver(bytes: ByteArray, startMs: Long, endMs: Long) {
            val capturedAt = nowNanos()
            mediaTimeline.record(capturedAt, startMs, endMs)
            try {
                withTimeout(10_000) { send(PcmAudioFrame(bytes, capturedAt)) }
            } catch (_: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                throw FileTranscriptionException("음성 인식기가 파일 입력에 응답하지 않습니다. 언어·모델을 확인하고 재작업하세요.")
            }
            delay((bytes.size / 32L).coerceAtLeast(1))
        }
        decoded.collect { frame ->
            currentCoroutineContext().ensureActive()
            check(frame.bytes.isNotEmpty() && frame.bytes.size % 2 == 0 && frame.startMs >= 0 && frame.endMs >= frame.startMs)
            // Decoder frames are normally 20 ms; also bound callers supplying a larger frame.
            var offset = 0
            while (offset < frame.bytes.size) {
                val end = minOf(offset + 640, frame.bytes.size)
                val startMs = minOf(frame.endMs, frame.startMs + offset / 32L)
                val endMs = minOf(frame.endMs, frame.startMs + end / 32L)
                processed.updateAndGet { maxOf(it, endMs) }
                deliver(frame.bytes.copyOfRange(offset, end), startMs, endMs)
                offset = end
            }
            if (processed.get() / 250 != lastProgress) {
                lastProgress = processed.get() / 250
                onProgress(FileTranscriptionProgress(processed.get(), durationMs(), segmentCount.get(), "파일을 들으며 받아쓰는 중"))
            }
        }
        repeat(100) { deliver(ByteArray(640), processed.get(), processed.get()) }
        inputFinished.complete(Unit)
    }.buffer(0)
    val collector = launch {
        engine.recognize(frames, SpeechRecognitionConfig(language)).collect { value ->
            transcript.accept(value, processed.get())
            segmentCount.set(transcript.count())
            onProgress(FileTranscriptionProgress(processed.get(), durationMs(), segmentCount.get(), "문장을 저장할 준비를 하고 있습니다"))
        }
    }
    val statusMonitor = availability?.let { states -> launch {
        states.first { !it }
        throw FileTranscriptionException("파일 음성 인식이 중단됐습니다. 원본 파일은 유지됩니다. 언어·모델을 확인한 뒤 재작업하세요.")
    } }
    try {
        select<Unit> {
            inputFinished.onAwait { }
            collector.onJoin {
                if (!inputFinished.isCompleted) throw FileTranscriptionException(
                    "파일 끝에 도달하기 전에 음성 인식이 종료됐습니다. 원본 파일은 유지됩니다. 재작업해 주세요.",
                )
            }
        }
        val drainExpired = withTimeoutOrNull(finalDrainMillis) { collector.join(); true } == null
        if (drainExpired) {
            collector.cancelAndJoin()
            if (transcript.hasPendingText()) throw FileTranscriptionException(
                "마지막 문장의 음성 인식이 확정되지 않아 변환을 완료하지 않았습니다. 원본 파일은 유지됩니다. 언어·모델을 확인한 뒤 재작업하세요.",
            )
        }
        val result = transcript.finish(processed.get())
        FileTranscriptionResult(result, language, buildList {
            add("기기 내 음성 인식으로 생성했습니다. 구간 시각은 추정값이며 단어별 정확한 시각은 제공하지 않습니다.")
            if (result.isEmpty()) add("인식된 음성이 없습니다. 원음과 원문 언어를 확인하세요.")
        }, maxOf(durationMs() ?: 0, processed.get()))
    } finally {
        collector.cancel()
        statusMonitor?.cancel()
    }
}
