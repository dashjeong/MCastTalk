package app.guidecast.transmitter

import app.guidecast.core.translation.TextTranslationEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TranslationModeNetworkSpyTest {
    @Test fun offlineOverridesKeysOnlineConsentAndLearningOptInWithoutAnyNetworkFactory() = runTest {
        val options = TranslationApiOptions(provider = TranslationApiProvider.LOCAL, allowOnline = true, hasKey = true, alwaysLearnOnline = true)
        var keyReads = 0; var httpOpens = 0; var localCalls = 0
        val wire = FixtureRealtimeWire(realtimeFixture())
        val service = TranslationApiService({ options }, { false }, { keyReads++; "synthetic" },
            { httpOpens++; error("Network forbidden") }, OpenAiRealtimeTransport(wire))
        assertEquals("Hello", service.engine(TextTranslationEngine { _, _, _ -> localCalls++; "Hello" }).translate("안녕", "ko", "en"))
        assertEquals(1, localCalls); assertEquals(0, keyReads); assertEquals(0, httpOpens); assertEquals(0, wire.opens)
        assertEquals(0L, service.usage.value.requests)
    }
    @Test fun onlineFailureNeverRunsLocalFallbackEvenWithLegacyPreference() = runTest {
        val options = TranslationApiOptions(provider = TranslationApiProvider.OPENAI, allowOnline = true, hasKey = true, localFallback = true)
        var localCalls = 0; var requests = 0
        val service = TranslationApiService({ options }, { true }, { "synthetic" }, { requests++; error("Unavailable") })
        try { service.engine(TextTranslationEngine { _, _, _ -> localCalls++; "fallback" }).translate("안녕", "ko", "en"); fail() }
        catch (_: IllegalStateException) { }
        assertEquals(1, requests); assertEquals(0, localCalls)
        assertEquals(1L, service.usage.value.unconfirmedUsage)
    }
    @Test fun realtimeUsesOnlyWebSocketAndReturnsSelectedOnlineAnswer() = runTest {
        val options = TranslationApiOptions(provider = TranslationApiProvider.OPENAI_REALTIME, model = "gpt-realtime-2.1-mini", allowOnline = true, hasKey = true)
        val wire = FixtureRealtimeWire(realtimeFixture())
        val service = TranslationApiService({ options }, { true }, { "synthetic" }, { error("HTTP path forbidden") }, OpenAiRealtimeTransport(wire))
        assertEquals("Hello", service.engine(TextTranslationEngine { _, _, _ -> error("Local path forbidden") }).translate("안녕", "ko", "en"))
        assertEquals(1, wire.opens)
        assertEquals(1L, service.usage.value.requests)
        assertEquals(0L, service.usage.value.unconfirmedUsage)
    }
}
