package app.guidecast.provider.mlkit.translation

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MlKitWorkerIsolationTest {
    @Test
    fun nativeWarmupUsesShortNonBlankTextForEverySupportedSource() {
        setOf("ar", "en", "es", "ja", "ko", "zh").forEach { source ->
            val warmup = mlKitWarmupSourceText(source)
            assertTrue(warmup.isNotBlank())
            assertTrue(warmup.length <= 16)
        }
    }

    @Test
    fun transferredColdLoadTicketIsNeverReusedForTransportRetry() {
        assertEquals(1, mlKitWorkerConnectionAttempts(hasNativeColdLoadTicket = true))
        assertEquals(2, mlKitWorkerConnectionAttempts(hasNativeColdLoadTicket = false))
    }

    @Test
    fun deathLinkRequiresBothClientAndTransferredNativeTerminalStates() {
        assertFalse(
            mlKitRequestMayUnlinkDeathRecipient(
                clientFinished = false,
                nativeSubmissionPlanned = true,
                nativeFinished = true,
            ),
        )
        assertFalse(
            mlKitRequestMayUnlinkDeathRecipient(
                clientFinished = true,
                nativeSubmissionPlanned = true,
                nativeFinished = false,
            ),
        )
        assertTrue(
            mlKitRequestMayUnlinkDeathRecipient(
                clientFinished = true,
                nativeSubmissionPlanned = true,
                nativeFinished = true,
            ),
        )
    }

    @Test
    fun connectedWorkerIsNotPreparedUntilThatExactGenerationCompletesNativeWork() {
        val prepared = PreparedWorkerGenerations<String, Any>()
        val firstGeneration = Any()
        val replacementGeneration = Any()

        assertFalse(prepared.isCurrent("en", firstGeneration))

        prepared.markCurrent("en", firstGeneration)
        assertTrue(prepared.isCurrent("en", firstGeneration))
        assertFalse(prepared.isCurrent("en", replacementGeneration))
        assertFalse(prepared.isCurrent("en", firstGeneration))
    }

    @Test
    fun staleWorkerFailureCannotClearReplacementPreparedGeneration() {
        val prepared = PreparedWorkerGenerations<String, Any>()
        val staleGeneration = Any()
        val replacementGeneration = Any()

        prepared.markCurrent("en", staleGeneration)
        prepared.markCurrent("en", replacementGeneration)
        prepared.invalidate("en", staleGeneration)

        assertTrue(prepared.isCurrent("en", replacementGeneration))
        prepared.invalidate("en", replacementGeneration)
        assertFalse(prepared.isCurrent("en", replacementGeneration))
    }

    @Test
    fun sessionResetClearsEveryPreparedWorkerGeneration() {
        val prepared = PreparedWorkerGenerations<String, Any>()
        val englishGeneration = Any()
        val japaneseGeneration = Any()
        prepared.markCurrent("en", englishGeneration)
        prepared.markCurrent("ja", japaneseGeneration)

        prepared.clear()

        assertFalse(prepared.isCurrent("en", englishGeneration))
        assertFalse(prepared.isCurrent("ja", japaneseGeneration))
    }

    @Test
    fun existingLeaseInspectionNeverAllocatesANewWorkerSlot() {
        val slots = MlKitWorkerSlotAllocator(slotCount = 2)

        assertNull(slots.existingLeaseFor("en"))
        val japanese = slots.leaseFor("ja")
        assertNull(slots.existingLeaseFor("en"))
        assertEquals(japanese, slots.existingLeaseFor("ja"))
        assertEquals(1, slots.snapshot().size)
    }

    @Test
    fun fiveLanguagesReceiveStableExclusiveSlotsUntilSessionReset() {
        val slots = MlKitWorkerSlotAllocator(slotCount = 5)
        val languages = listOf("en", "ja", "zh", "nl", "ar")

        val first = languages.associateWith { slots.leaseFor(it).slot }
        val second = languages.reversed().associateWith { slots.leaseFor(it).slot }

        assertEquals(5, first.values.toSet().size)
        languages.forEach { language -> assertEquals(first[language], second[language]) }
        assertTrue(
            runCatching { slots.leaseFor("es") }
                .exceptionOrNull() is IllegalStateException,
        )

        val japaneseSlot = first.getValue("ja")
        val retirement = requireNotNull(slots.beginRetirement("ja"))
        assertEquals(japaneseSlot, retirement.lease.slot)
        assertTrue(slots.completeRetirement(retirement))
        assertFalse(slots.completeRetirement(retirement))
        assertEquals(japaneseSlot, slots.leaseFor("es").slot)

        slots.beginReset()
        slots.completeReset(reopen = true)
        assertTrue(slots.snapshot().isEmpty())
        assertEquals(0, slots.leaseFor("es").slot)
    }

    @Test
    fun sevenLanguagesReceiveDistinctSlotsAndAnEighthIsRejected() {
        val slots = MlKitWorkerSlotAllocator(slotCount = 7)
        val languages = listOf("en", "ja", "zh", "zh-TW", "vi", "nl", "es")

        val leases = languages.associateWith(slots::leaseFor)

        assertEquals(7, leases.values.map { it.slot }.toSet().size)
        assertTrue(
            runCatching { slots.leaseFor("ar") }
                .exceptionOrNull() is IllegalStateException,
        )
        assertEquals(MlKitInferenceService6::class.java, MlKitInferenceServices.forSlot(6))
        assertTrue(
            runCatching { MlKitInferenceServices.forSlot(7) }
                .exceptionOrNull() is IndexOutOfBoundsException,
        )
    }

    @Test
    fun manifestDeclaresSevenPrivateWorkerProcesses() {
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()

        (0..6).forEach { slot ->
            assertTrue(manifest.contains("android:name=\".MlKitInferenceService$slot\""))
            assertTrue(manifest.contains("android:process=\":mlkit_translate_$slot\""))
        }
        assertEquals(7, Regex("android:exported=\"false\"").findAll(manifest).count())
    }

    @Test
    fun oneHungNativeLaneDoesNotBlockFourOtherLanguageLanes() = runBlocking {
        val lanes = List(5) { NativeOperationCoordinator(maxInFlightOperations = 2) }
        val hungStarted = CompletableDeferred<Unit>()
        val releaseHung = CompletableDeferred<Unit>()
        val hungCaller = launch {
            lanes[0].run {
                hungStarted.complete(Unit)
                releaseHung.await()
            }
        }
        hungStarted.await()

        val healthyCompleted = List(4) { index ->
            async { lanes[index + 1].run { "healthy-${index + 1}" } }
        }
        val results = withTimeout(1_000L) { healthyCompleted.map { it.await() } }

        assertEquals(listOf("healthy-1", "healthy-2", "healthy-3", "healthy-4"), results)
        assertFalse(hungCaller.isCompleted)
        releaseHung.complete(Unit)
        withTimeout(1_000L) { hungCaller.join() }
        lanes.forEach { it.closeAndJoin() }
    }

    @Test
    fun retirementWindowRejectsOldLanguageAndPreservesSiblingLease() {
        val slots = MlKitWorkerSlotAllocator(slotCount = 5)
        val english = slots.leaseFor("en")
        val japanese = slots.leaseFor("ja")
        val retirementStarted = CountDownLatch(1)
        val allowDisconnectToFinish = CountDownLatch(1)
        lateinit var retirement: MlKitWorkerSlotRetirement
        val retiringThread = Thread {
            retirement = requireNotNull(slots.beginRetirement("en"))
            retirementStarted.countDown()
            assertTrue(allowDisconnectToFinish.await(1, TimeUnit.SECONDS))
            assertTrue(slots.completeRetirement(retirement))
        }

        retiringThread.start()
        assertTrue(retirementStarted.await(1, TimeUnit.SECONDS))
        assertFalse(slots.isActive(english))
        assertTrue(slots.isActive(japanese))
        assertTrue(runCatching { slots.leaseFor("en") }.isFailure)
        assertEquals("ja", slots.leaseFor("ja").languageTag)

        allowDisconnectToFinish.countDown()
        retiringThread.join(1_000L)
        assertFalse(retiringThread.isAlive)
        val spanish = slots.leaseFor("es")
        assertEquals(english.slot, spanish.slot)
        assertTrue(slots.isActive(spanish))
        assertFalse(slots.isActive(english))
    }

    @Test
    fun sessionReleaseInvalidatesOldLeaseButRetainsOwnershipUntilModelRemovalDrain() {
        val slots = MlKitWorkerSlotAllocator(slotCount = 2)
        val staleEnglish = slots.leaseFor("en")
        val japanese = slots.leaseFor("ja")

        slots.invalidateLeasesPreservingAssignments()

        assertFalse(slots.isActive(staleEnglish))
        assertFalse(slots.isActive(japanese))
        assertEquals(mapOf("en" to staleEnglish.slot, "ja" to japanese.slot), slots.snapshot())
        val freshEnglish = slots.leaseFor("en")
        assertEquals(staleEnglish.slot, freshEnglish.slot)
        assertTrue(freshEnglish.generation > staleEnglish.generation)

        // Immediate model removal still sees and retires the exact owned slot. It cannot treat
        // release as proof that a one-way worker shutdown has already closed native model files.
        val removal = requireNotNull(slots.beginRetirement("en"))
        assertFalse(slots.isActive(freshEnglish))
        assertTrue(slots.isRetiring(removal))
        assertTrue(slots.completeRetirement(removal))
        assertFalse(slots.snapshot().containsKey("en"))
        assertEquals(staleEnglish.slot, slots.leaseFor("es").slot)
    }

    @Test
    fun nextAlreadyReadySelectionCanReplaceObsoleteAssignmentWithoutExhaustingFiveSlots() {
        val slots = MlKitWorkerSlotAllocator(slotCount = 5)
        listOf("en", "ja", "zh", "nl", "es").forEach(slots::leaseFor)
        slots.invalidateLeasesPreservingAssignments()

        val nextSelection = setOf("en", "ja", "zh", "es", "ar")
        val obsolete = slots.beginRetirementsExcept(nextSelection)

        assertEquals(listOf("nl"), obsolete.map { it.lease.languageTag })
        assertTrue(slots.completeRetirement(obsolete.single()))
        slots.leaseFor("ar")
        assertEquals(nextSelection, slots.snapshot().keys)
        assertEquals(5, slots.snapshot().values.toSet().size)
    }

    @Test
    fun releasedA5ToLiveB5SwapUsesCurrentOwnerGenerationAndPreservesRetainedSlots() = runBlocking {
        val slots = MlKitWorkerSlotAllocator(slotCount = 5)
        val firstSelection = listOf("en", "ja", "zh", "nl", "es")
        val firstSlots = firstSelection.associateWith { slots.leaseFor(it).slot }
        slots.activatePreparationGeneration(51L)
        slots.invalidateLeasesPreservingAssignments()
        slots.activatePreparationGeneration(52L)
        val session = slots.captureSessionGeneration()
        val replacementSelection = setOf("en", "ja", "zh", "es", "ar")

        val retirements = requireNotNull(
            slots.beginRetirementsExcept(replacementSelection, 52L),
        )

        assertEquals(listOf("nl"), retirements.map { it.lease.languageTag })
        replacementSelection.intersect(firstSelection.toSet()).forEach { retained ->
            assertEquals(firstSlots.getValue(retained), slots.awaitLeaseFor(retained, session, 52L).slot)
        }
        assertTrue(slots.completeRetirement(retirements.single()))
        val arabic = slots.awaitLeaseFor("ar", session, 52L)
        assertEquals(firstSlots.getValue("nl"), arabic.slot)
        assertEquals(replacementSelection, slots.snapshot().keys)
    }

    @Test
    fun newReadyTargetWaitsForOnlyItsRetiringSlotWhileRetainedTargetsStayActive() = runBlocking {
        val slots = MlKitWorkerSlotAllocator(slotCount = 5)
        listOf("en", "ja", "zh", "nl", "es").forEach(slots::leaseFor)
        slots.invalidateLeasesPreservingAssignments()
        val session = slots.captureSessionGeneration()
        val retirements = slots.beginRetirementsExcept(setOf("en", "ja", "zh", "es", "ar"))
        val retainedEnglish = slots.awaitLeaseFor("en", session)
        val waitingArabic = async { slots.awaitLeaseFor("ar", session) }

        delay(40L)
        assertFalse(waitingArabic.isCompleted)
        assertTrue(slots.isActive(retainedEnglish))
        assertTrue(slots.completeRetirement(retirements.single()))

        val arabic = withTimeout(1_000L) { waitingArabic.await() }
        assertEquals(retirements.single().lease.slot, arabic.slot)
        assertTrue(slots.isActive(arabic))
    }

    @Test
    fun waitingEngineFromStoppedSessionCannotClaimALaterFreedSlot() = runBlocking {
        val slots = MlKitWorkerSlotAllocator(slotCount = 1)
        slots.leaseFor("en")
        val staleSession = slots.captureSessionGeneration()
        val waiting = async { runCatching { slots.awaitLeaseFor("ja", staleSession) } }
        delay(20L)

        slots.invalidateLeasesPreservingAssignments()

        val result = withTimeout(1_000L) { waiting.await() }
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun broadcastGenerationWakesAndRejectsASettingsWaiterWithoutWaitingForNativeWork() = runBlocking {
        val slots = MlKitWorkerSlotAllocator(slotCount = 1)
        slots.activatePreparationGeneration(10L)
        slots.leaseFor("en")
        val session = slots.captureSessionGeneration()
        val staleSettings = async {
            runCatching {
                slots.awaitLeaseFor(
                    languageTag = "ja",
                    expectedSessionGeneration = session,
                    expectedPreparationGeneration = 10L,
                )
            }
        }
        delay(20L)
        assertFalse(staleSettings.isCompleted)

        slots.activatePreparationGeneration(11L)

        val result = withTimeout(1_000L) { staleSettings.await() }
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertEquals(mapOf("en" to 0), slots.snapshot())
    }

    @Test
    fun staleSettingsReconciliationCannotRetireBroadcastTargets() {
        val slots = MlKitWorkerSlotAllocator(slotCount = 5)
        listOf("en", "ja", "zh", "nl", "es").forEach(slots::leaseFor)
        slots.activatePreparationGeneration(21L)

        val stale = slots.beginRetirementsExcept(setOf("ar"), 20L)
        val current = slots.beginRetirementsExcept(setOf("en", "ja", "zh", "nl", "es"), 21L)

        assertNull(stale)
        assertEquals(emptyList<MlKitWorkerSlotRetirement>(), current)
        assertEquals(setOf("en", "ja", "zh", "nl", "es"), slots.snapshot().keys)
    }

    @Test
    fun completedWarmIsRejectedAfterItsPreparationGenerationIsSuperseded() = runBlocking {
        val slots = MlKitWorkerSlotAllocator(slotCount = 1)
        slots.activatePreparationGeneration(30L)
        val session = slots.captureSessionGeneration()
        val lease = slots.awaitLeaseFor("en", session, 30L)
        assertTrue(slots.isActiveForPreparation(lease, 30L))

        slots.activatePreparationGeneration(31L)

        assertFalse(slots.isActiveForPreparation(lease, 30L))
        assertTrue(slots.isActive(lease))
    }

    @Test
    fun assignedTargetCannotReopenUntilModelDeleteClosureActuallyCompletes() = runBlocking {
        val slots = MlKitWorkerSlotAllocator(slotCount = 2)
        val original = slots.leaseFor("en")
        val sibling = slots.leaseFor("ja")
        val session = slots.captureSessionGeneration()
        val deleteEntered = CompletableDeferred<Unit>()
        val allowDeleteToFinish = CompletableDeferred<Unit>()
        val removal = async {
            runReservedModelRemoval(
                reserve = { slots.awaitTargetRemoval("en") },
                drain = {},
                delete = {
                    deleteEntered.complete(Unit)
                    allowDeleteToFinish.await()
                },
                complete = { check(slots.completeTargetRemoval(it)) },
                recover = { check(slots.cancelTargetRemoval(it)) },
            )
        }
        deleteEntered.await()
        val waitingEnglish = async { slots.awaitLeaseFor("en", session) }

        delay(40L)
        assertFalse(waitingEnglish.isCompleted)
        assertFalse(slots.isActive(original))
        assertTrue(slots.isActive(sibling))

        allowDeleteToFinish.complete(Unit)
        withTimeout(1_000L) { removal.await() }
        val replacement = withTimeout(1_000L) { waitingEnglish.await() }
        assertEquals(original.slot, replacement.slot)
        assertTrue(slots.isActive(replacement))
    }

    @Test
    fun unassignedTargetIsAlsoReservedUntilModelDeleteCompletes() = runBlocking {
        val slots = MlKitWorkerSlotAllocator(slotCount = 1)
        val session = slots.captureSessionGeneration()
        val deleteEntered = CompletableDeferred<Unit>()
        val allowDeleteToFinish = CompletableDeferred<Unit>()
        val removal = async {
            runReservedModelRemoval(
                reserve = { slots.awaitTargetRemoval("en") },
                drain = {},
                delete = {
                    deleteEntered.complete(Unit)
                    allowDeleteToFinish.await()
                },
                complete = { check(slots.completeTargetRemoval(it)) },
                recover = { check(slots.cancelTargetRemoval(it)) },
            )
        }
        deleteEntered.await()
        val waitingEnglish = async { slots.awaitLeaseFor("en", session) }

        delay(40L)
        assertFalse(waitingEnglish.isCompleted)
        allowDeleteToFinish.complete(Unit)
        withTimeout(1_000L) { removal.await() }
        assertEquals(0, withTimeout(1_000L) { waitingEnglish.await() }.slot)
    }

    @Test
    fun failedModelDeleteRestoresOnlyItsPriorTargetOwnership() = runBlocking {
        val slots = MlKitWorkerSlotAllocator(slotCount = 2)
        val original = slots.leaseFor("en")
        val sibling = slots.leaseFor("ja")

        val result = runCatching {
            runReservedModelRemoval(
                reserve = { slots.awaitTargetRemoval("en") },
                drain = {},
                delete = { error("delete failed") },
                complete = { check(slots.completeTargetRemoval(it)) },
                recover = { check(slots.cancelTargetRemoval(it)) },
            )
        }

        assertEquals("delete failed", result.exceptionOrNull()?.message)
        val restored = slots.leaseFor("en")
        assertEquals(original.slot, restored.slot)
        assertTrue(restored.generation > original.generation)
        assertTrue(slots.isActive(restored))
        assertTrue(slots.isActive(sibling))
    }

    @Test
    fun callerCancellationCannotReleaseTargetWhileDeleteTaskIsStillRunning() = runBlocking {
        val slots = MlKitWorkerSlotAllocator(slotCount = 1)
        slots.leaseFor("en")
        val session = slots.captureSessionGeneration()
        val deleteEntered = CompletableDeferred<Unit>()
        val allowDeleteToFinish = CompletableDeferred<Unit>()
        val removal = async {
            runReservedModelRemoval(
                reserve = { slots.awaitTargetRemoval("en") },
                drain = {},
                delete = {
                    deleteEntered.complete(Unit)
                    allowDeleteToFinish.await()
                },
                complete = { check(slots.completeTargetRemoval(it)) },
                recover = { check(slots.cancelTargetRemoval(it)) },
            )
        }
        deleteEntered.await()
        removal.cancel()
        val waitingEnglish = async { slots.awaitLeaseFor("en", session) }

        delay(40L)
        assertFalse(removal.isCompleted)
        assertFalse(waitingEnglish.isCompleted)

        allowDeleteToFinish.complete(Unit)
        withTimeout(1_000L) { removal.join() }
        assertTrue(removal.isCancelled)
        assertTrue(slots.isActive(withTimeout(1_000L) { waitingEnglish.await() }))
    }

    @Test
    fun replacementGenerationWaitsForAbandonedNativeTaskToReallyClose() = runBlocking {
        val predecessor = MlKitWorkerProcessGenerationGate.beginGeneration()
        predecessor.awaitPredecessorClosed()
        val successor = MlKitWorkerProcessGenerationGate.beginGeneration()
        val runtimeCloseGate = MlKitRuntimeCloseGate()
        val closeCalls = AtomicInteger(0)
        val entered = AtomicBoolean(false)
        val waiter = launch {
            successor.awaitPredecessorClosed()
            entered.set(true)
        }

        delay(40L)
        assertFalse(entered.get())
        predecessor.closeRuntimeThenMarkClosed {
            runtimeCloseGate.close { closeCalls.incrementAndGet() }
        }
        withTimeout(1_000L) { waiter.join() }
        assertTrue(entered.get())
        runtimeCloseGate.close { closeCalls.incrementAndGet() }
        assertEquals(1, closeCalls.get())
        successor.markClosed()
    }

    @Test
    fun successorDrainCannotAcknowledgeModelRemovalBeforePredecessorRuntimeCloses() = runBlocking {
        val predecessor = MlKitWorkerProcessGenerationGate.beginGeneration()
        predecessor.awaitPredecessorClosed()
        val successor = MlKitWorkerProcessGenerationGate.beginGeneration()
        val drainAcknowledged = AtomicBoolean(false)
        val drain = launch {
            awaitPredecessorThenDrain(successor) {
                drainAcknowledged.set(true)
            }
        }

        delay(40L)
        assertFalse(drainAcknowledged.get())
        predecessor.markClosed()
        withTimeout(1_000L) { drain.join() }
        assertTrue(drainAcknowledged.get())
        successor.markClosed()
    }

    @Test
    fun emptyIntermediateGenerationCannotLetThirdGenerationBypassStuckNativeOwner() = runBlocking {
        val first = MlKitWorkerProcessGenerationGate.beginGeneration()
        first.awaitPredecessorClosed()
        val emptyReplacement = MlKitWorkerProcessGenerationGate.beginGeneration()
        emptyReplacement.markClosed()
        val third = MlKitWorkerProcessGenerationGate.beginGeneration()
        val entered = AtomicBoolean(false)
        val waiter = launch {
            third.awaitPredecessorClosed()
            entered.set(true)
        }

        delay(40L)
        assertFalse(entered.get())
        first.markClosed()
        withTimeout(1_000L) { waiter.join() }
        assertTrue(entered.get())
        third.markClosed()
    }

    @Test
    fun batchRetirementKeepsOrdinaryFailuresLocalButPropagatesCallerCancellation() = runBlocking {
        val completed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val failures = runIsolatedRetirements(
            retirements = listOf("en", "ja", "zh"),
            operation = { language ->
                if (language == "ja") error("stuck")
                completed += language
            },
        )
        assertEquals(setOf("en", "zh"), completed)
        assertEquals(setOf("ja"), failures.keys)

        val entered = CompletableDeferred<Unit>()
        val continuedAfterCancellation = AtomicBoolean(false)
        val caller = launch {
            runIsolatedRetirements(
                retirements = listOf("en", "ja"),
                operation = {
                    entered.complete(Unit)
                    awaitCancellation()
                },
            )
            continuedAfterCancellation.set(true)
        }
        entered.await()
        caller.cancel()
        withTimeout(1_000L) { caller.join() }
        assertTrue(caller.isCancelled)
        assertFalse(continuedAfterCancellation.get())
    }

    @Test
    fun cancellationNeverStartsQueuedNativeWorkOrRequiresProcessTermination() {
        val queued = MlKitNativeRequestCancellationGate()
        queued.cancel()
        assertFalse(queued.markNativeStarted())

        val running = MlKitNativeRequestCancellationGate()
        assertTrue(running.markNativeStarted())
        running.cancel()
        running.cancel()
        running.markNativeFinished()
        assertFalse(running.markNativeStarted())

        val completed = MlKitNativeRequestCancellationGate()
        assertTrue(completed.markNativeStarted())
        completed.markNativeFinished()
        completed.cancel()
        assertFalse(completed.markNativeStarted())

        // A separate language owns a separate gate; another channel's timeout cannot poison it.
        val siblingLanguage = MlKitNativeRequestCancellationGate()
        assertTrue(siblingLanguage.markNativeStarted())
        siblingLanguage.markNativeFinished()
    }

    @Test
    fun cancellingOneRunningLanguageDoesNotChangeOtherLanguageState() {
        val english = MlKitNativeRequestCancellationGate()
        val japanese = MlKitNativeRequestCancellationGate()
        assertTrue(english.markNativeStarted())
        assertTrue(japanese.markNativeStarted())

        english.cancel()
        japanese.markNativeFinished()

        assertFalse(japanese.markNativeStarted())
    }

    @Test
    fun processInitializerRunsOnceAcrossServiceRecreationAndRetriesFailure() {
        val gate = MlKitProcessInitializationGate()
        var calls = 0

        gate.initialize { calls++ }
        gate.initialize { calls++ }
        assertEquals(1, calls)

        val retryGate = MlKitProcessInitializationGate()
        assertTrue(runCatching { retryGate.initialize { error("first init failed") } }.isFailure)
        retryGate.initialize { calls++ }
        retryGate.initialize { calls++ }
        assertEquals(2, calls)
    }

    @Test
    fun drainAndServiceDestroyCloseNativeRuntimeExactlyOnce() {
        val gate = MlKitRuntimeCloseGate()
        val closeCalls = AtomicInteger(0)
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)

        repeat(2) {
            Thread {
                start.await()
                gate.close { closeCalls.incrementAndGet() }
                done.countDown()
            }.start()
        }
        start.countDown()

        assertTrue(done.await(1, TimeUnit.SECONDS))
        assertEquals(1, closeCalls.get())
    }

    @Test
    fun failedRuntimeCloseIsRetryableAndOnlySuccessfulCloseIsRemembered() {
        val gate = MlKitRuntimeCloseGate()
        val closeCalls = AtomicInteger(0)

        val firstFailure = runCatching {
            gate.close {
                closeCalls.incrementAndGet()
                error("native close failed")
            }
        }.exceptionOrNull()
        assertTrue(firstFailure?.message.orEmpty().contains("native close failed"))

        gate.close { closeCalls.incrementAndGet() }
        gate.close { closeCalls.incrementAndGet() }
        assertEquals(2, closeCalls.get())
    }

    @Test
    fun failedNativeCloseKeepsRestartBlockedUntilAConfirmedRetry() = runBlocking {
        val stoppedService = MlKitWorkerProcessGenerationGate.beginGeneration()
        stoppedService.awaitPredecessorClosed()
        val replacementService = MlKitWorkerProcessGenerationGate.beginGeneration()
        val runtimeCloseGate = MlKitRuntimeCloseGate()
        val closeCalls = AtomicInteger(0)
        val replacementReady = async { replacementService.awaitPredecessorClosed() }

        val firstFailure = runCatching {
            stoppedService.closeRuntimeThenMarkClosed {
                runtimeCloseGate.close {
                    closeCalls.incrementAndGet()
                    error("translator close failed")
                }
            }
        }.exceptionOrNull()
        assertTrue(firstFailure?.message.orEmpty().contains("translator close failed"))
        delay(100L)
        assertFalse("replacement bypassed failed native close", replacementReady.isCompleted)

        stoppedService.closeRuntimeThenMarkClosed {
            runtimeCloseGate.close { closeCalls.incrementAndGet() }
        }
        withTimeout(1_000L) { replacementReady.await() }
        assertTrue(replacementReady.isCompleted)
        assertEquals(2, closeCalls.get())
        replacementService.markClosed()
    }

    @Test
    fun requestRegistryBoundsPressureAndRemembersEarlyCancellation() {
        val registry = MlKitServiceRequestRegistry<String>(maximumPendingRequests = 2)
        assertNull(registry.cancel(7L))
        assertEquals(MlKitServiceRequestAdmission.OBSOLETE, registry.register(7L, "late"))
        assertEquals(MlKitServiceRequestAdmission.ACCEPTED, registry.register(8L, "first"))
        assertEquals(MlKitServiceRequestAdmission.ACCEPTED, registry.register(9L, "second"))
        assertEquals(MlKitServiceRequestAdmission.BUSY, registry.register(10L, "overflow"))
        assertEquals("first", registry.cancel(8L))
        assertTrue(registry.complete(8L, "first"))
        assertEquals(MlKitServiceRequestAdmission.ACCEPTED, registry.register(10L, "replacement"))
        assertEquals(2, registry.pendingCount())

        assertEquals(setOf("second", "replacement"), registry.close().toSet())
        assertEquals(MlKitServiceRequestAdmission.CLOSED, registry.register(11L, "closed"))
    }

    @Test
    fun simultaneousSimplifiedAndTraditionalChineseReceiveIndependentWorkerSlots() {
        val slots = MlKitWorkerSlotAllocator(slotCount = 5)
        val zhLease = slots.leaseFor("zh")
        val zhTwLease = slots.leaseFor("zh-TW")

        assertFalse("zh and zh-TW must receive distinct worker slots", zhLease.slot == zhTwLease.slot)
        assertEquals(zhLease, slots.existingLeaseFor("zh"))
        assertEquals(zhTwLease, slots.existingLeaseFor("zh-TW"))

        // Reconciling retained set {zh, zh-TW} must not retire either slot
        val retirements = slots.beginRetirementsExcept(setOf("zh", "zh-TW"))
        assertTrue("Neither zh nor zh-TW should be retired when both are retained", retirements.isEmpty())

        // Retiring only zh-TW leaves zh slot active
        val zhTwRetirement = requireNotNull(slots.beginRetirement("zh-TW"))
        assertEquals(zhTwLease.slot, zhTwRetirement.lease.slot)
        assertTrue(slots.completeRetirement(zhTwRetirement))

        assertEquals(zhLease, slots.existingLeaseFor("zh"))
        assertNull(slots.existingLeaseFor("zh-TW"))
    }

    @Test
    fun preparedWorkerGenerationsTracksSimplifiedAndTraditionalChineseIndependently() {
        val prepared = PreparedWorkerGenerations<String, Any>()
        val zhBinder = Any()
        val zhTwBinder = Any()

        prepared.markCurrent("zh", zhBinder)
        prepared.markCurrent("zh-TW", zhTwBinder)

        assertTrue(prepared.isCurrent("zh", zhBinder))
        assertTrue(prepared.isCurrent("zh-TW", zhTwBinder))

        // Invalidating zh-TW must not invalidate zh
        prepared.invalidate("zh-TW", zhTwBinder)
        assertFalse(prepared.isCurrent("zh-TW", zhTwBinder))
        assertTrue(prepared.isCurrent("zh", zhBinder))
    }

    @Test
    fun sharedBackendModelRemovalBlocksLeasesForBothLogicalTagsUntilCommitted() = runBlocking {
        val slots = MlKitWorkerSlotAllocator(slotCount = 5)
        val zhLease = slots.leaseFor("zh")
        assertTrue("Backend zh must be reported in use when zh has a lease", slots.isBackendInUse("zh"))

        val removal = slots.awaitTargetRemoval("zh")
        assertTrue("Removal must be active", slots.isTargetRemovalActive(removal))
        assertTrue("Backend must be in use during removal reservation", slots.isBackendInUse("zh"))

        // While removal of shared backend zh is in progress, neither zh nor zh-TW can obtain a lease
        assertNull("zh-TW lease must be blocked while backend zh removal is in progress", slots.existingLeaseFor("zh-TW"))
        assertNull("zh lease must be blocked while backend zh removal is in progress", slots.existingLeaseFor("zh"))

        // Completing removal makes the slots reusable
        assertTrue(slots.completeTargetRemoval(removal))
        assertFalse("Removal should no longer be active", slots.isTargetRemovalActive(removal))
        assertFalse("Backend should no longer be in use", slots.isBackendInUse("zh"))

        // New lease for zh-TW succeeds after removal completes
        val newZhTwLease = slots.leaseFor("zh-TW")
        assertEquals(newZhTwLease, slots.existingLeaseFor("zh-TW"))
        assertTrue("Backend zh is now in use by zh-TW", slots.isBackendInUse("zh"))
    }

    @Test
    fun sharedBackendModelRemovalRollbackRestoresPriorState() = runBlocking {
        val slots = MlKitWorkerSlotAllocator(slotCount = 5)
        val zhLease = slots.leaseFor("zh")
        val removal = slots.awaitTargetRemoval("zh")

        // Cancelling removal rolls back the reservation
        assertTrue(slots.cancelTargetRemoval(removal))
        assertFalse("Removal should no longer be active after rollback", slots.isTargetRemovalActive(removal))
        val restored = requireNotNull(slots.existingLeaseFor("zh"))
        assertEquals("zh slot should be restored", zhLease.slot, restored.slot)
        assertEquals("zh languageTag should be restored", zhLease.languageTag, restored.languageTag)
        assertTrue("Generation advances to invalidate stale in-flight callers", restored.generation > zhLease.generation)
        assertTrue("Backend zh remains in use after rollback", slots.isBackendInUse("zh"))
    }

    @Test
    fun siblingChannelPreservesSharedBackendWhenOnlyOneChannelIsRetired() {
        val slots = MlKitWorkerSlotAllocator(slotCount = 5)
        val zhLease = slots.leaseFor("zh")
        val zhTwLease = slots.leaseFor("zh-TW")

        assertTrue("Backend zh in use with both channels", slots.isBackendInUse("zh"))

        // Retire only zh
        val zhRetirement = requireNotNull(slots.beginRetirement("zh"))
        assertTrue(slots.completeRetirement(zhRetirement))

        // zh is gone, but zh-TW is still active: backend zh MUST remain in use
        assertNull("zh must be retired", slots.existingLeaseFor("zh"))
        assertEquals("zh-TW must remain active", zhTwLease, slots.existingLeaseFor("zh-TW"))
        assertTrue("Backend zh MUST remain in use because sibling zh-TW is active", slots.isBackendInUse("zh"))
    }

    @Test
    fun targetRemovalSharedBackendInUseControlsActualModelDeletionCount() = runBlocking {
        val slots = MlKitWorkerSlotAllocator(slotCount = 5)

        // Scenario 1: Solo channel removal (no sibling) -> deletion runs (count = 1)
        slots.leaseFor("zh")
        var deletionCalls = 0
        val soloRemoval = slots.awaitTargetRemoval("zh")
        assertFalse("Solo channel must report sharedBackendInUse = false", soloRemoval.sharedBackendInUse)
        if (!soloRemoval.sharedBackendInUse) {
            deletionCalls++
        }
        assertEquals(1, deletionCalls)
        assertTrue(slots.completeTargetRemoval(soloRemoval))

        // Scenario 2: Sibling channel active (zh-TW active) -> deletion skipped (count remains 1)
        slots.leaseFor("zh")
        slots.leaseFor("zh-TW")
        val siblingRemoval = slots.awaitTargetRemoval("zh")
        assertTrue("Active sibling zh-TW must report sharedBackendInUse = true", siblingRemoval.sharedBackendInUse)
        if (!siblingRemoval.sharedBackendInUse) {
            deletionCalls++
        }
        assertEquals(1, deletionCalls) // skipped, unchanged
        assertTrue(slots.completeTargetRemoval(siblingRemoval))

        // Scenario 3: Sibling channel is RETIRING (zh-TW retiring/draining) -> deletion skipped (count remains 1)
        slots.leaseFor("zh")
        val zhTwRetirement = requireNotNull(slots.beginRetirement("zh-TW"))
        val retiringSiblingRemoval = slots.awaitTargetRemoval("zh")
        assertTrue("Retiring sibling zh-TW must report sharedBackendInUse = true", retiringSiblingRemoval.sharedBackendInUse)
        if (!retiringSiblingRemoval.sharedBackendInUse) {
            deletionCalls++
        }
        assertEquals(1, deletionCalls) // still skipped, unchanged
        assertTrue(slots.completeTargetRemoval(retiringSiblingRemoval))
        assertTrue(slots.completeRetirement(zhTwRetirement))
    }

    @Test
    fun aliasPrepareRaceIsSerializedBehindActiveBackendRemoval() = runBlocking {
        val slots = MlKitWorkerSlotAllocator(slotCount = 5)
        slots.leaseFor("zh")
        val removal = slots.awaitTargetRemoval("zh")

        val prepareStarted = CompletableDeferred<Unit>()
        val prepareResult = async {
            prepareStarted.complete(Unit)
            slots.awaitLeaseFor("zh-TW", expectedSessionGeneration = slots.captureSessionGeneration())
        }
        prepareStarted.await()
        delay(50L)
        assertFalse("zh-TW prepare must be suspended while backend removal is in progress", prepareResult.isCompleted)

        // Complete removal -> unblocks prepare
        assertTrue(slots.completeTargetRemoval(removal))
        val newLease = withTimeout(1_000L) { prepareResult.await() }
        assertEquals("zh-TW", newLease.languageTag)
        assertTrue(slots.isBackendInUse("zh"))
    }

    @Test
    fun aliasPrepareRaceUnblocksOnRemovalRollback() = runBlocking {
        val slots = MlKitWorkerSlotAllocator(slotCount = 5)
        val zhLease = slots.leaseFor("zh")
        val removal = slots.awaitTargetRemoval("zh")

        val prepareStarted = CompletableDeferred<Unit>()
        val prepareResult = async {
            prepareStarted.complete(Unit)
            slots.awaitLeaseFor("zh", expectedSessionGeneration = slots.captureSessionGeneration())
        }
        prepareStarted.await()
        delay(50L)
        assertFalse("zh prepare must be suspended while removal is in progress", prepareResult.isCompleted)

        // Cancel removal (rollback) -> unblocks prepare
        assertTrue(slots.cancelTargetRemoval(removal))
        val restoredLease = withTimeout(1_000L) { prepareResult.await() }
        assertEquals("zh", restoredLease.languageTag)
        assertEquals(zhLease.slot, restoredLease.slot)
        assertTrue(slots.isBackendInUse("zh"))
    }

    @Test
    fun concurrentAliasTargetRemovalsAreSerializedOnBackendKey() = runBlocking {
        val slots = MlKitWorkerSlotAllocator(slotCount = 5)
        slots.leaseFor("zh")
        slots.leaseFor("zh-TW")

        val firstRemoval = slots.awaitTargetRemoval("zh")
        val secondStarted = CompletableDeferred<Unit>()
        val secondRemovalDeferred = async {
            secondStarted.complete(Unit)
            slots.awaitTargetRemoval("zh-TW")
        }
        secondStarted.await()
        delay(50L)
        assertFalse("zh-TW removal must suspend while backend zh removal is in flight", secondRemovalDeferred.isCompleted)

        assertTrue(slots.completeTargetRemoval(firstRemoval))
        val secondRemoval = withTimeout(1_000L) { secondRemovalDeferred.await() }
        assertEquals("zh-TW", secondRemoval.languageTag)
        assertEquals("zh", secondRemoval.backendModel)
        assertFalse("zh is now gone so zh-TW has no active sibling", secondRemoval.sharedBackendInUse)
        assertTrue(slots.completeTargetRemoval(secondRemoval))
        assertFalse(slots.isBackendInUse("zh"))
    }
}
