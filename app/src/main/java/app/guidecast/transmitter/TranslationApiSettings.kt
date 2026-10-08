package app.guidecast.transmitter

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.guidecast.core.translation.TranslationStyle
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

enum class TranslationApiProvider(val label: String) { LOCAL("기기 내 번역"), OPENAI("OpenAI 텍스트"), OPENAI_REALTIME("OpenAI Realtime"), GEMINI("Gemini 텍스트"), GEMINI_LIVE("Gemini Live 음성"), COMPATIBLE("OpenAI 호환 API") }
enum class OnlineInterpretationMode(val label: String) { CONTINUOUS("연속통역"), PROFESSIONAL("전문통역") }
enum class TranslationApiProtocol { RESPONSES, CHAT_COMPLETIONS }

data class TranslationApiOptions(
    val provider: TranslationApiProvider = TranslationApiProvider.LOCAL,
    val model: String = "gpt-5.4-mini", val baseUrl: String = "https://api.openai.com/v1",
    val protocol: TranslationApiProtocol = TranslationApiProtocol.RESPONSES,
    val tone: TranslationStyle = TranslationStyle.CONVERSATIONAL,
    val interpretationMode: OnlineInterpretationMode = OnlineInterpretationMode.CONTINUOUS,
    val domainPrompt: String = "",
    val interpreterInstructions: String = "",
    val allowOnline: Boolean = false, val localFallback: Boolean = false,
    val hasKey: Boolean = false, val revision: Long = 0,
    val alwaysLearnOnline: Boolean = false,
    val allowDomainReferences: Boolean = false,
    val allowLiveAudio: Boolean = false,
    val budgetLimitUsd: String = "1.00",
    /** Existing saved Realtime choices retain the text route until the user selects audio. */
    val realtimeAudio: Boolean = false,
    val liveVoice: RelayVoiceGender = RelayVoiceGender.AUTO,
) {
    internal val usesNativeLiveAudio: Boolean get() = provider == TranslationApiProvider.GEMINI_LIVE ||
        (provider == TranslationApiProvider.OPENAI_REALTIME && realtimeAudio)
    internal fun portable() = JSONObject().put("provider", provider.name).put("model", model).put("baseUrl", baseUrl)
        .put("protocol", protocol.name).put("tone", tone.name).put("interpretationMode", interpretationMode.name).put("domainPrompt", domainPrompt)
        .put("interpreterInstructions", interpreterInstructions).put("localFallback", localFallback).put("realtimeAudio", realtimeAudio)
        .put("liveVoice", liveVoice.name)
    internal val credentialScope: String get() = when {
        provider in setOf(TranslationApiProvider.GEMINI, TranslationApiProvider.GEMINI_LIVE) -> "GOOGLE:" + baseUrl
        provider in setOf(TranslationApiProvider.OPENAI, TranslationApiProvider.OPENAI_REALTIME) && baseUrl == "https://api.openai.com/v1" -> "OPENAI:" + baseUrl
        else -> provider.name + ":" + baseUrl
    }
    internal val readableCredentialScopes: List<String> get() = when {
        provider in setOf(TranslationApiProvider.GEMINI, TranslationApiProvider.GEMINI_LIVE) ->
            listOf(credentialScope, provider.name + ":" + baseUrl,
                (if (provider == TranslationApiProvider.GEMINI) "GEMINI_LIVE:" else "GEMINI:") + baseUrl).distinct()
        provider in setOf(TranslationApiProvider.OPENAI, TranslationApiProvider.OPENAI_REALTIME) && baseUrl == "https://api.openai.com/v1" ->
            listOf(credentialScope, "OPENAI_REALTIME:" + baseUrl)
        else -> listOf(credentialScope)
    }
    internal val endpoint: String get() = when (provider) {
        TranslationApiProvider.GEMINI -> "$baseUrl/models/$model:generateContent"
        else -> "$baseUrl/" + if (protocol == TranslationApiProtocol.RESPONSES) "responses" else "chat/completions"
    }
    internal companion object {
        fun fromPortable(row: JSONObject): TranslationApiOptions {
            val result = TranslationApiOptions(
                provider = TranslationApiProvider.valueOf(row.optString("provider", "LOCAL")),
                model = row.optString("model", "gpt-5.4-mini"), baseUrl = row.optString("baseUrl", "https://api.openai.com/v1"),
                protocol = TranslationApiProtocol.valueOf(row.optString("protocol", "RESPONSES")),
                interpretationMode = OnlineInterpretationMode.valueOf(row.optString("interpretationMode", "CONTINUOUS")),
                domainPrompt = row.optString("domainPrompt", ""),
                interpreterInstructions = row.optString("interpreterInstructions", ""),
                tone = TranslationStyle.valueOf(row.optString("tone", "CONVERSATIONAL")), localFallback = false,
                realtimeAudio = row.optBoolean("realtimeAudio", false),
                liveVoice = RelayVoiceGender.valueOf(row.optString("liveVoice", "AUTO")))
            require(validTranslationApiOptions(result))
            return result
        }
    }
}

