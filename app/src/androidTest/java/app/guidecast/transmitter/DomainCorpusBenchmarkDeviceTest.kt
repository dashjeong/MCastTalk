package app.guidecast.transmitter

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.guidecast.core.translation.TranslationStyle
import app.guidecast.core.translation.TranslationStyleContext
import app.guidecast.provider.gemma.translation.GEMMA_OFFLINE_EVALUATION_TIMEOUT_MILLIS
import app.guidecast.provider.gemma.translation.GemmaModelVariant
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Controlled fixtures only; never microphone logs. Completion is separate from semantic grading. */
@RunWith(AndroidJUnit4::class)
class DomainCorpusBenchmarkDeviceTest {
    @Test
    fun compareUnseenFormalAndConversationalCases(): Unit = runBlocking {
        executeSuite("meeting")
    }

    @Test
    fun compareOfficialHeldoutCases(): Unit = runBlocking {
        executeSuite("nkinfo")
    }

    @Test
    fun compareOfficialDevCases(): Unit = runBlocking {
        executeSuite("nkinfo-dev")
    }

    @Test
    fun compareFidelityDevCases(): Unit = runBlocking {
        executeSuite("fidelity-dev")
    }

    @Test
    fun compareRecoveryDevCases(): Unit = runBlocking {
        executeSuite("recovery-dev")
    }

    @Test
    fun compareRecoveryDevCasesOffline(): Unit = runBlocking {
        executeSuite("recovery-dev", offlineEvaluation = true)
    }

    @Test
    fun compareFidelityMeetingCasesOffline(): Unit = runBlocking {
        executeSuite("meeting", offlineEvaluation = true, selectedIds = setOf("MC01", "MC05", "MC09"))
    }

    @Test
    fun compareRecoveryRc01JsonOff(): Unit = runBlocking {
        executeSuite("recovery-dev", offlineEvaluation = true, selectedIds = setOf("RC01"),
            selectedTargets = setOf("en"), domainModes = listOf(false))
    }

    @Test
    fun compareRecoveryRc01JsonOn(): Unit = runBlocking {
        executeSuite("recovery-dev", offlineEvaluation = true, selectedIds = setOf("RC01"),
            selectedTargets = setOf("en"), domainModes = listOf(false), jsonResponseFormat = true)
    }

