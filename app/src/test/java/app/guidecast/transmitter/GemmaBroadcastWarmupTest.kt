package app.guidecast.transmitter

import app.guidecast.provider.gemma.translation.GemmaBroadcastCapability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GemmaBroadcastWarmupTest {
    @Test
    fun recoveredMemoryAfterBackendCleanupEnablesSixGigabyteGemmaAttempt() {
        val initial = GemmaBroadcastCapability.forMemory(
            totalMemoryBytes = 5_800L * 1024 * 1024,
            availableMemoryBytes = 2L * 1024 * 1024 * 1024,
            systemLowMemory = false,
        )
        val afterCleanup = GemmaBroadcastCapability.forMemory(
            totalMemoryBytes = 5_800L * 1024 * 1024,
            availableMemoryBytes = 4L * 1024 * 1024 * 1024,
            systemLowMemory = false,
        )

        val admission = gemmaBroadcastAttemptAdmission(initial, afterCleanup)

        assertTrue(admission.hardwareSupported)
        assertTrue(admission.constrainedMemoryMode)
        assertTrue(admission.loadPermittedNow)
        assertTrue(
            isGemmaBroadcastEligible(
                requested = true,
                languagePairSupported = true,
                hardwareSupported = admission.hardwareSupported,
                modelReady = true,
            ),
        )
    }

    @Test
    fun refreshedPressureSnapshotCannotPromoteUnsupportedPhysicalRam() {
        val initial = GemmaBroadcastCapability.forMemory(
            totalMemoryBytes = 3_000L * 1024 * 1024,
            availableMemoryBytes = 4L * 1024 * 1024 * 1024,
            systemLowMemory = false,
        )
        val inconsistentLatest = GemmaBroadcastCapability.forMemory(
            totalMemoryBytes = 6L * 1024 * 1024 * 1024,
            availableMemoryBytes = 4L * 1024 * 1024 * 1024,
            systemLowMemory = false,
        )

        val admission = gemmaBroadcastAttemptAdmission(initial, inconsistentLatest)

        assertFalse(admission.hardwareSupported)
        assertFalse(admission.loadPermittedNow)
        assertEquals(initial.message, admission.operatorMessage)
    }

    @Test
    fun provenLiveGemmaWorkerIsReusedWhenItsOwnResidentMemoryLowersAvailableRam() {
        val initial = GemmaBroadcastCapability.forMemory(
            totalMemoryBytes = 5_800L * 1024 * 1024,
            availableMemoryBytes = 2L * 1024 * 1024 * 1024,
            systemLowMemory = false,
        )
        val latest = GemmaBroadcastCapability.forMemory(
            totalMemoryBytes = 5_800L * 1024 * 1024,
            availableMemoryBytes = 2L * 1024 * 1024 * 1024,
            systemLowMemory = false,
        )

        val admission = gemmaBroadcastAttemptAdmission(
            initialCapability = initial,
            latestCapability = latest,
            workerAlreadyPrepared = true,
        )

        assertTrue(admission.loadPermittedNow)
        assertTrue(admission.operatorMessage.contains("작업 공간 재사용"))
        assertFalse(admission.operatorMessage.contains("모델 적재 보류"))
    }

    @Test
    fun sixGigabyteModeWarmsGemmaBeforeAcceptingLiveSpeech() {
        assertTrue(
            shouldWarmGemmaBeforeListening(
                awaitChannelPreparation = false,
                gemmaEligible = true,
                constrainedMemoryMode = true,
            ),
        )
        assertFalse(
            shouldWarmGemmaBeforeListening(
                awaitChannelPreparation = false,
                gemmaEligible = true,
                constrainedMemoryMode = false,
            ),
        )
    }

    @Test
    fun failedSynchronousWarmupKeepsGemmaRouteClosedForThatSession() {
        assertFalse(
            shouldEnableGemmaPriorityRoute(
                gemmaEligible = true,
                warmupActive = false,
            ),
        )
        assertTrue(
            shouldEnableGemmaPriorityRoute(
                gemmaEligible = true,
                warmupActive = true,
            ),
        )
        assertFalse(
            shouldEnableGemmaPriorityRoute(
                gemmaEligible = false,
                warmupActive = true,
            ),
        )
    }

    @Test
    fun constrainedMemorySerializesNativeSupportPreparation() {
        assertEquals(1, translationSupportPreparationParallelism(true, 5))
        assertEquals(5, translationSupportPreparationParallelism(false, 5))
        assertEquals(1, translationSupportReloadParallelism(true, 5))
        assertEquals(2, translationSupportReloadParallelism(false, 5))
        assertEquals(1, translationSupportReloadParallelism(false, 1))
        assertTrue(shouldSerializeNativeColdLoads(true, 1, true))
        assertTrue(shouldSerializeNativeColdLoads(false, 5, true))
        assertTrue(shouldSerializeNativeColdLoads(false, 1, false))
        assertFalse(shouldSerializeNativeColdLoads(false, 1, true))
    }

    @Test
    fun constrainedActiveGemmaWarmsFallbackOnlyWhenMemoryAdmissionProtectsTheLoad() {
        assertTrue(
            shouldWarmFallbackTranslationInBackground(
                constrainedMemoryMode = true,
                gemmaPriorityActive = true,
            ),
        )
        assertFalse(
            shouldWarmFallbackTranslationInBackground(
                constrainedMemoryMode = true,
                gemmaPriorityActive = true,
                memoryAdmissionEnabled = false,
            ),
        )
        assertTrue(
            shouldWarmFallbackTranslationInBackground(
                constrainedMemoryMode = true,
                gemmaPriorityActive = false,
            ),
        )
        assertTrue(
            shouldWarmFallbackTranslationInBackground(
                constrainedMemoryMode = false,
                gemmaPriorityActive = true,
            ),
        )
    }

    @Test
    fun constrainedBroadcastPinsGemmaFailureToFallbackUntilNextSession() {
        assertFalse(shouldRetryGemmaWithinBroadcast(constrainedMemoryMode = true))
        assertTrue(shouldRetryGemmaWithinBroadcast(constrainedMemoryMode = false))
    }

    @Test
    fun eligibleGemmaIsWarmedBeforeItCanBecomeActive() = runBlocking {
        var warmedLanguage: String? = null
        var cleaned = false

        val result = prepareGemmaBroadcastWarmup(
            eligible = true,
            fallbackReady = true,
            priorityLanguageTag = "ja",
            warmup = { warmedLanguage = it },
            cleanupAfterFailure = { cleaned = true },
        )

        assertTrue(warmedLanguage == "ja")
        assertFalse(cleaned)
        assertTrue(result.active)
        assertNull(result.warning)
    }

    @Test
    fun ineligibleGemmaNeverRunsAHiddenWarmup() = runBlocking {
        var warmed = false

        val result = prepareGemmaBroadcastWarmup(
            eligible = false,
            fallbackReady = true,
            priorityLanguageTag = "en",
            warmup = { warmed = true },
            cleanupAfterFailure = { fail("Ineligible Gemma must not be cleaned") },
        )

        assertFalse(warmed)
        assertFalse(result.active)
        assertNull(result.warning)
    }

    @Test
    fun temporaryMemoryPressureSerializesButDoesNotVetoGemmaWarmup() = runBlocking {
        var warmupCalls = 0
        val eligible = isGemmaBroadcastEligible(
            requested = true,
            languagePairSupported = true,
            hardwareSupported = true,
            modelReady = true,
        )

        val result = prepareGemmaBroadcastWarmup(
            eligible = eligible,
            fallbackReady = true,
            priorityLanguageTag = "en",
            warmup = { warmupCalls += 1 },
            cleanupAfterFailure = { fail("Successful Gemma must not be cleaned") },
        )

        assertTrue(eligible)
        assertEquals(1, warmupCalls)
        assertTrue(result.active)
        assertNull(result.warning)
    }

    @Test
    fun warmupFailureSelectsPreparedFallbackBeforeListening() = runBlocking {
        var cleaned = false
        val result = prepareGemmaBroadcastWarmup(
            eligible = true,
            fallbackReady = true,
            priorityLanguageTag = "en",
            warmup = { error("worker init failed") },
            cleanupAfterFailure = { cleaned = true },
        )

        assertTrue(cleaned)
        assertFalse(result.active)
        assertTrue(result.warning.orEmpty().contains("경량 오프라인 번역"))
        assertTrue(result.warning.orEmpty().contains("worker init failed"))
    }

    @Test
    fun warmupFailureWithoutFallbackIsIsolatedToThePriorityChannel() = runBlocking {
        var cleaned = false

        val result = prepareGemmaBroadcastWarmup(
            eligible = true,
            fallbackReady = false,
            priorityLanguageTag = "zh",
            warmup = { error("model init failed") },
            cleanupAfterFailure = { cleaned = true },
        )

        assertTrue(cleaned)
        assertFalse(result.active)
        assertTrue(result.warning.orEmpty().contains("해당 언어만"))
        assertTrue(result.warning.orEmpty().contains("model init failed"))
    }

    @Test
    fun cancelledSessionNeverFallsBackOrReachesListening() = runBlocking {
        val cancellation = CancellationException("operator stopped")

        try {
            prepareGemmaBroadcastWarmup(
                eligible = true,
                fallbackReady = true,
                priorityLanguageTag = "nl",
                warmup = { throw cancellation },
                cleanupAfterFailure = { fail("Session cancellation uses lifecycle cleanup") },
            )
            fail("Expected cancellation")
        } catch (error: CancellationException) {
            assertSame(cancellation, error)
        }
    }

    @Test
    fun cleanupFailureKeepsGemmaClosedButDoesNotBlockFallbackOrServerStartup() = runBlocking {
        val warmupError = IllegalStateException("warmup failed")
        val cleanupError = IllegalStateException("unbind failed")

        val result = prepareGemmaBroadcastWarmup(
            eligible = true,
            fallbackReady = true,
            priorityLanguageTag = "en",
            warmup = { throw warmupError },
            cleanupAfterFailure = { throw cleanupError },
        )

        assertFalse(result.active)
        assertTrue(result.warning.orEmpty().contains("경량 오프라인 번역"))
        assertTrue(result.warning.orEmpty().contains("이 방송에서는 재시도하지 않음"))
        assertTrue(result.warning.orEmpty().contains("unbind failed"))
    }
}
