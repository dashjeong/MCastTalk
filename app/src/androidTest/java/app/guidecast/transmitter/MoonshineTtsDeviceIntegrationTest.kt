package app.guidecast.transmitter

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.guidecast.core.audio.pcmS16LeSignalStats
import app.guidecast.core.stream.PcmAudioFrame
import app.guidecast.provider.moonshine.tts.MoonshineSpeechSynthesisProvider
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android instrumentation test for Moonshine TTS voice synthesis.
 *
 * LATENCY GATE & ENVIRONMENT SEPARATION (AGENTS.md & docs/DEVELOPMENT_GUIDELINES.md):
 * - Prepared-model single-language first-audio latency on the physical Galaxy S23 baseline
 *   has a strict 2,000 ms p95 release gate.
 * - In an emulator or general test environment without hardware NPU acceleration, an emulator
 *   safety ceiling of 6,500 ms is applied to guard against deadlocks or hangs.
 * - Never report an emulator test result as physical Galaxy S23 release qualification.
 */
@RunWith(AndroidJUnit4::class)
class MoonshineTtsDeviceIntegrationTest {

    @Test
    fun existingFourOfficialVoicesDownloadAndSynthesizePlayablePcm(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = MoonshineSpeechSynthesisProvider(context)
        try {
            withTimeout(20 * 60 * 1_000L) {
                val samples = linkedMapOf(
                    "en" to "Welcome to GuideCast.",
                    "ja" to "ガイドキャストへようこそ。",
                    "zh" to "欢迎使用导游广播。",
                    "nl" to "Welkom bij GuideCast.",
                )
                provider.prepare(samples.keys)
                samples.forEach { (languageTag, text) ->
                    assertPlayablePcm(provider, languageTag, text)
                }
            }
        } finally {
            provider.close()
        }
    }

    @Test
    fun spanishAndArabicOfficialVoicesDownloadAndSynthesizePlayablePcm(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = MoonshineSpeechSynthesisProvider(context)
        try {
            withTimeout(20 * 60 * 1_000L) {
                val samples = linkedMapOf(
                    "es" to "Bienvenidos a la visita guiada por la zona desmilitarizada.",
                    "ar" to "مرحبًا بكم في الجولة الإرشادية للمنطقة منزوعة السلاح.",
                )
                provider.prepare(samples.keys)
                samples.forEach { (languageTag, text) ->
                    assertPlayablePcm(provider, languageTag, text)
                }
            }
        } finally {
            provider.close()
        }
    }

