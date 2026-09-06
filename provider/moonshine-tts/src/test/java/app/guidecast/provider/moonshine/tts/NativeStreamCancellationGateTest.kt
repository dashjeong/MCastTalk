package app.guidecast.provider.moonshine.tts

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeStreamCancellationGateTest {
    @Test
    fun `cancellation during stream setup is deferred to the producer thread`() {
        val cancelCalls = AtomicInteger(0)
        val setupThread = Thread.currentThread()
        var cancellationThread: Thread? = null
        val gate = NativeStreamCancellationGate {
            cancellationThread = Thread.currentThread()
            cancelCalls.incrementAndGet()
        }

        gate.requestCancellation()

        assertEquals(0, cancelCalls.get())
        gate.markStreaming()
        assertEquals(1, cancelCalls.get())
        assertEquals(setupThread, cancellationThread)

        gate.requestCancellation()
        assertEquals(1, cancelCalls.get())
    }

    @Test
    fun `cancellation after stream setup promptly unblocks a native wait`() {
        val nativeWaitStarted = CountDownLatch(1)
        val nativeWaitReleased = CountDownLatch(1)
        val nativeLane = Executors.newSingleThreadExecutor()
        val gate = NativeStreamCancellationGate {
            nativeWaitReleased.countDown()
        }
        try {
            gate.markStreaming()
            val blockedNativeCall = nativeLane.submit {
                nativeWaitStarted.countDown()
                nativeWaitReleased.await()
            }
            assertTrue(nativeWaitStarted.await(1, TimeUnit.SECONDS))

            gate.requestCancellation()

            blockedNativeCall.get(1, TimeUnit.SECONDS)
            assertEquals(0L, nativeWaitReleased.count)
        } finally {
            nativeWaitReleased.countDown()
            nativeLane.shutdownNow()
        }
    }

    @Test
    fun `normal completion never cancels the native stream`() {
        val cancelCalls = AtomicInteger(0)
        val gate = NativeStreamCancellationGate { cancelCalls.incrementAndGet() }

        gate.markStreaming()
        gate.markFinished()
        gate.requestCancellation()

        assertEquals(0, cancelCalls.get())
    }
}
