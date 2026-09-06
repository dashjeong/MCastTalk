package app.guidecast.transmitter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveSpeechActivityDetectorTest {
    @Test
    fun `requires sustained speech and releases after a short natural pause`() {
        val detector = AdaptiveSpeechActivityDetector()
        assertFalse(detector.observe(0.08f, 0.20f, 0.ms).active)
        assertFalse(detector.observe(0.08f, 0.20f, 40.ms).active)
        assertTrue(detector.observe(0.08f, 0.20f, 80.ms).active)
        assertTrue(detector.observe(0.001f, 0.003f, 300.ms).active)
        assertFalse(detector.observe(0.001f, 0.003f, 450.ms).active)
    }

    @Test
    fun `one frame click never creates a speech segment`() {
        val detector = AdaptiveSpeechActivityDetector()
        assertFalse(detector.observe(0.5f, 0.9f, 0.ms).active)
        assertFalse(detector.observe(0.001f, 0.003f, 40.ms).active)
        assertFalse(detector.observe(0.001f, 0.003f, 400.ms).active)
    }

    @Test
    fun `steady low ambient noise is learned without opening the gate`() {
        val detector = AdaptiveSpeechActivityDetector()
        repeat(100) { index ->
            assertFalse(detector.observe(0.003f, 0.008f, (index * 40L).ms).active)
        }
    }

    @Test
    fun `quiet voiced syllables still open the gate after low ambient sound`() {
        val detector = AdaptiveSpeechActivityDetector()
        repeat(40) { index ->
            detector.observe(alternatingFrame(amplitude = 60), (index * 20L).ms)
        }

        assertFalse(detector.observe(voicedFrame(amplitude = 450), 800L.ms).active)
        assertFalse(detector.observe(voicedFrame(amplitude = 450), 820L.ms).active)
        assertTrue(detector.observe(voicedFrame(amplitude = 450), 840L.ms).active)
    }

    @Test
    fun `click and white noise do not reset last voice activity while releasing`() {
        val detector = AdaptiveSpeechActivityDetector()
        detector.observe(voicedFrame(amplitude = 2_000), 0L.ms)
        detector.observe(voicedFrame(amplitude = 2_000), 20L.ms)
        val voice = detector.observe(voicedFrame(amplitude = 2_000), 40L.ms)
        assertTrue(voice.active)
        assertEquals(40L.ms, voice.lastSpeechAtNanos)

        val afterClick = detector.observe(clickFrame(), 100L.ms)
        val afterNoise = detector.observe(whiteNoiseFrame(), 200L.ms)
        val released = detector.observe(whiteNoiseFrame(), 400L.ms)

        assertTrue(afterClick.active)
        assertTrue(afterNoise.active)
        assertFalse(released.active)
        assertEquals(40L.ms, released.lastSpeechAtNanos)
    }

    private fun voicedFrame(amplitude: Int): ByteArray = pcmFrame { index ->
        if ((index / 20) % 2 == 0) amplitude else -amplitude
    }

    private fun alternatingFrame(amplitude: Int): ByteArray = pcmFrame { index ->
        if (index % 2 == 0) amplitude else -amplitude
    }

    private fun clickFrame(): ByteArray = pcmFrame { index ->
        if (index == 160) 20_000 else 0
    }

    private fun whiteNoiseFrame(): ByteArray {
        var state = 0x13579BDF
        return pcmFrame {
            state = state * 1_103_515_245 + 12_345
            ((state ushr 16) and 0x1fff) - 0x1000
        }
    }

    private fun pcmFrame(sample: (Int) -> Int): ByteArray = ByteArray(320 * 2).also { bytes ->
        repeat(320) { index ->
            val value = sample(index).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            bytes[index * 2] = (value and 0xff).toByte()
            bytes[index * 2 + 1] = ((value shr 8) and 0xff).toByte()
        }
    }

    private val Int.ms: Long get() = toLong().ms
    private val Long.ms: Long get() = this * 1_000_000L
}
