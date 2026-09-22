package app.guidecast.transmitter

import app.guidecast.core.translation.TranslationStyle
import app.guidecast.core.translation.TranslationStyleContext
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ComparativeReviewerIntegrationTest {
    private class Memory : SentenceMemoryStore {
        var human: SentenceMemoryEntry? = null
        val reports = CopyOnWriteArrayList<TeacherLearningReport>()
        override suspend fun lookup(sourceLanguageTag: String, targetLanguageTag: String,
            register: TranslationRegister, original: String) = human?.takeIf { it.translationRegister == register }
        override suspend fun upsert(entry: SentenceMemoryEntry): Boolean = error("Comparison must never apply automatically")
        override suspend fun teacherReport(key: String) = reports.lastOrNull { it.key == key }
        override suspend fun recordTeacherReport(report: TeacherLearningReport, allowedToWrite: () -> Boolean): Boolean {
            if (!allowedToWrite()) return false
            reports += report
            return true
        }
    }
    private fun settings(secondKey: Boolean = true) = DeveloperLabSettings(FakeLabPreferences(), FakeDeveloperVault()).apply {
        if (secondKey) { setProvider(CloudReviewProvider.GOOGLE); setApiKey("synthetic-google-key") }
        setProvider(CloudReviewProvider.OPENAI); setApiKey("synthetic-openai-key")
        setCloudReviewEnabled(true); setComparisonEnabled(true)
    }
    private fun transport(block: suspend (CloudReviewRequest) -> TeacherReview?) = object : CloudReviewTransport {
        override suspend fun review(request: CloudReviewRequest, apiKey: String): String? = error("Structured review required")
        override suspend fun reviewWithLessons(request: CloudReviewRequest, apiKey: String) = block(request)
    }
    private fun answer(request: CloudReviewRequest) = TeacherReview("We meet at 11.",
        if (request.verificationOnly) emptySet() else setOf(TeacherLesson.NUMBERS))

    @Test fun independentReviewQueuesEvidenceWithoutOverwritingDraftOrHumanMemory() = runBlocking {
        val memory = Memory()
        val calls = CopyOnWriteArrayList<CloudReviewRequest>()
        CloudTranslationReviewer(settings(), { true }, memory, transport { calls += it; answer(it) }).use { reviewer ->
            assertEquals("We meet at 12.", reviewer.refine("ko", "en", "11시에 만나요", "We meet at 12.",
                requestTeacherReview = true, contextBefore = "만날 시간"))
            assertEquals(3, calls.size)
            assertEquals(TeacherReviewOutcome.PROPOSED, memory.reports.single().outcome)
            assertTrue(memory.reports.single().comparison!!.verified)
        }
    }

    @Test fun missingSecondKeySendsNothing() = runBlocking {
        CloudTranslationReviewer(settings(false), { true }, Memory(), transport { error("Must not send") }).use {
            assertEquals("We meet at 12.", it.refine("ko", "en", "11시에 만나요", "We meet at 12.", requestTeacherReview = true))
        }
    }

    @Test fun disagreementSurvivesReviewerRestartAndDoesNotRebillAfterFiveMinutes() = runBlocking {
        val settings = settings()
        val memory = Memory()
        CloudTranslationReviewer(settings, { true }, memory, transport {
            if (it.verificationOnly) TeacherReview("We should meet at 11.", setOf(TeacherLesson.MEANING)) else answer(it)
        }).use { it.refine("ko", "en", "11시에 만나요", "We meet at 12.", requestTeacherReview = true) }
        val refused = memory.reports.single()
        assertEquals(TeacherReviewOutcome.DISAGREEMENT, refused.outcome)
        memory.reports.clear()
        memory.reports += refused.copy(createdAtEpochMillis = System.currentTimeMillis() - 360_000)
        CloudTranslationReviewer(settings, { true }, memory, transport { error("Refused candidates must not rebill") }).use {
            assertEquals("We meet at 12.", it.refine("ko", "en", "11시에 만나요", "We meet at 12.", requestTeacherReview = true))
        }
        assertEquals(1, memory.reports.size)
    }

    @Test fun revokingConsentAfterIndependentRequestsPreventsVerificationAndPersistence() = runBlocking {
        val settings = settings()
        val memory = Memory()
        val both = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = CopyOnWriteArrayList<CloudReviewRequest>()
        CloudTranslationReviewer(settings, { true }, memory, transport {
            calls += it
            if (calls.size == 2) both.complete(Unit)
            release.await(); answer(it)
        }).use { reviewer ->
            val work = async { reviewer.refine("ko", "en", "11시에 만나요", "We meet at 12.", requestTeacherReview = true) }
            try {
                withTimeout(2_000) { both.await() }
                settings.setCloudReviewEnabled(false)
                release.complete(Unit)
                assertEquals("We meet at 12.", withTimeout(2_000) { work.await() })
                assertEquals(2, calls.size)
                assertTrue(memory.reports.isEmpty())
            } finally { release.complete(Unit); work.cancelAndJoin() }
        }
    }

    @Test fun liveHumanReuseIsImmediateAndBackgroundRelearningKeepsCapturedStyle() = runBlocking {
        val memory = Memory().apply { human = SentenceMemoryEntry(sourceLanguageTag = "ko", targetLanguageTag = "en",
            translationRegister = TranslationRegister.CONVERSATIONAL, original = "11시에 만나요",
            corrected = "We meet at 12.", origin = SentenceMemoryOrigin.USER) }
        val calls = CopyOnWriteArrayList<CloudReviewRequest>()
        val release = CompletableDeferred<Unit>()
        CloudTranslationReviewer(settings(), { true }, memory, transport { calls += it; release.await(); answer(it) }).use { reviewer ->
            try {
                assertEquals("We meet at 12.", withTimeout(500) {
                    withContext(TranslationStyleContext(TranslationStyle.CONVERSATIONAL)) {
                        reviewer.refine("ko", "en", "11시에 만나요", "Draft.", live = true)
                    }
                })
                withTimeout(2_000) { while (calls.size < 2) delay(5) }
                assertTrue(calls.all { it.translationRegister == TranslationRegister.CONVERSATIONAL })
                release.complete(Unit)
                withTimeout(2_000) { while (memory.reports.isEmpty()) delay(5) }
                assertEquals(3, calls.size)
            } finally { release.complete(Unit) }
        }
    }
}
