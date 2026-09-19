package app.guidecast.transmitter

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import app.guidecast.core.translation.GlossaryTerm
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.CoroutineContext

enum class DataTransferKind(val label: String) { SETTINGS("설정"), DICTIONARY("사전"), SCRIPTS("스크립트") }
data class DataTransferProgress(val message: String, val records: Long = 0, val applying: Boolean = false)
data class DataTransferResult(val records: Long, val message: String)

/** Version 1 JSONL is portable data only: never executable code, a database path or credentials. */
internal object DataTransferFormat {
    const val SCHEMA = "app.guidecast.portable"
    const val MAX_RECORD_CHARS = 512 * 1024
    const val MAX_EXPANDED_BYTES = 8L * 1024 * 1024 * 1024
    const val MAX_COMPRESSED_BYTES = 2L * 1024 * 1024 * 1024
    const val MAX_RECORDS = 20_000_000L
    private const val MAX_EPOCH = 253_402_300_799_999L
    val scriptTypes = setOf("session", "broadcast", "file", "segment", "translation")
    val dictionaryTypes = setOf("glossary", "correction", "memory", "teacherReport")

    fun manifest(kind: DataTransferKind, count: Long, sha256: String) = JSONObject().apply {
        put("schema", SCHEMA); put("major", 1); put("minor", 2); put("kind", kind.name)
        put("createdAt", System.currentTimeMillis()); put("records", count); put("sha256", sha256)
        put("referenceDictionary", "public-20260905")
    }

    fun validateManifest(row: JSONObject, kind: DataTransferKind) {
        require(row.getString("schema") == SCHEMA) { "MCastTalk 백업 형식이 아닙니다." }
        // Original v1 files used version; later minor fields do not change the major format.
        require(row.optInt("major", row.optInt("version", -1)) == 1) { "지원하지 않는 백업 버전입니다. 앱 업데이트 후 다시 시도하세요." }
        require(row.getString("kind") == kind.name) { "선택한 백업 종류가 다릅니다." }
        require(row.getLong("records") in 0..MAX_RECORDS)
        require(row.getString("sha256").matches(Regex("[a-f0-9]{64}")))
    }