internal fun validTranslationApiOptions(options: TranslationApiOptions): Boolean = runCatching {
    require(validInterpreterInstructions(options.interpreterInstructions))
    require(validInterpreterDomain(options.domainPrompt))
    require(options.model.matches(Regex("[A-Za-z0-9][A-Za-z0-9._/:-]{0,119}")) && ".." !in options.model)
    val uri = URI(options.baseUrl)
    require(options.baseUrl.length <= 300 && uri.scheme == "https" && !uri.host.isNullOrBlank() &&
        uri.userInfo == null && uri.query == null && uri.fragment == null && (uri.port == -1 || uri.port in 1..65535) &&
        !options.baseUrl.endsWith('/') && ".." !in uri.path && '%' !in uri.rawPath &&
        uri.path.matches(Regex("(?:/[A-Za-z0-9._-]+)*")))
    when (options.provider) {
        TranslationApiProvider.OPENAI_REALTIME -> require(options.baseUrl == "https://api.openai.com/v1" && options.model in OPENAI_REALTIME_MODELS && options.protocol == TranslationApiProtocol.RESPONSES)
        TranslationApiProvider.OPENAI -> require(options.baseUrl == "https://api.openai.com/v1")
        TranslationApiProvider.GEMINI_LIVE -> require(options.baseUrl == "https://generativelanguage.googleapis.com/v1beta" && options.model in setOf("gemini-3.5-live-translate-preview", "gemini-3.8-live"))
        TranslationApiProvider.GEMINI -> require(options.baseUrl == "https://generativelanguage.googleapis.com/v1beta" && validReviewModel(options.model))
        else -> Unit
    }
    true
}.getOrDefault(false)

