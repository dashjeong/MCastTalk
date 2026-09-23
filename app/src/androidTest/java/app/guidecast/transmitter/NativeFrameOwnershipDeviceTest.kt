package app.guidecast.transmitter

import ai.moonshine.voice.JNI
import ai.moonshine.voice.ModelCache
import ai.moonshine.voice.ModelSpec
import ai.moonshine.voice.TranscriberOption
import android.os.Debug
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import app.guidecast.core.audio.pcmS16LeSignalStats
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/** Accelerated synthetic JNI ownership check; neither an audio quality nor elapsed uptime test. */
class NativeFrameOwnershipDeviceTest {
    @Test fun invalidStreamDoesNotCrashAndStoppedStreamCanRestart(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as GuideCastApplication
        val result = JSONObject().put("scope", "NATIVE_STREAM_LIFECYCLE_SYNTHETIC_ZERO_PCM")
            .put("state", "STARTED").put("recognizedTextStored", false)
        val output = File(checkNotNull(app.getExternalFilesDir(null)), "native-stream-lifecycle-${System.currentTimeMillis()}.json")
        output.writeText(result.toString(2))
        try {
            app.withTranslationBackendUse {
                app.withProcessNativeColdLoadLease(ProcessNativeColdLoadKeys.speechRecognition("ko"), true) {
                    withContext(Dispatchers.Default) {
                        val models = ModelCache.directoryFor(app, ModelSpec.stt("ko", JNI.MOONSHINE_MODEL_ARCH_TINY, false), null)
                        check(listOf("encoder_model.ort", "tokenizer.bin", "decoder_model_merged.ort")
                            .all { File(models, it).isFile }) { "CACHED_KOREAN_MODEL_REQUIRED" }
                        JNI.ensureLibraryLoaded()
                        val handle = JNI.moonshineLoadTranscriberFromFiles(models.absolutePath,
                            JNI.MOONSHINE_MODEL_ARCH_TINY, emptyArray())
                        check(handle >= 0)
                        var stream = -1
                        try {
                            var explicitErrors = 0
                            for (operation in listOf("start", "add", "stop")) {
                                try {
                                    when (operation) {
                                        "start" -> JNI.moonshineStartStream(handle, Int.MAX_VALUE)
                                        "add" -> JNI.moonshineAddAudioToStream(handle, Int.MAX_VALUE, FloatArray(320), 16_000, 0)
                                        else -> JNI.moonshineStopStream(handle, Int.MAX_VALUE)
                                    }
                                } catch (_: IllegalStateException) {
                                    explicitErrors++
                                }
                            }
                            result.put("invalidStreamExplicitErrors", explicitErrors)
                            check(explicitErrors == 3) { "INVALID_STREAM_NOT_REJECTED" }
                            JNI.moonshineFreeStream(handle, Int.MAX_VALUE)
                            stream = JNI.moonshineCreateStream(handle, 0)
                            check(stream >= 0)
                            repeat(3) {
                                check(JNI.moonshineStartStream(handle, stream) == 0)
                                repeat(25) {
                                    check(JNI.moonshineAddAudioToStream(handle, stream, FloatArray(320), 16_000, 0) == 0)
                                }
                                check(JNI.moonshineTranscribeStream(handle, stream, JNI.MOONSHINE_FLAG_FORCE_UPDATE) != null)
                                check(JNI.moonshineStopStream(handle, stream) == 0)
                            }
                            result.put("startAddDrainStopCycles", 3)
                            var rejectedAfterStop = false
                            try {
                                JNI.moonshineAddAudioToStream(handle, stream, FloatArray(320), 16_000, 0)
                            } catch (_: IllegalStateException) {
                                rejectedAfterStop = true
                            }
                            check(rejectedAfterStop) { "STOPPED_STREAM_ACCEPTED_INPUT" }
                            result.put("addAfterStopRejected", true)
                            JNI.moonshineFreeStream(handle, stream)
                            JNI.moonshineFreeStream(handle, stream)
                            stream = -1
                            result.put("doubleFreeCleanupSafe", true).put("state", "PASSED")
                        } finally {
                            if (stream >= 0) JNI.moonshineFreeStream(handle, stream)
                            JNI.moonshineFreeTranscriber(handle)
                        }
                    }
                }
            }
        } catch (failure: Throwable) {
            result.put("state", "FAILED").put("failureType", failure.javaClass.simpleName)
            throw failure
        } finally {
            output.writeText(result.toString(2))
        }
    }