    /** Reject deeply nested parser input before org.json allocates a recursive object tree. */
    fun parse(text: String): JSONObject {
        require(text.length <= MAX_RECORD_CHARS && text.toByteArray(Charsets.UTF_8).size <= MAX_RECORD_CHARS)
        var depth = 0
        var quoted = false
        var escaped = false
        text.forEach { c ->
            if (quoted) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 16) { "백업 구조가 너무 깊습니다." } }
                '}', ']' -> { depth--; require(depth >= 0) }
            }
        }
        require(depth == 0 && !quoted)
        return JSONObject(text)
    }

    data class RecordKey(val type: String, val key: String, val parent: String = "", val ordinal: Int = -1,
        val group: String = "", val contentChars: Long = 0, val words: Int = 0, val encodedChars: Long = 0)

    fun validate(row: JSONObject, kind: DataTransferKind): RecordKey {
        val type = row.getString("type")
        require(type in when (kind) {
            DataTransferKind.SETTINGS -> setOf("settings")
            DataTransferKind.DICTIONARY -> dictionaryTypes
            DataTransferKind.SCRIPTS -> scriptTypes
        }) { "백업 종류와 내용이 일치하지 않습니다." }
        fun id(field: String) = row.getLong(field).also { require(it in 1 until Long.MAX_VALUE) }.toString()
        return when (type) {
            "teacherReport" -> {
                val report = TeacherLearningReport.fromJson(row)
                validateTeacherReport(report)
                RecordKey(type, report.key)
            }
            "settings" -> { PortableSettings.validate(row); RecordKey(type, "settings") }
            "session" -> {
                epoch(row, "started"); language(row.getString("language"))
                RecordKey(type, id("id"), group = "${row.getLong("started")}:${row.getString("language")}")
            }
            "broadcast" -> {
                val sid = id("session")
                val sequence = row.getLong("sequence").also { require(it >= 0) }
                epoch(row, "started"); language(row.getString("language")); text(row, "source", 65_536)
                require(row.optLong("captured", 0) >= 0)
                val translations = row.getJSONObject("translations")
                require(translations.length() <= FileTranscriptBudget.MAX_ARCHIVE_LANGUAGES)
                translations.keys().forEach { key -> language(key); require(translations.getString(key).length <= 65_536) }
                require(row.getString("source").length.toLong() + translations.keys().asSequence().sumOf { translations.getString(it).length.toLong() }
                    <= FileTranscriptBudget.MAX_ARCHIVE_ROW_CHARS) { FileTranscriptBudget.TOO_LARGE }
                listOf("translationMs", "firstAudioMs", "synthesisMs").forEach { name ->
                    row.optJSONObject(name)?.let { values ->
                        require(values.length() <= FileTranscriptBudget.MAX_ARCHIVE_LANGUAGES)
                        values.keys().forEach { key -> language(key); require(values.getLong(key) >= 0) }
                    }
                }
                RecordKey(type, "$sid:$sequence", sid, group = "${row.getLong("started")}:${row.getString("language")}")
            }
            "file" -> {
                val hash = hash(row.getString("id")); text(row, "name", 500)
                require(row.optString("uri").length <= 8_192)
                require(row.getLong("duration") >= 0); epoch(row, "created")
                row.nullablePortableString("language")?.takeIf { it.isNotBlank() }?.let(::language)
                val notes = row.optJSONArray("notes")?.let { notes ->
                    require(notes.length() <= FileTranscriptBudget.MAX_NOTES)
                    List(notes.length()) { notes.getString(it) }
                }.orEmpty()
                FileTranscriptBudget.validateMetadata(row.getString("name"), row.nullablePortableString("uri").orEmpty(), notes)
                RecordKey(type, hash)
            }
            "segment" -> {
                val file = hash(row.getString("file")); val ordinal = ordinal(row)
                val value = row.getJSONObject("value")
                require(value.getLong("id") >= 0)
                val start = value.getLong("start"); require(start >= 0 && value.getLong("end") >= start)
                text(value, "text", 65_536)
                value.nullablePortableString("language")?.takeIf { it.isNotBlank() }?.let(::language)
                var wordCount = 0
                var contentChars = value.getString("text").length.toLong()
                value.optJSONArray("words")?.let { words ->
                    require(words.length() <= 4_096)
                    wordCount = words.length()
                    var previous = start
                    repeat(words.length()) { index ->
                        val word = words.getJSONObject(index)
                        text(word, "text", 4_096)
                        contentChars += word.getString("text").length
                        val time = word.getLong("start"); require(time >= previous && time <= value.getLong("end") + 1_000); previous = time
                        if (word.has("end") && !word.isNull("end")) require(word.getLong("end") >= time)
                    }
                }
                RecordKey(type, "$file:$ordinal", file, ordinal, value.getLong("id").toString(), contentChars, wordCount, value.toString().length.toLong())
            }
            "translation" -> {
                val file = hash(row.getString("file")); val ordinal = ordinal(row); val lang = language(row.getString("language"))
                text(row, "text", 65_536)
                FileTranslationEngine.valueOf(row.optString("engine", FileTranslationEngine.MLKIT.name))
                RecordKey(type, "$file:$lang:$ordinal", file, ordinal, lang, row.getString("text").length.toLong(), encodedChars = row.getString("text").length.toLong())
            }
            "glossary" -> {
                val term = glossary(row).term
                GlossaryCsv.validate(listOf(term)); require(row.optString("alternatives").length <= 8_192)
                RecordKey(type, JSONArray(listOf(term.sourceLanguage, term.targetLanguage, term.sourceTerm)).toString())
            }
            "correction" -> {
                val entry = SpeechCorrectionValidation.entry(correction(row))
                RecordKey(type, JSONArray(listOf(entry.profile, entry.languageTag.lowercase(), entry.recognizedText)).toString())
            }
            "memory" -> {
                val entry = memory(row); validateMemoryEntry(entry); require(entry.updatedAtEpochMillis >= 0)
                RecordKey(type, JSONArray(listOf(normalizeMemoryLanguage(entry.sourceLanguageTag), normalizeMemoryLanguage(entry.targetLanguageTag),
                    entry.translationRegister.name, normalizeMemorySource(entry.original))).toString())
            }
            else -> error("지원하지 않는 레코드")
        }
    }

    fun glossary(row: JSONObject) = GlossaryRow(GlossaryTerm(row.getString("sourceLanguage"), row.getString("targetLanguage"),
        row.getString("term"), row.getString("preferred"), row.optString("replacement"), row.optString("category"),
        row.optString("origin"), row.optBoolean("enabled", true)), row.optString("alternatives"), true)
    fun correction(row: JSONObject) = SpeechCorrectionEntry(id = row.optLong("id", 1), profile = row.getString("profile"),
        languageTag = row.getString("language"), recognizedText = row.getString("recognized"), correctedText = row.getString("corrected"),
        hint = row.nullablePortableString("hint")?.ifBlank { null }, enabled = row.optBoolean("enabled", true), sourceKey = row.nullablePortableString("sourceKey")?.ifBlank { null },
        updatedAt = row.optLong("updated", 0))
    fun memory(row: JSONObject) = SentenceMemoryEntry(sourceLanguageTag = row.getString("sourceLanguage"),
        targetLanguageTag = row.getString("targetLanguage"), translationRegister = TranslationRegister.valueOf(row.getString("register")),
        original = row.getString("original"), corrected = row.getString("corrected"), origin = SentenceMemoryOrigin.valueOf(row.getString("origin")),
        updatedAtEpochMillis = row.optLong("updated", 0))
    fun session(row: JSONObject) = ArchivedBroadcastSession(row.getLong("id"), row.getLong("started"), row.getString("language"), 0)
    fun broadcast(row: JSONObject): ArchivedTranscriptLine {
        fun millis(name: String): Map<String, Long> = row.optJSONObject(name)?.let { values -> values.keys().asSequence().associateWith(values::getLong) }.orEmpty()
        val translations = row.getJSONObject("translations")
        return ArchivedTranscriptLine(TranscriptArchiveKey(row.getLong("session"), row.getLong("sequence")), row.getLong("started"), row.getString("language"),
            TranslationTranscriptLine(sequence = row.getLong("sequence"), sourceText = row.getString("source"), isFinal = true,
                capturedAtElapsedRealtimeNanos = row.optLong("captured", 0), sourceLanguageTag = row.getString("language"),
                translations = translations.keys().asSequence().associateWith(translations::getString), translationLatencyMillis = millis("translationMs"),
                firstAudioLatencyMillis = millis("firstAudioMs"), synthesisLatencyMillis = millis("synthesisMs")), row.optBoolean("backup", false))
    }
    private fun ordinal(row: JSONObject) = row.getInt("ordinal").also { require(it in 0 until MAX_FILE_SCRIPT_SEGMENTS) }
    private fun epoch(row: JSONObject, key: String) { require(row.getLong(key) in 0..MAX_EPOCH) }
    private fun text(row: JSONObject, key: String, max: Int) { require(row.getString(key).length <= max && '\u0000' !in row.getString(key)) }
    private fun hash(value: String) = value.also { require(it.matches(Regex("[a-f0-9]{64}"))) }
    private fun language(value: String) = value.also { require(it.matches(Regex("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8}){0,3}"))) }
}

