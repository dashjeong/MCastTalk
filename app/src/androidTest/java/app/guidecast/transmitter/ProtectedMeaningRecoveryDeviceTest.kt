package app.guidecast.transmitter

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.guidecast.core.translation.FailoverTranslationEngineProvider
import app.guidecast.core.translation.ContextualTextTranslationEngine
import app.guidecast.core.translation.TextTranslationEngine
import app.guidecast.core.translation.TranslationEngineProvider
import app.guidecast.core.translation.TranslationStyle
import app.guidecast.core.translation.TranslationStyleContext
import app.guidecast.core.translation.protectedTranslationReviewMessage
import app.guidecast.provider.gemma.translation.GemmaModelVariant
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real native lifecycle + production failover class; no speaker, microphone or user logs. */
@RunWith(AndroidJUnit4::class)
class ProtectedMeaningRecoveryDeviceTest {
    @Test fun reviewFailureKeepsNativeNextRequestUsableWithoutAutomaticFallback() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val application = context.applicationContext as GuideCastApplication
        val native = application.gemmaTranslationProvider
        val original = native.modelManager.selectedVariant
        val lease = requireNotNull(application.acquireTranslationBackendUseIf({ true }))
        val fixtures = File(context.getExternalFilesDir(null), "domain-fixtures/recovery-dev-corpus.json")
        val cases = JSONArray(fixtures.readText())
        val first = (0 until cases.length()).map { cases.getJSONObject(it) }.single { it.getString("id") == "RC05" }
        val rows = JSONArray()
        val result = JSONObject().put("scope", "REAL_E2B_NATIVE_AND_FAILOVER_CLASS_NOT_TTS_OR_LIVE_BROADCAST")
            .put("evaluationMode", "OFFLINE_SEMANTIC_EVALUATION").put("requestBudgetMs", 60_000L)
            .put("model", "standard").put("plannedRows", 2).put("rows", rows)
        val output = File(context.getExternalFilesDir(null), "benchmark/protected-review-native-lifecycle-${System.currentTimeMillis()}.json")
        fun save() = output.writeText(result.toString(2))
        var fallbackCalls = 0
        var failureCallbacks = 0
        try {
            native.applyVerifiedModel(GemmaModelVariant.STANDARD)
            val failover = FailoverTranslationEngineProvider(
                primary = TranslationEngineProvider { native.offlineEvaluationEngineFor(it) },
                fallback = TranslationEngineProvider { TextTranslationEngine { _, _, _ -> fallbackCalls++; error("No automatic fallback permitted for a review item") } },
                onPrimaryFailure = { failureCallbacks++ },
                allowFallbackForPrimaryFailure = { protectedTranslationReviewMessage(it.message) == null },
            )
            // No corpus reference or private active profile is used in this lifecycle probe.
            val engine = failover.engineFor("en") as ContextualTextTranslationEngine
            val requests = listOf(first, JSONObject().put("id", "NEXT_NORMAL_REQUEST").put("source", "오늘 일정은 내일 다시 확인하겠습니다.")
                .put("context", "다음 일정 확인").put("style", "FORMAL"))
            for (request in requests) {
                val source = request.getString("source")
                val style = TranslationStyle.valueOf(request.getString("style"))
                val row = JSONObject().put("id", request.getString("id")).put("source", source)
                    .put("context", request.getString("context")).put("style", style.name).put("state", "RUNNING")
                rows.put(row); save()
                val started = SystemClock.elapsedRealtime()
                try {
                    val translated = withContext(TranslationStyleContext(style)) {
                        engine.translateWithContext(source, request.getString("context"), "ko", "en") }
                    check(translated.isNotBlank())
                    row.put("state", "COMPLETED").put("translation", translated)
                } catch (error: Exception) {
                    val review = protectedTranslationReviewMessage(error.message) != null
                    row.put("state", when {
                        review -> "REVIEW_REQUIRED"
                        error is kotlinx.coroutines.TimeoutCancellationException -> "TIMED_OUT"
                        error is kotlinx.coroutines.CancellationException -> "CANCELLED"
                        else -> "FAILED"
                    }).put("failureClass", error.javaClass.simpleName)
                    if (review) row.put("failureCode", error.message)
                    if (!review) throw error
                } finally {
                    row.put("elapsedMs", SystemClock.elapsedRealtime() - started)
                    row.put("usingFallback", failover.isUsingFallback); save()
                }
            }
            result.put("fallbackCalls", fallbackCalls).put("failureCallbacks", failureCallbacks)
            assertEquals("A new normal native request must complete", "COMPLETED", rows.getJSONObject(1).getString("state"))
            assertEquals(0, fallbackCalls); assertEquals(0, failureCallbacks)
            assertTrue(!failover.isUsingFallback)
            val expectedReview = rows.getJSONObject(0).getString("state") == "REVIEW_REQUIRED" &&
                rows.getJSONObject(0).optString("failureCode") == "GEMMA_VERBATIM_QUOTE_REVIEW_REQUIRED"
            result.put("reviewObserved", expectedReview)
                .put("policyCheck", if (expectedReview) "VERIFIED_FOR_OBSERVED_REQUEST_PAIR" else "INCONCLUSIVE_EXPECTED_REJECTION_NOT_OBSERVED")
            assertTrue("The expected review event must actually be observed; preserve an inconclusive result otherwise", expectedReview)
        } finally {
            try {
                if (native.modelManager.selectedVariant != original) native.applyVerifiedModel(original)
                result.put("cleanupCompleted", true)
            } finally {
                lease.close()
                result.put("attemptedRows", rows.length())
                    .put("completedRows", (0 until rows.length()).count { rows.getJSONObject(it).optString("state") == "COMPLETED" })
                    .put("reviewRequiredRows", (0 until rows.length()).count { rows.getJSONObject(it).optString("state") == "REVIEW_REQUIRED" })
                    .put("failedRows", (0 until rows.length()).count { rows.getJSONObject(it).optString("state") in setOf("FAILED", "TIMED_OUT", "CANCELLED") })
                    .put("notAttemptedRows", 2 - rows.length())
                    .put("fallbackCalls", fallbackCalls).put("failureCallbacks", failureCallbacks)
                save()
            }
        }
    }
}
