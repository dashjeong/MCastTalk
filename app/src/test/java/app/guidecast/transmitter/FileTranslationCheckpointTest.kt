package app.guidecast.transmitter

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FileTranslationCheckpointTest {
    private fun entry() = FileLibraryEntry("a".repeat(64), "synthetic.wav", "content://synthetic/audio",
        "a".repeat(64), 2_000, "ko", 0,
        segments = listOf(FileSpeechSegment(0, 0, 1_000, "첫 문장", "ko"), FileSpeechSegment(1, 1_000, 2_000, "둘째 문장", "ko")))

    @Test fun failurePersistsCompletedSentenceAndRetryTranslatesOnlyRemainingSentence() = runBlocking {
        var saved = entry()
        val failed = runCatching {
            translateFileTargetWithCheckpoints(saved, "en", FileTranslationEngine.API, { saved = it }) { _, line ->
                line(0, "First.")
                error("synthetic offline")
            }
        }
        assertTrue(failed.isFailure)
        assertEquals(listOf("First.", ""), saved.translations["en"])
        val complete = translateFileTargetWithCheckpoints(saved, "en", FileTranslationEngine.API, { saved = it }) { pending, line ->
            assertEquals(listOf("둘째 문장"), pending.segments.map { it.text })
            assertEquals("첫 문장", fileTranslationContexts(saved.segments)[pending.segments.single()])
            line(0, "Second.")
            FileScriptTranslation(listOf("Second."), emptyList(), FileTranslationEngine.API)
        }
        assertEquals(listOf("First.", "Second."), complete.translations["en"])
        assertEquals(entry().segments, complete.segments)
        assertFalse(shouldTranslateFileTarget(complete, "en", FileTranslationEngine.API, true))
    }

    @Test fun cancellationPreservesCompletedSentences() = runBlocking {
        var saved = entry()
        runCatching {
            translateFileTargetWithCheckpoints(saved, "ja", FileTranslationEngine.MLKIT, { saved = it }) { _, line ->
                line(0, "保存済み")
                throw CancellationException("synthetic cancel")
            }
        }
        assertEquals(listOf("保存済み", ""), saved.translations["ja"])
    }

    @Test fun failedRefreshPreservesCompletePreviousTranslation() = runBlocking {
        val original = entry().copy(translations = mapOf("en" to listOf("Old first.", "Old second.")),
            translationModes = mapOf("en" to FileTranslationEngine.MLKIT))
        var saved = original
        runCatching {
            translateFileTargetWithCheckpoints(original, "en", FileTranslationEngine.API, { saved = it }) { _, line ->
                line(0, "New first.")
                error("synthetic failure")
            }
        }
        assertEquals(original, saved)
    }

    @Test fun changingEngineDoesNotMixPartialTranslations() = runBlocking {
        val original = entry().copy(translations = mapOf("en" to listOf("Earlier API.", "")),
            translationModes = mapOf("en" to FileTranslationEngine.API))
        val result = translateFileTargetWithCheckpoints(original, "en", FileTranslationEngine.MLKIT, {}) { pending, line ->
            assertEquals(2, pending.segments.size)
            line(0, "Local first."); line(1, "Local second.")
            FileScriptTranslation(listOf("Local first.", "Local second."), emptyList(), FileTranslationEngine.MLKIT)
        }
        assertEquals(listOf("Local first.", "Local second."), result.translations["en"])
    }
}
