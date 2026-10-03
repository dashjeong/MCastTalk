package app.guidecast.core.translation

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class SharedTranslationBatchContextTest {
    private val targets = listOf("en", "zh", "ja", "ru")
    private val values = targets.associateWith { "translated" }

    @Test fun fourSubscribersShareOneCallAndCancellingOneDoesNotAbortLaterSubscriber() = runTest {
        val parent = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val context = SharedTranslationBatchContext("session", targets, parent, { true })
        val ready = CompletableDeferred<Unit>()
        var calls = 0
        suspend fun read() = context.await(TranslationRequestIdentity("session", 1), "same-input") {
            calls++; ready.await(); values
        }
        val first = async { read() }
        runCurrent(); first.cancelAndJoin()
        val others = List(3) { async { read() } }
        runCurrent(); ready.complete(Unit)
        assertEquals(List(3) { values }, others.awaitAll())
        assertEquals(values, read())
        assertEquals(1, calls)
        parent.cancel()
    }

    @Test fun responseLossIsCachedAndNeverAutomaticallyRetried() = runTest {
        val parent = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val context = SharedTranslationBatchContext("session", targets, parent, { true })
        var calls = 0
        repeat(4) {
            val failed = runCatching { context.await(TranslationRequestIdentity("session", 2), "same") {
                calls++; throw IllegalStateException("response lost")
            } }
            assertTrue(failed.isFailure)
        }
        assertEquals(1, calls)
        parent.cancel()
    }

    @Test fun evictionCannotReplayOldInputButSameWordsAtNewSequenceAreNewInput() = runTest {
        val context = SharedTranslationBatchContext("session", targets, this, { true }, capacity = 1)
        var calls = 0
        suspend fun read(seq: Long) = context.await(TranslationRequestIdentity("session", seq), "same words") { calls++; values }
        assertEquals(values, read(1)); assertEquals(values, read(2))
        assertTrue(runCatching { read(1) }.isFailure)
        assertEquals(2, calls)
    }

    @Test fun conflictingIdentityAndEndedInputCannotReleaseTranslations() = runTest {
        var active = true
        val context = SharedTranslationBatchContext("session", targets, this, { active })
        assertEquals(values, context.await(TranslationRequestIdentity("session", 1), "first") { values })
        assertTrue(runCatching { context.await(TranslationRequestIdentity("session", 1), "different") { values } }.isFailure)
        assertTrue(runCatching { context.await(TranslationRequestIdentity("wrong-session", 1), "first") { values } }.isFailure)
        active = false
        assertTrue(runCatching { context.await(TranslationRequestIdentity("session", 1), "first") { values } }.isFailure)
    }

    @Test fun stalledApiDoesNotBlockNextInputAndParentCancellationStopsSharedWork() = runTest {
        val parentJob = SupervisorJob()
        val parent = CoroutineScope(parentJob + StandardTestDispatcher(testScheduler))
        val context = SharedTranslationBatchContext("session", targets, parent, { true })
        var stopped = false
        val first = async { context.await(TranslationRequestIdentity("session", 1), "first") {
            try { awaitCancellation() } finally { stopped = true }
        } }
        runCurrent()
        assertEquals(values, context.await(TranslationRequestIdentity("session", 2), "second") { values })
        parentJob.cancel(); runCurrent()
        assertTrue(stopped)
        assertTrue(runCatching { first.await() }.isFailure)
    }
}