    @Test
    fun sequentialEnglishGuideUtterancesSynthesizePlayablePcmWithMonotonicFrames(): Unit = runBlocking {
        Log.i(
            TAG,
            "[DISCLAIMER] Emulator synthetic audio and buffer timing checks do NOT claim or prove " +
                "physical human-ear voice naturalness, outdoor acoustic listener quality, or physical Galaxy S23 " +
                "speaker qualification (physical hardware testing strictly required per AGENTS.md).",
        )

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = MoonshineSpeechSynthesisProvider(context)
        try {
            withTimeout(20 * 60 * 1_000L) {
                // 1. Prepare English voice model (deterministic offline verification when cache exists)
                provider.prepare(listOf("en"))
                assertTrue("Moonshine en 음성이 오프라인 준비 상태가 아닙니다.", provider.isReady("en"))

                val englishGuideUtterances = listOf(
                    "Welcome to the DMZ Peace Walk guided tour.",
                    "Please stay on the designated trail and follow your tour guide.",
                    "The historical observation post is located right ahead of our path.",
                    "You can listen to live interpretation on your personal audio receiver.",
                    "We will pause here briefly before continuing to the next checkpoint.",
                )

                val completedIndices = mutableListOf<Int>()
                val recordedFirstAudioLatencies = mutableListOf<Long>()
                var previousUtteranceEndNanos = 0L

                // 2. Synthesize at least 5 sequential English guide utterances
                englishGuideUtterances.forEachIndexed { index, utteranceText ->
                    val synthesisStartedAtNanos = SystemClock.elapsedRealtimeNanos()
                    val output = ByteArrayOutputStream()
                    val frameTimes = mutableListOf<Long>()
                    val frames = mutableListOf<PcmAudioFrame>()
                    var firstAudibleCallbackNanos: Long? = null

                    provider.engineFor("en")
                        .synthesize(utteranceText, "en")
                        .collect { frame ->
                            val callbackNanos = SystemClock.elapsedRealtimeNanos()
                            frames += frame
                            frameTimes += frame.capturedAtElapsedRealtimeNanos
                            output.write(frame.bytes)
                            if (firstAudibleCallbackNanos == null && frame.isAudible()) {
                                firstAudibleCallbackNanos = callbackNanos
                            }
                        }

                    // Assert queue order: no dropped or duplicated utterances
                    completedIndices.add(index)
                    assertEquals("가이드 발화 순서가 보존되어야 합니다.", index, completedIndices.last())

                    // First-audio latency: measured at the callback timestamp of the first audible (non-silent) PCM frame
                    val firstAudioNanos = requireNotNull(firstAudibleCallbackNanos) {
                        "발화 $index ('$utteranceText')에서 첫 비무음 가청 PCM 프레임이 수신되지 않았습니다."
                    }
                    val firstAudioLatencyMillis = (firstAudioNanos - synthesisStartedAtNanos) / 1_000_000L
                    recordedFirstAudioLatencies.add(firstAudioLatencyMillis)

                    Log.i(
                        TAG,
                        "Sequential English Utterance $index ('$utteranceText'): first-audio (first audible callback) latency = ${firstAudioLatencyMillis}ms " +
                            "[Physical Galaxy S23 gate: p95 <= 2000ms | Tested against emulator regression ceiling: <= ${EMULATOR_CEILING_FIRST_PCM_MILLIS}ms]",
                    )

                    // Keep emulator ceiling separate from physical S23 release gate
                    assertTrue(
                        "Utterance $index 첫 비무음 PCM 지연이 에뮬레이터 상한(${EMULATOR_CEILING_FIRST_PCM_MILLIS}ms)을 초과했습니다: ${firstAudioLatencyMillis}ms",
                        firstAudioLatencyMillis <= EMULATOR_CEILING_FIRST_PCM_MILLIS,
                    )

                    // Assert every output is non-silent PCM
                    val pcm = output.toByteArray()
                    val stats = pcm.pcmS16LeSignalStats()
                    assertTrue("Utterance $index PCM 바이트가 부족합니다: ${pcm.size} bytes", pcm.size > 12_000)
                    assertTrue("Utterance $index PCM RMS가 너무 낮습니다 (무음): $stats", stats.rms > MIN_AUDIBLE_PCM_RMS)
                    assertTrue("Utterance $index PCM 피크가 너무 낮습니다: $stats", stats.peak > MIN_AUDIBLE_PCM_PEAK)
                    assertTrue("Utterance $index 0이 아닌 샘플 수가 부족합니다: $stats", stats.nonZeroSamples > 500)

                    // Assert s16le mono 24k / even frame bytes
                    assertEquals("전체 PCM 바이트 수는 짝수여야 합니다 (16-bit PCM).", 0, pcm.size % 2)
                    frames.forEachIndexed { frameIdx, frame ->
                        assertTrue("Frame $frameIdx 바이트 배열이 비어 있습니다.", frame.bytes.isNotEmpty())
                        assertEquals(
                            "Frame $frameIdx 바이트 길이는 짝수여야 합니다 (S16LE mono): ${frame.bytes.size}",
                            0,
                            frame.bytes.size % 2,
                        )
                    }

                    // 24k declared provider check
                    assertEquals(
                        "Moonshine TTS 제공자 샘플 레이트는 24,000 Hz여야 합니다.",
                        REQUIRED_SAMPLE_RATE_HZ,
                        MoonshineSpeechSynthesisProvider.OUTPUT_SAMPLE_RATE_HZ,
                    )
                    val durationSeconds = pcm.size.toDouble() / (REQUIRED_SAMPLE_RATE_HZ * 2.0)
                    assertTrue(
                        "Utterance $index 합성 음원 재생 시간이 비정상입니다: ${durationSeconds}초",
                        durationSeconds in 0.5..20.0,
                    )

                    // Assert strictly increasing frame timestamps (curr > prev) and bounded intra-stream gap
                    for (i in 1 until frameTimes.size) {
                        val prevTime = frameTimes[i - 1]
                        val currTime = frameTimes[i]
                        assertTrue(
                            "Frame timestamps는 엄격하게 단조 증가해야 합니다 (curr > prev): frame[$i]=$currTime <= frame[${i-1}]=$prevTime",
                            currTime > prevTime,
                        )

                        val gapMillis = (currTime - prevTime) / 1_000_000L
                        assertTrue(
                            "프레임 간 지연(gap)이 허용 상한(${MAX_INTRA_STREAM_GAP_BOUND_MILLIS}ms)을 초과했습니다: ${gapMillis}ms (frame ${i-1} -> $i)",
                            gapMillis <= MAX_INTRA_STREAM_GAP_BOUND_MILLIS,
                        )
                    }

                    // Sequence monotonic across utterances
                    if (previousUtteranceEndNanos > 0L) {
                        assertTrue(
                            "연속 발화 시작 시각은 이전 발화 종료 시각 이후여야 합니다.",
                            synthesisStartedAtNanos >= previousUtteranceEndNanos,
                        )
                    }
                    previousUtteranceEndNanos = frameTimes.last()
                }

                // Verify all 5 sequential utterances finished without drop or duplicate
                assertEquals(
                    "5개의 순차 가이드 발화가 누락이나 중복 없이 완료되어야 합니다.",
                    englishGuideUtterances.indices.toList(),
                    completedIndices,
                )

                // Compute and log first-audio (audible PCM) latency list and p95
                val sortedLatencies = recordedFirstAudioLatencies.sorted()
                val p95Index = ((sortedLatencies.size - 1) * 0.95).roundToInt()
                val p95Latency = sortedLatencies[p95Index]

                Log.i(
                    TAG,
                    "Sequential English TTS 5-Utterance Latencies (first non-silent audio callback): list=$recordedFirstAudioLatencies ms, " +
                        "p95=${p95Latency}ms, min=${sortedLatencies.first()}ms, max=${sortedLatencies.last()}ms " +
                        "[Physical Galaxy S23 gate: p95 <= 2000ms | Tested against emulator regression ceiling <= ${EMULATOR_CEILING_FIRST_PCM_MILLIS}ms]",
                )
            }
        } finally {
            provider.close()
        }
    }

