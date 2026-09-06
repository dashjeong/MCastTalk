package app.guidecast.provider.android.tts

import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PcmFrameBridgeEquivalenceTest {
    @Test fun `irregular callback partitions preserve byte order with independent frame ownership`() = runBlocking {
        val random = Random(29)
        repeat(200) {
            val pcm = random.nextBytes(random.nextInt(1, 8_000) * 2)
            val bridge = BoundedPcmFrameBridge(24_000, 20, 64)
            var offset = 0
            while (offset < pcm.size) {
                val end = minOf(pcm.size, offset + random.nextInt(1, 750) * 2)
                val callback = pcm.copyOfRange(offset, end)
                bridge.offer(callback)
                callback.fill(0) // Caller may immediately recycle its input, never the queued frames.
                offset = end
            }
            bridge.finish()
            val actual = mutableListOf<ByteArray>()
            for (frame in bridge.frames) actual += frame
            val expected = fixedPcm16MonoFrames(pcm, bridge.frameBytes).toList()
            assertNull(bridge.recoveryStartByteOffset())
            assertEquals(expected.size, actual.size)
            expected.indices.forEach { index -> assertArrayEquals(expected[index], actual[index]) }
        }
    }

    @Test fun `overflow while completing a staged frame recovers only the unaccepted suffix`() = runBlocking {
        val pcm = ByteArray(240) { it.toByte() }
        val bridge = BoundedPcmFrameBridge(1_000, 20, 1)
        bridge.offer(pcm.copyOfRange(0, 50)) // frame 0 accepted, 10 bytes staged
        bridge.offer(pcm.copyOfRange(50, pcm.size)) // frame 1 cannot enter full queue
        bridge.finish()
        assertEquals(40, bridge.recoveryStartByteOffset())
        val prefix = bridge.frames.receive()
        assertArrayEquals(pcm.copyOfRange(0, 40), prefix)
        assertTrue(bridge.frames.receiveCatching().isClosed)
        val recovered = fixedPcm16MonoFrames(pcm, 40, 40).toList()
        assertArrayEquals(pcm, (listOf(prefix) + recovered).fold(byteArrayOf()) { a, b -> a + b })
    }
}
