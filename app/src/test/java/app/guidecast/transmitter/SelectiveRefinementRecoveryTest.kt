package app.guidecast.transmitter

import app.guidecast.core.translation.FairQueuedTranslationEngineProvider
import app.guidecast.core.translation.FairTranslationQueueConfig
import app.guidecast.core.translation.SelectiveRefinementOutcome
import app.guidecast.core.translation.SelectiveRefinementTranslationEngineProvider
import app.guidecast.core.translation.TextTranslationEngine
import app.guidecast.core.translation.TranslationEngineProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SelectiveRefinementRecoveryTest {
    @Test fun admittedTimeoutWithoutRecoveryReproducesLaterUnavailableDrafts() = runTest {
        val fixture = ReviewFixture(this, recoveryEnabled = false)
        try {
            assertEquals(DRAFT, fixture.translate())
            runCurrent()
            assertEquals(DRAFT, fixture.translate())
            assertEquals(listOf(SelectiveRefinementOutcome.REVIEW_TIMED_OUT,
                SelectiveRefinementOutcome.REVIEW_UNAVAILABLE), fixture.outcomes)
            assertEquals(1, fixture.reviewerCalls)
            assertEquals(2, fixture.draftCalls)
            assertFalse(fixture.prepared)
        } finally { fixture.close() }
    }

    @Test fun admittedCancellationRecoversEvenWhenFailureLedgerHasNothingToReset() = runTest {
        val fixture = ReviewFixture(this)
        try {
            assertEquals(DRAFT, fixture.translate())
            runCurrent()
            // FairQueue may invalidate the provider after the first timeout diagnostic returns.
            assertEquals(DRAFT, fixture.translate())
            runCurrent()
            assertEquals(1, fixture.resetCalls)
            assertEquals(0, fixture.ledgerCleanups)
            assertEquals(0, fixture.warmupCalls)
            assertFalse(fixture.prepared)
            fixture.nativeTerminal.complete(Unit)
            runCurrent()
            assertEquals(1, fixture.warmupCalls)
            assertEquals(listOf(SelectiveRefinementRecoveryResult.RECOVERED), fixture.recoveries)
            assertEquals(REVIEWED, fixture.translate())
            assertEquals(SelectiveRefinementOutcome.REVIEW_ACCEPTED, fixture.outcomes.last())
            assertEquals(3, fixture.draftCalls)
            assertEquals(2, fixture.reviewerCalls)
        } finally { fixture.close() }
    }

    @Test fun ordinaryFailureAlsoRecoversAfterFailedGenerationCleanup() = runTest {
        val fixture = ReviewFixture(this, failWithException = true)
        try {
            assertEquals(DRAFT, fixture.translate())
            runCurrent()
            assertEquals(SelectiveRefinementOutcome.REVIEW_FAILED, fixture.outcomes.single())
            assertEquals(1, fixture.resetCalls)
            assertEquals(1, fixture.ledgerCleanups)
            fixture.nativeTerminal.complete(Unit)
            runCurrent()
            assertEquals(REVIEWED, fixture.translate())
            assertEquals(1, fixture.warmupCalls)
        } finally { fixture.close() }
    }

    @Test fun fiveLanguageFailuresCoalesceAndASecondFailureDoesNotCreateAWarmupLoop() = runTest {
        val terminal = CompletableDeferred<Unit>()
        var prepared = false
        var attempts = 0
        val recovery = SelectiveRefinementRecovery(this, { true }, { prepared }, {
            attempts++
            terminal.await()
            prepared = true
        })
        repeat(5) { recovery.onDiagnostic(SelectiveRefinementOutcome.REVIEW_FAILED) }
        runCurrent()
        assertEquals(1, attempts)
        terminal.complete(Unit)
        runCurrent()
        prepared = false
        repeat(5) { recovery.onDiagnostic(SelectiveRefinementOutcome.REVIEW_TIMED_OUT) }
        runCurrent()
        assertEquals(1, attempts)
        recovery.close()
    }

    @Test fun initialUnavailableRejectionAndPreparedQueueTimeoutDoNotReinitialize() = runTest {
        var prepared = false
        var attempts = 0
        val recovery = SelectiveRefinementRecovery(this, { true }, { prepared }, { attempts++ })
        recovery.onDiagnostic(SelectiveRefinementOutcome.REVIEW_UNAVAILABLE)
        recovery.onDiagnostic(SelectiveRefinementOutcome.REVIEW_REJECTED)
        runCurrent()
        assertEquals(0, attempts)
        prepared = true
        recovery.onDiagnostic(SelectiveRefinementOutcome.REVIEW_TIMED_OUT)
        runCurrent()
        assertEquals(0, attempts)
        recovery.close()
    }

    @Test fun workerPreparedBeforeScheduledRecoveryKeepsTheOneAttemptForALaterFailure() = runTest {
        var prepared = false
        var cleanups = 0
        var attempts = 0
        val results = mutableListOf<SelectiveRefinementRecoveryResult>()
        val recovery = SelectiveRefinementRecovery(
            scope = this,
            canRecover = { true },
            isPrepared = { prepared },
            cleanupLatestFailure = { cleanups++; false },
            recover = { attempts++; prepared = true },
            onResult = results::add,
        )
        repeat(5) { recovery.onDiagnostic(SelectiveRefinementOutcome.REVIEW_TIMED_OUT) }
        // A different already-running request succeeds before the lazy recovery body runs.
        prepared = true
        runCurrent()
        assertEquals(0, cleanups)
        assertEquals(0, attempts)
        assertTrue(results.isEmpty())

        prepared = false
        repeat(5) { recovery.onDiagnostic(SelectiveRefinementOutcome.REVIEW_FAILED) }
        runCurrent()
        assertEquals(1, cleanups)
        assertEquals(1, attempts)
        assertEquals(listOf(SelectiveRefinementRecoveryResult.RECOVERED), results)
        prepared = false
        recovery.onDiagnostic(SelectiveRefinementOutcome.REVIEW_TIMED_OUT)
        runCurrent()
        assertEquals(1, attempts)
        recovery.close()
    }

    @Test fun lateCancellationInvalidationCanRecoverOnNextUnavailableDiagnostic() = runTest {
        var prepared = true
        var attempts = 0
        val recovery = SelectiveRefinementRecovery(this, { true }, { prepared }, {
            attempts++
            prepared = true
        })
        recovery.onDiagnostic(SelectiveRefinementOutcome.REVIEW_TIMED_OUT)
        runCurrent()
        assertEquals(0, attempts)
        prepared = false
        recovery.onDiagnostic(SelectiveRefinementOutcome.REVIEW_UNAVAILABLE)
        runCurrent()
        assertEquals(1, attempts)
        assertTrue(prepared)
        recovery.close()
    }

    @Test fun disallowedRecoveryCannotStartOrBypassTheAdmissionGuard() = runTest {
        var attempts = 0
        val recovery = SelectiveRefinementRecovery(this, { false }, { false }, { attempts++ })
        recovery.onDiagnostic(SelectiveRefinementOutcome.REVIEW_FAILED)
        recovery.onDiagnostic(SelectiveRefinementOutcome.REVIEW_UNAVAILABLE)
        runCurrent()
        assertEquals(0, attempts)
        recovery.close()
    }

    @Test fun eligibilityIsRecheckedWhenScheduledWorkActuallyRuns() = runTest {
        var allowed = true
        var attempts = 0
        val recovery = SelectiveRefinementRecovery(this, { allowed }, { false }, { attempts++ })
        recovery.onDiagnostic(SelectiveRefinementOutcome.REVIEW_FAILED)
        allowed = false
        runCurrent()
        assertEquals(0, attempts)
        recovery.close()
    }

    @Test fun closeCancelsPendingAdmissionWithoutWarmupOrSuccess() = runTest {
        val terminal = CompletableDeferred<Unit>()
        var warmups = 0
        val results = mutableListOf<SelectiveRefinementRecoveryResult>()
        val recovery = SelectiveRefinementRecovery(this, { true }, { false }, {
            terminal.await()
            warmups++
        }, results::add)
        recovery.onDiagnostic(SelectiveRefinementOutcome.REVIEW_TIMED_OUT)
        runCurrent()
        recovery.close()
        terminal.complete(Unit)
        runCurrent()
        assertEquals(0, warmups)
        assertTrue(results.isEmpty())
    }

    @Test fun lateNonCancellableNativeCompletionCannotPublishAfterClose() = runTest {
        val terminal = CompletableDeferred<Unit>()
        val results = mutableListOf<SelectiveRefinementRecoveryResult>()
        val recovery = SelectiveRefinementRecovery(this, { true }, { false }, {
            withContext(NonCancellable) { terminal.await() }
        }, results::add)
        recovery.onDiagnostic(SelectiveRefinementOutcome.REVIEW_FAILED)
        runCurrent()
        recovery.close()
        terminal.complete(Unit)
        runCurrent()
        assertTrue(results.isEmpty())
    }

    @Test fun unsuccessfulWarmupCannotDeclareReadinessOrAutomaticallyRepeat() = runTest {
        var attempts = 0
        val results = mutableListOf<SelectiveRefinementRecoveryResult>()
        val recovery = SelectiveRefinementRecovery(this, { true }, { false }, {
            attempts++
            error("warmup failed")
        }, results::add)
        recovery.onDiagnostic(SelectiveRefinementOutcome.REVIEW_FAILED)
        runCurrent()
        recovery.onDiagnostic(SelectiveRefinementOutcome.REVIEW_UNAVAILABLE)
        runCurrent()
        assertEquals(1, attempts)
        assertEquals(listOf(SelectiveRefinementRecoveryResult.FAILED), results)
        recovery.close()
    }

    @Test fun backgroundWaitIsBoundedAndDoesNotReportPreparedWithoutProof() = runTest {
        val results = mutableListOf<SelectiveRefinementRecoveryResult>()
        val recovery = SelectiveRefinementRecovery(this, { true }, { false }, {
            awaitCancellation()
        }, results::add, maximumRecoveryMillis = 1_000L)
        recovery.onDiagnostic(SelectiveRefinementOutcome.REVIEW_TIMED_OUT)
        runCurrent()
        advanceTimeBy(1_001L)
        runCurrent()
        assertEquals(listOf(SelectiveRefinementRecoveryResult.TIMED_OUT), results)
        recovery.close()
    }

    @Test fun returningFromWarmupWithoutProviderReadinessIsNotSuccess() = runTest {
        val results = mutableListOf<SelectiveRefinementRecoveryResult>()
        val recovery = SelectiveRefinementRecovery(this, { true }, { false }, {}, results::add)
        recovery.onDiagnostic(SelectiveRefinementOutcome.REVIEW_FAILED)
        runCurrent()
        assertEquals(listOf(SelectiveRefinementRecoveryResult.FAILED), results)
        recovery.close()
    }

    /** Simulated native worker, but real selective wrapper, fair queue and first-use gate. */
    private class ReviewFixture(
        scope: TestScope,
        recoveryEnabled: Boolean = true,
        private val failWithException: Boolean = false,
    ) : AutoCloseable {
        var prepared = true
        var draftCalls = 0
        var reviewerCalls = 0
        var resetCalls = 0
        var ledgerCleanups = 0
        var warmupCalls = 0
        val nativeTerminal = CompletableDeferred<Unit>()
        val outcomes = mutableListOf<SelectiveRefinementOutcome>()
        val recoveries = mutableListOf<SelectiveRefinementRecoveryResult>()
        private val gate = NativeFirstUseGate(
            maxParallelInitializations = 1,
            isInitializationCurrent = { prepared },
            beforeFirstUse = { _, _ ->
                if (!prepared) nativeTerminal.await()
                null
            },
        )
        private val fair = FairQueuedTranslationEngineProvider(
            delegate = TranslationEngineProvider {
                TextTranslationEngine { _, _, _ ->
                    reviewerCalls++
                    if (reviewerCalls == 1) {
                        try {
                            if (failWithException) error("native inference failed")
                            awaitCancellation()
                        } finally { prepared = false }
                    }
                    REVIEWED
                }
            }.withNativeFirstUseGate(gate, "gemma"),
            parentScope = scope,
            config = FairTranslationQueueConfig(maxPendingPerLanguage = 2,
                queueWaitTimeoutMillis = 30_000L, inferenceTimeoutMillis = 15_000L),
        )
        private val recovery = SelectiveRefinementRecovery(
            scope = scope,
            canRecover = { true },
            isPrepared = { prepared },
            cleanupLatestFailure = {
                resetCalls++
                // Mirrors the provider's separate failed-generation ledger: cancellation has none.
                if (failWithException) ledgerCleanups++
                failWithException
            },
            recover = {
                gate.initialize("gemma:en") {
                    warmupCalls++
                    prepared = true
                }
            },
            onResult = recoveries::add,
        )
        private val provider = SelectiveRefinementTranslationEngineProvider(
            draftProvider = TranslationEngineProvider {
                TextTranslationEngine { _, _, _ -> draftCalls++; DRAFT }
            },
            reviewerProvider = fair,
            reviewerAvailable = { prepared },
            onDiagnostic = {
                outcomes += it.outcome
                if (recoveryEnabled) recovery.onDiagnostic(it.outcome)
            },
        )

        suspend fun translate(): String = provider.engineFor("en").translate("3번 출구로 가세요.", "ko", "en")
        override fun close() { recovery.close(); fair.close() }
    }

    private companion object {
        const val DRAFT = "Go to exit 3."
        const val REVIEWED = "Please use exit 3."
    }
}
