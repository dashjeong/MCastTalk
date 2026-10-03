package app.guidecast.transmitter

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

@OptIn(ExperimentalCoroutinesApi::class)
class GeminiLiveTransportTest {
    private class Wire : GeminiLiveWire {
        var opens = 0
        var closed = false
        val messages = mutableListOf<String>()
        val events = Channel<String>(10)
        override suspend fun connect(key: String, authorized: () -> Boolean, block: suspend (RealtimeSocket) -> Unit) {
            opens++
            try { block(object : RealtimeSocket {
                override suspend fun send(text: String) { messages += text }
                override suspend fun receive() = events.receive()
            }) } finally { closed = true }
        }
    }
    @Test fun styleInstructionsReachGeneralLiveButNeverDedicatedTranslate() {
        for (tone in app.guidecast.core.translation.TranslationStyle.entries) {
            val setup = JSONObject(geminiLiveSetup(GEMINI_LIVE_AGENT, "en", "Semiconductor", tone)).getJSONObject("setup")
            val instruction = setup.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")
            assertTrue(instruction.contains("Semiconductor"))
            assertTrue(instruction.contains("technical term, quotation, uncertainty"))
            assertTrue(instruction.contains(when (tone) {
                app.guidecast.core.translation.TranslationStyle.CONVERSATIONAL -> "short speakable sentences"
                app.guidecast.core.translation.TranslationStyle.FORMAL -> "well-structured written sentences"
                else -> "original situation and register"
            }))
            assertFalse(JSONObject(geminiLiveSetup(GEMINI_LIVE_TRANSLATE, "en", tone = tone)).getJSONObject("setup").has("systemInstruction"))
        }
    }
    @Test fun conversationalDefaultsPreserveExplicitImportedStyle() {
        assertEquals(app.guidecast.core.translation.TranslationStyle.CONVERSATIONAL, TranslationApiOptions().tone)
        assertEquals(app.guidecast.core.translation.TranslationStyle.CONVERSATIONAL, TranslationApiOptions.fromPortable(JSONObject()).tone)
        for (tone in app.guidecast.core.translation.TranslationStyle.entries) {
            assertEquals(tone, TranslationApiOptions.fromPortable(JSONObject().put("tone", tone.name)).tone)
        }
    }
    @Test fun capabilitySpecificSetupNeverInjectsUnsupportedRagOrInstructions() {
        val dedicated = JSONObject(geminiLiveSetup(GEMINI_LIVE_TRANSLATE, "zh-TW")).getJSONObject("setup")
        assertFalse(dedicated.has("systemInstruction"))
        assertFalse(dedicated.has("tools"))
        assertEquals("zh-Hant", dedicated.getJSONObject("generationConfig").getJSONObject("translationConfig").getString("targetLanguageCode"))
        val agent = JSONObject(geminiLiveSetup(GEMINI_LIVE_AGENT, "en")).getJSONObject("setup")
        assertTrue(agent.has("systemInstruction"))
        assertTrue(geminiLiveSetup(GEMINI_LIVE_AGENT, "en", "Semiconductor equipment").contains("Semiconductor equipment"))
        assertThrows(IllegalArgumentException::class.java) { geminiLiveSetup(GEMINI_LIVE_TRANSLATE, "en", "Semiconductor equipment") }
        assertFalse(agent.getJSONObject("generationConfig").has("translationConfig"))
        assertThrows(IllegalArgumentException::class.java) { geminiLiveSetup("unverified-model", "en") }
        assertThrows(IllegalArgumentException::class.java) { parseGeminiLiveEvent("[".repeat(10_000) + "0" + "]".repeat(10_000)) }
    }
    @Test fun trialDeadlineClosesSocketWithoutAutomaticReplay() = runTest {
        val wire = Wire(); wire.events.send("{\"setupComplete\":{}}")
        val task = launch { runCatching { GeminiLiveTransport(wire).run("synthetic", GEMINI_LIVE_AGENT, "en", flow { awaitCancellation() }, { true }, {}, {}) } }
        runCurrent(); advanceTimeBy(60_001); runCurrent()
        assertTrue(task.isCompleted); assertTrue(wire.closed); assertEquals(1, wire.opens)
    }
    @Test fun offlineHasZeroSocketOpens() = runTest {
        val wire = Wire()
        try { GeminiLiveTransport(wire).run("synthetic", GEMINI_LIVE_AGENT, "en", flow { awaitCancellation() }, { false }, {}, {}); fail() }
        catch (_: IllegalStateException) { }
        assertEquals(0, wire.opens)
    }
    @Test fun microphoneWaitsForAckThenSends100msChunksAndClosesOnCancel() = runTest {
        val wire = Wire()
        val task = launch { GeminiLiveTransport(wire).run("synthetic", GEMINI_LIVE_TRANSLATE, "en",
            flow { repeat(5) { emit(ByteArray(640)) }; awaitCancellation() }, { true }, {}, {}) }
        runCurrent()
        assertEquals(1, wire.messages.size)
        wire.events.send("{\"setupComplete\":{}}")
        runCurrent()
        assertEquals(2, wire.messages.size)
        val audio = JSONObject(wire.messages[1]).getJSONObject("realtimeInput").getJSONObject("audio")
        assertEquals(3_200, Base64.getDecoder().decode(audio.getString("data")).size)
        assertEquals("audio/pcm;rate=16000", audio.getString("mimeType"))
        task.cancelAndJoin()
        assertTrue(wire.closed)
        assertFalse(wire.messages.any { "synthetic" in it })
    }
    @Test fun allPartsAndTranscriptsAreProcessedButInterruptedPcmIsNotPublished() {
        val data = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4))
        val raw = """{"serverContent":{"inputTranscription":{"text":"안녕"},"outputTranscription":{"text":"Hello"},"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$data"}},{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$data"}}]},"turnComplete":true}}"""
        val event = parseGeminiLiveEvent(raw)
        assertEquals(2, event.audio.size); assertEquals("안녕", event.source); assertEquals("Hello", event.translation); assertTrue(event.finished)
        val interrupted = JSONObject(raw).apply { getJSONObject("serverContent").put("interrupted", true) }
        assertTrue(parseGeminiLiveEvent(interrupted.toString()).audio.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { parseGeminiLiveEvent(raw.replace("rate=24000", "rate=16000")) }
    }
    @Test fun reconnectIsNewConnectionWithoutReplayingOldMicrophone() = runTest {
        val wire = Wire()
        repeat(2) {
            wire.events.send("{\"setupComplete\":{}}")
            val task = launch { runCatching { GeminiLiveTransport(wire).run("synthetic", GEMINI_LIVE_AGENT, "en",
                flow { awaitCancellation() }, { true }, {}, {}) } }
            runCurrent()
            wire.events.send("{\"goAway\":{}}")
            runCurrent(); task.join()
        }
        assertEquals(2, wire.opens)
        assertEquals(2, wire.messages.size) // setup only, no microphone replay or clientContent
        assertTrue(wire.closed)
    }
    @Test fun consentRevocationClosesIdleSocketBeforeLateAudio() = runTest {
        val wire = Wire(); var authorized = true; var published = 0
        wire.events.send("{\"setupComplete\":{}}")
        val task = launch { runCatching { GeminiLiveTransport(wire).run("synthetic", GEMINI_LIVE_AGENT, "en",
            flow { awaitCancellation() }, { authorized }, {}, { published++ }) } }
        runCurrent(); authorized = false; advanceTimeBy(26); runCurrent(); task.join()
        assertEquals(0, published); assertTrue(wire.closed)
    }
}
