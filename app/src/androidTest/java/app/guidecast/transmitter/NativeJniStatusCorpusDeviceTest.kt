package app.guidecast.transmitter

import ai.moonshine.voice.JNI
import ai.moonshine.voice.ModelCache
import ai.moonshine.voice.ModelSpec
import ai.moonshine.voice.Transcript
import ai.moonshine.voice.TranscriberOption
import android.app.ActivityManager
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Opt-in, offline-only status probe. One staged public Korean case per instrumentation run.
 * Args: dedicatedCorpusDevice=true, dedicatedNativeJniProbe=true, jniCorpusManifest=<relative JSON>.
 * Outputs benchmark/jni-status-results/<case>-<timestamp>.json; never stores recognized text.
 * Calls public JNI directly so integer status returns cannot be discarded by the SDK wrapper.
 */
class NativeJniStatusCorpusDeviceTest {
    @Test fun recordsNativeStatusesForAssignedPublicKoreanPcm(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        require(args.getString("dedicatedCorpusDevice") == "true" &&
            args.getString("dedicatedNativeJniProbe") == "true") { "DEDICATED_JNI_PROBE_OPT_IN_REQUIRED" }
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as GuideCastApplication
        val base = File(checkNotNull(app.getExternalFilesDir(null)), "benchmark").canonicalFile
        val manifestFile = safeChild(base, requireNotNull(args.getString("jniCorpusManifest")))
        require(manifestFile.isFile && manifestFile.length() in 1..8_388_608)
        val manifest = JSONObject(manifestFile.readText())
        require(manifest.getInt("schemaVersion") == 1 && manifest.getJSONArray("cases").length() == 1)
        val variant = manifest.optString("diagnosticVariant", "BASELINE")
        require(variant in setOf("BASELINE", "VAD_035", "BASELINE_12S", "VAD_0_12S")) { "UNSUPPORTED_DIAGNOSTIC_VARIANT" }
        val item = manifest.getJSONArray("cases").getJSONObject(0)
        val id = item.getString("id").also { require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,79}"))) }
        val directory = safeChild(base, "jni-status-results").apply { check(isDirectory || mkdirs()) }
        val output = File(directory, "$id-$variant-${System.currentTimeMillis()}.json")
        val result = JSONObject().put("schemaVersion", 1).put("id", id).put("state", "VALIDATING")
            .put("engine", "DIRECT_SDK_PUBLIC_JNI").put("sdkVersion", "0.1.5")
            .put("diagnosticVariant", variant)
            .put("manifestSha256", hash(manifestFile)).put("sdkInt", Build.VERSION.SDK_INT)
            .put("semanticAssemblerUsed", false).put("watchdogUsed", false)
            .put("recognizedTextStored", false).put("qualityStatus", "NOT_SCORED_NATIVE_DIAGNOSTIC")
        val failures = JSONArray()
        result.put("failures", failures)
        write(output, result)
        try {
            val audio = validateAudio(base, item, variant.endsWith("_12S"))
            result.put("pcmSha256", hash(audio)).put("pcmBytes", audio.length())
                .put("audioDurationMs", item.getLong("durationMs"))
                .put("sourceUrl", item.getString("sourceUrl")).put("state", "VALIDATING_CACHED_MODEL")
            // Same directory and exact three pinned Korean TINY files as the production service.
            // No preparation/download API is called, even if a cache entry is absent or invalid.
            val spec = ModelSpec.stt("ko", JNI.MOONSHINE_MODEL_ARCH_TINY, false)
            val models = ModelCache.directoryFor(app, spec, null).canonicalFile
            val modelHashes = JSONObject()
            PINNED_MODELS.forEach { (name, expected) ->
                val file = File(models, name)
                check(file.isFile && !Files.isSymbolicLink(file.toPath())) { "JNI_PROBE_CACHED_MODEL_MISSING" }
                val actual = hash(file)
                check(actual == expected) { "JNI_PROBE_CACHED_MODEL_HASH_MISMATCH" }
                modelHashes.put(name, actual)
            }
            result.put("modelHashes", modelHashes).put("modelCacheKey", ModelCache.key(spec))
            val manager = app.getSystemService(ActivityManager::class.java)
            val memory = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
            val compatible = Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q ||
                memory.totalMem < 7L * 1024 * 1024 * 1024 || manager.isLowRamDevice
            // Mirror MoonshineRealtimeSttOptions in this diagnostic; never change production VAD.
            val options = arrayOf(
                TranscriberOption("transcription_interval", if (compatible) "0.50" else "0.35"),
                TranscriberOption("vad_threshold", when (variant) {
                    "VAD_035" -> "0.35"
                    "VAD_0_12S" -> "0"
                    else -> "0.58"
                }),
                TranscriberOption("vad_window_duration", "0.30"),
                TranscriberOption("vad_max_segment_duration", "12.0"),
                TranscriberOption("max_tokens_per_second", "13.0"),
            )
            result.put("options", JSONObject().also { obj -> options.forEach { obj.put(it.name, it.value) } })
                .put("modelArch", JNI.MOONSHINE_MODEL_ARCH_TINY)
                .put("modelLanguage", "ko").put("note9CompatibilityProfile", compatible)
            app.withTranslationBackendUse {
                app.withProcessNativeColdLoadLease(ProcessNativeColdLoadKeys.speechRecognition("ko"), true) {
                    withContext(Dispatchers.Default) {
                        withTimeout(item.getLong("durationMs") + 120_000L) {
                            probe(models, options, audio, result, output, failures, variant.endsWith("_12S"))
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            result.put("state", if (cancelled is TimeoutCancellationException) "TIMED_OUT" else "INTERRUPTED")
                .put("errorType", cancelled.javaClass.simpleName)
            failures.put(cancelled.javaClass.simpleName)
            if (cancelled !is TimeoutCancellationException) throw cancelled
        } catch (error: Throwable) {
            result.put("failedAtStage", result.optString("state")).put("state", "FAILED")
                .put("errorType", error.javaClass.simpleName)
            failures.put("JNI_PROBE_EXCEPTION")
        } finally {
            write(output, result)
        }
        assertEquals("JNI probe status/output failure; inspect ${output.name}", "EXECUTION_COMPLETED", result.getString("state"))
    }

    private suspend fun probe(models: File, options: Array<TranscriberOption>, audio: File,
        result: JSONObject, output: File, failures: JSONArray, publicShortText: Boolean) {
        val histogram = JSONObject()
        result.put("statusHistogram", histogram).put("firstNonzeroStatus", JSONObject.NULL)
        val polls = JSONArray()
        result.put("transcriptPolls", polls)
        var frameIndex = 0L
        var attemptedBytes = 0L
        var acceptedBytes = 0L
        var statusErrors = 0L
        var emptyTextObservations = 0L
        var lineObservations = 0L
        var newLineObservations = 0L
        var completeLineObservations = 0L
        var nullTranscripts = 0L
        val nonemptyFinalIds = mutableSetOf<Long>()
        val diagnosticLines = JSONObject()
        if (publicShortText) result.put("recognizedTextStored", true).put("publicDiagnosticLines", diagnosticLines)
        val started = SystemClock.elapsedRealtime()
        val digest = MessageDigest.getInstance("SHA-256")
        var inputEof = false
        var stopped = false
        var transcriber = -1
        var stream = -1
        fun record(stage: String, code: Int, handle: Boolean = false): Boolean {
            val stageCounts = histogram.optJSONObject(stage) ?: JSONObject().also { histogram.put(stage, it) }
            stageCounts.put(code.toString(), stageCounts.optLong(code.toString()) + 1L)
            val successful = if (handle) code >= 0 else code == JNI.MOONSHINE_ERROR_NONE
            if (!successful) {
                statusErrors++
                if (result.isNull("firstNonzeroStatus")) result.put("firstNonzeroStatus", JSONObject()
                    .put("stage", stage).put("code", code).put("frameIndex", frameIndex)
                    .put("attemptedAudioBytes", attemptedBytes).put("elapsedMs", SystemClock.elapsedRealtime()-started))
                failures.put("JNI_STATUS_$stage")
                write(output, result)
            }
            return successful
        }
        fun inspect(transcript: Transcript?, eof: Boolean) {
            if (transcript == null) {
                nullTranscripts++
                failures.put("NULL_NATIVE_TRANSCRIPT")
                return
            }
            val lines = transcript.lines.orEmpty()
            check(lines.size <= 10_000 && polls.length() < 500) { "JNI_PROBE_RESULT_LIMIT" }
            var empty = 0
            lines.forEach { line ->
                lineObservations++
                if (line.text.isNullOrBlank()) { empty++; emptyTextObservations++ }
                if (line.isNew) newLineObservations++
                if (publicShortText && !line.text.isNullOrBlank()) {
                    check(diagnosticLines.length() < 128 || diagnosticLines.has(line.id.toString()))
                    diagnosticLines.put(line.id.toString(), JSONObject()
                        .put("text", line.text.take(2000)).put("complete", line.isComplete))
                }
                if (line.isComplete) {
                    completeLineObservations++
                    if (!line.text.isNullOrBlank()) nonemptyFinalIds.add(line.id)
                }
            }
            check(nonemptyFinalIds.size <= 10_000)
            polls.put(JSONObject().put("frameIndex", frameIndex).put("audioBytes", attemptedBytes)
                .put("eofForcedUpdate", eof).put("lineCount", lines.size).put("emptyTextLines", empty)
                .put("completeLines", lines.count { it.isComplete }).put("elapsedMs", SystemClock.elapsedRealtime()-started))
        }
        try {
            result.put("state", "JNI_LOADING"); write(output, result)
            JNI.ensureLibraryLoaded()
            result.put("nativeVersion", JNI.moonshineGetVersion())
            transcriber = JNI.moonshineLoadTranscriberFromFiles(models.absolutePath, JNI.MOONSHINE_MODEL_ARCH_TINY, options)
            check(record("load", transcriber, handle = true)) { "JNI_LOAD_FAILED" }
            stream = JNI.moonshineCreateStream(transcriber, 0)
            check(record("create", stream, handle = true)) { "JNI_CREATE_FAILED" }
            check(record("start", JNI.moonshineStartStream(transcriber, stream))) { "JNI_START_FAILED" }
            result.put("state", "RUNNING"); write(output, result)
            var sincePollSeconds = 0.0
            var lastPollSeconds = 0.0
            audio.inputStream().buffered().use { input ->
                val block = ByteArray(640)
                while (true) {
                    var count = 0
                    while (count < block.size) {
                        val read = input.read(block, count, block.size-count)
                        if (read < 0) break
                        count += read
                    }
                    if (count == 0) { inputEof = true; break }
                    check(count % 2 == 0)
                    val floats = FloatArray(count/2) { i ->
                        (((block[i*2+1].toInt() shl 8) or (block[i*2].toInt() and 255)).toShort()).toFloat()/32768f
                    }
                    digest.update(block, 0, count)
                    attemptedBytes += count
                    check(record("add", JNI.moonshineAddAudioToStream(transcriber, stream, floats, 16000, 0))) { "JNI_ADD_FAILED" }
                    acceptedBytes += count
                    frameIndex++
                    sincePollSeconds += count/32000.0
                    // Match SDK 0.1.5 Java scheduling: 0.5 s minimum, last-pass adaptation capped at 5 s.
                    if (sincePollSeconds >= maxOf(0.5, lastPollSeconds).coerceAtMost(5.0)) {
                        val beforePoll = SystemClock.elapsedRealtimeNanos()
                        inspect(JNI.moonshineTranscribeStream(transcriber, stream, 0), eof = false)
                        lastPollSeconds = (SystemClock.elapsedRealtimeNanos()-beforePoll)/1e9
                        sincePollSeconds = 0.0
                        if (polls.length() % 10 == 0) write(output, result)
                    }
                    delay((count/32).toLong().coerceAtLeast(1))
                }
            }
            val stopCode = JNI.moonshineStopStream(transcriber, stream)
            stopped = true
            check(record("stop", stopCode)) { "JNI_STOP_FAILED" }
            inspect(JNI.moonshineTranscribeStream(transcriber, stream, JNI.MOONSHINE_FLAG_FORCE_UPDATE), eof = true)
        } finally {
            // A failed/negative call can never become a passing result during cleanup.
            if (transcriber >= 0 && stream >= 0) {
                if (!stopped) runCatching { record("cleanupStop", JNI.moonshineStopStream(transcriber, stream)) }
                    .onFailure { failures.put("JNI_CLEANUP_STOP_EXCEPTION") }
                runCatching { record("freeStream", JNI.moonshineFreeStream(transcriber, stream)) }
                    .onFailure { failures.put("JNI_FREE_STREAM_EXCEPTION") }
            }
            if (transcriber >= 0) runCatching { JNI.moonshineFreeTranscriber(transcriber) }
                .onSuccess { result.put("freeTranscriberReturned", true) }
                .onFailure { failures.put("JNI_FREE_TRANSCRIBER_EXCEPTION") }
            result.put("framesAttempted", frameIndex).put("bytesAttempted", attemptedBytes)
                .put("bytesWithSuccessfulJniAdd", acceptedBytes).put("inputHashWhileFeeding", digest.digest().hex())
                .put("inputReachedEof", inputEof).put("stopCalled", stopped)
                .put("nonzeroStatusCount", statusErrors).put("nullTranscriptCount", nullTranscripts)
                .put("lineObservationCount", lineObservations).put("emptyTextObservationCount", emptyTextObservations)
                .put("newLineObservationCount", newLineObservations).put("completeLineObservationCount", completeLineObservations)
                .put("uniqueNonemptyFinalLines", nonemptyFinalIds.size).put("elapsedMs", SystemClock.elapsedRealtime()-started)
            if (attemptedBytes != audio.length() || acceptedBytes != audio.length()) failures.put("INPUT_NOT_FULLY_ACCEPTED")
            if (result.getString("inputHashWhileFeeding") != result.getString("pcmSha256")) failures.put("INPUT_HASH_MISMATCH")
            if (!inputEof || !stopped) failures.put("NO_NORMAL_EOF")
            if (nonemptyFinalIds.isEmpty()) failures.put("NO_NONEMPTY_NATIVE_FINALS")
            result.put("jniStatusVerdict", if (statusErrors == 0L) "NO_NONZERO_RETURN_RECORDED" else "FAILED")
                .put("speechOutputVerdict", if (nonemptyFinalIds.isEmpty()) "NO_NONEMPTY_FINALS" else "FINALS_OBSERVED_NOT_QUALITY_SCORED")
                .put("state", if (failures.length() == 0) "EXECUTION_COMPLETED" else "FAILED")
            write(output, result)
        }
    }

    private fun validateAudio(base: File, item: JSONObject, shortGateProbe: Boolean): File {
        require(item.getString("language") == "ko" && item.getInt("sampleRateHz") == 16000 && item.getInt("channels") == 1)
        require(item.getString("encoding") == "PCM_S16LE")
        require(if (shortGateProbe) item.getLong("durationMs") == 12_000L
            else item.getLong("durationMs") in 120_000..180_000)
        val uri = URI(item.getString("sourceUrl"))
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null)
        require(item.getString("license").isNotBlank() && item.getString("license").length <= 160)
        val expected = item.getString("sha256").also { require(it.matches(Regex("[a-f0-9]{64}"))) }
        return safeChild(base, item.getString("pcmPath")).also {
            require(it.isFile && it.length() == item.getLong("durationMs")*32 && it.length()%2 == 0L)
            require(hash(it) == expected) { "JNI_PROBE_PCM_HASH_MISMATCH" }
        }
    }
    private fun safeChild(base: File, relative: String): File {
        require(relative.length in 1..300 && relative.matches(Regex("[A-Za-z0-9_./-]+")))
        require(!File(relative).isAbsolute && relative.split('/').none { it == ".." || it.isEmpty() })
        return File(base, relative).canonicalFile.also { require(it.path.startsWith(base.path+File.separator)) }
    }
    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val block = ByteArray(65536)
            while (true) { val n = input.read(block); if (n < 0) break; digest.update(block, 0, n) }
        }
        return digest.digest().hex()
    }
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
    private fun write(file: File, value: JSONObject) {
        val pending = File(file.parentFile, file.name+".part")
        pending.writeText(value.toString(2)); check(pending.renameTo(file)) { "JNI_PROBE_RESULT_WRITE_FAILED" }
    }
    private companion object {
        val PINNED_MODELS = mapOf(
            "encoder_model.ort" to "947260d46252f48eada86a34986b3f70c01d68a343959949a77375b94debd055",
            "tokenizer.bin" to "6884b35fd6377d4c4d32336a0bc152f36b64d1e45b6503683cdc238250a8472d",
            "decoder_model_merged.ort" to "95aa9f2e764b80625d2889d6ec9f05c965808e540ac50c16abd10c7ea33fe44b",
        )
    }
}
