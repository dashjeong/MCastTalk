package app.guidecast.transmitter

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** A slow or failing caption voice cannot suspend the original PCM producer. */
internal class MenuCaptionWorkQueue(
    scope: CoroutineScope,
    capacity: Int = 16,
    timeoutMillis: Long = 30_000,
    private val onFailure: () -> Unit,
    process: suspend (MenuBroadcastCaption) -> Unit,
) {
    private val queue = Channel<MenuBroadcastCaption>(capacity)
    private val worker: Job = scope.launch {
        try {
            for (line in queue) {
                try { withTimeout(timeoutMillis) { process(line) } }
                catch (_: TimeoutCancellationException) { onFailure() }
                catch (_: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    onFailure()
                }
                catch (_: Exception) { onFailure() }
            }
        } finally { queue.close() }
    }
    fun offer(line: MenuBroadcastCaption): Boolean = queue.trySend(line).isSuccess
    fun finishInput() { queue.close() }
    suspend fun drain(timeoutMillis: Long = 30_000): Boolean {
        finishInput()
        return try { withTimeout(timeoutMillis) { worker.join() }; true }
        catch (_: TimeoutCancellationException) { false }
    }
    suspend fun cancel() { queue.close(); worker.cancelAndJoin() }
}
