package app.guidecast.transmitter

import app.guidecast.core.stream.PcmAudioFrame
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Request-scoped reporting, inherited through Galaxy's provider queue; never persists audio. */
internal class LiveRecognitionInputContext(
    val onSkipped: () -> Unit,
    val recognitionCopy: (PcmAudioFrame) -> PcmAudioFrame = { it },
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<LiveRecognitionInputContext>
}

/** A stalled recognition consumer must not suspend the independent original-audio input. */
internal suspend fun forwardRecognitionFrame(
    input: SendChannel<PcmAudioFrame>,
    frame: PcmAudioFrame,
): Boolean {
    coroutineContext.ensureActive()
    // No waiting in the microphone collector, including when the recognizer is frozen.
    return input.trySend(frame.copy(localEnqueuedAtNanos = System.nanoTime())).isSuccess
}

/** Discard stale queued STT copies after a stalled consumer resumes; source audio is independent. */
internal fun Flow<PcmAudioFrame>.freshRecognitionFrames(
    onSkipped: () -> Unit,
    onDelivered: (Boolean) -> Unit = {},
    nanoTime: () -> Long = System::nanoTime,
): Flow<PcmAudioFrame> = transform { frame ->
    val admitted = frame.localEnqueuedAtNanos
    if (admitted != null && nanoTime() - admitted >= 160_000_000L) onSkipped()
    else {
        val copy = coroutineContext[LiveRecognitionInputContext]?.recognitionCopy?.invoke(frame) ?: frame
        emit(copy)
        // 진단에는 무음 치환 여부만 전달한다. 진단 실패로 인식 전달을 끊지 않는다.
        runCatching { onDelivered(copy !== frame) }
    }
}

/** A busy source output skips that output only; the same captured frame still reaches STT. */
internal suspend fun publishSourceThenForwardRecognitionFrame(
    frame: PcmAudioFrame,
    publishSource: Boolean,
    publication: app.guidecast.core.stream.ChannelAudioPublicationCoordinator?,
    publish: (PcmAudioFrame) -> Unit,
    recognition: SendChannel<PcmAudioFrame>?,
    recognitionCopy: (PcmAudioFrame) -> PcmAudioFrame = { it },
): Boolean {
    if (publishSource) {
        val lease = publication?.tryAcquireChannel("source")
        if (publication == null || lease != null) {
            try { publish(frame) } finally { lease?.close() }
        }
    }
    return recognition?.let { forwardRecognitionFrame(it, recognitionCopy(frame)) } ?: true
}
