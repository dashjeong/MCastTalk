package app.guidecast.provider.android.tts

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class AndroidTtsBinderCallTest {
    @get:Rule val deadline = Timeout.seconds(15)

    private fun executor() = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(2))

    private fun awaitIgnoringInterrupts(latch: CountDownLatch) {
        while (latch.count > 0L) {
            try { latch.await() } catch (_: InterruptedException) { /* Frozen Binder equivalent. */ }
        }
    }

    @Test fun `frozen transaction cancellation is prompt and cleans late artifact once`(): Unit = runBlocking {
        val executor = executor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val discarded = AtomicInteger()
        try {
            val call = launch(Dispatchers.Default) {
                awaitAndroidTtsBinderCall(executor, 10_000L, "fixture",
                    onAbandonedCompletion = { discarded.incrementAndGet() }) {
                    entered.countDown()
                    awaitIgnoringInterrupts(release)
                    "late result"
                }
                error("Cancelled transaction must not publish a result")
            }
            withTimeout(1_000L) { while (entered.count > 0L) delay(1L) }
            withTimeout(1_000L) { call.cancelAndJoin() }
            assertEquals(0, discarded.get())
            release.countDown()
            withTimeout(1_000L) { while (discarded.get() == 0) delay(1L) }
            assertEquals(1, discarded.get())
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun `repeated cancelled queued calls neither grow threads nor exhaust reserve`(): Unit = runBlocking {
        val executor = executor()
        val release = CountDownLatch(1)
        executor.execute { awaitIgnoringInterrupts(release) }
        val invoked = AtomicInteger()
        try {
            repeat(100) {
                val waiting = launch(Dispatchers.Default) {
                    awaitAndroidTtsBinderCall(executor, 10_000L, "queued") { invoked.incrementAndGet() }
                }
                withTimeout(1_000L) { while (executor.queue.isEmpty()) delay(1L) }
                withTimeout(1_000L) { waiting.cancelAndJoin() }
                assertEquals(0, executor.queue.size)
            }
            assertEquals(1, executor.largestPoolSize)
            assertEquals(0, invoked.get())
            release.countDown()
            assertEquals(42, awaitAndroidTtsBinderCall(executor, 1_000L, "recovered") { 42 })
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun `own binder deadline is a recoverable failure rather than external cancellation`(): Unit = runBlocking {
        val executor = executor()
        val release = CountDownLatch(1)
        try {
            val result = runCatching {
                awaitAndroidTtsBinderCall(executor, 100L, "fixture") { awaitIgnoringInterrupts(release) }
            }
            assertTrue(result.exceptionOrNull() is IllegalStateException)
            assertEquals(null, result.exceptionOrNull()?.cause)
        } finally { release.countDown(); executor.shutdownNow() }
    }
}
