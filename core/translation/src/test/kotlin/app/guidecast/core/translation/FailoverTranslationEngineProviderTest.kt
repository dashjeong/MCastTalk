package app.guidecast.core.translation

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FailoverTranslationEngineProviderTest {
    @Test
    fun contextualPrimaryReceivesPriorTextWithoutAddingItToCurrentDelta() = runBlocking {
        var receivedContext: String? = null
        val provider = FailoverTranslationEngineProvider(
            primary = TranslationEngineProvider {
                object : ContextualTextTranslationEngine {
                    override suspend fun translateWithContext(
                        text: String,
                        contextBefore: String?,
                        sourceLanguageTag: String,
                        targetLanguageTag: String,
                    ): String {
                        receivedContext = contextBefore
                        return "translated:$text"
                    }
                }
            },
            fallback = TranslationEngineProvider {
                TextTranslationEngine { text, _, _ -> "fallback:$text" }
            },
        )

        val engine = provider.engineFor("en")
        assertTrue(engine is ContextualTextTranslationEngine)
        val translated = (engine as ContextualTextTranslationEngine).translateWithContext(
            text = "다음 장소입니다",
            contextBefore = "경복궁 안내를 시작합니다",
            sourceLanguageTag = "ko",
            targetLanguageTag = "en",
        )

        assertEquals("경복궁 안내를 시작합니다", receivedContext)
        assertEquals("translated:다음 장소입니다", translated)
    }

    @Test
    fun firstPrimaryFailureSwitchesEveryLanguageToFallback() = runBlocking {
        val primaryCalls = AtomicInteger()
        val fallbackCalls = AtomicInteger()
        val failureCallbacks = AtomicInteger()
        val provider = FailoverTranslationEngineProvider(
            primary = TranslationEngineProvider {
                TextTranslationEngine { _, _, _ ->
                    primaryCalls.incrementAndGet()
                    error("native translator process died")
                }
            },
            fallback = TranslationEngineProvider { target ->
                TextTranslationEngine { text, _, _ ->
                    fallbackCalls.incrementAndGet()
                    "$target:$text"
                }
            },
            onPrimaryFailure = { failureCallbacks.incrementAndGet() },
        )

        val outputs = listOf("en", "ja", "zh", "nl").map { target ->
            async { provider.engineFor(target).translate("안녕하세요", "ko", target) }
        }.awaitAll()

        assertTrue(provider.isUsingFallback)
        assertEquals(1, primaryCalls.get())
        assertEquals(4, fallbackCalls.get())
        assertEquals(1, failureCallbacks.get())
        assertEquals(listOf("en:안녕하세요", "ja:안녕하세요", "zh:안녕하세요", "nl:안녕하세요"), outputs)
    }

    @Test
    fun boundedPrimaryAttemptFallsBackBeforeThePipelineDeadlineIsConsumed() = runTest {
        var observedFailure: Throwable? = null
        val provider = FailoverTranslationEngineProvider(
            primary = TranslationEngineProvider {
                TextTranslationEngine { _, _, _ ->
                    delay(Long.MAX_VALUE)
                    "unreachable"
                }
            },
            fallback = TranslationEngineProvider {
                TextTranslationEngine { text, _, _ -> "fallback:$text" }
            },
            primaryAttemptTimeoutMillis = 100,
            onPrimaryFailure = { observedFailure = it },
        )

        val translated = async {
            provider.engineFor("en").translate("안녕하세요", "ko", "en")
        }
        runCurrent()
        advanceTimeBy(101)
        runCurrent()

        assertEquals("fallback:안녕하세요", translated.await())
        assertTrue(provider.isUsingFallback)
        assertTrue(observedFailure?.message.orEmpty().contains("100ms"))
    }

    @Test
    fun recoverablePrimaryRetriesAfterCooldownWithoutDroppingTheFailedSentence() = runBlocking {
        var now = 1_000L
        var primaryCalls = 0
        var failureCallbacks = 0
        var recoveryCallbacks = 0
        val provider = FailoverTranslationEngineProvider(
            primary = TranslationEngineProvider {
                TextTranslationEngine { text, _, _ ->
                    primaryCalls += 1
                    if (primaryCalls == 1) error("worker restarted")
                    "gemma:$text"
                }
            },
            fallback = TranslationEngineProvider {
                TextTranslationEngine { text, _, _ -> "mlkit:$text" }
            },
            primaryRetryCooldownMillis = 5_000L,
            currentMonotonicMillis = { now },
            onPrimaryFailure = { failureCallbacks += 1 },
            onPrimaryRecovered = { recoveryCallbacks += 1 },
        )
        val engine = provider.engineFor("en")

        assertEquals("mlkit:첫 문장", engine.translate("첫 문장", "ko", "en"))
        assertEquals("mlkit:둘째 문장", engine.translate("둘째 문장", "ko", "en"))
        assertEquals(1, primaryCalls)
        assertTrue(provider.isUsingFallback)

        now += 5_000L
        assertEquals("gemma:셋째 문장", engine.translate("셋째 문장", "ko", "en"))
        assertEquals(2, primaryCalls)
        assertEquals(1, failureCallbacks)
        assertEquals(1, recoveryCallbacks)
        assertTrue(!provider.isUsingFallback)
    }

}
