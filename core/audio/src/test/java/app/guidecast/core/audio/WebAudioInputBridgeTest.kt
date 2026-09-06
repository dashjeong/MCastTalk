package app.guidecast.core.audio

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WebAudioInputBridgeTest {

    @Before
    @After
    fun cleanup() {
        WebAudioInputBridge.reset()
    }

    @Test
    fun initiallyDisconnectedAndNotStreaming() {
        assertFalse(WebAudioInputBridge.isSpeakerConnected)
        assertFalse(WebAudioInputBridge.isStreamingActive())
    }

    @Test
    fun emissionRequiresConnectedState() {
        val validFrame = PcmFrame(
            bytes = ByteArray(640),
            sampleRateHz = 16_000,
            capturedAtElapsedRealtimeNanos = 1000L,
        )
        assertFalse("Emission should fail when disconnected", WebAudioInputBridge.emitFrame(validFrame))
    }

    @Test
    fun validatesPcmAttributes() {
        val sessionGen = WebAudioInputBridge.openSession()
        assertTrue(WebAudioInputBridge.isSpeakerConnected)

        // Invalid sample rate
        val wrongRate = PcmFrame(ByteArray(640), sampleRateHz = 48_000, capturedAtElapsedRealtimeNanos = 100L)
        assertFalse(WebAudioInputBridge.emitFrame(wrongRate, sessionGen))

        // Empty bytes
        val emptyBytes = PcmFrame(ByteArray(0), sampleRateHz = 16_000, capturedAtElapsedRealtimeNanos = 200L)
        assertFalse(WebAudioInputBridge.emitFrame(emptyBytes, sessionGen))

        // Odd number of bytes (invalid for 16-bit PCM)
        val oddBytes = PcmFrame(ByteArray(641), sampleRateHz = 16_000, capturedAtElapsedRealtimeNanos = 300L)
        assertFalse(WebAudioInputBridge.emitFrame(oddBytes, sessionGen))

        // Exceeding 16KB max frame
        val oversized = PcmFrame(ByteArray(20_000), sampleRateHz = 16_000, capturedAtElapsedRealtimeNanos = 400L)
        assertFalse(WebAudioInputBridge.emitFrame(oversized, sessionGen))

        // Valid frame
        val validFrame = PcmFrame(ByteArray(640), sampleRateHz = 16_000, capturedAtElapsedRealtimeNanos = 500L)
        assertTrue(WebAudioInputBridge.emitFrame(validFrame, sessionGen))

        // Out of order timestamp
        val outOfOrder = PcmFrame(ByteArray(640), sampleRateHz = 16_000, capturedAtElapsedRealtimeNanos = 400L)
        assertFalse("Frame with timestamp older than last frame must be rejected", WebAudioInputBridge.emitFrame(outOfOrder, sessionGen))
    }

    @Test
    fun staleSessionGenerationRejected() {
        val oldGen = WebAudioInputBridge.openSession()
        val newGen = WebAudioInputBridge.openSession()

        val frame = PcmFrame(ByteArray(640), sampleRateHz = 16_000, capturedAtElapsedRealtimeNanos = 1000L)
        assertFalse("Frame with old generation must be rejected", WebAudioInputBridge.emitFrame(frame, oldGen))
        assertTrue("Frame with current generation must be accepted", WebAudioInputBridge.emitFrame(frame, newGen))
    }

    @Test
    fun staleSessionCloseDoesNotDisconnectNewerSession() {
        val session1 = WebAudioInputBridge.openSession()
        assertTrue(WebAudioInputBridge.isSpeakerConnected)

        val session2 = WebAudioInputBridge.openSession()
        assertTrue(WebAudioInputBridge.isSpeakerConnected)

        // Session 1 disconnects late
        val closedStale = WebAudioInputBridge.closeSession(session1)
        assertFalse("Stale session close must return false", closedStale)
        assertTrue("Bridge must remain connected for session 2", WebAudioInputBridge.isSpeakerConnected)

        // Session 2 disconnects
        val closedCurrent = WebAudioInputBridge.closeSession(session2)
        assertTrue("Current session close must return true", closedCurrent)
        assertFalse("Bridge is now disconnected", WebAudioInputBridge.isSpeakerConnected)
    }

    @Test
    fun rapidReconnectDropsBufferedFramesFromPreviousGeneration() = runBlocking(Dispatchers.Default) {
        val oldGeneration = WebAudioInputBridge.openSession()
        val firstFrameEnteredCollector = CompletableDeferred<Unit>()
        val releaseCollector = CompletableDeferred<Unit>()
        val replacementFrameReceived = CompletableDeferred<PcmFrame>()
        var skipInFlightOldFrame = true
        var staleFramesAfterReconnect = 0
        val collector = launch {
            WebAudioInputBridge.frames().collect { frame ->
                if (skipInFlightOldFrame) {
                    skipInFlightOldFrame = false
                    firstFrameEnteredCollector.complete(Unit)
                    releaseCollector.await()
                } else if (frame.bytes[0] == 1.toByte()) {
                    staleFramesAfterReconnect += 1
                } else if (frame.bytes[0] == 9.toByte()) {
                    replacementFrameReceived.complete(frame)
                }
            }
        }
        try {
            WebAudioInputBridge.subscriptionCount.first { it > 0 }
            assertTrue(
                WebAudioInputBridge.emitFrame(
                    PcmFrame(ByteArray(640) { 1 }, 16_000, 1L),
                    oldGeneration,
                ),
            )
            withTimeout(1_000L) { firstFrameEnteredCollector.await() }

            // Fill the bounded lag buffer while the first old frame is already in-flight.
            repeat(64) { index ->
                assertTrue(
                    WebAudioInputBridge.emitFrame(
                        PcmFrame(ByteArray(640) { 1 }, 16_000, 2L + index),
                        oldGeneration,
                    ),
                )
            }
            val replacementGeneration = WebAudioInputBridge.openSession()
            val replacement = PcmFrame(ByteArray(640) { 9 }, 16_000, 1L)
            assertTrue(WebAudioInputBridge.emitFrame(replacement, replacementGeneration))
            releaseCollector.complete(Unit)

            val received = withTimeout(1_000L) { replacementFrameReceived.await() }
            assertArrayEquals(replacement.bytes, received.bytes)
            assertEquals(0, staleFramesAfterReconnect)
        } finally {
            releaseCollector.complete(Unit)
            collector.cancelAndJoin()
        }
    }

    @Test
    fun replayZeroPreventsStaleHistoricalPlaybackOnNewSubscription() = runBlocking {
        val gen = WebAudioInputBridge.openSession()
        val historicalFrame = PcmFrame(ByteArray(640) { 1 }, sampleRateHz = 16_000, capturedAtElapsedRealtimeNanos = 1000L)
        WebAudioInputBridge.emitFrame(historicalFrame, gen)

        // New subscriber connects AFTER frame was emitted
        val receivedStale = withTimeoutOrNull(50L) {
            WebAudioInputBridge.frames().first()
        }
        assertNull("replay=0 must not replay historical frames to newly connecting subscribers", receivedStale)
    }

    @Test
    fun liveStreamingFlow() = runBlocking(Dispatchers.Default) {
        val gen = WebAudioInputBridge.openSession()
        val testBytes = ByteArray(640) { (it % 100).toByte() }
        val frame = PcmFrame(bytes = testBytes, sampleRateHz = 16_000, capturedAtElapsedRealtimeNanos = 2000L)

        var received: PcmFrame? = null
        val job = launch {
            received = WebAudioInputBridge.frames().first()
        }

        // Wait until subscription is actively registered in SharedFlow without arbitrary delays
        WebAudioInputBridge.subscriptionCount.first { it > 0 }

        assertTrue(WebAudioInputBridge.emitFrame(frame, gen))
        job.join()

        assertTrue(WebAudioInputBridge.isStreamingActive())
        assertArrayEquals(testBytes, received?.bytes)
        assertEquals(16_000, received?.sampleRateHz)
        assertEquals(2000L, received?.capturedAtElapsedRealtimeNanos)
    }
}
