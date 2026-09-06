package app.guidecast.transmitter

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.guidecast.core.audio.RnNoiseMicrophoneProcessor
import app.guidecast.core.audio.NoiseFrameFilter
import app.guidecast.core.audio.pcmS16LeSignalStats
import app.guidecast.core.stream.PcmAudioFrame
import app.guidecast.core.translation.SpeechRecognitionConfig
import java.io.ByteArrayOutputStream
import java.util.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MicrophoneNoiseDeviceTest {
    // Exercises the packaged native DSP without downloading or substituting an STT model.
    // This is not a speech-recognition accuracy or physical-microphone test.
    @Test fun packagedNativeNoiseFilterKeepsPcmAndReducesStationaryNoise() =
        compareNoise(10.0, verifyRecognition = false)

    @Test fun nativeNoiseSuppressionPreservesKoreanSpeechAndReducesStationaryNoise() = compareNoise(10.0)

    @Test fun noiseLouderThanSpeechIsCharacterizedWithoutClaimingCleanSpeechAccuracy() = compareNoise(null)

    private fun compareNoise(snrDb: Double?, verifyRecognition: Boolean = true) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as GuideCastApplication
        val clean = instrumentation.context.assets.open("fixtures/fleurs-ko-1959.pcm").use { it.readBytes() }
        var power = 0.0
        for (i in clean.indices step 2) {
            val sample = ((clean[i + 1].toInt() shl 8) or (clean[i].toInt() and 255)).toShort().toDouble()
            power += sample * sample
        }
        val cleanRms = kotlin.math.sqrt(power / (clean.size / 2))
        // Fixed moderate SNR and the original -4.75dB stress condition are both retained.
        // Speech reconstruction at noise above voice level is characterized, not guaranteed.
        val sigma = snrDb?.let { cleanRms / Math.pow(10.0, it / 20.0) } ?: 700.0
        println("RNNOISE_CONDITION snrDb=${20 * kotlin.math.log10(cleanRms / sigma)} noiseSigma=$sigma")
        val random = Random(32)
        val noisy = ByteArray((clean.size / 2 * 3 + 48_000 * 3) * 2)
        for (i in 0 until noisy.size / 2) {
            val index = i / 3 * 2
            val speech = if (index + 1 < clean.size)
                (((clean[index + 1].toInt() shl 8) or (clean[index].toInt() and 255)).toShort().toInt()) else 0
            val sample = (speech + random.nextGaussian() * sigma).toInt().coerceIn(-32768, 32767)
            noisy[i * 2] = sample.toByte(); noisy[i * 2 + 1] = (sample shr 8).toByte()
        }
        val output = ByteArrayOutputStream()
        val durations = mutableListOf<Long>()
        var failures = 0
        RnNoiseMicrophoneProcessor(onFailure = { failures++ }).use { processor ->
            var offset = 0
            while (offset < noisy.size) {
                val end = minOf(offset + 960, noisy.size)
                val start = SystemClock.elapsedRealtimeNanos()
                output.write(processor.process(noisy.copyOfRange(offset, end)))
                durations += SystemClock.elapsedRealtimeNanos() - start
                offset = end
            }
        }
        assertEquals(0, failures)
        val pcm = output.toByteArray()
        assertEquals(noisy.size / 3, pcm.size)
        val tail = pcm.copyOfRange(pcm.size - 32_000, pcm.size).pcmS16LeSignalStats()
        val inputTail = noisy.copyOfRange(noisy.size - 96_000, noisy.size).pcmS16LeSignalStats()
        val voice = pcm.copyOfRange(0, clean.size).pcmS16LeSignalStats()
        assertTrue("Noise not reduced: $tail / $inputTail", tail.rms < inputTail.rms * 0.5f)
        assertTrue("Voice became silent: $voice", voice.rms > 0.003f)
        val p95Millis = durations.sorted()[durations.size * 95 / 100] / 1_000_000.0
        println("RNNOISE_DEVICE inputNoise=$inputTail outputNoise=$tail voice=$voice frameP95Ms=$p95Millis")
        assertTrue("10ms frame cannot keep up on this test environment: $p95Millis", p95Millis < 10.0)
        if (!verifyRecognition) return@runBlocking
        withTimeout(180_000) { assertTrue(app.speechRecognitionEngine.prepareLanguage("ko").isReady) }
        val untreated = RnNoiseMicrophoneProcessor(filter = object : NoiseFrameFilter {
            override fun process(frame: FloatArray) = Unit
            override fun close() = Unit
        }).use { it.process(noisy) }
        val cleanText = recognize(app, clean + ByteArray(32_000 * 3))
        val noisyText = recognize(app, untreated)
        val denoisedText = recognize(app, pcm)
        val cleanScore = accuracy(cleanText)
        val noisyScore = accuracy(noisyText)
        val denoisedScore = accuracy(denoisedText)
        println("RNNOISE_ACCURACY clean=$cleanScore noisy=$noisyScore denoised=$denoisedScore")
        println("RNNOISE_CLEAN $cleanText\nRNNOISE_NOISY $noisyText\nRNNOISE_DENOISED $denoisedText")
        assertTrue("AI reduces recognition accuracy ($noisyScore -> $denoisedScore)", denoisedScore >= noisyScore)
        assertTrue("Clean reference must pass the STT floor: $cleanScore", cleanScore >= 0.60)
        if (snrDb != null) {
            assertTrue("10dB fixture below STT accuracy floor: $denoisedScore", denoisedScore >= 0.60)
        }
    }

    private suspend fun recognize(app: GuideCastApplication, pcm: ByteArray): String = coroutineScope {
        val finals = mutableListOf<String>()
        val inputEnded = CompletableDeferred<Unit>()
        val recognition = launch {
            app.speechRecognitionEngine.recognize(flow {
                var offset = 0
                while (offset < pcm.size) {
                    val end = minOf(offset + 640, pcm.size)
                    emit(PcmAudioFrame(pcm.copyOfRange(offset, end), SystemClock.elapsedRealtimeNanos()))
                    offset = end
                    delay(20)
                }
                inputEnded.complete(Unit)
            }, SpeechRecognitionConfig(sourceLanguageTag = "ko")).collect {
                println("RNNOISE_STT_EVENT final=${it.isFinal} text=${it.text}")
                if (it.isFinal) finals += it.text
            }
        }
        recognition.invokeOnCompletion { error ->
            if (!inputEnded.isCompleted) inputEnded.completeExceptionally(error ?: IllegalStateException("input stopped early"))
        }
        try {
            // A final may end a clause, not the file. Keep feeding all reference audio and
            // collecting finals; otherwise clean speech is unfairly scored as a truncated sentence.
            withTimeout(60_000) { inputEnded.await(); delay(2_000) }
        } finally { recognition.cancelAndJoin() }
        finals.joinToString(" ")
    }

    private fun accuracy(text: String): Double {
        val source = "그래도 관계자의 조언을 듣고 모든 표지판을 지키고 안전 경고에 세심한 주의를 기울여야 합니다"
            .filter { it.isLetterOrDigit() }
        val result = text.filter { it.isLetterOrDigit() }
        var previous = IntArray(result.length + 1) { it }
        source.forEachIndexed { i, a ->
            val next = IntArray(result.length + 1)
            next[0] = i + 1
            result.forEachIndexed { j, b -> next[j + 1] = minOf(next[j] + 1, previous[j + 1] + 1,
                previous[j] + if (a == b) 0 else 1) }
            previous = next
        }
        return (1.0 - previous.last().toDouble() / maxOf(source.length, result.length)).coerceIn(0.0, 1.0)
    }
}
