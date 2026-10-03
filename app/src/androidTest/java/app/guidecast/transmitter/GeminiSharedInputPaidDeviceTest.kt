package app.guidecast.transmitter

import androidx.test.platform.app.InstrumentationRegistry
import app.guidecast.core.translation.SharedTranslationBatchContext
import app.guidecast.core.translation.TextTranslationEngine
import app.guidecast.core.translation.TranslationRequestIdentity
import app.guidecast.core.audio.pcmS16LeSignalStats
import kotlinx.coroutines.flow.collect
import java.io.File
import android.content.Intent
import android.app.Activity
import app.guidecast.core.audio.AudioInputKind
import app.guidecast.core.stream.PcmAudioFrame
import app.guidecast.core.translation.SpeechRecognitionConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in actual paid generation. Synthetic TEXT only; never a microphone/listener acceptance. */
class GeminiSharedInputPaidDeviceTest {
    @Test fun oneKoreanInputGeneratesFourLanguagesWithOneSharedRequest() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit paid smoke invocation required", args.getString("paidBatchApproved") == "true")
        require(args.getString("maxRequests") == "1")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as GuideCastApplication
        val settings = app.translationApiSettings
        val previous = settings.state.value
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val inputId = java.util.UUID.randomUUID().toString()
        val targets = listOf("en", "zh", "ja", "ru")
        val microphone = args.getString("inputKind") == "MIC"
        var activity: Activity? = null
        val report = JSONObject().put("inputKind", if (microphone) "PHYSICAL_MICROPHONE" else "SYNTHETIC_TEXT_ONLY")
            .put("microphoneTest", microphone).put("listenerPlaybackTest", false)
            .put("providerConsoleChecked", false).put("targets", JSONArray(targets))
            .put("inputId", inputId).put("maxRequests", 1).put("outcome", "NOT_STARTED")
        val service = TranslationApiService(settings)
        try {
            assertTrue(settings.configure(geminiSharedInputChoice(previous)))
            assertTrue("A stored scoped Google key is required; never insert one into test args", settings.state.value.hasKey)
            require((settings.state.value.budgetLimitUsd.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO).signum() > 0)
            settings.consentToSelectedService()
            assertTrue(settings.authorized(settings.state.value))
            var original = "안녕하세요. 오늘 안내 방송을 시작합니다."
            if (microphone) {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                assertTrue(withTimeout(30_000) {
                    app.withProcessNativeColdLoadLease(ProcessNativeColdLoadKeys.speechRecognition("ko-KR"), true) {
                        app.speechRecognitionEngine.prepareLanguage("ko-KR").isReady
                    }
                })
                app.audioInputRepository.refresh()
                val device = app.audioInputRepository.availableDevices.value.first { it.kind == AudioInputKind.BUILT_IN }
                report.put("outcome", "MICROPHONE_READY")
                File(context.getExternalFilesDir(null), "paid-batch-smoke.json").writeText(report.toString(2))
                var frames = 0L; var audible = 0L; var partials = 0L
                val captured = app.audioCaptureEngine.frames(device.platformId).map { frame ->
                    frames++
                    if (frame.bytes.pcmS16LeSignalStats().peak > 0f) audible++
                    PcmAudioFrame(frame.bytes, frame.capturedAtElapsedRealtimeNanos)
                }
                val final = try { withTimeout(45_000) {
                    app.speechRecognitionEngine.recognize(captured, SpeechRecognitionConfig("ko-KR"))
                        .onEach { if (!it.isFinal) partials++ }.first { it.isFinal && !it.isRetracted }
                } } finally { report.put("captureFrames", frames).put("audibleFrames", audible).put("sttPartialCount", partials) }
                original = final.text
                report.put("sttFinalCount", 1).put("sttFinalSequence", final.sequence)
                    .put("recognizedAtNanos", final.recognizedAtElapsedRealtimeNanos)
                    .put("capturedAtNanos", final.capturedAtElapsedRealtimeNanos)
                File(context.getExternalFilesDir(null), "paid-batch-smoke.json").writeText(report.toString(2))
            }
            val batch = SharedTranslationBatchContext(inputId, targets, parent, { settings.authorized(settings.state.value) })
            val started = android.os.SystemClock.elapsedRealtime()
            val translated = withTimeout(12_000) { targets.map { target -> async {
                withContext(batch + TranslationRequestIdentity(inputId, 1)) {
                    service.engine(TextTranslationEngine { _, _, _ -> error("No local translation fallback") })
                        .translate(original, "ko", target)
                }
            } }.awaitAll() }
            assertTrue(translated.all { it.isNotBlank() })
            assertEquals(1L, service.usage.value.requests)
            report.put("outcome", "FOUR_TEXT_RESULTS_COMPLETE")
                .put("elapsedMillis", android.os.SystemClock.elapsedRealtime() - started)
                .put("completeTargets", JSONArray(targets))
            // Persist the generation receipt BEFORE local voice preparation so a voice failure
            // cannot obscure whether the paid shared request actually completed.
            val received = service.usage.value
            report.put("logicalRequestCount", received.requests)
                .put("promptTokenCount", received.reportedPromptTokens ?: JSONObject.NULL)
                .put("candidatesTokenCount", received.reportedCandidateTokens ?: JSONObject.NULL)
                .put("thoughtsTokenCount", received.reportedThoughtTokens ?: JSONObject.NULL)
                .put("totalTokenCount", received.reportedTotalTokens ?: JSONObject.NULL)
            File(context.getExternalFilesDir(null), "paid-batch-smoke.json").writeText(report.toString(2))
            if (args.getString("verifyPcm") == "true") {
                val preparation = runCatching { withTimeout(60_000) {
                    app.speechSynthesisProvider.prepareForSettings(targets.toSet(),
                        warmMoonshineWithNativeAdmission = { language, warm ->
                            app.withProcessNativeColdLoadLease(ProcessNativeColdLoadKeys.speech(language), true) { warm() }
                        }, isAppPreparationCurrent = { true })
                } }
                val prepared = preparation.getOrNull()
                report.put("voicePreparation", when {
                    prepared == null -> "FAILED"
                    prepared.unavailableLanguageReasons.isNotEmpty() -> "PARTIAL"
                    else -> "ALL_REQUESTED_VOICES_READY"
                })
                report.put("unavailableVoiceTargets", JSONArray(prepared?.unavailableLanguageReasons?.keys?.toList().orEmpty()))
                val pcmResults = targets.mapIndexed { index, language -> async {
                    val entry = JSONObject().put("target", language).put("frames", 0).put("nonSilentFrames", 0)
                    var frames = 0L
                    var nonSilent = 0L
                    var byteCount = 0L
                    try {
                        withTimeout(30_000) {
                            app.speechSynthesisProvider.engineFor(language).synthesize(translated[index], language).collect { frame ->
                                frames++; byteCount += frame.bytes.size
                                if (frame.bytes.pcmS16LeSignalStats().peak > 0f) nonSilent++
                            }
                        }
                        entry.put("outcome", if (nonSilent > 0) "NON_SILENT_PCM" else "NO_AUDIBLE_PCM")
                    } catch (error: Exception) { entry.put("outcome", "FAILED").put("errorType", error.javaClass.simpleName) }
                    entry.put("frames", frames).put("nonSilentFrames", nonSilent).put("bytes", byteCount)
                } }.awaitAll()
                report.put("pcm", JSONArray(pcmResults))
            }
        } catch (error: Throwable) {
            report.put("outcome", "FAILED").put("errorType", error.javaClass.simpleName)
            throw error
        } finally {
            parent.cancel()
            val usage = service.usage.value
            report.put("logicalRequestCount", usage.requests).put("unconfirmedUsageRequests", usage.unconfirmedUsage)
                .put("promptTokenCount", usage.reportedPromptTokens ?: JSONObject.NULL)
                .put("candidatesTokenCount", usage.reportedCandidateTokens ?: JSONObject.NULL)
                .put("thoughtsTokenCount", usage.reportedThoughtTokens ?: JSONObject.NULL)
                .put("totalTokenCount", usage.reportedTotalTokens ?: JSONObject.NULL)
                .put("note", "Actual transport attempts/status/body counts are in the bounded batch_usage diagnostic ledger. Unknown billing is not free.")
            File(context.getExternalFilesDir(null), "paid-batch-smoke.json").writeText(report.toString(2))
            // Restore the prior destination but never revive a transmission permission automatically.
            settings.configure(previous)
            activity?.let { visible -> InstrumentationRegistry.getInstrumentation().runOnMainSync { visible.finish() } }
        }
    }
}
