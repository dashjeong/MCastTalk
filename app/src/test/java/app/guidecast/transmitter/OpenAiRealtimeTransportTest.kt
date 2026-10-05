package app.guidecast.transmitter

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

internal class FixtureRealtimeWire(private val events: List<String>) : RealtimeWire {
    var opens = 0
    var closed = false
    val sent = mutableListOf<String>()
    var afterReceive: (() -> Unit)? = null
    var selectedModel: String? = null
    override suspend fun exchangeModel(model: String, key: String, authorized: () -> Boolean,
        conversation: suspend (RealtimeSocket) -> RealtimeTranslation): RealtimeTranslation {
        selectedModel = model
        return exchange(key, authorized, conversation)
    }
    override suspend fun exchange(key: String, authorized: () -> Boolean,
        conversation: suspend (RealtimeSocket) -> RealtimeTranslation): RealtimeTranslation {
        opens++
        val queue = ArrayDeque(events)
        try { return conversation(object : RealtimeSocket {
            override suspend fun send(text: String) { sent += text }
            override suspend fun receive(): String {
                if (queue.isEmpty()) awaitCancellation()
                return queue.removeFirst().also { afterReceive?.invoke() }
            }
        }) } finally { closed = true }
    }
}
internal fun realtimeFixture(id: String = "r1", status: String = "completed") = listOf(
    """{"type":"session.created"}""",
    """{"type":"session.updated"}""",
    """{"type":"response.created","response":{"id":"r1"}}""",
    """{"type":"response.output_text.delta","response_id":"r1","delta":"Hello"}""",
    """{"type":"response.done","response":{"id":"$id","status":"$status","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"Hello"}]}],"usage":{"input_tokens":100,"output_tokens":10,"input_token_details":{"text_tokens":100,"audio_tokens":0,"cached_tokens":20},"output_token_details":{"text_tokens":10,"audio_tokens":0}}}}""",
)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OpenAiRealtimeTransportTest {
    @Test fun selectedModelMatchesSocketDestinationAndSessionWithoutAudioClaims() = runTest {
        for (model in OPENAI_REALTIME_MODELS) {
            val wire = FixtureRealtimeWire(realtimeFixture())
            assertEquals("Hello", OpenAiRealtimeTransport(wire).translateModel(model, "synthetic-key", "Translate only", "fixture") { true }.text)
            assertEquals(model, wire.selectedModel)
            assertEquals("wss://api.openai.com/v1/realtime?model=$model", openAiRealtimeUrl(model))
            val session = JSONObject(wire.sent.first()).getJSONObject("session")
            assertEquals(model, session.getString("model"))
            assertEquals("text", session.getJSONArray("output_modalities").getString(0))
        }
    }
    @Test fun unsupportedModelFailsBeforeOpeningSocket() = runTest {
        val wire = FixtureRealtimeWire(realtimeFixture())
        try { OpenAiRealtimeTransport(wire).translateModel("gpt-5.4", "synthetic-key", "", "fixture") { true }; fail() }
        catch (_: IllegalArgumentException) { }
        assertEquals(0, wire.opens)
    }
    @Test fun completedProtocolReturnsOnlyFinalTextAndCloses() = runTest {
        val wire = FixtureRealtimeWire(realtimeFixture())
        val result = OpenAiRealtimeTransport(wire).translate("synthetic-key", "Translate only", "fixture") { true }
        assertEquals("Hello", result.text)
        assertEquals(listOf("session.update", "conversation.item.create", "response.create"), wire.sent.map { JSONObject(it).getString("type") })
        assertEquals("gpt-realtime-2.1-mini", JSONObject(wire.sent.first()).getJSONObject("session").getString("model"))
        assertTrue(wire.closed)
        assertFalse(wire.sent.any { "synthetic-key" in it })
        val options = TranslationApiOptions(provider = TranslationApiProvider.OPENAI_REALTIME, model = "gpt-realtime-2.1-mini")
        assertEquals(20L, reportedTranslationUsage(options, result.rawResponse)!!.text.cachedInput)
    }
    @Test fun offlineConsentPreventsEvenOpeningSocket() = runTest {
        val wire = FixtureRealtimeWire(realtimeFixture())
        try { OpenAiRealtimeTransport(wire).translate("synthetic-key", "", "fixture") { false }; fail() }
        catch (_: IllegalStateException) { }
        assertEquals(0, wire.opens)
    }
    @Test fun staleResponseAndIncompleteCompletionAreRejected() = runTest {
        for (events in listOf(realtimeFixture("old-id"), realtimeFixture(status = "cancelled"))) {
            val wire = FixtureRealtimeWire(events)
            try { OpenAiRealtimeTransport(wire).translate("synthetic-key", "", "fixture") { true }; fail() }
            catch (_: IllegalStateException) { }
            assertTrue(wire.closed)
        }
    }
    @Test fun cancellationBeforeAcknowledgmentSendsNoPrivateTextAndCloses() = runTest {
        val wire = FixtureRealtimeWire(emptyList())
        val task = async { OpenAiRealtimeTransport(wire).translate("synthetic-key", "", "private-fixture") { true } }
        runCurrent(); task.cancel(); runCurrent()
        assertTrue(wire.closed)
        assertFalse(wire.sent.any { "private-fixture" in it })
    }
    @Test fun revocationRejectsLateCallbackWithoutSendingNextCommand() = runTest {
        var authorized = true
        val wire = FixtureRealtimeWire(realtimeFixture()).apply { afterReceive = { authorized = false } }
        try { OpenAiRealtimeTransport(wire).translate("synthetic-key", "", "private-fixture") { authorized }; fail() }
        catch (_: IllegalStateException) { }
        assertEquals(1, wire.sent.size)
        assertTrue(wire.closed)
    }
}
