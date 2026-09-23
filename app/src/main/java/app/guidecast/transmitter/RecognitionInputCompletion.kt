package app.guidecast.transmitter

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

/** Finite-source EOF is distinct from provider completion and from owner cancellation. */
internal class RecognitionInputCompletion {
    private val normalInputEof = AtomicBoolean(false)
    private val providerInputDrained = AtomicBoolean(false)

    fun onNormalInputEof() { normalInputEof.set(true) }
    fun hasReachedEof(): Boolean = normalInputEof.get()
    fun hasDrainedInput(): Boolean = providerInputDrained.get()
    fun permitsAutomaticRestart(): Boolean = !hasReachedEof()

    fun newAttempt(): RecognitionAttemptInputCompletion = RecognitionAttemptInputCompletion {
        // The producer sets normal EOF before closing the channel. Cancellation/error closure is
        // never proof that the finite source reached and was consumed through its normal end.
        if (hasReachedEof()) providerInputDrained.set(true)
    }

    fun requireProviderCompletion(completedNormally: Boolean, failure: Throwable? = null) {
        if (!hasReachedEof() || completedNormally) return
        throw failure ?: IllegalStateException(
            "음성 파일 입력은 종료됐지만 인식기의 마지막 결과가 확정되기 전에 세션이 중단되었습니다.",
        )
    }

    fun requireProgressAfterFiniteEndpoint(attempt: RecognitionAttemptInputCompletion) {
        if (hasReachedEof() && !hasDrainedInput() && attempt.receivedFrameCount() == 0L) {
            throw IllegalStateException("인식기가 남은 음성 파일 입력을 소비하지 않고 종료되었습니다.")
        }
    }
}

/** A provider endpoint may cancel this Flow before the shared PCM queue has been drained. */
internal class RecognitionAttemptInputCompletion(private val onNormalDrain: () -> Unit) {
    private val receivedFrames = AtomicLong(0)

    fun receivedFrameCount(): Long = receivedFrames.get()

    fun <T> track(frames: Flow<T>): Flow<T> = flow {
        frames.collect { frame ->
            receivedFrames.incrementAndGet()
            emit(frame)
        }
        // Reached only after natural upstream EOF, never after take()/owner cancellation/error.
        onNormalDrain()
    }
}
