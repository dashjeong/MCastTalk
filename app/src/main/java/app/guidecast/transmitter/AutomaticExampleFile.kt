package app.guidecast.transmitter

import android.util.AtomicFile
import app.guidecast.core.translation.TranslationStyle
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Private app storage. No original speech or comparisons are written to diagnostic logs. */
internal class AutomaticExampleFile(file: File) : AutomaticExamplePersistence {
    private val atomic = AtomicFile(file)
    override suspend fun load(): List<AutomaticTranslationExample> = withContext(Dispatchers.IO) {
        if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) return@withContext emptyList()
        val bytes = atomic.openRead().use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                require(out.size() + read <= MAX_BYTES)
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }
        AutomaticExampleJson.decode(bytes)
    }
    override suspend fun save(examples: List<AutomaticTranslationExample>, allowed: () -> Boolean): Boolean =
        write(examples, allowed) { commit -> commit() }

    override suspend fun saveWithCommitAdmission(examples: List<AutomaticTranslationExample>, allowed: () -> Boolean,
        commitAdmission: ((() -> Boolean) -> Boolean)): Boolean = write(examples, allowed, commitAdmission)

    private suspend fun write(examples: List<AutomaticTranslationExample>, allowed: () -> Boolean,
        commitAdmission: ((() -> Boolean) -> Boolean)): Boolean = withContext(Dispatchers.IO) {
        require(examples.size <= AutomaticTranslationExamples.MAX_EXAMPLES)
        val bytes = AutomaticExampleJson.encode(examples)
        if (!allowed()) return@withContext false
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            val committed = commitAdmission {
                if (!allowed()) false else { atomic.finishWrite(stream); true }
            }
            if (!committed) atomic.failWrite(stream)
            committed
        } catch (error: Exception) { atomic.failWrite(stream); throw error }
    }
    private companion object { const val MAX_BYTES = 8 * 1024 * 1024 }
}

/** Storage version stays readable; absence of adoption provenance is never upgraded to approval. */
internal object AutomaticExampleJson {
    fun decode(bytes: ByteArray): List<AutomaticTranslationExample> {
        require(bytes.size <= 8 * 1024 * 1024)
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        require(root.getInt("version") == 1)
        val entries = root.getJSONArray("examples")
        require(entries.length() <= AutomaticTranslationExamples.MAX_EXAMPLES)
        return (0 until entries.length()).map { i ->
            val r = entries.getJSONObject(i)
            AutomaticTranslationExample(ShadowComparison(r.getString("sourceText"), r.optString("context"),
                r.getString("source"), r.getString("target"), r.getLong("corpusRevision"),
                r.getString("online"), r.getString("offline"), TranslationStyle.valueOf(r.getString("style"))),
                r.getString("domainIdentity"), r.getString("instructionsIdentity"),
                adoptionPolicyVersion = r.optInt("adoptionPolicyVersion", 0))
        }
    }
    fun encode(examples: List<AutomaticTranslationExample>): ByteArray {
        require(examples.size <= AutomaticTranslationExamples.MAX_EXAMPLES)
        val rows = JSONArray()
        examples.forEach { e ->
            val c = e.comparison
            rows.put(JSONObject().put("sourceText", c.original).put("context", c.contextBefore.orEmpty())
                .put("source", c.source).put("target", c.target).put("corpusRevision", c.corpusRevision)
                .put("online", c.online).put("offline", c.offline).put("style", c.style.name)
                .put("domainIdentity", e.domainIdentity).put("instructionsIdentity", e.instructionsIdentity)
                .put("adoptionPolicyVersion", e.adoptionPolicyVersion))
        }
        val bytes = JSONObject().put("version", 1).put("examples", rows).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 8 * 1024 * 1024)
        return bytes
    }
}
