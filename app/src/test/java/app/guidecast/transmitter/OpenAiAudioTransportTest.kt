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
class OpenAiAudioTransportTest {
    private class Wire : OpenAiAudioWire {
        val sent = mutableListOf<String>()
        val events = Channel<String>(40)
        var opens = 0; var closed = false; var model: String? = null
        override suspend fun connect(model: String, key: String, authorized: () -> Boolean, block: suspend (RealtimeSocket) -> Unit) {
            this.model = model; opens++
            try { block(object : RealtimeSocket {
                override suspend fun send(text: String) { sent += text }
                override suspend fun receive() = events.receive()
            }) } finally { closed = true }
        }
        suspend fun event(type: String, content: String = "") {
            events.send("{\"type\":\"$type\"${if (content.isBlank()) "" else ",$content"}}")
        }
        fun requests() = sent.map(::JSONObject).filter { it.getString("type") == "response.create" }
    }
    private fun CoroutineScope.start(wire: Wire, input: kotlinx.coroutines.flow.Flow<ByteArray> = flow { awaitCancellation() },
        authorized: () -> Boolean = { true }, output: MutableList<OpenAiAudioEvent> = mutableListOf(), durationLimitMillis: Long? = null) = launch {
        runCatching { OpenAiAudioTransport(wire).run("synthetic-key", "gpt-realtime-2", "ko", "en", input,
            authorized, {}, { output += it }, durationLimitMillis = durationLimitMillis) }
    }
    @Test fun selectedModelHasNativeAudioAndAsynchronousInputTranscription() {
        for (model in OPENAI_REALTIME_MODELS) {
            val session = JSONObject(openAiAudioSetup(model, "ko", "en", "Semiconductor")).getJSONObject("session")
            assertEquals(model, session.getString("model"))
            assertEquals("audio", session.getJSONArray("output_modalities").getString(0))
            val input = session.getJSONObject("audio").getJSONObject("input")
            assertEquals(24_000, input.getJSONObject("format").getInt("rate"))
            assertEquals("gpt-realtime-whisper", input.getJSONObject("transcription").getString("model"))
            assertFalse(input.getJSONObject("turn_detection").getBoolean("create_response"))
            assertFalse(input.getJSONObject("turn_detection").getBoolean("interrupt_response"))
            assertTrue(session.getString("instructions").contains("Semiconductor"))
            assertEquals(0, session.getJSONArray("tools").length())
        }
    }
    @Test fun resamplingIsIndependentOfCaptureFrameBoundaries() {
        val fixture = ByteArray(3_200) { (it * 7).toByte() }
        val whole = LivePcm16To24().convert(fixture)
        val split = LivePcm16To24()
        val parts = (fixture.indices step 640).flatMap { split.convert(fixture.copyOfRange(it, it + 640)).toList() }.toByteArray()
        assertArrayEquals(whole, parts)
        assertEquals(4_798, whole.size) // Last interpolation waits for the next sample, never an invented tail.
    }
    @Test fun resamplingPreservesSignedConstantSamplesAndRejectsIncompleteSamples() {
        val converter = LivePcm16To24()
        val output = converter.convert(ByteArray(32) { if (it % 2 == 0) 0 else 128.toByte() })
        for (i in output.indices step 2) { assertEquals(0, output[i].toInt()); assertEquals(-128, output[i + 1].toInt()) }
        assertThrows(IllegalArgumentException::class.java) { converter.convert(byteArrayOf(1)) }
    }
    @Test fun inputWaitsForSetupThenIsUploadedOnlyOnceIn24kChunks() = runTest {
        val wire = Wire(); var captured = 0
        val task = start(wire, flow { repeat(6) { captured++; emit(ByteArray(640)) }; awaitCancellation() })
        runCurrent(); assertEquals(0, captured); assertEquals(1, wire.sent.size)
        wire.event("session.created"); wire.event("session.updated"); runCurrent()
        val appends = wire.sent.map(::JSONObject).filter { it.getString("type") == "input_audio_buffer.append" }
        assertEquals(1, appends.size)
        assertEquals(4_800, Base64.getDecoder().decode(appends.single().getString("audio")).size)
        assertEquals("gpt-realtime-2", wire.model)
        assertFalse(wire.sent.any { "synthetic-key" in it })
        task.cancelAndJoin(); assertTrue(wire.closed)
    }
    @Test fun committedItemCreatesOneCorrelatedIsolatedResponseWithoutReupload() = runTest {
        val wire = Wire(); val task = start(wire)
        wire.event("session.updated"); runCurrent()
        repeat(2) { wire.event("input_audio_buffer.committed", "\"item_id\":\"in1\"") }; runCurrent()
        assertEquals(1, wire.requests().size)
        val response = wire.requests().single().getJSONObject("response")
        assertEquals("none", response.getString("conversation"))
        assertEquals("in1", response.getJSONObject("metadata").getString("input_item_id"))
        assertEquals("item_reference", response.getJSONArray("input").getJSONObject(0).getString("type"))
        assertFalse(response.toString().contains("audio_buffer"))
        task.cancelAndJoin()
    }
    @Test fun lateInputTranscriptRetainsItsOwnItemAfterNextOutput() = runTest {
        val wire = Wire(); val output = mutableListOf<OpenAiAudioEvent>(); val task = start(wire, output = output)
        wire.event("session.updated")
        for (id in listOf("in1", "in2")) {
            wire.event("input_audio_buffer.committed", "\"item_id\":\"$id\"")
            wire.event("response.created", "\"response\":{\"id\":\"r$id\",\"metadata\":{\"input_item_id\":\"$id\"}}")
            wire.event("response.output_audio_transcript.done", "\"response_id\":\"r$id\",\"transcript\":\"translated$id\"")
            wire.event("response.done", "\"response\":{\"id\":\"r$id\",\"status\":\"completed\"}")
        }
        wire.event("conversation.item.input_audio_transcription.completed", "\"item_id\":\"in1\",\"transcript\":\"late-source\"")
        runCurrent()
        assertEquals("in1", output.last().inputId)
        assertEquals("translatedin1", output.last().translation)
        assertEquals("late-source", output.last().source)
        assertTrue(output.last().finished)
        assertTrue(output.last().translationFinal)
        assertTrue(nativeLearningHasConfirmedPair(output.last()))
        task.cancelAndJoin()
    }
    @Test fun completedResponseWithOnlyPartialOutputTranscriptIsNotALearningPair() = runTest {
        val wire = Wire(); val output = mutableListOf<OpenAiAudioEvent>(); val task = start(wire, output = output)
        wire.event("session.updated")
        wire.event("input_audio_buffer.committed", "\"item_id\":\"in1\"")
        wire.event("response.created", "\"response\":{\"id\":\"r1\",\"metadata\":{\"input_item_id\":\"in1\"}}")
        wire.event("conversation.item.input_audio_transcription.completed", "\"item_id\":\"in1\",\"transcript\":\"안녕\"")
        wire.event("response.output_audio_transcript.delta", "\"response_id\":\"r1\",\"delta\":\"Hello\"")
        wire.event("response.done", "\"response\":{\"id\":\"r1\",\"status\":\"completed\"}")
        runCurrent()
        assertTrue(output.last().finished); assertTrue(output.last().sourceFinal)
        assertFalse(output.last().translationFinal); assertFalse(nativeLearningHasConfirmedPair(output.last()))
        task.cancelAndJoin()
    }
    @Test fun nativeComparisonReusesTheCorrelatedResponseWithNoSecondOnlineRequestOrAutomaticApproval() = runTest {
        val wire = Wire(); val monitor = NativeLearningMonitor(); var localCalls = 0
        val repository = object : DomainCorpusRepository(null) {
            override suspend fun match(text: String, source: String, target: String,
                style: app.guidecast.core.translation.TranslationStyle) = DomainCorpusMatch(null, "", 7)
        }
        val learning = NativeLearningSession(backgroundScope, 1, monitor, { true }, { true }, { true }, { 0 }, { 7 }, { null }) { pair ->
            val local = app.guidecast.core.translation.TextTranslationEngine { text, source, target ->
                assertEquals("안녕", text); assertEquals("ko", source); assertEquals("en", target)
                localCalls++; "Hi"
            }
            val captured = DomainCorpusTranslationEngine(local, repository).capture(pair.original, "ko", "en",
                app.guidecast.core.translation.TranslationStyle.CONVERSATIONAL)
            ShadowComparison(pair.original, null, "ko", "en", requireNotNull(captured.capturedRevision), pair.translation,
                captured.translateWithContext(pair.original, null, "ko", "en"),
                nativeIdentity = NativeComparisonIdentity(1, pair.inputId, pair.responseId, pair.sequence, 1,
                    TranslationApiProvider.OPENAI_REALTIME, "gpt-realtime-2", pair.controlGeneration))
        }
        val task = launch { runCatching { OpenAiAudioTransport(wire).run("synthetic", "gpt-realtime-2", "ko", "en",
            flow { awaitCancellation() }, { true }, {}, { learning.accept(it) }) } }
        runCurrent()
        wire.event("session.updated")
        wire.event("input_audio_buffer.committed", "\"item_id\":\"in1\"")
        wire.event("response.created", "\"response\":{\"id\":\"r1\",\"metadata\":{\"input_item_id\":\"in1\"}}")
        wire.event("response.output_audio_transcript.done", "\"response_id\":\"r1\",\"transcript\":\"Hello\"")
        wire.event("response.done", "\"response\":{\"id\":\"r1\",\"status\":\"completed\"}")
        runCurrent(); assertEquals(0, localCalls)
        wire.event("conversation.item.input_audio_transcription.completed", "\"item_id\":\"in1\",\"transcript\":\"안녕\"")
        runCurrent()
        assertEquals(1, wire.opens); assertEquals(1, wire.requests().size); assertEquals(1, localCalls)
        val comparison = requireNotNull(monitor.state.value.last)
        assertEquals("Hello", comparison.online); assertEquals("Hi", comparison.offline); assertEquals(7L, comparison.corpusRevision)
        assertThrows(IllegalArgumentException::class.java) { reviewedDomainComparisonPair(comparison, "Hi", humanReviewed = false) }
        assertEquals("Hi", reviewedDomainComparisonPair(comparison, "Hi", humanReviewed = true).corrected)
        learning.close(); task.cancelAndJoin()
    }
    @Test fun nextSpeechBeforeResponseCreatedPreservesPriorTranslationWithoutCancellation() = runTest {
        val wire = Wire(); val output = mutableListOf<OpenAiAudioEvent>(); val task = start(wire, output = output)
        wire.event("session.updated")
        wire.event("input_audio_buffer.committed", "\"item_id\":\"in1\"")
        wire.event("input_audio_buffer.speech_started")
        wire.event("response.created", "\"response\":{\"id\":\"r1\",\"metadata\":{\"input_item_id\":\"in1\"}}")
        val data = Base64.getEncoder().encodeToString(ByteArray(4800))
        wire.event("response.output_audio.delta", "\"response_id\":\"r1\",\"delta\":\"$data\"")
        runCurrent()
        assertTrue(output.none { it.interrupted })
        assertEquals(1, output.sumOf { it.audio.size })
        assertTrue(wire.sent.map(::JSONObject).none { it.getString("type") == "response.cancel" })
        wire.event("input_audio_buffer.committed", "\"item_id\":\"in1\""); runCurrent()
        assertEquals(1, wire.requests().size)
        task.cancelAndJoin()
    }
    @Test fun duplicateEventsCannotDuplicateAudioOrUsage() = runTest {
        val wire = Wire(); val output = mutableListOf<OpenAiAudioEvent>(); val task = start(wire, output = output)
        wire.event("session.updated"); wire.event("input_audio_buffer.committed", "\"item_id\":\"in1\"")
        wire.event("response.created", "\"response\":{\"id\":\"r1\",\"metadata\":{\"input_item_id\":\"in1\"}}")
        val data = Base64.getEncoder().encodeToString(ByteArray(4800))
        repeat(2) { wire.event("response.output_audio.delta", "\"event_id\":\"event1\",\"response_id\":\"r1\",\"delta\":\"$data\"") }
        repeat(2) { wire.event("response.done", "\"response\":{\"id\":\"r1\",\"status\":\"completed\",\"usage\":{\"total_tokens\":10}}") }
        runCurrent(); assertEquals(1, output.sumOf { it.audio.size }); assertEquals(1, output.count { it.usage != null })
        task.cancelAndJoin()
    }
    @Test fun newSpeechPreservesCompletedUnplayedAudioAndLateSourceStillMatchesOldItem() = runTest {
        val wire = Wire(); val output = mutableListOf<OpenAiAudioEvent>(); val task = start(wire, output = output)
        wire.event("session.updated"); wire.event("input_audio_buffer.committed", "\"item_id\":\"in1\"")
        wire.event("response.created", "\"response\":{\"id\":\"r1\",\"metadata\":{\"input_item_id\":\"in1\"}}")
        wire.event("response.done", "\"response\":{\"id\":\"r1\",\"status\":\"completed\"}")
        wire.event("input_audio_buffer.speech_started")
        wire.event("conversation.item.input_audio_transcription.completed", "\"item_id\":\"in1\",\"transcript\":\"late\"")
        runCurrent()
        assertEquals(0, output.count { it.interrupted })
        assertTrue(output.last().finished); assertEquals("completed", output.last().status)
        assertEquals("in1", output.last().inputId); assertEquals("late", output.last().source)
        assertFalse(wire.sent.map(::JSONObject).any { it.getString("type") == "response.cancel" })
        task.cancelAndJoin()
    }
    @Test fun modelOrModalityMismatchAcknowledgementCannotStartMicrophoneUpload() = runTest {
        for (session in listOf("\"model\":\"wrong\",\"output_modalities\":[\"audio\"]",
            "\"model\":\"gpt-realtime-2\",\"output_modalities\":[\"text\"]")) {
            val wire = Wire(); var captured = false
            val task = start(wire, flow { captured = true; emit(ByteArray(640)); awaitCancellation() })
            wire.event("session.updated", "\"session\":{$session}"); runCurrent(); task.join()
            assertFalse(captured); assertEquals(1, wire.sent.size); assertTrue(wire.closed)
        }
    }
    @Test fun unknownResponseFailsClosedWithoutAudio() = runTest {
        val wire = Wire(); val output = mutableListOf<OpenAiAudioEvent>(); val task = start(wire, output = output)
        wire.event("session.updated"); wire.event("response.output_audio.delta", "\"response_id\":\"old\",\"delta\":\"AAAA\"")
        runCurrent(); task.join(); assertTrue(wire.closed); assertTrue(output.isEmpty())
    }
    @Test fun offlineAndUnsupportedModelsNeverOpenSocket() = runTest {
        val wire = Wire()
        for ((model, allowed) in listOf("gpt-5.4" to true, "gpt-realtime-2" to false)) {
            runCatching { OpenAiAudioTransport(wire).run("synthetic", model, "ko", "en", flow { awaitCancellation() }, { allowed }, {}, {}) }
        }
        assertEquals(0, wire.opens)
    }
    @Test fun consentRevocationClosesIdleSocketAndNewSessionDoesNotReplay() = runTest {
        val wire = Wire(); var allowed = true
        val first = start(wire, authorized = { allowed }); wire.event("session.updated"); runCurrent()
        allowed = false; advanceTimeBy(26); runCurrent(); first.join(); assertTrue(wire.closed)
        allowed = true
        val second = start(wire, authorized = { allowed }); wire.event("session.updated"); runCurrent()
        assertEquals(2, wire.opens); assertEquals(2, wire.sent.size)
        second.cancelAndJoin()
    }
    @Test fun deadlineClosesWithNoAutomaticReconnect() = runTest {
        val wire = Wire(); val task = start(wire, durationLimitMillis = 60_000); wire.event("session.updated"); runCurrent()
        advanceTimeBy(60_001); runCurrent(); task.join()
        assertTrue(wire.closed); assertEquals(1, wire.opens)
    }
    @Test fun ordinarySessionStaysConnectedBeyondOneMinuteUntilExplicitStop() = runTest {
        val wire = Wire(); val task = start(wire); wire.event("session.updated"); runCurrent()
        advanceTimeBy(61_000); runCurrent()
        assertTrue(task.isActive); assertFalse(wire.closed); assertEquals(1, wire.opens)
        task.cancelAndJoin(); assertTrue(wire.closed)
    }
    @Test fun usageKeepsMissingUnknownAndActualZeroWithCacheNotAdded() {
        val usage = requireNotNull(openAiAudioUsage(JSONObject("""{"input_tokens":100,"output_tokens":20,"total_tokens":120,"input_token_details":{"audio_tokens":80,"text_tokens":20,"cached_tokens":50},"output_token_details":{"audio_tokens":0}}""")))
        assertEquals(100L, usage.input); assertEquals(120L, usage.total); assertEquals(50L, usage.cached)
        assertEquals(0L, usage.outputAudio); assertNull(usage.outputText)
        assertNull(openAiAudioUsage(null))
        assertNull(openAiAudioUsage(JSONObject("{\"total_tokens\":-1}")))
        assertNull(openAiAudioUsage(JSONObject()))
    }
    @Test fun durationBasedTranscriptionKeepsSecondsSeparateFromResponseTokens() {
        val usage = requireNotNull(openAiAudioUsage(JSONObject("""{"type":"duration","seconds":8.4}""")))
        assertEquals(8.4, requireNotNull(usage.durationSeconds), 0.00001)
        assertNull(usage.total); assertNull(usage.input); assertNull(usage.output)
        assertEquals("DURATION", usage.countsOnly().getString("measurement_type"))
        assertEquals(0.0, requireNotNull(openAiAudioUsage(JSONObject("""{"type":"duration","seconds":0}"""))?.durationSeconds), 0.0)
        for (raw in listOf("""{"type":"duration"}""", """{"type":"duration","seconds":-1}""", """{"type":"duration","seconds":"8"}"""))
            assertNull(openAiAudioUsage(JSONObject(raw)))
    }
}
