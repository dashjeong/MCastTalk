package app.guidecast.transmitter

import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import app.guidecast.core.translation.ContextualTextTranslationEngine
import app.guidecast.core.translation.FairQueuedTranslationEngineProvider
import app.guidecast.core.translation.FairTranslationQueueConfig
import app.guidecast.core.translation.FairTranslationQueueObserver
import app.guidecast.core.translation.SelectiveRefinementOutcome
import app.guidecast.core.translation.SelectiveRefinementTranslationEngineProvider
import app.guidecast.core.translation.TranslationEngineProvider
import app.guidecast.core.translation.TranslationStyle
import app.guidecast.core.translation.TranslationStyleContext
import app.guidecast.provider.gemma.translation.GemmaBroadcastCapability
import app.guidecast.provider.gemma.translation.GemmaModelReadiness
import app.guidecast.provider.gemma.translation.GemmaModelVariant
import app.guidecast.provider.mlkit.translation.MlKitTranslationProvider
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Integration of the real providers and the broadcast's selective/fair/admission configuration.
 * This does not start BroadcastService, its automatic recovery coordinator, capture audio, exercise UI/TTS/cloud/approved corrections,
 * or establish human translation quality. It deliberately retains both native providers and the
 * default selective deadlines (3 s draft, 800 ms review); Gemma's own 10 s limit is unchanged.
 * After initial preparation there is no test-inserted reset, warmup or retry between cases.
 *
 * Requires dedicatedCorpusDevice=true and the same hash-pinned contextualQualityManifest used
 * by ContextualTranslationQualityDeviceTest, under target externalFilesDir/benchmark.
 */
