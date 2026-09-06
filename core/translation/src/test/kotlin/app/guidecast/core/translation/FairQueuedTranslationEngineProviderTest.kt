package app.guidecast.core.translation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FairQueuedTranslationEngineProviderTest {
    @Test
    fun `B2-SCHED-01 seven languages receive fair rounds and retain per-language FIFO`() = runTest {
        val blocker = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val provider = provider(
            delegate = TranslationEngineProvider {
                TextTranslationEngine { text, _, target ->
                    if (text == "block") blocker.await()
                    calls += "$target:$text"
                    "$target:$text"
                }
            },
        )

        val active = async { provider.engineFor("seed").translate("block", "ko", "seed") }
        runCurrent()
        val languages = listOf("en", "ja", "zh", "zh-TW", "vi", "es", "ar")
        val queued = buildList {
            languages.forEach { language ->
                add(async { provider.engineFor(language).translate("1", "ko", language) })
            }
            languages.forEach { language ->
                add(async { provider.engineFor(language).translate("2", "ko", language) })
            }
        }
        runCurrent()

        blocker.complete(Unit)
        assertEquals("seed:block", active.await())
        queued.awaitAll()

        assertEquals(
            listOf("seed:block") +
                languages.map { "$it:1" } +
                languages.map { "$it:2" },
            calls,
        )
        provider.close()
        advanceUntilIdle()
    }

    @Test
    fun `B2-SCHED-01 scheduling cost changes order but does not truncate a meaning unit`() = runTest {
        val blocker = CompletableDeferred<Unit>()
        val calls = mutableListOf<Pair<String, String>>()
        val longMeaningUnit = "긴 문장".repeat(300)
        val provider = provider(
            config = config(costQuantum = 16),
            delegate = TranslationEngineProvider {
                TextTranslationEngine { text, _, target ->
                    if (target == "seed") blocker.await()
                    calls += target to text
                    text
                }
            },
        )
        val active = async { provider.engineFor("seed").translate("block", "ko", "seed") }
        runCurrent()
        val long = async { provider.engineFor("en").translate(longMeaningUnit, "ko", "en") }
        val short = async { provider.engineFor("ja").translate("짧음", "ko", "ja") }
        runCurrent()

        blocker.complete(Unit)
        active.await()
        assertEquals("짧음", short.await())
        assertEquals(longMeaningUnit, long.await())

        assertEquals("ja", calls[1].first)
        assertEquals(longMeaningUnit, calls[2].second)
        provider.close()
        advanceUntilIdle()
    }

    @Test
    fun `B2-SCHED-02 queue timeout never invokes delegate and differs from inference timeout`() = runTest {
        val blocker = CompletableDeferred<Unit>()
        var delegateCalls = 0
        val provider = provider(
            config = config(queueWaitTimeoutMillis = 100, inferenceTimeoutMillis = 1_000),
            delegate = TranslationEngineProvider {
                TextTranslationEngine { text, _, _ ->
                    delegateCalls++
                    if (text == "active") blocker.await()
                    text
                }
            },
        )
        val active = async { provider.engineFor("en").translate("active", "ko", "en") }
        runCurrent()
        val waiting = async {
            runCatching { provider.engineFor("ja").translate("waiting", "ko", "ja") }
        }
        runCurrent()

        advanceTimeBy(101)
        runCurrent()

        assertTrue(waiting.await().exceptionOrNull() is TranslationQueueWaitTimeoutException)
        assertEquals(1, delegateCalls)
        blocker.complete(Unit)
        assertEquals("active", active.await())
        provider.close()
        advanceUntilIdle()
    }

    @Test
    fun `B2-SCHED-02 inference deadline begins only after admission`() = runTest {
        val firstGate = CompletableDeferred<Unit>()
        val provider = provider(
            config = config(queueWaitTimeoutMillis = 1_000, inferenceTimeoutMillis = 100),
            delegate = TranslationEngineProvider {
                TextTranslationEngine { text, _, _ ->
                    if (text == "first") firstGate.await() else delay(Long.MAX_VALUE)
                    text
                }
            },
        )
        val first = async { provider.engineFor("en").translate("first", "ko", "en") }
        runCurrent()
        val second = async {
            runCatching { provider.engineFor("ja").translate("second", "ko", "ja") }
        }
        runCurrent()

        advanceTimeBy(90)
        firstGate.complete(Unit)
        assertEquals("first", first.await())
        runCurrent()
        advanceTimeBy(99)
        runCurrent()
        assertFalse(second.isCompleted)

        advanceTimeBy(2)
        runCurrent()
        assertTrue(second.await().exceptionOrNull() is TranslationInferenceTimeoutException)
        provider.close()
        advanceUntilIdle()
    }

    @Test
    fun `B2-SCHED-02 observer separates queue wait from inference time without text`() = runTest {
        val timings = mutableListOf<FairTranslationTiming>()
        val provider = provider(
            config = config(queueWaitTimeoutMillis = 1_000, inferenceTimeoutMillis = 1_000),
            observer = FairTranslationQueueObserver { timings += it },
            nanoTime = { testScheduler.currentTime * 1_000_000L },
            delegate = TranslationEngineProvider {
                TextTranslationEngine { text, _, _ ->
                    delay(if (text == "first") 50 else 20)
                    text
                }
            },
        )
        val first = async { provider.engineFor("en").translate("first", "ko", "en") }
        runCurrent()
        val second = async { provider.engineFor("ja").translate("second", "ko", "ja") }
        runCurrent()
        advanceUntilIdle()

        assertEquals(listOf("first", "second"), awaitAll(first, second))
        assertEquals(0L, timings[0].queueWaitMillis)
        assertEquals(50L, timings[0].inferenceMillis)
        assertEquals(50L, timings[1].queueWaitMillis)
        assertEquals(20L, timings[1].inferenceMillis)
        assertEquals(FairTranslationOutcome.SUCCESS, timings[1].outcome)
        provider.close()
        advanceUntilIdle()
    }

    @Test
    fun `B2-SCHED-03 cancelling a pending sibling does not cancel the active request`() = runTest {
        val blocker = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val provider = provider(
            delegate = TranslationEngineProvider {
                TextTranslationEngine { text, _, _ ->
                    calls += text
                    if (text == "first") blocker.await()
                    text
                }
            },
        )
        val first = async { provider.engineFor("en").translate("first", "ko", "en") }
        runCurrent()
        val sibling = async { provider.engineFor("ja").translate("sibling", "ko", "ja") }
        runCurrent()

        sibling.cancel()
        runCurrent()
        blocker.complete(Unit)

        assertEquals("first", first.await())
        assertTrue(sibling.isCancelled)
        assertEquals(listOf("first"), calls)
        provider.close()
        advanceUntilIdle()
    }

    @Test
    fun `B2-SCHED-03 active cancellation waits for noncooperative delegate termination`() = runTest {
        val cleanupGate = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val provider = provider(
            delegate = TranslationEngineProvider {
                TextTranslationEngine { text, _, _ ->
                    calls += text
                    if (text == "first") {
                        try {
                            awaitCancellation()
                        } finally {
                            withContext(NonCancellable) { cleanupGate.await() }
                        }
                    }
                    text
                }
            },
        )
        val first = async { provider.engineFor("en").translate("first", "ko", "en") }
        runCurrent()
        val second = async { provider.engineFor("ja").translate("second", "ko", "ja") }
        runCurrent()

        first.cancel()
        runCurrent()
        assertEquals(listOf("first"), calls)

        cleanupGate.complete(Unit)
        runCurrent()
        assertEquals("second", second.await())
        assertEquals(listOf("first", "second"), calls)
        provider.close()
        advanceUntilIdle()
    }

    @Test
    fun `B2-SCHED-03 caller context and explicit prior context reach contextual delegate`() = runTest {
        val glossary = TranslationGlossaryContext("궁궐=palace")
        var receivedGlossary: TranslationGlossaryContext? = null
        var receivedPrior: String? = null
        val provider = provider(
            delegate = TranslationEngineProvider {
                object : ContextualTextTranslationEngine {
                    override suspend fun translateWithContext(
                        text: String,
                        contextBefore: String?,
                        sourceLanguageTag: String,
                        targetLanguageTag: String,
                    ): String {
                        receivedGlossary = currentCoroutineContext()[TranslationGlossaryContext]
                        receivedPrior = contextBefore
                        return "translated:$text"
                    }
                }
            },
        )
        val engine = provider.engineFor("en") as ContextualTextTranslationEngine
        val result = withContext(glossary) {
            engine.translateWithContext("현재 문장", "이전 확정문", "ko", "en")
        }

        assertSame(glossary, receivedGlossary)
        assertEquals("이전 확정문", receivedPrior)
        assertEquals("translated:현재 문장", result)
        provider.close()
        advanceUntilIdle()
    }

    @Test
    fun `B2-SCHED-03 failed request does not stop the next request`() = runTest {
        val provider = provider(
            delegate = TranslationEngineProvider {
                TextTranslationEngine { text, _, _ ->
                    if (text == "bad") error("delegate failed")
                    "ok:$text"
                }
            },
        )
        val failed = async {
            runCatching { provider.engineFor("en").translate("bad", "ko", "en") }
        }
        val next = async { provider.engineFor("ja").translate("next", "ko", "ja") }

        assertEquals("delegate failed", failed.await().exceptionOrNull()?.message)
        assertEquals("ok:next", next.await())
        provider.close()
        advanceUntilIdle()
    }

    @Test
    fun `B2-SCHED-03 close rejects new calls clears pending work and stops worker`() = runTest {
        val provider = provider(
            delegate = TranslationEngineProvider {
                TextTranslationEngine { _, _, _ -> awaitCancellation() }
            },
        )
        val active = async {
            runCatching { provider.engineFor("en").translate("active", "ko", "en") }
        }
        runCurrent()
        val pending = async {
            runCatching { provider.engineFor("ja").translate("pending", "ko", "ja") }
        }
        runCurrent()

        provider.close()
        runCurrent()

        assertTrue(provider.isClosed)
        assertFalse(provider.isWorkerActive)
        assertTrue(active.await().exceptionOrNull() is TranslationQueueClosedException)
        assertTrue(pending.await().exceptionOrNull() is TranslationQueueClosedException)
        val rejected = runCatching {
            provider.engineFor("vi").translate("new", "ko", "vi")
        }
        assertTrue(rejected.exceptionOrNull() is TranslationQueueClosedException)
        advanceUntilIdle()
    }

    @Test
    fun `B2-SCHED-03 pending capacity is bounded per language`() = runTest {
        val blocker = CompletableDeferred<Unit>()
        val provider = provider(
            config = config(maxPendingPerLanguage = 1),
            delegate = TranslationEngineProvider {
                TextTranslationEngine { text, _, _ ->
                    if (text == "active") blocker.await()
                    text
                }
            },
        )
        val active = async { provider.engineFor("en").translate("active", "ko", "en") }
        runCurrent()
        val pending = async { provider.engineFor("en").translate("pending", "ko", "en") }
        runCurrent()

        val overflow = runCatching {
            provider.engineFor("en").translate("overflow", "ko", "en")
        }
        assertTrue(overflow.exceptionOrNull() is TranslationQueueFullException)

        blocker.complete(Unit)
        assertEquals("active", active.await())
        assertEquals("pending", pending.await())
        provider.close()
        advanceUntilIdle()
    }

    private fun kotlinx.coroutines.test.TestScope.provider(
        delegate: TranslationEngineProvider,
        config: FairTranslationQueueConfig = config(),
        observer: FairTranslationQueueObserver = FairTranslationQueueObserver.NONE,
        nanoTime: () -> Long = { testScheduler.currentTime * 1_000_000L },
    ): FairQueuedTranslationEngineProvider = FairQueuedTranslationEngineProvider(
        delegate = delegate,
        parentScope = this,
        config = config,
        observer = observer,
        nanoTime = nanoTime,
    )

    private fun config(
        maxPendingPerLanguage: Int = 16,
        queueWaitTimeoutMillis: Long = 10_000,
        inferenceTimeoutMillis: Long = 10_000,
        costQuantum: Int = 256,
    ) = FairTranslationQueueConfig(
        maxPendingPerLanguage = maxPendingPerLanguage,
        queueWaitTimeoutMillis = queueWaitTimeoutMillis,
        inferenceTimeoutMillis = inferenceTimeoutMillis,
        costQuantum = costQuantum,
        agingQuantumMillis = 1_000,
    )
}
