package app.guidecast.transmitter

import app.guidecast.core.stream.PcmAudioFrame
import app.guidecast.core.translation.NativeColdLoadTicket
import app.guidecast.core.translation.SpeechSynthesisEngine
import app.guidecast.core.translation.SpeechSynthesisEngineProvider
import app.guidecast.core.translation.currentNativeColdLoadTicket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeFirstUseGateTest {
    @Test
    fun `standard gate preserves five-way first preparation but bounds five dead-worker reloads`() =
        runBlocking {
            val liveWorkers = ConcurrentHashMap.newKeySet<String>()
            val gate = NativeFirstUseGate(
                maxParallelInitializations = 5,
                maxParallelReloads = 2,
                isInitializationCurrent = liveWorkers::contains,
            )
            val keys = listOf("en", "ja", "zh", "nl", "es").map { "mlkit-translation:$it" }

            val initialActive = AtomicInteger(0)
            val initialPeak = AtomicInteger(0)
            val allInitialEntered = CompletableDeferred<Unit>()
            val releaseInitial = CompletableDeferred<Unit>()
            val initialJobs = keys.map { key ->
                async(start = CoroutineStart.UNDISPATCHED) {
                    gate.run(key) {
                        val active = initialActive.incrementAndGet()
                        initialPeak.updateAndGet { peak -> maxOf(peak, active) }
                        if (active == keys.size) allInitialEntered.complete(Unit)
                        releaseInitial.await()
                        liveWorkers += key
                        initialActive.decrementAndGet()
                    }
                }
            }
            withTimeout(1_000L) { allInitialEntered.await() }
            assertEquals(5, initialPeak.get())
            releaseInitial.complete(Unit)
            initialJobs.forEach { it.await() }

            // Simulate Android reclaiming all five isolated Binder workers after preparation.
            liveWorkers.clear()
            val reloadActive = AtomicInteger(0)
            val reloadPeak = AtomicInteger(0)
            val releaseReload = CompletableDeferred<Unit>()
            val firstReloadPairEntered = CompletableDeferred<Unit>()
            val reloadJobs = keys.map { key ->
                async(start = CoroutineStart.UNDISPATCHED) {
                    gate.run(key) {
                        val active = reloadActive.incrementAndGet()
                        reloadPeak.updateAndGet { peak -> maxOf(peak, active) }
                        if (active == 2) firstReloadPairEntered.complete(Unit)
                        releaseReload.await()
                        liveWorkers += key
                        reloadActive.decrementAndGet()
                    }
                }
            }
            withTimeout(1_000L) { firstReloadPairEntered.await() }
            assertEquals(2, reloadPeak.get())
            releaseReload.complete(Unit)
            reloadJobs.forEach { withTimeout(1_000L) { it.await() } }
            assertEquals(2, reloadPeak.get())
        }

    @Test
    fun `healthy standard worker keeps the prepared fast path`() = runBlocking {
        var alive = true
        var admissionChecks = 0
        var livenessChecks = 0
        val gate = NativeFirstUseGate(
            maxParallelInitializations = 5,
            maxParallelReloads = 2,
            beforeFirstUse = { _, _ ->
                admissionChecks += 1
                null
            },
            isInitializationCurrent = {
                livenessChecks += 1
                alive
            },
        )

        gate.run("mlkit-translation:en") { Unit }
        gate.run("mlkit-translation:en") { Unit }

        assertTrue(alive)
        assertEquals(1, admissionChecks)
        assertEquals(
            "One post-coordinator probe and one prepared fast-path probe are expected",
            2,
            livenessChecks,
        )
    }

    @Test
    fun constrainedGateSerializesDifferentNativeInitializations() = runBlocking {
        val gate = NativeFirstUseGate(maxParallelInitializations = 1)
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            gate.run("mlkit-translation:en") {
                firstEntered.complete(Unit)
                releaseFirst.await()
                "first"
            }
        }
        firstEntered.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            gate.run("speech:ja") {
                secondEntered.complete(Unit)
                "second"
            }
        }
        assertFalse(secondEntered.isCompleted)

        releaseFirst.complete(Unit)
        assertEquals("first", first.await())
        assertEquals("second", withTimeout(1_000L) { second.await() })
    }

    @Test
    fun firstStreamingFrameReleasesPermitWithoutWaitingForWholeSentence() = runBlocking {
        val gate = NativeFirstUseGate(maxParallelInitializations = 1)
        val finishSpeech = CompletableDeferred<Unit>()
        val competingEngineEntered = CompletableDeferred<Unit>()

        val speech = async(start = CoroutineStart.UNDISPATCHED) {
            gate.runUntilInitialized("speech:en") { confirmInitialized ->
                confirmInitialized()
                finishSpeech.await()
            }
        }
        val competingEngine = async(start = CoroutineStart.UNDISPATCHED) {
            gate.run("mlkit-translation:ja") {
                competingEngineEntered.complete(Unit)
            }
        }

        withTimeout(1_000L) { competingEngineEntered.await() }
        assertFalse(speech.isCompleted)
        finishSpeech.complete(Unit)
        speech.await()
        competingEngine.await()
        Unit
    }

    @Test
    fun concurrentFirstRequestsForTheSameWorkerColdLoadOnlyOnce() = runBlocking {
        val gate = NativeFirstUseGate(maxParallelInitializations = 2)
        val firstEntered = CompletableDeferred<Unit>()
        val allowInitializationProof = CompletableDeferred<Unit>()
        val finishFirstSentence = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            gate.runUntilInitialized("speech:en") { confirmInitialized ->
                firstEntered.complete(Unit)
                allowInitializationProof.await()
                confirmInitialized()
                finishFirstSentence.await()
            }
        }
        firstEntered.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            gate.run("speech:en") { secondEntered.complete(Unit) }
        }

        assertFalse(secondEntered.isCompleted)
        allowInitializationProof.complete(Unit)
        withTimeout(1_000L) { secondEntered.await() }
        assertFalse(first.isCompleted)

        finishFirstSentence.complete(Unit)
        first.await()
        second.await()
        Unit
    }

    @Test
    fun failedInitializationIsNotMarkedReadyAndMustReenterAdmission() = runBlocking {
        val gate = NativeFirstUseGate(maxParallelInitializations = 1)
        runCatching {
            gate.run("speech:en") { error("prepare failed") }
        }

        val otherEntered = CompletableDeferred<Unit>()
        val releaseOther = CompletableDeferred<Unit>()
        val other = async(start = CoroutineStart.UNDISPATCHED) {
            gate.run("mlkit-translation:ja") {
                otherEntered.complete(Unit)
                releaseOther.await()
            }
        }
        otherEntered.await()
        val retryEntered = CompletableDeferred<Unit>()
        val retry = async(start = CoroutineStart.UNDISPATCHED) {
            gate.run("speech:en") { retryEntered.complete(Unit) }
        }
        assertFalse(retryEntered.isCompleted)

        releaseOther.complete(Unit)
        other.await()
        withTimeout(1_000L) { retry.await() }
        assertTrue(retryEntered.isCompleted)
    }

    @Test
    fun invalidatingARecreatedWorkerRearmsAdmission() = runBlocking {
        val gate = NativeFirstUseGate(maxParallelInitializations = 1)
        gate.run("gemma-translation:en") { Unit }
        gate.run("speech:ja") { Unit }
        gate.invalidate("gemma-translation:en")
        gate.invalidate("speech:ja")

        val otherEntered = CompletableDeferred<Unit>()
        val releaseOther = CompletableDeferred<Unit>()
        val other = async(start = CoroutineStart.UNDISPATCHED) {
            gate.run("speech:ja") {
                otherEntered.complete(Unit)
                releaseOther.await()
            }
        }
        otherEntered.await()
        val recreatedEntered = CompletableDeferred<Unit>()
        val recreated = async(start = CoroutineStart.UNDISPATCHED) {
            gate.run("gemma-translation:en") { recreatedEntered.complete(Unit) }
        }
        assertFalse(recreatedEntered.isCompleted)

        releaseOther.complete(Unit)
        other.await()
        withTimeout(1_000L) { recreated.await() }
        assertTrue(recreatedEntered.isCompleted)
    }

    @Test
    fun speechWrapperReleasesNativeAdmissionOnFirstValidPcmEvenWhenSilent() = runBlocking {
        val gate = NativeFirstUseGate(maxParallelInitializations = 1)
        val silentFrameEmitted = CompletableDeferred<Unit>()
        val allowAudibleFrame = CompletableDeferred<Unit>()
        val competingEntered = CompletableDeferred<Unit>()
        val provider = SpeechSynthesisEngineProvider {
            object : SpeechSynthesisEngine {
                override fun synthesize(text: String, languageTag: String) = flow {
                    emit(PcmAudioFrame(byteArrayOf(0, 0, 0, 0), 1L))
                    silentFrameEmitted.complete(Unit)
                    allowAudibleFrame.await()
                    emit(PcmAudioFrame(byteArrayOf(0, 64, 0, 64), 2L))
                }
            }
        }.withNativeFirstUseGate(gate)

        val speech = async(start = CoroutineStart.UNDISPATCHED) {
            provider.engineFor("en").synthesize("Welcome", "en").collect()
        }
        silentFrameEmitted.await()
        val competing = async(start = CoroutineStart.UNDISPATCHED) {
            gate.run("mlkit-translation:ja") { competingEntered.complete(Unit) }
        }
        withTimeout(1_000L) { competingEntered.await() }

        allowAudibleFrame.complete(Unit)
        speech.await()
        competing.await()
        Unit
    }

    @Test
    fun constrainedPolicyReadmitsEveryTranslationAfterWorkerCouldHaveBeenReclaimed() = runBlocking {
        val gate = NativeFirstUseGate(
            maxParallelInitializations = 1,
            guardEveryOperation = true,
        )
        gate.run("mlkit-translation:en") { Unit }

        val competingEntered = CompletableDeferred<Unit>()
        val releaseCompeting = CompletableDeferred<Unit>()
        val competing = async(start = CoroutineStart.UNDISPATCHED) {
            gate.run("speech:ja") {
                competingEntered.complete(Unit)
                releaseCompeting.await()
            }
        }
        competingEntered.await()
        val secondTranslationEntered = CompletableDeferred<Unit>()
        val secondTranslation = async(start = CoroutineStart.UNDISPATCHED) {
            gate.run("mlkit-translation:en") { secondTranslationEntered.complete(Unit) }
        }
        assertFalse(
            "A previously used translation key bypassed constrained rebind admission",
            secondTranslationEntered.isCompleted,
        )

        releaseCompeting.complete(Unit)
        competing.await()
        withTimeout(1_000L) { secondTranslation.await() }
        assertTrue(secondTranslationEntered.isCompleted)
    }

    @Test
    fun constrainedSpeechReacquiresPermitForEveryUtteranceUntilValidPcm() = runBlocking {
        val gate = NativeFirstUseGate(
            maxParallelInitializations = 1,
            guardEveryOperation = true,
        )
        val allowSecondAudibleFrame = CompletableDeferred<Unit>()
        val secondUtteranceEntered = CompletableDeferred<Unit>()
        var utterance = 0
        val provider = SpeechSynthesisEngineProvider {
            object : SpeechSynthesisEngine {
                override fun synthesize(text: String, languageTag: String) = flow {
                    utterance += 1
                    if (utterance == 2) {
                        secondUtteranceEntered.complete(Unit)
                        allowSecondAudibleFrame.await()
                    }
                    emit(PcmAudioFrame(byteArrayOf(0, 0, 0, 0), utterance.toLong()))
                }
            }
        }.withNativeFirstUseGate(gate)
        val engine = provider.engineFor("en")
        engine.synthesize("first", "en").collect()

        val second = async(start = CoroutineStart.UNDISPATCHED) {
            engine.synthesize("second", "en").collect()
        }
        withTimeout(1_000L) { secondUtteranceEntered.await() }
        val competingEntered = CompletableDeferred<Unit>()
        val competing = async(start = CoroutineStart.UNDISPATCHED) {
            gate.run("mlkit-translation:ja") { competingEntered.complete(Unit) }
        }
        assertFalse(
            "A previously audible speech key bypassed constrained rebind admission",
            competingEntered.isCompleted,
        )

        allowSecondAudibleFrame.complete(Unit)
        second.await()
        withTimeout(1_000L) { competing.await() }
        assertTrue(competingEntered.isCompleted)
    }

    @Test
    fun stalledColdSpeechDoesNotBlockAnAlreadyPreparedTranslationWorker() = runBlocking {
        val coordinator = NativeColdLoadCoordinator(maxParallelLoads = 2)
        val healthy = NativeSupportMemoryAdmission.forMemory(
            availableMemoryBytes = 4L * 1024L * 1024L * 1024L,
            androidLowMemoryThresholdBytes = 256L * 1024L * 1024L,
            systemLowMemory = false,
        )
        val translationGate = NativeFirstUseGate(
            maxParallelInitializations = 1,
            beforeFirstUse = { key, _ ->
                coordinator.acquire(key, true, healthy)
            },
        )
        val speechGate = NativeFirstUseGate(
            maxParallelInitializations = 1,
            beforeFirstUse = { key, _ ->
                coordinator.acquire(key, true, healthy)
            },
        )
        translationGate.run("mlkit-translation:ja") { Unit }
        val releaseSpeech = CompletableDeferred<Unit>()
        val speechEntered = CompletableDeferred<Unit>()
        val speech = async(start = CoroutineStart.UNDISPATCHED) {
            speechGate.runUntilInitialized("speech:en") {
                speechEntered.complete(Unit)
                releaseSpeech.await()
            }
        }
        speechEntered.await()

        val translated = withTimeout(1_000L) {
            translationGate.run("mlkit-translation:ja") { "translated script" }
        }
        assertEquals("translated script", translated)
        assertFalse(speech.isCompleted)

        releaseSpeech.complete(Unit)
        speech.await()
    }

    @Test
    fun independentTranslationAndSpeechGatesShareOneSerializedColdLoadLane() = runBlocking {
        val coordinator = NativeColdLoadCoordinator(maxParallelLoads = 2)
        val healthy = NativeSupportMemoryAdmission.forMemory(
            availableMemoryBytes = 4L * 1024L * 1024L * 1024L,
            androidLowMemoryThresholdBytes = 256L * 1024L * 1024L,
            systemLowMemory = false,
        )
        val translationGate = NativeFirstUseGate(
            maxParallelInitializations = 5,
            beforeFirstUse = { key, _ -> coordinator.acquire(key, true, healthy) },
        )
        val speechGate = NativeFirstUseGate(
            maxParallelInitializations = 5,
            beforeFirstUse = { key, _ -> coordinator.acquire(key, true, healthy) },
        )
        val translationEntered = CompletableDeferred<Unit>()
        val releaseTranslation = CompletableDeferred<Unit>()
        val speechEntered = CompletableDeferred<Unit>()

        val translation = async(start = CoroutineStart.UNDISPATCHED) {
            translationGate.run("mlkit-translation:en") {
                translationEntered.complete(Unit)
                releaseTranslation.await()
            }
        }
        translationEntered.await()
        val speech = async(start = CoroutineStart.UNDISPATCHED) {
            speechGate.run("speech:en") { speechEntered.complete(Unit) }
        }

        assertFalse(speechEntered.isCompleted)
        releaseTranslation.complete(Unit)
        translation.await()
        withTimeout(1_000L) { speech.await() }
        assertTrue(speechEntered.isCompleted)
        Unit
    }

    @Test
    fun separateSessionGatesRecheckLivenessAndSkipDuplicateExplicitInitializer() = runBlocking {
        val coordinator = NativeColdLoadCoordinator(maxParallelLoads = 1)
        val healthy = NativeSupportMemoryAdmission.forMemory(
            availableMemoryBytes = 4L * 1024L * 1024L * 1024L,
            androidLowMemoryThresholdBytes = 256L * 1024L * 1024L,
            systemLowMemory = false,
        )
        val workerAlive = AtomicBoolean(false)
        val initializerCalls = AtomicInteger(0)
        val firstInitializerEntered = CompletableDeferred<Unit>()
        val releaseFirstInitializer = CompletableDeferred<Unit>()
        fun newSessionGate() = NativeFirstUseGate(
            maxParallelInitializations = 1,
            beforeFirstUse = { key, _ ->
                coordinator.acquire(
                    key = key,
                    serializeWithAllColdLoads = true,
                    currentAdmission = healthy,
                )
            },
            isInitializationCurrent = { workerAlive.get() },
        )

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            newSessionGate().initialize("gemma-translation:en") {
                initializerCalls.incrementAndGet()
                firstInitializerEntered.complete(Unit)
                releaseFirstInitializer.await()
                workerAlive.set(true)
            }
        }
        firstInitializerEntered.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            newSessionGate().initialize("gemma-translation:en") {
                initializerCalls.incrementAndGet()
            }
        }

        assertFalse(second.isCompleted)
        releaseFirstInitializer.complete(Unit)
        withTimeout(1_000L) {
            first.await()
            second.await()
        }
        assertEquals(1, initializerCalls.get())
    }

    @Test
    fun firstPcmReleasesGateButTransferredTicketWaitsForNativeCompletion() = runBlocking {
        val coordinator = NativeColdLoadCoordinator(maxParallelLoads = 1)
        val healthy = NativeSupportMemoryAdmission.forMemory(
            availableMemoryBytes = 4L * 1024L * 1024L * 1024L,
            androidLowMemoryThresholdBytes = 256L * 1024L * 1024L,
            systemLowMemory = false,
        )
        val competingAdmissionAttempted = CompletableDeferred<Unit>()
        val competingOperationEntered = CompletableDeferred<Unit>()
        val firstPcmCollected = CompletableDeferred<Unit>()
        val allowNativeCompletion = CompletableDeferred<Unit>()
        val gate = NativeFirstUseGate(
            maxParallelInitializations = 1,
            beforeFirstUse = { key, _ ->
                if (key == "mlkit-translation:ja") {
                    competingAdmissionAttempted.complete(Unit)
                }
                coordinator.acquire(
                    key = key,
                    serializeWithAllColdLoads = true,
                    currentAdmission = healthy,
                )
            },
        )
        val provider = SpeechSynthesisEngineProvider {
            object : SpeechSynthesisEngine {
                override fun synthesize(text: String, languageTag: String) = flow {
                    val ticket = checkNotNull(currentNativeColdLoadTicket())
                    ticket.transferToNative()
                    emit(PcmAudioFrame(byteArrayOf(0, 64, 0, 64), 1L))
                    firstPcmCollected.complete(Unit)
                    allowNativeCompletion.await()
                    ticket.completeNative()
                }
            }
        }.withNativeFirstUseGate(gate)

        val speech = async(start = CoroutineStart.UNDISPATCHED) {
            provider.engineFor("en").synthesize("Welcome", "en").collect()
        }
        firstPcmCollected.await()
        val competing = async(start = CoroutineStart.UNDISPATCHED) {
            gate.run("mlkit-translation:ja") {
                competingOperationEntered.complete(Unit)
            }
        }

        withTimeout(1_000L) { competingAdmissionAttempted.await() }
        assertFalse(
            "The process-wide ticket was released by the gate after ownership transferred",
            competingOperationEntered.isCompleted,
        )

        allowNativeCompletion.complete(Unit)
        withTimeout(1_000L) {
            speech.await()
            competing.await()
        }
        assertTrue(competingOperationEntered.isCompleted)
    }

    @Test
    fun failedAdmissionDoesNotRunWorkerAndIsRecheckedOnRetry() = runBlocking {
        var admissionChecks = 0
        var operations = 0
        val gate = NativeFirstUseGate(
            maxParallelInitializations = 1,
            beforeFirstUse = { _, _ ->
                admissionChecks += 1
                if (admissionChecks == 1) {
                    throw IllegalStateException("admission callback failed")
                }
                null
            },
        )

        val first = runCatching {
            gate.run("mlkit-translation:en") { operations += 1 }
        }
        assertTrue(first.exceptionOrNull() is IllegalStateException)
        assertEquals(0, operations)

        gate.run("mlkit-translation:en") { operations += 1 }
        gate.run("mlkit-translation:en") { operations += 1 }
        assertEquals(2, admissionChecks)
        assertEquals(2, operations)
    }

    @Test
    fun oneFailedAdmissionDoesNotPoisonSiblingKey() = runBlocking {
        val gate = NativeFirstUseGate(
            maxParallelInitializations = 1,
            beforeFirstUse = { key, _ ->
                if (key.endsWith(":en")) {
                    throw IllegalStateException("en admission failure")
                }
                null
            },
        )

        val english = runCatching {
            gate.run("mlkit-translation:en") { "must not run" }
        }
        val japanese = gate.run("mlkit-translation:ja") { "日本語" }

        assertTrue(english.exceptionOrNull() is IllegalStateException)
        assertEquals("日本語", japanese)
    }

    @Test
    fun constrainedModeRechecksWorkerLivenessAfterAPreviouslySuccessfulUse() = runBlocking {
        val previouslyInitializedValues = mutableListOf<Boolean>()
        val gate = NativeFirstUseGate(
            maxParallelInitializations = 1,
            guardEveryOperation = true,
            beforeFirstUse = { _, previouslyInitialized ->
                previouslyInitializedValues += previouslyInitialized
                null
            },
        )

        gate.run("mlkit-translation:en") { Unit }
        gate.run("mlkit-translation:en") { Unit }

        assertEquals(listOf(false, true), previouslyInitializedValues)
    }

    @Test
    fun externalMemoryReservationClosesAtInitializationProof() = runBlocking {
        var closeCalls = 0
        val gate = NativeFirstUseGate(
            maxParallelInitializations = 1,
            beforeFirstUse = { _, _ -> closeCountingTicket { closeCalls += 1 } },
        )

        gate.run("mlkit-translation:en") {
            assertEquals(0, closeCalls)
        }

        assertEquals(1, closeCalls)
    }

    @Test
    fun externalMemoryReservationClosesAfterFailedInitializationAndRetryCanProceed() = runBlocking {
        var closeCalls = 0
        var attempts = 0
        val gate = NativeFirstUseGate(
            maxParallelInitializations = 1,
            beforeFirstUse = { _, _ -> closeCountingTicket { closeCalls += 1 } },
        )

        val failed = runCatching {
            gate.run("speech:en") {
                attempts += 1
                error("native initialization failed")
            }
        }
        assertTrue(failed.isFailure)
        assertEquals(1, closeCalls)

        gate.run("speech:en") { attempts += 1 }
        assertEquals(2, attempts)
        assertEquals(2, closeCalls)
    }

    private fun closeCountingTicket(onClose: () -> Unit): NativeColdLoadTicket =
        object : NativeColdLoadTicket {
            override fun transferToNative() = Unit

            override fun completeNative() = Unit

            override fun close() = onClose()
        }
}
