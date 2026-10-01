package app.guidecast.transmitter

import org.junit.Assert.*
import org.junit.Test

class VoicePreparationPresentationTest {
    @Test fun unpreparedVoiceIsNotReportedAsFailure() {
        val state = TranslationModelUiState(voicePreferences = mapOf("zh-TW" to SpeechVoicePreference.GOOGLE))
        assertEquals(VoicePreparationPhase.NOT_PREPARED, state.voicePreparationPresentation("zh-TW").phase)
        assertEquals(VoicePreparationPhase.CHECKING, state.copy(isBusy = true).voicePreparationPresentation("zh-TW").phase)
    }

    @Test fun failedChannelCannotReuseStaleReadyBadgeOrLookLikeDownloading() {
        val state = TranslationModelUiState(
            isBusy = true,
            ttsFallbackLanguageTags = setOf("zh-TW", "en"),
            ttsUnavailableLanguageReasons = mapOf("zh-TW" to "voice data missing"),
        )
        assertEquals(VoicePreparationPhase.FAILED, state.voicePreparationPresentation("zh-TW").phase)
        assertEquals(VoicePreparationPhase.READY, state.voicePreparationPresentation("en").phase)
    }

    @Test fun installedAndroidVoiceIsNotMislabeledAsSamsungOrMoonshine() {
        val state = TranslationModelUiState(ttsFallbackLanguageTags = setOf("zh-TW"))
        val status = state.voicePreparationPresentation("zh-TW")
        assertEquals(VoicePreparationPhase.READY, status.phase)
        assertFalse(status.text.contains("Galaxy"))
        assertFalse(status.text.contains("Moonshine"))
    }
}
