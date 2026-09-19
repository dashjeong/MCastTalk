package app.guidecast.transmitter

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Portable preferences only; never credentials, live session identifiers or hardware handles. */
data class OperatorOptions(
    val sourceLanguageTag: String = DEFAULT_SOURCE_LANGUAGE_TAG,
    val targetLanguageTags: List<String> = listOf("en", "ja", "zh", "zh-TW", "vi"),
    val translationEnabled: Boolean = true,
    val useGemma: Boolean = true,
    val selectiveRefinement: Boolean = false,
    val runMode: BroadcastRunMode = BroadcastRunMode.NETWORK,
    val automaticPreparation: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().put("source", sourceLanguageTag)
        .put("targets", JSONArray(targetLanguageTags)).put("translation", translationEnabled)
        .put("gemma", useGemma).put("refinement", selectiveRefinement).put("runMode", runMode.name)
        .put("automaticPreparation", automaticPreparation)

    companion object {
        fun fromJson(row: JSONObject): OperatorOptions {
            val source = row.optString("source", DEFAULT_SOURCE_LANGUAGE_TAG)
                .takeIf { tag -> SOURCE_LANGUAGE_OPTIONS.any { it.languageTag == tag } } ?: DEFAULT_SOURCE_LANGUAGE_TAG
            val allowed = translationTargetLanguageOptions(source).map { it.languageTag }.toSet()
            val targets = row.optJSONArray("targets")?.let { array ->
                require(array.length() <= 100) { "Too many language preferences" }
                (0 until array.length()).map { array.getString(it) }.filter { it in allowed }.distinct().take(7)
            } ?: listOf("en", "ja", "zh", "zh-TW", "vi").filter { it in allowed }
            val gemma = row.optBoolean("gemma", true)
            return OperatorOptions(source, targets, row.optBoolean("translation", true), gemma,
                row.optBoolean("refinement", false) && gemma,
                runCatching { BroadcastRunMode.valueOf(row.optString("runMode", "NETWORK")) }.getOrDefault(BroadcastRunMode.NETWORK),
                automaticPreparation = row.optBoolean("automaticPreparation", true))
        }
    }
}

class OperatorSettings(context: Context) {
    private val preferences = context.getSharedPreferences("operator_options", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(runCatching {
        OperatorOptions.fromJson(JSONObject(preferences.getString("options", "{}").orEmpty()))
    }.getOrDefault(OperatorOptions()))
    val state = mutableState.asStateFlow()
    private val mutableRestoration = MutableStateFlow(0L)
    val restoration = mutableRestoration.asStateFlow()
    @Synchronized fun store(options: OperatorOptions) {
        if (options == mutableState.value) return
        preferences.edit().putString("options", options.toJson().toString()).apply()
        mutableState.value = options
    }
    @Synchronized fun setRunMode(mode: BroadcastRunMode) = store(mutableState.value.copy(runMode = mode))
    @Synchronized fun setAutomaticPreparation(enabled: Boolean) = store(mutableState.value.copy(automaticPreparation = enabled))
    @Synchronized fun restore(options: OperatorOptions) { store(options); mutableRestoration.value++ }
}
