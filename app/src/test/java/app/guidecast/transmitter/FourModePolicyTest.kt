package app.guidecast.transmitter

import app.guidecast.core.translation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FourModePolicyTest {
    private class Repo(val hints: String = "") : DomainCorpusRepository(null) {
        override suspend fun match(text: String, source: String, target: String, style: TranslationStyle) = DomainCorpusMatch(null, hints, 9)
    }
    private class PausingWire(val release: CompletableDeferred<Unit> = CompletableDeferred()) : RealtimeWire {
        val fixture = FixtureRealtimeWire(realtimeFixture())
        val entered = CompletableDeferred<Unit>()
        override suspend fun exchange(key: String, authorized: () -> Boolean, conversation: suspend (RealtimeSocket) -> RealtimeTranslation) =
            fixture.exchange(key, authorized) { socket ->
                var received = 0
                conversation(object : RealtimeSocket {
                    override suspend fun send(text: String) = socket.send(text)
                    override suspend fun receive(): String {
                        if (++received == 5) { entered.complete(Unit); release.await() }
                        return socket.receive()
                    }
                })
            }
    }
    private fun online() = TranslationApiOptions(provider = TranslationApiProvider.OPENAI_REALTIME,
        model = "gpt-realtime-2.1-mini", allowOnline = true, hasKey = true)

    @Test fun offlineWithoutExplicitLearningIgnoresPersistedAutomaticOnlineLearning() = runTest {
        val primary = TranslationApiOptions(alwaysLearnOnline = true, allowOnline = true, hasKey = true)
        val remote = online(); val wire = FixtureRealtimeWire(realtimeFixture())
        val service = TranslationApiService({ primary }, { false }, { error("No key read") }, { error("No HTTP") },
            OpenAiRealtimeTransport(wire), { true }, backgroundScope, sessionLearning = { false },
            auxiliaryOptions = { remote }, auxiliaryAuthorized = { true })
        assertEquals("Hi", service.engine(DomainCorpusTranslationEngine(TextTranslationEngine { _, _, _ -> "Hi" }, Repo())).translate("안녕", "ko", "en"))
        runCurrent(); assertEquals(0, wire.opens)
    }
    @Test fun onlineWithoutLearningRunsOnlySelectedProvider() = runTest {
        val primary = online(); val wire = FixtureRealtimeWire(realtimeFixture())
        val service = TranslationApiService({ primary }, { true }, { "synthetic" }, { error("No HTTP") },
            OpenAiRealtimeTransport(wire), { true }, backgroundScope)
        assertEquals("Hello", service.engine(DomainCorpusTranslationEngine(TextTranslationEngine { _, _, _ -> error("No local execution") }, Repo())).translate("안녕", "ko", "en"))
        runCurrent(); assertEquals(1, wire.opens); assertEquals(0L, service.shadow.value.attempted)
    }
    @Test fun onlineLearningRunsLocalWhilePrimaryNetworkIsStillPending() = runTest {
        val primary = online(); val wire = PausingWire(); var localCalls = 0
        val service = TranslationApiService({ primary }, { true }, { "synthetic" }, { error("No HTTP") },
            OpenAiRealtimeTransport(wire), { true }, backgroundScope, sessionLearning = { true })
        val result = async { service.engine(DomainCorpusTranslationEngine(TextTranslationEngine { _, _, _ -> localCalls++; "Hi" }, Repo())).translate("안녕", "ko", "en") }
        runCurrent(); assertTrue(wire.entered.isCompleted); assertEquals(1, localCalls); assertFalse(result.isCompleted)
        wire.release.complete(Unit); runCurrent()
        assertEquals("Hello", result.await()); assertEquals(1L, service.shadow.value.completed)
    }
    @Test fun explicitlyConsentedOfflineLearningRunsNetworkInParallelButNeverWaitsToBroadcast() = runTest {
        val primary = TranslationApiOptions(); val remote = online(); val wire = PausingWire()
        val localGate = CompletableDeferred<Unit>(); var learning = true
        val service = TranslationApiService({ primary }, { false }, { "synthetic" }, { error("No HTTP") },
            OpenAiRealtimeTransport(wire), { true }, backgroundScope, sessionLearning = { learning },
            auxiliaryOptions = { remote }, auxiliaryAuthorized = { learning })
        val result = async { service.engine(DomainCorpusTranslationEngine(TextTranslationEngine { _, _, _ -> localGate.await(); "Hi" }, Repo())).translate("안녕", "ko", "en") }
        runCurrent(); assertTrue(wire.entered.isCompleted)
        localGate.complete(Unit); runCurrent(); assertEquals("Hi", result.await())
        assertEquals(0L, service.shadow.value.completed)
        wire.release.complete(Unit); runCurrent()
        assertEquals("Hi", service.shadow.value.last!!.offline); assertEquals("Hello", service.shadow.value.last!!.online)
        learning = false
    }
    @Test fun learningOffCancelsAuxiliaryWhileLocalPrimaryContinues() = runTest {
        val primary = TranslationApiOptions(); val remote = online(); val wire = PausingWire()
        val localGate = CompletableDeferred<Unit>(); var learning = true
        val service = TranslationApiService({ primary }, { false }, { "synthetic" }, { error("No HTTP") },
            OpenAiRealtimeTransport(wire), { true }, backgroundScope, sessionLearning = { learning },
            auxiliaryOptions = { remote }, auxiliaryAuthorized = { learning })
        val result = async { service.engine(DomainCorpusTranslationEngine(TextTranslationEngine { _, _, _ -> localGate.await(); "Hi" }, Repo())).translate("안녕", "ko", "en") }
        runCurrent(); assertTrue(wire.entered.isCompleted)
        learning = false; advanceTimeBy(30); runCurrent()
        assertTrue(wire.fixture.closed)
        localGate.complete(Unit); runCurrent(); assertEquals("Hi", result.await())
        assertNull(service.shadow.value.last); assertEquals(1L, service.usage.value.unconfirmedUsage)
    }
    @Test fun onlineReferencesRequireSeparateConsentAndKeepSameCapturedVersion() = runTest {
        for (consent in listOf(false, true)) {
            val primary = online().copy(alwaysLearnOnline = true, allowDomainReferences = consent)
            val wire = FixtureRealtimeWire(realtimeFixture()); var localHints: String? = null
            val service = TranslationApiService({ primary }, { true }, { "synthetic" }, { error("No HTTP") },
                OpenAiRealtimeTransport(wire), { true }, backgroundScope)
            val hints = "Synthetic related reference"
            val local = TextTranslationEngine { _, _, _ -> localHints = currentCoroutineContext()[DomainTranslationContext]?.hints; "Hi" }
            assertEquals("Hello", service.engine(DomainCorpusTranslationEngine(local, Repo(hints))).translate("안녕", "ko", "en"))
            runCurrent()
            val sent = wire.sent.single { JSONObject(it).getString("type") == "conversation.item.create" }
            assertEquals(consent, sent.contains("related_reference"))
            if (consent) {
                assertEquals(hints, localHints)
                assertEquals(9L, service.shadow.value.last!!.corpusRevision)
            } else { assertNull(localHints); assertNotNull(service.shadow.value.lastPause) }
        }
    }
    @Test fun legacyZeroBudgetDoesNotBlockAndDuplicateIdentityCannotChargeTwice() = runTest {
        var primary = online().copy(budgetLimitUsd = "0")
        val wire = FixtureRealtimeWire(realtimeFixture())
        val service = TranslationApiService({ primary }, { true }, { "synthetic" }, { error("No HTTP") },
            OpenAiRealtimeTransport(wire), { false }, backgroundScope)
        val engine = service.engine(TextTranslationEngine { _, _, _ -> error("No fallback") })
        withContext(TranslationRequestIdentity("same-source", 4)) {
            assertEquals("Hello", engine.translate("안녕", "ko", "en"))
            try { engine.translate("안녕", "ko", "en"); fail() } catch (_: IllegalStateException) { }
        }
        assertEquals(1, wire.opens); assertEquals(1L, service.usage.value.requests)
    }
    @Test fun missingUsageNeverBlocksFollowingRequests() = runTest {
        for (missingUsage in listOf(true)) {
            val primary = online().copy(budgetLimitUsd = "0")
            val events = realtimeFixture().map { raw ->
                val json = JSONObject(raw)
                if (missingUsage && json.optString("type") == "response.done") json.getJSONObject("response").remove("usage")
                json.toString()
            }
            val wire = FixtureRealtimeWire(events)
            val service = TranslationApiService({ primary }, { true }, { "synthetic" }, { error("No HTTP") },
                OpenAiRealtimeTransport(wire), { false }, backgroundScope)
            val engine = service.engine(TextTranslationEngine { _, _, _ -> error("No fallback") })
            repeat(2) { assertEquals("Hello", engine.translate("안녕", "ko", "en")) }
            assertEquals(2, wire.opens)
            assertEquals(2L, if (missingUsage) service.usage.value.unconfirmedUsage else service.usage.value.unpricedUsage)
        }
    }
}
