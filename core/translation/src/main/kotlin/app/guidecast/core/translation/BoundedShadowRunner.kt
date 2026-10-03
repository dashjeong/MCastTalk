package app.guidecast.core.translation

import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One low-concurrency comparison and one waiting item. Never publishes PCM or replaces primary output. */
class BoundedShadowRunner(scope: CoroutineScope, private val budgetMillis: Long = 1_500L) : Closeable {
    private class Work(val allowed: () -> Boolean, val run: suspend () -> Unit)
    private val pending = Channel<Work>(1)
    private val worker: Job
    init {
        require(budgetMillis in 100..5_000)
        worker = scope.launch {
            for (work in pending) {
                if (!work.allowed()) continue
                try {
                    withTimeout(budgetMillis) {
                        coroutineScope {
                            val task = async { if (work.allowed()) work.run() }
                            val revocation = launch {
                                while (task.isActive) {
                                    if (!work.allowed()) { task.cancel(); break }
                                    delay(25)
                                }
                            }
                            try { task.await() } finally { revocation.cancel() }
                        }
                    }
                } catch (error: CancellationException) {
                    currentCoroutineContext().ensureActive()
                } catch (_: Exception) {
                    // A shadow failure cannot cancel its parent or become a broadcast fallback.
                }
            }
        }
    }
    fun offer(allowed: () -> Boolean, run: suspend () -> Unit): Boolean =
        allowed() && pending.trySend(Work(allowed, run)).isSuccess
    override fun close() { pending.cancel(); worker.cancel() }
}
