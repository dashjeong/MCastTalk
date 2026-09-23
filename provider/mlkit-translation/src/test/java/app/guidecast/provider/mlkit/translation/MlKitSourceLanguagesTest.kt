package app.guidecast.provider.mlkit.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MlKitSourceLanguagesTest {
    @Test fun sourceSelectionAndWorkerWarmupShareTheSameTranslationOnlyCatalog() {
        assertEquals(setOf("ar", "de", "en", "es", "fr", "ja", "ko", "zh"),
            ML_KIT_SOURCE_WARMUP_TEXT.keys)
        ML_KIT_SOURCE_WARMUP_TEXT.forEach { (source, warmup) ->
            assertEquals(warmup, mlKitWarmupSourceText(source))
            assertTrue(warmup.isNotBlank() && warmup.length <= 16)
        }
    }

    @Test fun regionalFrenchAndGermanUseTheSameWarmupAsTheirBaseLanguage() {
        assertEquals("Bonjour", mlKitWarmupSourceText("fr-FR"))
        assertEquals("Bonjour", mlKitWarmupSourceText("fr-CA"))
        assertEquals("Hallo", mlKitWarmupSourceText("de-DE"))
        assertEquals("Hallo", mlKitWarmupSourceText("de-AT"))
    }

    @Test fun anMlKitOutputLanguageDoesNotSilentlyBecomeAnInputLanguage() {
        assertTrue(runCatching { mlKitWarmupSourceText("nl") }.exceptionOrNull()
            is IllegalArgumentException)
    }
}
