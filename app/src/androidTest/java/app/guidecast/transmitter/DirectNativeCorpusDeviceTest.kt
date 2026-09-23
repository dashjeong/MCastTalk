package app.guidecast.transmitter

import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import app.guidecast.core.stream.PcmAudioFrame
import app.guidecast.core.translation.SpeechRecognitionConfig
import app.guidecast.provider.moonshine.stt.MoonshineSpeechRecognitionEngine
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/** Opt-in direct native comparison using the same staged public Korean PCM as the app corpus. */
class DirectNativeCorpusDeviceTest {
    @Test fun directNativeConsumesAssignedKoreanCorpusThroughNormalEof(): Unit = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        require(arguments.getString("dedicatedCorpusDevice") == "true") {
            "Use a dedicated corpus device with dedicatedCorpusDevice=true"
        }
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as GuideCastApplication
        val base = File(checkNotNull(app.getExternalFilesDir(null)), "benchmark").canonicalFile
        val manifestFile = safeChild(base, requireNotNull(arguments.getString("nativeCorpusManifest")))
        require(manifestFile.isFile) { "NATIVE_CORPUS_SETUP_MANIFEST_MISSING" }
        require(manifestFile.length() in 1..8_388_608)
        val manifest = JSONObject(manifestFile.readText())
        require(manifest.getInt("schemaVersion") == 1)
        val cases = manifest.getJSONArray("cases")
        require(cases.length() in 1..2) { "Direct diagnostic is bounded to two assigned cases" }
        val ids = (0 until cases.length()).map {
            cases.getJSONObject(it).getString("id").also { id ->
                require(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,79}")))
            }
        }
        require(ids.distinct().size == ids.size)
        val outputDirectory = safeChild(base, "direct-native-results")
        check(outputDirectory.isDirectory || outputDirectory.mkdirs())
        val failed = mutableListOf<String>()
        for (index in ids.indices) {
            val id = ids[index]
            val item = cases.getJSONObject(index)
            val resultFile = File(outputDirectory, "$id-${System.currentTimeMillis()}.json")
            val result = JSONObject().put("schemaVersion", 1).put("id", id)
                .put("state", "VALIDATING").put("engine", "DIRECT_MOONSHINE_NATIVE")
                .put("galaxySemanticAssemblerUsed", false).put("galaxyWatchdogUsed", false)
                .put("manifestSha256", hash(manifestFile)).put("sdkInt", Build.VERSION.SDK_INT)
                .put("environment", "DEDICATED_ANDROID_INSTRUMENTATION")
                .put("qualityStatus", "NOT_SCORED_DIAGNOSTIC_COMPARISON")
                .put("rawProviderEvents", JSONArray())
            writeResult(resultFile, result)
            try {
                val audio = validateAudio(base, item)
                result.put("validatedPcmSha256", audio.sha256).put("pcmBytes", audio.file.length())
                    .put("audioDurationMs", audio.durationMs).put("sourceUrl", item.getString("sourceUrl"))
                    .put("license", item.getString("license")).put("state", "PREPARING")
                writeResult(resultFile, result)
                app.withTranslationBackendUse {
                    MoonshineSpeechRecognitionEngine(app).use { engine ->
                        val ready = withTimeout(300_000L) {
                            engine.prepareLanguageAssets("ko")
                            app.withProcessNativeColdLoadLease(
                                ProcessNativeColdLoadKeys.speechRecognition("ko"), true,
                            ) { engine.prepareNativeLanguage("ko") }
                        }
                        check(ready.isReady) { "NATIVE_PREPARATION_UNAVAILABLE" }
                        execute(engine, audio, result, resultFile)
                    }
                }
            } catch (cancelled: CancellationException) {
                result.put("state", if (cancelled is TimeoutCancellationException) "TIMED_OUT" else "INTERRUPTED")
                    .put("errorType", cancelled.javaClass.simpleName)
                if (cancelled !is TimeoutCancellationException) throw cancelled
            } catch (error: Exception) {
                result.put("failedAtStage", result.getString("state")).put("state", "FAILED")
                    .put("errorType", error.javaClass.simpleName)
            } finally {
                writeResult(resultFile, result)
            }
            if (result.getString("state") != "EXECUTION_COMPLETED") failed += "$id:${result.getString("state")}"
        }
        assertTrue("Direct native diagnostic execution failures: ${failed.joinToString()}", failed.isEmpty())
    }

    private suspend fun execute(engine: MoonshineSpeechRecognitionEngine, audio: Audio,
        result: JSONObject, resultFile: File) {
        val inputHash = MessageDigest.getInstance("SHA-256")
        val bytes = AtomicLong(0)
        val events = result.getJSONArray("rawProviderEvents")
        val started = SystemClock.elapsedRealtimeNanos()
        var partialCount = 0
        var finalCount = 0
        var characters = 0
        var inputEof = false
        var normalCompletion = false
        result.put("state", "RUNNING")
        writeResult(resultFile, result)
        try {
            withTimeout(audio.durationMs + 60_000L) {
                engine.recognize(flow {
                    audio.file.inputStream().buffered().use { input ->
                        val block = ByteArray(640)
                        while (true) {
                            var count = 0
                            while (count < block.size) {
                                val read = input.read(block, count, block.size - count)
                                if (read < 0) break
                                count += read
                            }
                            if (count == 0) break
                            check(count % 2 == 0)
                            val pcm = block.copyOf(count)
                            inputHash.update(pcm)
                            emit(PcmAudioFrame(pcm, SystemClock.elapsedRealtimeNanos()))
                            bytes.addAndGet(count.toLong())
                            delay((count / 32).toLong().coerceAtLeast(1))
                        }
                    }
                    // No synthetic endpoint silence and no cancellation used to manufacture EOF.
                    inputEof = true
                }, SpeechRecognitionConfig("ko")).collect { event ->
                    check(events.length() < 10_000 && characters + event.text.length <= 2_000_000) {
                        "NATIVE_CORPUS_RESULT_LIMIT"
                    }
                    characters += event.text.length
                    if (event.isFinal) finalCount++ else partialCount++
                    events.put(JSONObject().put("sequence", event.sequence).put("text", event.text)
                        .put("isFinal", event.isFinal).put("isRetracted", event.isRetracted)
                        .put("capturedAtElapsedRealtimeNanos", event.capturedAtElapsedRealtimeNanos)
                        .put("recognizedAtElapsedRealtimeNanos", event.recognizedAtElapsedRealtimeNanos)
                        .put("receivedElapsedMs", (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000)
                        .put("audioBytesEmittedAtCallback", bytes.get()))
                    if (event.isFinal) writeResult(resultFile, result)
                }
                normalCompletion = true
            }
        } finally {
            result.put("partialCallbackCount", partialCount).put("finalCallbackCount", finalCount)
                .put("bytesEmitted", bytes.get()).put("inputHashWhileFeeding", inputHash.digest().hex())
                .put("inputReachedEof", inputEof).put("recognizerCompletedAtEof", normalCompletion)
                .put("elapsedMs", (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000)
            writeResult(resultFile, result)
        }
        val failures = JSONArray()
        if (bytes.get() != audio.file.length()) failures.put("INPUT_NOT_FULLY_CONSUMED")
        if (result.getString("inputHashWhileFeeding") != audio.sha256) failures.put("INPUT_HASH_MISMATCH")
        if (!inputEof || !normalCompletion) failures.put("NO_NORMAL_EOF")
        if (finalCount == 0) failures.put("NO_NATIVE_FINALS")
        result.put("failures", failures)
            .put("state", if (failures.length() == 0) "EXECUTION_COMPLETED" else "FAILED")
    }

    private data class Audio(val file: File, val sha256: String, val durationMs: Long)

    private fun validateAudio(base: File, item: JSONObject): Audio {
        require(item.getString("language") == "ko")
        require(item.getInt("sampleRateHz") == 16_000 && item.getInt("channels") == 1)
        require(item.getString("encoding") == "PCM_S16LE")
        val duration = item.getLong("durationMs")
        require(duration in 120_000..600_000)
        val source = URI(item.getString("sourceUrl"))
        require(source.scheme == "https" && !source.host.isNullOrBlank() && source.rawUserInfo == null)
        require(item.getString("license").isNotBlank() && item.getString("license").length <= 160)
        val expected = item.getString("sha256")
        require(expected.matches(Regex("[a-f0-9]{64}")))
        val file = safeChild(base, item.getString("pcmPath"))
        require(file.isFile && file.length() in 3_840_000..19_200_000)
        require(file.length() % 2 == 0L && file.length() / 32 == duration)
        require(hash(file) == expected) { "NATIVE_CORPUS_PCM_HASH_MISMATCH" }
        return Audio(file, expected, duration)
    }

    private fun safeChild(base: File, relative: String): File {
        require(relative.length in 1..300 && relative.matches(Regex("[A-Za-z0-9_./-]+")))
        require(!File(relative).isAbsolute && relative.split('/').none { it == ".." || it.isEmpty() })
        return File(base, relative).canonicalFile.also { require(it.path.startsWith(base.path + File.separator)) }
    }

    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65_536)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().hex()
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
    private fun writeResult(file: File, result: JSONObject) {
        val temporary = File(file.parentFile, file.name + ".part")
        temporary.writeText(result.toString(2))
        check(temporary.renameTo(file)) { "NATIVE_CORPUS_RESULT_WRITE_FAILED" }
    }
}
