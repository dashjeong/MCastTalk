package app.guidecast.transmitter

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ComparativeTeacherLearningTest {
    @Test fun comparisonGroupOwnsBothTransportSlotsAndRelearningUsesANewFingerprint() {
        val admission = CloudReviewAdmission { 0 }
        assertTrue(admission.acquire("cross", live = false, exclusive = true))
        assertFalse(admission.acquire("single", live = false))
        assertFalse(admission.acquire("cross2", live = false, exclusive = true))
        admission.release("cross")
        assertTrue(admission.acquire("single", live = false))
        assertFalse(admission.acquire("cross", live = false, exclusive = true))
        val selector = SelectiveTeacherLearning { 0 }
        assertNotEquals(selector.key(request().copy(baselineVersion = "old")), selector.key(request().copy(baselineVersion = "new")))
    }
    private fun request() = CloudReviewRequest(CloudReviewProvider.OPENAI, "synthetic-primary", "ko", "en",
        TranslationRegister.FORMAL, "11시에 만나요", "We meet at 12.", false,
        teacherSignals = setOf(TeacherLearningSignal.NUMBERS), comparisonMode = true)
    private fun answer(text: String = "We meet at 11.") = TeacherReview(text, setOf(TeacherLesson.NUMBERS))

    @Test fun independentDraftsAndOtherProviderVerificationProducePendingCandidateOnly() = runBlocking {
        val calls = mutableListOf<CloudReviewRequest>()
        val result = compareTeacherTranslations(request(), "synthetic-secondary", "다음 만날 시간은?", "관람 안내", null, emptyList()) {
            calls += it
            when { it.verificationOnly -> TeacherReview(it.draft)
                it.provider == CloudReviewProvider.GOOGLE -> answer("We meet at 11 o'clock.")
                else -> answer() }
        }
        assertEquals(3, calls.size)
        assertEquals(setOf(CloudReviewProvider.OPENAI, CloudReviewProvider.GOOGLE), calls.take(2).map { it.provider }.toSet())
        assertTrue(calls.take(2).all { it.draft == "We meet at 12." && it.referenceTranslation == null })
        assertEquals(CloudReviewProvider.GOOGLE, calls.last().provider)
        assertEquals("We meet at 11.", calls.last().draft)
        assertTrue(calls.all { it.contextBefore == "다음 만날 시간은?" && it.situation == "관람 안내" })
        assertEquals(TeacherReviewOutcome.PROPOSED, result.outcome)
        assertTrue(result.evidence.verified)
    }

    @Test fun fluentButNumericallyWrongSecondaryCandidateCannotPass() = runBlocking {
        var calls = 0
        val result = compareTeacherTranslations(request(), "synthetic-secondary", "", "", null, emptyList()) {
            calls++
            if (it.provider == CloudReviewProvider.GOOGLE) answer("We meet at 12.") else answer()
        }
        assertEquals(2, calls)
        assertEquals(TeacherReviewOutcome.DISAGREEMENT, result.outcome)
        assertFalse(result.evidence.verified)
    }

    @Test fun secondReviewerChangingTheCandidateKeepsItOutOfApprovalQueue() = runBlocking {
        val result = compareTeacherTranslations(request(), "synthetic-secondary", "", "", null, emptyList()) {
            if (it.verificationOnly) answer("We should meet at 11.") else answer()
        }
        assertEquals(TeacherReviewOutcome.DISAGREEMENT, result.outcome)
        assertFalse(result.evidence.verified)
    }

    @Test fun missingProviderResponseCannotBeCountedAsAgreement() = runBlocking {
        val result = compareTeacherTranslations(request(), "synthetic-secondary", "", "", null, emptyList()) {
            if (it.provider == CloudReviewProvider.GOOGLE) null else answer()
        }
        assertEquals(TeacherReviewOutcome.UNAVAILABLE, result.outcome)
        assertFalse(result.evidence.verified)
    }

    @Test fun cancellationPropagatesInsteadOfPublishingPartialEvidence() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val task = launch {
            compareTeacherTranslations(request(), "synthetic-secondary", "", "", null, emptyList()) {
                try { entered.complete(Unit); awaitCancellation() }
                finally { cancelled.complete(Unit) }
            }
        }
        entered.await(); task.cancelAndJoin()
        assertTrue(cancelled.isCompleted)
        assertTrue(task.isCancelled)
    }

    @Test fun similarWordsNeverShareAnApplicationKeyAndContextChangesInvalidateReuse() {
        fun key(text: String, context: String = "관람", situation: String = "안내") =
            comparativeLessonKey("ko", "en", TranslationRegister.FORMAL, text, context, situation)
        assertTrue(comparativeSourceSimilarity("이 문을 열어 주세요", "이 문을 닫아 주세요") > 0.3)
        assertNotEquals(key("이 문을 열어 주세요"), key("이 문을 닫아 주세요"))
        assertNotEquals(key("괜찮아요"), key("괜찮아요", "거절"))
        assertNotEquals(key("괜찮아요"), key("괜찮아요", situation = "긴급 상황"))
    }

    @Test fun changingComparisonSettingsRevokesPendingAuthorizationAndImportNeverEnablesTransfers() {
        val settings = DeveloperLabSettings(FakeLabPreferences(), FakeDeveloperVault())
        settings.setApiKey("synthetic-openai-key-not-real")
        settings.setProvider(CloudReviewProvider.GOOGLE); settings.setApiKey("synthetic-google-key-not-real")
        settings.setProvider(CloudReviewProvider.OPENAI); settings.setCloudReviewEnabled(true); settings.setComparisonEnabled(true)
        assertTrue(settings.state.value.hasComparisonKeys)
        val revision = settings.state.value.authorizationRevision
        assertTrue(settings.setComparisonDetails("another-model", "박물관 안내"))
        assertFalse(settings.state.value.comparisonEnabled)
        assertTrue(settings.state.value.authorizationRevision > revision)
        settings.setComparisonEnabled(true)
        settings.importOptions(settings.state.value)
        assertFalse(settings.state.value.comparisonEnabled)
        assertFalse(settings.state.value.cloudReviewEnabled)
        assertFalse(settings.state.value.teacherLearningEnabled)
    }
}
