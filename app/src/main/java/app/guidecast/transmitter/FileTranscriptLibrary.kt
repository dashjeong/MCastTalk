package app.guidecast.transmitter

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable

enum class FileTranslationEngine { MLKIT, GEMMA, API }

/** User content, kept only in the app-private library and excluded from diagnostic exports. */
data class FileLibraryEntry(
    val id: String,
    val displayName: String,
    val uri: String,
    val sha256: String,
    val durationMs: Long,
    val sourceLanguageTag: String?,
    val createdAtMillis: Long,
    val segments: List<FileSpeechSegment> = emptyList(),
    val translations: Map<String, List<String>> = emptyMap(),
    val qualityNotes: List<String> = emptyList(),
    val translationModes: Map<String, FileTranslationEngine> = emptyMap(),
    val requiresRelink: Boolean = false,
)

/** List queries read metadata only; audio is never copied into the database. */
internal class FileTranscriptLibrary(context: Context, databaseName: String = "file_transcripts.db",
    private val maximumFiles: Int = FileTranscriptBudget.MAX_LIBRARY_FILES,
    private val maximumEncodedChars: Long = FileTranscriptBudget.MAX_ENCODED_CHARS,
) : Closeable {
    private val helper = object : SQLiteOpenHelper(context.applicationContext, databaseName, null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE files (id TEXT PRIMARY KEY, name TEXT NOT NULL, uri TEXT NOT NULL, duration INTEGER NOT NULL, language TEXT, created INTEGER NOT NULL, notes TEXT NOT NULL, requires_relink INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE segments (file_id TEXT NOT NULL REFERENCES files(id) ON DELETE CASCADE, ordinal INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(file_id, ordinal))")
            db.execSQL("CREATE TABLE translations (file_id TEXT NOT NULL REFERENCES files(id) ON DELETE CASCADE, language TEXT NOT NULL, ordinal INTEGER NOT NULL, payload TEXT NOT NULL, engine TEXT NOT NULL, PRIMARY KEY(file_id, language, ordinal))")
        }
        override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE files ADD COLUMN requires_relink INTEGER NOT NULL DEFAULT 0")
                val hasEngine = db.rawQuery("PRAGMA table_info(translations)", null).use { cursor ->
                    var found = false
                    while (cursor.moveToNext()) if (cursor.getString(1) == "engine") found = true
                    found
                }
                if (!hasEngine) db.execSQL("ALTER TABLE translations ADD COLUMN engine TEXT NOT NULL DEFAULT 'MLKIT'")
            }
        }
    }

    @Synchronized fun list(): List<FileLibraryEntry> = helper.readableDatabase.query(
        "files", null, null, null, null, null, "created DESC, id ASC", maximumFiles.toString(),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(cursor.metadata())
        }
    }

    @Synchronized fun load(id: String): FileLibraryEntry? {
        requireHash(id)
        requireReadableSize(helper.readableDatabase, id)
        val metadata = helper.readableDatabase.query("files", null, "id=?", arrayOf(id), null, null, null, "1").use { c ->
            if (c.moveToFirst()) c.metadata() else null
        } ?: return null
        var wordsLoaded = 0L
        var charsLoaded = 0L
        val segments = helper.readableDatabase.query("segments", arrayOf("payload"), "file_id=?", arrayOf(id), null, null, "ordinal ASC").use { cursor ->
            buildList { while (cursor.moveToNext()) {
                val segment = decodeSegment(JSONObject(cursor.getString(0)))
                wordsLoaded += segment.words.size
                charsLoaded += segment.text.length + segment.words.sumOf { it.text.length.toLong() }
                require(wordsLoaded <= FileTranscriptBudget.MAX_WORDS && charsLoaded <= FileTranscriptBudget.MAX_CONTENT_CHARS) { FileTranscriptBudget.TOO_LARGE }
                add(segment)
            } }
        }
        val modes = mutableMapOf<String, FileTranslationEngine>()
        val translations = helper.readableDatabase.query("translations", arrayOf("language", "payload", "engine"), "file_id=?", arrayOf(id), null, null, "language ASC, ordinal ASC").use { cursor ->
            val values = linkedMapOf<String, MutableList<String>>()
            while (cursor.moveToNext()) {
                val text = cursor.getString(1)
                charsLoaded += text.length
                require(charsLoaded <= FileTranscriptBudget.MAX_CONTENT_CHARS) { FileTranscriptBudget.TOO_LARGE }
                values.getOrPut(cursor.getString(0)) { mutableListOf() }.add(text)
                modes[cursor.getString(0)] = FileTranslationEngine.valueOf(cursor.getString(2))
            }
            values
        }
        FileTranscriptBudget.validate(segments, translations)
        return metadata.copy(segments = segments, translations = translations, translationModes = modes)
    }

    /** One transaction prevents an interrupted conversion from replacing a usable script. */
    @Synchronized fun save(entry: FileLibraryEntry) {
        requireHash(entry.id)
        require(entry.id == entry.sha256)
        require(entry.segments.isNotEmpty() && entry.segments.size <= MAX_FILE_SCRIPT_SEGMENTS)
        require(entry.translations.values.all { it.size == entry.segments.size })
        FileTranscriptBudget.validate(entry.segments, entry.translations)
        FileTranscriptBudget.validateMetadata(entry.displayName, entry.uri, entry.qualityNotes)
        val encodedChars = entry.segments.sumOf {
            val encoded = encodeSegment(it).toString()
            require(encoded.toByteArray(Charsets.UTF_8).size <= DataTransferFormat.MAX_RECORD_CHARS) { FileTranscriptBudget.TOO_LARGE }
            encoded.length.toLong()
        } +
            entry.translations.values.sumOf { lines -> lines.sumOf { it.length.toLong() } }
        require(encodedChars <= maximumEncodedChars) { FileTranscriptBudget.TOO_LARGE }
        require(entry.translations.values.all { lines -> lines.all { it.length <= 65_536 } }) { FileTranscriptBudget.TOO_LARGE }
        require(JSONArray(entry.qualityNotes).toString().toByteArray(Charsets.UTF_8).size <= DataTransferFormat.MAX_RECORD_CHARS) { FileTranscriptBudget.TOO_LARGE }
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            if (!contains(db, entry.id)) require(fileCount(db) < maximumFiles) { FileTranscriptBudget.LIBRARY_FULL }
            db.delete("files", "id=?", arrayOf(entry.id))
            db.insertOrThrow("files", null, ContentValues().apply {
                put("id", entry.id); put("name", entry.displayName.take(500)); put("uri", entry.uri)
                put("duration", entry.durationMs); put("language", entry.sourceLanguageTag)
                put("created", entry.createdAtMillis); put("notes", JSONArray(entry.qualityNotes).toString())
                put("requires_relink", if (entry.requiresRelink) 1 else 0)
            })
            entry.segments.forEachIndexed { index, segment ->
                db.insertOrThrow("segments", null, ContentValues().apply {
                    put("file_id", entry.id); put("ordinal", index); put("payload", encodeSegment(segment).toString())
                })
            }
            entry.translations.forEach { (language, lines) ->
                lines.forEachIndexed { index, line ->
                    db.insertOrThrow("translations", null, ContentValues().apply {
                        put("file_id", entry.id); put("language", language); put("ordinal", index); put("payload", line)
                        put("engine", (entry.translationModes[language] ?: FileTranslationEngine.MLKIT).name)
                    })
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized fun relink(id: String, verifiedHash: String, uri: String, displayName: String) {
        requireHash(id)
        require(id == verifiedHash) { "선택한 파일의 해시가 스크립트와 다릅니다." }
        check(helper.writableDatabase.update("files", ContentValues().apply {
            put("uri", uri); put("name", displayName.take(500)); put("requires_relink", 0)
        }, "id=?", arrayOf(id)) == 1) { "보관함에서 파일을 찾을 수 없습니다." }
    }

    @Synchronized fun delete(id: String) { requireHash(id); helper.writableDatabase.delete("files", "id=?", arrayOf(id)) }

    /** Portable, per-row output keeps even large translations below CursorWindow/RAM limits. */
    @Synchronized internal fun exportPortable(emit: (JSONObject) -> Unit) {
        val db = helper.readableDatabase
        db.rawQuery("SELECT id FROM files", null).use { c -> while (c.moveToNext()) requireReadableSize(db, c.getString(0)) }
        db.rawQuery("SELECT id,name,uri,duration,language,created,notes FROM files ORDER BY id", null).use { c ->
            while (c.moveToNext()) emit(JSONObject().apply {
                put("type", "file"); put("id", c.getString(0)); put("name", c.getString(1)); put("uri", c.getString(2))
                put("duration", c.getLong(3)); put("language", c.getString(4)); put("created", c.getLong(5))
                put("notes", JSONArray(c.getString(6)))
            })
        }
        db.rawQuery("SELECT file_id,ordinal,payload FROM segments ORDER BY file_id,ordinal", null).use { c ->
            while (c.moveToNext()) emit(JSONObject().apply {
                put("type", "segment"); put("file", c.getString(0)); put("ordinal", c.getInt(1)); put("value", JSONObject(c.getString(2)))
            })
        }
        db.rawQuery("SELECT file_id,language,ordinal,payload,engine FROM translations ORDER BY file_id,language,ordinal", null).use { c ->
            while (c.moveToNext()) emit(JSONObject().apply {
                put("type", "translation"); put("file", c.getString(0)); put("language", c.getString(1))
                put("ordinal", c.getInt(2)); put("text", c.getString(3)); put("engine", c.getString(4))
            })
        }
    }

    /** Input was fully validated in private staging. Existing SHA entries and edits always win. */
    @Synchronized internal fun importPortable(records: (String) -> Sequence<JSONObject>): Int {
        val db = helper.writableDatabase
        var inserted = 0
        db.beginTransaction()
        try {
            validatePortableCapacity(records("file"))
            db.execSQL("CREATE TEMP TABLE IF NOT EXISTS imported_files(id TEXT PRIMARY KEY)")
            db.execSQL("DELETE FROM imported_files")
            records("file").forEach { row ->
                val id = row.getString("id")
                val count = db.insertWithOnConflict("files", null, ContentValues().apply {
                    put("id", id); put("name", row.getString("name")); put("uri", row.optString("uri"))
                    put("duration", row.getLong("duration")); put("language", row.nullablePortableString("language")?.ifBlank { null })
                    put("created", row.getLong("created")); put("notes", row.optJSONArray("notes")?.toString() ?: "[]")
                    put("requires_relink", 1)
                }, SQLiteDatabase.CONFLICT_IGNORE)
                if (count != -1L) {
                    inserted++
                    db.execSQL("INSERT INTO imported_files VALUES(?)", arrayOf(id))
                }
            }
            fun isNew(id: String): Boolean = db.rawQuery("SELECT 1 FROM imported_files WHERE id=?", arrayOf(id))
                .use { it.moveToFirst() }
            records("segment").forEach { row ->
                if (isNew(row.getString("file"))) db.insertOrThrow("segments", null, ContentValues().apply {
                    put("file_id", row.getString("file")); put("ordinal", row.getInt("ordinal")); put("payload", row.getJSONObject("value").toString())
                })
            }
            records("translation").forEach { row ->
                if (isNew(row.getString("file"))) db.insertOrThrow("translations", null, ContentValues().apply {
                    put("file_id", row.getString("file")); put("language", row.getString("language")); put("ordinal", row.getInt("ordinal"))
                    put("payload", row.getString("text")); put("engine", row.optString("engine", FileTranslationEngine.MLKIT.name))
                })
            }
            db.execSQL("DELETE FROM imported_files")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return inserted
    }

    @Synchronized internal fun validatePortableCapacity(files: Sequence<JSONObject>) {
        val db = helper.readableDatabase
        val current = fileCount(db)
        var missing = 0
        files.forEach { row -> if (!contains(db, row.getString("id"))) {
            missing++
            require(current + missing <= maximumFiles) { FileTranscriptBudget.LIBRARY_FULL }
        } }
    }
    @Synchronized override fun close() = helper.close()
    /** SQL preflight rejects oversized legacy/imported entries before materializing their strings. */
    private fun requireReadableSize(db: SQLiteDatabase, id: String) {
        db.rawQuery("SELECT COALESCE((SELECT SUM(length(payload)) FROM segments WHERE file_id=?),0)+" +
            "COALESCE((SELECT SUM(length(payload)) FROM translations WHERE file_id=?),0)," +
            "(SELECT COUNT(DISTINCT language) FROM translations WHERE file_id=?)," +
            "COALESCE((SELECT MAX(length(payload)) FROM segments WHERE file_id=?),0)," +
            "COALESCE((SELECT MAX(length(payload)) FROM translations WHERE file_id=?),0)", arrayOf(id, id, id, id, id)).use { c ->
            c.moveToFirst()
            require(c.getLong(0) <= maximumEncodedChars && c.getInt(1) <= FileTranscriptBudget.MAX_LANGUAGES &&
                c.getLong(2) <= DataTransferFormat.MAX_RECORD_CHARS && c.getLong(3) <= 65_536) { FileTranscriptBudget.TOO_LARGE }
        }
    }
    private fun requireHash(id: String) { require(id.matches(Regex("[a-f0-9]{64}"))) }
    private fun contains(db: SQLiteDatabase, id: String) = db.rawQuery("SELECT 1 FROM files WHERE id=?", arrayOf(id)).use { it.moveToFirst() }
    private fun fileCount(db: SQLiteDatabase) = db.rawQuery("SELECT COUNT(*) FROM files", null).use { it.moveToFirst(); it.getLong(0) }
    private fun Cursor.metadata() = FileLibraryEntry(
        id = getString(getColumnIndexOrThrow("id")), sha256 = getString(getColumnIndexOrThrow("id")),
        displayName = getString(getColumnIndexOrThrow("name")), uri = getString(getColumnIndexOrThrow("uri")),
        durationMs = getLong(getColumnIndexOrThrow("duration")), sourceLanguageTag = getString(getColumnIndexOrThrow("language")),
        createdAtMillis = getLong(getColumnIndexOrThrow("created")), qualityNotes = decodeStrings(getString(getColumnIndexOrThrow("notes"))),
        requiresRelink = getInt(getColumnIndexOrThrow("requires_relink")) != 0,
    )

    private fun encodeSegment(segment: FileSpeechSegment) = JSONObject().apply {
        put("id", segment.id); put("start", segment.startMs); put("end", segment.endMs)
        put("text", segment.text); put("language", segment.languageTag)
        put("estimated", segment.timingEstimated); put("confidence", segment.languageConfidence)
        put("words", JSONArray().apply { segment.words.forEach { word -> put(JSONObject().apply {
            put("text", word.text); put("start", word.startMs); put("end", word.endMs)
        }) } })
    }
    private fun decodeSegment(value: JSONObject) = FileSpeechSegment(
        id = value.getLong("id"), startMs = value.getLong("start"), endMs = value.getLong("end"),
        text = value.getString("text"), languageTag = value.nullablePortableString("language")?.ifBlank { null },
        timingEstimated = value.optBoolean("estimated", true),
        languageConfidence = if (value.has("confidence") && !value.isNull("confidence")) value.getInt("confidence") else null,
        words = (value.optJSONArray("words") ?: JSONArray()).let { array ->
            require(array.length() <= 4_096) { FileTranscriptBudget.TOO_LARGE }
            List(array.length()) { index ->
            val word = array.getJSONObject(index)
            FileSpeechWord(word.getString("text"), word.getLong("start"), if (word.has("end") && !word.isNull("end")) word.getLong("end") else null)
        } },
    )
    private fun decodeStrings(value: String): List<String> = JSONArray(value).let { array -> List(array.length(), array::getString) }
}

internal const val MAX_FILE_SCRIPT_SEGMENTS = 20_000