class ContextualSelectiveReviewDeviceTest {
    @Test fun actualSelectiveRouteRecordsReviewApplicationAndMeaning(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        require(args.getString("dedicatedCorpusDevice") == "true") { "DEDICATED_QUALITY_DEVICE_REQUIRED" }
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as GuideCastApplication
        val base = File(checkNotNull(app.getExternalFilesDir(null)), "benchmark").canonicalFile
        val name = args.getString("contextualQualityManifest") ?: "contextual-quality-round12.json"
        require(name.matches(Regex("[A-Za-z0-9._-]{1,100}\\.json"))) { "INVALID_FIXTURE_NAME" }
        val file = File(base, name).canonicalFile
        require(file.parentFile == base && file.isFile && file.length() in 1L..64_000L) {
            "STAGED_PUBLIC_FIXTURE_REQUIRED"
        }
        val bytes = file.readBytes()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(hash == FIXTURE_SHA256) { "PUBLIC_FIXTURE_SHA256_MISMATCH" }
        val fixture = JSONObject(bytes.toString(Charsets.UTF_8))
        check(fixture.getInt("schemaVersion") == 1 && fixture.getString("sourceLanguage") == "ko" &&
            fixture.getString("targetLanguage") == "en")
        val cases = fixture.getJSONArray("cases")
        check(cases.length() == 10)
        check((0 until cases.length()).count {
            cases.getJSONObject(it).getString("kind") == "recorded-asr-output"
        } == 5)
        check((0 until cases.length()).count {
            cases.getJSONObject(it).getString("kind") == "authored-counterexample"
        } == 5)

        val capability = GemmaBroadcastCapability.detect(app)
        val rows = JSONArray()
        val failures = JSONArray()
        val output = File(base, "contextual-selective-results-${System.currentTimeMillis()}.json")
        val result = JSONObject().put("schemaVersion", 1).put("state", "PREPARING")
            .put("fixtureId", fixture.getString("fixtureId")).put("fixtureSha256", hash)
            .put("fixtureBytes", bytes.size).put("cases", rows).put("failures", failures)
            .put("scope", "REAL_PROVIDER_SELECTIVE_QUEUE_INTEGRATION_NOT_BROADCAST_SERVICE_E2E")
            .put("route", "MLKIT_SELECTIVE_DEFAULTS_FAIR_QUEUE_NATIVE_ADMISSION_GEMMA")
            .put("sourceLanguage", "ko").put("targetLanguage", "en")
            .put("audioRecognitionExecuted", false).put("humanMeaningAssessment", false)
            .put("externalAiApiUsed", false).put("userCorrectionsModified", false)
            .put("publicFixtureTextStoredLocally", true).put("live800msSelectiveBudgetTested", true)
            .put("productionAutomaticRecoveryTested", false)
            .put("gemmaProviderDeadlineMs", 10_000).put("selectiveUsesConstructorDefaults", true)
            .put("draftBudgetMs", 3_000).put("reviewBudgetMs", 800).put("style", "AUTO")
            .put("sampler", JSONObject().put("topK", 1).put("topP", 1.0).put("temperature", 0.0).put("seed", 7))
            .put("queue", JSONObject().put("maxPendingPerLanguage", 2)
                .put("queueWaitTimeoutMs", 30_000).put("inferenceTimeoutMs", 15_000))
            .put("mlKitRetainedDuringGemmaReview", true).put("betweenCaseWarmups", 0)
            .put("betweenCaseResets", 0).put("betweenCaseRetries", 0)
            .put("queueTerminalSettlementBudgetMs", QUEUE_SETTLEMENT_MS)
            .put("deviceApi", Build.VERSION.SDK_INT).put("deviceAbis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("deviceTotalMemoryBytes", capability.totalMemoryBytes)
            .put("broadcastCapabilitySupported", capability.supported)
            .put("constrainedMemoryMode", capability.constrainedMemoryMode)
        fun save() { output.parentFile?.mkdirs(); output.writeText(result.toString(2)) }
        val started = SystemClock.elapsedRealtime()
        val gemma = app.gemmaTranslationProvider
        val mlKit = MlKitTranslationProvider(app, "ko", requireWifiForModels = false)
        val queueJob = SupervisorJob()
        val queueScope = CoroutineScope(Dispatchers.Default + queueJob)
        var fair: FairQueuedTranslationEngineProvider? = null
        val active = AtomicReference<CaseTrace?>()
        val unmatchedQueueEvents = AtomicInteger()
        save()
        try {
            app.withTranslationBackendUse {
                withTimeout(600_000L) {
                    check(capability.supported) { "BROADCAST_GEMMA_MEMORY_CAPABILITY_REQUIRED" }
                    val serialize = shouldSerializeNativeColdLoads(capability.constrainedMemoryMode, 1,
                        capability.loadPermittedNow)
                    val gate = NativeFirstUseGate(
                        maxParallelInitializations = translationSupportPreparationParallelism(capability.constrainedMemoryMode, 1),
                        maxParallelReloads = translationSupportReloadParallelism(capability.constrainedMemoryMode, 1),
                        beforeFirstUse = { key, _ ->
                            when {
                                key.startsWith("$GEMMA_KEY:") && !gemma.hasActivePreparedWorker() ->
                                    app.acquireProcessNativeColdLoadLease(ProcessNativeColdLoadKeys.GEMMA_MODEL, serialize)
                                key.startsWith("$MLKIT_KEY:") && !mlKit.hasActivePreparedWorker(key.substringAfterLast(':')) ->
                                    app.acquireProcessNativeColdLoadLease(
                                        ProcessNativeColdLoadKeys.mlKitTranslation(key.substringAfterLast(':')), serialize)
                                else -> null
                            }
                        },
                        isInitializationCurrent = { key ->
                            when {
                                key.startsWith("$GEMMA_KEY:") -> gemma.hasActivePreparedWorker()
                                key.startsWith("$MLKIT_KEY:") -> mlKit.hasActivePreparedWorker(key.substringAfterLast(':'))
                                else -> true
                            }
                        },
                    )
                    val preparingMlKit = SystemClock.elapsedRealtime()
                    withTimeout(180_000L) {
                        mlKit.prepareModels(setOf("en"), "ko", isNativeOwnerCurrent = { true },
                            reconcileNativeTargets = true, initializeWithNativeAdmission = { target, initialize ->
                                app.withProcessNativeColdLoadLease(ProcessNativeColdLoadKeys.mlKitTranslation(target), serialize) {
                                    initialize()
                                }
                            })
                        app.withProcessNativeColdLoadLease(ProcessNativeColdLoadKeys.mlKitTranslation("en"), serialize) {
                            mlKit.warm(listOf("en"))
                        }
                    }
                    check(mlKit.hasActivePreparedWorker("en")) { "MLKIT_WARMUP_FAILED" }
                    result.put("mlKitPreparationMs", SystemClock.elapsedRealtime() - preparingMlKit)
                        .put("initialMlKitWarmupCalls", 1)

                    gemma.selectModel(GemmaModelVariant.STANDARD)
                    val manager = gemma.modelManager
                    val expected = fixture.getJSONObject("model")
                    check(expected.getString("sha256") == GemmaModelVariant.STANDARD.sha256 &&
                        expected.getLong("bytes") == GemmaModelVariant.STANDARD.sizeBytes &&
                        expected.getString("revision") == GemmaModelVariant.STANDARD.revision)
                    check(manager.modelFile.isFile && manager.modelFile.length() == expected.getLong("bytes")) {
                        "PINNED_STANDARD_MODEL_MUST_BE_STAGED_FIRST"
                    }
                    val preparingGemma = SystemClock.elapsedRealtime()
                    manager.refresh()
                    check(manager.status.value.readiness in setOf(GemmaModelReadiness.VERIFIED, GemmaModelReadiness.READY)) {
                        "MODEL_FILE_VERIFICATION_FAILED"
                    }
                    result.put("model", JSONObject().put("variant", manager.selectedVariant.id)
                        .put("bytes", manager.modelFile.length()).put("sha256", manager.selectedVariant.sha256)
                        .put("revision", manager.selectedVariant.revision)
                        .put("hashVerification", "GemmaModelManager.refresh pinned file verification contract"))
                    app.withProcessNativeColdLoadLease(ProcessNativeColdLoadKeys.GEMMA_MODEL, serialize) {
                        gemma.warmup("en", "ko", timeoutMillis = 120_000L)
                    }
                    check(gemma.hasActivePreparedWorker() && !gemma.isAutomaticRetryBlocked()) { "NO_PREPARED_GEMMA_WORKER" }
                    result.put("gemmaPreparationMs", SystemClock.elapsedRealtime() - preparingGemma)
                        .put("initialGemmaWarmupCalls", 1).put("state", "RUNNING")
                    save()

                    val draft = traceProvider(mlKit.withNativeFirstUseGate(gate, MLKIT_KEY), active, "draft")
                    val reviewer = traceProvider(gemma.withNativeFirstUseGate(gate, GEMMA_KEY), active, "review")
                    val queue = FairQueuedTranslationEngineProvider(reviewer, queueScope,
                        config = FairTranslationQueueConfig(maxPendingPerLanguage = 2,
                            queueWaitTimeoutMillis = 30_000L, inferenceTimeoutMillis = 15_000L),
                        observer = FairTranslationQueueObserver { timing ->
                            val trace = active.get()
                            if (trace == null) unmatchedQueueEvents.incrementAndGet() else {
                                trace.put("queueTiming", JSONObject().put("outcome", timing.outcome.name)
                                    .put("queueWaitMs", timing.queueWaitMillis).put("inferenceMs", timing.inferenceMillis))
                                trace.queueTerminal.complete(Unit)
                            }
                        })
                    fair = queue
                    val selective = SelectiveRefinementTranslationEngineProvider(
                        draftProvider = draft,
                        reviewerAvailable = { target ->
                            // The dedicated route starts live after the single warmup. As in the
                            // current selective broadcast route, this probe never warms a cold worker.
                            val prepared = gemma.hasActivePreparedWorker()
                            val blocked = gemma.isAutomaticRetryBlocked()
                            active.get()?.put("availabilityProbe", JSONObject().put("targetSupported", target == "en")
                                .put("gemmaLiveActive", true).put("prepared", prepared).put("crashBlocked", blocked))
                            target == "en" && prepared && !blocked
                        },
                        reviewerProvider = TranslationEngineProvider { target ->
                            check(target == "en" && gemma.hasActivePreparedWorker() && !gemma.isAutomaticRetryBlocked()) {
                                "Optional Gemma reviewer is not ready"
                            }
                            val engine = queue.engineFor(target) as ContextualTextTranslationEngine
                            object : ContextualTextTranslationEngine {
                                override suspend fun translateWithContext(text: String, contextBefore: String?,
                                    sourceLanguageTag: String, targetLanguageTag: String): String {
                                    checkNotNull(active.get()).queued = true
                                    return engine.translateWithContext(text, contextBefore, sourceLanguageTag, targetLanguageTag)
                                }
                            }
                        },
                        onDiagnostic = { diagnostic ->
                            active.get()?.let { trace ->
                                trace.diagnostics.incrementAndGet()
                                trace.put("selectiveOutcome", diagnostic.outcome.name)
                                trace.put("reviewReasons", JSONArray(diagnostic.reasons.map { it.name }))
                            }
                        },
                    )
                    val engine = selective.engineFor("en") as ContextualTextTranslationEngine
                    repeat(cases.length()) { index ->
                        val item = cases.getJSONObject(index)
                        val original = item.getString("originalText")
                        val context = item.getString("contextBefore")
                        check(original.isNotBlank() && original.length <= 600 && context.length <= 400)
                        val trace = CaseTrace()
                        check(active.compareAndSet(null, trace)) { "PRIOR_CASE_NOT_SETTLED" }
                        val row = JSONObject().put("id", item.getString("id")).put("kind", item.getString("kind"))
                            .put("originalText", original).put("contextBefore", context)
                            .put("sourceUrl", item.opt("sourceUrl") ?: JSONObject.NULL)
                            .put("sourceResultSha256", item.opt("sourceResultSha256") ?: JSONObject.NULL)
                            .put("sourceSequence", item.opt("sourceSequence") ?: JSONObject.NULL)
                            .put("sourcePcmSha256", item.opt("sourcePcmSha256") ?: JSONObject.NULL)
                            .put("preparedGemmaBefore", gemma.hasActivePreparedWorker())
                            .put("preparedMlKitBefore", mlKit.hasActivePreparedWorker("en"))
                        rows.put(row)
                        val requestStarted = SystemClock.elapsedRealtime()
                        try {
                            val translated = withContext(TranslationStyleContext(TranslationStyle.AUTO)) {
                                engine.translateWithContext(original, context, "ko", "en")
                            }
                            check(translated.isNotBlank() && translated.length <= 1_200) { "MALFORMED_FINAL_TRANSLATION" }
                            row.put("requestState", "COMPLETED").put("finalTranslation", translated)
                                .put("finalAssets", assess(item, translated))
                        } catch (error: Exception) {
                            currentCoroutineContext().ensureActive()
                            row.put("requestState", "FAILED").put("requestErrorType", error.javaClass.simpleName)
                                .put("requestErrorDiagnostic", safeErrorDiagnostic(error))
                            failures.put("${item.getString("id")}:REQUEST_FAILED")
                        } finally {
                            row.put("wrapperElapsedMs", SystemClock.elapsedRealtime() - requestStarted)
                        }
                        // Observe the prior queue's terminal event before assigning its observer to
                        // the next case. This is not reviewer extra time, a retry or model recovery.
                        val settling = SystemClock.elapsedRealtime()
                        val settled = !trace.queued || withTimeoutOrNull(QUEUE_SETTLEMENT_MS) {
                            trace.queueTerminal.await(); true
                        } == true
                        row.put("queueTerminalObserved", settled).put("queueSettlementMs", SystemClock.elapsedRealtime() - settling)
                        val observed = trace.snapshot()
                        observed.keys().forEach { key -> row.put(key, observed.get(key)) }
                        row.put("diagnosticCount", trace.diagnostics.get())
                            .put("preparedGemmaAfter", gemma.hasActivePreparedWorker())
                            .put("preparedMlKitAfter", mlKit.hasActivePreparedWorker("en"))
                            .put("automaticRetryBlockedAfter", gemma.isAutomaticRetryBlocked())
                        listOf("draft", "review").forEach { phase ->
                            if (row.has("${phase}Translation")) row.put("${phase}Assets", assess(item, row.getString("${phase}Translation")))
                        }
                        val accepted = row.optString("selectiveOutcome") == SelectiveRefinementOutcome.REVIEW_ACCEPTED.name
                        val completed = row.optString("requestState") == "COMPLETED"
                        val draftAccepted = row.optString("selectiveOutcome") == SelectiveRefinementOutcome.DRAFT_ACCEPTED.name
                        row.put("reviewApplied", accepted).put("draftFallbackUsed", completed && !accepted && !draftAccepted)
                            .put("draftAcceptedWithoutReview", completed && draftAccepted)
                            .put("finalEqualsDraft", completed && row.optString("finalTranslation") == row.optString("draftTranslation"))
                        if (!accepted) failures.put("${item.getString("id")}:REVIEW_NOT_APPLIED")
                        if (row.optJSONObject("finalAssets")?.optBoolean("passed") != true) failures.put("${item.getString("id")}:FINAL_MEANING_ASSET_FAILED")
                        if (trace.diagnostics.get() != 1 || row.optInt("draftCalls") != 1) {
                            failures.put("${item.getString("id")}:ROUTE_ACCOUNTING_FAILED")
                        }
                        save()
                        check(settled) { "QUEUE_DID_NOT_SETTLE_NO_FURTHER_CASES_SUBMITTED" }
                        check(active.compareAndSet(trace, null))
                    }
                }
            }
            val completed = (0 until rows.length()).count { rows.getJSONObject(it).optString("requestState") == "COMPLETED" }
            val accepted = (0 until rows.length()).count { rows.getJSONObject(it).optBoolean("reviewApplied") }
            val meaning = (0 until rows.length()).count { rows.getJSONObject(it).optJSONObject("finalAssets")?.optBoolean("passed") == true }
            val outcomes = JSONObject()
            SelectiveRefinementOutcome.values().forEach { outcome ->
                outcomes.put(outcome.name, (0 until rows.length()).count { rows.getJSONObject(it).optString("selectiveOutcome") == outcome.name })
            }
            result.put("requestedCases", 10).put("executedCases", rows.length()).put("completedRequests", completed)
                .put("reviewAcceptedCases", accepted).put("finalSemanticAssetPassCases", meaning)
                .put("draftFallbackCases", (0 until rows.length()).count { rows.getJSONObject(it).optBoolean("draftFallbackUsed") })
                .put("reviewApplicationDenominator", 10).put("semanticAssetDenominator", 10)
                .put("outcomes", outcomes).put("unmatchedQueueEvents", unmatchedQueueEvents.get())
            val passed = rows.length() == 10 && completed == 10 && accepted == 10 && meaning == 10 &&
                failures.length() == 0 && unmatchedQueueEvents.get() == 0
            result.put("state", if (passed) "SELECTIVE_APPLICATION_AND_ASSET_GATES_PASSED" else "SELECTIVE_APPLICATION_OR_ASSET_GATES_FAILED")
        } catch (error: Throwable) {
            result.put("failedStage", result.optString("state")).put("state", "FAILED")
                .put("failureType", error.javaClass.simpleName)
                .put("failureDiagnostic", safeErrorDiagnostic(error))
            failures.put("SETUP_OR_RUN_FAILED")
            throw error
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                fair?.close()
                queueJob.cancelAndJoin()
                val gemmaCleanup = runCatching { gemma.resetEngineSafely() }
                val mlKitCleanup = runCatching { mlKit.close() }
                result.put("reviewerCleanupSucceeded", gemmaCleanup.isSuccess)
                    .put("draftCleanupSucceeded", mlKitCleanup.isSuccess)
                if (gemmaCleanup.isFailure || mlKitCleanup.isFailure) {
                    result.put("state", "CLEANUP_FAILED")
                    failures.put("CLEANUP_FAILED")
                }
                result.put("elapsedMs", SystemClock.elapsedRealtime() - started)
                save()
            }
        }
        assertEquals("Actual selective route did not satisfy application and meaning gates; inspect ${output.name}",
            "SELECTIVE_APPLICATION_AND_ASSET_GATES_PASSED", result.getString("state"))
    }

