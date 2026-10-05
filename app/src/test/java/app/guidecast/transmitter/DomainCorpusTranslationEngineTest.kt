package app.guidecast.transmitter

import app.guidecast.core.translation.BoundedQueuedTranslationEngine
import app.guidecast.core.translation.DomainTranslationContext
import app.guidecast.core.translation.TranslationStyle
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.system.measureNanoTime
import kotlin.system.measureTimeMillis

class DomainCorpusTranslationEngineTest {
    @Test fun oversizedTopExampleDoesNotHideAWholeSmallerRelevantExample() = runBlocking {
        val repo = DomainCorpusRepository(null)
        val profile = DomainCorpusProfile(1, "회의", "", "ko", "en", TranslationStyle.AUTO, 2, true)
        repo.seedActiveProfileForTest(profile, listOf(
            ("회의 예산 확인 " + "구간".repeat(220)) to "Long reference ".repeat(30),
            "회의 예산" to "The meeting budget."))
        val result = repo.match("회의 예산 확인", "ko", "en", TranslationStyle.AUTO)
        assertTrue(result.hints.length <= 600)
        assertTrue(result.hints.contains("The meeting budget."))
        assertTrue(!result.hints.contains("Long reference"))
    }
    @Test fun generatedResultsAreCheckedWithDomainOffAndOnAndNextRequestCanContinue() = runBlocking {
        for (hints in listOf("", "{\"domain\":\"업무\"}")) {
            val repo = TestCorpusRepository(DomainCorpusMatch(null, hints))
            var output = "经理不是说自己会发送吗？"
            val delegate = app.guidecast.core.translation.TextTranslationEngine { _, _, _ -> output }
            val engine = DomainCorpusTranslationEngine(delegate, repo)
            val source = "직원이 보내라고 하셨지 본인이 보내겠다는 뜻은 아니에요."
            try { engine.translate(source, "ko", "zh"); fail("Wrong generated fallback must be rejected") }
            catch (error: IllegalStateException) { assertEquals("GEMMA_SENTENCE_TYPE_REVIEW_REQUIRED", error.message) }
            output = "经理让员工发送，并不是说自己会发送。"
            assertEquals(output, engine.translate(source, "ko", "zh"))
        }
    }

    @Test fun safeExactReviewedTranslationIsReusedWithoutModelCall() = runBlocking {
        val exact = "经理让员工发送，并不是说自己会发送。"
        val repo = TestCorpusRepository(DomainCorpusMatch(exact, "reference"))
        val delegate = RecordingDelegateEngine()
        assertEquals(exact, DomainCorpusTranslationEngine(delegate, repo).translate(
            "직원이 보내라고 하셨지 본인이 보내겠다는 뜻은 아니에요.", "ko", "zh"))
        assertEquals(0, delegate.callCount)
    }

    @Test fun unsafeLegacyExactPairCannotBypassMeaningProtection() = runBlocking {
        val repo = TestCorpusRepository(DomainCorpusMatch("经理不是说自己会发送吗？", "reference"))
        val safe = "经理让员工发送，并不是说自己会发送。"
        var calls = 0
        var answer = safe
        val delegate = app.guidecast.core.translation.TextTranslationEngine { _, _, _ -> calls++; answer }
        val engine = DomainCorpusTranslationEngine(delegate, repo)
        val source = "직원이 보내라고 하셨지 본인이 보내겠다는 뜻은 아니에요."
        assertEquals(safe, engine.translate(source, "ko", "zh")); assertEquals(1, calls)
        answer = "经理不是说自己会发送吗？"
        try { engine.translate(source, "ko", "zh"); fail("Unsafe fallback must not bypass the guard") }
        catch (_: IllegalStateException) { }
    }

