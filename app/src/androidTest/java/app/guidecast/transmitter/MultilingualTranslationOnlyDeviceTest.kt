package app.guidecast.transmitter

import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import app.guidecast.core.translation.ModelReadiness
import app.guidecast.provider.mlkit.translation.MlKitTranslationProvider
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seven equivalent, authored three-sentence corpora through the actual isolated ML Kit provider.
 * This is translation-only: no microphone, STT, user corrections, Gemma, cloud API or human score.
 * Missing platform-managed translation models may download during preparation. Each pair's
 * catalog state before/after preparation is recorded; transfer bytes/offline operation are not
 * inferred. Run on a dedicated test device with dedicatedCorpusDevice=true.
 */
class MultilingualTranslationOnlyDeviceTest {
    @Test fun recordsSevenSourceTranslationsAndCriticalFactReviewFlags(): Unit = runBlocking {
        require(InstrumentationRegistry.getArguments().getString("dedicatedCorpusDevice") == "true") {
            "DEDICATED_TRANSLATION_TEST_DEVICE_REQUIRED"
        }
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            as GuideCastApplication
        val directory = File(checkNotNull(app.getExternalFilesDir(null)), "benchmark")
        check(directory.isDirectory || directory.mkdirs()) { "TEST_EVIDENCE_DIRECTORY_UNAVAILABLE" }
        val output = File(directory, "multilingual-translation-only-${System.currentTimeMillis()}.json")
        val rows = JSONArray()
        val pairs = JSONArray()
        val result = JSONObject()
            .put("schemaVersion", 1).put("state", "RUNNING")
            .put("startedAtUtc", Instant.now().toString())
            .put("scope", "TRANSLATION_ONLY_AUTHORED_CRITICAL_FACT_ANCHORS")
            .put("provider", "ML_KIT_LOCAL_ISOLATED_WORKER")
            .put("sourceLanguages", JSONArray(CORPUS.keys.toList()))
            .put("expectedCases", 21).put("asrExecuted", false)
            .put("microphoneUsed", false).put("externalAiApiUsed", false)
            .put("humanAdequacyAssessment", false).put("physicalDeviceVerificationPerformed", false)
            .put("qualityVerdict", "NOT_ASSESSED")
            .put("anchorInterpretation", "Lexical flags require meaning review; missing synonyms are not proven translation errors")
            .put("modelDownloadsAllowed", true).put("modelDownloadBytesMeasured", false)
            .put("offlineExecutionVerified", false)
            .put("executionOrder", "SEQUENTIAL_LANGUAGE_PAIRS_AND_CASES")
            .put("fixtureSha256", sha256(CORPUS.entries.joinToString("\n") { (language, lines) ->
                language + "\n" + lines.joinToString("\n")
            }))
            .put("deviceApi", Build.VERSION.SDK_INT)
            .put("deviceAbis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("pairs", pairs).put("cases", rows)
        fun save() = output.writeText(result.toString(2))
        save()
        val provider = MlKitTranslationProvider(app, "ko", requireWifiForModels = false)
        val started = SystemClock.elapsedRealtime()
        try {
            app.withTranslationBackendUse {
                withTimeout(30 * 60_000L) {
                    CORPUS.forEach { (source, lines) ->
                        val target = if (source == "ko") "en" else "ko"
                        val pair = JSONObject().put("sourceLanguage", source).put("targetLanguage", target)
                            .put("state", "PREPARING").put("catalogBefore", "NOT_QUERIED")
                            .put("catalogAfter", "NOT_QUERIED").put("modelDownloadBytes", JSONObject.NULL)
                            .put("errorType", JSONObject.NULL)
                        pairs.put(pair)
                        val caseRows = lines.mapIndexed { index, text ->
                            JSONObject().put("id", "$source-${CASE_IDS[index]}")
                                .put("meaningCase", CASE_IDS[index]).put("sourceLanguage", source)
                                .put("targetLanguage", target).put("sourceText", text)
                                .put("referenceKind", "AUTHORED_TEST_SENTENCES")
                                .put("provider", "ML_KIT_LOCAL_ISOLATED_WORKER")
                                .put("state", "NOT_RUN").put("translation", JSONObject.NULL)
                                .put("elapsedMs", JSONObject.NULL).put("errorType", JSONObject.NULL)
                                .put("criticalFactsPassed", false).also { rows.put(it) }
                        }
                        save()
                        val preparationStart = SystemClock.elapsedRealtime()
                        var prepared = false
                        try {
                            withTimeout(180_000L) {
                                // Exercise source selection as well as worker warmup, including fr/de.
                                provider.selectSourceLanguage(source)
                                provider.modelManager.refresh(setOf(target))
                                val before = provider.modelManager.statuses.value.single { it.languageTag == target }
                                pair.put("catalogBefore", before.readiness.name)
                                pair.put("pairArtifactsMissingBefore", before.readiness == ModelReadiness.NOT_INSTALLED)
                                save()
                                provider.prepareModels(setOf(target), source,
                                    isNativeOwnerCurrent = { true }, reconcileNativeTargets = true,
                                ) { language, initialize ->
                                    app.withProcessNativeColdLoadLease(
                                        ProcessNativeColdLoadKeys.mlKitTranslation(language), true,
                                    ) { initialize() }
                                }
                                provider.modelManager.refresh(setOf(target))
                                val after = provider.modelManager.statuses.value.single { it.languageTag == target }
                                pair.put("catalogAfter", after.readiness.name)
                                pair.put("nativeWorkerPrepared", provider.hasActivePreparedWorker(target))
                                check(after.readiness == ModelReadiness.READY && provider.hasActivePreparedWorker(target)) {
                                    "TRANSLATION_PAIR_NOT_READY"
                                }
                            }
                            prepared = true
                            pair.put("state", "READY")
                        } catch (error: Exception) {
                            currentCoroutineContext().ensureActive()
                            pair.put("state", "PREPARATION_FAILED").put("errorType", error.javaClass.simpleName)
                            caseRows.forEach { it.put("state", "NOT_RUN_PREPARATION_FAILED") }
                        } finally {
                            pair.put("preparationElapsedMs", SystemClock.elapsedRealtime() - preparationStart)
                            save()
                        }
                        if (prepared) {
                            val engine = provider.engineFor(target)
                            caseRows.forEachIndexed { index, row ->
                                row.put("state", "RUNNING")
                                save()
                                val caseStart = SystemClock.elapsedRealtime()
                                try {
                                    val translated = withTimeout(30_000L) {
                                        engine.translate(lines[index], source, target)
                                    }
                                    check(translated.isNotBlank() && translated.length <= 2_000) {
                                        "EMPTY_OR_OVERSIZED_TRANSLATION"
                                    }
                                    val missing = criticalFacts(target, index).filterNot { (_, pattern) ->
                                        Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(translated)
                                    }.keys
                                    row.put("state", "COMPLETED").put("translation", translated)
                                        .put("missingCriticalFacts", JSONArray(missing.toList()))
                                        .put("criticalFactsPassed", missing.isEmpty())
                                } catch (error: Exception) {
                                    currentCoroutineContext().ensureActive()
                                    row.put("state", "FAILED").put("errorType", error.javaClass.simpleName)
                                } finally {
                                    row.put("elapsedMs", SystemClock.elapsedRealtime() - caseStart)
                                    save()
                                }
                            }
                        }
                    }
                }
            }
            val completed = (0 until rows.length()).count { rows.getJSONObject(it).getString("state") == "COMPLETED" }
            val passed = (0 until rows.length()).count { rows.getJSONObject(it).getBoolean("criticalFactsPassed") }
            val success = rows.length() == 21 && completed == 21
            result.put("completedCases", completed).put("passedCriticalFactCases", passed)
                .put("flaggedCriticalFactCases", completed - passed)
                .put("qualityVerdict", if (!success) "INCOMPLETE" else if (passed < 21) "REVIEW_REQUIRED" else "ANCHORS_PRESENT_NOT_HUMAN_VERIFIED")
                .put("state", if (!success) "TRANSLATION_EXECUTION_INCOMPLETE" else if (passed < 21)
                    "TRANSLATIONS_COMPLETED_REVIEW_REQUIRED" else "TRANSLATIONS_COMPLETED_ANCHORS_PRESENT")
            assertTrue("Translation-only execution: $completed/21 completed; $passed/21 lexical-anchor matches are not a quality certification; see ${output.name}", success)
        } catch (error: Throwable) {
            if (result.getString("state") == "RUNNING") result.put("state", "INCOMPLETE")
            result.put("terminalErrorType", error.javaClass.simpleName)
            throw error
        } finally {
            provider.close()
            result.put("endedAtUtc", Instant.now().toString())
                .put("elapsedMs", SystemClock.elapsedRealtime() - started)
            save()
        }
    }

    private fun criticalFacts(target: String, index: Int): Map<String, String> =
        if (target == "en") when (index) {
            0 -> mapOf("room" to "\\broom\\b", "entry" to "\\benter|\\bentry|\\baccess|\\bgo\\s+in",
                "negation" to "\\bnot\\b|n't|\\bno\\b|prohibit|forbid")
            1 -> mapOf("admission_fee" to "admission|entrance|entry|ticket",
                "15_dollars" to "\\b(?:15|fifteen)\\s*(?:US\\s*)?(?:dollars?|USD)\\b|\\$\\s*15\\b")
            else -> mapOf("3_people" to "\\b(?:3|three)\\s+(?:people|persons?|individuals?)\\b",
                "5_minutes" to "\\b(?:5|five)\\s+minutes?\\b", "arrival" to "arriv|\\bcome\\b")
        } else when (index) {
            0 -> mapOf("room" to "방|객실|공간", "entry" to "들어|입장|출입",
                "negation" to "안\\s*(?:됩|되)|않|마세|마십|말아|금지|불가|수\\s*없")
            1 -> mapOf("admission_fee" to "입장|입구|티켓",
                "15_dollars" to "(?<![0-9])(?:15|열다섯|십오)\\s*(?:미국\\s*)?(?:달러|불)")
            else -> mapOf("3_people" to "(?<![0-9])(?:3|세|삼)\\s*(?:명|사람)",
                "5_minutes" to "(?<![0-9])(?:5|다섯|오)\\s*분", "arrival" to "도착")
        }

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private companion object {
        val CASE_IDS = listOf("negation", "number-unit", "people-time")
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
