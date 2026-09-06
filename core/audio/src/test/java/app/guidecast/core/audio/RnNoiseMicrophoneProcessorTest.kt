package app.guidecast.core.audio

import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class RnNoiseMicrophoneProcessorTest {
    private class Passthrough : NoiseFrameFilter {
        override fun process(frame: FloatArray) = Unit
        override fun close() = Unit
    }
    private fun pcm(frequency: Double): ByteArray = ByteArray(48_000 * 2).also { bytes ->
        for (i in 0 until 48_000) {
            val sample = (sin(2 * PI * frequency * i / 48_000) * 12000).toInt()
            bytes[i * 2] = sample.toByte(); bytes[i * 2 + 1] = (sample shr 8).toByte()
        }
    }
    @Test fun arbitraryOddChunksPreserveOrderingRateAndIndependentBufferOwnership() {
        val input = pcm(1000.0)
        val expected = RnNoiseMicrophoneProcessor(Passthrough()).use { it.process(input) }
        val actual = ByteArrayOutputStream()
        RnNoiseMicrophoneProcessor(Passthrough()).use { processor ->
            var offset = 0
            while (offset < input.size) {
                val chunk = input.copyOfRange(offset, minOf(offset + 731, input.size))
                actual.write(processor.process(chunk))
                offset += chunk.size
                chunk.fill(0)
            }
        }
        assertEquals(32_000, expected.size)
        assertArrayEquals(expected, actual.toByteArray())
    }
    @Test fun antiAliasFilterRetainsSpeechAndRejectsAboveNyquistNoise() {
        val speech = RnNoiseMicrophoneProcessor(Passthrough()).use { it.process(pcm(1000.0)) }.pcmS16LeSignalStats()
        val noise = RnNoiseMicrophoneProcessor(Passthrough()).use { it.process(pcm(12000.0)) }.pcmS16LeSignalStats()
        assertTrue(speech.rms > 0.2f)
        assertTrue("alias rejection: $noise / $speech", noise.rms < speech.rms * 0.03f)
    }
    @Test fun nanFailureFallsBackOnceWithoutDroppingPcmOrChangingItsClock() {
        var failures = 0
        val input = pcm(500.0)
        val expected = RnNoiseMicrophoneProcessor(Passthrough()).use { it.process(input) }
        val broken = object : NoiseFrameFilter {
            override fun process(frame: FloatArray) { frame.fill(Float.NaN) }
            override fun close() = Unit
        }
        val actual = RnNoiseMicrophoneProcessor(broken) { failures++ }.use { it.process(input) }
        assertEquals(1, failures)
        assertArrayEquals(expected, actual)
    }
}
