package app.guidecast.transmitter

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Separate synthetic database: operator files/transcripts and diagnostic exports are untouched. */
class FileTranscriptLibraryDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var library: FileTranscriptLibrary

    @Before fun open() {
        context.deleteDatabase(DATABASE_NAME)
        library = FileTranscriptLibrary(context, DATABASE_NAME)
    }

    @After fun close() {
        library.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun translationLargerThanCursorWindowSurvivesReopenWithoutLoadingBodiesInList() {
        val text = "합성 검증 문장 ".repeat(150)
        val count = 1_200
        assertTrue(text.toByteArray(Charsets.UTF_8).size.toLong() * count > 2L * 1_024L * 1_024L)
        val entry = entry("a").copy(
            segments = (0 until count).map { index ->
                FileSpeechSegment(index.toLong(), index * 1_000L, (index + 1) * 1_000L,
                    "원문 $index", "ko-KR", listOf(FileSpeechWord("원문", index * 1_000L)))
            },
            translations = mapOf("en" to List(count) { "$it $text" }),
        )
        library.save(entry)
        val metadata = library.list().single()
        assertTrue(metadata.segments.isEmpty())
        assertTrue(metadata.translations.isEmpty())
        library.close()
        library = FileTranscriptLibrary(context, DATABASE_NAME)
        val restored = requireNotNull(library.load(entry.id))
        assertEquals(count, restored.segments.size)
        assertEquals(entry.translations["en"]?.last(), restored.translations["en"]?.last())
        assertNull(restored.segments.first().words.single().endMs)
    }

    @Test
    fun wrongHashRelinkCannotAssociateAnotherAudioFileWithExistingWords() {
        val original = entry("a")
        library.save(original)
        try {
            library.relink(original.id, "b".repeat(64), "content://synthetic/wrong", "wrong.wav")
            fail("A different audio hash must not relink the saved transcript")
        } catch (_: IllegalArgumentException) { }
        assertEquals(original.uri, library.load(original.id)?.uri)
        library.relink(original.id, original.sha256, "content://synthetic/moved", "moved.wav")
        val moved = requireNotNull(library.load(original.id))
        assertEquals("content://synthetic/moved", moved.uri)
        assertEquals(original.segments, moved.segments)
    }

    @Test
    fun failedReplacementTransactionPreservesPreviousUsableSourceAndTranslation() {
        val original = entry("a")
        library.save(original)
        context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("CREATE TRIGGER reject_fixture BEFORE INSERT ON segments " +
                "WHEN NEW.payload LIKE '%REJECT_SYNTHETIC%' BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
        }
        try {
            library.save(original.copy(segments = original.segments.map { it.copy(text = "REJECT_SYNTHETIC") }))
            fail("Synthetic database failure must fail the replacement")
        } catch (_: android.database.SQLException) { }
        assertEquals(original, library.load(original.id))
    }

    @Test
    fun deletingOneFileCascadesItsRowsAndKeepsOtherLibraryEntries() {
        val first = entry("a")
        val second = entry("b")
        library.save(first)
        library.save(second)
        library.delete(first.id)
        assertNull(library.load(first.id))
        assertEquals(second, library.load(second.id))
        context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).use { db ->
            for (table in listOf("segments", "translations")) {
                db.rawQuery("SELECT 1 FROM $table WHERE file_id=?", arrayOf(first.id)).use { cursor ->
                    assertFalse(cursor.moveToFirst())
                }
            }
        }
    }

    private fun entry(character: String) = FileLibraryEntry(
        id = character.repeat(64), displayName = "synthetic-$character.wav", uri = "content://synthetic/$character",
        sha256 = character.repeat(64), durationMs = 1_000L, sourceLanguageTag = "ko-KR", createdAtMillis = 10L,
        segments = listOf(FileSpeechSegment(1L, 0L, 1_000L, "합성 원문", "ko-KR", timingEstimated = true)),
        translations = mapOf("en" to listOf("Synthetic source")), qualityNotes = listOf("시험용 자료"),
        translationModes = mapOf("en" to FileTranslationEngine.MLKIT),
    )

    private companion object { const val DATABASE_NAME = "file-transcripts-regression.db" }
}
