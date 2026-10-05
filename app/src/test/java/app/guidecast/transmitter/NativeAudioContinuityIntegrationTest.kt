package app.guidecast.transmitter

import app.guidecast.core.stream.AudioChannelDescriptor
import app.guidecast.core.stream.AudioStreamRegistry
import app.guidecast.core.stream.PcmAudioFrame
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

/** Actual transport -> session router -> paced publisher -> stream mailboxes, with no network. */
@OptIn(ExperimentalCoroutinesApi::class)
class NativeAudioContinuityIntegrationTest {
    @Test fun pendingOriginalTranscriptionAndDurationRemainCorrelatedAfterThreeHundredAudioResponses() = runTest {
        val f = Fixture(this)
        val reader = launch { while (f.listener.frames.receiveCatching().isSuccess) {} }
        val monitorReader = launch { while (f.monitor.frames.receiveCatching().isSuccess) {} }
        f.wire.event("session.updated"); runCurrent()
        repeat(300) { index ->
            val id = "late$index"
            f.wire.commit(id); f.wire.created(id)
            f.wire.audio(id, 1); f.wire.translated(id); f.wire.done(id); runCurrent()
            assertNull(f.failure)
        }
        val original = f.captions.getValue("late0")
        advanceTimeBy(250); runCurrent()
        repeat(2) { f.wire.event("conversation.item.input_audio_transcription.completed",
            "\"item_id\":\"late0\",\"transcript\":\"delayed source\",\"usage\":{\"type\":\"duration\",\"seconds\":1.75}") }
        runCurrent()
        val updated = f.captions.getValue("late0")
        assertEquals(original.sequence, updated.sequence)
        assertEquals(original.capturedAtElapsedRealtimeNanos, updated.capturedAtElapsedRealtimeNanos)
        assertEquals("delayed source", updated.sourceText); assertTrue(updated.isFinal)
        assertEquals("translationlate0", updated.translations["en"])
        val usage = f.usages.filter { it.inputId == "late0" && it.usageKind == NativeAudioUsageKind.TRANSCRIPTION }
        assertEquals(1, usage.size); assertEquals(1.75, requireNotNull(usage.single().usage?.durationSeconds), 0.0)
        assertEquals(300, f.wire.requests().size); assertEquals(0, f.cancellationEdges)
        advanceTimeBy(30001); runCurrent()
        assertEquals((0 until 300).map { "late$it" }, f.delivered); assertNull(f.failure)
        reader.cancelAndJoin(); monitorReader.cancelAndJoin(); f.close()
    }

    @Test fun pendingCaptionDeadlineIsExplicitUnknownWithoutStoppingAudioOrReplayingPaidInput() = runTest {
        val f = Fixture(this)
        f.wire.event("session.updated"); f.wire.commit("A"); f.wire.created("A")
        f.wire.translated("A"); f.wire.done("A"); runCurrent()
        advanceTimeBy(120001); runCurrent()
        val expired = f.captions.getValue("A")
        assertTrue(expired.liveSourceExpired); assertFalse(expired.liveSourceFailed); assertFalse(expired.isFinal)
        assertTrue(expired.sourceStatusLabel.contains("대기 만료"))
        assertTrue(f.usages.single { it.inputId == "A" && it.usageKind == NativeAudioUsageKind.TRANSCRIPTION }.usage == null)
        f.wire.event("conversation.item.input_audio_transcription.completed", "\"item_id\":\"A\",\"transcript\":\"too late\"")
        f.wire.commit("A"); f.wire.commit("B"); f.wire.created("B")
        f.wire.audio("B", 1); f.wire.done("B"); runCurrent()
        assertEquals(2, f.wire.requests().size); assertEquals(listOf("B"), f.delivered)
        assertTrue(f.captions.getValue("A").liveSourceExpired); assertEquals(0, f.cancellationEdges); assertNull(f.failure)
        f.close()
    }

