package app.guidecast.transmitter

import app.guidecast.core.translation.TranslationStyle
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NativeComparisonReviewAdmissionTest {
    private class State {
        var relay = InterpreterRelayOptions(compareOffline = true)
        var generation = 3L; var authorized = true; var revision = 7L
        var sessionCurrent = true
        var api = TranslationApiOptions(provider = TranslationApiProvider.OPENAI_REALTIME,
            model = "gpt-realtime-2.1-mini", realtimeAudio = true, allowLiveAudio = true,
            allowOnline = true, hasKey = true, revision = 8)
        val candidate = ShadowComparison("안녕", null, "ko", "en", 7, "Hello", "Hi", TranslationStyle.CONVERSATIONAL,
            NativeComparisonIdentity(11, "input1", "response1", 1, 8, api.provider, api.model, 3))
        val monitor = NativeLearningMonitor()
        val relayLock = Any(); val apiLock = Any()
        init { monitor.begin(11, NativeLearningPause.WAITING, 3) { sessionCurrent }; monitor.update(11) { it.copy(last = candidate) } }
        fun current(c: ShadowComparison = candidate) = nativeComparisonIsCurrent(c, monitor.ownsCandidate(c), relay,
            generation, api, authorized, revision)
        fun admission() = NativeComparisonCommitAdmission(listOf(monitor.reviewAdmissionLock, relayLock, apiLock)) { current() }
    }

    @Test fun completedCandidateIsRejectedImmediatelyAfterOffOnBeforeAnyWatcherRuns() {
        val state = State(); assertTrue(state.current())
        state.relay = state.relay.copy(compareOffline = false); state.generation++
        state.relay = state.relay.copy(compareOffline = true); state.generation++
        assertSame(state.candidate, state.monitor.state.value.last)
        assertFalse(state.current()); var writes = 0
        assertThrows(IllegalStateException::class.java) { state.admission().commit { writes++ } }
        assertEquals(0, writes)
    }

    @Test fun providerModelSettingsLanguageStyleAndCorpusMustMatchAtApproval() {
        val changes = listOf<(State) -> Unit>(
            { it.api = it.api.copy(revision = 9) }, { it.api = it.api.copy(model = "gpt-realtime-2") },
            { it.api = it.api.copy(provider = TranslationApiProvider.GEMINI_LIVE, model = GEMINI_LIVE_AGENT) },
            { it.relay = it.relay.copy(source = "ja") }, { it.relay = it.relay.copy(target = "ja") },
            { it.api = it.api.copy(tone = TranslationStyle.FORMAL) }, { it.revision++ },
            { it.authorized = false }, { it.api = it.api.copy(allowLiveAudio = false) })
        for (change in changes) { val state = State(); change(state); assertFalse(state.current()) }
    }

    @Test fun endedOrReplacedSessionCannotApproveAnOldCandidate() {
        val ended = State(); ended.monitor.end(11); assertFalse(ended.current())
        val replaced = State(); replaced.monitor.begin(12, NativeLearningPause.WAITING, 3)
        replaced.monitor.update(12) { it.copy(last = replaced.candidate) }; assertFalse(replaced.current())
        val retiring = State(); retiring.sessionCurrent = false
        assertSame(retiring.candidate, retiring.monitor.state.value.last); assertFalse(retiring.current())
    }

    @Test fun changeWhileWaitingForDatabaseAdmissionPreventsEveryActivationWrite() = runTest {
        val changes = listOf<(State) -> Unit>(
            { it.relay = it.relay.copy(compareOffline = false); it.generation++ },
            { it.authorized = false }, { it.monitor.end(11) }, { it.api = it.api.copy(revision = 9) })
        for (change in changes) {
            val state = State(); val databaseQueue = Mutex(); databaseQueue.lock(); var writes = 0
            val result = CompletableDeferred<Result<Unit>>()
            assertTrue(state.current())
            val task = launch { databaseQueue.withLock { result.complete(runCatching { state.admission().commit { writes++ } }) } }
            runCurrent(); assertTrue(task.isActive)
            change(state); databaseQueue.unlock(); runCurrent()
            assertTrue(result.await().isFailure); assertEquals(0, writes); task.join()
        }
    }

    @Test fun finalActivationHoldsAllMutationLocksAndAllowsACurrentReviewedCandidate() {
        val state = State(); var writes = 0
        state.admission().commit {
            assertTrue(Thread.holdsLock(state.monitor.reviewAdmissionLock)); assertFalse(Thread.holdsLock(state.monitor))
            assertTrue(Thread.holdsLock(state.relayLock))
            assertTrue(Thread.holdsLock(state.apiLock)); writes++
        }
        assertEquals(1, writes)
    }

    @Test fun failedActivationReleasesControlLocksAndDoesNotAuthorizeALaterStaleRetry() {
        val state = State()
        assertThrows(IllegalArgumentException::class.java) { state.admission().commit { throw IllegalArgumentException("Synthetic write failure") } }
        assertFalse(Thread.holdsLock(state.monitor.reviewAdmissionLock)); assertFalse(Thread.holdsLock(state.relayLock)); assertFalse(Thread.holdsLock(state.apiLock))
        synchronized(state.apiLock) { state.authorized = false }
        assertThrows(IllegalStateException::class.java) { state.admission().commit { fail("Stale retry") } }
    }
    @Test fun ordinaryComparisonCountersAreNotLockedByDatabaseActivation() {
        val state = State(); val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try { state.admission().commit {
            executor.submit { state.monitor.update(11) { it.copy(attempted = it.attempted + 1) } }
                .get(2, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(1L, state.monitor.state.value.attempted)
        } } finally { executor.shutdownNow() }
    }
}