/** Explicit destination-scoped consent and credentials. Portable files contain no permission or keys. */
class TranslationApiSettings(context: Context,
    preferenceNamespace: String = DEFAULT_TRANSLATION_API_PREFERENCES,
    seedOptions: TranslationApiOptions? = null,
) {
    private val namespace = preferenceNamespace.also { require(it.matches(Regex("[A-Za-z0-9_]{1,80}"))) }
    private val applicationScope = context.applicationContext?.packageName ?: context.packageName
    private val preferences = context.getSharedPreferences(namespace, Context.MODE_PRIVATE).also { prefs ->
        if (!prefs.contains("options") && seedOptions != null) {
            require(validTranslationApiOptions(seedOptions))
            prefs.edit().putString("options", seedOptions.portable().toString())
                .putBoolean("allow_online", false).putBoolean("allow_live_audio", false)
                .putBoolean("allow_domain_references", false)
                .putString("budget_limit_usd", seedOptions.budgetLimitUsd).apply()
        }
    }
    private val vault = TranslationCredentialVault(context)
    private fun storageScope(scope: String): String = translationCredentialStorageScope(namespace, scope)
    private fun sessionSlot(scope: String): String = applicationScope + ":" + storageScope(scope)
    private fun resolvedKey(options: TranslationApiOptions): String? = options.readableCredentialScopes
        .firstNotNullOfOrNull { sharedSessionKeys[sessionSlot(it)] ?: vault.read(storageScope(it)) }
    private val mutableState = MutableStateFlow(runCatching {
        TranslationApiOptions.fromPortable(JSONObject(preferences.getString("options", "{}").orEmpty()))
            .let { it.copy(allowOnline = preferences.getBoolean("allow_online", false) && resolvedKey(it) != null, hasKey = resolvedKey(it) != null,
                alwaysLearnOnline = preferences.getBoolean("always_learn_online", false),
                allowLiveAudio = preferences.getBoolean("allow_live_audio", false) && resolvedKey(it) != null,
                allowDomainReferences = preferences.getBoolean("allow_domain_references", false),
                budgetLimitUsd = preferences.getString("budget_limit_usd", "1.00") ?: "1.00") }
    }.getOrDefault(TranslationApiOptions()))
    val state = mutableState.asStateFlow()
    private val mutableSessionLearning = MutableStateFlow(false)
    val sessionLearning = mutableSessionLearning.asStateFlow()
    private val mutableLearningOnline = MutableStateFlow<TranslationApiOptions?>(null)
    val learningOnline = mutableLearningOnline.asStateFlow()
    private var learningGeneration = 0L
    init { synchronized(credentialObservers) { credentialObservers[this] = Unit } }
    internal fun preparedLearningProvider(): TranslationApiOptions? = runCatching {
        TranslationApiOptions.fromPortable(JSONObject(preferences.getString("last_online_options", null) ?: return null))
            .let { it.copy(hasKey = resolvedKey(it) != null, budgetLimitUsd = state.value.budgetLimitUsd) }
    }.getOrNull()
    /** Restore choices and scoped credentials, never a previous transmission permission. */
    @Synchronized internal fun restoreOnlineSelection(): Boolean =
        configure(preparedLearningProvider() ?: onlineServiceChoice(state.value, true))
    /** Streaming restores its own sentence profile; learning keeps its existing provider lookup. */
    @Synchronized internal fun restoreTextOnlineSelection(): Boolean {
        fun load(name: String): TranslationApiOptions? = runCatching {
            preferences.getString(name, null)?.let { TranslationApiOptions.fromPortable(JSONObject(it)) }
        }.getOrNull()
        return configure(restoreStreamingTextApiProfile(state.value,
            savedText = load("last_text_options"), legacyOnline = load("last_online_options")))
    }
    @Synchronized fun endSessionLearning() { mutableSessionLearning.value = false; mutableLearningOnline.value = null }
    /** User confirmation is session-only; saved online opt-in can never enable OFFLINE networking. */
    @Synchronized fun beginSessionLearning(agreeToTextAndCost: Boolean, allowReferences: Boolean): Boolean {
        endSessionLearning()
        if (!agreeToTextAndCost) return false
        if (state.value.provider == TranslationApiProvider.LOCAL) {
            val candidate = preparedLearningProvider()?.takeIf { it.hasKey } ?: return false
            if (candidate.usesNativeLiveAudio) return false
            mutableLearningOnline.value = candidate.copy(allowOnline = true, allowDomainReferences = allowReferences,
                revision = ++learningGeneration)
        } else {
            if (!authorized(state.value) || state.value.usesNativeLiveAudio) return false
            store(state.value.copy(allowDomainReferences = allowReferences, revision = state.value.revision + 1))
        }
        mutableSessionLearning.value = true
        return true
    }
    internal fun learningAuthorized(options: TranslationApiOptions): Boolean = state.value.provider == TranslationApiProvider.LOCAL &&
        mutableSessionLearning.value && mutableLearningOnline.value == options && options.allowOnline && options.hasKey
    /** Consent is distinct from account billing and never depends on estimated usage. */
    @Synchronized fun consentToSelectedService() {
        if (!state.value.hasKey) return
        store(state.value.copy(allowOnline = true,
            allowLiveAudio = state.value.usesNativeLiveAudio,
            revision = state.value.revision + 1))
    }
    @Synchronized fun revokeSelectedService() {
        store(state.value.copy(allowOnline = false, allowLiveAudio = false, allowDomainReferences = false,
            revision = state.value.revision + 1))
    }
    internal fun usesTemporaryKey(): Boolean = state.value.readableCredentialScopes.any { sharedSessionKeys.containsKey(sessionSlot(it)) }
    @Synchronized fun setLiveAudioConsent(allowed: Boolean) {
        store(state.value.copy(allowLiveAudio = allowed && state.value.hasKey && state.value.usesNativeLiveAudio, revision = state.value.revision + 1))
    }
    @Synchronized fun setBudgetLimit(value: String): Boolean {
        val next = state.value.copy(budgetLimitUsd = value.trim(), revision = state.value.revision + 1)
        if (!validTranslationApiOptions(next)) return false
        store(next); return true
    }
    @Synchronized fun setAllowDomainReferences(enabled: Boolean) {
        store(state.value.copy(allowDomainReferences = enabled, revision = state.value.revision + 1))
    }
    @Synchronized fun configure(options: TranslationApiOptions): Boolean {
        if (!validTranslationApiOptions(options)) return false
        if (options.usesNativeLiveAudio) {
            mutableSessionLearning.value = false
            mutableLearningOnline.value = null
        }
        store(options.copy(allowLiveAudio = false, allowOnline = false, alwaysLearnOnline = state.value.alwaysLearnOnline, allowDomainReferences = false, localFallback = false, hasKey = resolvedKey(options) != null, revision = state.value.revision + 1))
        return true
    }
    /** Retain consent for the same data categories; newly reachable stored context requires consent. */
    @Synchronized internal fun selectModel(model: String): Boolean {
        val current = state.value
        val choice = serviceModelChoices(current).firstOrNull { it.id == model } ?: return false
        if (current.model == model) return true
        val next = current.copy(model = choice.id, interpretationMode = choice.interpretationMode,
            revision = current.revision + 1)
        val selected = if (nativeContextTransmissionExpands(current, next)) next.copy(
            allowOnline = false, allowLiveAudio = false, allowDomainReferences = false,
        ) else next
        if (!validTranslationApiOptions(selected)) return false
        store(selected)
        return true
    }
    @Synchronized fun setDomainPrompt(prompt: String): Boolean {
        val next = state.value.copy(domainPrompt = prompt.trim(), revision = state.value.revision + 1)
        if (!validTranslationApiOptions(next)) return false
        // Same provider/data category; keep consent, but invalidate any in-flight snapshot.
        store(next)
        return true
    }
    @Synchronized fun setInterpreterInstructions(instructions: String): Boolean {
        val next = state.value.copy(interpreterInstructions = instructions.trim(), revision = state.value.revision + 1)
        if (!validTranslationApiOptions(next)) return false
        store(next)
        return true
    }
    @Synchronized internal fun setProfessionalRelayInstructions(domain: String, instructions: String): Boolean {
        val current = state.value
        if (!current.usesNativeLiveAudio || !serviceExperience(current).supportsDomainInstructions) return false
        if (containsNativeContextCredentialLikeText(domain)) return false
        val next = current.copy(domainPrompt = domain.trim(), interpreterInstructions = instructions.trim(),
            interpretationMode = OnlineInterpretationMode.PROFESSIONAL, revision = current.revision + 1)
        if (!validTranslationApiOptions(next)) return false
        store(next)
        return true
    }
    @Synchronized fun setAlwaysLearnOnline(enabled: Boolean) { store(state.value.copy(alwaysLearnOnline = enabled, revision = state.value.revision + 1)) }
    @Synchronized fun setTone(tone: TranslationStyle) { store(state.value.copy(tone = tone, revision = state.value.revision + 1)) }
    @Synchronized fun setAllowOnline(allowed: Boolean) { store(state.value.copy(allowOnline = allowed && state.value.hasKey, revision = state.value.revision + 1)) }
    @Deprecated("Single mode operation never falls back across the network boundary")
    @Synchronized fun setFallback(enabled: Boolean) { store(state.value.copy(localFallback = false, revision = state.value.revision + 1)) }
    fun useSessionKey(key: String): Boolean = replaceKey(key, temporary = true)
    fun saveKey(key: String): Boolean = replaceKey(key, temporary = false)
    private fun replaceKey(key: String, temporary: Boolean): Boolean {
        if (key.length !in 20..512 || key.any { it.code !in 33..126 }) return false
        val changed = synchronized(this) {
            val current = state.value
            val scopes = current.readableCredentialScopes.map(::storageScope)
            synchronized(credentialMutationLock) {
                val saved = if (temporary) vault.removeAll(scopes) else vault.write(storageScope(current.credentialScope),
                    key, scopes.filterNot { it == storageScope(current.credentialScope) })
                if (!saved) return false
                current.readableCredentialScopes.forEach { sharedSessionKeys.remove(sessionSlot(it)) }
                if (temporary) sharedSessionKeys[sessionSlot(current.credentialScope)] = key
            }
            store(current.copy(hasKey = true, allowLiveAudio = false, allowOnline = false,
                revision = current.revision + 1))
            scopes.toSet()
        }
        notifyCredentialChange(applicationScope, changed, this)
        return true
    }
    fun clearKey(): Boolean {
        var removed = false
        val changed = synchronized(this) {
            val current = state.value
            val scopes = current.readableCredentialScopes.map(::storageScope)
            synchronized(credentialMutationLock) {
                current.readableCredentialScopes.forEach { sharedSessionKeys.remove(sessionSlot(it)) }
                removed = vault.removeAll(scopes)
            }
            store(current.copy(hasKey = !removed && resolvedKey(current) != null,
                allowLiveAudio = false, allowOnline = false, revision = current.revision + 1))
            scopes.toSet()
        }
        notifyCredentialChange(applicationScope, changed, this)
        return removed
    }
    @Synchronized private fun credentialsChanged(scopes: Set<String>) {
        val current = state.value
        if (current.readableCredentialScopes.none { storageScope(it) in scopes }) return
        store(current.copy(hasKey = resolvedKey(current) != null, allowOnline = false, allowLiveAudio = false,
            revision = current.revision + 1))
    }
    internal fun key(options: TranslationApiOptions): String? = if (authorized(options) || learningAuthorized(options)) resolvedKey(options) else null
    internal fun authorized(options: TranslationApiOptions) = options.provider != TranslationApiProvider.LOCAL &&
        options.allowOnline && options.hasKey && state.value == options
    private fun store(value: TranslationApiOptions) {
        endSessionLearning()
        if (value.provider != TranslationApiProvider.LOCAL) preferences.edit().putString("last_online_options", value.portable().toString()).apply()
        if (isOnlineTextApiProfile(value)) preferences.edit().putString("last_text_options", value.portable().toString()).apply()
        preferences.edit().putString("options", value.portable().toString()).putBoolean("allow_online", value.allowOnline).putBoolean("always_learn_online", value.alwaysLearnOnline)
            .putBoolean("allow_live_audio", value.allowLiveAudio).putBoolean("allow_domain_references", value.allowDomainReferences).putString("budget_limit_usd", value.budgetLimitUsd).apply()
        mutableState.value = value
    }
    private companion object {
        val sharedSessionKeys = java.util.concurrent.ConcurrentHashMap<String, String>()
        val credentialMutationLock = Any()
        val credentialObservers = java.util.WeakHashMap<TranslationApiSettings, Unit>()
        fun notifyCredentialChange(application: String, scopes: Set<String>, sender: TranslationApiSettings) {
            val observers = synchronized(credentialObservers) { credentialObservers.keys.toList() }
            observers.filter { it !== sender && it.applicationScope == application }.forEach { it.credentialsChanged(scopes) }
        }
    }
}