    @Test fun pendingCaptionCapacityExpiryIsVisibleAndLateOldIdCannotCreateAnotherPaidResponse() = runTest {
        val f = Fixture(this)
        f.wire.event("session.updated"); runCurrent()
        repeat(700) { index ->
            val id = "pending$index"
            f.wire.commit(id); f.wire.created(id); f.wire.done(id); runCurrent()
            assertNull(f.failure)
        }
        assertTrue(f.captions.getValue("pending0").liveSourceExpired)
        f.wire.commit("pending0")
        f.wire.event("conversation.item.input_audio_transcription.completed", "\"item_id\":\"pending0\",\"transcript\":\"expired\"")
        runCurrent()
        assertEquals(700, f.wire.requests().size); assertNull(f.failure)
        assertEquals(1, f.usages.count { it.inputId == "pending0" && it.usageKind == NativeAudioUsageKind.TRANSCRIPTION })
        f.close()
    }
    @Test fun threeHundredTurnsPruneStateWithoutReplayAndLateOldEventsCannotChangeCurrentAudio() = runTest {
        val f = Fixture(this)
        val reader = launch { while (f.listener.frames.receiveCatching().isSuccess) {} }
        val monitorReader = launch { while (f.monitor.frames.receiveCatching().isSuccess) {} }
        f.wire.event("session.updated"); runCurrent()
        repeat(300) { index ->
            val id = "i$index"
            f.wire.commit(id); runCurrent()
            f.wire.created(id)
            f.wire.event("conversation.item.input_audio_transcription.completed", "\"item_id\":\"$id\",\"transcript\":\"source$index\"")
            f.wire.audio(id, 1); f.wire.translated(id); f.wire.done(id)
            runCurrent()
            assertNull("Turn $index failed", f.failure)
            assertEquals(index + 1, f.wire.requests().size)
        }
        val lastSequence = f.captions.getValue("i299").sequence
        val epoch = f.epoch
        f.wire.commit("i0")
        f.wire.event("conversation.item.input_audio_transcription.completed", "\"item_id\":\"i0\",\"transcript\":\"stale\"")
        f.wire.audio("i0", 1); f.wire.done("i0", "cancelled"); runCurrent()
        assertEquals(300, f.wire.requests().size)
        assertEquals(epoch, f.epoch)
        assertEquals(lastSequence, f.captions.getValue("i299").sequence)
        assertEquals(2_000_000_300L, lastSequence)
        advanceTimeBy(30001); runCurrent()
        assertEquals((0 until 300).map { "i$it" }, f.delivered)
        assertEquals(0, f.discarded); assertNull(f.failure)
        assertEquals(300, f.terminals.size)
        reader.cancelAndJoin(); monitorReader.cancelAndJoin(); f.close()
    }

    @Test fun captionFailureDoesNotCancelAudioOrFollowingTurnAndNeverProducesFinalLearningPair() = runTest {
        val f = Fixture(this)
        f.wire.event("session.updated"); f.wire.commit("A"); f.wire.created("A")
        f.wire.event("conversation.item.input_audio_transcription.failed", "\"item_id\":\"A\",\"error\":{\"message\":\"private fixture error\"}")
        f.wire.audio("A", 3); f.wire.translated("A"); f.wire.done("A"); runCurrent()
        assertTrue(f.captions.getValue("A").liveSourceFailed)
        assertFalse(f.captions.getValue("A").isFinal)
        assertTrue(f.captions.getValue("A").sourceStatusLabel.contains("실패"))
        f.wire.commit("B"); f.wire.created("B")
        f.wire.event("conversation.item.input_audio_transcription.completed", "\"item_id\":\"B\",\"transcript\":\"sourceB\",\"usage\":{\"type\":\"duration\",\"seconds\":1.25}")
        f.wire.audio("B", 1); f.wire.translated("B"); f.wire.done("B"); runCurrent()
        advanceTimeBy(401); runCurrent()
        assertEquals(listOf("A", "A", "A", "B"), f.delivered)
        assertEquals(2, f.wire.requests().size); assertEquals(0, f.cancellationEdges); assertNull(f.failure)
        assertTrue(f.captions.getValue("B").isFinal)
        assertEquals(1.25, requireNotNull(f.usages.first { it.inputId == "B" && it.usageKind == NativeAudioUsageKind.TRANSCRIPTION }.usage?.durationSeconds), 0.0)
        f.close()
    }

