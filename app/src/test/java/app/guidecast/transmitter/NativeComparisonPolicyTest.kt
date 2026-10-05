package app.guidecast.transmitter

import org.junit.Assert.*
import org.junit.Test

class NativeComparisonPolicyTest {
    private val openAi = TranslationApiOptions(provider = TranslationApiProvider.OPENAI_REALTIME,
        model = "gpt-realtime-2.1-mini", realtimeAudio = true)
    private fun gemini(model: String) = TranslationApiOptions(provider = TranslationApiProvider.GEMINI_LIVE,
        model = model, baseUrl = "https://generativelanguage.googleapis.com/v1beta")

    @Test fun storedOnDoesNotMeanGeminiComparisonIsSelectedOrRunning() {
        assertTrue(nativeComparisonPresentation(openAi, true).checked)
        for (model in listOf(GEMINI_LIVE_AGENT, GEMINI_LIVE_TRANSLATE)) {
            val selected = nativeComparisonPresentation(gemini(model), true)
            assertFalse(selected.supported); assertFalse(selected.checked)
            assertEquals("비교 학습 · 현재 모델에서 미지원", selected.status)
            assertTrue(selected.detail.contains("음성 통역 중계는 이용"))
        }
        assertTrue(nativeComparisonPresentation(openAi, true).checked)
    }

    @Test fun activeAndPausedGeminiKeepsUnsupportedStatusWithoutAnySettingsVisibilityInput() {
        for (model in listOf(GEMINI_LIVE_AGENT, GEMINI_LIVE_TRANSLATE)) {
            val idle = nativeComparisonPresentation(gemini(model), true)
            val connected = nativeComparisonPresentation(gemini(model), true, true, model)
            assertEquals(idle, connected)
        }
    }

    @Test fun connectionSnapshotMismatchCannotDisplayTheNewSelectionAsRunning() {
        val changed = nativeComparisonPresentation(openAi, true, true, GEMINI_LIVE_AGENT)
        assertFalse(changed.supported); assertFalse(changed.checked)
        assertEquals("비교 학습 · 연결 모델 확인 중", changed.status)
        assertFalse(nativeComparisonPresentation(openAi, true, true, null).checked)
    }

    @Test fun optingOutStillUsesSupportedRouteButDoesNotDisplayOn() {
        val choice = nativeComparisonPresentation(openAi, false)
        assertTrue(choice.supported); assertFalse(choice.checked)
        assertEquals("비교 학습 · 꺼짐", choice.status)
    }
    @Test fun changedComparisonChoiceIsClearlyDeferredToANewRelay() {
        val choice = nativeComparisonPresentation(openAi, true, true, openAi.model, 3, 5)
        assertTrue(choice.supported); assertTrue(choice.checked)
        assertEquals("비교 학습 · 다음 중계 시작부터 적용", choice.status)
        assertTrue(choice.detail.contains("현재 음성 중계는 계속"))
    }
    @Test fun inputAdmissionRequiresAnIdleServiceWithNoCaptureJobAndAKnownClosedRecorder() {
        for (state in listOf("NOT_STARTED", "CLOSED", "FAILED"))
            assertTrue(nativeLearningInputBoundaryKnown(false, InputPhase.IDLE, state))
        for (state in listOf("RECORDING", "UNKNOWN", ""))
            assertFalse(nativeLearningInputBoundaryKnown(false, InputPhase.IDLE, state))
        assertFalse(nativeLearningInputBoundaryKnown(true, InputPhase.IDLE, "CLOSED"))
        for (phase in InputPhase.entries.filter { it != InputPhase.IDLE })
            assertFalse(nativeLearningInputBoundaryKnown(false, phase, "CLOSED"))
    }
}