    @Test fun actualFailoverContractPreservesInputAndValidatesTheFallbackBeforeReturning() = runBlocking {
        val source = "직원이 보내라고 하셨지 본인이 보내겠다는 뜻은 아니에요."
        val context = "업무 분장 확인"
        val received = mutableListOf<Pair<String, String?>>()
        var fallbackText = "经理不是说自己会发送吗？"
        val primary = app.guidecast.core.translation.TranslationEngineProvider {
            app.guidecast.core.translation.TextTranslationEngine { _, _, _ ->
                throw IllegalStateException("GEMMA_SENTENCE_TYPE_REVIEW_REQUIRED") }
        }
        val fallback = app.guidecast.core.translation.TranslationEngineProvider {
            object : app.guidecast.core.translation.ContextualTextTranslationEngine {
                override suspend fun translateWithContext(text: String, contextBefore: String?, sourceLanguageTag: String, targetLanguageTag: String): String {
                    received += text to contextBefore
                    return fallbackText
                }
                override suspend fun translate(text: String, sourceLanguageTag: String, targetLanguageTag: String): String =
                    translateWithContext(text, null, sourceLanguageTag, targetLanguageTag)
            }
        }
        val failover = app.guidecast.core.translation.FailoverTranslationEngineProvider(primary, fallback)
        val engine = DomainCorpusTranslationEngine(failover.engineFor("zh"), TestCorpusRepository())
        try { engine.translateWithContext(source, context, "ko", "zh"); fail("Invalid fallback cannot be a normal output") }
        catch (error: IllegalStateException) { assertEquals("GEMMA_SENTENCE_TYPE_REVIEW_REQUIRED", error.message) }
        fallbackText = "经理让员工发送，并不是说自己会发送。"
        assertEquals(fallbackText, engine.translateWithContext(source, context, "ko", "zh"))
        assertEquals(listOf(source to context, source to context), received)
        assertTrue(failover.isUsingFallback)
    }

    private class TestCorpusRepository(
        var matchResult: DomainCorpusMatch = DomainCorpusMatch(null, ""),
        var matchException: Exception? = null,
    ) : DomainCorpusRepository(null) {
        var lastMatchedText: String? = null
        var lastMatchedSource: String? = null
        var lastMatchedTarget: String? = null
        var lastMatchedStyle: TranslationStyle? = null

        override suspend fun match(
            text: String,
            source: String,
            target: String,
            style: TranslationStyle,
        ): DomainCorpusMatch {
            matchException?.let { throw it }
            lastMatchedText = text
            lastMatchedSource = source
            lastMatchedTarget = target
            lastMatchedStyle = style
            return matchResult
        }
    }

    private class RecordingDelegateEngine(
        private val resultPrefix: String = "Translated: ",
        override val maximumCallDurationMillis: Long = 4_000L,
    ) : BoundedQueuedTranslationEngine {
        var callCount = 0
        var receivedText: String? = null
        var receivedContextBefore: String? = null
        var receivedDomainHints: String? = null

        override suspend fun translateWithContext(
            text: String,
            contextBefore: String?,
            sourceLanguageTag: String,
            targetLanguageTag: String,
        ): String {
            callCount++
            receivedText = text
            receivedContextBefore = contextBefore
            receivedDomainHints = currentCoroutineContext()[DomainTranslationContext]?.hints
            return "$resultPrefix$text"
        }

        override suspend fun translate(
            text: String,
            sourceLanguageTag: String,
            targetLanguageTag: String,
        ): String = translateWithContext(text, null, sourceLanguageTag, targetLanguageTag)
    }

    @Test
    fun exactMatchReturnsDirectlyWithoutInvokingDelegate() = runBlocking {
        val repo = TestCorpusRepository(
            matchResult = DomainCorpusMatch(exactTranslation = "Exact output", hints = ""),
        )
        val delegate = RecordingDelegateEngine()
        val engine = DomainCorpusTranslationEngine(delegate, repo)

        val result = engine.translateWithContext("원문", null, "ko", "en")

        assertEquals("Exact output", result)
        assertEquals(0, delegate.callCount)
    }

    @Test
    fun liveKoreanSourceTagKoKrNormalizesAndMatches() = runBlocking {
        val repo = TestCorpusRepository(
            matchResult = DomainCorpusMatch(exactTranslation = "Exact English", hints = ""),
        )
        val delegate = RecordingDelegateEngine()
        val engine = DomainCorpusTranslationEngine(delegate, repo)

        // Moonshine live microphone passes ko-KR directly
        val result = engine.translateWithContext("오늘 회의를 시작합니다.", null, "ko-KR", "en")

        assertEquals("Exact English", result)
        assertEquals(0, delegate.callCount)
        assertEquals("ko-KR", repo.lastMatchedSource)
    }

