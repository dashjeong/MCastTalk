package app.guidecast.provider.android.tts

import java.util.concurrent.Callable
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/**
 * A coroutine timeout cannot interrupt a vendor Binder transaction. Bound the number of abandoned
 * calls with a fixed executor and detach the caller; never create a replacement thread per timeout.
 * A permanently blocked vendor still requires that vendor or the app process to restart.
 */
internal suspend fun <T> awaitAndroidTtsBinderCall(
    executor: Executor,
    timeoutMillis: Long,
    operationName: String,
    onAbandonedCompletion: () -> Unit = {},
    operation: () -> T,
): T = try {
    withTimeout(timeoutMillis) {
        suspendCancellableCoroutine { continuation ->
            val abandoned = AtomicBoolean(false)
            val completed = AtomicBoolean(false)
            val discarded = AtomicBoolean(false)
            fun discardIfFinished() {
                if (abandoned.get() && completed.get() && discarded.compareAndSet(false, true)) {
                    runCatching(onAbandonedCompletion)
                }
            }
            val task = object : FutureTask<T>(Callable {
                try {
                    operation()
                } finally {
                    completed.set(true)
                    discardIfFinished()
                }
            }) {
                override fun done() {
                    if (!isCancelled) continuation.resumeWith(runCatching { get() })
                }
            }
            continuation.invokeOnCancellation {
                abandoned.set(true)
                task.cancel(true)
                // Cancelled queued calls must not fill the bounded queue behind a frozen worker.
                removeQueuedAndroidTtsBinderTask(executor, task)
                discardIfFinished()
            }
            try {
                executor.execute(task)
                if (task.isCancelled) removeQueuedAndroidTtsBinderTask(executor, task)
            } catch (failure: RuntimeException) {
                continuation.resumeWith(Result.failure(failure))
            }
        }
    }
} catch (timeout: TimeoutCancellationException) {
    currentCoroutineContext().ensureActive()
    throw IllegalStateException("Android TTS $operationName timed out")
}

private fun removeQueuedAndroidTtsBinderTask(executor: Executor, task: Runnable) {
    when (executor) {
        is ThreadPoolExecutor -> executor.remove(task)
        is RemovableTtsBinderExecutor -> executor.removeQueued(task)
    }
}
