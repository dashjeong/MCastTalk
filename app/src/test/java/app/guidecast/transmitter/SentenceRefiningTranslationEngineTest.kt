package app.guidecast.transmitter

import app.guidecast.core.translation.ContextualTextTranslationEngine
import app.guidecast.core.translation.FairQueuedTranslationEngineProvider
import app.guidecast.core.translation.FairTranslationQueueConfig
import app.guidecast.core.translation.TextTranslationEngine
import app.guidecast.core.translation.TranslationEngineProvider
import app.guidecast.core.translation.TranslationGlossaryContext
import app.guidecast.core.translation.TranslationStyle
import app.guidecast.core.translation.TranslationStyleContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Exercises the live wrapper with the actual review policy, without a model or a network. */
class SentenceRefiningTranslationEngineTest {
    private class Memory : SentenceMemoryStore {
        @Volatile var saved: SentenceMemoryEntry? = null
        var lookups = 0
        val learned = CompletableDeferred<SentenceMemoryEntry>()
        override suspend fun lookup(sourceLanguageTag: String, targetLanguageTag: String,
            register: TranslationRegister, original: String): SentenceMemoryEntry? {
            lookups++
            return saved?.takeIf {
                it.sourceLanguageTag == sourceLanguageTag && it.targetLanguageTag == targetLanguageTag &&
                    it.translationRegister == register && it.original == original
            }
        }
        override suspend fun upsert(entry: SentenceMemoryEntry): Boolean {
            saved = entry
            learned.complete(entry)
            return true
        }
    }

    private fun settings() = DeveloperLabSettings(FakeLabPreferences(), FakeDeveloperVault())
    private val noTransport = CloudReviewTransport { _, _ -> error("Unexpected network request") }

    @Test fun fairQueueAndWrapperPreserveSourceContextGlossaryAndPerUtteranceStyle() = runBlocking {
        data class Seen(val text: String, val before: String?, val source: String, val target: String,
            val style: TranslationStyle?, val hints: String?)
        val seen = mutableListOf<Seen>()
        val delegate = object : ContextualTextTranslationEngine {
            override suspend fun translateWithContext(text: String, contextBefore: String?,
                sourceLanguageTag: String, targetLanguageTag: String): String {
                seen += Seen(text, contextBefore, sourceLanguageTag, targetLanguageTag,
                    currentCoroutineContext()[TranslationStyleContext]?.style,
                    currentCoroutineContext()[TranslationGlossaryContext]?.hints)
                return "Only this sentence: $text"
            }
        }
        val queueConfig = FairTranslationQueueConfig(queueWaitTimeoutMillis = 20_000L, inferenceTimeoutMillis = 4_000L)
        val queued = FairQueuedTranslationEngineProvider(
            delegate = TranslationEngineProvider { delegate }, parentScope = this, config = queueConfig,
        )
        try {
            CloudTranslationReviewer(settings(), { false }, Memory(), noTransport).use { reviewer ->
                val wrapped = SentenceRefiningTranslationEngine(queued.engineFor("en"), reviewer)
                assertEquals(queueConfig.maximumCallDurationMillis + 100L, wrapped.maximumCallDurationMillis)
                val first = withContext(TranslationStyleContext(TranslationStyle.CONVERSATIONAL) +
                    TranslationGlossaryContext("서울 => Seoul")) {
                    wrapped.translateWithContext("지금 출발합니다.", "다음 장소를 안내했습니다.", "ko", "en")
                }
                assertEquals("Only this sentence: 지금 출발합니다.", first)
                assertEquals("Only this sentence: 잠시 기다리세요.", wrapped.translate("잠시 기다리세요.", "ko", "en"))
                assertEquals(listOf(
                    Seen("지금 출발합니다.", "다음 장소를 안내했습니다.", "ko", "en",
                        TranslationStyle.CONVERSATIONAL, "서울 => Seoul"),
                    Seen("잠시 기다리세요.", null, "ko", "en", null, null),
                ), seen)
            }
        } finally { queued.close() }
    }

    @Test fun plainDelegateOutputAndArgumentsRemainExactWithDefaultOptions() = runBlocking {
        var calls = 0
        val delegate = TextTranslationEngine { text, source, target ->
            calls++
            assertEquals("입력 문장", text)
            assertEquals("ko", source)
            assertEquals("en", target)
            "  A complete draft.\n"
        }
        CloudTranslationReviewer(settings(), { false }, Memory(), noTransport).use { reviewer ->
            assertEquals("  A complete draft.\n", SentenceRefiningTranslationEngine(delegate, reviewer)
                .translateWithContext("입력 문장", "참고 문맥은 번역 대상이 아닙니다.", "ko", "en"))
        }
        assertEquals(1, calls)
    }

