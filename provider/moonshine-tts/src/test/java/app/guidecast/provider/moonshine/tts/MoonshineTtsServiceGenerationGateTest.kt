package app.guidecast.provider.moonshine.tts

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MoonshineTtsServiceGenerationGateTest {
    @Test
    fun `replacement generation waits until previous native runtime is closed`() = runBlocking {
        val gate = MoonshineTtsServiceGenerationGate()
        val first = gate.beginGeneration()
        val second = gate.beginGeneration()

        first.awaitPredecessorClosed()
        val secondReady = async { second.awaitPredecessorClosed() }
        assertFalse(secondReady.isCompleted)

        first.markClosed()
        withTimeout(1_000L) { secondReady.await() }
        assertTrue(secondReady.isCompleted)
        second.markClosed()
    }

    @Test
    fun `empty middle generation cannot let successor bypass old runtime`() = runBlocking {
        val gate = MoonshineTtsServiceGenerationGate()
        val first = gate.beginGeneration()
        val emptyMiddle = gate.beginGeneration()
        val third = gate.beginGeneration()

        emptyMiddle.markClosed()
        val thirdReady = async { third.awaitPredecessorClosed() }
        assertFalse(thirdReady.isCompleted)

        first.markClosed()
        withTimeout(1_000L) { thirdReady.await() }
        assertTrue(thirdReady.isCompleted)
        third.markClosed()
    }

    @Test
    fun `marking generation closed is idempotent`() = runBlocking {
        val gate = MoonshineTtsServiceGenerationGate()
        val first = gate.beginGeneration()
        val second = gate.beginGeneration()

        first.markClosed()
        first.markClosed()
        withTimeout(1_000L) { second.awaitPredecessorClosed() }
        second.markClosed()
    }

    @Test
    fun `native close failure keeps replacement generation blocked`() = runBlocking {
        val gate = MoonshineTtsServiceGenerationGate()
        val first = gate.beginGeneration()
        val replacement = gate.beginGeneration()

        first.awaitPredecessorClosed()
        val closeFailure = runCatching {
            first.closeRuntimeThenMarkClosed { error("native close failed") }
        }.exceptionOrNull()
        assertTrue(closeFailure?.message.orEmpty().contains("native close failed"))

        val replacementReady = async { replacement.awaitPredecessorClosed() }
        delay(100L)
        assertFalse(
            "replacement bypassed a failed native close",
            replacementReady.isCompleted,
        )
        replacementReady.cancel()
    }

    @Test
    fun `failed runtime close is retried before successor generation is released`() = runBlocking {
        val generationGate = MoonshineTtsServiceGenerationGate()
        val stoppedService = generationGate.beginGeneration()
        val replacementService = generationGate.beginGeneration()
        val runtimeCloseGate = MoonshineTtsRuntimeCloseGate()
        val closeCalls = AtomicInteger(0)
        val replacementReady = async { replacementService.awaitPredecessorClosed() }

        stoppedService.awaitPredecessorClosed()
        val closeError = closeMoonshineTtsRuntimeWithRetry(maxAttempts = 2) {
            stoppedService.closeRuntimeThenMarkClosed {
                runtimeCloseGate.close {
                    if (closeCalls.incrementAndGet() == 1) error("transient native close failure")
                }
            }
        }

        assertNull(closeError)
        withTimeout(1_000L) { replacementReady.await() }
        assertTrue(replacementReady.isCompleted)
        assertEquals(2, closeCalls.get())

        // A later Android lifecycle callback must not close an already confirmed runtime again.
        runtimeCloseGate.close { closeCalls.incrementAndGet() }
        assertEquals(2, closeCalls.get())
        replacementService.markClosed()
    }

    @Test
    fun `exhausted runtime close retries never acknowledge an unconfirmed close`() = runBlocking {
        val generationGate = MoonshineTtsServiceGenerationGate()
        val stoppedService = generationGate.beginGeneration()
        val replacementService = generationGate.beginGeneration()
        val runtimeCloseGate = MoonshineTtsRuntimeCloseGate()
        val closeCalls = AtomicInteger(0)

        stoppedService.awaitPredecessorClosed()
        val closeError = closeMoonshineTtsRuntimeWithRetry(maxAttempts = 2) {
            stoppedService.closeRuntimeThenMarkClosed {
                runtimeCloseGate.close {
                    closeCalls.incrementAndGet()
                    error("persistent native close failure")
                }
            }
        }

        assertTrue(closeError?.message.orEmpty().contains("persistent native close failure"))
        assertEquals(2, closeCalls.get())
        val replacementReady = async { replacementService.awaitPredecessorClosed() }
        delay(100L)
        assertFalse("replacement bypassed an unconfirmed native close", replacementReady.isCompleted)
        replacementReady.cancel()
    }

    @Test
    fun `failed native resource remains tracked until a confirmed retry closes it`() {
        val voice = FakeNativeVoice()
        val resources = linkedMapOf("voice" to voice)

        val firstFailure = runCatching {
            closeMoonshineTtsResources(resources, FakeNativeVoice::close)
        }.exceptionOrNull()

        assertTrue(firstFailure?.message.orEmpty().contains("close failed"))
        assertEquals(setOf("voice"), resources.keys)
        closeMoonshineTtsResources(resources, FakeNativeVoice::close)
        assertTrue(resources.isEmpty())
        assertEquals(2, voice.closeCalls)
    }

    @Test
    fun `partial initialization close failure retains exact native owner for shutdown retry`() {
        val candidate = FakeNativeVoice()
        val retained = linkedMapOf<String, FakeNativeVoice>()
        val initializationFailure = IllegalStateException("load failed")

        val failure = runCatching {
            closeOrRetainFailedMoonshineTtsInitialization(
                key = "en",
                candidate = candidate,
                retainedOwners = retained,
                initializationFailure = initializationFailure,
                closeCandidate = FakeNativeVoice::close,
            )
        }.exceptionOrNull()

        assertTrue(failure is MoonshineTtsEngineCloseNotConfirmedException)
        assertSame(initializationFailure, failure?.cause)
        assertSame(candidate, retained["en"])
        closeMoonshineTtsResources(retained, FakeNativeVoice::close)
        assertTrue(retained.isEmpty())
        assertEquals(2, candidate.closeCalls)
    }

    @Test
    fun `confirmed partial initialization cleanup preserves original load failure`() {
        val candidate = FakeNativeVoice(failFirstClose = false)
        val retained = linkedMapOf<String, FakeNativeVoice>()
        val initializationFailure = IllegalStateException("load failed")

        val failure = runCatching {
            closeOrRetainFailedMoonshineTtsInitialization(
                key = "en",
                candidate = candidate,
                retainedOwners = retained,
                initializationFailure = initializationFailure,
                closeCandidate = FakeNativeVoice::close,
            )
        }.exceptionOrNull()

        assertSame(initializationFailure, failure)
        assertTrue(retained.isEmpty())
        assertEquals(1, candidate.closeCalls)
    }

    @Test
    fun `replacement dispatcher cannot enter runtime while old cancelled native call returns`() =
        runBlocking {
            val gate = MoonshineTtsServiceGenerationGate()
            val firstGeneration = gate.beginGeneration()
            val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val firstDispatcher = MoonshineTtsAsyncRequestDispatcher(
                firstScope,
                maxConcurrentOperations = 2,
            )
            val oldNativeStarted = CountDownLatch(1)
            val releaseOldNative = CountDownLatch(1)
            val firstCallback = GateRecordingCallback()

            val secondGeneration = gate.beginGeneration()
            val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val secondDispatcher = MoonshineTtsAsyncRequestDispatcher(
                secondScope,
                maxConcurrentOperations = 2,
            )
            val replacementEnteredRuntime = CountDownLatch(1)
            val secondCallback = GateRecordingCallback()
            try {
                firstGeneration.awaitPredecessorClosed()
                assertTrue(
                    firstDispatcher.submit("old-client", 1L, "en", firstCallback) {
                        oldNativeStarted.countDown()
                        releaseOldNative.await()
                        "old.pcm"
                    },
                )
                assertTrue(oldNativeStarted.await(1, TimeUnit.SECONDS))
                firstDispatcher.cancel("old-client", 1L)
                firstDispatcher.shutdown(firstGeneration::markClosed)

                assertTrue(
                    secondDispatcher.submit("new-client", 1L, "en", secondCallback) {
                        secondGeneration.awaitPredecessorClosed()
                        replacementEnteredRuntime.countDown()
                        "new.pcm"
                    },
                )
                assertFalse(
                    "replacement entered a second runtime before old JNI returned",
                    replacementEnteredRuntime.await(150, TimeUnit.MILLISECONDS),
                )

                releaseOldNative.countDown()
                assertTrue(replacementEnteredRuntime.await(1, TimeUnit.SECONDS))
                assertTrue(withTimeout(1_000L) { secondCallback.success.await() } == "new.pcm")
            } finally {
                releaseOldNative.countDown()
                val secondStopped = CompletableDeferred<Unit>()
                secondDispatcher.shutdown {
                    secondGeneration.markClosed()
                    secondStopped.complete(Unit)
                }
                withTimeout(1_000L) { secondStopped.await() }
                firstScope.cancel()
                secondScope.cancel()
            }
        }

    private class GateRecordingCallback : MoonshineTtsServiceCallback {
        val success = CompletableDeferred<String?>()

        override fun onSuccess(result: String?) {
            success.complete(result)
        }

        override fun onError(message: String) = Unit

        override fun onFinished() = Unit
    }

    private class FakeNativeVoice(
        private val failFirstClose: Boolean = true,
    ) {
        var closeCalls = 0
            private set

        fun close() {
            closeCalls += 1
            if (failFirstClose && closeCalls == 1) error("close failed")
        }
    }
}
