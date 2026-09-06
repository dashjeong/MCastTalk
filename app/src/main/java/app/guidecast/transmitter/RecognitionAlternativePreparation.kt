package app.guidecast.transmitter

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

/** An installed primary recognizer must never wait for an optional engine's model download. */
internal suspend fun <T> prepareRecognitionAlternative(
    primaryReady: Boolean,
    inspectInstalled: suspend () -> T,
    prepareRequired: suspend () -> T,
    timeoutMillis: Long = 60_000L,
): Result<T> = try {
    Result.success(withTimeout(timeoutMillis) {
        if (primaryReady) inspectInstalled() else prepareRequired()
    })
} catch (timeout: TimeoutCancellationException) {
    // A provider's own support-query timeout is a failure of that provider, not user stop.
    currentCoroutineContext().ensureActive()
    Result.failure(timeout)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    Result.failure(error)
}