    private fun traceProvider(delegate: TranslationEngineProvider, active: AtomicReference<CaseTrace?>,
        phase: String): TranslationEngineProvider = TranslationEngineProvider { target ->
        val engine = delegate.engineFor(target)
        object : ContextualTextTranslationEngine {
            override suspend fun translateWithContext(text: String, contextBefore: String?,
                sourceLanguageTag: String, targetLanguageTag: String): String {
                val trace = checkNotNull(active.get()) { "PROVIDER_CALL_WITHOUT_CASE" }
                trace.increment("${phase}Calls")
                val started = SystemClock.elapsedRealtime()
                try {
                    val translated = if (engine is ContextualTextTranslationEngine) {
                        engine.translateWithContext(text, contextBefore, sourceLanguageTag, targetLanguageTag)
                    } else engine.translate(text, sourceLanguageTag, targetLanguageTag)
                    check(translated.isNotBlank() && translated.length <= 1_200) { "MALFORMED_PROVIDER_TRANSLATION" }
                    trace.put("${phase}Translation", translated)
                    trace.put("${phase}State", "COMPLETED")
                    return translated
                } catch (error: Throwable) {
                    trace.put("${phase}State", "FAILED")
                    trace.put("${phase}ErrorType", error.javaClass.simpleName)
                    trace.put("${phase}ErrorDiagnostic", safeErrorDiagnostic(error))
                    throw error
                } finally {
                    trace.put("${phase}ElapsedMs", SystemClock.elapsedRealtime() - started)
                }
            }
        }
    }