    @Test
    fun hintsAreAttachedViaDomainTranslationContextToDelegate() = runBlocking {
        val testHints = "{\"domain\":\"회의\",\"examples\":[{\"source\":\"A\",\"translation\":\"B\"}]}"
        val repo = TestCorpusRepository(
            matchResult = DomainCorpusMatch(exactTranslation = null, hints = testHints),
        )
        val delegate = RecordingDelegateEngine()
        val engine = DomainCorpusTranslationEngine(delegate, repo)

        val result = engine.translateWithContext("원문", "앞문맥", "ko", "en")

        assertEquals("Translated: 원문", result)
        assertEquals(1, delegate.callCount)
        assertEquals(testHints, delegate.receivedDomainHints)
        assertEquals("앞문맥", delegate.receivedContextBefore)
    }

    @Test
    fun domainOffLeavesDomainTranslationContextEmpty() = runBlocking {
        val repo = TestCorpusRepository(
            matchResult = DomainCorpusMatch(exactTranslation = null, hints = ""),
        )
        val delegate = RecordingDelegateEngine()
        val engine = DomainCorpusTranslationEngine(delegate, repo)

        val result = engine.translateWithContext("원문", null, "ko", "en")

        assertEquals("Translated: 원문", result)
        assertEquals(1, delegate.callCount)
        assertNull(delegate.receivedDomainHints)
    }

    @Test
    fun failOpenResilienceOnDatabaseExceptionFallsBackToDelegate() = runBlocking {
        val repo = TestCorpusRepository(
            matchException = IOException("disk I/O error"),
        )
        val delegate = RecordingDelegateEngine()
        val engine = DomainCorpusTranslationEngine(delegate, repo)

        val result = engine.translateWithContext("원문", null, "ko", "en")

        assertEquals("Translated: 원문", result)
        assertEquals(1, delegate.callCount)
        assertNull(delegate.receivedDomainHints)
    }

    @Test
    fun preservesDelegateWatchdogBudgetWithBoundedLookupMargin() {
        val delegate = RecordingDelegateEngine(maximumCallDurationMillis = 4_500L)
        val repo = TestCorpusRepository()
        val engine = DomainCorpusTranslationEngine(delegate, repo)

        assertEquals(4_700L, engine.maximumCallDurationMillis)
    }

    @Test
    fun meaningfulWatchdogBudgetVirtualTimeSucceedsWithinBudget() = runTest {
        val repo = TestCorpusRepository(
            matchResult = DomainCorpusMatch(null, "{\"domain\":\"회의\"}"),
        )
        val slowDelegate = object : BoundedQueuedTranslationEngine {
            override val maximumCallDurationMillis: Long = 4_000L
            override suspend fun translateWithContext(
                text: String,
                contextBefore: String?,
                sourceLanguageTag: String,
                targetLanguageTag: String,
            ): String {
                // Takes 4,100ms: longer than delegate's 4,000ms budget, but within engine's 4,200ms budget
                delay(4_100L)
                return "Slow result: $text"
            }
            override suspend fun translate(text: String, sourceLanguageTag: String, targetLanguageTag: String): String =
                translateWithContext(text, null, sourceLanguageTag, targetLanguageTag)
        }

        val engine = DomainCorpusTranslationEngine(slowDelegate, repo)
        assertEquals(4_200L, engine.maximumCallDurationMillis)

        val result = withTimeout(engine.maximumCallDurationMillis) {
            engine.translateWithContext("느린 문장", null, "ko", "en")
        }
        assertEquals("Slow result: 느린 문장", result)
    }

    @Test
    fun meaningfulWatchdogBudgetVirtualTimeTimesOutWhenExceeded() = runTest {
        val repo = TestCorpusRepository(
            matchResult = DomainCorpusMatch(null, "{\"domain\":\"회의\"}"),
        )
        val overBudgetDelegate = object : BoundedQueuedTranslationEngine {
            override val maximumCallDurationMillis: Long = 4_000L
            override suspend fun translateWithContext(
                text: String,
                contextBefore: String?,
                sourceLanguageTag: String,
                targetLanguageTag: String,
            ): String {
                // Takes 4,300ms: exceeds engine's 4,200ms budget
                delay(4_300L)
                return "Too slow"
            }
            override suspend fun translate(text: String, sourceLanguageTag: String, targetLanguageTag: String): String =
                translateWithContext(text, null, sourceLanguageTag, targetLanguageTag)
        }

        val engine = DomainCorpusTranslationEngine(overBudgetDelegate, repo)
        try {
            withTimeout(engine.maximumCallDurationMillis) {
                engine.translateWithContext("초과 문장", null, "ko", "en")
            }
            fail("Expected TimeoutCancellationException when duration exceeds watchdog budget")
        } catch (_: TimeoutCancellationException) {
            // Success: properly timed out
        }
    }