    private class Wire : OpenAiAudioWire {
        val sent = mutableListOf<JSONObject>()
        val incoming = Channel<String>(64)
        var closed = false
        override suspend fun connect(model: String, key: String, authorized: () -> Boolean,
            block: suspend (RealtimeSocket) -> Unit) {
            try { block(object : RealtimeSocket {
                override suspend fun send(text: String) { sent += JSONObject(text) }
                override suspend fun receive() = incoming.receive()
            }) } finally { closed = true }
        }
        suspend fun event(type: String, fields: String = "") {
            incoming.send("{\"type\":\"$type\"${if (fields.isEmpty()) "" else ",$fields"}}")
        }
        suspend fun commit(id: String) = event("input_audio_buffer.committed", "\"item_id\":\"$id\"")
        suspend fun created(id: String) = event("response.created",
            "\"response\":{\"id\":\"r$id\",\"metadata\":{\"input_item_id\":\"$id\"}}")
        suspend fun audio(id: String, chunks: Int) = event("response.output_audio.delta",
            "\"response_id\":\"r$id\",\"delta\":\"${Base64.getEncoder().encodeToString(ByteArray(chunks * 4800))}\"")
        suspend fun translated(id: String) = event("response.output_audio_transcript.done",
            "\"response_id\":\"r$id\",\"transcript\":\"translation$id\"")
        suspend fun done(id: String, status: String = "completed") = event("response.done",
            "\"response\":{\"id\":\"r$id\",\"status\":\"$status\",\"usage\":{\"input_tokens\":3,\"output_tokens\":4,\"total_tokens\":7}}")
        fun requests() = sent.filter { it.getString("type") == "response.create" }
    }
    private class Fixture(private val scope: TestScope, private val durationLimitMillis: Long? = null) {
        val wire = Wire()
        val session = AudioStreamRegistry().configure(listOf(AudioChannelDescriptor("en", "English", "en", 24000)))
        val listener = session.subscribe("en")
        val monitor = session.subscribeLocalMonitor("en")
        val segments = OpenAiAudioSegments("en", "ko", 77L)
        val captions = linkedMapOf<String, TranslationTranscriptLine>()
        val delivered = mutableListOf<String>()
        val publishedAt = mutableListOf<Long>()
        val usages = mutableListOf<OpenAiAudioEvent>()
        val terminals = mutableListOf<String>()
        val retired = NativeAudioRetiredTurns()
        var epoch = 0
        var currentSequence: Long? = null
        var cancellationEdges = 0
        var discarded = 0
        var captured = 0
        var failure: Throwable? = null
        var authorized = true
        val endedReasons = mutableListOf<NativeAudioEndReason>()
        val termination = NativeAudioTermination { reason ->
            endedReasons += reason
            val updated = terminalizeNativeAudioTranscripts(captions.values.toList(), 77L, reason)
            captions.keys.toList().zip(updated).forEach { (id, row) -> captions[id] = row }
        }
        private val output = NativeAudioOutputQueue({ event, bytes ->
            val sequence = 2_000_000_000L + requireNotNull(event.inputSequence)
            assertTrue(retired.allows(sequence))
            currentSequence = sequence
            delivered += event.inputId
            publishedAt += scope.testScheduler.currentTime
            session.publish("en", PcmAudioFrame(bytes, 0, sequence))
        }, { discarded += it }, nowNanos = { scope.testScheduler.currentTime * 1_000_000 })
        private val router = NativeAudioEventRouter(output, { event ->
            val sequence = 2_000_000_000L + requireNotNull(event.inputSequence)
            cancellationEdges++
            retired.retire(sequence)
            session.discardQueuedAudio("en", sequence)
            if (currentSequence == sequence) epoch++
        }, { if (!termination.isEnded) captions[it.inputId] = segments.accept(it, scope.testScheduler.currentTime) },
            { usages += it }, { terminals += it })
        private val publisher = scope.launch { output.run() }
        val transport = scope.launch {
            try { OpenAiAudioTransport(wire, { scope.testScheduler.currentTime }).run("synthetic", "gpt-realtime-2", "ko", "en",
                flow { while (true) { captured++; emit(ByteArray(640)); delay(20) } }, { authorized }, {}, router::accept, durationLimitMillis = durationLimitMillis)
            } catch (cancelled: CancellationException) { termination.failed(cancelled, authorized); throw cancelled }
            catch (error: Throwable) { termination.failed(error, authorized); failure = error }
            finally { termination.finish() }
        }
        fun receivedSequences() = buildList {
            while (true) add(listener.frames.tryReceive().getOrNull()?.utteranceSequence ?: break)
        }
        fun stop(reason: NativeAudioEndReason = NativeAudioEndReason.STOPPED) {
            termination.request(reason); termination.finish(); transport.cancel()
        }
        suspend fun close() { stop(); transport.join(); output.close(); publisher.cancelAndJoin(); session.close() }
    }

