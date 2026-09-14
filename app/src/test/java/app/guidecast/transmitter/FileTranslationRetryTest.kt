package app.guidecast.transmitter

import org.junit.Assert.*
import org.junit.Test

class FileTranslationRetryTest {
    private val entry = FileLibraryEntry("a".repeat(64), "synthetic.wav", "content://synthetic/audio",
        "a".repeat(64), 1_000, "ko", 0, translations = mapOf("en" to listOf("Earlier wording.")),
        translationModes = mapOf("en" to FileTranslationEngine.MLKIT))

    @Test fun explicitReconversionAppliesCurrentOptionsButRetryKeepsCompletedTargets() {
        assertTrue(shouldTranslateFileTarget(entry, "en", FileTranslationEngine.MLKIT, retryOnly = false))
        assertFalse(shouldTranslateFileTarget(entry, "en", FileTranslationEngine.MLKIT, retryOnly = true))
        assertTrue(shouldTranslateFileTarget(entry, "ja", FileTranslationEngine.MLKIT, retryOnly = true))
        assertTrue(shouldTranslateFileTarget(entry, "en", FileTranslationEngine.GEMMA, retryOnly = true))
    }

    @Test fun failedRefreshCanBeRetriedEvenWhenPriorSuccessfulTranslationWasPreserved() {
        val failed = entry.copy(qualityNotes = listOf("en 번역을 완료하지 못했습니다. 재생 화면에서 다시 요청할 수 있습니다."))
        assertTrue(shouldTranslateFileTarget(failed, "en", FileTranslationEngine.MLKIT, retryOnly = true))
        assertEquals(listOf("Earlier wording."), failed.translations["en"])
    }
}
