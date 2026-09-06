package app.guidecast.transmitter

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSupportMemoryAdmissionTest {
    @Test
    fun androidLowMemorySignalAlwaysDefersAnotherNativeWorker() {
        val admission = NativeSupportMemoryAdmission.forMemory(
            availableMemoryBytes = 4L * 1024 * 1024 * 1024,
            androidLowMemoryThresholdBytes = 256L * 1024 * 1024,
            systemLowMemory = true,
        )

        assertFalse(admission.mayStartNewWorker)
    }

    @Test
    fun supportWorkerHeadroomHasAnExactBoundary() {
        val boundary = NativeSupportMemoryAdmission.forMemory(
            availableMemoryBytes = NativeSupportMemoryAdmission.MIN_NEW_WORKER_HEADROOM_BYTES,
            androidLowMemoryThresholdBytes = 256L * 1024 * 1024,
            systemLowMemory = false,
        )
        val below = NativeSupportMemoryAdmission.forMemory(
            availableMemoryBytes =
                NativeSupportMemoryAdmission.MIN_NEW_WORKER_HEADROOM_BYTES - 1L,
            androidLowMemoryThresholdBytes = 256L * 1024 * 1024,
            systemLowMemory = false,
        )

        assertTrue(boundary.mayStartNewWorker)
        assertFalse(below.mayStartNewWorker)
        assertTrue(below.operatorMessage.contains("선택 언어와 음성 품질은 유지"))
    }

    @Test
    fun largerAndroidThresholdOverridesThePortableFloor() {
        val admission = NativeSupportMemoryAdmission.forMemory(
            availableMemoryBytes = 900L * 1024 * 1024,
            androidLowMemoryThresholdBytes = 1L * 1024 * 1024 * 1024,
            systemLowMemory = false,
        )

        assertFalse(admission.mayStartNewWorker)
    }

    @Test
    fun sharedBudgetPreventsTwoBackendLoadsFromPassingTheSameNearFloorSnapshot() {
        val budget = NativeSupportMemoryBudget(
            currentAdmission = {
                NativeSupportMemoryAdmission.forMemory(
                    availableMemoryBytes = 1L * 1024 * 1024 * 1024,
                    androidLowMemoryThresholdBytes = 256L * 1024 * 1024,
                    systemLowMemory = false,
                )
            },
        )

        val translation = budget.tryReserve()
        val simultaneousSpeech = budget.tryReserve()
        assertTrue(translation.reservation != null)
        assertTrue(simultaneousSpeech.reservation == null)

        translation.reservation?.close()
        val laterSpeech = budget.tryReserve()
        assertTrue(laterSpeech.reservation != null)
        laterSpeech.reservation?.close()
    }

    @Test
    fun boundedReservationsAreReusableAfterIdempotentReleaseWithoutLeakingCapacity() {
        val budget = NativeSupportMemoryBudget(
            currentAdmission = {
                NativeSupportMemoryAdmission.forMemory(
                    availableMemoryBytes = 1_536L * 1024L * 1024L,
                    androidLowMemoryThresholdBytes = 256L * 1024L * 1024L,
                    systemLowMemory = false,
                )
            },
        )

        val firstWave = List(4) { budget.tryReserve().reservation }
        assertEquals(3, firstWave.count { it != null })
        assertTrue(firstWave.last() == null)

        firstWave.filterNotNull().forEach { reservation ->
            reservation.close()
            reservation.close()
        }

        val secondWave = List(4) { budget.tryReserve().reservation }
        assertEquals(3, secondWave.count { it != null })
        assertTrue(secondWave.last() == null)
        secondWave.filterNotNull().forEach(AutoCloseable::close)
    }

    @Test
    fun lowMemorySignalSerializesButNeverDeniesASelectedNativeEngine() = runBlocking {
        val pressureObservations = AtomicInteger(0)
        val coordinator = NativeColdLoadCoordinator(maxParallelLoads = 2)
        val pressure = NativeSupportMemoryAdmission.forMemory(
            availableMemoryBytes = 128L * 1024L * 1024L,
            androidLowMemoryThresholdBytes = 256L * 1024L * 1024L,
            systemLowMemory = true,
        )
        val firstLease = coordinator.acquire(
            key = "gemma-translation:en",
            serializeWithAllColdLoads = false,
            currentAdmission = pressure,
            onPressureDetected = { pressureObservations.incrementAndGet() },
        )
        val secondAcquired = CompletableDeferred<AutoCloseable>()
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            secondAcquired.complete(
                coordinator.acquire(
                    key = "speech:en",
                    serializeWithAllColdLoads = false,
                    currentAdmission = pressure,
                    onPressureDetected = { pressureObservations.incrementAndGet() },
                ),
            )
        }

        assertFalse(secondAcquired.isCompleted)
        firstLease.close()
        val secondLease = withTimeout(1_000L) { secondAcquired.await() }
        secondLease.close()
        second.await()
        assertEquals(2, pressureObservations.get())
    }

    @Test
    fun supersededPreparationCannotWarmAfterAdmissionWaitAndReturnsPermit() = runBlocking {
        val coordinator = NativeColdLoadCoordinator(maxParallelLoads = 1)
        val healthy = NativeSupportMemoryAdmission.forMemory(
            availableMemoryBytes = 4L * 1024L * 1024L * 1024L,
            androidLowMemoryThresholdBytes = 256L * 1024L * 1024L,
            systemLowMemory = false,
        )
        val blocker = coordinator.acquire(
            key = "gemma-translation",
            serializeWithAllColdLoads = false,
            currentAdmission = healthy,
        )
        val ownerCurrent = AtomicBoolean(true)
        var warmCalls = 0
        val queued = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                runPreparationOwnedNativeOperation(
                    isOwnerCurrent = ownerCurrent::get,
                    ownerLostMessage = "superseded settings preparation",
                    withAdmission = { operation ->
                        coordinator.acquire(
                            key = "speech-recognition:ko",
                            serializeWithAllColdLoads = false,
                            currentAdmission = healthy,
                        ).use { operation() }
                    },
                ) {
                    warmCalls += 1
                }
            }
        }

        assertFalse(queued.isCompleted)
        ownerCurrent.set(false)
        blocker.close()
        val result = withTimeout(1_000L) { queued.await() }
        assertTrue(result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertEquals(0, warmCalls)

        val returned = withTimeout(1_000L) {
            coordinator.acquire(
                key = "mlkit-translation:en",
                serializeWithAllColdLoads = false,
                currentAdmission = healthy,
            )
        }
        returned.close()
    }

    @Test
    fun healthySingleLanguagePolicyKeepsTwoColdLoadsAvailable() = runBlocking {
        val coordinator = NativeColdLoadCoordinator(maxParallelLoads = 2)
        val healthy = NativeSupportMemoryAdmission.forMemory(
            availableMemoryBytes = 4L * 1024L * 1024L * 1024L,
            androidLowMemoryThresholdBytes = 256L * 1024L * 1024L,
            systemLowMemory = false,
        )
        val translation = coordinator.acquire(
            key = "mlkit-translation:en",
            serializeWithAllColdLoads = false,
            currentAdmission = healthy,
        )
        val speech = coordinator.acquire(
            key = "speech:en",
            serializeWithAllColdLoads = false,
            currentAdmission = healthy,
        )
        val thirdAcquired = CompletableDeferred<AutoCloseable>()
        val third = async(start = CoroutineStart.UNDISPATCHED) {
            thirdAcquired.complete(
                coordinator.acquire(
                    key = "gemma-translation:en",
                    serializeWithAllColdLoads = false,
                    currentAdmission = healthy,
                ),
            )
        }

        assertFalse(thirdAcquired.isCompleted)
        translation.close()
        val thirdLease = withTimeout(1_000L) { thirdAcquired.await() }
        thirdLease.close()
        speech.close()
        third.await()
        Unit
    }

    @Test
    fun broadcastAndSettingsGemmaUseOneTargetIndependentProcessLane() = runBlocking {
        val coordinator = NativeColdLoadCoordinator(maxParallelLoads = 2)
        val healthy = NativeSupportMemoryAdmission.forMemory(
            availableMemoryBytes = 4L * 1024L * 1024L * 1024L,
            androidLowMemoryThresholdBytes = 256L * 1024L * 1024L,
            systemLowMemory = false,
        )
        val broadcastLease = coordinator.acquire(
            key = ProcessNativeColdLoadKeys.GEMMA_MODEL,
            serializeWithAllColdLoads = false,
            currentAdmission = healthy,
        )
        val settingsEntered = CompletableDeferred<Unit>()
        val settings = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.acquire(
                // Settings self-test and every broadcast target use this exact shared model key.
                key = ProcessNativeColdLoadKeys.GEMMA_MODEL,
                serializeWithAllColdLoads = false,
                currentAdmission = healthy,
            ).use { settingsEntered.complete(Unit) }
        }

        assertFalse(
            "Settings must not cold-load Gemma beside an in-flight broadcast cold load",
            settingsEntered.isCompleted,
        )
        broadcastLease.close()
        withTimeout(1_000L) { settings.await() }
        assertTrue(settingsEntered.isCompleted)
    }

    @Test
    fun broadcastAndSettingsVoiceWarmupShareTheLanguageWorkerLane() = runBlocking {
        val coordinator = NativeColdLoadCoordinator(maxParallelLoads = 2)
        val healthy = NativeSupportMemoryAdmission.forMemory(
            availableMemoryBytes = 4L * 1024L * 1024L * 1024L,
            androidLowMemoryThresholdBytes = 256L * 1024L * 1024L,
            systemLowMemory = false,
        )
        val broadcastLease = coordinator.acquire(
            key = ProcessNativeColdLoadKeys.speech("en"),
            serializeWithAllColdLoads = false,
            currentAdmission = healthy,
        )
        val settingsEntered = CompletableDeferred<Unit>()
        val settings = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.acquire(
                key = ProcessNativeColdLoadKeys.speech("en"),
                serializeWithAllColdLoads = false,
                currentAdmission = healthy,
            ).use { settingsEntered.complete(Unit) }
        }

        assertFalse(
            "Settings must not warm the same voice beside broadcast first use",
            settingsEntered.isCompleted,
        )
        broadcastLease.close()
        withTimeout(1_000L) { settings.await() }
        assertTrue(settingsEntered.isCompleted)
    }

    @Test
    fun transferredTicketIgnoresCallerCloseUntilNativeCompletionReleasesCapacity() = runBlocking {
        val coordinator = NativeColdLoadCoordinator(maxParallelLoads = 1)
        val healthy = NativeSupportMemoryAdmission.forMemory(
            availableMemoryBytes = 4L * 1024L * 1024L * 1024L,
            androidLowMemoryThresholdBytes = 256L * 1024L * 1024L,
            systemLowMemory = false,
        )
        val nativeOwned = coordinator.acquire(
            key = "speech:en",
            serializeWithAllColdLoads = false,
            currentAdmission = healthy,
        )
        nativeOwned.transferToNative()
        nativeOwned.close()

        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.acquire(
                key = "gemma-translation:ja",
                serializeWithAllColdLoads = false,
                currentAdmission = healthy,
            )
        }
        assertFalse(
            "Caller close released a ticket whose ownership had moved to native work",
            waiting.isCompleted,
        )

        nativeOwned.completeNative()
        val next = withTimeout(1_000L) { waiting.await() }
        next.close()
        // Terminal calls remain idempotent and cannot over-release the coordinator.
        nativeOwned.completeNative()
        nativeOwned.close()
    }

    @Test
    fun detachedExclusiveNativeOwnerKeepsSameKeyClosedButLetsOneSiblingProceed() = runBlocking {
        val coordinator = NativeColdLoadCoordinator(maxParallelLoads = 2)
        val healthy = NativeSupportMemoryAdmission.forMemory(
            availableMemoryBytes = 4L * 1024L * 1024L * 1024L,
            androidLowMemoryThresholdBytes = 256L * 1024L * 1024L,
            systemLowMemory = false,
        )
        val unresolved = coordinator.acquire(
            key = "gemma-translation:en",
            serializeWithAllColdLoads = true,
            currentAdmission = healthy,
        )
        unresolved.transferToNative()
        unresolved.close()

        val sameKey = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.acquire(
                key = "gemma-translation:en",
                serializeWithAllColdLoads = false,
                currentAdmission = healthy,
            )
        }
        assertFalse("Detached native owner must retain its exact key lane", sameKey.isCompleted)

        val sibling = withTimeout(1_000L) {
            coordinator.acquire(
                key = "speech:ja",
                serializeWithAllColdLoads = true,
                currentAdmission = healthy,
            )
        }
        val third = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.acquire(
                key = "mlkit-translation:zh",
                serializeWithAllColdLoads = false,
                currentAdmission = healthy,
            )
        }
        assertFalse("Only one sibling may load beside an unresolved owner", third.isCompleted)

        sibling.close()
        val thirdLease = withTimeout(1_000L) { third.await() }
        thirdLease.close()
        unresolved.completeNative()
        val sameKeyLease = withTimeout(1_000L) { sameKey.await() }
        sameKeyLease.close()
    }

    @Test
    fun detachedNonExclusiveOwnerDowngradesLaterExclusiveRequestToOneFreeLane() = runBlocking {
        val coordinator = NativeColdLoadCoordinator(maxParallelLoads = 2)
        val healthy = NativeSupportMemoryAdmission.forMemory(
            availableMemoryBytes = 4L * 1024L * 1024L * 1024L,
            androidLowMemoryThresholdBytes = 256L * 1024L * 1024L,
            systemLowMemory = false,
        )
        val unresolved = coordinator.acquire(
            key = "speech:en",
            serializeWithAllColdLoads = false,
            currentAdmission = healthy,
        )
        unresolved.transferToNative()
        unresolved.close()

        val isolatedSibling = withTimeout(1_000L) {
            coordinator.acquire(
                key = "gemma-translation:ja",
                serializeWithAllColdLoads = true,
                currentAdmission = healthy,
            )
        }
        val blocked = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.acquire(
                key = "mlkit-translation:zh",
                serializeWithAllColdLoads = false,
                currentAdmission = healthy,
            )
        }
        assertFalse(blocked.isCompleted)

        isolatedSibling.close()
        val recovered = withTimeout(1_000L) { blocked.await() }
        recovered.close()
        unresolved.completeNative()
    }

    @Test
    fun cancellingExclusivePartialAcquisitionRestoresGlobalAndKeyPermits() = runBlocking {
        val coordinator = NativeColdLoadCoordinator(maxParallelLoads = 2)
        val healthy = NativeSupportMemoryAdmission.forMemory(
            availableMemoryBytes = 4L * 1024L * 1024L * 1024L,
            androidLowMemoryThresholdBytes = 256L * 1024L * 1024L,
            systemLowMemory = false,
        )
        val occupied = coordinator.acquire(
            key = "mlkit-translation:en",
            serializeWithAllColdLoads = false,
            currentAdmission = healthy,
        )
        val cancelledExclusive = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.acquire(
                key = "speech:ja",
                serializeWithAllColdLoads = true,
                currentAdmission = healthy,
            )
        }
        assertFalse(cancelledExclusive.isCompleted)

        cancelledExclusive.cancelAndJoin()
        occupied.close()

        val recovered = withTimeout(1_000L) {
            coordinator.acquire(
                key = "speech:ja",
                serializeWithAllColdLoads = true,
                currentAdmission = healthy,
            )
        }
        recovered.close()
    }
}