    @Test fun threeConsecutiveUtterancesPreserveLongUnplayedTranslationAndKeepCaptureMoving() = runTest {
        val f = Fixture(this)
        val received = mutableListOf<Long?>()
        // A connected reader drains the real bounded mailbox during paced publication.
        val reader = launch { while (true) received += f.listener.frames.receiveCatching().getOrNull()?.utteranceSequence ?: break }
        val monitorReader = launch { while (f.monitor.frames.receiveCatching().isSuccess) { /* drain */ } }
        f.wire.event("session.updated"); f.wire.commit("A"); f.wire.created("A"); f.wire.audio("A", 12)
        f.wire.event("input_audio_buffer.speech_started"); f.wire.commit("B")
        f.wire.event("input_audio_buffer.speech_started"); f.wire.commit("C")
        f.wire.event("conversation.item.input_audio_transcription.completed", "\"item_id\":\"B\",\"transcript\":\"sourceB\"")
        runCurrent()
        assertEquals(1, f.wire.requests().size)
        assertEquals(LiveOutputState.QUEUED, f.captions.getValue("B").liveOutputState)
        val capturedBefore = f.captured
        advanceTimeBy(500); runCurrent()
        assertTrue(f.captured >= capturedBefore + 24)
        assertTrue(f.wire.sent.count { it.getString("type") == "input_audio_buffer.append" } >= 4)
        assertEquals(List(6) { "A" }, f.delivered)
        assertEquals(0, f.discarded)
        f.wire.translated("A"); f.wire.done("A"); runCurrent(); assertEquals(2, f.wire.requests().size)
        f.wire.commit("B") // Duplicate commit must not issue a second translation.
        f.wire.created("B"); f.wire.audio("B", 2); f.wire.translated("B"); f.wire.done("B"); runCurrent()
        assertEquals(3, f.wire.requests().size)
        f.wire.created("C"); f.wire.audio("C", 1); f.wire.translated("C"); f.wire.done("C"); runCurrent()
        for (id in listOf("A", "B", "C")) {
            f.wire.event("conversation.item.input_audio_transcription.completed", "\"item_id\":\"$id\",\"transcript\":\"source$id\"")
        }
        advanceTimeBy(1_000); runCurrent()
        assertEquals(List(12) { "A" } + listOf("B", "B", "C"), f.delivered)
        assertEquals((0L..14L).map { it * 100L }, f.publishedAt)
        assertEquals((List(12) { 1L } + listOf(2L, 2L, 3L)).map { it + 2_000_000_000L }, received)
        assertEquals(listOf("A", "B", "C"), f.wire.requests().map {
            it.getJSONObject("response").getJSONObject("metadata").getString("input_item_id") })
        assertFalse(f.wire.sent.any { it.getString("type") == "response.cancel" })
        assertEquals(0, f.cancellationEdges); assertNull(f.failure)
        assertTrue(f.captions.values.all { it.isFinal && it.liveOutputState == LiveOutputState.GENERATED })
        for (id in listOf("A", "B", "C")) {
            assertEquals("source$id", f.captions.getValue(id).sourceText)
            assertEquals("translation$id", f.captions.getValue(id).translations["en"])
        }
        assertTrue(f.captions.values.all { it.liveOutputState!!.label.contains("청취 미확인") })
        reader.cancelAndJoin(); monitorReader.cancelAndJoin(); f.close()
    }