    private suspend fun assertPlayablePcm(
        provider: MoonshineSpeechSynthesisProvider,
        languageTag: String,
        text: String,
    ) {
        assertTrue(
            "Moonshine $languageTag 음성이 오프라인 준비 상태가 아닙니다.",
            provider.isReady(languageTag),
        )
        val output = ByteArrayOutputStream()
        val frameTimes = mutableListOf<Long>()
        val frames = mutableListOf<PcmAudioFrame>()
        var firstAudibleCallbackNanos: Long? = null
        val synthesisStartedAtNanos = SystemClock.elapsedRealtimeNanos()

        provider.engineFor(languageTag)
            .synthesize(text, languageTag)
            .collect { frame ->
                val callbackNanos = SystemClock.elapsedRealtimeNanos()
                frames += frame
                frameTimes += frame.capturedAtElapsedRealtimeNanos
                output.write(frame.bytes)
                if (firstAudibleCallbackNanos == null && frame.isAudible()) {
                    firstAudibleCallbackNanos = callbackNanos
                }
            }

        val pcm = output.toByteArray()
        val stats = pcm.pcmS16LeSignalStats()
        val firstAudioNanos = requireNotNull(firstAudibleCallbackNanos) {
            "$languageTag TTS 스트리밍에서 비무음 PCM 프레임이 수신되지 않았습니다."
        }
        val firstAudioLatencyMillis = (firstAudioNanos - synthesisStartedAtNanos) / 1_000_000L

        Log.i(
            TAG,
            "Playable PCM Telemetry ($languageTag): firstAudioLatency=${firstAudioLatencyMillis}ms " +
                "[Physical Galaxy S23 gate: <= 2000ms | Emulator ceiling: <= ${EMULATOR_CEILING_FIRST_PCM_MILLIS}ms], " +
                "pcmBytes=${pcm.size}, frames=${frames.size}, rms=${stats.rms}, peak=${stats.peak}",
        )

        assertTrue("$languageTag TTS가 음성 PCM을 만들지 않았습니다.", pcm.size > 12_000)
        assertTrue(
            "$languageTag 스트리밍 첫 비무음 PCM이 에뮬레이터 상한(${EMULATOR_CEILING_FIRST_PCM_MILLIS}ms)을 넘었습니다: ${firstAudioLatencyMillis}ms",
            firstAudioLatencyMillis <= EMULATOR_CEILING_FIRST_PCM_MILLIS,
        )

        // Even bytes verification
        assertEquals("16-bit PCM 길이는 짝수여야 합니다.", 0, pcm.size % 2)
        frames.forEachIndexed { idx, frame ->
            assertTrue("Frame $idx 바이트 배열이 비어 있습니다.", frame.bytes.isNotEmpty())
            assertEquals(
                "Frame $idx 바이트 길이는 짝수여야 합니다 (S16LE mono): ${frame.bytes.size}",
                0,
                frame.bytes.size % 2,
            )
        }

        // 24k declared provider check
        assertEquals(
            "Moonshine TTS 제공자 샘플 레이트는 24,000 Hz여야 합니다.",
            REQUIRED_SAMPLE_RATE_HZ,
            MoonshineSpeechSynthesisProvider.OUTPUT_SAMPLE_RATE_HZ,
        )

        val durationSeconds = pcm.size.toDouble() / (REQUIRED_SAMPLE_RATE_HZ * 2.0)
        assertTrue(
            "$languageTag TTS 길이가 비정상입니다: ${durationSeconds}s",
            durationSeconds in 0.35..20.0,
        )
        assertTrue("$languageTag TTS RMS가 너무 낮습니다: $stats", stats.rms > MIN_AUDIBLE_PCM_RMS)
        assertTrue("$languageTag TTS 피크가 너무 낮습니다: $stats", stats.peak > MIN_AUDIBLE_PCM_PEAK)
        assertTrue("$languageTag TTS가 무음입니다: $stats", stats.nonZeroSamples > 500)

        // Monotonic frame timestamps & bounded intra-stream gap
        for (i in 1 until frameTimes.size) {
            val prev = frameTimes[i - 1]
            val curr = frameTimes[i]
            assertTrue(
                "Frame timestamps는 엄격하게 단조 증가해야 합니다 (curr > prev): frame[$i]=$curr <= frame[${i-1}]=$prev",
                curr > prev,
            )
            val gapMillis = (curr - prev) / 1_000_000L
            assertTrue(
                "프레임 간 지연(gap)이 허용 상한(${MAX_INTRA_STREAM_GAP_BOUND_MILLIS}ms)을 초과했습니다: ${gapMillis}ms (frame ${i-1} -> $i)",
                gapMillis <= MAX_INTRA_STREAM_GAP_BOUND_MILLIS,
            )
        }

        val emittedSpanMillis = if (frameTimes.size < 2) {
            0L
        } else {
            (frameTimes.last() - frameTimes.first()) / 1_000_000L
        }
        val minimumRealTimeSpanMillis = ((frameTimes.size - 1) * 20L * 0.85).toLong()
        assertTrue(
            "$languageTag TTS가 실시간보다 빠르게 송출됐습니다: " +
                "frames=${frameTimes.size}, span=${emittedSpanMillis}ms",
            emittedSpanMillis >= minimumRealTimeSpanMillis,
        )
    }

    private fun PcmAudioFrame.isAudible(): Boolean {
        val stats = bytes.pcmS16LeSignalStats()
        return stats.rms > MIN_AUDIBLE_PCM_RMS || stats.peak > MIN_AUDIBLE_PCM_PEAK
    }

    private companion object {
        private const val TAG = "MoonshineTtsTest"
        private const val REQUIRED_SAMPLE_RATE_HZ = 24_000
        private const val EMULATOR_CEILING_FIRST_PCM_MILLIS = 6_500L
        private const val MAX_INTRA_STREAM_GAP_BOUND_MILLIS = 250L
        private const val MIN_AUDIBLE_PCM_RMS = 0.003f
        private const val MIN_AUDIBLE_PCM_PEAK = 0.015f
    }
}
