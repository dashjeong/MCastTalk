package app.guidecast.transmitter

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Test
import app.guidecast.core.audio.pcmS16LeSignalStats

/** Local voice-only diagnostic. No API invocation, microphone, playback or private content. */
class RussianOfflineVoiceDeviceTest {
    @Test fun classifyRussianVoicePreparationAndPcm(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as GuideCastApplication
        val report = JSONObject().put("target", "ru").put("actualApiCalls", 0)
            .put("microphoneTest", false).put("listenerPlaybackTest", false)
        fun classify(reason: String?): String = when {
            reason == null -> "NONE"
            reason.contains("설치되지") || reason.contains("MISSING_DATA") -> "VOICE_DATA_MISSING"
            reason.contains("지원하지") || reason.contains("NOT_SUPPORTED") -> "LANGUAGE_UNSUPPORTED"
            reason.contains("선택하지") -> "VOICE_SELECTION_FAILED"
            reason.contains("initialization", true) || reason.contains("초기화") -> "INITIALIZATION_FAILED"
            else -> "UNCLASSIFIED_PREPARATION_FAILURE"
        }
        try {
            val prepared = withTimeout(60_000) {
                app.speechSynthesisProvider.prepareForSettings(setOf("ru"),
                    warmMoonshineWithNativeAdmission = { language, warm ->
                        app.withProcessNativeColdLoadLease(ProcessNativeColdLoadKeys.speech(language), true) { warm() }
                    }, isAppPreparationCurrent = { true })
            }
            report.put("androidFallbackReady", "ru" in prepared.androidFallbackLanguageTags)
                .put("preparationFailure", classify(prepared.unavailableLanguageReasons["ru"]))
            var frames = 0L; var nonSilent = 0L
            try {
                withTimeout(30_000) {
                    app.speechSynthesisProvider.engineFor("ru")
                        .synthesize("Здравствуйте. Сегодня начинается объявление.", "ru").collect { frame ->
                            frames++
                            if (frame.bytes.pcmS16LeSignalStats().peak > 0f) nonSilent++
                        }
                }
                report.put("synthesis", if (nonSilent > 0) "NON_SILENT_PCM" else "NO_AUDIBLE_PCM")
            } catch (error: Exception) {
                report.put("synthesis", "FAILED").put("errorType", error.javaClass.simpleName)
                    .put("errorFrames", error.stackTrace.take(8).joinToString(";") {
                        "${it.className}.${it.methodName}:${it.lineNumber}"
                    })
                report.put("synthesisFailure", classify(app.speechSynthesisProvider.unavailableReason("ru")))
            }
            report.put("frames", frames).put("nonSilentFrames", nonSilent)
        } catch (error: Exception) {
            report.put("preparation", "FAILED").put("errorType", error.javaClass.simpleName)
        } finally {
            File(context.getExternalFilesDir(null), "russian-offline-voice-diagnostic.json").writeText(report.toString(2))
        }
        Unit
    }
}
