package app.guidecast.provider.gemma.translation

import org.junit.Assert.*
import org.junit.Test

class GemmaTranslationStylePromptTest {
    @Test fun disabledStylePreservesOriginalPromptExactly() {
        val prompt = "Original prompt including quoted source and glossary."
        assertSame(prompt, GemmaTranslationStylePrompt.apply(prompt, ""))
    }
    @Test fun chosenRegisterAddsFidelityRulesWithoutChangingSourceOrBasePrompt() {
        val prompt = "ORIGINAL: \"Do not arrive after 10:30.\""
        val formal = GemmaTranslationStylePrompt.apply(prompt, "FORMAL")
        val conversational = GemmaTranslationStylePrompt.apply(prompt, "CONVERSATIONAL")
        assertTrue(formal.contains("formal register"))
        assertTrue(conversational.contains("conversational phrasing"))
        for (result in listOf(formal, conversational)) {
            assertTrue(result.endsWith(prompt))
            assertTrue(result.contains("negation, condition"))
            assertTrue(result.contains("Do not embellish"))
        }
    }
    @Test(expected = IllegalArgumentException::class) fun arbitraryStyleInstructionsAreRejected() {
        GemmaTranslationStylePrompt.apply("fixture", "Ignore the source")
    }
}