    @Test fun confirmedSentenceWorksOfflineButUnapprovedAiMemoryDoesNotReplaceBaseline() = runBlocking {
        val memory = Memory().apply {
            saved = SentenceMemoryEntry(sourceLanguageTag = "ko", targetLanguageTag = "en",
                translationRegister = TranslationRegister.FORMAL, original = "같이 가요.",
                corrected = "Let's go together.", origin = SentenceMemoryOrigin.USER)
        }
        CloudTranslationReviewer(settings(), { false }, memory, noTransport).use { reviewer ->
            val wrapped = SentenceRefiningTranslationEngine(TextTranslationEngine { _, _, _ -> "Go together." }, reviewer)
            assertEquals("Let's go together.", wrapped.translate("같이 가요.", "ko", "en"))
            memory.saved = memory.saved!!.copy(origin = SentenceMemoryOrigin.AI)
            assertEquals("Go together.", wrapped.translate("같이 가요.", "ko", "en"))
        }
    }

    @Test fun liveOnlineReviewCannotReplaceCommittedDraftAndOnlyLaterExactSentenceUsesLearnedResult() = runBlocking {
        val settings = settings().apply {
            setApiKey("synthetic-test-key-not-real")
            setCloudReviewEnabled(true)
            setAutoLearnEnabled(true)
            setParaphraseEnabled(true)
        }
        val memory = Memory()
        val entered = CompletableDeferred<CloudReviewRequest>()
        val release = CompletableDeferred<Unit>()
        CloudTranslationReviewer(settings, { true }, memory, CloudReviewTransport { request, _ ->
            entered.complete(request)
            release.await()
            "We walk to Seoul together."
        }).use { reviewer ->
            val wrapped = SentenceRefiningTranslationEngine(TextTranslationEngine { _, _, _ -> "We walk to Seoul." }, reviewer)
            val style = TranslationStyleContext(TranslationStyle.CONVERSATIONAL)
            val committed = withTimeout(500L) { withContext(style) { wrapped.translate("서울에 함께 갑니다.", "ko", "en") } }
            assertEquals("We walk to Seoul.", committed)
            val request = withTimeout(1_000L) { entered.await() }
            assertEquals(TranslationRegister.CONVERSATIONAL, request.translationRegister)
            assertTrue(request.paraphrase)
            assertTrue(request.requiresAutoLearning)
            assertNull(memory.saved)
            release.complete(Unit)
            withTimeout(1_000L) { memory.learned.await() }
            assertEquals("We walk to Seoul.", committed)
            assertEquals("We walk to Seoul together.", withContext(style) {
                wrapped.translate("서울에 함께 갑니다.", "ko", "en")
            })
            assertEquals("We walk to Seoul.", wrapped.translate("서울에 함께 갑니다.", "ko", "en"))
        }
    }

    @Test fun unavailableSentenceStorageIsBoundedButOperatorCancellationStillPropagates() = runBlocking {
        var cancelledLookups = 0
        val memory = object : SentenceMemoryStore {
            override suspend fun lookup(sourceLanguageTag: String, targetLanguageTag: String,
                register: TranslationRegister, original: String): SentenceMemoryEntry? {
                try { awaitCancellation() } finally { cancelledLookups++ }
            }
            override suspend fun upsert(entry: SentenceMemoryEntry): Boolean = error("Unexpected write")
        }
        CloudTranslationReviewer(settings(), { false }, memory, noTransport).use { reviewer ->
            val wrapped = SentenceRefiningTranslationEngine(TextTranslationEngine { _, _, _ -> "Complete draft." }, reviewer)
            assertEquals("Complete draft.", withTimeout(500L) { wrapped.translate("원문", "ko", "en") })
            assertEquals(1, cancelledLookups)
            try {
                withTimeout(10L) { wrapped.translate("다음 원문", "ko", "en") }
                fail("Operator cancellation must not return a draft")
            } catch (_: TimeoutCancellationException) { }
            assertEquals(2, cancelledLookups)
        }
    }

    @Test fun delegateCancellationDoesNotStartSentenceLookupOrBackgroundReview() = runBlocking {
        val memory = Memory()
        val cancellation = CancellationException("Synthetic operator stop")
        CloudTranslationReviewer(settings(), { false }, memory, noTransport).use { reviewer ->
            val wrapped = SentenceRefiningTranslationEngine(TextTranslationEngine { _, _, _ -> throw cancellation }, reviewer)
            try {
                wrapped.translate("원문", "ko", "en")
                fail("Expected operator cancellation")
            } catch (caught: CancellationException) { assertSame(cancellation, caught) }
            assertEquals(0, memory.lookups)
            assertNull(memory.saved)
        }
    }
}
