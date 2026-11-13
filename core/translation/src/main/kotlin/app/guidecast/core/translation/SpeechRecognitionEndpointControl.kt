package app.guidecast.core.translation

import app.guidecast.core.stream.PcmAudioFrame
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull

/** One provider attempt's graceful endpoint request, inherited by its input/recognizer flow. */
class SpeechRecognitionEndpointControl : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SpeechRecognitionEndpointControl>

    private val stop = CompletableDeferred<Unit>()
    private val unwrittenInput = AtomicReference<PcmAudioFrame?>(null)
    val stopRequested: Deferred<Unit> get() = stop
    val isStopRequested: Boolean get() = stop.isCompleted

    suspend fun awaitStop() = stop.await()

    fun preserveUnwrittenInput(frame: PcmAudioFrame) {
        unwrittenInput.set(frame)
    }

    fun takeUnwrittenInput(): PcmAudioFrame? = unwrittenInput.getAndSet(null)

    suspend fun requestStopAndAwait(collector: Job, timeoutMillis: Long = 800L): Boolean {
        require(timeoutMillis in 1..800)
        stop.complete(Unit)
        return withTimeoutOrNull(timeoutMillis) {
            collector.join()
            true
        } ?: false
    }
}