    private suspend fun executeSuite(
        suite: String,
        offlineEvaluation: Boolean = false,
        selectedIds: Set<String>? = null,
        selectedTargets: Set<String>? = null,
        domainModes: List<Boolean> = listOf(false, true),
        jsonResponseFormat: Boolean = false,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val app = context.applicationContext as GuideCastApplication
        val repository = app.domainCorpus
        val provider = app.gemmaTranslationProvider
        val originalVariant = provider.modelManager.selectedVariant
        val requestedVariant = if (arguments.getString("domainModel") == "e2b")
            GemmaModelVariant.STANDARD else GemmaModelVariant.E4B_IT
        val official = suite.startsWith("nkinfo")
        val trainingPrefix = if (official) "nkinfo" else "meeting"
        val targets = (if (!official) listOf("en", "ja", "zh") else listOf("en", "zh"))
            .filter { selectedTargets == null || it in selectedTargets }
        val styles = if (!official) listOf(TranslationStyle.FORMAL, TranslationStyle.CONVERSATIONAL)
            else listOf(TranslationStyle.FORMAL)
        val fixtureDir = File(context.getExternalFilesDir(null), "domain-fixtures")
        val allCases = JSONArray(File(fixtureDir, "$suite-corpus.json").readText(Charsets.UTF_8))
        val cases = if (selectedIds == null) allCases else JSONArray().also { selected ->
            for (index in 0 until allCases.length()) {
                val item = allCases.getJSONObject(index)
                if (item.getString("id") in selectedIds) selected.put(item)
            }
            assertEquals("Frozen regression IDs must all exist", selectedIds.size, selected.length())
        }
        if (suite == "meeting") assertEquals("Frozen holdout must include six cases per register", 12, allCases.length())
        else assertTrue("Official cases must be an independently frozen heldout set", allCases.length() >= 3)
        val canonicalTargets = targets.map(DomainCorpusRepository::normalizeTargetLanguageTag).toSet()
        val originals = repository.profiles().filter {
            it.active && it.sourceLanguageTag == "ko" && it.targetLanguageTag in canonicalTargets
        }
        val imported = mutableListOf<Long>()
        val profileIds = mutableMapOf<Pair<String, TranslationStyle>, Long>()
        val backendLease = requireNotNull(app.acquireTranslationBackendUseIf({ true }))
        val hintBudget = if (offlineEvaluation || requestedVariant == GemmaModelVariant.E4B_IT) 600
            else DomainCorpusFormat.MAX_HINTS_LENGTH
        val plannedRows = (0 until cases.length()).sumOf { index ->
            val item = cases.getJSONObject(index)
            targets.count { !item.has("target") || item.getString("target") == it } * domainModes.size
        }
        val result = JSONObject().put("model", requestedVariant.id).put("suite", suite)
            .put("evaluationMode", if (offlineEvaluation) "OFFLINE_SEMANTIC_EVALUATION" else "REALTIME")
            .put("requestBudgetMs", if (offlineEvaluation) GEMMA_OFFLINE_EVALUATION_TIMEOUT_MILLIS else 10_000L)
            .put("referenceCharacterBudget", hintBudget).put("plannedRows", plannedRows)
            .put("responseFormat", if (jsonResponseFormat) "JSON_TRANSLATION_SCHEMA" else "NONE")
            .put("trainingCorpus", trainingPrefix)
            .put("fixtureKind", if (!official) "authored-text-not-microphone" else "official-paired-text-local-research-not-microphone")
            .put("semanticGrade", "REQUIRES_INDEPENDENT_REVIEW")
        val rows = JSONArray()
        result.put("rows", rows)
        val output = File(context.getExternalFilesDir(null), "benchmark/domain-$suite-${if (offlineEvaluation) "offline" else "realtime"}-${if (jsonResponseFormat) "json-on" else "json-off"}-${requestedVariant.id}-${System.currentTimeMillis()}.json")
        output.parentFile?.mkdirs()
        fun save() = output.writeText(result.toString(2), Charsets.UTF_8)

        try {
            originals.forEach { repository.deactivate(it.id) }
            for (target in targets) for (style in styles) {
                val before = repository.profiles().map { it.id }.toSet()
                val input = File(fixtureDir, "$trainingPrefix-ko-$target-${style.name.lowercase()}.txt")
                input.inputStream().use {
                    repository.importTxt(it, if (!official) "회의·업무 ($target/${style.name})" else "외교·국제회의 ($target)",
                        if (!official) "회의 보고와 업무 대화의 문맥·용어를 참고합니다." else "공식 주변국 발언의 외교·안보 용어를 참고합니다.",
                        "ko", target, style)
                }
                val created = repository.profiles().filter { it.id !in before }
                imported.addAll(created.map { it.id })
                assertEquals("Fixture import must create exactly one profile", 1, created.size)
                assertTrue("Domain metadata must match the selected training corpus",
                    created.single().name.startsWith(if (trainingPrefix == "meeting") "회의·업무" else "외교·국제회의"))
                profileIds[target to style] = created.single().id
                repository.deactivate(created.single().id)
            }
            val started = SystemClock.elapsedRealtime()
            provider.applyVerifiedModel(requestedVariant)
            result.put("modelPreparationMs", SystemClock.elapsedRealtime() - started)
            assertEquals(requestedVariant, provider.modelManager.appliedVariant)
            assertTrue("Native model must really be prepared", provider.hasActivePreparedWorker())
            save()

            for (target in targets) {
                val engine = DomainCorpusTranslationEngine(
                    if (offlineEvaluation) provider.offlineEvaluationEngineFor(target, jsonResponseFormat) else provider.engineFor(target),
                    repository,
                ) { hintBudget }
                for (index in 0 until cases.length()) {
                    val item = cases.getJSONObject(index)
                    if (item.has("target") && item.getString("target") != target) continue
                    val source = item.getString("source")
                    val style = TranslationStyle.valueOf(item.getString("style"))
                    val id = requireNotNull(profileIds[target to style])
                    for (enabled in domainModes) {
                        if (enabled) repository.activate(id) else repository.deactivate(id)
                        val lookupStarted = SystemClock.elapsedRealtime()
                        val match = repository.match(source, "ko", target, style)
                        val lookupMs = SystemClock.elapsedRealtime() - lookupStarted
                        assertEquals("Unseen quality cases must not be served as memorized exact translations",
                            null, match.exactTranslation)
                        val row = JSONObject().put("id", item.getString("id"))
                            .put("style", style.name).put("category", item.getString("category"))
                            .put("target", target).put("source", source).put("context", item.getString("context"))
                            .put("domainEnabled", enabled).put("hintCharacters", match.hints.length)
                            .put("submittedHintCharacters", boundedDomainReferenceHints(match.hints,
                                hintBudget).length)
                            .put("lookupMs", lookupMs)
                            .put("state", "RUNNING")
                        rows.put(row)
                        save()
                        val before = SystemClock.elapsedRealtime()
                        val translation = try {
                            suspend fun request() = withContext(TranslationStyleContext(style)) {
                                engine.translateWithContext(source, item.getString("context"), "ko", target)
                            }
                            if (offlineEvaluation) request() else withTimeout(60_000L) { request() }
                        } catch (error: Exception) {
                            // Persist terminal metadata before rethrowing; a failed native request
                            // must not look like an unfinished benchmark after cleanup succeeds.
                            val failureState = when {
                                error is kotlinx.coroutines.TimeoutCancellationException ||
                                    error.javaClass.simpleName == "GemmaRealtimeTimeoutException" -> "TIMED_OUT"
                                error is kotlinx.coroutines.CancellationException -> "CANCELLED"
                                else -> "FAILED"
                            }
                            row.put("elapsedMs", SystemClock.elapsedRealtime() - before)
                                .put("state", failureState)
                                .put("failureClass", error.javaClass.simpleName)
                            if (error.message in setOf("GEMMA_TARGET_SCRIPT_MISMATCH", "GEMMA_UNTRANSLATED_SOURCE_COPY")) {
                                row.put("failureCode", error.message)
                            }
                            try {
                                save()
                            } catch (saveError: Exception) {
                                error.addSuppressed(saveError)
                            }
                            // Keep cancellation, native failure, and the existing cleanup contract.
                            // Never persist exception messages, which may contain private text.
                            throw error
                        }
                        row.put("elapsedMs", SystemClock.elapsedRealtime() - before)
                            .put("translation", translation).put("state", "COMPLETED")
                        save()
                        assertTrue("Native translation must not be empty", translation.isNotBlank())
                    }
                    repository.deactivate(id)
                }
            }
            val expectedRows = plannedRows
            assertEquals("Each held-out translation requires both OFF and ON output", expectedRows, rows.length())
            result.put("functionalCompletion", true)
            save()
        } finally {
            try {
                imported.forEach { repository.remove(it) }
                originals.forEach { repository.activate(it.id) }
                val restored = repository.profiles()
                assertTrue("Benchmark must remove only its own imported profiles", restored.none { it.id in imported })
                assertTrue("Original active corpus profiles must be restored", originals.all { original ->
                    restored.any { it.id == original.id && it.active }
                })
                if (provider.modelManager.selectedVariant != originalVariant) {
                    provider.applyVerifiedModel(originalVariant)
                }
                result.put("cleanupCompleted", true)
            } finally {
                backendLease.close()
                val states = (0 until rows.length()).map { rows.getJSONObject(it).getString("state") }
                result.put("attemptedRows", rows.length())
                    .put("completedRows", states.count { it == "COMPLETED" })
                    .put("failedRows", states.count { it in setOf("FAILED", "TIMED_OUT", "CANCELLED") })
                    .put("notAttemptedRows", plannedRows - rows.length())
                save()
            }
        }
    }
}
