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
}
