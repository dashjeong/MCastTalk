package app.guidecast.transmitter

import android.os.Build
import app.guidecast.core.audio.pcmS16LeSignalStats
import app.guidecast.core.server.BroadcastAccess
import app.guidecast.core.server.GuideCastLocalServer
import app.guidecast.core.server.GuideCastServerConfig
import app.guidecast.core.server.RunningGuideCastServer
import androidx.test.platform.app.InstrumentationRegistry
import app.guidecast.core.stream.AudioStreamRegistry
import app.guidecast.core.translation.*
import app.guidecast.provider.gemma.translation.GemmaModelVariant
import app.guidecast.provider.moonshine.tts.MoonshineSpeechSynthesisProvider
import app.guidecast.provider.android.tts.AndroidOfflineSpeechSynthesisProvider
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real final text -> first audible native PCM; not microphone, WebSocket, or speaker playback. */
class E4BPreparedAudioLatencyDeviceTest {
    @Test fun recordsTwentyPreparedEnglishFirstAudiblePcmSamples() = runPreparedTest(false)
    @Test fun recordsTwentyPreparedAndroidEnglishFirstAudiblePcmSamples() = runPreparedTest(true)
    @Test fun recordsTwentyPreparedAndroidEnglishFirstWebPcmSamples() = runPreparedTest(true, true)

