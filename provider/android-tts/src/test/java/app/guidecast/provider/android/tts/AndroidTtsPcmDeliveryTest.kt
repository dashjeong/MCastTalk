package app.guidecast.provider.android.tts

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidTtsPcmDeliveryTest {
    @Test
    fun `fixed frame splitter preserves pcm and pads only final frame`() {
        val frameBytes = 40
        val pcm = ByteArray(frameBytes * 2 + 6) { index -> (index % 113).toByte() }

        val frames = fixedPcm16MonoFrames(pcm, frameBytes).toList()

        assertEquals(listOf(frameBytes, frameBytes, frameBytes), frames.map(ByteArray::size))
        assertArrayEquals(pcm, frames.flattenBytes().copyOf(pcm.size))
        assertTrue(frames.last().drop(6).all { it == 0.toByte() })
    }

    @Test
    fun `pacer spaces frames by their sample duration and never catches up in a burst`() = runBlocking {
        var nowNanos = 1_000_000_000L
        val waitTargets = mutableListOf<Long>()
        val pacer = SampleDurationPacer(
            sampleRateHz = 1_000,
            nowNanos = { nowNanos },
            waitUntilNanos = { target ->
                waitTargets += target
                if (nowNanos < target) nowNanos = target
            },
        )
        val twentyMillisOfPcm = 40

        val first = pacer.awaitTurn(twentyMillisOfPcm)
        val second = pacer.awaitTurn(twentyMillisOfPcm)
        nowNanos += 100_000_000L // Simulate a slow downstream collector.
        val delayedThird = pacer.awaitTurn(twentyMillisOfPcm)
        val fourth = pacer.awaitTurn(twentyMillisOfPcm)

        assertEquals(1_000_000_000L, first)
        assertEquals(20_000_000L, second - first)
        assertEquals(100_000_000L, delayedThird - second)
        assertEquals(20_000_000L, fourth - delayedThird)
        assertEquals(
            listOf(1_020_000_000L, 1_040_000_000L, 1_140_000_000L),
            waitTargets,
        )
    }

    @Test
    fun `overflow recovers exact unsent wav suffix without replaying accepted prefix`() = runBlocking {
        val bridge = BoundedPcmFrameBridge(
            sampleRateHz = 1_000,
            frameDurationMillis = 20,
            capacity = 2,
        )
        val fullWavPcm = ByteArray(bridge.frameBytes * 5) { index -> (index % 127).toByte() }

        bridge.offer(fullWavPcm)
        bridge.finish()

        val livePrefix = mutableListOf<ByteArray>()
        for (frame in bridge.frames) livePrefix += frame
        val recoveryStart = requireNotNull(bridge.recoveryStartByteOffset())
        val recoveredSuffix = fixedPcm16MonoFrames(
            pcm = fullWavPcm,
            frameBytes = bridge.frameBytes,
            startByteOffset = recoveryStart,
        ).toList()

        assertEquals(2, livePrefix.size)
        assertEquals(bridge.frameBytes * 2, recoveryStart)
        assertArrayEquals(fullWavPcm, (livePrefix + recoveredSuffix).flattenBytes())
    }

    @Test
    fun `callback chunks are reassembled and final partial frame is retained`() = runBlocking {
        val bridge = BoundedPcmFrameBridge(
            sampleRateHz = 1_000,
            frameDurationMillis = 20,
            capacity = 4,
        )
        val pcm = ByteArray(54) { index -> (index + 1).toByte() }

        bridge.offer(pcm.copyOfRange(0, 14))
        bridge.offer(pcm.copyOfRange(14, pcm.size))
        bridge.finish()

        val frames = mutableListOf<ByteArray>()
        for (frame in bridge.frames) frames += frame

        assertNull(bridge.recoveryStartByteOffset())
        assertEquals(listOf(40, 40), frames.map(ByteArray::size))
        assertArrayEquals(pcm, frames.flattenBytes().copyOf(pcm.size))
        assertTrue(frames.last().drop(14).all { it == 0.toByte() })
    }

    @Test
    fun `missing audio callbacks request full wav recovery`() = runBlocking {
        val bridge = BoundedPcmFrameBridge(
            sampleRateHz = 1_000,
            frameDurationMillis = 20,
            capacity = 2,
        )

        bridge.finish()
        for (ignored in bridge.frames) Unit

        assertEquals(0, bridge.recoveryStartByteOffset())
    }

    @Test
    fun `fixed frame staging across irregular chunks pads final frame with exact zeros`() = runBlocking {
        val bridge = BoundedPcmFrameBridge(
            sampleRateHz = 1_000,
            frameDurationMillis = 20,
            capacity = 10,
        )
        val frameBytes = bridge.frameBytes
        val pcm = ByteArray(96) { index -> (index + 1).toByte() }

        bridge.offer(pcm.copyOfRange(0, 10))
        bridge.offer(pcm.copyOfRange(10, 46))
        bridge.offer(pcm.copyOfRange(46, 70))
        bridge.offer(pcm.copyOfRange(70, 96))
        bridge.finish()

        val frames = mutableListOf<ByteArray>()
        for (frame in bridge.frames) frames += frame

        assertEquals(3, frames.size)
        assertTrue("All frames must be exactly frameBytes", frames.all { it.size == frameBytes })
        assertNull(bridge.recoveryStartByteOffset())
        assertArrayEquals(pcm, frames.flattenBytes().copyOf(96))

        val lastFrame = frames.last()
        assertTrue("Final frame tail must be zero padded", lastFrame.drop(16).all { it == 0.toByte() })
    }

    @Test
    fun `queue frames have independent array ownership and are never mutated or reused`() = runBlocking {
        val bridge = BoundedPcmFrameBridge(
            sampleRateHz = 1_000,
            frameDurationMillis = 20,
            capacity = 5,
        )
        val frameBytes = bridge.frameBytes

        val chunk1 = ByteArray(30) { 1.toByte() }
        bridge.offer(chunk1)

        val chunk2 = ByteArray(30) { 2.toByte() }
        bridge.offer(chunk2)

        val frame1 = bridge.frames.receive()
        assertEquals(frameBytes, frame1.size)
        val frame1Snapshot = frame1.clone()

        val chunk3 = ByteArray(80) { 3.toByte() }
        bridge.offer(chunk3)

        val frame2 = bridge.frames.receive()
        val frame3 = bridge.frames.receive()

        bridge.finish()
        val frame4 = bridge.frames.receive()

        val allFrames = listOf(frame1, frame2, frame3, frame4)
        for (i in allFrames.indices) {
            for (j in i + 1 until allFrames.size) {
                assertTrue("Frames must be separate array instances", allFrames[i] !== allFrames[j])
            }
        }
        assertArrayEquals("Emitted queue frame must not be corrupted by subsequent offers", frame1Snapshot, frame1)
    }

    @Test
    fun `overflow recovery offset prevents duplicate playback and preserves exact suffix`() = runBlocking {
        val bridge = BoundedPcmFrameBridge(
            sampleRateHz = 1_000,
            frameDurationMillis = 20,
            capacity = 2,
        )
        val frameBytes = bridge.frameBytes
        val fullPcm = ByteArray(frameBytes * 6) { index -> (index + 10).toByte() }

        bridge.offer(fullPcm)
        bridge.finish()

        val liveFrames = mutableListOf<ByteArray>()
        for (frame in bridge.frames) liveFrames += frame

        assertEquals("Queue should have accepted exactly 2 frames", 2, liveFrames.size)
        val recoveryOffset = requireNotNull(bridge.recoveryStartByteOffset())
        assertEquals("Recovery offset must be exactly the accepted bytes", 2 * frameBytes, recoveryOffset)

        val recoveredSuffix = fixedPcm16MonoFrames(
            pcm = fullPcm,
            frameBytes = frameBytes,
            startByteOffset = recoveryOffset,
        ).toList()

        assertEquals("Recovered suffix should contain remaining 4 frames", 4, recoveredSuffix.size)
        val combinedAudio = (liveFrames + recoveredSuffix).flattenBytes()
        assertEquals("Total audio bytes must match original without duplication or omission", fullPcm.size, combinedAudio.size)
        assertArrayEquals("Audio must match byte-for-byte with zero duplicate reading", fullPcm, combinedAudio)
    }

    private fun List<ByteArray>.flattenBytes(): ByteArray =
        fold(ByteArray(0)) { output, frame -> output + frame }
}