    private class CaseTrace {
        private val data = JSONObject()
        val diagnostics = AtomicInteger()
        val queueTerminal = CompletableDeferred<Unit>()
        @Volatile var queued = false
        @Synchronized fun put(key: String, value: Any) { data.put(key, value) }
        @Synchronized fun increment(key: String) { data.put(key, data.optInt(key) + 1) }
        @Synchronized fun snapshot(): JSONObject = JSONObject(data.toString())
    }

    private fun assess(item: JSONObject, text: String): JSONObject {
        val missing = JSONArray()
        val rejected = JSONArray()
        val assets = item.getJSONArray("requiredAssets")
        repeat(assets.length()) { index ->
            val asset = assets.getJSONObject(index)
            if (!Regex(asset.getString("pattern"), RegexOption.IGNORE_CASE).containsMatchIn(text)) missing.put(asset.getString("name"))
        }
        val forbidden = item.getJSONArray("forbiddenPatterns")
        repeat(forbidden.length()) { index ->
            if (Regex(forbidden.getString(index), RegexOption.IGNORE_CASE).containsMatchIn(text)) rejected.put(index)
        }
        return JSONObject().put("requiredAssetCount", assets.length()).put("matchedAssetCount", assets.length() - missing.length())
            .put("missingAssets", missing).put("forbiddenPatternIndexes", rejected)
            .put("passed", missing.length() == 0 && rejected.length() == 0)
    }

