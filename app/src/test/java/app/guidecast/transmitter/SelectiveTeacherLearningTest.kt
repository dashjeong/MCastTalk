package app.guidecast.transmitter

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class SelectiveTeacherLearningTest {
    private fun request(original: String = "11시에 만나요", draft: String = "We meet at 12.") = CloudReviewRequest(
        CloudReviewProvider.OPENAI, "test-model", "ko", "en", TranslationRegister.FORMAL, original, draft, false)
    private class Memory : SentenceMemoryStore {
        var entry: SentenceMemoryEntry? = null
        val reports = mutableListOf<TeacherLearningReport>()
        override suspend fun lookup(sourceLanguageTag: String, targetLanguageTag: String, register: TranslationRegister, original: String) = entry
        override suspend fun upsert(entry: SentenceMemoryEntry): Boolean { this.entry = entry; return true }
        override suspend fun recordTeacherReport(report: TeacherLearningReport, allowedToWrite: () -> Boolean): Boolean {
            if (!allowedToWrite()) return false
            reports += report
            return true
        }
        override suspend fun teacherReport(key: String) = reports.lastOrNull { it.key == key }
    }
    private fun settings() = DeveloperLabSettings(FakeLabPreferences(), FakeDeveloperVault()).apply {
        setApiKey("synthetic-test-key-not-real"); setCloudReviewEnabled(true); setTeacherLearningEnabled(true)
    }
    private fun transport(answer: TeacherReview?, call: () -> Unit = {}) = object : CloudReviewTransport {
        override suspend fun review(request: CloudReviewRequest, apiKey: String): String? = error("Structured teacher result required")
        override suspend fun reviewWithLessons(request: CloudReviewRequest, apiKey: String): TeacherReview? { call(); return answer }
    }

    @Test fun normalFrequentTextIsNotSelectedAndChangedDefectiveTextIsAnIncrement() {
        val policy = SelectiveTeacherLearning { 0 }
        val normal = request(draft = "We meet at 11.")
        repeat(500) { assertTrue(policy.signals(normal, false).isEmpty()) }
        assertTrue(TeacherLearningSignal.NUMBERS in policy.signals(request(), false))
        assertTrue(TeacherLearningSignal.UNSTABLE in policy.signals(request(), false))
        assertNotEquals(policy.key(normal), policy.key(request()))
    }

    @Test fun completedUnchangedWorkIsNotResentAndFailureRetriesHaveCooldown() {
        var time = 0L
        val policy = SelectiveTeacherLearning { time }
        assertTrue(policy.acquire(request())); assertFalse(policy.acquire(request()))
        policy.finish(request(), true); policy.finish(request(), false)
        time = 300_001; assertFalse(policy.acquire(request()))
        time = 86_400_001; assertTrue(policy.acquire(request()))
        policy.finish(request(), false)
        time += 299_999; assertFalse(policy.acquire(request()))
        time += 2; assertTrue(policy.acquire(request()))
    }

    @Test fun budgetAppliesToExplicitAndFileRequestsAsWellAsLive() {
        val policy = SelectiveTeacherLearning { 0 }
        repeat(2) { index -> val r = request("${index}시에 만나요"); assertTrue(policy.acquire(r)); policy.finish(r, true) }
        assertFalse(policy.acquire(request("3시에 만나요")))
    }

    @Test fun selectedNumericRepairIsLearnedWithBeforeAfterAndNormalDataNeverLeaves() = runBlocking {
        val memory = Memory()
        var calls = 0
        CloudTranslationReviewer(settings(), { true }, memory, transport(TeacherReview("We meet at 11.", setOf(TeacherLesson.NUMBERS))) { calls++ }).use {
            assertEquals("We walk together.", it.refine("ko", "en", "같이 가요", "We walk together.")); assertEquals(0, calls)
            assertEquals("We meet at 12.", it.refine("ko", "en", "11시에 만나요", "We meet at 12.")); assertEquals(1, calls)
            assertNull(memory.entry)
            val report = memory.reports.single()
            assertEquals("We meet at 12.", report.before); assertEquals(TeacherReviewOutcome.PROPOSED, report.outcome)
            assertFalse(report.checks().first().before); assertEquals(true, report.checks().first().after)
        }
    }

    @Test fun unchangedAndUnexplainedTeacherAnswersDoNotGrowMemory() = runBlocking {
        for (answer in listOf(TeacherReview("We walk together."), TeacherReview("We will walk together."))) {
            val memory = Memory()
            CloudTranslationReviewer(settings(), { true }, memory, transport(answer)).use {
                it.refine("ko", "en", "같이 가요", "We walk together.", requestTeacherReview = true)
                assertNull(memory.entry)
                assertEquals(if (answer.corrected == "We walk together.") TeacherReviewOutcome.UNCHANGED else TeacherReviewOutcome.NO_LESSON,
                    memory.reports.single().outcome)
            }
        }
    }

    @Test fun teacherLearningNeverOverridesHumanConfirmedSentence() = runBlocking {
        val memory = Memory().apply { entry = SentenceMemoryEntry(sourceLanguageTag = "ko", targetLanguageTag = "en",
            translationRegister = TranslationRegister.FORMAL, original = "같이 가요", corrected = "Let's go together.", origin = SentenceMemoryOrigin.USER) }
        CloudTranslationReviewer(settings(), { true }, memory, transport(null) { fail("No request for a human-confirmed sentence") }).use {
            assertEquals("Let's go together.", it.refine("ko", "en", "같이 가요", "Draft.", requestTeacherReview = true)); assertTrue(memory.reports.isEmpty())
        }
    }

    @Test fun liveDoesNotWaitForTeacherAndOffOnRevocationDiscardsOldResponse() = runBlocking {
        val settings = settings(); val memory = Memory()
        val dispatched = CompletableDeferred<Unit>(); val response = CompletableDeferred<TeacherReview?>(); val done = CompletableDeferred<Unit>()
        val transport = object : CloudReviewTransport {
            override suspend fun review(request: CloudReviewRequest, apiKey: String): String? = null
            override suspend fun reviewWithLessons(request: CloudReviewRequest, apiKey: String): TeacherReview? {
                dispatched.complete(Unit); return response.await().also { done.complete(Unit) }
            }
        }
        CloudTranslationReviewer(settings, { true }, memory, transport).use { reviewer ->
            assertEquals("We meet at 12.", withTimeout(200) { reviewer.refine("ko", "en", "11시에 만나요", "We meet at 12.", live = true) })
            withTimeout(1_000) { dispatched.await() }
            settings.setTeacherLearningEnabled(false); settings.setTeacherLearningEnabled(true)
            response.complete(TeacherReview("We meet at 11.", setOf(TeacherLesson.NUMBERS))); done.await()
            kotlinx.coroutines.delay(50)
            assertNull(memory.entry); assertTrue(memory.reports.isEmpty())
        }
    }

    @Test fun persistedUnchangedReportPreventsResendingAfterReviewerRestart() = runBlocking {
        val memory = Memory(); val settings = settings()
        CloudTranslationReviewer(settings, { true }, memory, transport(TeacherReview("We walk together."))).use {
            it.refine("ko", "en", "같이 가요", "We walk together.", requestTeacherReview = true)
        }
        CloudTranslationReviewer(settings, { true }, memory, transport(null) { fail("Already reviewed") }).use {
            it.refine("ko", "en", "같이 가요", "We walk together.", requestTeacherReview = true)
        }
        Unit
    }
}
