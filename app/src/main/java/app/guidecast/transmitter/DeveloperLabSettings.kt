package app.guidecast.transmitter

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TranslationRegister { AUTO, FORMAL, CONVERSATIONAL }
enum class CloudReviewProvider { OPENAI, GOOGLE }

data class DeveloperLabOptions(
    val expressiveTtsEnabled: Boolean = false,
    val paraphraseEnabled: Boolean = false,
    val translationRegister: TranslationRegister = TranslationRegister.FORMAL,
    val cloudReviewEnabled: Boolean = false,
    val autoLearnEnabled: Boolean = false,
    val provider: CloudReviewProvider = CloudReviewProvider.OPENAI,
    val modelId: String = defaultReviewModel(CloudReviewProvider.OPENAI),
    val hasApiKey: Boolean = false,
    val teacherLearningEnabled: Boolean = false,
    val authorizationRevision: Long = 0,
)

fun defaultReviewModel(provider: CloudReviewProvider): String = when (provider) {
    CloudReviewProvider.OPENAI -> "gpt-5.4-mini"
    CloudReviewProvider.GOOGLE -> "gemini-2.5-flash-lite"
}

/** No credential enters observable UI state or portable settings. */
class DeveloperLabSettings internal constructor(
    private val preferences: DeveloperLabPreferenceStore,
    private val vault: DeveloperApiKeyVault,
) {
    constructor(context: Context) : this(AndroidDeveloperLabPreferences(context.applicationContext),
        AndroidDeveloperApiKeyVault(context.applicationContext))

    private val lock = Any()
    private val mutableState = MutableStateFlow(preferences.read().let {
        it.copy(hasApiKey = vault.read(it.provider) != null)
    })
    val state = mutableState.asStateFlow()

    fun setExpressiveTtsEnabled(value: Boolean) = update { it.copy(expressiveTtsEnabled = value) }
    internal fun invalidateAuthorization() = update { it.copy(authorizationRevision = it.authorizationRevision + 1) }
    fun setParaphraseEnabled(value: Boolean) = update { it.copy(paraphraseEnabled = value) }
    fun setTranslationRegister(value: TranslationRegister) = update { it.copy(translationRegister = value) }
    fun setCloudReviewEnabled(value: Boolean) = update { it.copy(cloudReviewEnabled = value, authorizationRevision = it.authorizationRevision + 1) }
    fun setAutoLearnEnabled(value: Boolean) = update { it.copy(autoLearnEnabled = value, teacherLearningEnabled = it.teacherLearningEnabled && value,
        authorizationRevision = it.authorizationRevision + 1) }
    fun setTeacherLearningEnabled(value: Boolean) = update { it.copy(teacherLearningEnabled = value, autoLearnEnabled = value,
        authorizationRevision = it.authorizationRevision + 1) }
    fun setProvider(value: CloudReviewProvider) = update {
        if (it.provider == value) it else it.copy(provider = value, modelId = defaultReviewModel(value),
            cloudReviewEnabled = false, hasApiKey = vault.read(value) != null, authorizationRevision = it.authorizationRevision + 1)
    }
    fun setModelId(value: String): Boolean {
        if (!validReviewModel(value)) return false
        update { if (it.modelId == value) it else it.copy(modelId = value, cloudReviewEnabled = false,
            authorizationRevision = it.authorizationRevision + 1) }
        return true
    }
    fun setApiKey(value: String): Boolean = synchronized(lock) {
        if (value.length !in 20..512 || value.any { it.code !in 33..126 }) return false
        if (!vault.write(mutableState.value.provider, value)) return false
        mutableState.value = mutableState.value.copy(hasApiKey = true, authorizationRevision = mutableState.value.authorizationRevision + 1)
        true
    }
    fun clearApiKey(): Boolean = synchronized(lock) {
        val removed = vault.remove(mutableState.value.provider)
        update { it.copy(hasApiKey = if (removed) false else it.hasApiKey, cloudReviewEnabled = false,
            authorizationRevision = it.authorizationRevision + 1) }
        removed
    }
    internal fun apiKey(provider: CloudReviewProvider): String? = vault.read(provider)

    /** Import is configuration, never renewed permission to send speech-derived text externally. */
    fun portableOptions() = state.value.copy(hasApiKey = false, cloudReviewEnabled = false, autoLearnEnabled = false,
        teacherLearningEnabled = false, authorizationRevision = 0)
    fun importOptions(value: DeveloperLabOptions) = update {
        value.copy(modelId = value.modelId.takeIf(::validReviewModel) ?: defaultReviewModel(value.provider),
            cloudReviewEnabled = false, autoLearnEnabled = false, teacherLearningEnabled = false,
            authorizationRevision = it.authorizationRevision + 1, hasApiKey = vault.read(value.provider) != null)
    }
    private fun update(transform: (DeveloperLabOptions) -> DeveloperLabOptions) = synchronized(lock) {
        val next = transform(mutableState.value)
        preferences.write(next.copy(hasApiKey = false))
        mutableState.value = next
    }
}

