package app.guidecast.transmitter

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.guidecast.core.stream.PcmAudioFrame
import app.guidecast.core.translation.RecognizedUtterance
import app.guidecast.core.translation.SpeechRecognitionConfig
import app.guidecast.provider.moonshine.stt.MoonshineSpeechRecognitionEngine
import app.guidecast.provider.moonshine.stt.MoonshineSttNativeReleaseResult
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android instrumentation test for Moonshine Korean STT engine.
 *
 * NOTE ON ACOUSTIC FIDELITY & ENVIRONMENT SEPARATION (AGENTS.md & docs/DEVELOPMENT_GUIDELINES.md):
 * Deterministic PCM audio fixtures directly streamed through PcmAudioFrame validate digital IPC
 * contracts, PCM buffer mechanics, partial-to-final continuity, and endpoint latency telemetry.
 *
 * DO NOT pretend emulator synthetic audio proves physical microphone accuracy:
 * - Acoustic microphone transduction, room reverberation, outdoor wind/street noise, and Samsung
 *   One UI hardware audio HAL / AGC / NS routing can ONLY be verified on physical hardware.
 * - The physical Galaxy S23 baseline requires physical acoustic testing satisfying release gates
 *   (onset clip p95 <= 50ms, endpoint latency p95 <= 700ms, first-audio latency p95 <= 2,000ms).
 */
@RunWith(AndroidJUnit4::class)
class MoonshineSttDeviceIntegrationTest {

