package app.guidecast.provider.mlkit.translation

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred

/**
 * Prevents a recreated Service in the same process from entering JNI before an abandoned native
 * task owned by its predecessor has really returned. Static state is process-local on Android.
 */
internal object MlKitWorkerProcessGenerationGate {
    private val lock = Any()
    private var latestClosed = CompletableDeferred(Unit)

    fun beginGeneration(): Generation = synchronized(lock) {
        val predecessorClosed = latestClosed
        val generation = Generation(predecessorClosed)
        latestClosed = generation.closed
        generation
    }

    internal class Generation(
        private val predecessorClosed: CompletableDeferred<Unit>,
    ) {
        private val markedClosed = AtomicBoolean(false)
        internal val closed = CompletableDeferred<Unit>()

        suspend fun awaitPredecessorClosed() {
            predecessorClosed.await()
        }

        fun markClosed() {
            if (!markedClosed.compareAndSet(false, true)) return
            // Preserve the entire generation chain. An empty replacement Service can be destroyed
            // while its predecessor still owns JNI; it must not let a third generation bypass it.
            if (predecessorClosed.isCompleted) {
                closed.complete(Unit)
            } else {
                predecessorClosed.invokeOnCompletion { closed.complete(Unit) }
            }
        }

        /** A thrown native close must leave every successor generation blocked. */
        fun closeRuntimeThenMarkClosed(closeRuntime: () -> Unit) {
            closeRuntime()
            markClosed()
        }
    }
}
