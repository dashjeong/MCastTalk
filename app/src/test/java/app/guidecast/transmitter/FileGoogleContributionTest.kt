package app.guidecast.transmitter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileGoogleContributionTest {
    @Test
    fun onlyPublishedGoogleChunksDetermineTheSegmentAttribution() {
        assertNull(FileGoogleContribution.fromChunks(0, 2))
        assertEquals(FileGoogleContribution.ML_KIT_PARTIAL, FileGoogleContribution.fromChunks(1, 2))
        assertEquals(FileGoogleContribution.ML_KIT, FileGoogleContribution.fromChunks(2, 2))
        assertNull(FileGoogleContribution.fromStored(null))
        assertNull(FileGoogleContribution.fromStored("GEMMA"))
    }

    @Test
    fun checkpointKeepsTheActualProviderForEachLanguageAndOrdinal() = kotlinx.coroutines.runBlocking {
        val entry = FileLibraryEntry("id", "sample", "", "id", 1000, "ko", 0,
            segments = listOf(FileSpeechSegment(0, 0, 500, "첫째", "ko"),
                FileSpeechSegment(1, 500, 1000, "둘째", "ko")))
        val saved = translateFileTargetWithCheckpoints(entry, "ja", FileTranslationEngine.GEMMA, {}) { _, emit ->
            kotlinx.coroutines.withContext(FileLineOrigin(FileGoogleContribution.ML_KIT)) { emit(0, "一") }
            emit(1, "二")
            FileScriptTranslation(listOf("一", "二"), emptyList(), FileTranslationEngine.GEMMA)
        }
        assertEquals(mapOf(0 to FileGoogleContribution.ML_KIT), saved.googleContributions["ja"])
    }

    @Test
    fun replacingTheTranslatedWordingRemovesTheAutomaticAttribution() {
        val saved = VoiceNoteLine(0, 500, "안녕하세요", "ko", "Hello",
            googleContribution = FileGoogleContribution.ML_KIT)
        assertNull(saved.corrected("안녕하세요", "Welcome", true).googleContribution)
        assertNull(saved.corrected("환영합니다", "Hello", false).googleContribution)
        assertEquals(FileGoogleContribution.ML_KIT, saved.corrected("안녕하세요", "Hello", false).googleContribution)
    }
}
