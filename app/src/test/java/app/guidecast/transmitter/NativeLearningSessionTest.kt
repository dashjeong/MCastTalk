package app.guidecast.transmitter

import app.guidecast.core.translation.TranslationStyle
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NativeLearningSessionTest {
    private class State {
        var enabled = true; var authorized = true; var current = true
        var generation = 0L; var revision = 7L
        var readiness: NativeLearningPause? = null
        val monitor = NativeLearningMonitor()
        val calls = mutableListOf<NativeLearningPair>()
        fun result(pair: NativeLearningPair, id: Long = 1) = ShadowComparison(pair.original, null, "ko", "en", pair.corpusRevision,
            pair.translation, "Hi", TranslationStyle.CONVERSATIONAL,
            NativeComparisonIdentity(id, pair.inputId, pair.responseId, pair.sequence, 1, TranslationApiProvider.OPENAI_REALTIME,
                "gpt-realtime-2.1-mini", pair.controlGeneration))
        fun session(scope: CoroutineScope, id: Long = 1, evaluate: suspend (NativeLearningPair) -> ShadowComparison = { result(it, id) }) =
            NativeLearningSession(scope, id, monitor, { enabled }, { authorized }, { current }, { generation },
                { revision }, { readiness }) { pair -> calls += pair; evaluate(pair) }
    }
    private fun event(sequence: Long = 1) = OpenAiAudioEvent("input$sequence", "안녕", "Hello", finished = true,
        responseId = "response$sequence", status = "completed", inputSequence = sequence, sourceFinal = true, translationFinal = true)

    @Test fun acceptsOnlyExplicitlyFinalCorrelatedSuccessfulPairs() {
        val confirmed = event()
        assertTrue(nativeLearningHasConfirmedPair(confirmed))
        listOf(confirmed.copy(sourceFinal = false), confirmed.copy(translationFinal = false), confirmed.copy(finished = false),
            confirmed.copy(interrupted = true), confirmed.copy(sourceFailed = true), confirmed.copy(sourceExpired = true),
            confirmed.copy(status = "incomplete"), confirmed.copy(responseId = null), confirmed.copy(inputSequence = null),
            confirmed.copy(inputSequence = 0), confirmed.copy(inputId = "bad id"), confirmed.copy(responseId = "x".repeat(201)))
            .forEach { assertFalse(nativeLearningHasConfirmedPair(it)) }
    }

    @Test fun incompleteSnapshotsCannotBecomeAComparisonButLateFinalSourceCan() = runTest {
        val state = State(); val session = state.session(backgroundScope); runCurrent()
        session.accept(event().copy(sourceFinal = false)); session.accept(event().copy(translationFinal = false)); runCurrent()
        assertTrue(state.calls.isEmpty())
        session.accept(event()); runCurrent()
        assertEquals(1, state.calls.size); assertEquals(1L, state.monitor.state.value.completed)
        assertEquals("Hello", state.monitor.state.value.last!!.online)
        assertEquals("Hi", state.monitor.state.value.last!!.offline)
        assertEquals(7L, state.monitor.state.value.last!!.corpusRevision)
        session.close()
    }

    @Test fun reusedOnlineResultNeedsOnlyOneLocalCallAndDuplicateEventsDoNothing() = runTest {
        val state = State(); val session = state.session(backgroundScope); runCurrent()
        repeat(4) { session.accept(event()) }; runCurrent()
        assertEquals(1, state.calls.size)
        assertEquals("input1", state.calls.single().inputId); assertEquals("response1", state.calls.single().responseId)
        assertEquals(1L, state.monitor.state.value.completed)
        session.close()
    }

    @Test fun queuedLocalWorkDoesNotBlockIngressAndQueueIsBounded() = runTest {
        val state = State(); val gate = CompletableDeferred<Unit>()
        val session = state.session(backgroundScope) { gate.await(); state.result(it) }; runCurrent()
        session.accept(event(1)); runCurrent()
        session.accept(event(2)); session.accept(event(3)); session.accept(event(4))
        assertEquals(1, state.calls.size); assertEquals(2L, state.monitor.state.value.skipped)
        gate.complete(Unit); runCurrent()
        assertEquals(listOf(1L, 2L), state.calls.map { it.sequence }); assertEquals(2L, state.monitor.state.value.completed)
        session.close()
    }

    @Test fun switchOffAndBackOnCannotReviveRunningOrWaitingOldWork() = runTest {
        val state = State(); val gate = CompletableDeferred<Unit>()
        val session = state.session(backgroundScope) { withContext(NonCancellable) { gate.await() }; state.result(it) }; runCurrent()
        session.accept(event(1)); runCurrent(); session.accept(event(2))
        state.enabled = false; state.generation++; state.enabled = true; state.generation++
        advanceTimeBy(30); runCurrent(); gate.complete(Unit); runCurrent()
        assertEquals(0L, state.monitor.state.value.completed); assertNull(state.monitor.state.value.last)
        assertEquals(1, state.calls.size)
        session.accept(event(3)); runCurrent(); assertEquals(0L, state.monitor.state.value.completed)
        assertEquals(NativeLearningPause.RESTART.label, state.monitor.state.value.lastPause)
        session.close()
        val fresh = state.session(backgroundScope, id = 2); runCurrent(); fresh.accept(event(4)); runCurrent()
        assertEquals(1L, state.monitor.state.value.completed)
        advanceTimeBy(251); runCurrent(); assertNotNull(state.monitor.state.value.last)
        fresh.close()
    }

    @Test fun consentRevocationCancelsLocalWorkAndClearsTheCandidate() = runTest {
        val state = State(); val gate = CompletableDeferred<Unit>()
        val session = state.session(backgroundScope) { gate.await(); state.result(it) }; runCurrent()
        session.accept(event()); runCurrent(); state.authorized = false
        advanceTimeBy(30); runCurrent(); gate.complete(Unit); runCurrent()
        assertEquals(0L, state.monitor.state.value.completed); assertNull(state.monitor.state.value.last)
        session.accept(event(2)); runCurrent(); assertEquals(1, state.calls.size)
    }

    @Test fun closedOldSessionCannotOverwriteNewOwnerAfterLateNonCancellableCompletion() = runTest {
        val state = State(); val gate = CompletableDeferred<Unit>()
        val old = state.session(backgroundScope) { withContext(NonCancellable) { gate.await() }; state.result(it) }; runCurrent()
        old.accept(event()); runCurrent(); old.close()
        assertEquals(1L, state.monitor.state.value.attempted); assertEquals(1L, state.monitor.state.value.incomplete)
        val fresh = state.session(backgroundScope, id = 2); runCurrent(); fresh.accept(event(2)); runCurrent()
        gate.complete(Unit); runCurrent()
        assertEquals(1L, state.monitor.state.value.completed); assertEquals("Hello", state.monitor.state.value.last!!.online)
        old.close(); assertNotNull(state.monitor.state.value.last)
        fresh.close()
    }

    @Test fun staleConstructionCannotClaimANewerMonitorsOwner() {
        val monitor = NativeLearningMonitor()
        monitor.begin(2, NativeLearningPause.WAITING); monitor.begin(1, NativeLearningPause.ALIGNMENT)
        monitor.update(1) { it.copy(completed = 500) }; monitor.end(1)
        assertEquals(0L, monitor.state.value.completed)
        assertEquals(NativeLearningPause.WAITING.label, monitor.state.value.lastPause)
    }

    @Test fun alreadyInvalidSessionCanTerminateDuringImmediateCoroutineDispatch() = runTest {
        val state = State().apply { current = false }
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val session = state.session(scope)
        assertEquals(NativeLearningPause.ENDED.label, state.monitor.state.value.lastPause)
        session.accept(event()); assertTrue(state.calls.isEmpty()); session.close()
    }

    @Test fun changedCorpusCannotPublishAnOldRevisionEvenWithANonCancellableEngine() = runTest {
        val state = State(); val gate = CompletableDeferred<Unit>()
        val session = state.session(backgroundScope) { withContext(NonCancellable) { gate.await() }; state.result(it) }; runCurrent()
        session.accept(event()); runCurrent(); state.revision++
        gate.complete(Unit); runCurrent()
        assertEquals(0L, state.monitor.state.value.completed); assertNull(state.monitor.state.value.last)
        session.close()
    }

    @Test fun unpreparedOrHotDeviceSkipsOptionalComparisonWithoutCallingAnEngine() = runTest {
        val state = State().apply { readiness = NativeLearningPause.PREPARATION }
        val session = state.session(backgroundScope); runCurrent(); session.accept(event()); runCurrent()
        assertTrue(state.calls.isEmpty()); assertEquals(NativeLearningPause.PREPARATION.label, state.monitor.state.value.lastPause)
        state.readiness = NativeLearningPause.RESOURCES; advanceTimeBy(501); runCurrent()
        session.accept(event(2)); runCurrent(); assertTrue(state.calls.isEmpty())
        assertEquals(2L, state.monitor.state.value.skipped); session.close()
    }

    @Test fun hotDeviceCancelsAnOngoingComparisonWithoutPublishingItsLateResult() = runTest {
        val state = State(); val gate = CompletableDeferred<Unit>()
        val session = state.session(backgroundScope) { gate.await(); state.result(it) }; runCurrent()
        session.accept(event()); runCurrent(); state.readiness = NativeLearningPause.RESOURCES
        advanceTimeBy(525); runCurrent(); gate.complete(Unit); runCurrent()
        assertNull(state.monitor.state.value.last); assertEquals(0L, state.monitor.state.value.completed)
        session.close()
    }

    @Test fun timeoutOrFailureCannotStopLaterComparisons() = runTest {
        val state = State(); val session = state.session(backgroundScope) {
            if (it.sequence == 1L) awaitCancellation()
            if (it.sequence == 2L) error("Synthetic local failure")
            state.result(it)
        }; runCurrent(); session.accept(event(1)); runCurrent()
        advanceTimeBy(4_001); runCurrent(); session.accept(event(2)); runCurrent()
        session.accept(event(3)); runCurrent()
        assertEquals(2L, state.monitor.state.value.incomplete); assertEquals(1L, state.monitor.state.value.completed)
        session.close()
    }

    @Test fun evictionCannotReplayAnOldPaidResponseAndOutOfOrderRecentFinalsRemainSafe() = runTest {
        val state = State(); val session = state.session(backgroundScope); runCurrent()
        for (seq in 1L..150L) { session.accept(event(seq)); runCurrent() }
        session.accept(event(1)); session.accept(event(149)); runCurrent()
        assertEquals(150, state.calls.size)
        session.accept(event(152)); runCurrent(); session.accept(event(151)); runCurrent()
        assertEquals(152, state.calls.size); session.close()
    }

    @Test fun unsafeOrOversizePairsAreExcludedWholeInsteadOfClipped() = runTest {
        val state = State(); val session = state.session(backgroundScope); runCurrent()
        session.accept(event(1).copy(source = "x".repeat(501)))
        session.accept(event(2).copy(translation = "prefix" + "sk-" + "a".repeat(30)))
        session.accept(event(3).copy(source = "two\nlines"))
        session.accept(event(4).copy(translation = "bad\uD800")); runCurrent()
        assertEquals(4L, state.monitor.state.value.skipped); assertTrue(state.calls.isEmpty()); session.close()
    }

    @Test fun disabledComparisonNeverUsesACompletedOldUtteranceAfterEnabling() = runTest {
        val state = State().apply { enabled = false }; val session = state.session(backgroundScope); runCurrent()
        session.accept(event()); state.enabled = true; state.generation++
        session.accept(event()); runCurrent(); assertTrue(state.calls.isEmpty())
        session.accept(event(2)); runCurrent(); assertTrue(state.calls.isEmpty()); session.close()
        val fresh = state.session(backgroundScope, id = 2); runCurrent(); fresh.accept(event(3)); runCurrent()
        assertEquals(1, state.calls.size); fresh.close()
    }

    @Test fun identicalCaptionsCannotHideAComparisonFromTheWrongResponseIdentity() = runTest {
        val state = State(); val session = state.session(backgroundScope) { pair ->
            val correct = state.result(pair)
            correct.copy(nativeIdentity = correct.nativeIdentity!!.copy(responseId = "another-response"))
        }; runCurrent(); session.accept(event()); runCurrent()
        assertEquals(0L, state.monitor.state.value.completed); assertEquals(1L, state.monitor.state.value.incomplete)
        assertNull(state.monitor.state.value.last); session.close()
    }

    @Test fun turningOnComparisonAfterInputAdmissionCannotLearnThatEarlierSpeech() = runTest {
        val state = State().apply { enabled = false }; val session = state.session(backgroundScope); runCurrent()
        session.accept(event().copy(source = "", translation = "", finished = false, sourceFinal = false, translationFinal = false,
            responseId = null, status = "queued"))
        state.enabled = true; state.generation++
        session.accept(event()); runCurrent()
        assertTrue(state.calls.isEmpty()); assertNull(state.monitor.state.value.last); session.close()
    }

    @Test fun corpusVersionIsPinnedAtInputAdmissionBeforeEitherTranscriptFinishes() = runTest {
        val state = State(); val session = state.session(backgroundScope); runCurrent()
        session.accept(event().copy(source = "", translation = "", finished = false, sourceFinal = false, translationFinal = false,
            responseId = null, status = "queued"))
        state.revision++
        session.accept(event()); runCurrent()
        assertTrue(state.calls.isEmpty()); assertEquals(1L, state.monitor.state.value.skipped)
        assertEquals(NativeLearningPause.CHANGED.label, state.monitor.state.value.lastPause); session.close()
    }

    @Test fun cancelledOrFailedPairsHaveOneVisibleExclusionAndCannotBeRevivedByLateSuccess() = runTest {
        val state = State(); val session = state.session(backgroundScope); runCurrent()
        val rejected = event().copy(interrupted = true)
        session.accept(rejected); session.accept(rejected); session.accept(event()); runCurrent()
        assertEquals(1L, state.monitor.state.value.skipped); assertEquals(NativeLearningPause.INVALID.label, state.monitor.state.value.lastPause)
        assertTrue(state.calls.isEmpty())
        session.accept(event(2)); runCurrent(); assertEquals(1L, state.monitor.state.value.completed); session.close()
    }
}
