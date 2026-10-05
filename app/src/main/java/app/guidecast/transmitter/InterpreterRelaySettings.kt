package app.guidecast.transmitter

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

internal data class InterpreterRelayOptions(
    val source: String = "ko-KR", val target: String = "en",
    val networkBroadcast: Boolean = false, val localPlayback: Boolean = true,
    val compareOffline: Boolean = false,
)

/** Separate relay choices and portable service profiles. Credentials remain in the existing vault. */
internal class InterpreterRelaySettings(context: Context) {
    private val comparisonEpoch = java.util.concurrent.atomic.AtomicLong()
    val comparisonGeneration: Long get() = comparisonEpoch.get()
    private val preferences = context.getSharedPreferences("interpreter_relay", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(InterpreterRelayOptions(
        source = preferences.getString("source", "ko-KR") ?: "ko-KR",
        target = preferences.getString("target", "en") ?: "en",
        networkBroadcast = preferences.getBoolean("network", false),
        localPlayback = preferences.getBoolean("playback", true),
        compareOffline = preferences.getBoolean("compare", false),
    ))
    val state = mutable.asStateFlow()
    @Synchronized fun update(options: InterpreterRelayOptions) {
        require(SOURCE_LANGUAGE_OPTIONS.any { it.languageTag == options.source })
        require(translationTargetLanguageOptions(options.source).any { it.languageTag == options.target })
        if (options != mutable.value) comparisonEpoch.incrementAndGet()
        preferences.edit().putString("source", options.source).putString("target", options.target)
            .putBoolean("network", options.networkBroadcast).putBoolean("playback", options.localPlayback)
            .putBoolean("compare", options.compareOffline).apply()
        mutable.value = options
    }
    fun rememberRelayApi(options: TranslationApiOptions) {
        if (options.usesNativeLiveAudio) preferences.edit().putString("relay_api", options.portable().toString()).apply()
    }
    private fun load(name: String): TranslationApiOptions? = runCatching {
        preferences.getString(name, null)?.let { TranslationApiOptions.fromPortable(JSONObject(it)) }
    }.getOrNull()
    fun enterRelay(settings: TranslationApiSettings) {
        val current = settings.state.value
        // Mode navigation changes choices only; configure explicitly withdraws old transmission consent.
        if (!current.usesNativeLiveAudio) preferences.edit().putString("stream_api", current.portable().toString()).apply()
        val saved = load("relay_api")?.takeIf { it.usesNativeLiveAudio }
        settings.configure(saved ?: current.takeIf { it.usesNativeLiveAudio } ?: current.copy(provider = TranslationApiProvider.GEMINI_LIVE,
            model = GEMINI_LIVE_TRANSLATE, baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            interpretationMode = OnlineInterpretationMode.CONTINUOUS, protocol = TranslationApiProtocol.RESPONSES,
            realtimeAudio = false))
    }
    fun enterStreaming(settings: TranslationApiSettings) {
        val current = settings.state.value
        if (!current.usesNativeLiveAudio) return
        rememberRelayApi(current)
        val saved = load("stream_api")?.takeIf { !it.usesNativeLiveAudio }
        settings.configure(saved ?: geminiSharedInputChoice(current))
    }
}
