package app.guidecast.transmitter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionInputProfileTest {
    @Test
    fun `s23 also uses semantic sentence completion without timer cuts`() {
        val profile = recognitionInputProfile(
            manufacturer = "samsung",
            model = "SM-S911N",
            sdkInt = 35,
            isLowRamDevice = false,
        )

        assertFalse(profile.interpretationPolicy.allowContinuousSpeechCommit)
        assertTrue(profile.interpretationPolicy.recognizerEndpointEnabled)
        assertFalse(profile.interpretationPolicy.allowSemanticContinuousSpeechCommit)
        assertTrue(profile.interpretationPolicy.requireAcousticPauseForBoundary)
        assertTrue(profile.interpretationPolicy.requireCompleteKoreanMeaningForUnpunctuatedPause)
        assertEquals(8, profile.pcmBufferCapacity)
        assertFalse(profile.sentenceCompletionMode)
    }

    @Test
    fun `note9 and s21 keep the same semantic policy with a larger pcm reserve`() {
        listOf("SM-N960N", "SM-G991N").forEach { model ->
            val profile = recognitionInputProfile(
                manufacturer = "Samsung",
                model = model,
                sdkInt = 35,
                isLowRamDevice = false,
            )

            assertFalse(profile.interpretationPolicy.allowContinuousSpeechCommit)
            assertFalse(profile.interpretationPolicy.allowSemanticContinuousSpeechCommit)
            assertTrue(profile.interpretationPolicy.requireAcousticPauseForBoundary)
            assertTrue(profile.interpretationPolicy.requireCompleteKoreanMeaningForUnpunctuatedPause)
            assertEquals(16, profile.pcmBufferCapacity)
            assertTrue(profile.sentenceCompletionMode)
        }
    }

    @Test
    fun `source language contract matches official Gemma Translator set`() {
        listOf("ko-KR", "en-US", "ja-JP", "zh-CN", "es-ES", "ar-SA").forEach { tag ->
            assertTrue(normalizeSourceLanguage(tag) in GUIDECAST_SOURCE_LANGUAGE_BASE_TAGS)
            assertEquals(normalizeSourceLanguage(tag), requireSupportedSourceLanguage(tag))
        }
        assertTrue(shouldUseMoonshineForSource("ko-KR"))
        listOf("en-US", "ja-JP", "zh-CN", "es-ES", "ar-SA").forEach { tag ->
            assertFalse(shouldUseMoonshineForSource(tag))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `Dutch remains output only`() {
        requireSupportedSourceLanguage("nl-NL")
    }
}