    @Test fun completedButUnplayedAudioSurvivesNextSpeechAndKeepsFifoOrder() = runTest {
        val f = Fixture(this)
        f.wire.event("session.updated"); f.wire.commit("A"); f.wire.created("A"); f.wire.audio("A", 4)
        f.wire.done("A"); f.wire.event("input_audio_buffer.speech_started"); f.wire.commit("B")
        f.wire.created("B"); f.wire.audio("B", 1); f.wire.done("B"); runCurrent()
        assertEquals(listOf("A"), f.delivered)
        assertEquals(LiveOutputState.GENERATED, f.captions.getValue("A").liveOutputState)
        assertFalse(f.captions.getValue("A").isFinal) // Source transcription still missing.
        assertEquals(false, f.captions.getValue("A").liveSourceFinal)
        advanceTimeBy(501); runCurrent()
        assertEquals(listOf("A", "A", "A", "A", "B"), f.delivered)
        assertEquals(0, f.discarded); assertEquals(0, f.cancellationEdges)
        assertEquals(2, f.wire.requests().size); assertNull(f.failure)
        f.close()
    }

    @Test fun lateCancelledMetadataAndAudioCannotDiscardCurrentTurnOrChangeItsEpoch() = runTest {
        val f = Fixture(this)
        f.wire.event("session.updated"); f.wire.commit("A"); f.wire.created("A"); f.wire.audio("A", 3)
        runCurrent(); assertEquals(listOf("A"), f.delivered)
        f.wire.done("A", "cancelled"); f.wire.commit("B"); f.wire.created("B"); f.wire.audio("B", 2)
        advanceTimeBy(101); runCurrent()
        assertEquals(listOf("A", "B"), f.delivered)
        val epochBeforeLateA = f.epoch
        f.wire.event("conversation.item.input_audio_transcription.completed",
            "\"event_id\":\"late-source-A\",\"item_id\":\"A\",\"transcript\":\"late\",\"usage\":{\"total_tokens\":2}")
        f.wire.event("response.done", "\"event_id\":\"late-done-A\",\"response\":{\"id\":\"rA\",\"status\":\"cancelled\",\"usage\":{\"total_tokens\":7}}")
        f.wire.audio("A", 1); f.wire.done("B"); runCurrent()
        advanceTimeBy(101); runCurrent()
        assertEquals(epochBeforeLateA, f.epoch)
        assertEquals(1, f.cancellationEdges)
        assertEquals(listOf("A", "B", "B"), f.delivered)
        assertEquals(listOf(2_000_000_002L, 2_000_000_002L), f.receivedSequences())
        assertEquals(2_000_000_002L, f.monitor.frames.tryReceive().getOrThrow().utteranceSequence)
        assertEquals(1, f.usages.count { it.inputId == "A" && it.usageKind == NativeAudioUsageKind.RESPONSE })
        assertEquals(1, f.usages.count { it.inputId == "A" && it.usageKind == NativeAudioUsageKind.TRANSCRIPTION })
        assertEquals(listOf("cancelled", "completed"), f.terminals)
        assertFalse(f.captions.getValue("A").isFinal)
        assertEquals(LiveOutputState.CANCELLED, f.captions.getValue("A").liveOutputState)
        assertFalse(f.retired.allows(2_000_000_001L)) // Even an A frame dequeued before retirement cannot be written.
        assertTrue(f.retired.allows(2_000_000_002L))
        assertEquals(9600, f.discarded); assertNull(f.failure)
        f.close()
    }

