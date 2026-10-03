package app.guidecast.transmitter

import app.guidecast.core.translation.OnlinePrices
import org.junit.Assert.*
import org.junit.Test

class TranslationUsageFixtureTest {
    private val gemini = TranslationApiOptions(provider = TranslationApiProvider.GEMINI, model = "gemini-3.5-flash-lite",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta")
    @Test fun geminiThoughtTokensAndCacheArePricedOnlyFromReportedUsage() {
        val raw = """{"candidates":[{"finishReason":"STOP","content":{"parts":[{"thought":true,"text":"synthetic reasoning"},{"text":"Hello"}]}}],"usageMetadata":{"promptTokenCount":100,"cachedContentTokenCount":20,"candidatesTokenCount":10,"thoughtsTokenCount":5}}"""
        assertEquals("Hello", TranslationApiJson.result(gemini, raw))
        val usage = reportedTranslationUsage(gemini, raw)!!
        assertEquals(15L, usage.text.output)
        assertEquals(20L, usage.text.cachedInput)
        assertEquals(0, OnlinePrices.geminiFlashLite.estimate(usage)!!.compareTo("0.0000621".toBigDecimal()))
        assertNull(TranslationApiJson.result(gemini, raw.replace("STOP", "MAX_TOKENS")))
    }
    @Test fun missingInvalidAndUnexpectedRealtimeUsageCannotBecomeZeroCost() {
        assertNull(reportedTranslationUsage(gemini, "{}"))
        assertNull(reportedTranslationUsage(gemini, """{"usageMetadata":{"promptTokenCount":1,"cachedContentTokenCount":2,"candidatesTokenCount":3}}"""))
        val realtime = TranslationApiOptions(provider = TranslationApiProvider.OPENAI_REALTIME, model = "gpt-realtime-2.1-mini")
        val raw = org.json.JSONObject(realtimeFixture().last()).getJSONObject("response").toString()
        assertNotNull(reportedTranslationUsage(realtime, raw))
        assertNull(reportedTranslationUsage(realtime, raw.replace("\"audio_tokens\":0", "\"audio_tokens\":1")))
        assertNull(reportedTranslationUsage(realtime, raw.replace("\"input_tokens\":100", "\"input_tokens\":101")))
    }
    @Test fun realtimeRejectsWrongEndpointModelOrTextProtocol() {
        val base = TranslationApiOptions(provider = TranslationApiProvider.OPENAI_REALTIME, model = "gpt-realtime-2.1-mini")
        assertTrue(validTranslationApiOptions(base))
        assertFalse(validTranslationApiOptions(base.copy(model = "gpt-5.4-mini")))
        assertFalse(validTranslationApiOptions(base.copy(baseUrl = "https://untrusted.example/v1")))
        assertFalse(validTranslationApiOptions(base.copy(protocol = TranslationApiProtocol.CHAT_COMPLETIONS)))
    }
}
