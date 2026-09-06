package app.guidecast.transmitter

import java.text.Normalizer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SpeechCorrectionRepositoryTest {
    @Test
    fun validationUsesUnicodeCodePointsAndRejectsNonnfcControlsWithoutLeakingText() {
        val eightyEmoji = "😀".repeat(80)
        SpeechCorrectionValidation.draft(draft(hint = eightyEmoji))
        assertEquals(80, SpeechCorrectionValidation.codePointLength(eightyEmoji))

        assertValidation(SpeechCorrectionValidationCode.HINT_TOO_LONG) {
            SpeechCorrectionValidation.draft(draft(hint = eightyEmoji + "😀"))
        }
        val privateText = "private-correction\nsecret"
        val controlFailure = captureValidation {
            SpeechCorrectionValidation.draft(draft(correctedText = privateText))
        }
        assertEquals(SpeechCorrectionValidationCode.CONTROL_CHARACTER, controlFailure.code)
        assertFalse(controlFailure.message.orEmpty().contains(privateText))

        val decomposed = "e\u0301"
        assertFalse(Normalizer.isNormalized(decomposed, Normalizer.Form.NFC))
        assertValidation(SpeechCorrectionValidationCode.TEXT_NOT_NFC) {
            SpeechCorrectionValidation.draft(draft(recognizedText = decomposed))
        }
        assertValidation(SpeechCorrectionValidationCode.RECOGNIZED_TEXT_TOO_LONG) {
            SpeechCorrectionValidation.draft(draft(recognizedText = "가".repeat(2_001)))
        }
        assertValidation(SpeechCorrectionValidationCode.CONTROL_CHARACTER) {
            SpeechCorrectionValidation.draft(draft(hint = "safe\u202Ehidden"))
        }
        assertValidation(SpeechCorrectionValidationCode.LANGUAGE_TAG_INVALID) {
            SpeechCorrectionValidation.draft(draft(languageTag = "ko-KR-"))
        }
        assertEquals("ko-KR", SpeechCorrectionValidation.draft(draft(languageTag = "KO-kr")).languageTag)
        assertEquals("ko-KR", SpeechCorrectionValidation.draft(draft(languageTag = "ko")).languageTag)
        assertEquals("en", SpeechCorrectionValidation.draft(draft(languageTag = "en")).languageTag)
        assertEquals("en-US", SpeechCorrectionValidation.draft(draft(languageTag = "en-US")).languageTag)
    }

    @Test
    fun batchValidationFailsBeforeStorageForProfileAndAggregateHintLimits() = runBlocking {
        val storage = FakeStorage()
        val repository = SpeechCorrectionRepository(storage) { 10L }

        assertValidation(SpeechCorrectionValidationCode.PROFILE_MISMATCH) {
            runBlocking {
                repository.replaceProfile(
                    "현장 A",
                    listOf(draft(profile = "현장 B")),
                )
            }
        }
        assertValidation(SpeechCorrectionValidationCode.MAX_HINTS) {
            runBlocking {
                repository.replaceProfile(
                    "현장 A",
                    List(33) { index -> draft(recognizedText = "인식 $index", hint = "힌트 $index") },
                )
            }
        }
        assertValidation(SpeechCorrectionValidationCode.MAX_HINT_CHARACTERS) {
            runBlocking {
                repository.replaceProfile(
                    "현장 A",
                    List(13) { index -> draft(recognizedText = "인식 $index", hint = "가".repeat(80)) },
                )
            }
        }
        assertValidation(SpeechCorrectionValidationCode.EMPTY_BATCH) {
            runBlocking { repository.replaceProfile("현장 A", emptyList()) }
        }
        assertValidation(SpeechCorrectionValidationCode.MAX_ENTRIES) {
            runBlocking {
                repository.replaceProfile(
                    "현장 A",
                    List(501) { index -> draft(recognizedText = "인식 $index") },
                )
            }
        }
        assertValidation(SpeechCorrectionValidationCode.DUPLICATE_ENTRY) {
            runBlocking {
                repository.replaceProfile(
                    "현장 A",
                    listOf(draft(languageTag = "ko-KR"), draft(languageTag = "KO-kr")),
                )
            }
        }
        assertEquals(0, storage.replaceCalls)
    }

    @Test
    fun recognitionSnapshotIsExactImmutableAndFailsClosedAfterReadFailure() = runBlocking {
        val entry = SpeechCorrectionEntry(
            id = 1L,
            profile = SpeechCorrectionRepository.DEFAULT_PROFILE,
            languageTag = "ko-KR",
            recognizedText = "가이드 캐스트",
            correctedText = "GuideCast",
            hint = "GuideCast",
            enabled = true,
            updatedAt = 9L,
        )
        val storage = FakeStorage(entries = mutableListOf(entry))
        val repository = SpeechCorrectionRepository(storage)
        repository.refresh()

        assertEquals(listOf("GuideCast"), repository.recognitionHints("KO-kr"))
        assertEquals(listOf("GuideCast"), repository.recognitionHints("ko"))
        assertEquals(listOf(entry), repository.correctionCandidates("가이드 캐스트", "ko"))
        assertTrue(repository.recognitionHints("ko-KR-").isEmpty())
        assertEquals(listOf(entry), repository.correctionCandidates("가이드 캐스트", "ko-KR"))
        assertTrue(repository.correctionCandidates("가이드", "ko-KR").isEmpty())
        try {
            @Suppress("UNCHECKED_CAST")
            (repository.recognitionHints("ko-KR") as MutableList<String>).add("mutation")
            fail("Snapshot list was mutable")
        } catch (_: UnsupportedOperationException) {
            // Expected: callers cannot mutate the recognition snapshot.
        }

        storage.failReads = true
        try {
            repository.refresh()
            fail("Storage failure was hidden from management callers")
        } catch (_: StorageFailure) {
            // Management observes the failure; recognition continues with no stale hints.
        }
        val revisionAfterFirstFailure = repository.revision.value
        assertTrue(repository.recognitionHints("ko-KR").isEmpty())
        assertTrue(repository.correctionCandidates("가이드 캐스트", "ko-KR").isEmpty())
        try {
            repository.list(SpeechCorrectionRepository.DEFAULT_PROFILE)
            fail("Repeated storage failure was hidden")
        } catch (_: StorageFailure) {
            // An already-empty fail-closed cache must not retrigger a revision-driven read loop.
        }
        assertEquals(revisionAfterFirstFailure, repository.revision.value)
    }

    private fun draft(
        profile: String = "현장 A",
        languageTag: String = "ko-KR",
        recognizedText: String = "가이드 케스트",
        correctedText: String = "가이드캐스트",
        hint: String? = null,
    ) = SpeechCorrectionDraft(
        profile = profile,
        languageTag = languageTag,
        recognizedText = recognizedText,
        correctedText = correctedText,
        hint = hint,
    )

    private fun assertValidation(
        code: SpeechCorrectionValidationCode,
        block: () -> Unit,
    ) {
        assertEquals(code, captureValidation(block).code)
    }

    private fun captureValidation(block: () -> Unit): SpeechCorrectionValidationException = try {
        block()
        fail("Expected SpeechCorrectionValidationException")
        error("unreachable")
    } catch (failure: SpeechCorrectionValidationException) {
        failure
    }

    private class StorageFailure : RuntimeException()

    private class FakeStorage(
        val entries: MutableList<SpeechCorrectionEntry> = mutableListOf(),
    ) : SpeechCorrectionStorage {
        var failReads = false
        var replaceCalls = 0
        private var selectedProfile = SpeechCorrectionRepository.DEFAULT_PROFILE

        override fun save(
            draft: SpeechCorrectionDraft,
            id: Long?,
            updatedAt: Long,
        ): SpeechCorrectionEntry = error("unused")

        override fun list(
            profile: String,
            languageTag: String?,
            query: String,
        ): List<SpeechCorrectionEntry> {
            if (failReads) throw StorageFailure()
            return entries.filter { entry ->
                entry.profile == profile &&
                    (languageTag == null || entry.languageTag.equals(languageTag, ignoreCase = true))
            }
        }

        override fun profiles(): List<String> = entries.map(SpeechCorrectionEntry::profile).distinct()
        override fun delete(id: Long): SpeechCorrectionEntry? = error("unused")
        override fun setEnabled(id: Long, enabled: Boolean, updatedAt: Long): SpeechCorrectionEntry? = error("unused")
        override fun restore(entry: SpeechCorrectionEntry): SpeechCorrectionEntry = error("unused")
        override fun clear(profile: String): List<SpeechCorrectionEntry> = error("unused")

        override fun replaceProfile(
            profile: String,
            drafts: List<SpeechCorrectionDraft>,
            updatedAt: Long,
        ): List<SpeechCorrectionEntry> {
            replaceCalls++
            return emptyList()
        }

        override fun activeProfile(): String {
            if (failReads) throw StorageFailure()
            return selectedProfile
        }

        override fun selectProfile(profile: String) {
            selectedProfile = profile
        }
    }
}