    /** Fixed codes and code locations only; arbitrary exception messages may contain speech. */
    private fun safeErrorDiagnostic(error: Throwable): JSONObject {
        val code = when (error.message) {
            "GEMMA_UNSUPPORTED_TRANSLATION_STYLE" -> "GEMMA_UNSUPPORTED_TRANSLATION_STYLE"
            "GEMMA_UNTRANSLATED_SOURCE_COPY" -> "GEMMA_UNTRANSLATED_SOURCE_COPY"
            "Failed requirement." -> "ARGUMENT_CONTRACT_REJECTED"
            "Optional Gemma reviewer is not ready" -> "PREPARED_REVIEWER_UNAVAILABLE"
            "A native cold-load operation cannot acquire a nested process lease" -> "NESTED_NATIVE_ADMISSION"
            "MALFORMED_PROVIDER_TRANSLATION", "MALFORMED_FINAL_TRANSLATION" -> "MALFORMED_TRANSLATION"
            "모델 실행 점검·적용 중입니다. 완료 후 통역을 시작하세요." -> "MODEL_APPLICATION_IN_PROGRESS"
            else -> if (error is kotlinx.coroutines.CancellationException) "REQUEST_CANCELLED" else "UNCLASSIFIED_PROVIDER_ERROR"
        }
        val result = JSONObject().put("type", error.javaClass.simpleName).put("code", code)
        error.stackTrace.firstOrNull { it.className.startsWith("app.guidecast.") }?.let { frame ->
            result.put("firstAppFrame", JSONObject().put("class", frame.className)
                .put("method", frame.methodName).put("line", frame.lineNumber))
        }
        return result
    }

    private companion object {
        const val FIXTURE_SHA256 = "0350057708c2043bf954a72d827fb320d7a476fbe2098ec17d398077a707a708"
        const val MLKIT_KEY = "mlkit-translation"
        const val GEMMA_KEY = "gemma-translation"
        const val QUEUE_SETTLEMENT_MS = 20_000L
    }
}
