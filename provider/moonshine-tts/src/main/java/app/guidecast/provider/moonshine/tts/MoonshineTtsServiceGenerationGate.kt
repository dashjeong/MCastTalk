package app.guidecast.provider.moonshine.tts

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred

/**
 * Serializes Android Service instances inside the private TTS process.
 *
 * Android may construct a replacement bound-service instance while a cancelled JNI call from the
 * previous instance is still returning. A new runtime must not load native voices until the old
 * runtime has closed. Each generation therefore waits for its predecessor, and completion remains
 * chained even when an intermediate generation is created and destroyed without doing work.
 */
internal class MoonshineTtsServiceGenerationGate {
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
        private val closeRequested = AtomicBoolean(false)
        internal val closed = CompletableDeferred<Unit>()

        suspend fun awaitPredecessorClosed() {
            predecessorClosed.await()
        }

        fun markClosed() {
            if (!closeRequested.compareAndSet(false, true)) return
            if (predecessorClosed.isCompleted) {
                closed.complete(Unit)
            } else {
                predecessorClosed.invokeOnCompletion { closed.complete(Unit) }
            }
        }

        /** A thrown native close must leave the successor gate closed. */
        fun closeRuntimeThenMarkClosed(closeRuntime: () -> Unit) {
            closeRuntime()
            markClosed()
        }
    }

    private companion object {
        fun completedSignal() = CompletableDeferred(Unit)
    }
}

/** One coordinator per private TTS Linux process, shared by all Android Service instances. */
internal object MoonshineTtsProcessGenerationGate {
    private val delegate = MoonshineTtsServiceGenerationGate()

    fun beginGeneration(): MoonshineTtsServiceGenerationGate.Generation =
        delegate.beginGeneration()
}