    @Test
    fun languageTagNormalizationConformsToRule() {
        // Source language normalization
        assertEquals("ko", DomainCorpusRepository.normalizeSourceLanguageTag("ko"))
        assertEquals("ko", DomainCorpusRepository.normalizeSourceLanguageTag("ko-KR"))
        assertEquals("ko", DomainCorpusRepository.normalizeSourceLanguageTag("ko-kr"))
        assertEquals("ko", DomainCorpusRepository.normalizeSourceLanguageTag("ko-KP"))
        assertEquals("en", DomainCorpusRepository.normalizeSourceLanguageTag("en-US"))
        assertEquals("en", DomainCorpusRepository.normalizeSourceLanguageTag("en-GB"))
        assertEquals("ja", DomainCorpusRepository.normalizeSourceLanguageTag("ja-JP"))
        assertEquals("zh-Hant", DomainCorpusRepository.normalizeSourceLanguageTag("zh-TW"))
        assertEquals("zh-Hant", DomainCorpusRepository.normalizeSourceLanguageTag("zh-Hant"))
        assertEquals("zh-Hans", DomainCorpusRepository.normalizeSourceLanguageTag("zh-CN"))
        assertEquals("zh-Hans", DomainCorpusRepository.normalizeSourceLanguageTag("zh-Hans"))

        // Target language normalization - preserves Chinese script variants
        assertEquals("zh-Hant", DomainCorpusRepository.normalizeTargetLanguageTag("zh-TW"))
        assertEquals("zh-Hant", DomainCorpusRepository.normalizeTargetLanguageTag("zh-HK"))
        assertEquals("zh-Hant", DomainCorpusRepository.normalizeTargetLanguageTag("zh-Hant"))
        assertEquals("zh-Hans", DomainCorpusRepository.normalizeTargetLanguageTag("zh-CN"))
        assertEquals("zh-Hans", DomainCorpusRepository.normalizeTargetLanguageTag("zh-Hans"))
        assertEquals("zh-Hans", DomainCorpusRepository.normalizeTargetLanguageTag("zh-SG"))
        assertEquals("en", DomainCorpusRepository.normalizeTargetLanguageTag("en-US"))
        assertEquals("en", DomainCorpusRepository.normalizeTargetLanguageTag("en-GB"))
        assertEquals("ja", DomainCorpusRepository.normalizeTargetLanguageTag("ja-JP"))
        assertEquals("ko", DomainCorpusRepository.normalizeTargetLanguageTag("ko-KR"))
    }

    @Test
    fun chineseScriptVariantIsolationPreventsMismatchedSubstitutions() = runBlocking {
        val repo = DomainCorpusRepository(null)
        val profileTraditional = DomainCorpusProfile(
            id = 1L,
            name = "대만 비즈니스",
            description = "번체자 회의 코퍼스",
            sourceLanguageTag = "ko",
            targetLanguageTag = "zh-Hant",
            style = TranslationStyle.FORMAL,
            pairCount = 1,
            active = true,
        )
        repo.seedActiveProfileForTest(
            profileTraditional,
            listOf("회의 시작" to "會議開始"),
        )

        // Simplified query (zh-CN / zh-Hans) must NOT match Traditional Chinese profile
        val matchSimplified = repo.match("회의 시작", "ko", "zh-CN", TranslationStyle.FORMAL)
        assertNull("zh-CN must not match zh-Hant profile", matchSimplified.exactTranslation)
        assertEquals("", matchSimplified.hints)

        // Traditional query (zh-TW / zh-Hant) must match exactly
        val matchTraditional = repo.match("회의 시작", "ko-KR", "zh-TW", TranslationStyle.FORMAL)
        assertEquals("會議開始", matchTraditional.exactTranslation)
    }