    @Test fun stalledGenerationHasBoundedVisibleOverloadWithoutUnadmittedRequestOrReplay() = runTest {
        val f = Fixture(this)
        f.wire.event("session.updated"); f.wire.commit("A"); f.wire.created("A"); runCurrent()
        advanceTimeBy(200); runCurrent(); assertTrue(f.captured >= 10)
        repeat(8) { f.wire.commit("pending$it") }; runCurrent()
        assertEquals(1, f.wire.requests().size)
        f.wire.commit("overload"); runCurrent(); f.transport.join()
        assertTrue(f.failure is NativeAudioOverload); assertTrue(f.wire.closed)
        assertEquals(1, f.wire.requests().size)
        assertFalse("overload" in f.captions)
        assertEquals(listOf(NativeAudioEndReason.OVERLOAD), f.endedReasons)
        assertEquals(9, f.captions.size)
        assertTrue(f.captions.values.all { it.liveOutputState == LiveOutputState.INCOMPLETE &&
            it.liveEndReason == NativeAudioEndReason.OVERLOAD })
        f.close()
    }

    @Test fun userStopTerminalizesActiveAndQueuedRowsButPreservesGeneratedUnplayedRow() = runTest {
        val f = Fixture(this)
        f.wire.event("session.updated"); f.wire.commit("A"); f.wire.created("A"); f.wire.audio("A", 4); f.wire.done("A")
        f.wire.commit("B"); f.wire.created("B"); f.wire.commit("C"); runCurrent()
        val completed = f.captions.getValue("A")
        val requestsBefore = f.wire.requests().size
        f.stop(); f.transport.join()
        assertSame(completed, f.captions.getValue("A"))
        assertEquals(LiveOutputState.GENERATED, completed.liveOutputState)
        assertTrue(completed.liveStatusLabel!!.contains("청취 미확인"))
        for (id in listOf("B", "C")) {
            assertEquals(LiveOutputState.CANCELLED, f.captions.getValue(id).liveOutputState)
            assertEquals(NativeAudioEndReason.STOPPED, f.captions.getValue(id).liveEndReason)
            assertTrue(f.captions.getValue(id).liveStatusLabel!!.contains("통역 중지"))
        }
        assertEquals(requestsBefore, f.wire.requests().size)
        f.close(); assertEquals(listOf(NativeAudioEndReason.STOPPED), f.endedReasons)
    }

    @Test fun timeoutLeavesNoGeneratingOrQueuedRowsAndCannotCreateWaitingRequest() = runTest {
        val f = Fixture(this, durationLimitMillis = 60_000)
        f.wire.event("session.updated"); f.wire.commit("A"); f.wire.created("A"); f.wire.commit("B"); runCurrent()
        advanceTimeBy(60_001); runCurrent(); f.transport.join()
        assertTrue(f.wire.closed)
        assertEquals(listOf(NativeAudioEndReason.TIMEOUT), f.endedReasons)
        assertEquals(1, f.wire.requests().size)
        assertTrue(f.captions.values.all { it.liveOutputState == LiveOutputState.INCOMPLETE &&
            it.liveEndReason == NativeAudioEndReason.TIMEOUT })
        f.close()
    }

    @Test fun revokedConsentCancelsOnlyUnfinishedRowsWithoutReplay() = runTest {
        val f = Fixture(this)
        f.wire.event("session.updated"); f.wire.commit("A"); f.wire.created("A"); f.wire.commit("B"); runCurrent()
        f.authorized = false; advanceTimeBy(26); runCurrent(); f.transport.join()
        assertTrue(f.wire.closed)
        assertEquals(listOf(NativeAudioEndReason.CONSENT_REVOKED), f.endedReasons)
        assertEquals(1, f.wire.requests().size)
        assertTrue(f.captions.values.all { it.liveOutputState == LiveOutputState.CANCELLED &&
            it.liveEndReason == NativeAudioEndReason.CONSENT_REVOKED })
        f.close()
    }

