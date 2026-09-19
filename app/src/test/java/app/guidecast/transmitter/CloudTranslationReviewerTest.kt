package app.guidecast.transmitter

import app.guidecast.core.translation.TranslationStyle
import app.guidecast.core.translation.TranslationStyleContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class CloudTranslationReviewerTest {
    private class Memory : SentenceMemoryStore {
        var saved: SentenceMemoryEntry? = null
        var registerSeen: TranslationRegister? = null
        override suspend fun lookup(sourceLanguageTag: String, targetLanguageTag: String,
            register: TranslationRegister, original: String): SentenceMemoryEntry? {
            registerSeen = register
            return saved?.takeIf { it.original == original && it.translationRegister == register }
        }
        override suspend fun upsert(entry: SentenceMemoryEntry): Boolean { saved = entry; return true }
    }
    private fun enabledSettings() = DeveloperLabSettings(FakeLabPreferences(), FakeDeveloperVault()).apply {
        setApiKey("synthetic-test-key-not-real")
        setCloudReviewEnabled(true)
    }

    @Test fun queuedAiWriteRechecksDeveloperCloudAndLearningConsentAtExecution() = runBlocking {
        for (revoked in listOf("developer", "cloud", "learning")) {
            val settings = enabledSettings().apply { setAutoLearnEnabled(true) }
            val developer = AtomicBoolean(true)
            val queued = CompletableDeferred<Unit>()
            val execute = CompletableDeferred<Unit>()
            var saved: SentenceMemoryEntry? = null
            val memory = object : SentenceMemoryStore {
                override suspend fun lookup(sourceLanguageTag: String, targetLanguageTag: String,
                    register: TranslationRegister, original: String): SentenceMemoryEntry? = null
                override suspend fun upsert(entry: SentenceMemoryEntry): Boolean =
                    error("Cloud learning must use a conditional write")
                override suspend fun upsertIf(entry: SentenceMemoryEntry, allowedToWrite: () -> Boolean): Boolean {
                    // Manual executor: the call is admitted now, but its DB work starts later.
                    queued.complete(Unit)
                    execute.await()
                    if (!allowedToWrite()) return false
                    saved = entry
                    return true
                }
            }
            CloudTranslationReviewer(settings, developer::get, memory, CloudReviewTransport { _, _ ->
                "We will walk together."
            }).use { reviewer ->
                val result = async { reviewer.refine("ko", "en", "함께 갑니다.", "We walk together.") }
                try {
                    withTimeout(1_000L) { queued.await() }
                    when (revoked) {
                        "developer" -> developer.set(false)
                        "cloud" -> settings.setCloudReviewEnabled(false)
                        "learning" -> settings.setAutoLearnEnabled(false)
                    }
                    execute.complete(Unit)
                    // An authorization generation change also discards already-running responses.
                    assertEquals("We walk together.",
                        withTimeout(1_000L) { result.await() })
                    assertNull("No AI record may be written after $revoked consent is revoked", saved)
                } finally { execute.complete(Unit); result.cancel() }
            }
        }
    }

    @Test fun allThreeConsentConditionsAreRequiredBeforeAnyTransportCall() = runBlocking {
        for (developer in listOf(false, true)) for (cloud in listOf(false, true)) for (key in listOf(false, true)) {
            if (developer && cloud && key) continue
            val settings = DeveloperLabSettings(FakeLabPreferences(), FakeDeveloperVault())
            settings.setCloudReviewEnabled(cloud)
            if (key) settings.setApiKey("synthetic-test-key-not-real")
            var calls = 0
            CloudTranslationReviewer(settings, { developer }, Memory(), CloudReviewTransport { _, _ -> calls++; "impossible" }).use {
                assertEquals("We walk to Seoul.", it.refine("ko", "en", "서울에 갑니다.", "We walk to Seoul."))
                assertEquals(0, calls)
            }
        }
    }

    @Test fun humanSentenceMemoryWorksOfflineAndUsesCapturedRegister() = runBlocking {
        val settings = DeveloperLabSettings(FakeLabPreferences(), FakeDeveloperVault())
        val memory = Memory().apply { saved = SentenceMemoryEntry(sourceLanguageTag = "ko", targetLanguageTag = "en",
            translationRegister = TranslationRegister.CONVERSATIONAL, original = "같이 가요", corrected = "Let's go together.", origin = SentenceMemoryOrigin.USER) }
        CloudTranslationReviewer(settings, { false }, memory, CloudReviewTransport { _, _ -> error("No network") }).use { reviewer ->
            assertEquals("Let's go together.", withContext(TranslationStyleContext(TranslationStyle.CONVERSATIONAL)) {
                reviewer.refine("ko", "en", "같이 가요", "Go together.")
            })
            settings.setTranslationRegister(TranslationRegister.CONVERSATIONAL)
            assertEquals("Go together.", reviewer.refine("ko", "en", "같이 가요", "Go together."))
            assertEquals(TranslationRegister.FORMAL, memory.registerSeen)
        }
    }

    @Test fun cloudFailureAndUnsafeNumberNameChangesKeepDraftWithoutLearning() = runBlocking {
        val settings = enabledSettings().apply { setAutoLearnEnabled(true) }
        for (answer in listOf<String?>(null, "We meet at Busan at 11.", "We meet at Seoul at 12.")) {
            val memory = Memory()
            CloudTranslationReviewer(settings, { true }, memory, CloudReviewTransport { _, _ -> answer }).use {
                assertEquals("We meet at Seoul at 11.", it.refine("ko", "en", "서울에서 11시에 만나요.", "We meet at Seoul at 11."))
                assertNull(memory.saved)
            }
        }
        CloudTranslationReviewer(settings, { true }, Memory(), CloudReviewTransport { _, _ -> error("private upstream error") }).use {
            assertEquals("Draft.", it.refine("ko", "en", "원문", "Draft."))
            assertEquals(CloudReviewStatus.FALLBACK, it.status.value)
        }
    }

    @Test fun acceptedReviewLearnsOnlyWhenEnabledAndUsesUtteranceStyle() = runBlocking {
        val settings = enabledSettings().apply { setAutoLearnEnabled(true) }
        val memory = Memory()
        var captured: CloudReviewRequest? = null
        CloudTranslationReviewer(settings, { true }, memory, CloudReviewTransport { request, _ ->
            captured = request
            "We walk to Seoul together."
        }).use { reviewer ->
            assertEquals("We walk to Seoul together.", withContext(TranslationStyleContext(TranslationStyle.CONVERSATIONAL)) {
                reviewer.refine("ko", "en", "서울에 함께 갑니다.", "We walk to Seoul.")
            })
            assertEquals(TranslationRegister.CONVERSATIONAL, captured?.translationRegister)
            assertEquals(SentenceMemoryOrigin.AI, memory.saved?.origin)
        }
    }

    @Test fun cancellationPropagatesWithoutReturningOrLearningLateResponse() = runBlocking {
        val memory = Memory()
        CloudTranslationReviewer(enabledSettings(), { true }, memory, CloudReviewTransport { _, _ -> awaitCancellation() }).use {
            try { withTimeout(50L) { it.refine("ko", "en", "원문", "Draft.") }; fail("Expected cancellation") }
            catch (_: TimeoutCancellationException) { }
            assertNull(memory.saved)
        }
    }

    @Test fun paraphraseUsesCapturedUtteranceContextAcrossLaterSettingChanges() = runBlocking {
        val settings = enabledSettings().apply { setParaphraseEnabled(true) }
        val requests = mutableListOf<CloudReviewRequest>()
        CloudTranslationReviewer(settings, { true }, Memory(), CloudReviewTransport { request, _ ->
            requests += request; null
        }).use { reviewer ->
            reviewer.refine("ko", "en", "원문", "Draft.")
            assertFalse(requests.last().paraphrase)
            settings.setParaphraseEnabled(false)
            withContext(TranslationStyleContext(TranslationStyle.CONVERSATIONAL)) {
                reviewer.refine("ko", "en", "원문", "Draft.")
            }
            assertTrue(requests.last().paraphrase)
            assertEquals(TranslationRegister.CONVERSATIONAL, requests.last().translationRegister)
        }
    }

    @Test fun liveReturnsDraftImmediatelyDeduplicatesAndCapsTwoRequestsPerMinute() = runBlocking {
        val settings = enabledSettings().apply { setAutoLearnEnabled(true) }
        val calls = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val memory = Memory()
        CloudTranslationReviewer(settings, { true }, memory, CloudReviewTransport { _, _ ->
            calls.incrementAndGet(); release.await(); null
        }, clockMillis = { 0L }).use { reviewer ->
            repeat(8) {
                assertEquals("We walk to Seoul.", withTimeout(200L) {
                    reviewer.refine("ko", "en", if (it % 2 == 0) "서울갑니다" else "서울가요", "We walk to Seoul.", live = true)
                })
            }
            withTimeout(1_000L) { while (calls.get() < 2) delay(5L) }
            assertEquals(2, calls.get())
            release.complete(Unit)
            delay(20L)
            reviewer.refine("ko", "en", "세 번째 문장", "We walk to Seoul.", live = true)
            assertEquals(2, calls.get())
        }
    }

    @Test fun liveCloudWithoutAutomaticLearningMakesNoUnusablePaidRequest() = runBlocking {
        var called = false
        CloudTranslationReviewer(enabledSettings(), { true }, Memory(), CloudReviewTransport { _, _ -> called = true; null }).use {
            assertEquals("Draft.", it.refine("ko", "en", "원문", "Draft.", live = true))
            assertFalse(called)
        }
    }
}
