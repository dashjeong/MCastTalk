package app.guidecast.transmitter

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptHudPresentationTest {
    @Test fun recordedCaptionLanguagesRemainAvailableAfterRelayTargetChanges() {
        val recorded = TranslationTranscriptLine(1, "합성 원문", 1,
            translations = mapOf("en" to "Synthetic English", "ja" to "合成文"))
        assertEquals(listOf("en", "ja", "vi"), transcriptHudLanguageTags(listOf("vi"), listOf(recorded)))
    }

    @Test fun fiveRelayTargetsAreAvailableBeforeCaptionsArriveWithoutDuplicateChoices() {
        assertEquals(listOf("en", "ja", "vi", "zh", "zh-TW"),
            transcriptHudLanguageTags(listOf("en", "ja", "zh", "zh-TW", "vi", "en"), emptyList()))
    }

    @Test fun unavailableRememberedDisplayChoiceFallsBackToSourceInsteadOfBlankHud() {
        assertEquals(setOf("source"), transcriptHudSelection(setOf("fr"), listOf("en", "ja")))
        assertEquals(setOf("source", "ja"),
            transcriptHudSelection(setOf("source", "ja", "fr"), listOf("en", "ja")))
    }
    @Test fun hudTranslationViewDefaultsToAllAndUsesLanguageLabels() {
        assertEquals(listOf("en", "ja", "zh"), relayCaptionDisplayLanguages(listOf("en", "ja", "zh"), null))
        assertEquals(listOf("ja"), relayCaptionDisplayLanguages(listOf("en", "ja", "zh"), "ja"))
        assertEquals("영어", relayCaptionLanguageLabel("en"))
        assertEquals("일본어", relayCaptionLanguageLabel("ja"))
        assertEquals("중국어(간체)", relayCaptionLanguageLabel("zh"))
    }

    @Test fun individualLanguageViewDoesNotRepeatUnrelatedNativeSourceRows() {
        val en = TranslationTranscriptLine(1, "첫 발화", 1,
            translations = mapOf("en" to "English"), liveSegmentLanguage = "en", nativeAudioSessionId = 1)
        val ja = TranslationTranscriptLine(2, "다른 발화", 2,
            translations = mapOf("ja" to "Japanese"), liveSegmentLanguage = "ja", nativeAudioSessionId = 1)
        val groups = relayCaptionPresentation(listOf(en, ja))
        assertEquals(2, relayCaptionDisplayGroups(groups, null).size)
        assertEquals("첫 발화", relayCaptionDisplayGroups(groups, "en").single().sourceText)
        assertEquals(mapOf("en" to "English"), en.translations)
        assertEquals(mapOf("ja" to "Japanese"), ja.translations)
    }
}
