package app.guidecast.transmitter

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

internal enum class ServiceMenuProfile(val preferenceName: String) {
    NOTES("voice_notes"), FILES("files"),
}

internal data class ServiceMenuProfileOptions(
    val usesCommonDefaults: Boolean = true,
    val sourceTag: String = DEFAULT_SOURCE_LANGUAGE_TAG,
    val targetTags: Set<String> = setOf("en"),
    val automaticSource: Boolean = false,
    val localEngine: FileTranslationEngine = FileTranslationEngine.MLKIT,
)

/** Global defaults describe the next explicitly reset menu, never a running session. */
internal class ServiceDefaults(app: GuideCastApplication) {
    private val preferences = app.getSharedPreferences("service_defaults", android.content.Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(runCatching {
        val row = JSONObject(preferences.getString("options", "{}").orEmpty())
        ServiceMenuProfileOptions(sourceTag = row.optString("source", DEFAULT_SOURCE_LANGUAGE_TAG),
            targetTags = row.optJSONArray("targets")?.let { a ->
                require(a.length() <= 5); (0 until a.length()).map { a.getString(it) }.toSet()
            } ?: OperatorOptions().targetLanguageTags.take(5).toSet(),
            localEngine = FileTranslationEngine.valueOf(row.optString("localEngine", "GEMMA")))
            .also(::validateServiceMenuProfile)
    }.getOrDefault(ServiceMenuProfileOptions(targetTags = OperatorOptions().targetLanguageTags.take(5).toSet(),
        localEngine = FileTranslationEngine.GEMMA)))
    val state = mutableState.asStateFlow()
    @Synchronized fun setLanguages(sourceTag: String, targetTags: Set<String>): Boolean =
        save(state.value.copy(sourceTag = sourceTag, targetTags = targetTags))
    @Synchronized fun setLocalEngine(engine: FileTranslationEngine): Boolean = save(state.value.copy(localEngine = engine))
    private fun save(value: ServiceMenuProfileOptions): Boolean {
        if (runCatching { validateServiceMenuProfile(value) }.isFailure) return false
        preferences.edit().putString("options", JSONObject().put("source", value.sourceTag)
            .put("targets", JSONArray(value.targetTags.toList())).put("localEngine", value.localEngine.name).toString()).apply()
        mutableState.value = value
        return true
    }
}

/** Copying common choices never copies a transmission permission or session identity. */
internal fun serviceMenuTextOptions(common: TranslationApiOptions): TranslationApiOptions {
    val text = when (common.provider) {
        TranslationApiProvider.GEMINI_LIVE -> geminiSharedInputChoice(common)
        TranslationApiProvider.OPENAI_REALTIME -> openAiTextChoice(common)
        else -> common
    }
    return text.copy(allowOnline = false, allowLiveAudio = false, allowDomainReferences = false,
        alwaysLearnOnline = false, hasKey = false, localFallback = false, realtimeAudio = false, revision = 0)
}

internal data class ServiceMenuRefreshPlan(
    val apiChanged: Boolean,
    val apiOptions: TranslationApiOptions,
    val metadata: ServiceMenuProfileOptions,
)

/** Identity follows portable choices; permissions, key presence and revisions belong to the menu. */
private fun menuApiPortableChoices(options: TranslationApiOptions): TranslationApiOptions = options.copy(
    allowOnline = false, allowLiveAudio = false, allowDomainReferences = false, hasKey = false,
    alwaysLearnOnline = false, revision = 0, budgetLimitUsd = "1.00",
)

internal fun inheritedMenuRefreshPlan(current: ServiceMenuProfileOptions, selectedApi: TranslationApiOptions,
    commonApi: TranslationApiOptions, defaults: ServiceMenuProfileOptions): ServiceMenuRefreshPlan? {
    if (!current.usesCommonDefaults) return null
    val api = serviceMenuTextOptions(commonApi)
    return ServiceMenuRefreshPlan(menuApiPortableChoices(selectedApi) != menuApiPortableChoices(api),
        api, defaults.copy(usesCommonDefaults = true))
}

/** Menu settings are snapshots; changing another menu never replaces an active request. */
internal class ServiceMenuProfiles(private val app: GuideCastApplication,
    private val common: TranslationApiSettings,
) {
    private val metadata = app.getSharedPreferences("service_menu_profiles", android.content.Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(ServiceMenuProfile.entries.associateWith { profile ->
        val defaults = app.serviceDefaults.state.value
        runCatching {
            metadata.getString(profile.name, null)?.let { serialized ->
                val row = JSONObject(serialized)
                ServiceMenuProfileOptions(row.optBoolean("common", true), row.getString("source"),
                    row.getJSONArray("targets").let { a ->
                        require(a.length() <= 5)
                        (0 until a.length()).map { a.getString(it) }.toSet()
                    }, row.optBoolean("automaticSource", false),
                    FileTranslationEngine.valueOf(row.optString("localEngine", "MLKIT"))).also(::validateServiceMenuProfile)
            }
        }.getOrNull() ?: defaults.copy(usesCommonDefaults = true)
    })
    val state = mutableState.asStateFlow()
    private val settingsByMenu = mutableMapOf<ServiceMenuProfile, TranslationApiSettings>()
    private val servicesByMenu = mutableMapOf<ServiceMenuProfile, TranslationApiService>()
    init {
        ServiceMenuProfile.entries.forEach { profile ->
            if (!metadata.contains(profile.name)) save(profile, state.value.getValue(profile))
            settings(profile)
        }
    }

    @Synchronized fun settings(profile: ServiceMenuProfile): TranslationApiSettings = settingsByMenu.getOrPut(profile) {
        TranslationApiSettings(app, "translation_api_${profile.preferenceName}", serviceMenuTextOptions(common.state.value))
    }
    @Synchronized fun service(profile: ServiceMenuProfile): TranslationApiService = servicesByMenu.getOrPut(profile) {
        TranslationApiService(settings(profile), shadowAllowed = { target ->
            app.learningResourcesAvailable() && app.translationProvider.hasActivePreparedWorker(target)
        }, comparisonResources = app::learningResourcesAvailable, automaticExamples = app.automaticTranslationExamples,
            exampleDomain = app.domainCorpus::automaticExampleDomain, comparisonLifetime = app::comparisonWorkLifetime)
    }

    /** The caller exposes this explicit reset only after stopping that menu's work. */
    @Synchronized fun useCommonDefaults(profile: ServiceMenuProfile): Boolean {
        if (!settings(profile).configure(serviceMenuTextOptions(common.state.value))) return false
        val defaults = app.serviceDefaults.state.value
        save(profile, defaults.copy(usesCommonDefaults = true))
        return true
    }
    /** Invoke only on idle menu entry. An unchanged choice keeps permission and revision intact. */
    @Synchronized fun refreshInheritedDefaults(profile: ServiceMenuProfile): Boolean {
        val busy = when (profile) {
            ServiceMenuProfile.NOTES -> app.localVoiceNoteWorkActive.value || app.menuBroadcast.owns(MenuBroadcastOrigin.NOTES)
            ServiceMenuProfile.FILES -> app.localFileWorkActive.value || app.menuBroadcast.owns(MenuBroadcastOrigin.FILES)
        }
        if (busy) return false
        val current = state.value.getValue(profile)
        val plan = inheritedMenuRefreshPlan(current, settings(profile).state.value,
            common.state.value, app.serviceDefaults.state.value) ?: return true
        if (plan.apiChanged && !settings(profile).configure(plan.apiOptions)) return false
        if (current != plan.metadata) save(profile, plan.metadata)
        return true
    }
    @Synchronized fun useMenuSettings(profile: ServiceMenuProfile) {
        save(profile, state.value.getValue(profile).copy(usesCommonDefaults = false))
    }
    @Synchronized fun setLanguages(profile: ServiceMenuProfile, sourceTag: String?, targetTags: Set<String>): Boolean {
        val previous = state.value.getValue(profile)
        val next = state.value.getValue(profile).copy(usesCommonDefaults = false,
            sourceTag = sourceTag ?: previous.sourceTag, targetTags = targetTags, automaticSource = sourceTag == null)
        if (runCatching { validateServiceMenuProfile(next) }.isFailure) return false
        save(profile, next)
        return true
    }
    @Synchronized fun setTargetLanguages(profile: ServiceMenuProfile, targetTags: Set<String>): Boolean {
        val next = runCatching { menuProfileWithTargetLanguages(state.value.getValue(profile), targetTags) }
            .getOrNull() ?: return false
        save(profile, next)
        return true
    }
    @Synchronized fun setLocalEngine(profile: ServiceMenuProfile, engine: FileTranslationEngine): Boolean {
        val next = state.value.getValue(profile).copy(usesCommonDefaults = false, localEngine = engine)
        if (runCatching { validateServiceMenuProfile(next) }.isFailure) return false
        save(profile, next)
        return true
    }
    @Synchronized fun portable(profile: ServiceMenuProfile): JSONObject {
        val options = state.value.getValue(profile)
        return JSONObject().put("version", 1).put("menu", profile.name)
            .put("source", options.sourceTag).put("automaticSource", options.automaticSource)
            .put("targets", JSONArray(options.targetTags.toList()))
            .put("localEngine", options.localEngine.name)
            .put("options", settings(profile).state.value.portable())
    }
    /** Imported choices are menu overrides and require fresh permission before networking. */
    @Synchronized fun restorePortable(profile: ServiceMenuProfile, row: JSONObject): Boolean = runCatching {
        require(row.getInt("version") == 1 && row.getString("menu") == profile.name)
        val targets = row.getJSONArray("targets")
        require(targets.length() <= 5)
        val restored = ServiceMenuProfileOptions(false, row.getString("source"),
            (0 until targets.length()).map { targets.getString(it) }.toSet(), row.optBoolean("automaticSource", false),
            FileTranslationEngine.valueOf(row.optString("localEngine", "MLKIT")))
        validateServiceMenuProfile(restored)
        val api = serviceMenuTextOptions(TranslationApiOptions.fromPortable(row.getJSONObject("options")))
        if (!settings(profile).configure(api)) return false
        save(profile, restored)
        true
    }.getOrDefault(false)
    private fun save(profile: ServiceMenuProfile, value: ServiceMenuProfileOptions) {
        validateServiceMenuProfile(value)
        metadata.edit().putString(profile.name, JSONObject().put("common", value.usesCommonDefaults)
            .put("source", value.sourceTag).put("targets", JSONArray(value.targetTags.toList()))
            .put("automaticSource", value.automaticSource).put("localEngine", value.localEngine.name).toString()).apply()
        mutableState.update { it + (profile to value) }
    }
}

internal fun validateServiceMenuProfile(value: ServiceMenuProfileOptions) {
    val pattern = Regex("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8}){0,3}")
    require(pattern.matches(value.sourceTag) && value.targetTags.size <= 5 && value.targetTags.all { pattern.matches(it) })
    require(value.localEngine != FileTranslationEngine.API)
}

/** Editing output languages preserves the independently selected source mode and local engine. */
internal fun menuProfileWithTargetLanguages(current: ServiceMenuProfileOptions, targetTags: Set<String>): ServiceMenuProfileOptions =
    current.copy(usesCommonDefaults = false, targetTags = targetTags.toSet()).also(::validateServiceMenuProfile)

/** A base-language default may select its offered locale; a specified unsupported region is not relabeled. */
internal fun supportedMenuLanguageTag(tag: String, supported: Set<String>): String? =
    supported.firstOrNull { it.equals(tag, true) } ?: if ('-' in tag) null
    else supported.firstOrNull { it.substringBefore('-').equals(tag, true) }

/** Resolve the same supported, ordered choices for the settings screen and the next operation. */
internal fun effectiveMenuTargetTags(profile: ServiceMenuProfile, targets: Set<String>): Set<String> {
    val supported = if (profile == ServiceMenuProfile.NOTES) VOICE_NOTE_LANGUAGES.keys else FILE_LANGUAGE_OPTIONS.keys
    val maximum = if (profile == ServiceMenuProfile.NOTES) 1 else 4
    val resolved = targets.mapNotNull { supportedMenuLanguageTag(it, supported) }.distinct().take(maximum).toSet()
    return if (profile == ServiceMenuProfile.NOTES && resolved.isEmpty()) setOf("en-US") else resolved
}
