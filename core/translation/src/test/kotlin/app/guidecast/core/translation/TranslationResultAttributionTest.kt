package app.guidecast.core.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranslationResultAttributionTest {
    @Test
    fun replacedDraftAndIndependentLanguageNeverInheritGoogleAttribution() {
        val japanese = TranslationResultAttribution()
        val dutch = TranslationResultAttribution()
        japanese.recordMlKit("こんにちは")
        assertEquals("ML_KIT", japanese.providerFor("こんにちは"))
        assertNull(japanese.providerFor("ようこそ"))
        assertNull(dutch.providerFor("こんにちは"))
        japanese.recordMlKit(null)
        assertNull(japanese.providerFor("こんにちは"))
    }

    @Test
    fun scriptConversionRetainsOnlyTheOriginalProvidersAttribution() {
        val origin = TranslationResultAttribution()
        origin.recordMlKit("汉语")
        origin.recordScriptConversion("普通话", "普通話")
        assertNull(origin.providerFor("普通話"))
        origin.recordScriptConversion("汉语", "漢語")
        assertEquals("ML_KIT", origin.providerFor("漢語"))
    }
}
