package app.guidecast.transmitter

import org.junit.Assert.*
import org.junit.Test

internal class FakeLabPreferences(var value: DeveloperLabOptions = DeveloperLabOptions()) : DeveloperLabPreferenceStore {
    override fun read() = value
    override fun write(options: DeveloperLabOptions) { value = options }
}
internal class FakeDeveloperVault : DeveloperApiKeyVault {
    val values = mutableMapOf<CloudReviewProvider, String>()
    var removeSucceeds = true
    override fun read(provider: CloudReviewProvider) = values[provider]
    override fun write(provider: CloudReviewProvider, value: String): Boolean { values[provider] = value; return true }
    override fun remove(provider: CloudReviewProvider): Boolean {
        if (!removeSucceeds) return false
        values.remove(provider); return true
    }
}

class DeveloperLabSettingsTest {
    @Test fun defaultsNeverEnableCloudOrTrainingAndPortableImportResetsConsent() {
        val vault = FakeDeveloperVault()
        val settings = DeveloperLabSettings(FakeLabPreferences(), vault)
        assertFalse(settings.state.value.cloudReviewEnabled)
        assertFalse(settings.state.value.autoLearnEnabled)
        assertFalse(settings.state.value.expressiveTtsEnabled)
        assertFalse(settings.state.value.paraphraseEnabled)
        assertTrue(settings.setApiKey("synthetic-test-key-not-a-real-secret"))
        settings.setCloudReviewEnabled(true)
        settings.setAutoLearnEnabled(true)
        val portable = settings.portableOptions()
        assertFalse(portable.cloudReviewEnabled)
        assertFalse(portable.autoLearnEnabled)
        assertFalse(portable.hasApiKey)
        assertFalse(portable.toString().contains("synthetic-test-key"))
        settings.importOptions(portable.copy(cloudReviewEnabled = true, autoLearnEnabled = true, hasApiKey = true))
        assertTrue(settings.state.value.hasApiKey)
        assertFalse(settings.state.value.cloudReviewEnabled)
        assertFalse(settings.state.value.autoLearnEnabled)
    }

    @Test fun providerChangeRequiresFreshCloudConsentAndKeepsSeparateCredentials() {
        val settings = DeveloperLabSettings(FakeLabPreferences(), FakeDeveloperVault())
        settings.setApiKey("synthetic-openai-key-only")
        settings.setCloudReviewEnabled(true)
        settings.setProvider(CloudReviewProvider.GOOGLE)
        assertFalse(settings.state.value.hasApiKey)
        assertFalse(settings.state.value.cloudReviewEnabled)
        assertEquals("gemini-2.5-flash-lite", settings.state.value.modelId)
        settings.setProvider(CloudReviewProvider.OPENAI)
        assertTrue(settings.state.value.hasApiKey)
        assertFalse(settings.state.value.cloudReviewEnabled)
        settings.clearApiKey()
        assertFalse(settings.state.value.hasApiKey)
    }

    @Test fun modelPathAndHeaderInjectionAreRejectedWithoutChangingSettings() {
        val settings = DeveloperLabSettings(FakeLabPreferences(), FakeDeveloperVault())
        assertFalse(settings.setModelId("../models/model?key=secret"))
        assertEquals("gpt-5.4-mini", settings.state.value.modelId)
        assertFalse(settings.setApiKey("synthetic-key\r\nAuthorization:other"))
        assertFalse(settings.state.value.hasApiKey)
    }

    @Test fun keyRemovalFailureDisablesSendingWithoutFalselyClaimingCredentialRemoval() {
        val vault = FakeDeveloperVault()
        val settings = DeveloperLabSettings(FakeLabPreferences(), vault)
        settings.setApiKey("synthetic-test-key-not-real")
        settings.setCloudReviewEnabled(true)
        vault.removeSucceeds = false
        assertFalse(settings.clearApiKey())
        assertTrue(settings.state.value.hasApiKey)
        assertFalse(settings.state.value.cloudReviewEnabled)
        vault.removeSucceeds = true
        assertTrue(settings.clearApiKey())
        assertFalse(settings.state.value.hasApiKey)
    }

    @Test fun sentenceNormalizationPreservesCaseAndBoundsUntrustedEntries() {
        assertEquals("Seoul DMZ", normalizeMemorySource("  Seoul\n DMZ  "))
        assertNotEquals(normalizeMemorySource("US"), normalizeMemorySource("us"))
        assertEquals("zh-hant", normalizeMemoryLanguage("zh-Hant-TW"))
        assertThrows(IllegalArgumentException::class.java) {
            validateMemoryEntry(SentenceMemoryEntry(sourceLanguageTag = "ko", targetLanguageTag = "en",
                translationRegister = TranslationRegister.FORMAL, original = "bad\u0000source",
                corrected = "translation", origin = SentenceMemoryOrigin.USER))
        }
    }

    @Test fun recognitionAndTranslationRegionalAliasesShareKeysButChineseScriptsRemainDistinct() {
        for (tag in listOf("en-US", "en-GB", "ko-KR", "ja-JP", "fr-FR", "es-419", "de-DE", "vi-VN")) {
            assertEquals(tag.substringBefore('-'), normalizeMemoryLanguage(tag))
        }
        assertEquals(normalizeMemoryLanguage("zh-TW"), normalizeMemoryLanguage("zh-Hant"))
        assertEquals(normalizeMemoryLanguage("zh-CN"), normalizeMemoryLanguage("cmn-Hans-CN"))
        assertEquals(normalizeMemoryLanguage("zh"), normalizeMemoryLanguage("zh-SG"))
        assertNotEquals(normalizeMemoryLanguage("zh-TW"), normalizeMemoryLanguage("zh-CN"))
        assertNotEquals(normalizeMemoryLanguage("sr-Latn"), normalizeMemoryLanguage("sr-Cyrl"))
    }
}
