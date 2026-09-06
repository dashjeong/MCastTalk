package app.guidecast.transmitter

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import app.guidecast.core.translation.GlossaryTerm
import app.guidecast.core.translation.GlossaryTerms
import java.io.File
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class GlossaryRow(val term: GlossaryTerm, val alternatives: String, val edited: Boolean)

/** On-disk indexed reference + atomic user overlay. Never load 100k terms into model RAM. */
class TranslationGlossaryRepository(private val context: Context) {
    private val lock = Mutex()
    private var database: SQLiteDatabase? = null
    val warning = MutableStateFlow<String?>(null)

    private fun database(): SQLiteDatabase {
        database?.let { return it }
        val directory = File(context.filesDir, "glossary").apply { mkdirs() }
        val base = File(directory, "public-20260905.db")
        if (!base.isFile || base.length() != BASE_BYTES) {
            val temporary = File(directory, "public-20260905.tmp")
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                // AAPT expands .gz assets and removes that suffix in the APK. AssetManager
                // already supplies the original database bytes from the compressed ZIP entry.
                context.assets.open("glossary/public-20260905.db").use { input ->
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            check(total <= BASE_BYTES) { "기본 사전 크기 오류" }
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                        output.fd.sync()
                        check(total == BASE_BYTES) { "기본 사전이 불완전합니다" }
                    }
                }
                check(digest.digest().joinToString("") { "%02x".format(it) } == BASE_SHA) {
                    "기본 사전 무결성 확인 실패"
                }
                check(temporary.renameTo(base)) { "기본 사전 저장 실패" }
            } finally { temporary.delete() }
        }
        val db = SQLiteDatabase.openOrCreateDatabase(File(directory, "user.db"), null)
        try {
            db.execSQL("CREATE TABLE IF NOT EXISTS overrides(src TEXT NOT NULL,lang TEXT NOT NULL,term TEXT NOT NULL,value TEXT NOT NULL,replacement TEXT NOT NULL,category TEXT NOT NULL,origin TEXT NOT NULL,enabled INTEGER NOT NULL,prefix TEXT NOT NULL,alternatives TEXT NOT NULL DEFAULT '',PRIMARY KEY(src,lang,term))")
            db.execSQL("CREATE INDEX IF NOT EXISTS overlay_lookup ON overrides(src,lang,prefix)")
            db.execSQL("ATTACH DATABASE ? AS reference", arrayOf(base.absolutePath))
            db.execSQL("""CREATE TEMP VIEW effective AS
                SELECT src,lang,term,value,replacement,category,origin,enabled,prefix,alternatives,1 AS edited FROM overrides
                UNION ALL
                SELECT b.src,b.lang,b.term,b.value,b.replacement,b.category,b.origin,b.enabled,b.prefix,b.alternatives,0 AS edited
                FROM reference.terms b WHERE NOT EXISTS
                (SELECT 1 FROM overrides u WHERE u.src=b.src AND u.lang=b.lang AND u.term=b.term)
            """.trimIndent())
            database = db
            return db
        } catch (e: Exception) { db.close(); throw e }
    }

    suspend fun matching(text: String, source: String, target: String): List<GlossaryTerm> =
        try {
            val normalized = Normalizer.normalize(text, Normalizer.Form.NFC)
            val prefixes = normalized.indices.flatMap { i ->
                listOf(normalized.substring(i, minOf(i + 2, normalized.length)), normalized.substring(i, i + 1))
            }.map { it.lowercase(Locale.ROOT) }.distinct()
            val terms = io {
                val found = mutableListOf<GlossaryTerm>()
                prefixes.chunked(200).forEach { chunk ->
                    database().rawQuery(
                        "SELECT * FROM effective WHERE src=? AND lang=? AND enabled=1 AND prefix IN (${chunk.joinToString { "?" }})",
                        (listOf(language(source), language(target)) + chunk).toTypedArray(),
                    ).use { cursor -> while (cursor.moveToNext()) found.add(cursor.row().term) }
                }
                found.distinctBy { it.sourceTerm }
            }
            GlossaryTerms.select(normalized, language(source), language(target), terms)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            warning.value = "용어 사전 확인 필요 · ${e.message?.take(180)} · 일반 번역은 계속합니다."
            emptyList()
        }

    suspend fun search(source: String, target: String, query: String, offset: Int = 0): List<GlossaryRow> = io {
        require(offset >= 0)
        val escaped = query.take(150).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        database().rawQuery(
            "SELECT * FROM effective WHERE src=? AND lang=? AND (term LIKE ? ESCAPE '\\' OR value LIKE ? ESCAPE '\\') ORDER BY edited DESC,term LIMIT 100 OFFSET ?",
            arrayOf(language(source), language(target), "%$escaped%", "%$escaped%", offset.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(c.row()) } }
    }

    suspend fun count(source: String, target: String): Int = io {
        database().rawQuery("SELECT COUNT(*) FROM effective WHERE src=? AND lang=?", arrayOf(language(source), language(target)))
            .use { it.moveToFirst(); it.getInt(0) }
    }

    suspend fun save(terms: List<GlossaryTerm>) = io {
        val valid = GlossaryCsv.validate(terms)
        val db = database()
        db.beginTransaction()
        try {
            valid.forEach { term ->
                val values = ContentValues().apply {
                    put("src", term.sourceLanguage); put("lang", term.targetLanguage)
                    put("term", term.sourceTerm); put("value", term.preferredTerm)
                    put("replacement", term.replacement); put("category", term.category)
                    put("origin", term.origin); put("enabled", if (term.enabled) 1 else 0)
                    put("prefix", term.sourceTerm.take(2).lowercase(Locale.ROOT)); put("alternatives", "")
                }
                db.insertWithOnConflict("overrides", null, values, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    suspend fun restore(term: GlossaryTerm) = io {
        database().delete("overrides", "src=? AND lang=? AND term=?", arrayOf(term.sourceLanguage, term.targetLanguage, term.sourceTerm))
    }

    suspend fun export(source: String, target: String, writer: java.io.Writer) {
        val staged = withContext(Dispatchers.IO) { File.createTempFile("glossary-export-", ".csv", context.cacheDir) }
        try {
            io {
                staged.bufferedWriter(Charsets.UTF_8).use { local ->
                    local.write("\uFEFF" + GlossaryCsv.header + "\r\n")
                    database().rawQuery("SELECT * FROM effective WHERE src=? AND lang=? ORDER BY term", arrayOf(language(source), language(target)))
                        .use { c -> while (c.moveToNext()) local.write(GlossaryCsv.line(c.row().term) + "\r\n") }
                }
            }
            // A remote DocumentsProvider may block for minutes. Never hold the dictionary
            // transaction/mutex while writing to a user-selected provider.
            withContext(Dispatchers.IO) { staged.bufferedReader(Charsets.UTF_8).use { it.copyTo(writer) } }
        } finally { withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { staged.delete() } }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { lock.withLock { block() } }
    private fun Cursor.row() = GlossaryRow(
        GlossaryTerm(string("src"), string("lang"), string("term"), string("value"), string("replacement"),
            string("category"), string("origin"), getInt(getColumnIndexOrThrow("enabled")) == 1),
        string("alternatives"), getInt(getColumnIndexOrThrow("edited")) == 1,
    )
    private fun Cursor.string(name: String) = getString(getColumnIndexOrThrow(name))
    companion object {
        private const val BASE_BYTES = 29_405_184L
        private const val BASE_SHA = "5f39f27faf0c68b305d06e67c09eb929398e0242dd0278eb8430c49070d2ccb3"
        fun language(tag: String): String = when (val normalized = tag.lowercase(Locale.ROOT).trim().replace('_', '-')) {
            "ko-kr" -> "ko"
            "en-us" -> "en"
            "ja-jp" -> "ja"
            "zh-cn" -> "zh"
            "es-es" -> "es"
            "ar-sa" -> "ar"
            else -> normalized
        }
    }
}