/** Fully validated app-private staging; no archive entry name is ever used as an output path. */
internal class DataTransferStage(private val file: File,
    private val maxFileContentChars: Long = FileTranscriptBudget.MAX_CONTENT_CHARS,
    private val maxFileWords: Long = FileTranscriptBudget.MAX_WORDS,
) : Closeable {
    private val db = SQLiteDatabase.openOrCreateDatabase(file, null).apply {
        execSQL("CREATE TABLE records(type TEXT NOT NULL,record_key TEXT NOT NULL,parent TEXT NOT NULL,ordinal INTEGER NOT NULL,group_name TEXT NOT NULL,content_chars INTEGER NOT NULL,word_count INTEGER NOT NULL,encoded_chars INTEGER NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(type,record_key))")
        execSQL("CREATE INDEX record_parent ON records(type,parent,group_name,ordinal)")
        execSQL("CREATE UNIQUE INDEX unique_segment_id ON records(parent,group_name) WHERE type='segment'")
    }
    var count = 0L
        private set
    fun records(type: String): Sequence<JSONObject> = sequence {
        db.rawQuery("SELECT payload FROM records WHERE type=? ORDER BY record_key", arrayOf(type)).use { c ->
            while (c.moveToNext()) yield(DataTransferFormat.parse(c.getString(0)))
        }
    }
    fun add(row: JSONObject, kind: DataTransferKind) {
        val key = DataTransferFormat.validate(row, kind)
        require(++count <= DataTransferFormat.MAX_RECORDS)
        db.insertOrThrow("records", null, ContentValues().apply {
            put("type", key.type); put("record_key", key.key); put("parent", key.parent); put("ordinal", key.ordinal); put("group_name", key.group)
            put("content_chars", key.contentChars); put("word_count", key.words); put("encoded_chars", key.encodedChars)
            put("payload", row.toString())
        })
    }
    fun addBatch(rows: List<JSONObject>, kind: DataTransferKind) {
        db.beginTransaction()
        try { rows.forEach { add(it, kind) }; db.setTransactionSuccessful() }
        finally { db.endTransaction() }
    }
    fun validateReferences(kind: DataTransferKind) {
        fun none(sql: String) = db.rawQuery(sql, null).use { require(!it.moveToFirst()) { "백업의 문장 순서 또는 연결 정보가 불완전합니다." } }
        if (kind == DataTransferKind.SETTINGS) require(count == 1L)
        if (kind == DataTransferKind.DICTIONARY) {
            db.rawQuery("SELECT COUNT(*) FROM records WHERE type='correction'", null).use { it.moveToFirst(); require(it.getLong(0) <= 500) }
            db.rawQuery("SELECT COUNT(*) FROM records WHERE type='memory'", null).use { it.moveToFirst(); require(it.getLong(0) <= SentenceTranslationMemory.MAX_ENTRIES) }
        }
        if (kind != DataTransferKind.SCRIPTS) return
        db.rawQuery("SELECT COUNT(*) FROM records WHERE type='file'", null).use {
            it.moveToFirst(); require(it.getLong(0) <= FileTranscriptBudget.MAX_LIBRARY_FILES) { FileTranscriptBudget.LIBRARY_FULL }
        }
        none("SELECT 1 FROM records WHERE type IN ('segment','translation') GROUP BY parent HAVING SUM(content_chars)>$maxFileContentChars OR SUM(word_count)>$maxFileWords OR SUM(encoded_chars)>${FileTranscriptBudget.MAX_ENCODED_CHARS} LIMIT 1")
        none("SELECT 1 FROM records WHERE type='translation' GROUP BY parent HAVING COUNT(DISTINCT group_name)>${FileTranscriptBudget.MAX_LANGUAGES} LIMIT 1")
        none("SELECT 1 FROM records r WHERE r.type IN ('segment','translation') AND NOT EXISTS (SELECT 1 FROM records f WHERE f.type='file' AND f.record_key=r.parent) LIMIT 1")
        none("SELECT 1 FROM records f WHERE f.type='file' AND NOT EXISTS (SELECT 1 FROM records s WHERE s.type='segment' AND s.parent=f.record_key) LIMIT 1")
        none("SELECT 1 FROM records WHERE type='segment' GROUP BY parent HAVING MIN(ordinal)<>0 OR MAX(ordinal)+1<>COUNT(*) OR COUNT(*)>$MAX_FILE_SCRIPT_SEGMENTS LIMIT 1")
        none("SELECT 1 FROM records t WHERE t.type='translation' GROUP BY t.parent,t.group_name HAVING MIN(t.ordinal)<>0 OR MAX(t.ordinal)+1<>COUNT(*) OR COUNT(*)<>(SELECT COUNT(*) FROM records s WHERE s.type='segment' AND s.parent=t.parent) LIMIT 1")
        none("SELECT 1 FROM records b WHERE b.type='broadcast' AND NOT EXISTS (SELECT 1 FROM records s WHERE s.type='session' AND s.record_key=b.parent AND s.group_name=b.group_name) LIMIT 1")
    }

    fun readZip(input: InputStream, kind: DataTransferKind, coroutine: CoroutineContext, progress: (DataTransferProgress) -> Unit) {
        var compressed = 0L
        val boundedInput = object : java.io.FilterInputStream(input) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = `in`.read(b, off, len).also {
                if (it > 0) { compressed += it; require(compressed <= DataTransferFormat.MAX_COMPRESSED_BYTES) }
            }
            override fun read(): Int = `in`.read().also { if (it >= 0) { compressed++; require(compressed <= DataTransferFormat.MAX_COMPRESSED_BYTES) } }
        }
        val seen = mutableSetOf<String>()
        var manifest: JSONObject? = null
        var expanded = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        db.beginTransaction()
        try {
            ZipInputStream(boundedInput).use { zip ->
                while (true) {
                    coroutine.ensureActive()
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory && entry.name in setOf("manifest.json", "records.jsonl") && seen.add(entry.name)) { "백업 파일 경로나 중복 항목이 올바르지 않습니다." }
                    val bytes = object : java.io.InputStream() {
                        override fun read(): Int = zip.read().also { n -> if (n >= 0) { expanded++; checkSize(); if (entry.name == "records.jsonl") digest.update(n.toByte()) } }
                        override fun read(b: ByteArray, off: Int, len: Int): Int = zip.read(b, off, len).also { n -> if (n > 0) {
                            expanded += n; checkSize(); if (entry.name == "records.jsonl") digest.update(b, off, n)
                        } }
                        private fun checkSize() { require(expanded <= DataTransferFormat.MAX_EXPANDED_BYTES) { "백업이 지원 용량 8GB를 초과합니다." }; coroutine.ensureActive() }
                    }
                    val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    val reader = InputStreamReader(bytes, decoder).buffered()
                    if (entry.name == "manifest.json") {
                        manifest = DataTransferFormat.parse(readLimitedLine(reader, 8_192) ?: error("백업 정보가 비어 있습니다."))
                        require(readLimitedLine(reader, 8_192) == null)
                    } else {
                        while (true) {
                            val line = readLimitedLine(reader, DataTransferFormat.MAX_RECORD_CHARS) ?: break
                            require(line.isNotBlank()); add(DataTransferFormat.parse(line), kind)
                            if (count % 1_000 == 0L) progress(DataTransferProgress("백업 내용을 검사하고 있습니다.", count))
                        }
                    }
                    zip.closeEntry()
                }
            }
            require(seen == setOf("manifest.json", "records.jsonl")) { "백업 파일이 불완전합니다." }
            val info = requireNotNull(manifest)
            DataTransferFormat.validateManifest(info, kind)
            require(info.getLong("records") == count && info.getString("sha256") == digest.digest().joinToString("") { "%02x".format(it) }) { "백업 무결성 확인에 실패했습니다. 기존 자료는 변경하지 않았습니다." }
            validateReferences(kind)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    override fun close() { db.close(); file.delete(); File(file.path + "-journal").delete() }
}

internal fun JSONObject.nullablePortableString(name: String): String? = if (!has(name) || isNull(name)) null else getString(name)

private fun readLimitedLine(reader: java.io.Reader, max: Int): String? {
    val line = StringBuilder()
    while (true) {
        val value = reader.read()
        if (value < 0) return if (line.isEmpty()) null else line.toString()
        if (value == '\n'.code) return line.toString().removeSuffix("\r")
        require(line.length < max) { "백업의 단일 레코드 크기 제한을 초과했습니다." }
        line.append(value.toChar())
    }
}
