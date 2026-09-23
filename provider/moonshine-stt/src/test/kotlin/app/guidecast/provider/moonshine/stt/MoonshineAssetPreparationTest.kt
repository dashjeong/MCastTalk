package app.guidecast.provider.moonshine.stt

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class MoonshineAssetPreparationTest {
    @Test fun cancellationInterruptsTheBlockingDownloaderWorker() = runBlocking {
        val entered = CountDownLatch(1)
        val interrupted = AtomicBoolean(false)
        val job = launch {
            runMoonshineAssetPreparation {
                entered.countDown()
                try { Thread.sleep(60_000) }
                catch (error: InterruptedException) { interrupted.set(true); throw error }
            }
        }
        // Start the coroutine before the synchronous latch wait on this test thread.
        kotlinx.coroutines.yield()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        withTimeout(2_000) { job.cancelAndJoin() }
        assertTrue(interrupted.get())
        assertTrue(job.isCancelled)
    }
}
