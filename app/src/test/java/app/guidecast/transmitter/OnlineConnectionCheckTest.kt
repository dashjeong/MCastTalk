package app.guidecast.transmitter

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnlineConnectionCheckTest {
    private val options = TranslationApiOptions(provider = TranslationApiProvider.OPENAI_REALTIME,
        model = "gpt-realtime-2.1-mini", domainPrompt = "private fixture never sent")
    @Test fun emptyKeyOpensNoConnection() = runTest {
        val wire = FixtureRealtimeWire(emptyList())
        assertEquals(OnlineConnectionResult.KEY_REQUIRED, OnlineConnectionCheck(openAi = wire).run(options, " ") { true })
        assertEquals(0, wire.opens)
    }
    @Test fun geminiSetupErrorsGiveDistinctActionsAndNeverLeakProviderMessage() = runTest {
        for ((code, expected) in listOf(401 to OnlineConnectionResult.KEY_REJECTED,
                403 to OnlineConnectionResult.ACCESS_DENIED, 404 to OnlineConnectionResult.MODEL_UNAVAILABLE,
                429 to OnlineConnectionResult.LIMIT_REACHED, 503 to OnlineConnectionResult.SERVICE_UNAVAILABLE,
                504 to OnlineConnectionResult.TIMED_OUT)) {
            var closed = false
            val wire = object : GeminiLiveWire {
                override suspend fun connect(key: String, authorized: () -> Boolean, block: suspend (RealtimeSocket) -> Unit) {
                    try { block(object : RealtimeSocket {
                        override suspend fun send(text: String) { assertFalse(text.contains(key)) }
                        override suspend fun receive() = JSONObject().put("error", JSONObject().put("code", code)
                            .put("message", "private provider body synthetic-key-not-for-ui")).toString()
                    }) } finally { closed = true }
                }
            }
            val result = OnlineConnectionCheck(gemini = GeminiLiveTransport(wire)).run(
                options.copy(provider = TranslationApiProvider.GEMINI_LIVE, model = GEMINI_LIVE_AGENT), "synthetic-key-not-for-ui") { true }
            assertEquals(expected, result); assertTrue(closed)
            assertFalse(result.message.contains("private")); assertFalse(result.message.contains("synthetic-key"))
        }
    }
    @Test fun openAiSetupErrorsGiveDistinctActionsWithoutRawServerText() = runTest {
        for ((code, expected) in listOf("invalid_api_key" to OnlineConnectionResult.KEY_REJECTED,
                "permission_denied" to OnlineConnectionResult.ACCESS_DENIED,
                "model_not_found" to OnlineConnectionResult.MODEL_UNAVAILABLE,
                "insufficient_quota" to OnlineConnectionResult.LIMIT_REACHED,
                "rate_limit_exceeded" to OnlineConnectionResult.LIMIT_REACHED)) {
            val wire = FixtureRealtimeWire(listOf(JSONObject().put("type", "error").put("error",
                JSONObject().put("code", code).put("message", "private-source-or-key")).toString()))
            val result = OnlineConnectionCheck(openAi = wire).run(options, "synthetic") { true }
            assertEquals(expected, result); assertTrue(wire.closed)
            assertFalse(result.message.contains("private-source"))
        }
    }
    @Test fun networkFailureCanBeRetriedWithTheSameKeyAndNoReplay() = runTest {
        val keys = mutableListOf<String>(); val sent = mutableListOf<String>()
        val wire = object : RealtimeWire {
            override suspend fun exchange(key: String, authorized: () -> Boolean, conversation: suspend (RealtimeSocket) -> RealtimeTranslation): RealtimeTranslation {
                keys += key
                if (keys.size == 1) throw java.io.IOException("private endpoint or credential must not surface")
                return conversation(object : RealtimeSocket {
                    override suspend fun send(text: String) { sent += text }
                    override suspend fun receive() = "{\"type\":\"session.updated\"}"
                })
            }
        }
        val check = OnlineConnectionCheck(openAi = wire)
        assertEquals(OnlineConnectionResult.NETWORK_UNAVAILABLE, check.run(options, "same-synthetic-key") { true })
        assertEquals(OnlineConnectionResult.READY, check.run(options, "same-synthetic-key") { true })
        assertEquals(listOf("same-synthetic-key", "same-synthetic-key"), keys)
        assertEquals(1, sent.size); assertFalse(sent.single().contains("response.create"))
        assertFalse(sent.single().contains(options.domainPrompt))
    }
    @Test fun socketTimeoutAndWrappedProviderStatusRemainDistinctFromNetwork() {
        assertEquals(OnlineConnectionResult.TIMED_OUT, onlineConnectionFailureResult(java.net.SocketTimeoutException("private")))
        assertEquals(OnlineConnectionResult.ACCESS_DENIED, onlineConnectionFailureResult(IllegalStateException("private",
            OnlineProviderFailure(onlineHttpFailure(403)))))
        assertEquals(OnlineConnectionResult.FAILED, onlineConnectionFailureResult(IllegalArgumentException("private")))
    }
    @Test fun unknownProviderErrorDoesNotInferCauseFromPrivateMessage() {
        val error = onlineProviderFailure(JSONObject("""{"error":{"code":"unknown","message":"401 invalid_api_key secret"}}"""))
        assertEquals(OnlineConnectionResult.FAILED, error.result)
        assertEquals("FAILED", error.message); assertNull(error.cause)
    }
    @Test fun geminiSymbolicStatusesHaveTheSameSafeTaxonomy() {
        for ((status, expected) in listOf("UNAUTHENTICATED" to OnlineConnectionResult.KEY_REJECTED,
            "PERMISSION_DENIED" to OnlineConnectionResult.ACCESS_DENIED,
            "NOT_FOUND" to OnlineConnectionResult.MODEL_UNAVAILABLE,
            "RESOURCE_EXHAUSTED" to OnlineConnectionResult.LIMIT_REACHED,
            "DEADLINE_EXCEEDED" to OnlineConnectionResult.TIMED_OUT,
            "UNAVAILABLE" to OnlineConnectionResult.SERVICE_UNAVAILABLE)) {
            assertEquals(expected, onlineProviderFailure(JSONObject().put("error", JSONObject().put("status", status))).result)
        }
    }
    @Test fun geminiStructuredKeyErrorDoesNotDependOnPrivateMessage() {
        val result = onlineProviderFailure(JSONObject("""{"error":{"code":400,"status":"INVALID_ARGUMENT",
            "message":"private key must not surface","details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo",
            "reason":"API_KEY_INVALID","metadata":{"private":"do not export"}}]}}"""))
        assertEquals(OnlineConnectionResult.KEY_REJECTED, result.result)
        assertEquals("KEY_REJECTED", result.message)
        val unknownDetail = onlineProviderFailure(JSONObject("""{"error":{"code":400,"details":[
            {"@type":"untrusted-type","reason":"API_KEY_INVALID"}]}}"""))
        assertEquals(OnlineConnectionResult.FAILED, unknownDetail.result)
    }

    @Test fun setupOnlyChecksSelectedModelWithoutContentOrInference() = runTest {
        val wire = FixtureRealtimeWire(realtimeFixture())
        assertEquals(OnlineConnectionResult.READY, OnlineConnectionCheck(openAi = wire).run(options, "synthetic") { true })
        assertEquals(1, wire.sent.size)
        val setup = JSONObject(wire.sent.single())
        assertEquals("session.update", setup.getString("type"))
        assertEquals(options.model, setup.getJSONObject("session").getString("model"))
        assertFalse(wire.sent.single().contains(options.domainPrompt))
        assertTrue(wire.closed)
    }
    @Test fun noConsentOpensNoConnection() = runTest {
        val wire = FixtureRealtimeWire(emptyList())
        assertEquals(OnlineConnectionResult.CONSENT_REQUIRED, OnlineConnectionCheck(openAi = wire).run(options, "synthetic") { false })
        assertEquals(0, wire.opens)
    }
    @Test fun timeoutClosesConnectionAndRetryIsAllowed() = runTest {
        val wire = FixtureRealtimeWire(emptyList())
        repeat(2) { assertEquals(OnlineConnectionResult.TIMED_OUT, OnlineConnectionCheck(openAi = wire).run(options, "synthetic") { true }) }
        assertTrue(wire.closed); assertEquals(2, wire.opens)
    }
    @Test fun revocationClosesIdleConnectionPromptly() = runTest {
        val wire = FixtureRealtimeWire(emptyList()); var allowed = true
        val task = async { OnlineConnectionCheck(openAi = wire).run(options, "synthetic") { allowed } }
        runCurrent(); allowed = false; advanceTimeBy(26); runCurrent()
        assertTrue(task.isCancelled); assertTrue(wire.closed)
    }
    @Test fun geminiAcknowledgementClosesWithoutAudioOrDomainContent() = runTest {
        val sent = mutableListOf<String>(); var closed = false
        val wire = object : GeminiLiveWire {
            override suspend fun connect(key: String, authorized: () -> Boolean, block: suspend (RealtimeSocket) -> Unit) {
                try { block(object : RealtimeSocket {
                    override suspend fun send(text: String) { sent += text }
                    override suspend fun receive() = "{\"setupComplete\":{}}"
                }) } finally { closed = true }
            }
        }
        assertEquals(OnlineConnectionResult.READY, OnlineConnectionCheck(gemini = GeminiLiveTransport(wire))
            .run(options.copy(provider = TranslationApiProvider.GEMINI_LIVE, model = GEMINI_LIVE_AGENT), "synthetic") { true })
        assertEquals(1, sent.size); assertTrue(JSONObject(sent.single()).has("setup"))
        assertFalse(sent.single().contains(options.domainPrompt)); assertTrue(closed)
    }
}
