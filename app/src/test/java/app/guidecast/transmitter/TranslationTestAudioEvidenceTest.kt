package app.guidecast.transmitter

import app.guidecast.core.translation.SynthesizedPcmStats
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationTestAudioEvidenceTest {
    @Test
    fun emptySynthesisNeverPassesEvenWhenPlaybackReportedBytes() {
        val evidence = evidence()
            .withNonSilentPlaybackWrite(640)
            .withSynthesis(pcm(frameCount = 0, byteCount = 0, sampleCount = 0))

        assertFalse(evidence.hasNonSilentSynthesis)
        assertTrue(evidence.hasSuccessfulPlaybackWrite)
        assertFalse(evidence.passed)
    }

    @Test
    fun allZeroSynthesisNeverPasses() {
        val evidence = evidence()
            .withNonSilentPlaybackWrite(640)
            .withSynthesis(
                pcm(
                    frameCount = 1,
                    byteCount = 640,
                    sampleCount = 320,
                    nonZeroSampleCount = 0,
                    rms = 0f,
                    peak = 0f,
                ),
            )

        assertFalse(evidence.hasNonSilentSynthesis)
        assertFalse(evidence.passed)
    }

    @Test
    fun nonSilentSynthesisWaitsForSuccessfulAudioTrackWrite() {
        val synthesized = evidence().withSynthesis(
            pcm(
                frameCount = 2,
                byteCount = 1_280,
                sampleCount = 640,
                nonZeroSampleCount = 500,
                rms = 0.1f,
                peak = 0.4f,
            ),
        )

        assertTrue(synthesized.hasNonSilentSynthesis)
        assertFalse(synthesized.hasSuccessfulPlaybackWrite)
        assertFalse(synthesized.passed)
        assertTrue(synthesized.withNonSilentPlaybackWrite(640).passed)
    }

    @Test
    fun subThresholdNoiseDoesNotPass() {
        val evidence = evidence()
            .withNonSilentPlaybackWrite(640)
            .withSynthesis(
                pcm(
                    frameCount = 1,
                    byteCount = 640,
                    sampleCount = 320,
                    nonZeroSampleCount = 320,
                    rms = 0.0005f,
                    peak = MIN_TRANSLATION_TEST_PEAK,
                ),
            )

        assertFalse(evidence.passed)
    }

    @Test
    fun playbackFailureRevokesEarlierWriteEvidence() {
        val evidence = evidence()
            .withSynthesis(
                pcm(
                    frameCount = 1,
                    byteCount = 640,
                    sampleCount = 320,
                    nonZeroSampleCount = 320,
                    rms = 0.1f,
                    peak = 0.4f,
                ),
            )
            .withNonSilentPlaybackWrite(640)

        assertTrue(evidence.passed)
        assertFalse(evidence.withPlaybackWriteFailure().passed)
    }

    private fun evidence() = TranslationTestAudioEvidence(
        sessionId = 1,
        languageTag = "en",
    )

    private fun pcm(
        frameCount: Long,
        byteCount: Long,
        sampleCount: Long,
        nonZeroSampleCount: Long = 0,
        rms: Float = 0f,
        peak: Float = 0f,
    ) = SynthesizedPcmStats(
        frameCount = frameCount,
        byteCount = byteCount,
        sampleCount = sampleCount,
        nonZeroSampleCount = nonZeroSampleCount,
        rms = rms,
        peak = peak,
    )
}
