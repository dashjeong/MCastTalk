package app.guidecast.transmitter

import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import app.guidecast.core.translation.TranslationStyle
import app.guidecast.core.translation.TranslationStyleContext
import app.guidecast.provider.gemma.translation.GemmaModelReadiness
import app.guidecast.provider.gemma.translation.GemmaModelVariant
import app.guidecast.provider.gemma.translation.GemmaTranslationProvider
import java.io.File
import java.security.MessageDigest
import java.time.Instant
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
 * Direct Gemma translations of the same authored inputs as MultilingualTranslationOnlyDeviceTest.
 * The ML Kit baseline is joined by case ID on the host, never supplied to Gemma as a draft/hint.
 * Requires dedicatedCorpusDevice=true and the already staged, pinned STANDARD model. No download,
 * STT, TTS, API, fallback or corrections. JUnit verifies execution only; whole-meaning comparison
 * remains a separate review. French/German are explicitly not run because Gemma does not support
 * those pairs in this product; their six rows cannot count toward the fifteen completed cases.
 */
class MultilingualGemmaComparisonDeviceTest {
    @Test fun recordsActualGemmaTranslationsForFiveSupportedSourceLanguages(): Unit = runBlocking {
        require(InstrumentationRegistry.getArguments().getString("dedicatedCorpusDevice") == "true") {
            "DEDICATED_TRANSLATION_TEST_DEVICE_REQUIRED"
        }
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            as GuideCastApplication
        val directory = File(checkNotNull(app.getExternalFilesDir(null)), "benchmark")
        check(directory.isDirectory || directory.mkdirs()) { "TEST_EVIDENCE_DIRECTORY_UNAVAILABLE" }
        val output = File(directory, "multilingual-gemma-comparison-${System.currentTimeMillis()}.json")
        val rows = JSONArray()
        val pairs = JSONArray()
        CORPUS.forEach { (source, lines) ->
            val target = if (source == "ko") "en" else "ko"
            val supported = source in SUPPORTED_TEST_SOURCES
            lines.forEachIndexed { index, text ->
                rows.put(JSONObject().put("id", "$source-${CASE_IDS[index]}")
                    .put("meaningCase", CASE_IDS[index]).put("sourceLanguage", source)
                    .put("targetLanguage", target).put("sourceText", text)
                    .put("semanticIntentForReviewOnly", SEMANTIC_INTENTS[index])
                    .put("referenceKind", "AUTHORED_TEST_SENTENCES")
                    .put("provider", "GEMMA_LOCAL_DIRECT_SOURCE")
                    .put("state", if (supported) "NOT_RUN" else "UNSUPPORTED_SOURCE_NOT_RUN")
                    .put("appProviderSupportsPair", GemmaTranslationProvider.supportsTranslation(source, target))
                    .put("translationExecuted", false).put("fallbackUsed", false)
                    .put("translation", JSONObject.NULL).put("elapsedMs", JSONObject.NULL)
                    .put("errorType", JSONObject.NULL))
            }
        }
        val result = JSONObject().put("schemaVersion", 1).put("state", "PREPARING")
            .put("startedAtUtc", Instant.now().toString())
            .put("scope", "TRANSLATION_ONLY_DIRECT_GEMMA_AUTHORED_COMPARISON")
            .put("provider", "GEMMA_LOCAL_DIRECT_SOURCE")
            .put("sourceLanguages", JSONArray(CORPUS.keys.toList()))
            .put("expectedExecutedCases", 15).put("expectedUnsupportedCases", 6)
            .put("asrExecuted", false).put("microphoneUsed", false).put("ttsExecuted", false)
            .put("externalAiApiUsed", false).put("modelDownloadsAllowed", false)
            .put("networkIsolationTested", false).put("fallbackUsed", false)
            .put("mlKitDraftProvidedToModel", false).put("userCorrectionsUsed", false)
            .put("priorSourceContextUsed", false).put("style", "AUTO")
            .put("semanticIntentProvidedToModel", false)
            .put("qualityVerdict", "REVIEW_REQUIRED")
            .put("humanAdequacyAssessment", false).put("physicalDeviceVerificationPerformed", false)
            .put("callerRequestDeadlineMs", 15_000).put("productionProviderDeadlineMs", 10_000)
            .put("executionOrder", "SEQUENTIAL_LANGUAGE_PAIRS_AND_CASES_NO_RETRIES")
            .put("fixtureSha256", sha256(CORPUS.entries.joinToString("\n") { (language, lines) ->
                language + "\n" + lines.joinToString("\n")
            }))
            .put("baselineResultsSha256", BASELINE_SHA256)
            .put("baselineComparison", "HOST_JOIN_BY_CASE_ID_NOT_SENT_TO_MODEL")
            .put("deviceApi", Build.VERSION.SDK_INT)
            .put("deviceAbis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("pairs", pairs).put("cases", rows)
        fun save() = output.writeText(result.toString(2))
        save()
        val gemma = app.gemmaTranslationProvider
        val started = SystemClock.elapsedRealtime()
        try {
            app.withTranslationBackendUse {
                withTimeout(15 * 60_000L) {
                    gemma.selectModel(GemmaModelVariant.STANDARD)
                    val manager = gemma.modelManager
                    check(manager.modelFile.isFile && manager.modelFile.length() == GemmaModelVariant.STANDARD.sizeBytes) {
                        "PINNED_STANDARD_MODEL_MUST_ALREADY_BE_STAGED"
                    }
                    manager.refresh()
                    check(manager.status.value.readiness in setOf(GemmaModelReadiness.VERIFIED, GemmaModelReadiness.READY)) {
                        "PINNED_MODEL_VERIFICATION_FAILED"
                    }
                    result.put("model", JSONObject().put("variant", manager.selectedVariant.id)
                        .put("bytes", manager.modelFile.length()).put("sha256", manager.selectedVariant.sha256)
                        .put("revision", manager.selectedVariant.revision)
                        .put("verification", "GemmaModelManager.refresh pinned artifact contract"))
                    result.put("state", "TRANSLATING")
                    CORPUS.keys.forEach { source ->
                        val target = if (source == "ko") "en" else "ko"
                        val supported = source in SUPPORTED_TEST_SOURCES
                        check(GemmaTranslationProvider.supportsTranslation(source, target) == supported) {
                            "PRODUCT_LANGUAGE_SUPPORT_CHANGED_REVIEW_TEST_SCOPE"
                        }
                        if (supported) {
                            val caseRows = (0 until rows.length()).map { rows.getJSONObject(it) }
                                .filter { it.getString("sourceLanguage") == source }
                            val pair = JSONObject().put("sourceLanguage", source).put("targetLanguage", target)
                                .put("state", "WARMING").put("errorType", JSONObject.NULL)
                            pairs.put(pair)
                            save()
                            val preparationStart = SystemClock.elapsedRealtime()
                            var prepared = false
                            try {
                                app.withProcessNativeColdLoadLease(ProcessNativeColdLoadKeys.GEMMA_MODEL, true) {
                                    gemma.warmup(target, source, timeoutMillis = 120_000L)
                                }
                                check(gemma.hasActivePreparedWorker() && !gemma.isAutomaticRetryBlocked()) {
                                    "PREPARED_GEMMA_WORKER_UNAVAILABLE"
                                }
                                prepared = true
                                pair.put("state", "READY")
                            } catch (error: Exception) {
                                currentCoroutineContext().ensureActive()
                                pair.put("state", "WARMUP_FAILED").put("errorType", error.javaClass.simpleName)
                                caseRows.forEach { it.put("state", "NOT_RUN_WARMUP_FAILED") }
                            } finally {
                                pair.put("warmupElapsedMs", SystemClock.elapsedRealtime() - preparationStart)
                                save()
                            }
                            if (prepared) {
                                val engine = gemma.engineFor(target)
                                caseRows.forEach { row ->
                                    row.put("state", "RUNNING").put("translationExecuted", true)
                                    save()
                                    val before = SystemClock.elapsedRealtime()
                                    try {
                                        val translated = withTimeout(15_000L) {
                                            app.withProcessNativeColdLoadLease(ProcessNativeColdLoadKeys.GEMMA_MODEL, true) {
                                                withContext(TranslationStyleContext(TranslationStyle.AUTO)) {
                                                    engine.translate(row.getString("sourceText"), source, target)
                                                }
                                            }
                                        }
                                        check(translated.isNotBlank() && translated.length <= 1_200) {
                                            "EMPTY_OR_OVERSIZED_TRANSLATION"
                                        }
                                        row.put("state", "COMPLETED").put("translation", translated)
                                    } catch (error: Exception) {
                                        currentCoroutineContext().ensureActive()
                                        row.put("state", "FAILED").put("errorType", error.javaClass.simpleName)
                                    } finally {
                                        row.put("elapsedMs", SystemClock.elapsedRealtime() - before)
                                        save()
                                    }
                                }
                            }
                        }
                    }
                }
            }
            val completed = (0 until rows.length()).count { rows.getJSONObject(it).getString("state") == "COMPLETED" }
            val unsupported = (0 until rows.length()).count { rows.getJSONObject(it).getString("state") == "UNSUPPORTED_SOURCE_NOT_RUN" }
            result.put("completedCases", completed).put("unsupportedNotRunCases", unsupported)
                .put("state", if (completed == 15 && unsupported == 6)
                    "TRANSLATIONS_COMPLETED_REVIEW_REQUIRED" else "TRANSLATION_EXECUTION_INCOMPLETE")
        } catch (error: Throwable) {
            result.put("state", "INCOMPLETE").put("terminalErrorType", error.javaClass.simpleName)
            throw error
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                val cleanup = runCatching { gemma.resetEngineSafely() }
                result.put("cleanupSucceeded", cleanup.isSuccess)
                if (cleanup.isFailure) result.put("state", "CLEANUP_FAILED")
                    .put("cleanupErrorType", cleanup.exceptionOrNull()?.javaClass?.simpleName)
                result.put("elapsedMs", SystemClock.elapsedRealtime() - started)
                    .put("endedAtUtc", Instant.now().toString())
                save()
            }
        }
        assertEquals("Execution only; whole-meaning comparison requires separate review: ${output.name}",
            "TRANSLATIONS_COMPLETED_REVIEW_REQUIRED", result.getString("state"))
    }

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private companion object {
        const val BASELINE_SHA256 = "3a45ac19c2c471b171065e900aa4ec8a015314d3600e9230a41e8569f11d017b"
        val SUPPORTED_TEST_SOURCES = setOf("ko", "en", "ja", "zh", "es")
        val CASE_IDS = listOf("negation", "number-unit", "people-time")
        val SEMANTIC_INTENTS = listOf(
            "Prohibit entering this room; preserve negation scope and the room referent.",
            "The admission/entry fee is fifteen dollars, not a school enrollment fee; preserve currency and amount.",
            "Three people arrive five minutes later; preserve people versus minutes and later versus an upper-bound deadline.",
        )
        val CORPUS = linkedMapOf(
            "ko" to listOf("이 방에 들어가지 마세요.", "입장료는 15달러입니다.", "3명이 5분 후에 도착합니다."),
            "en" to listOf("Do not enter this room.", "The admission fee is 15 dollars.", "Three people will arrive in 5 minutes."),
            "ja" to listOf("この部屋に入らないでください。", "入場料は15ドルです。", "3人が5分後に到着します。"),
            "zh" to listOf("请不要进入这个房间。", "入场费是15美元。", "3个人将在5分钟后到达。"),
            "fr" to listOf("N'entrez pas dans cette pièce.", "Le prix d'entrée est de 15 dollars.", "Trois personnes arriveront dans 5 minutes."),
            "de" to listOf("Betreten Sie diesen Raum nicht.", "Der Eintritt kostet 15 Dollar.", "Drei Personen werden in 5 Minuten ankommen."),
            "es" to listOf("No entre en esta habitación.", "La entrada cuesta 15 dólares.", "Tres personas llegarán en 5 minutos."),
        )
    }
}