    @Test fun disconnectedSocketMarksPendingRowsIncompleteInsteadOfWaitingForever() = runTest {
        val f = Fixture(this)
        f.wire.event("session.updated"); f.wire.commit("A"); f.wire.created("A"); f.wire.commit("B"); runCurrent()
        f.wire.incoming.close(); runCurrent(); f.transport.join()
        assertTrue(f.wire.closed)
        assertEquals(listOf(NativeAudioEndReason.FAILURE), f.endedReasons)
        assertEquals(1, f.wire.requests().size)
        assertTrue(f.captions.values.all { it.liveOutputState == LiveOutputState.INCOMPLETE &&
            it.liveEndReason == NativeAudioEndReason.FAILURE })
        f.close()
    }

    @Test fun oldSessionCleanupPreservesOtherSessionSameSequenceAndAlreadyTerminalRows() {
        val pending = TranslationTranscriptLine(2_000_000_001L, "source", 0, translations = mapOf("en" to "partial"),
            nativeAudioSessionId = 77L, liveOutputState = LiveOutputState.QUEUED)
        val other = pending.copy(nativeAudioSessionId = 78L)
        val generated = pending.copy(liveOutputState = LiveOutputState.GENERATED)
        val cancelled = pending.copy(liveOutputState = LiveOutputState.CANCELLED, liveEndReason = NativeAudioEndReason.STOPPED)
        val local = pending.copy(nativeAudioSessionId = null, liveOutputState = null)
        val before = listOf(pending, other, generated, cancelled, local)
        val after = terminalizeNativeAudioTranscripts(before, 77L, NativeAudioEndReason.TIMEOUT)
        assertEquals(LiveOutputState.INCOMPLETE, after[0].liveOutputState)
        assertEquals(NativeAudioEndReason.TIMEOUT, after[0].liveEndReason)
        assertEquals(pending.sourceText, after[0].sourceText); assertEquals(pending.translations, after[0].translations)
        for (i in 1..4) assertSame(before[i], after[i])
        assertEquals(after, terminalizeNativeAudioTranscripts(after, 77L, NativeAudioEndReason.STOPPED))
        val endings = mutableListOf<NativeAudioEndReason>()
        val lazyTermination = NativeAudioTermination { endings += it }
        lazyTermination.request(NativeAudioEndReason.STOPPED); lazyTermination.finish()
        lazyTermination.failed(NativeAudioOverload()); lazyTermination.finish()
        assertEquals(listOf(NativeAudioEndReason.STOPPED), endings)
    }

    @Test fun generatedAndIncompleteCaptionsRemainDistinctFromAudibleSuccess() {
        val segments = OpenAiAudioSegments("en", "ko")
        for ((status, state) in listOf("queued" to LiveOutputState.QUEUED, "generating" to LiveOutputState.GENERATING,
            "completed" to LiveOutputState.GENERATED, "cancelled" to LiveOutputState.CANCELLED, "incomplete" to LiveOutputState.INCOMPLETE)) {
            val row = segments.accept(OpenAiAudioEvent(status, finished = true, status = status,
                interrupted = status in setOf("cancelled", "incomplete"), sourceFinal = true), 0)
            assertEquals(state, row.liveOutputState)
            assertEquals("확정", row.sourceStatusLabel)
            assertEquals(status == "completed", row.isFinal)
            if (status == "completed") assertTrue(state.label.contains("청취 미확인"))
        }
    }

    @Test fun sourceLabelUsesSourceFinalityAndCannotImplyRecognitionContinuesAfterTermination() {
        val base = TranslationTranscriptLine(1, "synthetic", 0, isFinal = false, liveSourceFinal = true)
        for (state in LiveOutputState.entries) assertEquals("확정", base.copy(liveOutputState = state).sourceStatusLabel)
        for (state in listOf(LiveOutputState.CANCELLED, LiveOutputState.INCOMPLETE, LiveOutputState.GENERATED))
            assertEquals("인식 미완료", base.copy(liveSourceFinal = false, liveOutputState = state).sourceStatusLabel)
        assertEquals("인식 중", base.copy(liveSourceFinal = false, liveOutputState = LiveOutputState.GENERATING).sourceStatusLabel)
        assertEquals("확정", base.copy(liveSourceFinal = null, isFinal = true).sourceStatusLabel)
        assertEquals("인식 중", base.copy(liveSourceFinal = null).sourceStatusLabel)
    }
}
