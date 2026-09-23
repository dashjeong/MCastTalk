package app.guidecast.transmitter

import android.content.Intent
import android.os.Build
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Capability inventory only: no UI, preparation, downloads, language readiness query, or audio. */
class InstalledSpeechCapabilityDeviceTest {
    @Test fun recordsSevenSourceLanguageCapabilitiesWithoutClaimingRecognitionSuccess() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val languages = listOf("ko", "en", "ja", "zh", "fr", "de", "es")
        @Suppress("DEPRECATION")
        val serviceCount = context.packageManager.queryIntentServices(
            Intent(RecognitionService.SERVICE_INTERFACE), 0,
        ).size
        val onDeviceAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        val results = JSONArray()
        // A separate instance prevents changing the application's active recognition state.
        GalaxySpeechRecognitionEngine(context).use { engine ->
            languages.forEach { language ->
                val supported = language in GUIDECAST_SOURCE_LANGUAGE_BASE_TAGS
                val capability = if (supported) engine.capability(language) else null
                results.put(JSONObject()
                    .put("language", language)
                    .put("appSourceSupported", supported)
                    .put("engineAvailable", capability?.available ?: JSONObject.NULL)
                    .put("capabilityReason", capability?.reason ?: JSONObject.NULL)
                    .put("capabilityStatus", when {
                        !supported -> "APP_SOURCE_UNSUPPORTED"
                        capability?.available == true -> "ENGINE_CAPABILITY_AVAILABLE"
                        else -> "ENGINE_CAPABILITY_UNAVAILABLE"
                    })
                    .put("languageReadiness", "NOT_QUERIED")
                    .put("actualLanguageRecognitionSupport", "NOT_TESTED")
                    .put("asrExecuted", false)
                    .put("asrPassed", JSONObject.NULL)
                    .put("qualityAssessment", "NOT_SCORED"))
            }
        }
        val report = JSONObject()
            .put("schemaVersion", 1)
            .put("recordedAtUtc", Instant.now().toString())
            .put("instrumentationEnvironment", "ANDROID_INSTRUMENTATION")
            // Hardware type is not inferred from a model name. The execution receipt identifies
            // the actual device; this inventory does not establish physical-device validation.
            .put("physicalDeviceVerificationPerformed", false)
            .put("hardwareType", "NOT_VERIFIED_BY_THIS_TEST")
            .put("sdkInt", Build.VERSION.SDK_INT)
            .put("visibleRecognitionServiceCount", serviceCount)
            .put("androidRecognitionServiceAvailable", SpeechRecognizer.isRecognitionAvailable(context))
            .put("androidOnDeviceRecognitionAvailable", onDeviceAvailable)
            .put("scope", "ENGINE_CAPABILITY_ONLY_NOT_LANGUAGE_READINESS_OR_ASR_QUALITY")
            .put("results", results)
        val externalFiles = checkNotNull(context.getExternalFilesDir(null)) {
            "CAPABILITY_REPORT_STORAGE_UNAVAILABLE"
        }
        val output = File(externalFiles, "benchmark/speech-capabilities.json")
        check(output.parentFile!!.isDirectory || output.parentFile!!.mkdirs()) {
            "CAPABILITY_REPORT_DIRECTORY_UNAVAILABLE"
        }
        output.writeText(report.toString(2))

        // Success means a complete, honest inventory was persisted, not that seven ASRs passed.
        val persisted = JSONObject(output.readText()).getJSONArray("results")
        assertEquals(7, persisted.length())
        val reportedLanguages = (0 until persisted.length()).map { index ->
            val row = persisted.getJSONObject(index)
            assertFalse(row.getBoolean("asrExecuted"))
            assertTrue(row.isNull("asrPassed"))
            assertEquals("NOT_QUERIED", row.getString("languageReadiness"))
            if (!row.getBoolean("appSourceSupported")) {
                assertTrue(row.isNull("engineAvailable"))
                assertEquals("APP_SOURCE_UNSUPPORTED", row.getString("capabilityStatus"))
            }
            row.getString("language")
        }
        assertEquals(languages, reportedLanguages)
    }
}
