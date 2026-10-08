package app.guidecast.core.audio

import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** A replacement capture cannot acquire a route while the previous capture is still releasing it. */
internal class AudioCaptureLifetime {
    private val mutex = Mutex()

    suspend fun <T> withSession(block: suspend () -> T): T {
        mutex.lock()
        try { return block() }
        finally { mutex.unlock() }
    }
}

/** Non-blocking reads wait briefly for data, while remaining cancellable when input is stopped. */
internal suspend fun readCapturePcm(read: () -> Int): Int {
    currentCoroutineContext().ensureActive()
    val count = read()
    currentCoroutineContext().ensureActive()
    if (count == 0) delay(10)
    return count
}

/** The read worker must finish before its processor, effects and recorder are released. */
internal suspend fun shutDownCaptureReadWorker(
    worker: Job, stopRead: () -> Unit, releaseAfterWorker: () -> Unit,
) = withContext(NonCancellable) {
    // AudioRecord.stop wakes a blocking read. An already-stopped recorder may reject stop.
    runCatching(stopRead)
    worker.cancelAndJoin()
    releaseAfterWorker()
}