    @Test
    fun officialKoreanModelRecognizesDeterministicGuideUtterances(): Unit = runBlocking {
        Log.i(
            TAG,
            "[DISCLAIMER] Emulator synthetic audio verifies only IPC contracts and engine framing; " +
                "it does NOT substitute physical Galaxy S23 microphone acoustic qualification.",
        )

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        // 1. Offline Korean PCM fixture contract (Google FLEURS row 7, id 1959, CC-BY-4.0, 7.02s duration < 12s VAD max)
        val fixtureSpecs = listOf(
            KoreanPcmFixtureSpec(
                assetPath = "fixtures/fleurs-ko-1959.pcm",
                transcript = "그래도 관계자의 조언을 듣고 모든 표지판을 지키고, 안전 경고에 세심한 주의를 기울여야 합니다.",
                requiredKeywords = listOf("관계자", "조언", "표지판", "안전", "경고", "주의"),
                sampleRateHz = TARGET_STT_SAMPLE_RATE_HZ,
                channelCount = 1,
                encoding = "pcm_s16le",
                sampleCount = 112_320,
                byteSize = 224_640,
                convertedPcmSha256 = "b35aa5acf7ff72a4ec1b68415957ac9e7fe89268312cc8f5e5325a88acea841f",
                originalWavSha256 = "61ed57382352c83a8a0f1799854237e00204df6c3e1c37f93d8231d0a07738d4",
                dataset = "google/fleurs",
                config = "ko_kr",
                split = "test",
                rowIdx = 7,
                sampleId = 1959,
                revision = "70bb2e84b976b7e960aa89f1c648e09c59f894dd",
                sourceUrl = "https://huggingface.co/datasets/google/fleurs",
                license = "CC-BY-4.0 (https://huggingface.co/datasets/google/fleurs/discussions/2)",
                conversionCommand = "afconvert -f WAVE -d LEI16@16000 -c 1 fleurs-ko-1959.wav fleurs-ko-1959-s16le.wav && dd if=fleurs-ko-1959-s16le.wav of=fleurs-ko-1959.pcm bs=4096 skip=1",
            ),
        )

        // Load fixtures and enforce strict contract & integrity (fails via assumption if missing)
        val loadedFixtures = fixtureSpecs.map { spec ->
            val pcmBytes = runCatching {
                instrumentation.context.assets.open(spec.assetPath).use { it.readBytes() }
            }.recoverCatching {
                instrumentation.targetContext.assets.open(spec.assetPath).use { it.readBytes() }
            }.getOrNull()

            assumeTrue(
                "Korean STT PCM fixture asset (${spec.assetPath}) is missing from test APK. " +
                    "Dataset: ${spec.dataset} id=${spec.sampleId}. Cannot run offline STT regression without valid fixture.",
                pcmBytes != null,
            )
            val nonNullBytes = requireNotNull(pcmBytes)

            val digest = MessageDigest.getInstance("SHA-256")
                .digest(nonNullBytes)
                .joinToString("") { "%02x".format(it) }

            assumeTrue(
                "Korean STT PCM fixture checksum mismatch: expected=${spec.convertedPcmSha256}, actual=$digest",
                digest.equals(spec.convertedPcmSha256, ignoreCase = true),
            )
            assumeTrue(
                "Korean STT PCM fixture byte size mismatch: expected=${spec.byteSize}, actual=${nonNullBytes.size}",
                nonNullBytes.size == spec.byteSize,
            )
            spec to nonNullBytes
        }

        // 2. Prepare Moonshine STT engine (deterministic offline verification when cache exists)
        val recognizer = MoonshineSpeechRecognitionEngine(context)
        try {
            withTimeout(15 * 60 * 1_000L) { recognizer.prepareLanguage("ko-KR") }
            assertTrue("한국어 Moonshine STT가 READY 상태가 아닙니다.", recognizer.languageStatus("ko-KR").isReady)

            val telemetryRecords = mutableListOf<SttUtteranceTelemetry>()

            // 3. Test each deterministic guide utterance through streaming recognition
            loadedFixtures.forEachIndexed { index, (fixtureSpec, pcmBytes) ->
                val emittedUtterances = mutableListOf<RecognizedUtterance>()
                val lastAudibleSampleInjectionNanos = AtomicLong(0L)

                val shorts = ShortArray(pcmBytes.size / 2)
                ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                val lastAudibleSampleIndex = shorts.indices.reversed().firstOrNull { kotlin.math.abs(shorts[it].toInt()) > 400 }
                    ?: (shorts.size - 1)

                // Let finite PCM + silence flow end and collect recognition flow under timeout
                withTimeout(3 * 60 * 1_000L) {
                    recognizer.recognize(
                        frames = flow {
                            val frameSamples = 320 // 20ms at 16kHz mono
                            var offset = 0
                            while (offset < shorts.size) {
                                val count = minOf(frameSamples, shorts.size - offset)
                                val frameStartNanos = SystemClock.elapsedRealtimeNanos()
                                if (lastAudibleSampleIndex in offset until (offset + count)) {
                                    val sampleOffsetInFrame = lastAudibleSampleIndex - offset
                                    val sampleOffsetNanos = (sampleOffsetInFrame * 1_000_000_000L) / TARGET_STT_SAMPLE_RATE_HZ
                                    lastAudibleSampleInjectionNanos.set(frameStartNanos + sampleOffsetNanos)
                                }
                                emit(shorts.toPcmFrame(offset, count))
                                offset += count
                                delay(20)
                            }
                            if (lastAudibleSampleInjectionNanos.get() == 0L) {
                                lastAudibleSampleInjectionNanos.set(SystemClock.elapsedRealtimeNanos())
                            }
                            // Trailing silence to prompt endpoint detection
                            repeat(75) {
                                emit(ByteArray(frameSamples * 2).asFrame())
                                delay(20)
                            }
                        },
                        config = SpeechRecognitionConfig(sourceLanguageTag = "ko-KR"),
                    ).transformWhile { utterance ->
                        emit(utterance)
                        !utterance.isFinal
                    }.collect { utterance ->
                        emittedUtterances += utterance
                    }
                }

                // 4. Reject blank output
                assertTrue("발화 $index: 인식 이벤트가 전혀 수신되지 않았습니다.", emittedUtterances.isNotEmpty())
                emittedUtterances.forEach { utterance ->
                    assertFalse(
                        "Moonshine STT 출력이 비어 있습니다: seq=${utterance.sequence}, isFinal=${utterance.isFinal}",
                        utterance.text.isBlank(),
                    )
                    assertTrue(
                        "Moonshine STT 출력이 공백 문자만 포함합니다.",
                        utterance.text.trim().isNotEmpty(),
                    )
                }

                // 5. Assert finals exactly once per expected sequence
                val finals = emittedUtterances.filter { it.isFinal }
                assertEquals(
                    "단일 발화 세그먼트에 최종 확정 문장(isFinal=true)은 정확히 하나여야 합니다: $finals",
                    1,
                    finals.size,
                )
                val finalUtterance = finals.single()
                assertFalse("최종 확정 문장이 비어 있습니다.", finalUtterance.text.isBlank())

                val finalSequences = finals.map { it.sequence }
                assertEquals(
                    "최종 확정 sequence는 중복될 수 없습니다: $finalSequences",
                    finalSequences.distinct().size,
                    finalSequences.size,
                )

                // 6. Partial-to-final continuity where observable
                val normalizedFinal = normalizeKoreanText(finalUtterance.text)
                val partials = emittedUtterances.filter { !it.isFinal }
                if (partials.isNotEmpty()) {
                    var previousSequence = -1L

                    partials.forEach { partial ->
                        assertFalse("부분 인식(partial) 텍스트가 비어 있습니다.", partial.text.isBlank())

                        // Monotonic sequence numbers across partials
                        if (previousSequence >= 0L) {
                            assertTrue(
                                "부분 인식 sequence가 단조 증가하지 않습니다: prev=$previousSequence, curr=${partial.sequence}",
                                partial.sequence >= previousSequence,
                            )
                        }
                        previousSequence = partial.sequence

                        // Continuity check between partial hypothesis and final result
                        val normalizedPartial = normalizeKoreanText(partial.text)
                        val lcs = longestCommonSubsequenceLength(normalizedPartial, normalizedFinal)
                        val continuityRatio = if (normalizedPartial.isNotEmpty()) {
                            lcs.toDouble() / normalizedPartial.length
                        } else 0.0

                        assertTrue(
                            "부분 가설('$normalizedPartial')과 최종 가설('$normalizedFinal') 간 연속성이 결여되었습니다 " +
                                "(LCS=$lcs, continuityRatio=$continuityRatio)",
                            normalizedFinal.contains(normalizedPartial) || continuityRatio >= 0.5,
                        )
                    }
                }

                // 7. Normalized accuracy metric & required semantic keyword retention
                val normalizedSource = normalizeKoreanText(fixtureSpec.transcript)

                val matchedKeywords = fixtureSpec.requiredKeywords.filter { keyword ->
                    normalizedFinal.contains(normalizeKoreanText(keyword))
                }
                val keywordRetentionRate = matchedKeywords.size.toDouble() / fixtureSpec.requiredKeywords.size

                val editDistance = levenshteinDistance(normalizedSource, normalizedFinal)
                val maxLen = maxOf(normalizedSource.length, normalizedFinal.length)
                val normalizedAccuracy = if (maxLen > 0) {
                    (1.0 - editDistance.toDouble() / maxLen).coerceIn(0.0, 1.0)
                } else 1.0

                assertTrue(
                    "의미론적 필수 키워드 보존율이 기준치(0.5) 미만입니다: " +
                        "보존율=$keywordRetentionRate, 검출키워드=$matchedKeywords, 기대키워드=${fixtureSpec.requiredKeywords}, " +
                        "인식결과='${finalUtterance.text}'",
                    keywordRetentionRate >= 0.5,
                )
                assertTrue(
                    "정규화된 한국어 인식 정확도가 기준치(0.60) 미만입니다: " +
                        "accuracy=$normalizedAccuracy, source='$normalizedSource', recognized='$normalizedFinal'",
                    normalizedAccuracy >= 0.60,
                )

                // 8. Record endpoint latency relative to last audible sample injection (no negative hiding)
                val arrivalNanos = if (finalUtterance.recognizedAtElapsedRealtimeNanos > 0L) {
                    finalUtterance.recognizedAtElapsedRealtimeNanos
                } else {
                    SystemClock.elapsedRealtimeNanos()
                }
                val lastAudibleInjectionNanos = lastAudibleSampleInjectionNanos.get()
                check(lastAudibleInjectionNanos > 0L) { "마지막 비무음 샘플 주입 시각이 기록되지 않았습니다." }

                val endpointLatencyMillis = (arrivalNanos - lastAudibleInjectionNanos) / 1_000_000L

                assertTrue(
                    "Moonshine STT endpoint latency는 양수여야 합니다 (도착 시각 $arrivalNanos vs 마지막 비무음 샘플 주입 시각 $lastAudibleInjectionNanos): ${endpointLatencyMillis}ms",
                    endpointLatencyMillis > 0L,
                )
                assertTrue(
                    "Moonshine STT endpoint latency가 에뮬레이터 상한(${EMULATOR_ENDPOINT_CEILING_MILLIS}ms)을 초과했습니다: ${endpointLatencyMillis}ms",
                    endpointLatencyMillis <= EMULATOR_ENDPOINT_CEILING_MILLIS,
                )

                val telemetry = SttUtteranceTelemetry(
                    index = index,
                    sourceText = fixtureSpec.transcript,
                    recognizedText = finalUtterance.text,
                    partialCount = partials.size,
                    keywordRetentionRate = keywordRetentionRate,
                    matchedKeywords = matchedKeywords,
                    normalizedAccuracy = normalizedAccuracy,
                    endpointLatencyMillis = endpointLatencyMillis,
                )
                telemetryRecords += telemetry

                Log.i(
                    TAG,
                    "STT Utterance $index Telemetry: '${fixtureSpec.transcript}' -> '${finalUtterance.text}' | " +
                        "keywordRetention=${"%.2f".format(keywordRetentionRate)} ($matchedKeywords), " +
                        "normalizedAccuracy=${"%.2f".format(normalizedAccuracy)}, " +
                        "endpointLatency=${endpointLatencyMillis}ms " +
                        "[Physical S23 gate: p95 <=700ms | Emulator ceiling: <=${EMULATOR_ENDPOINT_CEILING_MILLIS}ms]",
                )
            }

            val stoppedSessionUseEpoch = recognizer.nativeResourceUseEpoch()
            assertEquals(
                "종료된 인식 세션의 Moonshine 네이티브 자원을 반환하지 못했습니다.",
                MoonshineSttNativeReleaseResult.RELEASED,
                recognizer.releaseNativeResourcesIfUnchanged(stoppedSessionUseEpoch),
            )
            val reboundStatus = withTimeout(15 * 60 * 1_000L) {
                recognizer.prepareLanguage("ko-KR")
            }
            assertTrue(
                "네이티브 자원 반환 뒤 동일 STT 엔진이 새 작업자에 재연결되지 않았습니다: " +
                    reboundStatus.message,
                reboundStatus.isReady,
            )
            assertEquals(
                "오래된 정리 요청이 새로 준비한 Moonshine STT 작업자를 종료하려 했습니다.",
                MoonshineSttNativeReleaseResult.SUPERSEDED,
                recognizer.releaseNativeResourcesIfUnchanged(stoppedSessionUseEpoch),
            )

            // Summary of all deterministic guide utterances
            Log.i(
                TAG,
                "Korean Guide STT Test Summary: ${telemetryRecords.size} utterances tested. " +
                    "Endpoint latencies: [${telemetryRecords.joinToString { "${it.endpointLatencyMillis}ms" }}], " +
                    "Keyword retentions: [${telemetryRecords.joinToString { "%.2f".format(it.keywordRetentionRate) }}]",
            )
        } finally {
            recognizer.close()
        }
    }