internal const val DEFAULT_TRANSLATION_API_PREFERENCES = "translation_api"

/** Official vendor accounts are shared; custom endpoints belong to their setting profile. */
internal fun translationCredentialStorageScope(namespace: String, scope: String): String {
    val official = scope in setOf(
        "GOOGLE:https://generativelanguage.googleapis.com/v1beta",
        "GEMINI:https://generativelanguage.googleapis.com/v1beta",
        "GEMINI_LIVE:https://generativelanguage.googleapis.com/v1beta",
        "OPENAI:https://api.openai.com/v1", "OPENAI_REALTIME:https://api.openai.com/v1",
    )
    return if (official || namespace == DEFAULT_TRANSLATION_API_PREFERENCES) scope else "$namespace:$scope"
}

internal class TranslationCredentialVault(context: Context) {
    private val preferences = context.getSharedPreferences("translation_api_credentials", Context.MODE_PRIVATE)
    private fun slot(scope: String) = MessageDigest.getInstance("SHA-256").digest(scope.toByteArray()).joinToString("") { "%02x".format(it) }
    fun read(scope: String): String? = runCatching {
        val encoded = preferences.getString(slot(scope), null) ?: return null
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size in 29..1_024)
        val key = keyStore().getKey(ALIAS, null) as? SecretKey ?: return null
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12))); updateAAD(scope.toByteArray())
        }.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }.getOrNull()
    fun write(scope: String, value: String, obsoleteScopes: List<String> = emptyList()): Boolean = runCatching {
        val key = keyStore().getKey(ALIAS, null) as? SecretKey ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key); updateAAD(scope.toByteArray()) }
        val bytes = cipher.doFinal(value.toByteArray())
        preferences.edit().apply { obsoleteScopes.forEach { remove(slot(it)) } }
            .putString(slot(scope), Base64.encodeToString(cipher.iv + bytes, Base64.NO_WRAP)).commit()
    }.getOrDefault(false)
    fun removeAll(scopes: List<String>) = preferences.edit().apply { scopes.forEach { remove(slot(it)) } }.commit()
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private companion object { const val ALIAS = "mcasttalk.translation.credentials.v1" }
}
