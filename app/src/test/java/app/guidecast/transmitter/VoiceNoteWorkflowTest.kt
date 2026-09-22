package app.guidecast.transmitter

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class VoiceNoteWorkflowTest {
    private fun note(count: Int = 3) = VoiceNote("synthetic", "회의", 1, "en-US", "ko-KR",
        interrupted = false, lines = List(count) { VoiceNoteLine(it * 1000L, (it + 1) * 1000L, "line $it", "en-US", speaker = "speaker $it") })

    @Test fun automaticRecognitionKeepsUnknownLanguageInsteadOfBorrowingAnotherSegmentLanguage() {
        val result = FileTranscriptionResult(listOf(
            FileSpeechSegment(0, 0, 1_000, "Hello", null),
            FileSpeechSegment(1, 1_000, 2_000, "こんにちは", "ja-JP")), "en-US", emptyList(), 2_000)
        assertEquals(listOf(null, "ja-JP"), result.voiceNoteLines().map { it.language })
        assertEquals(listOf("en-US", "ja-JP"), result.voiceNoteLines("en-US").map { it.language })
        assertEquals(listOf(null, "ja-JP"), result.copy(sourceLanguageTag = null).voiceNoteLines().map { it.language })
    }
    @Test fun originalTimingEvidenceIsPreservedThroughEditing() {
        val result = FileTranscriptionResult(listOf(FileSpeechSegment(0, 5, 100, "말", "ko", timingEstimated = false)), "ko", emptyList(), 100)
        val line = result.voiceNoteLines().single()
        assertFalse(line.timingEstimated)
        assertFalse(line.corrected("수정", "", false).timingEstimated)
    }

    @Test fun sourceCorrectionInvalidatesStaleTranslationButKeepsTimingAndSpeaker() {
        val old = note().lines.first().copy(translation = "오래된 번역")
        val edited = old.corrected("corrected", old.translation, false)
        assertEquals("", edited.translation)
        assertEquals(old.startMs, edited.startMs)
        assertEquals(old.endMs, edited.endMs)
        assertEquals(old.speaker, edited.speaker)
        assertTrue(edited.edited)
    }
    @Test fun manualTranslationIsPreservedAndExported() {
        val edited = note().lines.first().corrected("corrected", "직접 수정", true)
        assertTrue(voiceNoteTranscript("회의", listOf(edited), true).contains("corrected\n(직접 수정)"))
    }
    @Test fun invalidEditsDoNotEraseSource() {
        val old = note().lines.first()
        assertThrows(IllegalArgumentException::class.java) { old.corrected(" ", "", false) }
        assertThrows(IllegalArgumentException::class.java) { old.corrected("a".repeat(65_537), "", false) }
    }
    @Test fun searchKeepsOriginalOrdinalsAndIncludesSpeakerAndTranslation() {
        val lines = note().lines.toMutableList().apply { this[2] = this[2].copy(translation = "검색 단어") }
        assertEquals(listOf(2), voiceNoteMatchingLines(lines, "검색"))
        assertEquals(listOf(1), voiceNoteMatchingLines(lines, "SPEAKER 1"))
        assertEquals(listOf(0, 1, 2), voiceNoteMatchingLines(lines, " "))
        assertTrue(voiceNoteMatchingLines(lines, "missing").isEmpty())
    }
    @Test fun retryOnlyReceivesUnfinishedLinesAndKeepsHumanEdits() = runTest {
        val initial = note().let { it.copy(lines = it.lines.mapIndexed { i, l -> if (i == 1) l.copy(translation = "직접 번역", edited = true) else l }) }
        var saved: VoiceNote? = null
        val done = translateVoiceNote(initial, "ko-KR", { saved = it }) { pending, emit ->
            assertEquals(listOf("line 0", "line 2"), pending.map { it.original })
            pending.indices.reversed().forEach { emit(it, "번역 $it") }
        }
        assertEquals(done, saved)
        assertEquals("직접 번역", done.lines[1].translation)
        assertTrue(done.lines[1].edited)
        assertEquals(initial.lines.map { it.speaker }, done.lines.map { it.speaker })
        assertEquals("번역 1", done.lines[2].translation)
    }
    @Test fun failurePersistsCompletedLinesAndRetryResumes() = runTest {
        var saved = note()
        try {
            translateVoiceNote(saved, "ko-KR", { saved = it }) { _, emit -> emit(0, "완료"); error("network") }
            fail("failure must propagate")
        } catch (_: IllegalStateException) { }
        assertEquals("완료", saved.lines[0].translation)
        val done = translateVoiceNote(saved, "ko-KR", { saved = it }) { pending, emit ->
            assertEquals(2, pending.size)
            pending.indices.forEach { emit(it, "나머지") }
        }
        assertEquals("완료", done.lines[0].translation)
        assertTrue(done.lines.all { it.translation.isNotBlank() })
    }
    @Test fun cancellationKeepsCompletedWorkWithoutInventingMissingOutput() = runTest {
        var saved = note()
        try {
            translateVoiceNote(saved, "ko-KR", { saved = it }) { _, emit -> emit(2, "완료"); throw CancellationException() }
            fail("cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals("완료", saved.lines[2].translation)
        assertEquals("", saved.lines[0].translation)
    }
    @Test fun changingTargetClearsOldTranslationsWithoutChangingOriginals() = runTest {
        var firstSave: VoiceNote? = null
        val original = note(1).let { it.copy(lines = it.lines.map { l -> l.copy(translation = "기존") }) }
        val result = translateVoiceNote(original, "ja-JP", { if (firstSave == null) firstSave = it }) { _, emit -> emit(0, "新しい") }
        assertEquals("新しい", firstSave!!.lines[0].translation)
        assertEquals("ja-JP", result.targetLanguage)
        assertEquals(original.lines[0].original, result.lines[0].original)
    }
    @Test fun failedLanguageReplacementKeepsAllPreviousTranslationsAfterEightNewLines() = runTest {
        val initial = note(10).let { it.copy(lines = it.lines.map { l -> l.copy(translation = "기존 번역", edited = true) }) }
        var saved = initial
        try {
            translateVoiceNote(initial, "ja-JP", { saved = it }) { _, emit ->
                repeat(8) { emit(it, "新しい") }
                assertEquals(initial, saved)
                error("model failure before replacement completed")
            }
            fail("replacement failure must propagate")
        } catch (_: IllegalStateException) { }
        assertEquals(initial, saved)
    }
    @Test fun cancellingLanguageReplacementKeepsThePreviouslySavedLanguage() = runTest {
        val initial = note().let { it.copy(lines = it.lines.map { l -> l.copy(translation = "기존 번역") }) }
        var saved = initial
        val ready = CompletableDeferred<Unit>()
        val job = launch {
            translateVoiceNote(initial, "ja-JP", { saved = it }) { _, emit ->
                emit(0, "新しい"); ready.complete(Unit); awaitCancellation()
            }
        }
        ready.await(); job.cancelAndJoin()
        assertEquals(initial, saved)
    }
    @Test fun replacementCommitFailureDoesNotPublishNewLanguageOrEraseOldTranslation() = runTest {
        val initial = note(1).let { it.copy(lines = it.lines.map { l -> l.copy(translation = "기존 번역") }) }
        var saves = 0
        try {
            translateVoiceNote(initial, "ja-JP", { saves++; throw java.io.IOException("disk full") }) { _, emit -> emit(0, "新しい") }
            fail("failed commit must propagate")
        } catch (_: java.io.IOException) { }
        assertEquals(1, saves)
        assertEquals("기존 번역", initial.lines.single().translation)
    }
    @Test fun actualJobCancellationFlushesTheLastPartialBatch() = runTest {
        var saved = note()
        val ready = CompletableDeferred<Unit>()
        val job = launch {
            translateVoiceNote(saved, "ko-KR", { saved = it }) { _, emit ->
                emit(0, "취소 직전 완료")
                ready.complete(Unit)
                awaitCancellation()
            }
        }
        ready.await()
        job.cancelAndJoin()
        assertEquals("취소 직전 완료", saved.lines[0].translation)
        assertEquals("", saved.lines[1].translation)
    }
    @Test fun incompleteEngineOutputCannotBeReportedAsComplete() = runTest {
        var saved = note()
        try {
            translateVoiceNote(saved, "ko-KR", { saved = it }) { _, emit -> emit(1, "부분 완료") }
            fail("missing lines must fail")
        } catch (_: IllegalStateException) { }
        assertEquals("부분 완료", saved.lines[1].translation)
        assertTrue(saved.lines[0].translation.isBlank())
    }
    @Test fun checkpointStorageFailurePropagatesInsteadOfClaimingSuccess() = runTest {
        var writes = 0
        try {
            translateVoiceNote(note(9), "ko-KR", { if (++writes > 1) throw java.io.IOException("disk full") }) { pending, emit ->
                pending.indices.forEach { emit(it, "번역") }
            }
            fail("disk failure must propagate")
        } catch (_: java.io.IOException) { }
    }
    @Test fun completedNoteDoesNotRequestModelsOrRewriteMetadata() = runTest {
        val initial = note(1).let { it.copy(lines = it.lines.map { l -> l.copy(translation = "완료") }) }
        assertEquals(initial, translateVoiceNote(initial, "ko-KR", { error("no writes") }) { _, _ -> error("no translation") })
    }
    @Test fun twoThousandSegmentsUseBoundedCheckpointWritesAndCorrectOrder() = runTest {
        var writes = 0
        val initial = note(2_000)
        val done = translateVoiceNote(initial, "ko-KR", { writes++ }) { pending, emit ->
            pending.indices.forEach { emit(it, "번역 $it") }
        }
        assertEquals(252, writes)
        assertEquals("번역 1999", done.lines.last().translation)
        assertEquals(initial.lines.map { it.startMs }, done.lines.map { it.startMs })
    }
}