    @Test fun nativeErrorsAreExplicitInsteadOfEmptyRecognition() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        JNI.ensureLibraryLoaded()
        val result = JSONObject().put("scope", "CONTROLLED_INVALID_NATIVE_HANDLE_NO_AUDIO")
            .put("nativeVersion", JNI.moonshineGetVersion())
        var explicitErrors = 0
        for (name in listOf("stream", "batch", "add", "start", "stop")) {
            try {
                val returned: Any? = when (name) {
                    "stream" -> JNI.moonshineTranscribeStream(-1, -1, 0)
                    "batch" -> JNI.moonshineTranscribeWithoutStreaming(-1, FloatArray(320), 16_000, 0)
                    "add" -> JNI.moonshineAddAudioToStream(-1, -1, FloatArray(320), 16_000, 0)
                    "start" -> JNI.moonshineStartStream(-1, -1)
                    else -> JNI.moonshineStopStream(-1, -1)
                }
                result.put(name, if (returned == null) "SILENT_NULL" else "RETURNED_WITHOUT_EXCEPTION")
            } catch (error: IllegalStateException) {
                explicitErrors++
                result.put(name, error.javaClass.simpleName)
            }
        }
        result.put("state", if (explicitErrors == 5) "ERROR_PROPAGATION_PASSED" else "ERROR_PROPAGATION_FAILED")
        File(checkNotNull(app.getExternalFilesDir(null)), "native-error-propagation-${System.currentTimeMillis()}.json")
            .writeText(result.toString(2))
        assertTrue("Native failures must not look like silence or be discarded by SDK wrappers: $result", explicitErrors == 5)
    }

    @Test fun repeatedFrameBuffersStayBoundedAfterWarmup(): Unit = runBlocking {
        checkFrameOwnership(intArrayOf(25), "fixed-25")
    }

    @Test fun irregularForcedDrainsKeepFrameBuffersBounded(): Unit = runBlocking {
        checkFrameOwnership(intArrayOf(25, 50, 100), "irregular-25-50-100")
    }

    private suspend fun checkFrameOwnership(drainCadence: IntArray, variant: String) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as GuideCastApplication
        val growthLimit = 1024L * 1024
        val result = JSONObject().put("scope", "SYNTHETIC_ZERO_PCM_JNI_OWNERSHIP_NOT_REALTIME_STABILITY")
            .put("state", "PREPARING").put("warmupFrames", 1_500).put("measuredFrames", 12_000)
            .put("samplesPerFrame", 320).put("sampleRateHz", 16_000)
            .put("variant", variant)
            .put("forcedDrainCadenceFrames", JSONArray(drainCadence.toList()))
            .put("forceDrainForAcceleratedClock", true)
            .put("nativeHeapGrowthLimitBytes", growthLimit)
            .put("recognizedTextStored", false)
        val output = File(checkNotNull(app.getExternalFilesDir(null)), "native-frame-ownership-$variant-${System.currentTimeMillis()}.json")
        val started = SystemClock.elapsedRealtime()
        try {
            app.withTranslationBackendUse {
                app.withProcessNativeColdLoadLease(ProcessNativeColdLoadKeys.speechRecognition("ko"), true) {
                    withContext(Dispatchers.Default) {
                        withTimeout(180_000L) {
                            val models = ModelCache.directoryFor(app, ModelSpec.stt("ko", JNI.MOONSHINE_MODEL_ARCH_TINY, false), null)
                            check(listOf("encoder_model.ort", "tokenizer.bin", "decoder_model_merged.ort")
                                .all { File(models, it).isFile }) { "CACHED_KOREAN_MODEL_REQUIRED" }
                            JNI.ensureLibraryLoaded()
                            result.put("nativeVersion", JNI.moonshineGetVersion())
                            val handle = JNI.moonshineLoadTranscriberFromFiles(models.absolutePath, JNI.MOONSHINE_MODEL_ARCH_TINY,
                                arrayOf(TranscriberOption("vad_threshold", "0.58"),
                                    TranscriberOption("vad_window_duration", "0.30"),
                                    TranscriberOption("vad_max_segment_duration", "12.0")))
                            check(handle >= 0) { "NATIVE_LOAD_FAILED" }
                            var stream = -1
                            var measured = 0
                            var forcedDrains = 0
                            var maxPendingFrames = 0
                            try {
                                stream = JNI.moonshineCreateStream(handle, 0)
                                check(stream >= 0 && JNI.moonshineStartStream(handle, stream) == 0)
                                fun forceDrain() {
                                    check(JNI.moonshineTranscribeStream(handle, stream, JNI.MOONSHINE_FLAG_FORCE_UPDATE) != null)
                                    forcedDrains++
                                }
                                fun feedFrames(count: Int, measuring: Boolean) {
                                    var cadenceIndex = 0
                                    var pendingFrames = 0
                                    repeat(count) {
                                        check(JNI.moonshineAddAudioToStream(handle, stream, FloatArray(320), 16_000, 0) == 0)
                                        if (measuring) measured++
                                        pendingFrames++
                                        maxPendingFrames = maxOf(maxPendingFrames, pendingFrames)
                                        if (pendingFrames == drainCadence[cadenceIndex]) {
                                            forceDrain()
                                            pendingFrames = 0
                                            cadenceIndex = (cadenceIndex + 1) % drainCadence.size
                                        }
                                    }
                                    // Both windows end fully drained, including an irregular last interval.
                                    forceDrain()
                                }
                                feedFrames(1_500, false)
                                result.put("warmupForcedDrains", forcedDrains)
                                val before = settledNativeHeap()
                                result.put("state", "MEASURING").put("nativeHeapBeforeBytes", before)
                                output.writeText(result.toString(2))
                                feedFrames(12_000, true)
                                val after = settledNativeHeap()
                                result.put("nativeHeapAfterBytes", after).put("nativeHeapGrowthBytes", after - before)
                                    .put("allInputCallsSucceeded", true)
                                    .put("totalForcedDrains", forcedDrains)
                                    .put("maximumPendingInputFrames", maxPendingFrames)
                                    .put("finalPendingInputFrames", 0)
                                check(measured == 12_000) { "INCOMPLETE_MEASUREMENT" }
                                check(JNI.moonshineStopStream(handle, stream) == 0)
                                forceDrain()
                                result.put("normalStop", true)
                                    .put("nativeHeapAfterStopAndDrainBytes", settledNativeHeap())
                            } finally {
                                result.put("measuredFramesCompleted", measured)
                                if (stream >= 0) JNI.moonshineFreeStream(handle, stream)
                                JNI.moonshineFreeTranscriber(handle)
                                result.put("nativeHeapAfterCloseBytes", settledNativeHeap())
                            }
                        }
                    }
                }
            }
            val growth = result.getLong("nativeHeapGrowthBytes")
            result.put("state", if (growth <= growthLimit) "MEMORY_BOUND_PASSED" else "MEMORY_BOUND_FAILED")
            assertTrue("Native heap grew by $growth bytes after warmup; inspect ${output.name}", growth <= growthLimit)
        } catch (failure: Throwable) {
            if (!result.optString("state").endsWith("FAILED")) result.put("state", "FAILED")
            result.put("failureType", failure.javaClass.simpleName)
            throw failure
        } finally {
            result.put("elapsedMs", SystemClock.elapsedRealtime() - started)
            output.writeText(result.toString(2))
        }
    }

    @Test fun repeatedPublicSpeechBatchInferenceHasBoundedNativeGrowth(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as GuideCastApplication
        val overallLimit = 2L * 1024 * 1024
        val lateWindowLimit = 512L * 1024
        val result = JSONObject().put("scope", "PUBLIC_SPEECH_BATCH_JNI_DECODER_MEMORY_NOT_LONG_DURATION_STABILITY")
            .put("state", "PREPARING").put("recognizedTextStored", false)
            .put("warmupInferences", 2).put("measuredInferences", 4)
            .put("nativeHeapGrowthLimitBytes", overallLimit)
            .put("lastTwoInferencesGrowthLimitBytes", lateWindowLimit)
            .put("sampleRateHz", 16_000).put("channels", 1).put("encoding", "PCM_S16LE")
            .put("sourceFixture", "google-fleurs-ko_kr-test-1959").put("sourceLicense", "CC-BY-4.0")
            .put("sourceRevision", "70bb2e84b976b7e960aa89f1c648e09c59f894dd")
        val rounds = JSONArray()
        result.put("rounds", rounds)
        val output = File(checkNotNull(app.getExternalFilesDir(null)), "native-speech-batch-ownership-${System.currentTimeMillis()}.json")
        val started = SystemClock.elapsedRealtime()
        output.writeText(result.toString(2))
        try {
            val pcm = instrumentation.context.assets.open("fixtures/fleurs-ko-1959.pcm").use { it.readBytes() }
            val digest = MessageDigest.getInstance("SHA-256").digest(pcm).joinToString("") { "%02x".format(it) }
            result.put("pcmSha256", digest).put("pcmBytes", pcm.size)
            check(pcm.size == 224_640 && digest == "b35aa5acf7ff72a4ec1b68415957ac9e7fe89268312cc8f5e5325a88acea841f") {
                "PUBLIC_SPEECH_FIXTURE_CONTRACT_FAILED"
            }
            val stats = pcm.pcmS16LeSignalStats()
            check(stats.rms > 0.002f) { "PUBLIC_SPEECH_FIXTURE_IS_SILENT" }
            result.put("sampleCount", pcm.size / 2).put("durationMs", pcm.size / 32)
                .put("inputRms", stats.rms.toDouble())
            val expectedTerms = listOf("관계자", "조언", "표지판", "안전", "경고", "주의")
            result.put("requiredKeywordCount", expectedTerms.size)
            app.withTranslationBackendUse {
                app.withProcessNativeColdLoadLease(ProcessNativeColdLoadKeys.speechRecognition("ko"), true) {
                    withContext(Dispatchers.Default) {
                        withTimeout(240_000L) {
                            val models = ModelCache.directoryFor(app, ModelSpec.stt("ko", JNI.MOONSHINE_MODEL_ARCH_TINY, false), null)
                            check(listOf("encoder_model.ort", "tokenizer.bin", "decoder_model_merged.ort")
                                .all { File(models, it).isFile }) { "CACHED_KOREAN_MODEL_REQUIRED" }
                            JNI.ensureLibraryLoaded()
                            result.put("nativeVersion", JNI.moonshineGetVersion())
                                .put("nativeHeapBeforeModelLoadBytes", settledNativeHeap())
                            val handle = JNI.moonshineLoadTranscriberFromFiles(models.absolutePath, JNI.MOONSHINE_MODEL_ARCH_TINY,
                                arrayOf(TranscriberOption("vad_threshold", "0.58"),
                                    TranscriberOption("vad_window_duration", "0.30"),
                                    TranscriberOption("vad_max_segment_duration", "12.0"),
                                    TranscriberOption("max_tokens_per_second", "13.0")))
                            check(handle >= 0) { "NATIVE_LOAD_FAILED" }
                            var completed = 0
                            try {
                                // Each call gets a fresh float array, so a missing JNI release is exercised.
                                fun infer(round: Int, phase: String) {
                                    val audio = FloatArray(pcm.size / 2) { index ->
                                        (((pcm[index * 2 + 1].toInt() shl 8) or (pcm[index * 2].toInt() and 255))
                                            .toShort()).toFloat() / 32768f
                                    }
                                    val inferenceStarted = SystemClock.elapsedRealtime()
                                    val transcript = checkNotNull(JNI.moonshineTranscribeWithoutStreaming(handle, audio, 16_000, 0)) {
                                        "NATIVE_BATCH_RETURNED_NULL"
                                    }
                                    val lines = transcript.lines.orEmpty()
                                    val text = lines.joinToString(" ") { it.text.orEmpty() }
                                    val matched = expectedTerms.count { text.contains(it) }
                                    val completeNonemptyLines = lines.count { it.isComplete && !it.text.isNullOrBlank() }
                                    rounds.put(JSONObject().put("phase", phase).put("round", round)
                                        .put("inputSamples", audio.size).put("lineCount", lines.size)
                                        .put("completeNonemptyLineCount", completeNonemptyLines)
                                        .put("characterCount", text.length).put("matchedKeywordCount", matched)
                                        .put("inferenceElapsedMs", SystemClock.elapsedRealtime() - inferenceStarted))
                                    check(completeNonemptyLines > 0 && matched == expectedTerms.size) {
                                        "PUBLIC_SPEECH_DECODER_CONTENT_CHECK_FAILED"
                                    }
                                    completed++
                                }
                                repeat(2) { infer(it + 1, "warmup") }
                                val before = settledNativeHeap()
                                result.put("state", "MEASURING").put("nativeHeapBeforeBytes", before)
                                var halfway = before
                                var after = before
                                repeat(4) { index ->
                                    infer(index + 1, "measured")
                                    after = settledNativeHeap()
                                    rounds.getJSONObject(rounds.length() - 1).put("settledNativeHeapBytes", after)
                                    if (index == 1) halfway = after
                                    output.writeText(result.toString(2))
                                }
                                val growth = after - before
                                val lateGrowth = after - halfway
                                result.put("nativeHeapAfterBytes", after).put("nativeHeapGrowthBytes", growth)
                                    .put("nativeHeapAfterFirstTwoMeasuredBytes", halfway)
                                    .put("lastTwoInferencesGrowthBytes", lateGrowth)
                                    .put("allSixContentChecksPassed", completed == 6)
                                check(completed == 6) { "INCOMPLETE_BATCH_INFERENCE_MEASUREMENT" }
                                assertTrue("Speech batch native heap grew by $growth bytes; inspect ${output.name}", growth <= overallLimit)
                                assertTrue("Last two speech batches grew by $lateGrowth bytes; inspect ${output.name}", lateGrowth <= lateWindowLimit)
                            } finally {
                                result.put("completedInferences", completed)
                                    .put("nativeHeapBeforeCloseBytes", Debug.getNativeHeapAllocatedSize())
                                JNI.moonshineFreeTranscriber(handle)
                                result.put("nativeHeapAfterCloseBytes", settledNativeHeap())
                            }
                        }
                    }
                }
            }
            result.put("state", "SPEECH_BATCH_MEMORY_BOUND_PASSED")
        } catch (failure: Throwable) {
            result.put("state", "FAILED").put("failureType", failure.javaClass.simpleName)
            throw failure
        } finally {
            result.put("elapsedMs", SystemClock.elapsedRealtime() - started)
            output.writeText(result.toString(2))
        }
    }

    private suspend fun settledNativeHeap(): Long {
        System.gc()
        delay(150)
        return Debug.getNativeHeapAllocatedSize()
    }
}