    private fun runPreparedTest(androidVoice: Boolean, measureWeb: Boolean = false) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as GuideCastApplication
        val gemma = app.gemmaTranslationProvider
        val original = gemma.modelManager.selectedVariant
        val lease = requireNotNull(app.acquireTranslationBackendUseIf({ true }))
        val moonshine = if (!androidVoice) MoonshineSpeechSynthesisProvider(context) else null
        val android = if (androidVoice) AndroidOfflineSpeechSynthesisProvider(context, outputSampleRateHz = 24_000) else null
        val speech: SpeechSynthesisEngineProvider = moonshine ?: requireNotNull(android)
        val rows = JSONArray()
        val result = JSONObject().put("model", "e4b_it").put("deviceModel", Build.MODEL)
            .put("api", Build.VERSION.SDK_INT).put("target", "en").put("samplesPlanned", 20)
            .put("ttsEngine", if (androidVoice) "installed-android-offline" else "moonshine")
            .put("uniqueSourcePhrases", 5).put("repeatedSourcePhrases", true).put("sessionMemoryEnabled", true)
            .put("microphoneMeasured", false).put("webSocketMeasured", measureWeb)
            .put("webTransport", if (measureWeb) "production-server-loopback-raw-websocket-not-browser" else "NONE")
            .put("speakerPlaybackMeasured", false).put("rows", rows)
        val output = File(context.getExternalFilesDir(null), "benchmark/e4b-prepared-${if (measureWeb) "web" else "native"}-pcm-${System.currentTimeMillis()}.json")
        output.parentFile?.mkdirs()
        fun save() = output.writeText(result.toString(2))
        var pipeline: RunningTranslationPipeline? = null
        var server: RunningGuideCastServer? = null
        try {
            // Do not download a missing model merely to make a prepared-model test look ready.
            if (moonshine != null) assertTrue("Verified English voice must already be installed", moonshine.isReady("en"))
            withTimeout(120_000) {
                gemma.applyVerifiedModel(GemmaModelVariant.E4B_IT)
                if (moonshine != null) moonshine.prepare(listOf("en")) else requireNotNull(android).prepare(listOf("en"))
                speech.engineFor("en").synthesize("The audio test is ready.", "en").collect {}
            }
            val source = MutableSharedFlow<RecognizedUtterance>()
            var row = JSONObject()
            var finished = CompletableDeferred<Unit>()
            var startedAt = 0L
            val streams = AudioStreamRegistry()
            pipeline = TranslationBroadcastPipeline(streams, gemma, speech,
                translationTimeoutMillis = if (measureWeb) 10_000 else 15_000,
                firstAudioTimeoutMillis = if (measureWeb) 7_000 else 30_000,
                observer = object : TranslationPipelineObserver {
                    override fun onTranslationCompleted(utterance: RecognizedUtterance, target: TranslationTarget, translatedText: String, elapsedMillis: Long) {
                        row.put("translationMs", elapsedMillis)
                        // This suite accepts only the five fixed public synthetic phrases below.
                        // Retain their actual output for independent meaning review, not user audio.
                        row.put("source", utterance.text).put("translation", translatedText)
                    }
                    override fun onSynthesisAudioStarted(utterance: RecognizedUtterance, target: TranslationTarget, elapsedMillis: Long) {
                        row.put("ttsFirstAudibleMs", elapsedMillis)
                        row.put("finalToFirstAudiblePcmMs", (System.nanoTime() - startedAt) / 1_000_000)
                        save()
                    }
                    override fun onSynthesisAudioCompleted(utterance: RecognizedUtterance, target: TranslationTarget, elapsedMillis: Long, pcm: SynthesizedPcmStats) {
                        row.put("nonSilent", pcm.isNonSilent()).put("pcmBytes", pcm.byteCount)
                        row.put("clippingRatio", pcm.clippingRatio).put("state", "COMPLETED")
                        save(); finished.complete(Unit)
                    }
                }).start(this, source, listOf(TranslationTarget("en", "English", "en", 24_000)), "ko")
            if (measureWeb) {
                server = GuideCastLocalServer(context, streams).start(
                    InetAddress.getByName("127.0.0.1") as Inet4Address,
                    GuideCastServerConfig(port = ServerSocket(0).use { it.localPort }, enableHttps = false,
                        access = BroadcastAccess.Open))
            }
            yield()
            val phrases = listOf("회의를 시작하겠습니다.", "자료를 확인해 주세요.", "질문이 있으면 말씀해 주세요.",
                "잠시 쉬었다가 계속하겠습니다.", "오늘 참석해 주셔서 감사합니다.")
            repeat(20) { index ->
                row = JSONObject().put("id", index).put("state", "RUNNING")
                rows.put(row); save(); finished = CompletableDeferred()
                if (measureWeb) {
                    withTimeout(5_000) { while (streams.observability.value.totalListeners != 0) delay(10) }
                    PreparedWebReader(URI(requireNotNull(server).listenerUrl)).use { reader ->
                        reader.requirePcmConfiguration()
                        startedAt = System.nanoTime()
                        val finalAt = startedAt
                        val web = async(Dispatchers.IO) { reader.firstAudiblePcm(finalAt) }
                        try {
                            source.emit(RecognizedUtterance(index.toLong(), phrases[index % phrases.size], "ko", true, finalAt))
                            val observation = withTimeout(15_000) { web.await() }
                            row.put("finalToFirstWebPcmMs", observation.first).put("firstWebFrameBytes", observation.second)
                            save()
                            withTimeout(90_000) { finished.await() }
                        } finally { reader.close(); web.cancel() }
                    }
                } else {
                    startedAt = System.nanoTime()
                    source.emit(RecognizedUtterance(index.toLong(), phrases[index % phrases.size], "ko", true, startedAt))
                    withTimeout(90_000) { finished.await() }
                }
                assertTrue(row.getBoolean("nonSilent"))
                assertTrue(row.getDouble("clippingRatio") < .005)
            }
            val ordered = (0 until rows.length()).map { rows.getJSONObject(it).getLong("finalToFirstAudiblePcmMs") }.sorted()
            val p95 = ordered[18] // nearest-rank ceil(.95 * 20) - 1
            result.put("nativeFirstPcmP95Ms", p95).put("nativeTwoSecondGatePass", p95 <= 2_000)
            if (measureWeb) {
                val webTimes = (0 until rows.length()).map { rows.getJSONObject(it).getLong("finalToFirstWebPcmMs") }.sorted()
                result.put("webFirstPcmP95Ms", webTimes[18]).put("webTwoSecondGatePass", webTimes[18] <= 2_000)
                result.put("droppedListenerFrames", streams.observability.value.droppedFrames)
                // This measures final text -> socket receipt. Physical microphone/meaning and
                // browser playback/scheduling remain separate user-journey gates.
                val physicalS23 = Build.MODEL.startsWith("SM-S91") && !Build.FINGERPRINT.contains("emulator")
                result.put("physicalGalaxyS23", physicalS23)
                result.put("releaseWebGateProven", physicalS23 && webTimes[18] <= 2_000 &&
                    streams.observability.value.droppedFrames == 0L); save()
                assertEquals(0L, streams.observability.value.droppedFrames)
                assertTrue("Measured prepared final-to-Web PCM p95 exceeds 2000ms: ${webTimes[18]}", webTimes[18] <= 2_000)
            } else result.put("releaseWebGateProven", false)
            save()
            assertEquals(20, rows.length())
        } finally {
            pipeline?.close(); server?.close(); moonshine?.close(); android?.close()
            try { if (gemma.modelManager.selectedVariant != original) gemma.applyVerifiedModel(original) }
            finally { lease.close(); save() }
        }
    }

    /** Test-only bounded RFC6455 reader, isolated from other apps and listener audio. */
    private class PreparedWebReader(uri: URI) : AutoCloseable {
        private val socket = Socket(uri.host, uri.port).apply { soTimeout = 15_000 }
        private val input = socket.getInputStream()
        init {
            try {
                val key = Base64.getEncoder().encodeToString(ByteArray(16).also(SecureRandom()::nextBytes))
                socket.getOutputStream().apply {
                    write(("GET /ws/en HTTP/1.1\r\nHost: ${uri.host}:${uri.port}\r\nUpgrade: websocket\r\n" +
                        "Connection: Upgrade\r\nSec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n").toByteArray(Charsets.US_ASCII)); flush()
                }
                val header = ByteArrayOutputStream()
                while (!header.toString(Charsets.US_ASCII.name()).endsWith("\r\n\r\n")) {
                    val ch = input.read(); check(ch >= 0 && header.size() < 16_384)
                    header.write(ch)
                }
                check(header.toString(Charsets.US_ASCII.name()).startsWith("HTTP/1.1 101"))
            } catch (error: Throwable) { socket.close(); throw error }
        }
        fun requirePcmConfiguration() {
            val (opcode, bytes) = frame()
            check(opcode == 1)
            val config = JSONObject(bytes.toString(Charsets.UTF_8))
            check(config.getInt("sampleRate") == 24_000 && config.getString("format") == "pcm_s16le" && config.getInt("channels") == 1)
        }
        fun firstAudiblePcm(finalAt: Long): Pair<Long, Int> {
            repeat(5_000) {
                val (opcode, bytes) = frame()
                if (opcode == 2) {
                    check(bytes.isNotEmpty() && bytes.size % 2 == 0)
                    val stats = bytes.pcmS16LeSignalStats()
                    if (stats.rms > .003f && stats.peak > .015f && stats.nonZeroSamples > 100)
                        return ((System.nanoTime() - finalAt) / 1_000_000L) to bytes.size
                } else check(opcode == 1) { "Unexpected control frame before first PCM" }
            }
            error("No audible Web PCM")
        }
        private fun frame(): Pair<Int, ByteArray> {
            val first = input.read(); val second = input.read()
            check(first >= 0 && second >= 0 && first and 0x80 != 0 && second and 0x80 == 0)
            var length = (second and 0x7f).toLong()
            if (length == 126L) length = ((input.read() shl 8) or input.read()).toLong()
            else if (length == 127L) { length = 0; repeat(8) { length = (length shl 8) or input.read().toLong() } }
            check(length in 0..1_048_576)
            return (first and 0x0f) to input.exact(length.toInt())
        }
        private fun InputStream.exact(size: Int): ByteArray = ByteArray(size).also { bytes ->
            var offset = 0
            while (offset < size) { val count = read(bytes, offset, size - offset); check(count > 0); offset += count }
        }
        override fun close() = socket.close()
    }
}
