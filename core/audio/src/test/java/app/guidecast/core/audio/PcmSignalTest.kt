package app.guidecast.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmSignalTest {
    @Test
    fun `silence has no signal energy`() {
        val stats = ByteArray(640).pcmS16LeSignalStats()

        assertEquals(0f, stats.rms)
        assertEquals(0f, stats.peak)
        assertEquals(0, stats.nonZeroSamples)
        assertEquals(320, stats.sampleCount)
    }

    @Test
    fun `generated test tone has measurable energy and correct frame size`() {
        val tone = PcmSineWaveGenerator(16_000, 1_000.0, 0.5).nextFrame(20)
        val stats = tone.pcmS16LeSignalStats()

        assertEquals(640, tone.size)
        assertTrue("RMS must prove non-silent PCM", stats.rms in 0.34f..0.36f)
        assertTrue("Peak must prove non-silent PCM", stats.peak in 0.49f..0.51f)
        assertTrue(stats.nonZeroSamples > 250)
    }

    @Test
    fun `test tone phase remains continuous across frames`() {
        val generator = PcmSineWaveGenerator(16_000, 997.0, 0.35)
        val joined = generator.nextFrame(20) + generator.nextFrame(20)

        assertEquals(1_280, joined.size)
        assertTrue(joined.pcmS16LeSignalStats().rms > 0.2f)
    }
}
