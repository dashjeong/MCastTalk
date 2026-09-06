package app.guidecast.provider.moonshine.tts

import android.os.RemoteException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoonshineTtsAsyncRequestsTest {
    @Test
    fun `stream completion is independent of callback arrival order`() {
        assertFalse(
            moonshineStreamCanComplete(
                streamCompleted = false,
                workerFinished = true,
                failurePresent = false,
            ),
        )
        assertFalse(
            moonshineStreamCanComplete(
                streamCompleted = true,
                workerFinished = false,
                failurePresent = false,
            ),
        )
        assertTrue(
            moonshineStreamCanComplete(
                streamCompleted = true,
                workerFinished = true,
                failurePresent = false,
            ),
        )
        assertFalse(
            moonshineStreamCanComplete(
                streamCompleted = true,
                workerFinished = true,
                failurePresent = true,
            ),
        )
    }

    @Test
    fun `death link requires both client and transferred native terminal states`() {
        assertFalse(
            moonshineRequestMayUnlinkDeathRecipient(
                clientFinished = false,
                nativeSubmissionPlanned = true,
                nativeFinished = true,
            ),
        )
        assertFalse(
            moonshineRequestMayUnlinkDeathRecipient(
                clientFinished = true,
                nativeSubmissionPlanned = true,
                nativeFinished = false,
            ),
        )
        assertTrue(
            moonshineRequestMayUnlinkDeathRecipient(
                clientFinished = true,
                nativeSubmissionPlanned = true,
                nativeFinished = true,
            ),
        )
        assertTrue(
            moonshineRequestMayUnlinkDeathRecipient(
                clientFinished = true,
                nativeSubmissionPlanned = false,
                nativeFinished = false,
            ),
        )
    }

    @Test
    fun `single language process accepts exactly one native lane`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val callback = RecordingServiceCallback()
        val dispatcher = MoonshineTtsAsyncRequestDispatcher(
            scope = scope,
            maxConcurrentOperations = 1,
            maxPendingOperations = 2,
        )
        try {
            assertTrue(
                dispatcher.submit(CLIENT_A, 1L, "en", callback) { "prepared" },
            )
            assertEquals("prepared", withTimeout(1_000L) { callback.success.await() })
            withTimeout(1_000L) { callback.finished.await() }
        } finally {
            dispatcher.shutdown {}
            scope.cancel()
        }
    }

    @Test
    fun `local worker death preserves RemoteException for connection retry`() = runBlocking {
        supervisorScope {
            lateinit var callback: MoonshineTtsClientCallback
            val request = async(start = CoroutineStart.UNDISPATCHED) {
                awaitMoonshineTtsRequest(
                    start = { callback = it },
                    cancel = {},
                )
            }

            callback.onFailure(RemoteException("worker died during model download"))
            assertFalse("failure escaped before worker cleanup", request.isCompleted)
            callback.onFinished()
            val error = runCatching { request.await() }.exceptionOrNull()

            assertTrue(error is RemoteException)
        }
    }

    @Test
    fun `ordinary serialized worker error remains a non transport failure`() = runBlocking {
        supervisorScope {
            lateinit var callback: MoonshineTtsClientCallback
            val request = async(start = CoroutineStart.UNDISPATCHED) {
                awaitMoonshineTtsRequest(
                    start = { callback = it },
                    cancel = {},
                )
            }

            callback.onError("model checksum mismatch")
            callback.onFinished()
            val error = runCatching { request.await() }.exceptionOrNull()

            assertTrue(error is IllegalStateException)
            assertFalse(error is RemoteException)
            assertTrue(error?.message.orEmpty().contains("checksum mismatch"))
        }
    }

    @Test
    fun `worker failure keeps actionable native cause in operator status`() {
        val message = moonshineWorkerFailureMessage(
            IllegalStateException(
                "Moonshine prepare failed",
                IllegalArgumentException("QLinearMatMul\n operator unavailable"),
            ),
        )

        assertTrue(message.contains("Moonshine prepare failed"))
        assertTrue(message.contains("QLinearMatMul operator unavailable"))
        assertTrue(message.contains("방송은 계속"))
    }

    @Test
    fun `worker failure without a message still identifies exception class`() {
        val message = moonshineWorkerFailureMessage(IllegalStateException())

        assertTrue(message.contains("IllegalStateException"))
    }

    @Test
    fun `cancelled binding attempt cannot start after its first cleanup ran`() {
        val attempt = MoonshineTtsBindingAttemptState()

        assertTrue(attempt.mayStartBinding(continuationActive = true))
        attempt.cancel()

        assertFalse(attempt.mayStartBinding(continuationActive = true))
        assertFalse(attempt.mayStartBinding(continuationActive = false))
        assertTrue(attempt.wasCancelled)
    }

    @Test
    fun `blocking fake times out without blocking next request and discards late callback`() =
        runBlocking {
            val executor = Executors.newSingleThreadExecutor()
            val firstStarted = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            val lateCallbackSent = CountDownLatch(1)
            val cancelCalls = AtomicInteger(0)
            val discardedPaths = CopyOnWriteArrayList<String>()
            try {
                val first = async(Dispatchers.Default) {
                    withTimeoutOrNull(200L) {
                        awaitMoonshineTtsRequest(
                            start = { callback ->
                                executor.execute {
                                    firstStarted.countDown()
                                    releaseFirst.await()
                                    callback.onSynthesized("late-first.pcm")
                                    callback.onFinished()
                                    lateCallbackSent.countDown()
                                }
                            },
                            cancel = { cancelCalls.incrementAndGet() },
                            discardLateSynthesis = discardedPaths::add,
                        )
                    }
                }
                assertTrue(firstStarted.await(1, TimeUnit.SECONDS))
                assertNull(withTimeout(1_000L) { first.await() })
                assertEquals(1, cancelCalls.get())

                val nextCancelCalls = AtomicInteger(0)
                val next = withTimeout(1_000L) {
                    awaitMoonshineTtsRequest(
                        start = { callback ->
                            callback.onSynthesized("next.pcm")
                            callback.onFinished()
                        },
                        cancel = { nextCancelCalls.incrementAndGet() },
                    )
                }
                assertEquals(MoonshineTtsClientResult.Synthesized("next.pcm"), next)
                assertEquals(0, nextCancelCalls.get())

                releaseFirst.countDown()
                assertTrue(lateCallbackSent.await(1, TimeUnit.SECONDS))
                assertEquals(listOf("late-first.pcm"), discardedPaths)
            } finally {
                releaseFirst.countDown()
                executor.shutdownNow()
            }
        }

    @Test
    fun `client does not complete before worker confirms registry cleanup`() = runBlocking {
        lateinit var callback: MoonshineTtsClientCallback
        val request = async(start = CoroutineStart.UNDISPATCHED) {
            awaitMoonshineTtsRequest(
                start = { callback = it },
                cancel = {},
            )
        }

        callback.onPrepared()
        assertFalse("result escaped before onFinished", request.isCompleted)
        callback.onFinished()

        assertEquals(MoonshineTtsClientResult.Prepared, withTimeout(1_000L) { request.await() })
    }

    @Test
    fun `cancelled non cooperative operation queues same language without blocking escape lane`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val dispatcher = MoonshineTtsAsyncRequestDispatcher(
                scope,
                maxConcurrentOperations = 2,
                maxPendingOperations = 2,
            )
            val firstStarted = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            val first = RecordingServiceCallback()
            try {
                assertTrue(
                    dispatcher.submit(CLIENT_A, 1L, "en", first) {
                        firstStarted.countDown()
                        releaseFirst.await()
                        "late-first.pcm"
                    },
                )
                assertTrue(firstStarted.await(1, TimeUnit.SECONDS))
                dispatcher.cancel(CLIENT_A, 1L)

                val sameLanguage = RecordingServiceCallback()
                val sameLanguageStarted = CountDownLatch(1)
                assertTrue(
                    dispatcher.submit(CLIENT_A, 2L, "en", sameLanguage) {
                        sameLanguageStarted.countDown()
                        "same-language-next.pcm"
                    },
                )
                assertFalse(sameLanguageStarted.await(100, TimeUnit.MILLISECONDS))

                val nextLanguage = RecordingServiceCallback()
                assertTrue(
                    dispatcher.submit(CLIENT_A, 3L, "ja", nextLanguage) { "next.pcm" },
                )
                assertEquals("next.pcm", withTimeout(1_000L) { nextLanguage.success.await() })

                releaseFirst.countDown()
                assertEquals("late-first.pcm", withTimeout(1_000L) { first.discarded.await() })
                withTimeout(1_000L) { first.finished.await() }
                assertEquals(
                    "same-language-next.pcm",
                    withTimeout(1_000L) { sameLanguage.success.await() },
                )
            } finally {
                releaseFirst.countDown()
                val stopped = CompletableDeferred<Unit>()
                dispatcher.shutdown { stopped.complete(Unit) }
                withTimeout(1_000L) { stopped.await() }
                scope.cancel()
            }
        }

    @Test
    fun `bounded dispatcher rejects work beyond its native operation lanes`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dispatcher = MoonshineTtsAsyncRequestDispatcher(
            scope,
            maxConcurrentOperations = 2,
            maxPendingOperations = 0,
        )
        val release = CountDownLatch(1)
        val started = CountDownLatch(2)
        try {
            repeat(2) { index ->
                assertTrue(
                    dispatcher.submit(
                        CLIENT_A,
                        (index + 1).toLong(),
                        "language-$index",
                        RecordingServiceCallback(),
                    ) {
                        started.countDown()
                        release.await()
                        null
                    },
                )
            }
            assertTrue(started.await(1, TimeUnit.SECONDS))

            val rejected = RecordingServiceCallback()
            assertTrue(!dispatcher.submit(CLIENT_A, 3L, "third-language", rejected) { null })
            assertTrue(rejected.errors.single().contains("busy"))
            assertTrue(rejected.finished.isCompleted)
        } finally {
            release.countDown()
            val stopped = CompletableDeferred<Unit>()
            dispatcher.shutdown { stopped.complete(Unit) }
            withTimeout(1_000L) { stopped.await() }
            scope.cancel()
        }
    }

    @Test
    fun `dispatcher forwards streaming PCM but suppresses late frames after cancellation`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val dispatcher = MoonshineTtsAsyncRequestDispatcher(
                scope,
                maxConcurrentOperations = 2,
                maxPendingOperations = 1,
            )
            val firstFrameSent = CountDownLatch(1)
            val releaseNative = CountDownLatch(1)
            val callback = RecordingServiceCallback()
            try {
                assertTrue(
                    dispatcher.submit(CLIENT_A, 41L, "en", callback) { _, pcm ->
                        pcm(byteArrayOf(1, 2))
                        firstFrameSent.countDown()
                        releaseNative.await()
                        pcm(byteArrayOf(3, 4))
                        null
                    },
                )
                assertTrue(firstFrameSent.await(1, TimeUnit.SECONDS))
                dispatcher.cancel(CLIENT_A, 41L)
                releaseNative.countDown()
                withTimeout(1_000L) { callback.finished.await() }

                assertEquals(listOf(byteArrayOf(1, 2).toList()), callback.pcm.map(ByteArray::toList))
            } finally {
                releaseNative.countDown()
                val stopped = CompletableDeferred<Unit>()
                dispatcher.shutdown { stopped.complete(Unit) }
                withTimeout(1_000L) { stopped.await() }
                scope.cancel()
            }
        }

    @Test
    fun `client namespace prevents request id collision and isolates cancellation`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dispatcher = MoonshineTtsAsyncRequestDispatcher(
            scope,
            maxConcurrentOperations = 2,
            maxPendingOperations = 2,
        )
        val releaseA = CountDownLatch(1)
        val releaseB = CountDownLatch(1)
        val started = CountDownLatch(2)
        val a = RecordingServiceCallback()
        val b = RecordingServiceCallback()
        try {
            assertTrue(
                dispatcher.submit(CLIENT_A, 1L, "en", a) {
                    started.countDown()
                    releaseA.await()
                    "a.pcm"
                },
            )
            assertTrue(
                dispatcher.submit(CLIENT_B, 1L, "ja", b) {
                    started.countDown()
                    releaseB.await()
                    "b.pcm"
                },
            )
            assertTrue(started.await(1, TimeUnit.SECONDS))

            dispatcher.cancel(CLIENT_A, 1L)
            releaseA.countDown()
            assertEquals("a.pcm", withTimeout(1_000L) { a.discarded.await() })
            assertFalse("cancelling client A cancelled client B", b.finished.isCompleted)

            releaseB.countDown()
            assertEquals("b.pcm", withTimeout(1_000L) { b.success.await() })
        } finally {
            releaseA.countDown()
            releaseB.countDown()
            val stopped = CompletableDeferred<Unit>()
            dispatcher.shutdown { stopped.complete(Unit) }
            withTimeout(1_000L) { stopped.await() }
            scope.cancel()
        }
    }

    @Test
    fun `cancelling queued middle request preserves predecessor barrier for successor`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val dispatcher = MoonshineTtsAsyncRequestDispatcher(
                scope,
                maxConcurrentOperations = 2,
                maxPendingOperations = 3,
            )
            val releaseFirst = CountDownLatch(1)
            val firstStarted = CountDownLatch(1)
            val thirdStarted = CountDownLatch(1)
            val first = RecordingServiceCallback()
            val second = RecordingServiceCallback()
            val third = RecordingServiceCallback()
            try {
                assertTrue(
                    dispatcher.submit(CLIENT_A, 1L, "en", first) {
                        firstStarted.countDown()
                        releaseFirst.await()
                        "first.pcm"
                    },
                )
                assertTrue(firstStarted.await(1, TimeUnit.SECONDS))
                assertTrue(
                    dispatcher.submit(CLIENT_A, 2L, "en", second) { "must-not-run.pcm" },
                )
                assertTrue(
                    dispatcher.submit(CLIENT_A, 3L, "en", third) {
                        thirdStarted.countDown()
                        "third.pcm"
                    },
                )

                dispatcher.cancel(CLIENT_A, 2L)
                assertFalse(
                    "successor bypassed a still-running native predecessor",
                    thirdStarted.await(150, TimeUnit.MILLISECONDS),
                )

                releaseFirst.countDown()
                assertEquals("first.pcm", withTimeout(1_000L) { first.success.await() })
                assertEquals("third.pcm", withTimeout(1_000L) { third.success.await() })
            } finally {
                releaseFirst.countDown()
                val stopped = CompletableDeferred<Unit>()
                dispatcher.shutdown { stopped.complete(Unit) }
                withTimeout(1_000L) { stopped.await() }
                scope.cancel()
            }
        }

    @Test
    fun `shutdown cannot close runtime across an in flight registration`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val registrationEntered = CountDownLatch(1)
        val allowRegistration = CountDownLatch(1)
        val dispatcher = MoonshineTtsAsyncRequestDispatcher(
            scope = scope,
            maxConcurrentOperations = 2,
            beforeRegistrationForTest = {
                registrationEntered.countDown()
                allowRegistration.await()
            },
        )
        val callback = RecordingServiceCallback()
        val shutdownIdle = CompletableDeferred<Unit>()
        try {
            val submit = async(Dispatchers.Default) {
                dispatcher.submit(CLIENT_A, 1L, "en", callback) { "result.pcm" }
            }
            assertTrue(registrationEntered.await(1, TimeUnit.SECONDS))
            val shutdown = async(Dispatchers.Default) {
                dispatcher.shutdown { shutdownIdle.complete(Unit) }
            }

            assertNull(
                "shutdown closed the runtime while registration still owned the lifecycle lock",
                withTimeoutOrNull(150L) { shutdownIdle.await() },
            )
            allowRegistration.countDown()
            assertTrue(withTimeout(1_000L) { submit.await() })
            withTimeout(1_000L) { shutdown.await() }
            withTimeout(1_000L) { shutdownIdle.await() }
            withTimeout(1_000L) { callback.finished.await() }
        } finally {
            allowRegistration.countDown()
            scope.cancel()
        }
    }

    @Test
    fun `release gate returns on deadline and allows the next client operation`() = runBlocking {
        val registry = MoonshineTtsClientOperationRegistry()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val blockingStarted = CountDownLatch(1)
        val releaseBlocking = CountDownLatch(1)
        val blocking = scope.launch {
            blockingStarted.countDown()
            releaseBlocking.await()
        }
        try {
            assertTrue(registry.register(1L, blocking))
            assertTrue(blockingStarted.await(1, TimeUnit.SECONDS))

            val elapsedMillis = measureTimeMillis {
                assertFalse(registry.pauseAndCancel(timeoutMillis = 100L))
            }
            assertTrue("release exceeded its bounded drain: ${elapsedMillis}ms", elapsedMillis < 1_000L)

            val racedIntoPausedEpoch = SupervisorJob()
            assertFalse(registry.register(2L, racedIntoPausedEpoch))
            racedIntoPausedEpoch.cancel()

            registry.resume()
            val next = SupervisorJob()
            assertTrue(registry.register(3L, next))
            registry.finish(3L, next)
            next.cancel()
        } finally {
            releaseBlocking.countDown()
            withTimeout(1_000L) { blocking.join() }
            registry.finish(1L, blocking)
            scope.cancel()
        }
    }

    @Test
    fun `rapid five language replacement cancels only removed voice and rejects its late bind`() =
        runBlocking {
            val registry = MoonshineTtsClientOperationRegistry()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val firstSelection = linkedSetOf("en", "ja", "zh", "nl", "es")
            val replacement = linkedSetOf("ar", "ja", "zh", "nl", "es")
            val jobs = linkedMapOf<String, kotlinx.coroutines.Job>()
            try {
                assertTrue(
                    registry.selectLanguagesAndCancelUnselected(
                        firstSelection,
                        timeoutMillis = 100L,
                    ).drained,
                )
                firstSelection.forEachIndexed { index, languageTag ->
                    val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        awaitCancellation()
                    }
                    jobs[languageTag] = job
                    assertTrue(registry.register((index + 1).toLong(), languageTag, job))
                }

                val result = registry.selectLanguagesAndCancelUnselected(
                    replacement,
                    timeoutMillis = 1_000L,
                )

                assertTrue(result.drained)
                assertTrue(result.pendingLanguageTags.isEmpty())
                assertTrue(requireNotNull(jobs["en"]).isCancelled)
                replacement.intersect(firstSelection).forEach { languageTag ->
                    assertFalse("selected sibling was cancelled: $languageTag", jobs.getValue(languageTag).isCancelled)
                }
                assertEquals(replacement, registry.selectedLanguagesForTest())

                val obsoleteEnglish = SupervisorJob()
                assertFalse(registry.register(20L, "en", obsoleteEnglish))
                obsoleteEnglish.cancel()

                val replacementArabic = SupervisorJob()
                assertTrue(registry.register(21L, "ar", replacementArabic))
                registry.finish(21L, replacementArabic)
                replacementArabic.cancel()
                assertTrue(
                    requireNotNull(registry.selectedLanguagesForTest()).size <=
                        MAX_MOONSHINE_BROADCAST_LANGUAGES,
                )
            } finally {
                jobs.forEach { (languageTag, job) ->
                    job.cancel()
                    registry.finish((firstSelection.indexOf(languageTag) + 1).toLong(), job)
                }
                scope.cancel()
            }
        }

    @Test
    fun `language boundary accepts seven and rejects an eighth selection`() = runBlocking {
        val registry = MoonshineTtsClientOperationRegistry()
        val seven = setOf("en", "ja", "zh", "zh-TW", "vi", "nl", "es")

        val result = registry.selectLanguagesAndCancelUnselected(
            languageTags = seven,
            timeoutMillis = 100L,
        )

        assertTrue(result.drained)
        assertEquals(seven, registry.selectedLanguagesForTest())
        assertTrue(
            runCatching {
                registry.selectLanguagesAndCancelUnselected(
                    languageTags = seven + "ar",
                    timeoutMillis = 100L,
                )
            }.exceptionOrNull() is IllegalArgumentException,
        )
    }

    @Test
    fun `empty replacement retires every Moonshine voice`() = runBlocking {
        val registry = MoonshineTtsClientOperationRegistry()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val english = scope.launch(start = CoroutineStart.UNDISPATCHED) { awaitCancellation() }
        try {
            registry.selectLanguagesAndCancelUnselected(setOf("en"), timeoutMillis = 100L)
            assertTrue(registry.register(1L, "en", english))

            val result = registry.selectLanguagesAndCancelUnselected(
                languageTags = emptySet(),
                timeoutMillis = 1_000L,
            )

            assertTrue(result.drained)
            assertTrue(english.isCancelled)
            assertEquals(emptySet<String>(), registry.selectedLanguagesForTest())
            val lateEnglish = SupervisorJob()
            assertFalse(registry.register(2L, "en", lateEnglish))
            lateEnglish.cancel()
        } finally {
            registry.finish(1L, english)
            scope.cancel()
        }
    }

    private class RecordingServiceCallback : MoonshineTtsServiceCallback {
        val success = CompletableDeferred<String?>()
        val discarded = CompletableDeferred<String?>()
        val finished = CompletableDeferred<Unit>()
        val errors = CopyOnWriteArrayList<String>()
        val pcm = CopyOnWriteArrayList<ByteArray>()

        override fun onSuccess(result: String?) {
            success.complete(result)
        }

        override fun onError(message: String) {
            errors += message
        }

        override fun onPcmChunk(pcm: ByteArray) {
            this.pcm += pcm.copyOf()
        }

        override fun onDiscardedResult(result: String?) {
            discarded.complete(result)
        }

        override fun onFinished() {
            finished.complete(Unit)
        }
    }

    private companion object {
        const val CLIENT_A = "client-a-test"
        const val CLIENT_B = "client-b-test"
    }
}
