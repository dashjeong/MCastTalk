package app.guidecast.transmitter

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RecognitionAlternativePreparationTest {
    @Test fun readyPrimaryNeverRequestsOptionalDownload() = runBlocking {
        var downloads = 0
        val result = prepareRecognitionAlternative(true, { "missing" }, {
            downloads++
            awaitCancellation()
        })
        assertEquals("missing", result.getOrThrow())
        assertEquals(0, downloads)
    }

    @Test fun installedAndroidRemainsPreferredCandidate() = runBlocking {
        assertEquals("ready", prepareRecognitionAlternative(true, { "ready" }, {
            error("must not download")
        }).getOrThrow())
    }

    @Test fun providerSupportTimeoutDoesNotCancelReadyPrimary() = runBlocking {
        val result = prepareRecognitionAlternative(true,
            { withTimeout(20) { awaitCancellation() } }, { "unused" })
        assertTrue(result.isFailure)
        currentCoroutineContext().ensureActive()
    }

    @Test fun noPrimaryPreparesAndroidButBoundsMissingCallback() = runBlocking {
        var called = false
        val result = prepareRecognitionAlternative(false, { "unused" }, {
            called = true
            awaitCancellation()
        }, timeoutMillis = 20)
        assertTrue(called)
        assertTrue(result.isFailure)
    }

    @Test fun userCancellationStillStopsPreparation() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var returned = false
        val job = launch {
            prepareRecognitionAlternative(false, { Unit }, {
                entered.complete(Unit)
                awaitCancellation()
            })
            returned = true
        }
        entered.await()
        job.cancelAndJoin()
        assertFalse(returned)
    }
}
