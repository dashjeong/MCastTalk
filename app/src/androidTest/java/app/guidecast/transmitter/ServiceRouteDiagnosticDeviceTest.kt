package app.guidecast.transmitter

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Test

/** Read-only operator diagnostic, not functional API acceptance. Never exports keys or speech. */
class ServiceRouteDiagnosticDeviceTest {
    @Test fun exportCountOnlyBatchReceipts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = java.io.ByteArrayOutputStream()
        RuntimeDiagnosticLog.export(File(context.filesDir, "diagnostics"), output)
        val rows = mutableListOf<String>()
        java.util.zip.ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            while (zip.nextEntry != null) {
                rows += zip.readBytes().toString(Charsets.UTF_8).lineSequence()
                    .filter { it.contains(" batch_usage ") }.toList()
            }
        }
        // Product batch_usage contains only fixed states, opaque IDs and numeric counts.
        // Never export the other diagnostics or exception messages through this helper.
        File(context.getExternalFilesDir(null), "batch-usage-receipts.txt")
            .writeText(rows.takeLast(20).joinToString("\n"))
    }

    @Test fun exportCurrentAndLastPreparedProviderWithoutPrivateContent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("translation_api", Context.MODE_PRIVATE)
        fun route(raw: String?): JSONObject? {
            if (raw == null) return null
            val row = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            val provider = row.optString("provider", "LOCAL")
                .takeIf { it in TranslationApiProvider.entries.map { provider -> provider.name } } ?: "UNRECOGNIZED"
            val model = row.optString("model", "").takeIf { it in setOf(
                "gemini-3.8-live", "gemini-3.5-live-translate-preview", "gemini-3.5-flash-lite", "gpt-realtime-2.1-mini", "gpt-5.4-mini") }
            return JSONObject().put("provider", provider).put("model", model ?: "CUSTOM")
        }
        val runtime = TranslationApiSettings(context)
        val current = runtime.state.value
        val lastPrepared = runtime.preparedLearningProvider()
        val snapshot = JSONObject()
            .put("current", route(preferences.getString("options", null)) ?: JSONObject().put("provider", "LOCAL"))
            .put("lastPreparedOnline", route(preferences.getString("last_online_options", null)) ?: JSONObject.NULL)
            .put("storedOnlineConsent", preferences.getBoolean("allow_online", false))
            .put("storedAudioConsent", preferences.getBoolean("allow_live_audio", false))
            .put("runtimeProvider", current.provider.name)
            .put("runtimeRevision", current.revision)
            .put("currentHasKey", current.hasKey)
            .put("lastPreparedHasKey", lastPrepared?.hasKey ?: false)
            .put("runtimeOnlineConsent", current.allowOnline)
            .put("runtimeAudioConsent", current.allowLiveAudio)
            .put("billingTier", "NOT_VERIFIED; not available from device settings")
            .put("scope", "persisted settings after app recreation; not the previous live session")
            .put("functionalApiTest", false)
        File(context.getExternalFilesDir(null), "service-route-diagnostic.json").writeText(snapshot.toString(2))
    }
}
