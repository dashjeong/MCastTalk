package app.guidecast.provider.gemma.translation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaSupportedLanguageTest {
    @Test
    fun officialGemmaTranslatorLanguagesCanBeSourcesAndTargets() {
        listOf("ar", "en", "es", "ja", "zh", "ko", "en-US").forEach { languageTag ->
            assertTrue(GemmaTranslationProvider.supportsTargetLanguage(languageTag))
            assertTrue(GemmaTranslationProvider.supportsSourceLanguage(languageTag))
        }
        listOf("nl", "de", "fr").forEach { languageTag ->
            assertFalse(GemmaTranslationProvider.supportsTargetLanguage(languageTag))
            assertFalse(GemmaTranslationProvider.supportsSourceLanguage(languageTag))
        }
    }

    @Test
    fun translationPairMustUseDifferentOfficialLanguages() {
        assertTrue(GemmaTranslationProvider.supportsTranslation("en-US", "ja-JP"))
        assertTrue(GemmaTranslationProvider.supportsTranslation("ar", "ko-KR"))
        assertFalse(GemmaTranslationProvider.supportsTranslation("en-US", "en"))
        assertFalse(GemmaTranslationProvider.supportsTranslation("nl", "en"))
        assertFalse(GemmaTranslationProvider.supportsTranslation("en", "nl"))
    }
}
