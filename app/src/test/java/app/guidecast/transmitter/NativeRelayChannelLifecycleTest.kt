package app.guidecast.transmitter

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class NativeRelayChannelLifecycleTest {
    private val targets = listOf("en", "ja", "zh", "ru", "vi")

    @Test fun rejectsEmptyOversizedDuplicateAndMalformedSelections() {
        listOf(emptyList(), targets + "fr", listOf("en", "en"), listOf("en", "EN"),
            listOf(""), listOf("  "), listOf(" en")).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { NativeRelayChannelLifecycle(7, invalid) }
        }
        assertEquals(targets, NativeRelayChannelLifecycle(7, targets).targets)
    }

    @Test fun callerCannotMutateTheFrozenLanguageSelection() {
        val original = targets.toMutableList()
        val lifecycle = NativeRelayChannelLifecycle(7, original)
        original.clear()
        assertEquals(targets, lifecycle.targets)
        assertThrows(UnsupportedOperationException::class.java) {
            (lifecycle.targets as MutableList<String>).clear()
        }
        assertEquals(targets, lifecycle.pendingTargets())
    }

    @Test fun pendingAndReadyInputFanoutExcludeOnlyTheFailedLanguage() {
        val lifecycle = NativeRelayChannelLifecycle(7, targets)
        assertEquals(targets, lifecycle.acceptingTargets())
        assertTrue(lifecycle.readyTargets().isEmpty())
        assertTrue(lifecycle.markReady("en"))
        assertTrue(lifecycle.markReady("en"))
        assertTrue(lifecycle.fail("ja"))
        assertFalse(lifecycle.fail("ja"))
        assertEquals(listOf("en"), lifecycle.readyTargets())
        assertEquals(listOf("zh", "ru", "vi"), lifecycle.pendingTargets())
        assertEquals(listOf("en", "zh", "ru", "vi"), lifecycle.acceptingTargets())
        assertFalse(lifecycle.accepts("ja"))
        assertFalse(lifecycle.accepts("unknown"))
        assertFalse(lifecycle.allFailed())
    }

    @Test fun failedLanguageNeverRevivesFromLateReadyWhileSiblingsStayReady() {
        val lifecycle = NativeRelayChannelLifecycle(7, targets)
        targets.forEach { assertTrue(lifecycle.markReady(it)) }
        lifecycle.fail("zh")
        repeat(10) { assertFalse(lifecycle.markReady("zh")) }
        assertEquals(listOf("en", "ja", "ru", "vi"), lifecycle.readyTargets())
        assertTrue(lifecycle.accepts("ru"))
        assertFalse(lifecycle.isClosed)
    }

    @Test fun unknownMutationCannotChangeKnownLanguageState() {
        val lifecycle = NativeRelayChannelLifecycle(7, targets)
        assertThrows(IllegalArgumentException::class.java) { lifecycle.markReady("fr") }
        assertThrows(IllegalArgumentException::class.java) { lifecycle.fail("fr") }
        assertEquals(targets, lifecycle.pendingTargets())
    }

    @Test fun closeAndConsentRevocationRetireReadyAndPendingLanguagesIdempotently() {
        for (revoke in listOf(false, true)) {
            val lifecycle = NativeRelayChannelLifecycle(7, targets)
            lifecycle.markReady("en")
            lifecycle.fail("ja")
            if (revoke) lifecycle.revokeConsent() else lifecycle.close()
            lifecycle.close()
            lifecycle.revokeConsent()
            assertTrue(lifecycle.isClosed)
            assertTrue(lifecycle.readyTargets().isEmpty())
            assertTrue(lifecycle.pendingTargets().isEmpty())
            assertTrue(lifecycle.acceptingTargets().isEmpty())
            targets.forEach { assertFalse(lifecycle.accepts(it)); assertFalse(lifecycle.markReady(it)) }
            assertFalse(lifecycle.fail("en"))
            // An intentional stop is distinct from every provider failing.
            assertFalse(lifecycle.allFailed())
        }
    }

    @Test fun retiredRunCallbacksCannotAffectANewRunWithTheSameLanguages() {
        val retired = NativeRelayChannelLifecycle(7, targets)
        val current = NativeRelayChannelLifecycle(8, targets)
        retired.close()
        targets.forEach { assertFalse(retired.markReady(it)); assertTrue(current.markReady(it)) }
        assertEquals(8L, current.sessionId)
        assertEquals(targets, current.readyTargets())
    }

    @Test fun concurrentReadyAndFailureCallbacksCannotReviveTheFailedRoute() {
        val lifecycle = NativeRelayChannelLifecycle(7, targets)
        val start = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(3)
        try {
            val callbacks = listOf(
                workers.submit { start.await(); repeat(1_000) { lifecycle.markReady("ja") } },
                workers.submit { start.await(); lifecycle.fail("ja"); repeat(1_000) { lifecycle.markReady("ja") } },
                workers.submit { start.await(); repeat(1_000) { lifecycle.markReady("en"); lifecycle.markReady("ru") } },
            )
            start.countDown()
            callbacks.forEach { it.get(10, TimeUnit.SECONDS) }
            assertFalse(lifecycle.markReady("ja"))
            assertFalse(lifecycle.accepts("ja"))
            assertEquals(listOf("en", "ru"), lifecycle.readyTargets())
            assertTrue(lifecycle.accepts("zh"))
        } finally { workers.shutdownNow() }
    }

    @Test fun readinessStartsEveryLanguageInParallelAndIsolatesOneFailure() = runTest {
        val lifecycle = NativeRelayChannelLifecycle(7, targets)
        val gates = targets.associateWith { CompletableDeferred<Unit>() }
        val entered = linkedSetOf<String>()
        val failures = mutableListOf<String>()
        val result = async { lifecycle.awaitReadyChannels(
            awaitReady = { entered += it; gates.getValue(it).await() },
            onFailure = { target, _ ->
                failures += target
                // Compatible with a caller that retires/closes its own failed connection.
                assertTrue(lifecycle.fail(target))
            },
        ) }
        runCurrent()
        assertEquals(targets.toSet(), entered)
        assertEquals(targets, lifecycle.pendingTargets())
        gates.getValue("ja").completeExceptionally(IllegalStateException("Synthetic unavailable route"))
        gates.getValue("en").complete(Unit)
        runCurrent()
        assertEquals(listOf("en"), lifecycle.readyTargets())
        assertFalse(result.isCompleted)
        listOf("zh", "ru", "vi").forEach { gates.getValue(it).complete(Unit) }
        assertEquals(listOf("en", "zh", "ru", "vi"), result.await())
        assertEquals(listOf("ja"), failures)
        assertFalse(lifecycle.markReady("ja"))
        assertFalse(lifecycle.isClosed)
    }

    @Test fun routeLocalReadinessTimeoutDoesNotCancelReadySiblings() = runTest {
        val lifecycle = NativeRelayChannelLifecycle(7, listOf("en", "ja", "ru"))
        val failures = mutableListOf<String>()
        val result = async { lifecycle.awaitReadyChannels(
            awaitReady = { tag -> if (tag == "ja") withTimeout(10) { delay(20) } },
            onFailure = { target, _ -> failures += target },
        ) }
        advanceUntilIdle()
        assertEquals(listOf("en", "ru"), result.await())
        assertEquals(listOf("ja"), failures)
        assertFalse(lifecycle.accepts("ja"))
        assertTrue(lifecycle.accepts("en"))
        assertFalse(lifecycle.isClosed)
    }

    @Test fun allFailedReadinessThrowsInsteadOfPublishingAnEmptyReadySession() = runTest {
        val lifecycle = NativeRelayChannelLifecycle(7, targets)
        val result = runCatching { lifecycle.awaitReadyChannels(
            awaitReady = { throw IllegalStateException("Synthetic unavailable route") },
        ) }
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertTrue(lifecycle.allFailed())
        assertTrue(lifecycle.isClosed)
        assertTrue(lifecycle.acceptingTargets().isEmpty())
    }

    @Test fun closeImmediatelyUnblocksPendingReadinessAndCancelsThoseWaiters() = runTest {
        val lifecycle = NativeRelayChannelLifecycle(7, targets)
        val cancelled = linkedSetOf<String>()
        val result = async { runCatching { lifecycle.awaitReadyChannels(
            awaitReady = { tag -> try { awaitCancellation() } finally { cancelled += tag } },
        ) } }
        runCurrent()
        lifecycle.close()
        runCurrent()
        assertTrue(result.await().exceptionOrNull() is IllegalStateException)
        assertEquals(targets.toSet(), cancelled)
        targets.forEach { assertFalse(lifecycle.markReady(it)); assertFalse(lifecycle.accepts(it)) }
    }

    @Test fun parentCancellationRetiresTheRunWithoutMisreportingProviderFailures() = runTest {
        val lifecycle = NativeRelayChannelLifecycle(7, targets)
        val failures = mutableListOf<String>()
        val parent = launch { lifecycle.awaitReadyChannels(
            awaitReady = { awaitCancellation() },
            onFailure = { target, _ -> failures += target },
        ) }
        runCurrent()
        parent.cancelAndJoin()
        assertTrue(lifecycle.isClosed)
        assertTrue(failures.isEmpty())
        targets.forEach { assertFalse(lifecycle.markReady(it)) }
    }

    @Test fun closedRunCannotStartAnyNewReadinessOperation() = runTest {
        val lifecycle = NativeRelayChannelLifecycle(7, targets)
        lifecycle.revokeConsent()
        var calls = 0
        val result = runCatching { lifecycle.awaitReadyChannels(awaitReady = { calls++ }) }
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertEquals(0, calls)
    }

    @Test fun fiveLanguagesDoNotCollideForTheSameProviderInputAndLateCaptions() {
        val segments = targets.mapIndexed { index, tag ->
            OpenAiAudioSegments(tag, "ko", 7, 2_000_000_000L + index * 1_000_000_000L)
        }
        val initial = segments.map { it.accept(OpenAiAudioEvent("shared-input", inputSequence = 1), 10) }
        assertEquals(5, initial.map { it.sequence }.distinct().size)
        segments.forEachIndexed { index, segment ->
            val late = segment.accept(OpenAiAudioEvent("shared-input", inputSequence = 1,
                source = "Synthetic source", translation = "Synthetic translation", sourceFinal = true), 20)
            assertEquals(initial[index].sequence, late.sequence)
            assertEquals(10L, late.capturedAtElapsedRealtimeNanos)
            assertEquals(targets[index], late.liveSegmentLanguage)
            assertEquals(7L, late.nativeAudioSessionId)
            assertEquals(mapOf(targets[index] to "Synthetic translation"), late.translations)
        }
    }

    @Test fun namespacedFallbackIdsRemainDistinctWhileDefaultSingleLanguageIdsAreCompatible() {
        val first = OpenAiAudioSegments("en", "ko")
        val second = OpenAiAudioSegments("ja", "ko", sequenceBase = 3_000_000_000L)
        assertEquals(2_000_000_000L, first.accept(OpenAiAudioEvent("first"), 10).sequence)
        assertEquals(2_000_000_001L, first.accept(OpenAiAudioEvent("second"), 20).sequence)
        assertEquals(3_000_000_000L, second.accept(OpenAiAudioEvent("first"), 10).sequence)
        assertEquals(3_000_000_001L, second.accept(OpenAiAudioEvent("second"), 20).sequence)
        assertEquals(2_000_000_000L, first.accept(OpenAiAudioEvent("first", source = "Late synthetic source"), 30).sequence)
    }
}
