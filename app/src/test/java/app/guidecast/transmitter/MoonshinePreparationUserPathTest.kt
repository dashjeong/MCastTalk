package app.guidecast.transmitter

import app.guidecast.provider.moonshine.tts.MoonshineTtsReadiness
import app.guidecast.provider.moonshine.tts.MoonshineTtsStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoonshinePreparationUserPathTest {
    @Test
    fun failedMoonshineStatusShowsItsExactCauseOnTheModelCard() {
        val status = ttsStatus(
            readiness = MoonshineTtsReadiness.FAILED,
            errorMessage = "QLinearMatMul operator unavailable",
        )

        assertEquals(
            "Moonshine 오류 · QLinearMatMul operator unavailable",
            moonshineTtsFailureDetail(status),
        )
        assertNull(moonshineTtsFailureDetail(status.copy(readiness = MoonshineTtsReadiness.READY)))
        assertNull(moonshineTtsFailureDetail(status.copy(errorMessage = "  ")))
    }

    @Test
    fun preparationResultKeepsPerLanguageCauseInTheViewModelMessage() {
        val report = GalaxySpeechPreparationReport(
            moonshineLanguageTags = setOf("ja"),
            androidFallbackLanguageTags = linkedSetOf("en", "nl"),
            fallbackReasons = mapOf(
                "en" to "native worker exited",
                "nl" to "voice file SHA-256 mismatch",
            ),
        )

        val message = translationModelPreparationMessage(
            readyMessage = "번역·통역 음성 준비 완료",
            speechPreparation = report,
        )

        assertTrue(message.startsWith("번역·통역 음성 준비 완료"))
        assertTrue(message.contains("en: native worker exited"))
        assertTrue(message.contains("nl: voice file SHA-256 mismatch"))
    }

    @Test
    fun preparedGalaxyFallbackCountsAsReadyAndDoesNotBlockBroadcastSelection() {
        val state = TranslationModelUiState(
            selectedLanguageTags = setOf("en"),
            ttsStatuses = listOf(
                ttsStatus(
                    readiness = MoonshineTtsReadiness.FAILED,
                    errorMessage = "native worker exited",
                ),
            ),
            ttsFallbackLanguageTags = setOf("en"),
        )

        assertTrue(state.selectedTtsReady)
        val selection = translationBroadcastSelectionResult(
            selectedLanguageTags = state.selectedLanguageTags,
            speechRecognitionReady = true,
            translationModelsReady = true,
            speechSynthesisReady = state.selectedTtsReady,
            useGemma = true,
        )

        assertTrue(selection.enabled)
        assertNull(selection.message)
    }

    @Test
    fun missingAndroidOnlyVoiceRemainsUnavailableAcrossUiRefreshes() {
        val state = TranslationModelUiState(
            selectedLanguageTags = setOf("es"),
            ttsFallbackLanguageTags = emptySet(),
            ttsUnavailableLanguageReasons = mapOf(
                "es" to "설치된 오프라인 TTS 음성이 없습니다: es",
            ),
        )

        assertFalse(state.ttsReady("es"))
        assertEquals(
            "설치된 오프라인 TTS 음성이 없습니다: es",
            state.ttsUnavailableReason("es"),
        )
        assertFalse(state.selectedTtsReady)
    }

    @Test
    fun failedReadinessWarnsButStillLeavesTheOperatorAbleToBroadcast() {
        val selection = translationBroadcastSelectionResult(
            selectedLanguageTags = setOf("en"),
            speechRecognitionReady = false,
            translationModelsReady = false,
            speechSynthesisReady = false,
            useGemma = true,
        )

        assertTrue(selection.enabled)
        assertTrue(selection.message.orEmpty().contains("한국어 음성인식"))
        assertTrue(selection.message.orEmpty().contains("Gemma 번역"))
        assertTrue(selection.message.orEmpty().contains("통역 음성"))
        assertTrue(selection.message.orEmpty().contains("운영자가 결정"))
    }

    @Test
    fun emptyLanguageSelectionCannotEnableAnEmptyTranslationBroadcast() {
        val selection = translationBroadcastSelectionResult(
            selectedLanguageTags = emptySet(),
            speechRecognitionReady = true,
            translationModelsReady = true,
            speechSynthesisReady = true,
            useGemma = true,
        )

        assertFalse(selection.enabled)
        assertEquals("통역 언어를 하나 이상 선택하세요.", selection.message)
    }

    private fun ttsStatus(
        readiness: MoonshineTtsReadiness,
        errorMessage: String?,
    ) = MoonshineTtsStatus(
        languageTag = "en",
        displayName = "영어",
        voiceId = "kokoro_af_heart",
        readiness = readiness,
        errorMessage = errorMessage,
    )
}
