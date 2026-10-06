package app.guidecast.transmitter

import org.junit.Assert.*
import org.junit.Test

class InterpreterRelayTargetsTest {
    @Test fun legacySelectionAndFiveTargetsKeepIndependentDeviceChoice() {
        assertEquals(listOf("en"), InterpreterRelayOptions().targetLanguageTags)
        val five = InterpreterRelayOptions().withTargets(listOf("en", "ja", "zh", "ru", "vi"))
        val monitor = five.copy(target = "ja")
        assertEquals(five.targetLanguageTags, monitor.targetLanguageTags)
        assertEquals("ja", monitor.target)
        assertEquals("ja", monitor.withTargets(listOf("ja", "vi")).target)
        assertEquals("vi", monitor.withTargets(listOf("vi", "ru")).target)
    }
    @Test fun sourceChangeRetainsOtherLanguagesAndRestoresAValidMonitor() {
        val changed = InterpreterRelayOptions().withTargets(listOf("en", "ja", "ru")).withSource("en-US")
        assertEquals(listOf("ja", "ru"), changed.targetLanguageTags)
        assertEquals("ja", changed.target)
        assertEquals(listOf("ko"), InterpreterRelayOptions().withSource("en-US").targetLanguageTags)
    }
    @Test fun sixDuplicateAndSourceLanguageTargetsAreRejected() {
        for (invalid in listOf(emptyList(), listOf("en", "en"), listOf("ko"), listOf("en", "ja", "ru", "vi", "de", "es"))) {
            try { InterpreterRelayOptions().withTargets(invalid); fail("Invalid targets accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun endingOneLanguageLeavesOtherLanguageAndNewSessionCaptionsAlive() {
        fun row(id: Long, target: String, owner: Long) = TranslationTranscriptLine(id, "source", id,
            translations = mapOf(target to "translation"), nativeAudioSessionId = owner,
            liveSegmentLanguage = target, liveOutputState = LiveOutputState.GENERATING)
        val rows = listOf(row(1, "en", 7), row(2, "ja", 7), row(3, "en", 8))
        val ended = terminalizeNativeAudioTranscripts(rows, 7, NativeAudioEndReason.FAILURE, "en")
        assertEquals(LiveOutputState.INCOMPLETE, ended[0].liveOutputState)
        assertEquals(rows[1], ended[1]); assertEquals(rows[2], ended[2])
        val all = terminalizeNativeAudioTranscripts(rows, 7, NativeAudioEndReason.CONSENT_REVOKED)
        assertEquals(LiveOutputState.CANCELLED, all[1].liveOutputState)
        assertEquals(rows[2], all[2])
    }
}
