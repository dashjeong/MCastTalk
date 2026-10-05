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
class GeminiNativeComparisonExclusionTest {
    private class Wire : GeminiLiveWire {
        var opens = 0
        var closed = false
        val sent = mutableListOf<String>()
        val received = Channel<String>(Channel.UNLIMITED)
        override suspend fun connect(key: String, authorized: () -> Boolean, block: suspend (RealtimeSocket) -> Unit) {
            opens++
            try { block(object : RealtimeSocket {
                override suspend fun send(text: String) { sent += text }
                override suspend fun receive() = received.receive()
            }) } finally { closed = true }
        }
    }
    private fun event(source: String? = null, target: String? = null, end: Boolean = false,
        interrupted: Boolean = false, audio: Boolean = false): String {
        val body = JSONObject().put("turnComplete", end).put("interrupted", interrupted)
        source?.let { body.put("inputTranscription", JSONObject().put("text", it)) }
        target?.let { body.put("outputTranscription", JSONObject().put("text", it)) }
        if (audio) body.put("modelTurn", JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put(
            "inlineData", JSONObject().put("mimeType", "audio/pcm;rate=24000")
                .put("data", Base64.getEncoder().encodeToString(byteArrayOf(0, 1, 0, 2)))))))
        return JSONObject().put("serverContent", body).toString()
    }
    @Test fun delayedAndReversedCaptionsNeverCreateAWorkerWhileAudioStillArrives() = runTest {
        for (model in listOf(GEMINI_LIVE_AGENT, GEMINI_LIVE_TRANSLATE)) {
            val options = TranslationApiOptions(provider = TranslationApiProvider.GEMINI_LIVE, model = model)
            val monitor = NativeLearningMonitor(); var creations = 0
            val comparison = nativeLearningSessionFor(options, 1, true, monitor) { creations++; error("Unexpected worker") }
            val wire = Wire(); val relayed = mutableListOf<GeminiLiveEvent>()
            wire.received.send("{\"setupComplete\":{}}")
            val task = launch { GeminiLiveTransport(wire).run("synthetic", model, "en", flow { awaitCancellation() },
                { true }, {}, { relayed += it }) }
            runCurrent()
            wire.received.send(event(target = "Hello", end = true, audio = true))
            wire.received.send(event(source = "안녕"))
            wire.received.send(event(target = "Next", end = true, audio = true))
            wire.received.send(event(source = "다음")); runCurrent()
            assertEquals(4, relayed.size); assertEquals(8, relayed.sumOf { e -> e.audio.sumOf { it.size } })
            assertEquals(listOf(1L, 2L, 2L, 3L), relayed.map { it.timingTurn })
            assertNull(comparison); assertEquals(0, creations); assertNull(monitor.state.value.last)
            assertEquals(0L, monitor.state.value.attempted); assertEquals(NativeLearningPause.ALIGNMENT.label, monitor.state.value.lastPause)
            assertEquals(1, wire.sent.size); task.cancelAndJoin(); assertTrue(wire.closed)
        }
    }

    @Test fun sameMessageTextAndInterruptionDoNotEstablishAnInputOutputPair() = runTest {
        val monitor = NativeLearningMonitor(); var creations = 0
        val options = TranslationApiOptions(provider = TranslationApiProvider.GEMINI_LIVE, model = GEMINI_LIVE_AGENT)
        assertNull(nativeLearningSessionFor(options, 1, true, monitor) { creations++; error("Unexpected worker") })
        val wire = Wire(); val relayed = mutableListOf<GeminiLiveEvent>(); wire.received.send("{\"setupComplete\":{}}")
        val task = launch { GeminiLiveTransport(wire).run("synthetic", options.model, "en", flow { awaitCancellation() },
            { true }, {}, { relayed += it }) }
        runCurrent(); wire.received.send(event("안녕", "Hello", end = true, interrupted = true, audio = true))
        wire.received.send(event("다음", "Next", audio = true)); runCurrent()
        assertTrue(relayed.first().audio.isEmpty()); assertEquals(4, relayed.last().audio.single().size)
        assertEquals(0, creations); assertNull(monitor.state.value.last); assertEquals(0L, monitor.state.value.completed)
        task.cancelAndJoin()
    }

    @Test fun reconnectUsesNewOwnerAndLateOldEventsCannotProduceCandidatesOrTeacherRequests() = runTest {
        val monitor = NativeLearningMonitor(); var creations = 0
        val options = TranslationApiOptions(provider = TranslationApiProvider.GEMINI_LIVE, model = GEMINI_LIVE_AGENT)
        val relayed = mutableListOf<GeminiLiveEvent>(); val wires = mutableListOf<Wire>()
        for (id in 1L..2L) {
            assertNull(nativeLearningSessionFor(options, id, true, monitor) { creations++; error("Unexpected worker") })
            val wire = Wire().also { wires += it }; wire.received.send("{\"setupComplete\":{}}")
            val task = launch { GeminiLiveTransport(wire).run("synthetic", options.model, "en", flow { awaitCancellation() },
                { true }, {}, { relayed += it }) }
            runCurrent(); wire.received.send(event("새 입력", "Fresh output", end = true, audio = true)); runCurrent()
            task.cancelAndJoin(); wire.received.send(event("늦은 원문", "Late output", end = true))
        }
        monitor.update(1) { it.copy(completed = 999) }; monitor.end(1)
        assertEquals(2, relayed.size); assertEquals(0, creations); assertNull(monitor.state.value.last)
        assertEquals(0L, monitor.state.value.completed); assertEquals(NativeLearningPause.ALIGNMENT.label, monitor.state.value.lastPause)
        assertTrue(wires.all { it.opens == 1 && it.closed && it.sent.size == 1 })
    }
}
