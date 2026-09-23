package app.guidecast.transmitter

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import app.guidecast.core.translation.SelectiveRefinementReason
import app.guidecast.core.translation.currentNativeColdLoadTicket
import app.guidecast.core.translation.TranslationReviewContext
import app.guidecast.core.translation.TranslationStyle
import app.guidecast.core.translation.TranslationStyleContext
import app.guidecast.provider.gemma.translation.GemmaBroadcastCapability
import app.guidecast.provider.gemma.translation.GemmaModelReadiness
import app.guidecast.provider.gemma.translation.GemmaModelVariant
import app.guidecast.provider.mlkit.translation.MlKitTranslationProvider
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
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

/**
 * Real local ML Kit draft -> real Gemma review of that draft with prior source context.
 * Requires a dedicated device, a pre-staged pinned STANDARD Gemma model and a hash-pinned
 * external public fixture. It never downloads Gemma, calls a cloud API, or bypasses its
 * production provider deadline. This is not ASR truth, a live 800 ms reviewer-budget test,
 * or a human assessment of complete meaning. Public outputs stay in local test evidence.
 *
 * Args: dedicatedCorpusDevice=true; contextualQualityManifest defaults to
 * contextual-quality-round12.json below target externalFilesDir/benchmark.
 * contextualQualityRoute defaults to review; direct-context-control omits TranslationReviewContext
 * while retaining the same source, prior context, style, model, deadlines and semantic asset gates.
 */
