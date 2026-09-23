package app.guidecast.transmitter

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FileTranslationCheckpointTest {
    private fun entry() = FileLibraryEntry("a".repeat(64), "synthetic.wav", "content://synthetic/audio",
        "a".repeat(64), 2_000, "ko", 0,
        segments = listOf(FileSpeechSegment(0, 0, 1_000, "첫 문장", "ko"), FileSpeechSegment(1, 1_000, 2_000, "둘째 문장", "ko")))

    @Test fun sameLanguageUsesSourceWithoutCallingAnyTranslationOrRecordingAnEngine() = runBlocking {
        for (mode in FileTranslationEngine.entries) {
            val result = translateFileTargetWithCheckpoints(entry(), "ko", mode, {}) { _, _ ->
                error("No model preparation, API configuration, translation or review should run")
            }
            assertEquals(entry().segments, result.segments)
            assertFalse(result.translations.containsKey("ko"))
            assertFalse(result.translationModes.containsKey("ko"))
            assertTrue(result.qualityNotes.any { it.contains("실행하지 않았습니다") })
            assertFalse(shouldTranslateFileTarget(result, "ko", mode, retryOnly = true))
            assertNull(sourceOnlyFileTranslation(entry(), "ko")?.engine)
        }
    }

    @Test fun sameLanguageRemovesLegacyCopiedApiCompletionButPreservesDistinctSavedWording() = runBlocking {
        val copied = entry().copy(translations = mapOf("ko" to entry().segments.map { it.text }),
            translationModes = mapOf("ko" to FileTranslationEngine.API), qualityNotes = listOf("설정한 API 경로 · 완료"))
        val result = translateFileTargetWithCheckpoints(copied, "ko", FileTranslationEngine.API, {}) { _, _ -> error("Unexpected API call") }
        assertTrue(result.translations.isEmpty())
        assertTrue(result.translationModes.isEmpty())
        assertFalse(result.qualityNotes.any { it.startsWith("설정한 API 경로") })
        val edited = copied.copy(translations = mapOf("ko" to listOf("사용자가 보존한 표현", "둘째 문장")))
        val preserved = translateFileTargetWithCheckpoints(edited, "ko", FileTranslationEngine.GEMMA, {}) { _, _ -> error("Unexpected AI call") }
        assertEquals(edited.translations, preserved.translations)
    }

    @Test fun mixedLanguageResumeCopiesRemainingSourceWithoutClaimingAnotherApiRun() = runBlocking {
        val mixed = entry().copy(segments = listOf(entry().segments.first(), FileSpeechSegment(1, 1_000, 2_000, "Second.", "en")),
            translations = mapOf("en" to listOf("First.", "")), translationModes = mapOf("en" to FileTranslationEngine.API))
        val result = translateFileTargetWithCheckpoints(mixed, "en", FileTranslationEngine.API, {}) { pending, onLine ->
            val sourceOnly = requireNotNull(sourceOnlyFileTranslation(pending, "en"))
            assertNull(sourceOnly.engine)
            sourceOnly.lines.forEachIndexed { index, text -> onLine(index, text) }
            sourceOnly
        }
        assertEquals(listOf("First.", "Second."), result.translations["en"])
        assertEquals(FileTranslationEngine.API, result.translationModes["en"])
        assertTrue(result.qualityNotes.any { it.contains("실행하지 않았습니다") })
    }

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
