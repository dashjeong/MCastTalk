package app.guidecast.transmitter

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import app.guidecast.provider.gemma.translation.GemmaModelVariant
import app.guidecast.core.translation.TranslationStyle
import app.guidecast.core.translation.TranslationStyleContext
import app.guidecast.core.translation.BroadcastSessionBilingualMemory
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** Local user-supplied corpus. Completion is not semantic approval or ASR/TTS validation. */
class ArticleTranslationComparisonDeviceTest {
    @Test fun recordsActualE4BArticleTranslations(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        require(args.getString("dedicatedCorpusDevice") == "true")
        val runLabel = args.getString("comparisonRun", "baseline")!!
        require(runLabel.matches(Regex("[a-z0-9-]{1,40}")))
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as GuideCastApplication
        val directory = File(checkNotNull(app.getExternalFilesDir(null)), "benchmark").apply { mkdirs() }
        val corpusName = args.getString("comparisonCorpus", "ir-corpus.json")!!
        require(corpusName.matches(Regex("[a-z0-9-]{1,64}\\.json")))
        val corpusFile = File(directory, corpusName)
        require(corpusFile.length() in 1..200_000)
        val bytes = corpusFile.readBytes()
        val corpus = JSONObject(bytes.toString(Charsets.UTF_8))
        val allCases = corpus.getJSONArray("cases")
        require(allCases.length() in 1..100)
        val selectedIds = args.getString("comparisonCaseIds")?.split(',')?.toSet()
        val cases = JSONArray()
        for (index in 0 until allCases.length()) {
            val item = allCases.getJSONObject(index)
            if (selectedIds == null || item.getString("id") in selectedIds) cases.put(item)
        }
        require(cases.length() > 0 && (selectedIds == null || cases.length() == selectedIds.size))
        val requestedLanguages = args.getString("comparisonLanguages", "en,ja,zh")!!.split(',')
        val useSessionMemory = args.getString("comparisonSessionMemory", "false") == "true"
        require(requestedLanguages.isNotEmpty() && requestedLanguages.distinct().size == requestedLanguages.size)
        require(requestedLanguages.all { it in setOf("en", "ja", "zh") })
        val rows = JSONArray()
        val result = JSONObject()
            .put("run", runLabel).put("startedAtUtc", Instant.now().toString())
            .put("corpusFile", corpusName)
            .put("apkVersion", BuildConfig.VERSION_NAME).put("apkVersionCode", BuildConfig.VERSION_CODE)
            .put("fixtureSha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            .put("sourceSha256", corpus.getString("sourceSha256"))
            .put("provider", "ACTUAL_NATIVE_E4B_DIRECT_SOURCE")
            .put("modelSha256", GemmaModelVariant.E4B_IT.sha256)
            .put("qualityVerdict", "REVIEW_REQUIRED")
            .put("translationStyle", "AUTO")
            .put("sessionMemoryEnabled", useSessionMemory)
            .put("asrExecuted", false).put("ttsExecuted", false).put("externalApiUsed", false)
            .put("geminiReferenceProvidedToModel", false).put("fallbackUsed", false)
            .put("state", "PREPARING").put("cases", rows)
        val output = File(directory, "ir-e4b-$runLabel-${System.currentTimeMillis()}.json")
        fun save() = output.writeText(result.toString(2))
        save()
        val provider = app.gemmaTranslationProvider
        val previousVariant = provider.modelManager.selectedVariant
        val lease = requireNotNull(app.acquireTranslationBackendUseIf({ true }))
        try {
            provider.applyVerifiedModel(GemmaModelVariant.E4B_IT)
            check(provider.hasActivePreparedWorker())
            result.put("state", "TRANSLATING")
            for (language in requestedLanguages) {
                val engine = provider.engineFor(language)
                val sessionMemory = BroadcastSessionBilingualMemory()
                for (index in 0 until cases.length()) {
                    val item = cases.getJSONObject(index)
                    val row = JSONObject().put("id", item.getString("id"))
                        .put("source", item.getString("source"))
                        .put("context", item.optString("context"))
                        .put("targetLanguage", language).put("state", "RUNNING")
                    rows.put(row)
                    save()
                    val started = SystemClock.elapsedRealtime()
                    try {
                        val memory = sessionMemory.buildContext("ko", language)
                        row.put("sessionMemory", memory.memory)
                        val translated = withTimeout(60_000L) {
                            withContext(TranslationStyleContext(TranslationStyle.AUTO) + memory) {
                                engine.translateWithContext(item.getString("source"), item.optString("context"), "ko", language)
                            }
                        }
                        check(translated.isNotBlank()) { "EMPTY_TRANSLATION" }
                        row.put("translation", translated).put("state", "COMPLETED")
                        if (useSessionMemory) sessionMemory.record("ko", language, item.getString("source"), translated)
                    } catch (error: Exception) {
                        currentCoroutineContext().ensureActive()
                        row.put("state", "FAILED").put("errorType", error.javaClass.simpleName)
                    } finally {
                        row.put("elapsedMs", SystemClock.elapsedRealtime() - started)
                        save()
                    }
                }
                sessionMemory.clear()
            }
            val completed = (0 until rows.length()).count { rows.getJSONObject(it).getString("state") == "COMPLETED" }
            result.put("completedCases", completed).put("state", if (completed == cases.length() * requestedLanguages.size)
                "COMPLETED_REVIEW_REQUIRED" else "INCOMPLETE")
            save()
            assertEquals("Execution completion only; independent semantic review is required", cases.length() * requestedLanguages.size, completed)
        } finally {
            withContext(NonCancellable) {
                try {
                    if (provider.modelManager.selectedVariant != previousVariant) provider.applyVerifiedModel(previousVariant)
                } finally {
                    lease.close()
                    result.put("endedAtUtc", Instant.now().toString())
                    save()
                }
            }
        }
    }
}