    private data class KoreanPcmFixtureSpec(
        val assetPath: String,
        val transcript: String,
        val requiredKeywords: List<String>,
        val sampleRateHz: Int,
        val channelCount: Int,
        val encoding: String,
        val sampleCount: Int,
        val byteSize: Int,
        val convertedPcmSha256: String,
        val originalWavSha256: String,
        val dataset: String,
        val config: String,
        val split: String,
        val rowIdx: Int,
        val sampleId: Int,
        val revision: String,
        val sourceUrl: String,
        val license: String,
        val conversionCommand: String,
    )

    private data class SttUtteranceTelemetry(
        val index: Int,
        val sourceText: String,
        val recognizedText: String,
        val partialCount: Int,
        val keywordRetentionRate: Double,
        val matchedKeywords: List<String>,
        val normalizedAccuracy: Double,
        val endpointLatencyMillis: Long,
    )

    private companion object {
        private const val TAG = "MoonshineSttTest"
        private const val TARGET_STT_SAMPLE_RATE_HZ = 16_000
        private const val EMULATOR_ENDPOINT_CEILING_MILLIS = 30_000L
    }
}

private fun normalizeKoreanText(text: String): String =
    text.filter { char ->
        char.isDigit() || char in '\uAC00'..'\uD7A3' || char in '\u1100'..'\u11FF' || char in '\u3130'..'\u318F'
    }

