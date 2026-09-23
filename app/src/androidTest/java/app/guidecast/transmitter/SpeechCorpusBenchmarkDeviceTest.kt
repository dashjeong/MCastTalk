package app.guidecast.transmitter

import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import app.guidecast.core.stream.PcmAudioFrame
import app.guidecast.core.translation.ContextualTextTranslationEngine
import app.guidecast.core.translation.RecognizedUtterance
import app.guidecast.core.translation.SpeechRecognitionConfig
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Opt-in, public-corpus instrumentation. This runs the shipped PCM recognition and local ML Kit
 * paths; execution completion never means semantic quality passed. No microphone/user archive,
 * cloud API, synthetic recognizer or diagnostic export is read by this harness.
 *
 * corpusManifest names a JSON file below target externalFilesDir/benchmark. Required schema:
 * {schemaVersion:1,batchId,cases:[{id,language,pcmPath,sha256,durationMs,sourceUrl,license,
 * referenceText,referenceKind,referenceUrl,sampleRateHz:16000,channels:1,encoding:"PCM_S16LE"}]}.
 * referenceKind="unavailable" permits empty/null referenceText and null referenceUrl, and records
 * reference-based quality scoring as unavailable. Execution can complete without a quality pass.
 * Languages: ko/en/ja/zh/fr/de/es. Each case is genuine-source PCM of 120..600 seconds; source
 * authenticity and licensing remain independently reviewable manifest claims, not inferred facts.
 */
