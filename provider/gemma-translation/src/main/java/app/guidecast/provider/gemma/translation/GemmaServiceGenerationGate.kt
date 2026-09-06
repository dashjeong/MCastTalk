package app.guidecast.provider.gemma.translation

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred

/**
 * Orders Android Service instances that share the private Gemma Linux process.
 *
 * Unbinding a bound service does not prove that a cancelled LiteRT call has returned or that its
 * multi-gigabyte runtime has closed. Android may construct a replacement Service in that same
 * process first. Every generation therefore waits for the previous generation's real native close
 * before it is allowed to touch [GemmaInferenceRuntime]. Empty/cancelled intermediate generations
 * remain chained, so they cannot accidentally let a later generation overtake the old runtime.
 */
internal class GemmaServiceGenerationGate {
    private val stateLock = Any()
    private var tail = completedSignal()

    fun beginGeneration(): Generation = synchronized(stateLock) {
        val generation = Generation(predecessorClosed = tail)
        tail = generation.closed
        generation
    }

    internal class Generation(
        private val predecessorClosed: CompletableDeferred<Unit>,
    ) {
        private val closeConfirmed = AtomicBoolean(false)
        internal val closed = CompletableDeferred<Unit>()

        suspend fun awaitPredecessorClosed() {
            predecessorClosed.await()
        }

        /** Call only after this generation's initialized native runtime has actually closed. */
        fun confirmClosed() {
            if (!closeConfirmed.compareAndSet(false, true)) return
            if (predecessorClosed.isCompleted) {
                closed.complete(Unit)
            } else {
                predecessorClosed.invokeOnCompletion { closed.complete(Unit) }
            }
        }
    }

    private companion object {
        fun completedSignal() = CompletableDeferred(Unit)
    }
}

/** One coordinator per `:gemma_inference` Linux process, shared by replacement Services. */
internal object GemmaProcessGenerationGate {
    private val delegate = GemmaServiceGenerationGate()

    fun beginGeneration(): GemmaServiceGenerationGate.Generation = delegate.beginGeneration()
}
