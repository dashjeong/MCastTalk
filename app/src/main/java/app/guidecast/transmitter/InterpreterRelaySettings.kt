package app.guidecast.transmitter

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

internal const val MAX_RELAY_LANGUAGES = 5

internal data class InterpreterRelayOptions(
    val source: String = "ko-KR", val target: String = "en",
    val networkBroadcast: Boolean = true, val localPlayback: Boolean = true,
    val compareOffline: Boolean = false,
    val targets: List<String> = emptyList(),
    val broadcastTitle: String = "",
) {
    /** The legacy target remains the device monitor language. */
    val targetLanguageTags: List<String> get() = targets.ifEmpty { listOf(target) }
    fun withTargets(tags: List<String>): InterpreterRelayOptions {
        require(tags.size in 1..MAX_RELAY_LANGUAGES && tags.distinct().size == tags.size)
        require(tags.all { tag -> nativeRelayTargetLanguageOptions(source).any { it.languageTag == tag } })
        return copy(targets = tags.toList(), target = target.takeIf { it in tags } ?: tags.first())
    }
    fun withSource(sourceTag: String): InterpreterRelayOptions {
        require(NATIVE_RELAY_SOURCE_LANGUAGE_OPTIONS.any { it.languageTag == sourceTag })
        val available = nativeRelayTargetLanguageOptions(sourceTag).map { it.languageTag }.toSet()
        val retained = targetLanguageTags.filter { it in available }
        return copy(source = sourceTag).withTargets(retained.ifEmpty {
            listOf(if ("en" in available) "en" else "ko")
        })
    }
}

/** Separate relay choices and portable service profiles. Credentials remain in the existing vault. */
internal class InterpreterRelaySettings(context: Context) {
    private val comparisonEpoch = java.util.concurrent.atomic.AtomicLong()
    val comparisonGeneration: Long get() = comparisonEpoch.get()
    private val preferences = context.getSharedPreferences("interpreter_relay", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(InterpreterRelayOptions(
        source = preferences.getString("source", "ko-KR") ?: "ko-KR",
        target = preferences.getString("target", "en") ?: "en",
        networkBroadcast = preferences.getBoolean("network", true),
        localPlayback = preferences.getBoolean("playback", true),
        compareOffline = preferences.getBoolean("compare", false),
        broadcastTitle = preferences.getString("broadcast_title", "").orEmpty(),
    ).let { legacy ->
        val stored = preferences.getString("targets", null)?.split(',').orEmpty()
        val valid = stored.filter { tag -> nativeRelayTargetLanguageOptions(legacy.source).any { it.languageTag == tag } }
            .distinct().take(MAX_RELAY_LANGUAGES)
        legacy.withTargets(valid.ifEmpty { listOf(legacy.target) })
    })
    val state = mutable.asStateFlow()
    @Synchronized fun update(options: InterpreterRelayOptions) {
        require(NATIVE_RELAY_SOURCE_LANGUAGE_OPTIONS.any { it.languageTag == options.source })
        require(nativeRelayTargetLanguageOptions(options.source).any { it.languageTag == options.target })
        require(options.targetLanguageTags.size in 1..MAX_RELAY_LANGUAGES)
        require(options.targetLanguageTags.distinct().size == options.targetLanguageTags.size)
        require(options.target in options.targetLanguageTags)
        require(options.targetLanguageTags.all { tag -> nativeRelayTargetLanguageOptions(options.source).any { it.languageTag == tag } })
        if (options != mutable.value) comparisonEpoch.incrementAndGet()
        preferences.edit().putString("source", options.source).putString("target", options.target)
            .putBoolean("network", options.networkBroadcast).putBoolean("playback", options.localPlayback)
            .putBoolean("compare", options.compareOffline).putString("targets", options.targetLanguageTags.joinToString(","))
            .putString("broadcast_title", options.broadcastTitle).apply()
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
        if (saved != null) settings.configure(saved) else settings.restoreTextOnlineSelection()
    }
}
