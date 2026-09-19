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

enum class TranslationApiProvider(val label: String) { LOCAL("기기 내 번역"), OPENAI("OpenAI"), GEMINI("Gemini"), COMPATIBLE("OpenAI 호환 API") }
enum class TranslationApiProtocol { RESPONSES, CHAT_COMPLETIONS }

data class TranslationApiOptions(
    val provider: TranslationApiProvider = TranslationApiProvider.LOCAL,
    val model: String = "gpt-5.4-mini", val baseUrl: String = "https://api.openai.com/v1",
    val protocol: TranslationApiProtocol = TranslationApiProtocol.RESPONSES,
    val tone: TranslationStyle = TranslationStyle.AUTO,
    val allowOnline: Boolean = false, val localFallback: Boolean = true,
    val hasKey: Boolean = false, val revision: Long = 0,
) {
    internal fun portable() = JSONObject().put("provider", provider.name).put("model", model).put("baseUrl", baseUrl)
        .put("protocol", protocol.name).put("tone", tone.name).put("localFallback", localFallback)
    internal val credentialScope: String get() = provider.name + ":" + baseUrl
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
                tone = TranslationStyle.valueOf(row.optString("tone", "AUTO")), localFallback = row.optBoolean("localFallback", true))
            require(validTranslationApiOptions(result))
            return result
        }
    }
}

internal fun validTranslationApiOptions(options: TranslationApiOptions): Boolean = runCatching {
    require(options.model.matches(Regex("[A-Za-z0-9][A-Za-z0-9._/:-]{0,119}")) && ".." !in options.model)
    val uri = URI(options.baseUrl)
    require(options.baseUrl.length <= 300 && uri.scheme == "https" && !uri.host.isNullOrBlank() &&
        uri.userInfo == null && uri.query == null && uri.fragment == null && (uri.port == -1 || uri.port in 1..65535) &&
        !options.baseUrl.endsWith('/') && ".." !in uri.path && '%' !in uri.rawPath &&
        uri.path.matches(Regex("(?:/[A-Za-z0-9._-]+)*")))
    when (options.provider) {
        TranslationApiProvider.OPENAI -> require(options.baseUrl == "https://api.openai.com/v1")
        TranslationApiProvider.GEMINI -> require(options.baseUrl == "https://generativelanguage.googleapis.com/v1beta" && validReviewModel(options.model))
        else -> Unit
    }
    true
}.getOrDefault(false)

/** Explicit destination-scoped consent and credentials. Portable files contain no permission or keys. */
class TranslationApiSettings(context: Context) {
    private val preferences = context.getSharedPreferences("translation_api", Context.MODE_PRIVATE)
    private val vault = TranslationCredentialVault(context)
    private val mutableState = MutableStateFlow(runCatching {
        TranslationApiOptions.fromPortable(JSONObject(preferences.getString("options", "{}").orEmpty()))
            .let { it.copy(allowOnline = preferences.getBoolean("allow_online", false), hasKey = vault.read(it.credentialScope) != null) }
    }.getOrDefault(TranslationApiOptions()))
    val state = mutableState.asStateFlow()
    @Synchronized fun configure(options: TranslationApiOptions): Boolean {
        if (!validTranslationApiOptions(options)) return false
        store(options.copy(allowOnline = false, hasKey = vault.read(options.credentialScope) != null, revision = state.value.revision + 1))
        return true
    }
    @Synchronized fun setTone(tone: TranslationStyle) { store(state.value.copy(tone = tone, revision = state.value.revision + 1)) }
    @Synchronized fun setAllowOnline(allowed: Boolean) { store(state.value.copy(allowOnline = allowed && state.value.hasKey, revision = state.value.revision + 1)) }
    @Synchronized fun setFallback(enabled: Boolean) { store(state.value.copy(localFallback = enabled, revision = state.value.revision + 1)) }
    @Synchronized fun saveKey(key: String): Boolean {
        if (key.length !in 20..512 || key.any { it.code !in 33..126 } || !vault.write(state.value.credentialScope, key)) return false
        store(state.value.copy(hasKey = true, allowOnline = false, revision = state.value.revision + 1)); return true
    }
    @Synchronized fun clearKey(): Boolean {
        val removed = vault.remove(state.value.credentialScope)
        store(state.value.copy(hasKey = if (removed) false else state.value.hasKey, allowOnline = false, revision = state.value.revision + 1))
        return removed
    }
    internal fun key(options: TranslationApiOptions): String? = if (authorized(options)) vault.read(options.credentialScope) else null
    internal fun authorized(options: TranslationApiOptions) = options.provider != TranslationApiProvider.LOCAL &&
        options.allowOnline && options.hasKey && state.value == options
    private fun store(value: TranslationApiOptions) {
        preferences.edit().putString("options", value.portable().toString()).putBoolean("allow_online", value.allowOnline).apply()
        mutableState.value = value
    }
}

private class TranslationCredentialVault(context: Context) {
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
    fun write(scope: String, value: String): Boolean = runCatching {
        val key = keyStore().getKey(ALIAS, null) as? SecretKey ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key); updateAAD(scope.toByteArray()) }
        val bytes = cipher.doFinal(value.toByteArray())
        preferences.edit().putString(slot(scope), Base64.encodeToString(cipher.iv + bytes, Base64.NO_WRAP)).commit()
    }.getOrDefault(false)
    fun remove(scope: String) = preferences.edit().remove(slot(scope)).commit()
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private companion object { const val ALIAS = "mcasttalk.translation.credentials.v1" }
}