    @Test
    fun activeProfileCacheSwitchingUnderMutex() = runBlocking {
        val repo = DomainCorpusRepository(null)
        val profileA = DomainCorpusProfile(
            id = 10L,
            name = "도메인 A",
            description = "",
            sourceLanguageTag = "ko",
            targetLanguageTag = "en",
            style = TranslationStyle.FORMAL,
            pairCount = 1,
            active = true,
        )
        repo.seedActiveProfileForTest(profileA, listOf("결과 보고" to "Report A"))

        val matchA = repo.match("결과 보고", "ko", "en", TranslationStyle.FORMAL)
        assertEquals("Report A", matchA.exactTranslation)

        // Switch to Profile B in same language pair
        val profileB = DomainCorpusProfile(
            id = 11L,
            name = "도메인 B",
            description = "",
            sourceLanguageTag = "ko",
            targetLanguageTag = "en",
            style = TranslationStyle.FORMAL,
            pairCount = 1,
            active = true,
        )
        repo.seedActiveProfileForTest(profileB, listOf("결과 보고" to "Report B"))

        val matchB = repo.match("결과 보고", "ko", "en", TranslationStyle.FORMAL)
        assertEquals("Report B", matchB.exactTranslation)
    }

    @Test
    fun tenThousandPairsColdAndWarmLookupBenchmark() = runBlocking {
        val repo = DomainCorpusRepository(null)

        // Generate 10,000 distinct pairs
        val pairs = ArrayList<Pair<String, String>>(10_000)
        for (i in 1..10_000) {
            pairs.add("업무 보고 안건 번호 $i 번에 대해 설명하겠습니다." to "I will explain agenda item number $i of the business report.")
        }

        val profile = DomainCorpusProfile(
            id = 99L,
            name = "대규모 10k 코퍼스",
            description = "성능 실측용 대용량 코퍼스",
            sourceLanguageTag = "ko",
            targetLanguageTag = "en",
            style = TranslationStyle.FORMAL,
            pairCount = pairs.size,
            active = true,
        )

        // Seed 10k pairs and measure build time
        val seedTimeMs = measureTimeMillis {
            repo.seedActiveProfileForTest(profile, pairs)
        }
        assertTrue("10,000 pairs cache indexing should complete quickly (< 5000ms), actual: ${seedTimeMs}ms", seedTimeMs < 5000)

        // Measure warm exact lookup latency over 100 queries
        val warmupQuery = "업무 보고 안건 번호 5000 번에 대해 설명하겠습니다."
        val warmResult = repo.match(warmupQuery, "ko-KR", "en", TranslationStyle.FORMAL)
        assertEquals("I will explain agenda item number 5000 of the business report.", warmResult.exactTranslation)

        val queryCount = 100
        val totalNanos = measureNanoTime {
            for (i in 1..queryCount) {
                val query = "업무 보고 안건 번호 ${i * 50} 번에 대해 설명하겠습니다."
                val res = repo.match(query, "ko-KR", "en", TranslationStyle.FORMAL)
                assertNotNull(res.exactTranslation)
            }
        }

        val averageMicros = (totalNanos / queryCount) / 1000
        // Exact HashMap lookup in 10,000 pairs must be sub-millisecond (< 1,000 microseconds)
        assertTrue(
            "Average exact lookup time across 10k pairs must be sub-millisecond (< 1000us), actual: ${averageMicros}us",
            averageMicros < 1000,
        )
    }

    @Test
    fun concurrentMatchesUseThePublishedImmutableRevision(): Unit = runBlocking {
        val repo = DomainCorpusRepository(null)
        val profile = DomainCorpusProfile(
            id = 50L,
            name = "동시성 테스트",
            description = "",
            sourceLanguageTag = "ko",
            targetLanguageTag = "en",
            style = TranslationStyle.AUTO,
            pairCount = 100,
            active = true,
        )
        val pairs = (1..100).map { "문장 $it" to "Sentence $it" }
        repo.seedActiveProfileForTest(profile, pairs)

        val deferreds = (1..50).map { i ->
            async(Dispatchers.Default) {
                val idx = (i % 100) + 1
                val res = repo.match("문장 $idx", "ko-KR", "en-US", TranslationStyle.AUTO)
                assertEquals("Sentence $idx", res.exactTranslation)
            }
        }
        deferreds.awaitAll()
    }
}
