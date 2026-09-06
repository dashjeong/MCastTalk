package app.guidecast.transmitter

import app.guidecast.provider.moonshine.stt.MoonshineSpeechLanguageReadiness
import app.guidecast.provider.moonshine.stt.MoonshineSpeechLanguageStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoonshineRecognitionRestartAdmissionTest {
    @Test
    fun `reclaimed worker waits for sibling cold load before native restart`() = runBlocking {
        val coordinator = NativeColdLoadCoordinator(maxParallelLoads = 2)
        val admission = NativeSupportMemoryAdmission.forMemory(
            availableMemoryBytes = 4L * 1_024 * 1_024 * 1_024,
            androidLowMemoryThresholdBytes = 256L * 1_024 * 1_024,
            systemLowMemory = false,
        )
        val sibling = coordinator.acquire(
            key = "gemma:sibling",
            serializeWithAllColdLoads = false,
            currentAdmission = admission,
        )
        val admissionRequested = CompletableDeferred<Unit>()
        val nativeWarmStarted = CompletableDeferred<Unit>()
        var workerActive = false
        var nativeWarmCalls = 0

        val restart = async(start = CoroutineStart.UNDISPATCHED) {
            ensureMoonshineWorkerForRecognition(
                languageTag = "ko-KR",
                hasActiveWorker = { workerActive },
                activeStatus = ::readyStatus,
                prepareNative = {
                    nativeWarmCalls += 1
                    nativeWarmStarted.complete(Unit)
                    workerActive = true
                    readyStatus()
                },
                warmWithNativeAdmission = { _, warm ->
                    admissionRequested.complete(Unit)
                    val ticket = coordinator.acquire(
                        key = "stt:ko",
                        // A reclaimed native generation is uncertain memory, so live recovery
                        // takes the exclusive lane until the sibling cold load has completed.
                        serializeWithAllColdLoads = true,
                        currentAdmission = admission,
                    )
                    try {
                        warm()
                    } finally {
                        ticket.close()
                    }
                },
            )
        }

        admissionRequested.await()
        assertFalse(nativeWarmStarted.isCompleted)
        assertEquals(0, nativeWarmCalls)

        sibling.close()
        assertTrue(restart.await().isReady)
        assertTrue(nativeWarmStarted.isCompleted)
        assertEquals(1, nativeWarmCalls)
    }

    @Test
    fun `normal prepared restart stays hot and does not request admission`() = runBlocking {
        var admissionCalls = 0
        var nativeWarmCalls = 0

        val status = ensureMoonshineWorkerForRecognition(
            languageTag = "ko-KR",
            hasActiveWorker = { true },
            activeStatus = ::readyStatus,
            prepareNative = {
                nativeWarmCalls += 1
                readyStatus()
            },
            warmWithNativeAdmission = { _, warm ->
                admissionCalls += 1
                warm()
            },
        )

        assertTrue(status.isReady)
        assertEquals(0, admissionCalls)
        assertEquals(0, nativeWarmCalls)
    }

    @Test
    fun `worker prepared by a concurrent owner is reused after admission wait`() = runBlocking {
        var workerActive = false
        var nativeWarmCalls = 0
        var admissionCalls = 0

        val status = ensureMoonshineWorkerForRecognition(
            languageTag = "ko-KR",
            hasActiveWorker = { workerActive },
            activeStatus = ::readyStatus,
            prepareNative = {
                nativeWarmCalls += 1
                readyStatus()
            },
            warmWithNativeAdmission = { _, warm ->
                admissionCalls += 1
                workerActive = true
                warm()
            },
        )

        assertTrue(status.isReady)
        assertEquals(1, admissionCalls)
        assertEquals(0, nativeWarmCalls)
    }

    private fun readyStatus() = MoonshineSpeechLanguageStatus(
        readiness = MoonshineSpeechLanguageReadiness.READY,
        message = "streaming ready",
        progress = 1f,
    )
}