internal fun validReviewModel(value: String) = value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}"))
internal interface DeveloperLabPreferenceStore {
    fun read(): DeveloperLabOptions
    fun write(options: DeveloperLabOptions)
}
internal interface DeveloperApiKeyVault {
    fun read(provider: CloudReviewProvider): String?
    fun write(provider: CloudReviewProvider, value: String): Boolean
    fun remove(provider: CloudReviewProvider): Boolean
}

private class AndroidDeveloperLabPreferences(context: Context) : DeveloperLabPreferenceStore {
    private val prefs = context.getSharedPreferences("developer_lab", Context.MODE_PRIVATE)
    override fun read(): DeveloperLabOptions {
        val provider = runCatching { CloudReviewProvider.valueOf(prefs.getString("provider", null).orEmpty()) }
            .getOrDefault(CloudReviewProvider.OPENAI)
        return DeveloperLabOptions(
            expressiveTtsEnabled = prefs.getBoolean("expression", false),
            paraphraseEnabled = prefs.getBoolean("paraphrase", false),
            translationRegister = runCatching { TranslationRegister.valueOf(prefs.getString("register", null).orEmpty()) }
                .getOrDefault(TranslationRegister.FORMAL),
            cloudReviewEnabled = prefs.getBoolean("cloud_review", false),
            autoLearnEnabled = prefs.getBoolean("auto_learn", false), provider = provider,
            teacherLearningEnabled = prefs.getBoolean("selective_teacher", prefs.getBoolean("auto_learn", false)),
            modelId = prefs.getString("model", null)?.takeIf(::validReviewModel) ?: defaultReviewModel(provider),
        )
    }
    override fun write(options: DeveloperLabOptions) {
        prefs.edit().putBoolean("expression", options.expressiveTtsEnabled)
            .putBoolean("paraphrase", options.paraphraseEnabled).putString("register", options.translationRegister.name)
            .putBoolean("cloud_review", options.cloudReviewEnabled).putBoolean("auto_learn", options.autoLearnEnabled)
            .putBoolean("selective_teacher", options.teacherLearningEnabled)
            .putString("provider", options.provider.name).putString("model", options.modelId).apply()
    }
}

/** AES-GCM ciphertext only; the non-exportable key is generated inside Android Keystore. */
private class AndroidDeveloperApiKeyVault(context: Context) : DeveloperApiKeyVault {
    private val prefs = context.getSharedPreferences("developer_lab_encrypted_credentials", Context.MODE_PRIVATE)
    override fun read(provider: CloudReviewProvider): String? = runCatching {
        val encoded = prefs.getString(provider.name, null) ?: return null
        val data = Base64.decode(encoded, Base64.NO_WRAP)
        require(data.size in 29..1_024)
        val key = keyStore().getKey(KEY_ALIAS, null) as? SecretKey ?: return null
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data.copyOfRange(0, 12)))
            updateAAD(provider.name.toByteArray(Charsets.UTF_8))
        }.doFinal(data.copyOfRange(12, data.size)).toString(Charsets.UTF_8)
    }.getOrNull()

    override fun write(provider: CloudReviewProvider, value: String): Boolean = runCatching {
        val store = keyStore()
        val key = store.getKey(KEY_ALIAS, null) as? SecretKey ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key)
            updateAAD(provider.name.toByteArray(Charsets.UTF_8))
        }
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(provider.name, Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)).commit()
    }.getOrDefault(false)
    override fun remove(provider: CloudReviewProvider): Boolean = runCatching {
        prefs.edit().remove(provider.name).commit()
    }.getOrDefault(false)
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private companion object { const val KEY_ALIAS = "mcasttalk.developer.credentials.v1" }
}
