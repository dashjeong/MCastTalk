package app.guidecast.transmitter

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.guidecast.core.translation.TranslationStyle
import app.guidecast.core.translation.TranslationStyleContext
import app.guidecast.core.translation.requireProtectedTranslationMeaning
import app.guidecast.provider.gemma.translation.GEMMA_OFFLINE_EVALUATION_TIMEOUT_MILLIS
import app.guidecast.provider.gemma.translation.GemmaModelVariant
import app.guidecast.provider.mlkit.translation.MlKitTranslationProvider
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

    @Test
    fun compareProtectedMeaningHoldoutOffline(): Unit = runBlocking {
        executeSuite("protected-meaning-holdout", offlineEvaluation = true, recoverReviewFailures = true)
    }

    @Test
    fun compareProtectedMeaningIndependentHoldoutOffline(): Unit = runBlocking {
        executeSuite("protected-meaning-independent-holdout", offlineEvaluation = true, recoverReviewFailures = true)
    }

    @Test
    fun compareProtectedMeaningRegressionsOffline(): Unit = runBlocking {
        executeSuite("recovery-dev", offlineEvaluation = true, selectedIds = setOf("RC04", "RC05"), recoverReviewFailures = true)
        executeSuite("meeting", offlineEvaluation = true, selectedIds = setOf("MC09"), recoverReviewFailures = true)
    }

    private suspend fun executeSuite(
        suite: String,
        offlineEvaluation: Boolean = false,
        selectedIds: Set<String>? = null,
        selectedTargets: Set<String>? = null,
        domainModes: List<Boolean> = listOf(false, true),
        jsonResponseFormat: Boolean = false,
        recoverReviewFailures: Boolean = false,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val app = context.applicationContext as GuideCastApplication
        val repository = app.domainCorpus
        val provider = app.gemmaTranslationProvider
        val evaluationFallback = if (recoverReviewFailures) MlKitTranslationProvider(
            context, sourceLanguageTag = "ko", requireWifiForModels = false) else null
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
            .put("fallbackEvaluationPolicy", if (recoverReviewFailures) "TEXT_ONLY_COMPARISON_NOT_PRODUCTION_PUBLICATION" else "NONE")
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
                if (evaluationFallback != null) {
                    val prepareStarted = SystemClock.elapsedRealtime()
                    try {
                        withTimeout(180_000L) { evaluationFallback.modelManager.prepare(setOf(target)) }
                        result.put("fallbackPreparation_$target", "COMPLETED")
                    } catch (prepareError: Exception) {
                        if (prepareError is kotlinx.coroutines.CancellationException &&
                            prepareError !is kotlinx.coroutines.TimeoutCancellationException) throw prepareError
                        result.put("fallbackPreparation_$target", prepareError.javaClass.simpleName)
                    }
                    result.put("fallbackPreparationMs_$target", SystemClock.elapsedRealtime() - prepareStarted)
                    save()
                }
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
                            val reviewFailure = error.message in setOf(
                                "GEMMA_SENTENCE_TYPE_REVIEW_REQUIRED", "GEMMA_CURRENCY_ASSET_REVIEW_REQUIRED",
                                "GEMMA_VERBATIM_QUOTE_REVIEW_REQUIRED")
                            val observableFailure = reviewFailure || error.message in setOf("GEMMA_TARGET_SCRIPT_MISMATCH", "GEMMA_UNTRANSLATED_SOURCE_COPY")
                            if (observableFailure) {
                                row.put("failureCode", error.message)
                            }
                            if (reviewFailure) row.put("productionDisposition", "REVIEW_REQUIRED_NO_AUTOMATIC_FALLBACK")
                            if (recoverReviewFailures && observableFailure) {
                                // Separate real ML Kit recovery from native Gemma completion.
                                // No guessed repair, cached reference answer, or timeout expansion.
                                val fallbackStarted = SystemClock.elapsedRealtime()
                                row.put("fallbackEngine", "MLKIT_TEXT_ONLY").put("fallbackRequestBudgetMs", 10_000L)
                                    .put("fallbackState", "RUNNING").put("fallbackStartedElapsedMs", fallbackStarted)
                                save()
                                try {
                                    val recovered = withTimeout(10_000L) {
                                        requireNotNull(evaluationFallback).engineFor(target).translate(
                                            source, "ko", target)
                                    }
                                    check(recovered.isNotBlank()) { "Empty fallback result" }
                                    row.put("fallbackRawTranslation", recovered)
                                    val fallbackRepaired = if (enabled)
                                        TextFidelityGuard.repair(source, recovered, "ko", target) else recovered
                                    try {
                                        requireProtectedTranslationMeaning(source, fallbackRepaired, "ko", target)
                                        row.put("fallbackValidation", "ACCEPTED_OBSERVABLE_CONTRACTS_NOT_SEMANTIC_PASS")
                                    } catch (validationError: IllegalStateException) {
                                        row.put("fallbackValidation", "REJECTED").put("fallbackValidationCode", validationError.message)
                                        throw validationError
                                    }
                                    row.put("fallbackState", "COMPLETED").put("fallbackTranslation", fallbackRepaired)
                                        .put("fallbackElapsedMs", SystemClock.elapsedRealtime() - fallbackStarted)
                                } catch (fallbackError: Exception) {
                                    val fallbackState = when (fallbackError) {
                                        is kotlinx.coroutines.TimeoutCancellationException -> "TIMED_OUT"
                                        is kotlinx.coroutines.CancellationException -> "CANCELLED"
                                        else -> "FAILED"
                                    }
                                    row.put("fallbackState", fallbackState).put("fallbackFailureClass", fallbackError.javaClass.simpleName)
                                        .put("fallbackElapsedMs", SystemClock.elapsedRealtime() - fallbackStarted)
                                    save()
                                    if (fallbackError is kotlinx.coroutines.CancellationException &&
                                        fallbackError !is kotlinx.coroutines.TimeoutCancellationException) throw fallbackError
                                }
                                save()
                                continue
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
            result.put("functionalCompletion", (0 until rows.length()).all {
                rows.getJSONObject(it).getString("state") == "COMPLETED" })
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
                try { evaluationFallback?.close() } finally { backendLease.close() }
                val states = (0 until rows.length()).map { rows.getJSONObject(it).getString("state") }
                result.put("attemptedRows", rows.length())
                    .put("completedRows", states.count { it == "COMPLETED" })
                    .put("failedRows", states.count { it in setOf("FAILED", "TIMED_OUT", "CANCELLED") })
                    .put("notAttemptedRows", plannedRows - rows.length())
                    .put("fallbackAttemptedRows", (0 until rows.length()).count { rows.getJSONObject(it).has("fallbackEngine") })
                    .put("fallbackCompletedRows", (0 until rows.length()).count { rows.getJSONObject(it).optString("fallbackState") == "COMPLETED" })
                    .put("fallbackFailedRows", (0 until rows.length()).count { rows.getJSONObject(it).optString("fallbackState") in setOf("FAILED", "TIMED_OUT", "CANCELLED") })
                save()
            }
        }
    }
}
