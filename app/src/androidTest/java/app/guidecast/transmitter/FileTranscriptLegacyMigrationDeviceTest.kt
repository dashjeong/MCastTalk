package app.guidecast.transmitter

import android.content.ContentValues
import android.content.Context
import android.database.SQLException
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Exercises the old on-device schema, not a fresh v2 database with working FK cascades. */
class FileTranscriptLegacyMigrationDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var library: FileTranscriptLibrary
    private val first = entry("a")
    private val other = entry("b")

    @Before fun createLegacyLibrary() {
        context.deleteDatabase(DATABASE_NAME)
        database { db ->
            db.execSQL("CREATE TABLE files(id TEXT PRIMARY KEY,name TEXT NOT NULL,uri TEXT NOT NULL,duration INTEGER NOT NULL,language TEXT,created INTEGER NOT NULL,notes TEXT NOT NULL)")
            db.execSQL("CREATE TABLE segments(file_id TEXT NOT NULL,ordinal INTEGER NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(file_id,ordinal))")
            db.execSQL("CREATE TABLE translations(file_id TEXT NOT NULL,language TEXT NOT NULL,ordinal INTEGER NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(file_id,language,ordinal))")
            seedLegacy(db, first)
            seedLegacy(db, other)
            db.version = 1
        }
        library = FileTranscriptLibrary(context, DATABASE_NAME)
        assertEquals(first, library.load(first.id)) // Forces the actual v1 -> v2 migration.
        database { db ->
            assertEquals(2, db.version)
            for (table in listOf("segments", "translations")) {
                db.rawQuery("PRAGMA foreign_key_list($table)", null).use {
                    assertFalse("The regression must retain the real legacy schema", it.moveToFirst())
                }
            }
        }
    }

    @After fun cleanUp() {
        if (::library.isInitialized) library.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test fun replacingSourceThenAddingTranslationKeepsOtherFilesAndRemovesOldRows() {
        val source = first.copy(
            segments = listOf(first.segments.first().copy(text = "사용자가 보완한 합성 원문")),
            translations = emptyMap(), translationModes = emptyMap(),
        )
        library.save(source)
        assertEquals(source, library.load(first.id))
        assertEquals(1L, rowCount("segments", first.id))
        assertEquals(0L, rowCount("translations", first.id))

        val translated = source.copy(
            translations = mapOf("en" to listOf("The revised synthetic source.")),
            translationModes = mapOf("en" to FileTranslationEngine.API),
        )
        library.save(translated)
        library.close()
        library = FileTranscriptLibrary(context, DATABASE_NAME)
        assertEquals(translated, library.load(first.id))
        assertEquals(other, library.load(other.id))
        assertEquals(1L, rowCount("segments", first.id))
        assertEquals(1L, rowCount("translations", first.id))
    }

    @Test fun deletingAndRecreatingAFileAlsoCleansOnlyItsLegacyOrphans() {
        val orphan = entry("c")
        database { db -> seedChildren(db, orphan, migrated = true) }
        library.delete(first.id)
        assertNull(library.load(first.id))
        assertEquals(0L, rowCount("segments", first.id))
        assertEquals(0L, rowCount("translations", first.id))
        assertEquals(2L, rowCount("segments", orphan.id))
        library.save(first)
        assertEquals(first, library.load(first.id))

        // A previous version could already have removed a parent without its children.
        library.save(orphan)
        assertEquals(orphan, library.load(orphan.id))
        library.delete(orphan.id)
        assertEquals(0L, rowCount("segments", orphan.id))
        assertEquals(0L, rowCount("translations", orphan.id))
        assertEquals(other, library.load(other.id))
    }

    @Test fun failedReplacementRollsBackExplicitChildDeletionAndPreservesUserEdits() {
        installRejectingTrigger()
        expectDatabaseFailure {
            library.save(first.copy(segments = first.segments.map { it.copy(text = "REJECT_SYNTHETIC") }))
        }
        assertEquals(first, library.load(first.id))
        assertEquals(other, library.load(other.id))
        assertEquals(2L, rowCount("segments", first.id))
        assertEquals(2L, rowCount("translations", first.id))
    }

    @Test fun importReusesOnlyNewIdsAndPreservesExistingUserContent() {
        val orphan = entry("c")
        val unrelatedOrphan = entry("d")
        database { db ->
            seedChildren(db, orphan, migrated = true)
            seedChildren(db, unrelatedOrphan, migrated = true)
        }
        val replacement = orphan.copy(
            segments = listOf(orphan.segments.first().copy(text = "가져온 합성 원문")),
            translations = mapOf("en" to listOf("Imported synthetic source.")),
        )
        val incomingExisting = first.copy(displayName = "must-not-overwrite.wav")
        val rows = portable(incomingExisting) + portable(replacement)
        assertEquals(1, library.importPortable { type -> rows.asSequence().filter { it.getString("type") == type } })
        assertEquals(first, library.load(first.id))
        assertEquals(other, library.load(other.id))
        assertEquals(replacement.copy(requiresRelink = true), library.load(orphan.id))
        assertEquals(1L, rowCount("segments", orphan.id))
        assertEquals(1L, rowCount("translations", orphan.id))
        assertEquals(2L, rowCount("segments", unrelatedOrphan.id))
        assertEquals(2L, rowCount("translations", unrelatedOrphan.id))
        assertEquals(0, library.importPortable { type -> rows.asSequence().filter { it.getString("type") == type } })
        assertEquals(first, library.load(first.id))
    }

    @Test fun failedImportRestoresOrphansAndLeavesNoPartialParentThenCanRetry() {
        val orphan = entry("c")
        database { db -> seedChildren(db, orphan, migrated = true) }
        installRejectingTrigger()
        val rejected = orphan.copy(segments = orphan.segments.map { it.copy(text = "REJECT_SYNTHETIC") })
        val badRows = portable(rejected)
        expectDatabaseFailure {
            library.importPortable { type -> badRows.asSequence().filter { it.getString("type") == type } }
        }
        assertNull(library.load(orphan.id))
        assertEquals(2, library.list().size)
        assertEquals(2L, rowCount("segments", orphan.id))
        assertEquals(2L, rowCount("translations", orphan.id))
        assertEquals(first, library.load(first.id))
        assertEquals(other, library.load(other.id))

        val validRows = portable(orphan)
        assertEquals(1, library.importPortable { type -> validRows.asSequence().filter { it.getString("type") == type } })
        assertEquals(orphan.copy(requiresRelink = true), library.load(orphan.id))
    }

    private fun installRejectingTrigger() = database { db ->
        db.execSQL("CREATE TRIGGER reject_fixture BEFORE INSERT ON segments " +
            "WHEN NEW.payload LIKE '%REJECT_SYNTHETIC%' BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
    }

    private fun seedLegacy(db: SQLiteDatabase, entry: FileLibraryEntry) {
        db.insertOrThrow("files", null, ContentValues().apply {
            put("id", entry.id); put("name", entry.displayName); put("uri", entry.uri)
            put("duration", entry.durationMs); put("language", entry.sourceLanguageTag)
            put("created", entry.createdAtMillis); put("notes", JSONArray(entry.qualityNotes).toString())
        })
        seedChildren(db, entry, migrated = false)
    }

    private fun seedChildren(db: SQLiteDatabase, entry: FileLibraryEntry, migrated: Boolean) {
        entry.segments.forEachIndexed { ordinal, segment ->
            db.insertOrThrow("segments", null, ContentValues().apply {
                put("file_id", entry.id); put("ordinal", ordinal); put("payload", segmentJson(segment).toString())
            })
        }
        entry.translations.forEach { (language, lines) -> lines.forEachIndexed { ordinal, text ->
            db.insertOrThrow("translations", null, ContentValues().apply {
                put("file_id", entry.id); put("language", language); put("ordinal", ordinal); put("payload", text)
                if (migrated) put("engine", FileTranslationEngine.MLKIT.name)
            })
        } }
    }

    private fun portable(entry: FileLibraryEntry): List<JSONObject> = buildList {
        add(JSONObject().apply {
            put("type", "file"); put("id", entry.id); put("name", entry.displayName); put("uri", entry.uri)
            put("duration", entry.durationMs); put("language", entry.sourceLanguageTag)
            put("created", entry.createdAtMillis); put("notes", JSONArray(entry.qualityNotes))
        })
        entry.segments.forEachIndexed { ordinal, segment -> add(JSONObject().apply {
            put("type", "segment"); put("file", entry.id); put("ordinal", ordinal); put("value", segmentJson(segment))
        }) }
        entry.translations.forEach { (language, lines) -> lines.forEachIndexed { ordinal, text -> add(JSONObject().apply {
            put("type", "translation"); put("file", entry.id); put("language", language)
            put("ordinal", ordinal); put("text", text); put("engine", FileTranslationEngine.MLKIT.name)
        }) } }
    }

    private fun segmentJson(segment: FileSpeechSegment) = JSONObject().apply {
        put("id", segment.id); put("start", segment.startMs); put("end", segment.endMs)
        put("text", segment.text); put("language", segment.languageTag); put("estimated", true)
        put("words", JSONArray())
    }

    private fun entry(character: String) = FileLibraryEntry(
        id = character.repeat(64), sha256 = character.repeat(64), displayName = "synthetic-$character.wav",
        uri = "content://synthetic/$character", durationMs = 2_000, sourceLanguageTag = "ko-KR", createdAtMillis = 10,
        segments = listOf(
            FileSpeechSegment(1, 0, 1_000, "첫 합성 원문", "ko-KR", timingEstimated = true),
            FileSpeechSegment(2, 1_000, 2_000, "다음 합성 원문", "ko-KR", timingEstimated = true),
        ),
        translations = mapOf("en" to listOf("First synthetic source.", "Next synthetic source.")),
        translationModes = mapOf("en" to FileTranslationEngine.MLKIT), qualityNotes = listOf("합성 시험 자료"),
    )

    private fun rowCount(table: String, id: String): Long = database { db ->
        db.rawQuery("SELECT COUNT(*) FROM $table WHERE file_id=?", arrayOf(id)).use {
            assertTrue(it.moveToFirst()); it.getLong(0)
        }
    }

    private fun <T> database(block: (SQLiteDatabase) -> T): T =
        context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).use(block)

    private fun expectDatabaseFailure(block: () -> Unit) {
        try { block(); fail("The synthetic database trigger must reject this write") }
        catch (_: SQLException) { }
    }

    private companion object { const val DATABASE_NAME = "file-transcripts-legacy-migration-regression.db" }
}
