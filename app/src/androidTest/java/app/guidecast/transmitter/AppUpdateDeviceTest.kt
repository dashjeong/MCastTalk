package app.guidecast.transmitter

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Platform-only fixture can run against both signed APK versions, regardless of R8 mappings.
 * Run with -e updatePhase seed before adb install -r, then -e updatePhase verify afterwards.
 * Only synthetic data on a dedicated emulator. Does not clear the app or its model cache.
 */
class AppUpdateDeviceTest {
    @Test fun publicUpdatePreservesSettingsScriptsCorrectionsAndDownloadedModels() {
        val arguments = InstrumentationRegistry.getArguments()
        val phase = arguments.getString("updatePhase")
        org.junit.Assume.assumeTrue("Explicit seed/verify phase is required", phase != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        val receipt = File(context.filesDir, "synthetic-update-receipt.json")
        val settings = context.getSharedPreferences("operator_options", Context.MODE_PRIVATE)
        val original = "Synthetic update preservation source"
        val translated = "사용자 확정 수정 보존"
        val scriptId = "ec".repeat(32)
        val modelRoot = File(context.noBackupFilesDir, "moonshine-models")
        if (phase == "seed") {
            assertEquals("0.2.42-alpha", version)
            val options = JSONObject().put("source", "en-US").put("targets", JSONArray().put("ko").put("ja"))
                .put("translation", true).put("gemma", false).put("automaticPreparation", false)
                .put("runMode", "STANDALONE")
            assertTrue(settings.edit().putString("options", options.toString()).commit())
            context.openOrCreateDatabase("sentence-translation-memory.db", Context.MODE_PRIVATE, null).use { db ->
                db.execSQL("CREATE TABLE IF NOT EXISTS phrases(id INTEGER PRIMARY KEY AUTOINCREMENT,source_language TEXT NOT NULL,target_language TEXT NOT NULL,register_name TEXT NOT NULL,normalized_original TEXT NOT NULL,original TEXT NOT NULL,corrected TEXT NOT NULL,origin TEXT NOT NULL,updated_at INTEGER NOT NULL,UNIQUE(source_language,target_language,register_name,normalized_original))")
                db.execSQL("CREATE TABLE IF NOT EXISTS teacher_reports(fingerprint TEXT PRIMARY KEY,created_at INTEGER NOT NULL,report_json TEXT NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS phrases_recent ON phrases(updated_at DESC,id DESC)")
                db.execSQL("CREATE INDEX IF NOT EXISTS phrases_confirmed ON phrases(source_language,target_language,normalized_original,origin,updated_at)")
                db.execSQL("CREATE INDEX IF NOT EXISTS teacher_reports_recent ON teacher_reports(created_at DESC)")
                db.execSQL("INSERT OR REPLACE INTO phrases(source_language,target_language,register_name,normalized_original,original,corrected,origin,updated_at) VALUES('en','ko','AUTO',?,?,?,'USER',1234)", arrayOf(original, original, translated))
                if (db.version == 0) db.version = 2
            }
            context.openOrCreateDatabase("file_transcripts.db", Context.MODE_PRIVATE, null).use { db ->
                db.execSQL("CREATE TABLE IF NOT EXISTS files(id TEXT PRIMARY KEY,name TEXT NOT NULL,uri TEXT NOT NULL,duration INTEGER NOT NULL,language TEXT,created INTEGER NOT NULL,notes TEXT NOT NULL,requires_relink INTEGER NOT NULL DEFAULT 0)")
                db.execSQL("CREATE TABLE IF NOT EXISTS segments(file_id TEXT NOT NULL,ordinal INTEGER NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(file_id,ordinal))")
                db.execSQL("CREATE TABLE IF NOT EXISTS translations(file_id TEXT NOT NULL,language TEXT NOT NULL,ordinal INTEGER NOT NULL,payload TEXT NOT NULL,engine TEXT NOT NULL,PRIMARY KEY(file_id,language,ordinal))")
                db.execSQL("INSERT OR REPLACE INTO files VALUES(?,?,?,1000,'en-US',1234,'[]',1)", arrayOf(scriptId, "synthetic-update.wav", "content://synthetic/update"))
                val segment = JSONObject().put("id", 0).put("start", 0).put("end", 1_000).put("text", original)
                    .put("language", "en-US").put("estimated", true).put("words", JSONArray()).toString()
                db.execSQL("INSERT OR REPLACE INTO segments VALUES(?,0,?)", arrayOf(scriptId, segment))
                db.execSQL("INSERT OR REPLACE INTO translations VALUES(?,'ko',0,?,'MLKIT')", arrayOf(scriptId, translated))
                if (db.version == 0) db.version = 2
            }
            val models = modelRoot.walkTopDown().filter { it.isFile }.map { file ->
                JSONObject().put("path", file.relativeTo(modelRoot).path).put("bytes", file.length()).put("sha256", hash(file))
            }.toList()
            assertTrue("Prepare actual models before taking the update snapshot", models.isNotEmpty())
            receipt.writeText(JSONObject().put("settings", options.toString()).put("models", JSONArray(models)).toString())
        } else {
            assertEquals("verify", phase); assertEquals("0.2.43-alpha", version)
            val snapshot = JSONObject(receipt.readText())
            assertEquals(snapshot.getString("settings"), settings.getString("options", null))
            context.openOrCreateDatabase("sentence-translation-memory.db", Context.MODE_PRIVATE, null).use { db ->
                db.rawQuery("SELECT corrected,origin FROM phrases WHERE original=?", arrayOf(original)).use { c ->
                    assertTrue(c.moveToFirst()); assertEquals(translated, c.getString(0)); assertEquals("USER", c.getString(1))
                }
            }
            context.openOrCreateDatabase("file_transcripts.db", Context.MODE_PRIVATE, null).use { db ->
                db.rawQuery("SELECT payload FROM translations WHERE file_id=? AND language='ko'", arrayOf(scriptId)).use { c ->
                    assertTrue(c.moveToFirst()); assertEquals(translated, c.getString(0))
                }
            }
            val models = snapshot.getJSONArray("models")
            repeat(models.length()) { index ->
                val row = models.getJSONObject(index); val file = File(modelRoot, row.getString("path"))
                assertTrue(file.isFile); assertEquals(row.getLong("bytes"), file.length()); assertEquals(row.getString("sha256"), hash(file))
            }
            println("UPDATE_OK: settings, confirmed sentence, file translation, ${models.length()} downloaded model files preserved")
        }
    }
    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(65_536); while (true) {
            val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count)
        } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