class SpeechCorpusBenchmarkDeviceTest {
    @Test
    fun runAssignedPublicSpeechCorpus() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        require(arguments.getString("dedicatedCorpusDevice") == "true") {
            "Use a dedicated benchmark emulator and set dedicatedCorpusDevice=true"
        }
        val manifestName = requireNotNull(arguments.getString("corpusManifest")) {
            "corpusManifest must identify the assigned public benchmark manifest"
        }
        val app = instrumentation.targetContext.applicationContext as GuideCastApplication
        val base = File(requireNotNull(app.getExternalFilesDir(null)) {
            "CORPUS_SETUP_EXTERNAL_STORAGE_UNAVAILABLE: benchmark storage is not available on this device"
        }, "benchmark").canonicalFile
        val manifestFile = safeChild(base, manifestName)
        require(manifestFile.isFile) {
            "CORPUS_SETUP_MANIFEST_MISSING: stage the assigned manifest below this device's target externalFilesDir/benchmark before running; a cloned device may not contain the original external storage"
        }
        require(manifestFile.length() in 1..MAX_MANIFEST_BYTES) {
            "CORPUS_SETUP_MANIFEST_SIZE_INVALID: the assigned manifest is empty or exceeds the safety limit"
        }
        val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))
        require(manifest.getInt("schemaVersion") == 1)
        val batchId = safeId(manifest.getString("batchId"))
        val cases = manifest.getJSONArray("cases")
        require(cases.length() in 1..210)
        val ids = (0 until cases.length()).map { safeId(cases.getJSONObject(it).getString("id")) }
        require(ids.distinct().size == ids.size)
        val runId = "$batchId-${System.currentTimeMillis()}"
        val outputDirectory = safeChild(base, "results/$runId").apply { check(mkdirs()) }
        val summary = JSONObject().put("schemaVersion", 1).put("runId", runId)
            .put("batchId", batchId).put("manifestSha256", hash(manifestFile))
            .put("sdkInt", Build.VERSION.SDK_INT).put("deviceModel", Build.MODEL)
            .put("environment", "dedicated-instrumentation-device")
            .put("qualityStatus", "PENDING_INDEPENDENT_REVIEW")
            .put("cases", JSONArray())
        val summaryFile = File(outputDirectory, "summary.json")
        atomicWrite(summaryFile, summary.toString(2))
        val failed = mutableListOf<String>()
        for (index in 0 until cases.length()) {
            currentCoroutineContext().ensureActive()
            val item = cases.getJSONObject(index)
            val id = ids[index]
            val referenceUnavailable = item.optString("referenceKind") == "unavailable"
            val resultFile = File(outputDirectory, "$id.json")
            val result = JSONObject().put("id", id).put("state", "VALIDATING")
                .put("qualityStatus", if (referenceUnavailable) "REFERENCE_UNAVAILABLE_NOT_SCORED" else "PENDING_INDEPENDENT_REVIEW")
                .put("referenceBasedScoringAvailable", !referenceUnavailable)
                .put("referenceScoringUnavailableReason", if (referenceUnavailable) "NO_REFERENCE_TRANSCRIPT" else JSONObject.NULL)
                .put("reference", item).put("finalUnits", JSONArray())
                .put("rawProviderEvents", JSONArray()).put("failures", JSONArray())
            atomicWrite(resultFile, result.toString(2))
            try {
                val audio = validateCase(base, item)
                result.put("validatedPcmSha256", audio.sha256).put("pcmBytes", audio.file.length())
                    .put("nonzeroSamples", audio.nonzeroSamples).put("peakSample", audio.peak)
                val language = item.getString("language")
                val supported = language in GUIDECAST_SOURCE_LANGUAGE_BASE_TAGS &&
                    app.speechRecognitionEngine.capability(language).available
                if (!supported) {
                    result.put("state", "UNSUPPORTED_ASR")
                        .put("unsupportedScope", if (language !in GUIDECAST_SOURCE_LANGUAGE_BASE_TAGS)
                            "APP_SOURCE_LANGUAGE" else "DEVICE_PCM_RECOGNIZER")
                } else {
                    executeCase(app, audio, language, result, resultFile)
                }
            } catch (cancelled: CancellationException) {
                result.put("state", if (cancelled is TimeoutCancellationException) "TIMED_OUT" else "INTERRUPTED")
                result.getJSONArray("failures").put(cancelled.javaClass.simpleName)
                atomicWrite(resultFile, result.toString(2))
                if (cancelled !is TimeoutCancellationException) throw cancelled
            } catch (error: Exception) {
                // Exception text can contain arbitrary paths/provider text. Keep type and stage only.
                result.put("failedAtStage", result.optString("state")).put("state", "FAILED")
                result.getJSONArray("failures").put(error.javaClass.simpleName)
            } finally {
                atomicWrite(resultFile, result.toString(2))
            }
            val state = result.getString("state")
            if (state != "EXECUTION_COMPLETED") failed += "$id:$state"
            summary.getJSONArray("cases").put(JSONObject().put("id", id).put("state", state)
                .put("result", "$id.json").put("qualityStatus", result.getString("qualityStatus"))
                .put("referenceBasedScoringAvailable", result.getBoolean("referenceBasedScoringAvailable")))
            summary.put("finishedCases", index + 1).put("executionFailures", failed.size)
            atomicWrite(summaryFile, summary.toString(2))
        }
        summary.put("state", if (failed.isEmpty()) "EXECUTION_COMPLETED" else "EXECUTION_INCOMPLETE")
        atomicWrite(summaryFile, summary.toString(2))
        assertTrue("Corpus execution failures (not quality verdict): ${failed.joinToString()}", failed.isEmpty())
    }

    private suspend fun executeCase(
        app: GuideCastApplication,
        audio: ValidatedAudio,
        language: String,
        result: JSONObject,
        resultFile: File,
    ) = app.withTranslationBackendUse {
        val target = if (language == "ko") "en" else "ko"
        val owner = app.beginSettingsPreparation(language, setOf(target))
        val preparationStarted = SystemClock.elapsedRealtime()
        result.put("state", "PREPARING").put("translationEngine", "GOOGLE_MLKIT_LOCAL")
            .put("targetLanguage", target)
        atomicWrite(resultFile, result.toString(2))
        try {
            withTimeout(300_000) {
                app.selectTranslationSource(owner)
                check(app.prepareSpeechRecognitionWithProcessAdmission(language, true, owner).isReady)
                app.prepareTranslationModelsWithProcessAdmission(setOf(target), true, owner)
            }
            result.put("preparationMs", SystemClock.elapsedRealtime() - preparationStarted)
            val translator = app.translationProvider.engineFor(target)
            result.put("translatorAcceptsContext", translator is ContextualTextTranslationEngine)
                .put("state", "RUNNING")
            atomicWrite(resultFile, result.toString(2))
            val lock = Any()
            val pending = linkedMapOf<Long, RecognizedUtterance>()
            val finalized = linkedSetOf<Long>()
            val timeline = FileMediaTimeline(maximumFrames = 32_000)
            val finalUnits = result.getJSONArray("finalUnits")
            val rawEvents = result.getJSONArray("rawProviderEvents")
            var rawCharacters = 0
            var finalCharacters = 0
            var rawCaptureOverflow = false
            var lastFinalSequence = -1L
            var partialCount = 0
            var lastTranslatedSequence = -1L
            var bytesEmitted = 0L
            var framesEmitted = 0L
            val started = SystemClock.elapsedRealtimeNanos()
            val inputEnded = CompletableDeferred<Unit>()
            val actualInputHash = MessageDigest.getInstance("SHA-256")
            var collectionError: String? = null
            var collectionCompleted = false
            val backendNames = linkedSetOf<String>()
            val inspection = RecognitionInspectionContext { attempt, backend, value, accepted ->
                synchronized(lock) {
                    backendNames += backend
                    if (rawEvents.length() >= MAX_PROVIDER_EVENTS ||
                        rawCharacters + value.text.length > MAX_RESULT_CHARACTERS) {
                        rawCaptureOverflow = true
                    } else {
                        rawCharacters += value.text.length
                        rawEvents.put(JSONObject().put("attemptId", attempt).put("backend", backend)
                            .put("providerSequence", value.sequence).put("providerFinal", value.isFinal)
                            .put("acceptedByProviderGate", accepted).put("text", value.text)
                            .put("receivedElapsedMs", elapsedMs(started))
                            .put("estimatedAudioStartMs", timeline.mediaMillis(value.capturedAtElapsedRealtimeNanos)
                                ?: JSONObject.NULL))
                    }
                }
            }
            try {
                withTimeout(audio.durationMs + 60_000) {
                    coroutineScope {
                        val translationQueue = Channel<Pair<RecognizedUtterance, JSONObject>>(32)
                        val translate = launch {
                            for ((utterance, row) in translationQueue) {
                                val translationStarted = SystemClock.elapsedRealtime()
                                try {
                                    check(utterance.sequence > lastTranslatedSequence)
                                    val text = withTimeout(20_000) {
                                        if (translator is ContextualTextTranslationEngine) {
                                            translator.translateWithContext(utterance.text, utterance.contextBefore, language, target)
                                        } else translator.translate(utterance.text, language, target)
                                    }
                                    check(text.isNotBlank())
                                    synchronized(lock) {
                                        row.put("translation", text).put("translationState", "COMPLETED")
                                    }
                                } catch (cancelled: CancellationException) {
                                    if (cancelled !is TimeoutCancellationException) throw cancelled
                                    synchronized(lock) { row.put("translationState", "TIMED_OUT") }
                                } catch (error: Exception) {
                                    synchronized(lock) {
                                        row.put("translationState", "FAILED").put("translationErrorType", error.javaClass.simpleName)
                                    }
                                } finally {
                                    lastTranslatedSequence = utterance.sequence
                                    synchronized(lock) {
                                        row.put("translationMs", SystemClock.elapsedRealtime() - translationStarted)
                                        atomicWrite(resultFile, result.toString(2))
                                    }
                                }
                            }
                        }
                        val recognize = launch {
                            try {
                                withContext(inspection) {
                                    val frames = flow {
                                        audio.file.inputStream().buffered().use { input ->
                                            val block = ByteArray(FRAME_BYTES)
                                            while (true) {
                                                var count = 0
                                                while (count < block.size) {
                                                    val read = input.read(block, count, block.size - count)
                                                    if (read < 0) break
                                                    count += read
                                                }
                                                if (count == 0) break
                                                check(count % 2 == 0)
                                                check(app.speechRecognitionEngine.status.value.isReady)
                                                val captured = SystemClock.elapsedRealtimeNanos()
                                                timeline.record(captured, bytesEmitted / 32, (bytesEmitted + count) / 32)
                                                val pcm = block.copyOf(count)
                                                actualInputHash.update(pcm)
                                                emit(PcmAudioFrame(pcm, captured))
                                                bytesEmitted += count
                                                framesEmitted++
                                                delay((count / 32).toLong().coerceAtLeast(1))
                                            }
                                        }
                                        // Continuous actual quiet observations, not a synthetic elapsed-time jump.
                                        repeat(400) {
                                            val captured = SystemClock.elapsedRealtimeNanos()
                                            timeline.record(captured, audio.durationMs, audio.durationMs)
                                            emit(PcmAudioFrame(ByteArray(FRAME_BYTES), captured))
                                            delay(20)
                                        }
                                        inputEnded.complete(Unit)
                                    }
                                    app.speechRecognitionEngine.recognize(frames, SpeechRecognitionConfig(language))
                                        .collect { event ->
                                            var translationItem: Pair<RecognizedUtterance, JSONObject>? = null
                                            synchronized(lock) {
                                                if (event.isRetracted) pending.remove(event.sequence)
                                                else if (!event.isFinal) {
                                                    check(event.sequence !in finalized)
                                                    pending[event.sequence] = event
                                                    check(pending.size <= 64)
                                                    partialCount++
                                                } else {
                                                    check(event.sequence > lastFinalSequence && finalized.add(event.sequence))
                                                    check(finalUnits.length() < MAX_FINAL_UNITS)
                                                    finalCharacters += event.text.length
                                                    check(finalCharacters <= MAX_RESULT_CHARACTERS)
                                                    lastFinalSequence = event.sequence
                                                    pending.remove(event.sequence)
                                                    val row = JSONObject().put("sequence", event.sequence)
                                                        .put("sourceText", event.text).put("translationRequestText", event.text)
                                                        .put("contextBefore", event.contextBefore ?: JSONObject.NULL)
                                                        .put("sourceLanguage", event.sourceLanguageTag)
                                                        .put("finalReceivedElapsedMs", elapsedMs(started))
                                                        .put("estimatedAudioStartMs", timeline.mediaMillis(event.capturedAtElapsedRealtimeNanos)
                                                            ?: JSONObject.NULL)
                                                        .put("audioBytesEmittedAtFinal", bytesEmitted)
                                                        .put("translationState", "QUEUED")
                                                    finalUnits.put(row)
                                                    translationItem = event to row
                                                    atomicWrite(resultFile, result.toString(2))
                                                }
                                            }
                                            translationItem?.let { translationQueue.send(it) }
                                        }
                                    collectionCompleted = true
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                collectionError = error.javaClass.simpleName
                            } finally {
                                inputEnded.complete(Unit)
                                translationQueue.close()
                            }
                        }
                        try {
                            inputEnded.await()
                            withTimeoutOrNull(15_000) { recognize.join() }
                        } finally {
                            recognize.cancelAndJoin()
                        }
                        withTimeout(20_000) { translate.join() }
                    }
                }
            } finally {
                synchronized(lock) {
                    result.put("asrBackendsObserved", JSONArray(backendNames.toList()))
                        .put("recognizerCompletedAtEof", collectionCompleted)
                        .put("postSourceQuietPcmMs", 8_000)
                        .put("collectionErrorType", collectionError ?: JSONObject.NULL)
                        .put("rawCaptureTruncated", rawCaptureOverflow)
                        .put("partialCallbackCount", partialCount)
                        .put("framesEmitted", framesEmitted).put("bytesEmitted", bytesEmitted)
                        .put("inputHashWhileFeeding", actualInputHash.digest().hex())
                        .put("elapsedMs", elapsedMs(started))
                        .put("audioDurationMs", audio.durationMs)
                        .put("pendingAtStop", JSONArray(pending.values.map { event ->
                            JSONObject().put("sequence", event.sequence).put("text", event.text)
                        }))
                    val failures = result.getJSONArray("failures")
                    if (bytesEmitted != audio.file.length()) failures.put("INPUT_NOT_FULLY_CONSUMED")
                    if (result.getString("inputHashWhileFeeding") != audio.sha256) failures.put("INPUT_HASH_MISMATCH")
                    if (pending.isNotEmpty()) failures.put("UNRESOLVED_PENDING_TAIL")
                    if (rawCaptureOverflow) failures.put("RAW_CAPTURE_LIMIT")
                    if (collectionError != null) failures.put("ASR_COLLECTION_FAILED")
                    if (!collectionCompleted) failures.put("NO_NORMAL_ASR_EOF")
                    if (finalUnits.length() == 0) failures.put("NO_SEMANTIC_FINALS")
                    if ((0 until finalUnits.length()).any {
                            finalUnits.getJSONObject(it).optString("translationState") != "COMPLETED"
                        }) failures.put("TRANSLATION_INCOMPLETE")
                    result.put("state", if (failures.length() == 0) "EXECUTION_COMPLETED" else "FAILED")
                    atomicWrite(resultFile, result.toString(2))
                }
            }
        } finally { app.endPreparation(owner) }
    }

    private data class ValidatedAudio(val file: File, val sha256: String, val durationMs: Long,
        val nonzeroSamples: Long, val peak: Int)

    private fun validateCase(base: File, item: JSONObject): ValidatedAudio {
        require(item.getString("language") in setOf("ko", "en", "ja", "zh", "fr", "de", "es"))
        require(item.getInt("sampleRateHz") == 16_000 && item.getInt("channels") == 1)
        require(item.getString("encoding") == "PCM_S16LE")
        val duration = item.getLong("durationMs")
        require(duration in 120_000..600_000)
        val referenceKind = item.getString("referenceKind")
        require(referenceKind in setOf("human", "publisher", "asr-unverified", "unavailable"))
        val urlFields = if (referenceKind == "unavailable") {
            require(item.isNull("referenceText") || item.opt("referenceText") == "") {
                "An unavailable reference must not contain an invented transcript"
            }
            require(item.isNull("referenceUrl")) { "An unavailable reference must have a null reference URL" }
            listOf("sourceUrl")
        } else {
            require(!item.isNull("referenceText") && item.opt("referenceText") is String &&
                item.getString("referenceText").isNotBlank() && item.getString("referenceText").length <= 200_000) {
                "A declared human/publisher/asr-unverified reference requires a nonempty transcript"
            }
            require(!item.isNull("referenceUrl") && item.opt("referenceUrl") is String)
            listOf("sourceUrl", "referenceUrl")
        }
        urlFields.forEach { name ->
            val uri = URI(item.getString(name))
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null)
        }
        require(item.getString("license").isNotBlank() && item.getString("license").length <= 160)
        val expected = item.getString("sha256").lowercase(Locale.ROOT)
        require(expected.matches(Regex("[a-f0-9]{64}")))
        val file = safeChild(base, item.getString("pcmPath"))
        require(file.isFile && file.length() in 3_840_000..19_200_000)
        require(file.length() % 2 == 0L && file.length() / 32 == duration)
        val digest = MessageDigest.getInstance("SHA-256")
        var nonzero = 0L
        var peak = 0
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(65_536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(count % 2 == 0)
                digest.update(buffer, 0, count)
                for (index in 0 until count step 2) {
                    val sample = ((buffer[index].toInt() and 255) or (buffer[index + 1].toInt() shl 8)).toShort().toInt()
                    if (sample != 0) nonzero++
                    peak = maxOf(peak, kotlin.math.abs(sample))
                }
            }
        }
        val actual = digest.digest().hex()
        require(actual == expected && nonzero > 0)
        return ValidatedAudio(file, actual, duration, nonzero, peak)
    }

    private fun safeChild(base: File, relative: String): File {
        require(relative.isNotBlank() && relative.length <= 300 && !File(relative).isAbsolute)
        require(relative.split('/', '\\').none { it == ".." || it.isEmpty() })
        return File(base, relative).canonicalFile.also { require(it.path.startsWith(base.path + File.separator)) }
    }

    private fun safeId(value: String): String = value.also { require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,79}"))) }
    private fun elapsedMs(started: Long): Long = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000
    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(65_536)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
        return digest.digest().hex()
    }
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
    private fun atomicWrite(file: File, text: String) {
        val temporary = File(file.parentFile, file.name + ".part")
        temporary.writeText(text, Charsets.UTF_8)
        check(temporary.renameTo(file)) { "Benchmark result replacement failed" }
    }

    private companion object {
        const val FRAME_BYTES = 640
        const val MAX_MANIFEST_BYTES = 8L * 1024 * 1024
        const val MAX_PROVIDER_EVENTS = 10_000
        const val MAX_FINAL_UNITS = 1_000
        const val MAX_RESULT_CHARACTERS = 2_000_000
    }
}
