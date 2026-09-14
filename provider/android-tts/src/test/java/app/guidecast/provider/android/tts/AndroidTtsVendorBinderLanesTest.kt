package app.guidecast.provider.android.tts

import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class AndroidTtsVendorBinderLanesTest {
    @get:Rule val deadline = Timeout.seconds(15)

    private fun frozen(release: CountDownLatch) {
        while (release.count > 0L) {
            try { release.await() } catch (_: InterruptedException) { /* Uninterruptible Binder fixture. */ }
        }
    }

    @Test fun `cancelled active calls retain bounded vendor lane while sibling runs`(): Unit = runBlocking {
        val lanes = AndroidTtsVendorBinderLanes(maximumVendors = 2, threadsPerVendor = 2, queueCapacity = 2)
        val release = CountDownLatch(1)
        val entered = CountDownLatch(2)
        try {
            val stuck = List(2) {
                launch(Dispatchers.Default) {
                    awaitAndroidTtsBinderCall(lanes.executorFor("bad"), 10_000L, "fixture") {
                        entered.countDown(); frozen(release)
                    }
                }
            }
            withTimeout(1_000L) { while (entered.count > 0) delay(1) }
            withTimeout(1_000L) { stuck.forEach { it.cancelAndJoin() } }
            assertEquals(2, lanes.pendingOperationCount)
            repeat(100) {
                val queued = launch(Dispatchers.Default) {
                    awaitAndroidTtsBinderCall(lanes.executorFor("bad"), 10_000L, "queued") {
                        error("Cancelled queued call entered the vendor")
                    }
                }
                withTimeout(1_000L) { while (lanes.pendingOperationCount < 3) delay(1) }
                queued.cancelAndJoin()
                assertEquals(2, lanes.pendingOperationCount)
            }
            assertEquals(42, awaitAndroidTtsBinderCall(lanes.executorFor("healthy"), 1_000L, "healthy") { 42 })
            assertEquals(2, lanes.retainedLaneCount)
        } finally { release.countDown(); lanes.close() }
    }

    @Test fun `busy vendor bound is enforced and only genuinely idle lane is replaced`(): Unit = runBlocking {
        val lanes = AndroidTtsVendorBinderLanes(maximumVendors = 2, threadsPerVendor = 1, queueCapacity = 1)
        val release = CountDownLatch(1)
        val entered = CountDownLatch(2)
        try {
            listOf("one", "two").forEach { vendor ->
                lanes.executorFor(vendor).execute { entered.countDown(); frozen(release) }
            }
            withTimeout(1_000L) { while (entered.count > 0) delay(1) }
            assertThrows(RejectedExecutionException::class.java) {
                lanes.executorFor("three").execute { error("Exceeded vendor cap") }
            }
            assertEquals(2, lanes.retainedLaneCount)
            release.countDown()
            withTimeout(1_000L) { while (lanes.pendingOperationCount > 0) delay(1) }
            repeat(30) { vendor ->
                assertEquals(vendor, awaitAndroidTtsBinderCall(lanes.executorFor("idle-$vendor"), 1_000L, "idle") { vendor })
                withTimeout(1_000L) { while (lanes.pendingOperationCount > 0) delay(1) }
                assertEquals(2, lanes.retainedLaneCount)
            }
        } finally { release.countDown(); lanes.close() }
    }
}