class ContextualTranslationQualityDeviceTest {
    @Test fun publicMeaningAssetsSurviveRealContextualReview(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        require(args.getString("dedicatedCorpusDevice") == "true") { "DEDICATED_QUALITY_DEVICE_REQUIRED" }
        val requestedRoute = args.getString("contextualQualityRoute") ?: "review"
        require(requestedRoute in setOf("review", "direct-context-control")) { "INVALID_QUALITY_ROUTE" }
        val noDraftControl = requestedRoute == "direct-context-control"
        val app = instrumentation.targetContext.applicationContext as GuideCastApplication
        val base = File(checkNotNull(app.getExternalFilesDir(null)), "benchmark").canonicalFile
        val manifestName = args.getString("contextualQualityManifest") ?: "contextual-quality-round12.json"
        require(manifestName.matches(Regex("[A-Za-z0-9._-]{1,100}\\.json"))) { "INVALID_FIXTURE_NAME" }
        val fixtureFile = File(base, manifestName).canonicalFile
        require(fixtureFile.parentFile == base && fixtureFile.isFile && fixtureFile.length() in 1L..64_000L) {
            "STAGED_PUBLIC_FIXTURE_REQUIRED"
        }
        val bytes = fixtureFile.readBytes()
        val fixtureHash = sha256(bytes)
        check(fixtureHash == FIXTURE_SHA256) { "PUBLIC_FIXTURE_SHA256_MISMATCH" }
        val fixture = JSONObject(bytes.toString(Charsets.UTF_8))
        check(fixture.getInt("schemaVersion") == 1)
        check(fixture.getString("sourceLanguage") == "ko" && fixture.getString("targetLanguage") == "en")
        val cases = fixture.getJSONArray("cases")
        check(cases.length() == 10 && (0 until cases.length()).count {
            cases.getJSONObject(it).getString("kind") == "recorded-asr-output"
        } == 5)
        check((0 until cases.length()).count {
            cases.getJSONObject(it).getString("kind") == "authored-counterexample"
        } == 5)
        val rows = JSONArray()
        val failures = JSONArray()
        val output = File(base, "contextual-quality-results-${System.currentTimeMillis()}.json")
        val result = JSONObject().put("schemaVersion", 1).put("state", "PREPARING")
            .put("fixtureId", fixture.getString("fixtureId")).put("fixtureSha256", fixtureHash)
            .put("fixtureBytes", bytes.size).put("cases", rows).put("failures", failures)
            .put("scope", "RECORDED_ASR_TEXT_AND_AUTHORED_COUNTEREXAMPLES_SEMANTIC_ASSET_GATES")
            .put("route", if (noDraftControl) "ML_KIT_BASELINE_THEN_GEMMA_DIRECT_SOURCE_CONTEXT_CONTROL"
                else "ML_KIT_DIRECT_DRAFT_THEN_GEMMA_PROVIDER_WITH_TRANSLATION_REVIEW_CONTEXT")
            .put("noDraftControl", noDraftControl).put("requestedRoute", requestedRoute)
            .put("draftPassedToGemma", !noDraftControl)
            .put("comparisonLimit", "Omitting review context selects the production direct-translation prompt; this is a route contrast, not proof of a single anchoring cause")
            .put("sourceLanguage", "ko").put("targetLanguage", "en")
            .put("audioRecognitionExecuted", false).put("humanMeaningAssessment", false)
            .put("externalAiApiUsed", false).put("userCorrectionsModified", false)
            .put("publicFixtureTextStoredLocally", true).put("live800msSelectiveBudgetTested", false)
            .put("gemmaProviderDeadlineMs", 10_000).put("style", "AUTO")
            .put("draftsPreparedBeforeGemmaLoad", true)
            .put("sampler", JSONObject().put("topK", 1).put("topP", 1.0).put("temperature", 0.0).put("seed", 7))
            .put("deviceApi", Build.VERSION.SDK_INT).put("deviceAbis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("broadcastCapabilitySupported", GemmaBroadcastCapability.detect(app).supported)
        val memory = ActivityManager.MemoryInfo()
        (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
        result.put("deviceTotalMemoryBytes", memory.totalMem)
        val started = SystemClock.elapsedRealtime()
        fun save() { output.parentFile?.mkdirs(); output.writeText(result.toString(2)) }
        save()
        val gemma = app.gemmaTranslationProvider
        try {
            app.withTranslationBackendUse {
                withTimeout(600_000L) {
                    val mlKit = MlKitTranslationProvider(app, "ko", requireWifiForModels = false)
                    try {
                        val preparing = SystemClock.elapsedRealtime()
                        withTimeout(180_000L) { mlKit.modelManager.prepare(setOf("en")) }
                        result.put("mlKitPreparationMs", SystemClock.elapsedRealtime() - preparing)
                        val draftEngine = mlKit.engineFor("en")
                        repeat(cases.length()) { index ->
                            val item = cases.getJSONObject(index)
                            val original = item.getString("originalText")
                            val context = item.getString("contextBefore")
                            check(original.isNotBlank() && original.length <= 600 && context.length <= 400)
                            val row = JSONObject().put("id", item.getString("id")).put("kind", item.getString("kind"))
                                .put("noDraftControl", noDraftControl).put("draftPassedToGemma", !noDraftControl)
                                .put("originalText", original).put("contextBefore", context)
                                .put("sourceUrl", item.opt("sourceUrl") ?: JSONObject.NULL)
                                .put("sourceResultSha256", item.opt("sourceResultSha256") ?: JSONObject.NULL)
                                .put("sourceSequence", item.opt("sourceSequence") ?: JSONObject.NULL)
                                .put("sourcePcmSha256", item.opt("sourcePcmSha256") ?: JSONObject.NULL)
                                .put("draftState", "RUNNING").put("reviewState", "PENDING")
                            rows.put(row)
                            val before = SystemClock.elapsedRealtime()
                            try {
                                val draft = withTimeout(30_000L) { draftEngine.translate(original, "ko", "en") }
                                check(draft.isNotBlank() && draft.length <= 1_200) { "MALFORMED_DRAFT" }
                                row.put("draftState", "COMPLETED").put("draftTranslation", draft)
                                    .put("draftAssets", assess(item, draft))
                            } catch (error: Exception) {
                                currentCoroutineContext().ensureActive()
                                row.put("draftState", "FAILED").put("draftErrorType", error.javaClass.simpleName)
                                    .put("draftErrorDiagnostic", safeErrorDiagnostic(error))
                                failures.put("${item.getString("id")}:DRAFT_FAILED")
                            } finally {
                                row.put("draftElapsedMs", SystemClock.elapsedRealtime() - before)
                                save()
                            }
                        }
                    } finally {
                        mlKit.close()
                    }
                    // No model fetch: a missing, altered or incompatible model is a hard failure.
                    gemma.selectModel(GemmaModelVariant.STANDARD)
                    val manager = gemma.modelManager
                    val expectedModel = fixture.getJSONObject("model")
                    check(expectedModel.getString("sha256") == GemmaModelVariant.STANDARD.sha256 &&
                        expectedModel.getLong("bytes") == GemmaModelVariant.STANDARD.sizeBytes &&
                        expectedModel.getString("revision") == GemmaModelVariant.STANDARD.revision)
                    check(manager.modelFile.isFile && manager.modelFile.length() == expectedModel.getLong("bytes")) {
                        "PINNED_STANDARD_MODEL_MUST_BE_STAGED_FIRST"
                    }
                    val preparing = SystemClock.elapsedRealtime()
                    manager.refresh()
                    check(manager.status.value.readiness in setOf(GemmaModelReadiness.VERIFIED, GemmaModelReadiness.READY)) {
                        "MODEL_FILE_VERIFICATION_FAILED"
                    }
                    result.put("model", JSONObject().put("variant", manager.selectedVariant.id)
                        .put("bytes", manager.modelFile.length()).put("sha256", manager.selectedVariant.sha256)
                        .put("revision", manager.selectedVariant.revision)
                        .put("hashVerification", "GemmaModelManager.refresh pinned file verification contract")
                        .put("readinessBeforeWarmup", manager.status.value.readiness.name))
                    app.withProcessNativeColdLoadLease(ProcessNativeColdLoadKeys.GEMMA_MODEL, true) {
                        gemma.warmup("en", "ko", timeoutMillis = 120_000L)
                    }
                    check(gemma.hasActivePreparedWorker()) { "NO_PREPARED_GEMMA_WORKER" }
                    result.put("gemmaPreparationMs", SystemClock.elapsedRealtime() - preparing)
                        .put("state", "REVIEWING").put("preparedGemmaWorker", true)
                    save()
                    val reviewer = gemma.engineFor("en")
                    repeat(cases.length()) { index ->
                        val item = cases.getJSONObject(index)
                        val row = rows.getJSONObject(index)
                        if (row.getString("draftState") != "COMPLETED") {
                            row.put("reviewState", "FAILED_NO_VALID_DRAFT")
                            return@repeat
                        }
                        val original = item.getString("originalText")
                        val context = item.getString("contextBefore")
                        val draft = row.getString("draftTranslation")
                        val before = SystemClock.elapsedRealtime()
                        row.put("reviewState", "RUNNING").put("reviewStage", "CHECK_PREPARED_WORKER")
                            .put("preparedGemmaBeforeReview", gemma.hasActivePreparedWorker())
                            .put("automaticRetryBlockedBeforeReview", gemma.isAutomaticRetryBlocked())
                            .put("nativeTicketPresentBeforeReview", currentNativeColdLoadTicket() != null)
                        try {
                            check(gemma.hasActivePreparedWorker() && !gemma.isAutomaticRetryBlocked()) {
                                "PREPARED_REVIEWER_UNAVAILABLE"
                            }
                            val requestContext = if (noDraftControl) TranslationStyleContext(TranslationStyle.AUTO)
                                else TranslationReviewContext(original, draft, "ko", "en",
                                    setOf(SelectiveRefinementReason.STYLE_REQUESTED)) + TranslationStyleContext(TranslationStyle.AUTO)
                            row.put("reviewStage", "ACQUIRE_NATIVE_ADMISSION")
                            val reviewed = withTimeout(15_000L) {
                                app.withProcessNativeColdLoadLease(ProcessNativeColdLoadKeys.GEMMA_MODEL, true) {
                                    withContext(requestContext) {
                                        row.put("reviewStage", "CALL_GEMMA_PROVIDER")
                                        reviewer.translateWithContext(original, context, "ko", "en")
                                    }
                                }
                            }
                            check(reviewed.isNotBlank() && reviewed.length <= 1_200) { "MALFORMED_REVIEW" }
                            val assessment = assess(item, reviewed)
                            row.put("reviewState", "COMPLETED").put("reviewedTranslation", reviewed)
                                .put("reviewAssets", assessment).put("translationChanged", reviewed != draft)
                            if (!assessment.getBoolean("passed")) failures.put("${item.getString("id")}:MEANING_ASSET_FAILED")
                        } catch (error: Exception) {
                            currentCoroutineContext().ensureActive()
                            row.put("reviewState", "FAILED").put("reviewErrorType", error.javaClass.simpleName)
                                .put("reviewFailureStage", row.getString("reviewStage"))
                                .put("reviewErrorDiagnostic", safeErrorDiagnostic(error))
                            failures.put("${item.getString("id")}:REVIEW_FAILED")
                        } finally {
                            row.put("reviewElapsedMs", SystemClock.elapsedRealtime() - before)
                                .put("preparedGemmaAfterReview", gemma.hasActivePreparedWorker())
                            save()
                        }
                    }
                }
            }
            val completed = (0 until rows.length()).count { rows.getJSONObject(it).optString("reviewState") == "COMPLETED" }
            val passed = (0 until rows.length()).count {
                rows.getJSONObject(it).optJSONObject("reviewAssets")?.optBoolean("passed") == true
            }
            result.put("completedReviews", completed).put("passedSemanticAssetCases", passed)
            val success = rows.length() == 10 && completed == 10 && passed == 10 && failures.length() == 0
            result.put("state", if (success) "SEMANTIC_ASSET_GATES_PASSED" else "SEMANTIC_ASSET_GATES_FAILED")
        } catch (error: Throwable) {
            result.put("failedStage", result.optString("state")).put("state", "FAILED")
                .put("failureType", error.javaClass.simpleName)
                .put("failureDiagnostic", safeErrorDiagnostic(error))
            failures.put("SETUP_OR_RUN_FAILED")
            throw error
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                val cleanup = runCatching { gemma.resetEngineSafely() }
                result.put("reviewerCleanupSucceeded", cleanup.isSuccess)
                if (cleanup.isFailure) {
                    result.put("state", "REVIEWER_CLEANUP_FAILED")
                        .put("cleanupFailureType", cleanup.exceptionOrNull()?.javaClass?.simpleName)
                    failures.put("REVIEWER_CLEANUP_FAILED")
                }
                result.put("elapsedMs", SystemClock.elapsedRealtime() - started)
                save()
            }
        }
        assertEquals("Real contextual semantic asset failures; inspect ${output.name}",
            "SEMANTIC_ASSET_GATES_PASSED", result.getString("state"))
    }

    private fun assess(item: JSONObject, text: String): JSONObject {
        val missing = JSONArray()
        val rejected = JSONArray()
        val assets = item.getJSONArray("requiredAssets")
        repeat(assets.length()) { index ->
            val asset = assets.getJSONObject(index)
            if (!Regex(asset.getString("pattern"), RegexOption.IGNORE_CASE).containsMatchIn(text)) {
                missing.put(asset.getString("name"))
            }
        }
        val forbidden = item.getJSONArray("forbiddenPatterns")
        repeat(forbidden.length()) { index ->
            if (Regex(forbidden.getString(index), RegexOption.IGNORE_CASE).containsMatchIn(text)) rejected.put(index)
        }
        return JSONObject().put("requiredAssetCount", assets.length()).put("matchedAssetCount", assets.length() - missing.length())
            .put("missingAssets", missing).put("forbiddenPatternIndexes", rejected)
            .put("passed", missing.length() == 0 && rejected.length() == 0)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    /** Do not persist arbitrary provider messages: they can contain source or generated text. */
    private fun safeErrorDiagnostic(error: Throwable): JSONObject {
        val code = when (error.message) {
            "GEMMA_UNSUPPORTED_TRANSLATION_STYLE" -> "GEMMA_UNSUPPORTED_TRANSLATION_STYLE"
            "GEMMA_UNTRANSLATED_SOURCE_COPY" -> "GEMMA_UNTRANSLATED_SOURCE_COPY"
            "Failed requirement." -> "ARGUMENT_CONTRACT_REJECTED"
            "PREPARED_REVIEWER_UNAVAILABLE" -> "PREPARED_REVIEWER_UNAVAILABLE"
            "A native cold-load operation cannot acquire a nested process lease" -> "NESTED_NATIVE_ADMISSION"
            "MALFORMED_REVIEW", "MALFORMED_DRAFT" -> "MALFORMED_TRANSLATION"
            "모델 실행 점검·적용 중입니다. 완료 후 통역을 시작하세요." -> "MODEL_APPLICATION_IN_PROGRESS"
            else -> "UNCLASSIFIED_PROVIDER_ERROR"
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
    }
}