private fun levenshteinDistance(s1: String, s2: String): Int {
    val dp = IntArray(s2.length + 1) { it }
    for (i in 1..s1.length) {
        var prev = dp[0]
        dp[0] = i
        for (j in 1..s2.length) {
            val temp = dp[j]
            dp[j] = if (s1[i - 1] == s2[j - 1]) {
                prev
            } else {
                minOf(prev, dp[j], dp[j - 1]) + 1
            }
            prev = temp
        }
    }
    return dp[s2.length]
}

private fun longestCommonSubsequenceLength(s1: String, s2: String): Int {
    if (s1.isEmpty() || s2.isEmpty()) return 0
    val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
    for (i in 1..s1.length) {
        for (j in 1..s2.length) {
            dp[i][j] = if (s1[i - 1] == s2[j - 1]) {
                dp[i - 1][j - 1] + 1
            } else {
                maxOf(dp[i - 1][j], dp[i][j - 1])
            }
        }
    }
    return dp[s1.length][s2.length]
}

private fun ShortArray.toPcmFrame(offset: Int, count: Int): PcmAudioFrame =
    ByteBuffer.allocate(count * 2)
        .order(ByteOrder.LITTLE_ENDIAN)
        .apply {
            for (index in offset until (offset + count)) {
                putShort(this@toPcmFrame[index])
            }
        }
        .array()
        .asFrame()

private fun ByteArray.asFrame() = PcmAudioFrame(
    bytes = this,
    capturedAtElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
)
