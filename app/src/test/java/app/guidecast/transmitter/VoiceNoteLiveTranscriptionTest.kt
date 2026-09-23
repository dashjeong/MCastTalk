package app.guidecast.transmitter

import app.guidecast.core.stream.PcmAudioFrame
import app.guidecast.core.translation.RecognizedUtterance
import app.guidecast.core.translation.SpeechRecognitionConfig
import app.guidecast.core.translation.SpeechRecognitionEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class VoiceNoteLiveTranscriptionTest {
    private val origin = 1_000_000_000L
    private fun event(sequence: Long, text: String, final: Boolean = false, offsetMs: Long = 0) =
        RecognizedUtterance(sequence, text, "ko-KR", final, origin + offsetMs * 1_000_000)

    @Test fun partialIsVisibleBeforeFinalAndFinalIsCommittedOnlyOnce() {
        val session = VoiceNoteLiveTranscript(origin, "ko-KR")
        val partial = session.accept(event(0, "안전"), 500)
        assertEquals("안전", partial.partial)
        assertTrue(partial.lines.isEmpty())
        val final = session.accept(event(0, "안전 경고", true), 900)
        assertEquals("안전 경고", final.lines.single().original)
        assertEquals("", final.partial)
        assertEquals(final, session.accept(event(0, "늦은 다른 결과", true), 1_200))
        assertEquals(final, session.accept(event(0, "늦은 부분"), 1_300))
    }

    @Test fun finalBoundaryFlushKeepsLastPartialWithoutDuplicatingCommittedText() {
        val session = VoiceNoteLiveTranscript(origin, "ko-KR")
        session.accept(event(0, "첫 문장", true), 500)
        session.accept(event(1, "마지막 말", offsetMs = 600), 900)
        val saved = session.finish(1_000)
        assertEquals(listOf("첫 문장", "마지막 말"), saved.lines.map { it.original })
        assertEquals(saved, session.finish(1_000))
        assertEquals(saved, session.accept(event(1, "늦은 확정", true), 1_000))
        assertEquals("", saved.partial)
    }

    @Test fun retractedPartialIsNotInventedAtStop() {
        val session = VoiceNoteLiveTranscript(origin, "ko-KR")
        session.accept(event(0, "오인식"), 500)
        session.accept(RecognizedUtterance(0, "", "ko-KR", false, origin, isRetracted = true), 500)
        assertTrue(session.finish(600).lines.isEmpty())
    }

    @Test fun repeatedWordsInDifferentSequencesAreNotDeduplicatedByText() {
        val session = VoiceNoteLiveTranscript(origin, "ko-KR")
        session.accept(event(0, "네", true), 500)
        val saved = session.accept(event(1, "네", true, 100), 1_000)
        assertEquals(listOf("네", "네"), saved.lines.map { it.original })
        assertTrue(saved.lines.zipWithNext().all { (a, b) -> a.endMs <= b.startMs })
        assertTrue(saved.lines.all { it.startMs >= 0 && it.endMs <= 1_000 && it.timingEstimated })
    }

    @Test fun zeroLengthCaptureCannotCreateAFalseTranscript() {
        val session = VoiceNoteLiveTranscript(origin, "ko-KR")
        session.accept(event(0, "소리 없음", true), 0)
        assertTrue(session.finish(0).lines.isEmpty())
    }

    private fun engine(failure: Exception? = null) = object : SpeechRecognitionEngine {
        override fun recognize(frames: Flow<PcmAudioFrame>, config: SpeechRecognitionConfig) = flow {
            emit(event(0, "확정 문장", true))
            emit(event(1, "마지막 부분", offsetMs = 200))
            if (failure != null) throw failure
        }
    }

    @Test fun providerFailurePreservesIncrementalFinalAndLastHypothesis() = runTest {
        val snapshots = mutableListOf<VoiceNoteLiveSnapshot>()
        try {
            collectVoiceNoteLiveTranscript(engine(IllegalStateException("synthetic")), emptyFlow(), "ko-KR", origin, { 1_000 }) { snapshots += it }
            fail("Failure must remain visible to the recording controller")
        } catch (_: IllegalStateException) { }
        assertEquals(listOf("확정 문장", "마지막 부분"), snapshots.last().lines.map { it.original })
        assertTrue(snapshots.first().lines.size == 1)
    }

    @Test fun cancellationFlushesButDoesNotBecomeSuccess() = runTest {
        var saved = VoiceNoteLiveSnapshot(emptyList(), "")
        try {
            collectVoiceNoteLiveTranscript(engine(CancellationException()), emptyFlow(), "ko-KR", origin, { 1_000 }) { saved = it }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(listOf("확정 문장", "마지막 부분"), saved.lines.map { it.original })
    }

    @Test fun aNewRecordingDoesNotReusePreviousProviderSequenceIds() {
        val first = VoiceNoteLiveTranscript(origin, "ko-KR")
        val old = first.accept(event(0, "이전 노트", true), 500)
        val retry = VoiceNoteLiveTranscript(origin, "ko-KR").accept(event(0, "다음 노트", true), 500)
        assertEquals("이전 노트", old.lines.single().original)
        assertEquals("다음 노트", retry.lines.single().original)
    }

    @Test fun providerAwaitingOperatorDoesNotKeepTheNoteInListeningState() = runTest {
        val available = MutableStateFlow(true)
        val partialArrived = CompletableDeferred<Unit>()
        val waitingEngine = object : SpeechRecognitionEngine {
            override fun recognize(frames: Flow<PcmAudioFrame>, config: SpeechRecognitionConfig) = flow {
                emit(event(0, "실패 전에 인식한 문장"))
                partialArrived.complete(Unit)
                awaitCancellation()
            }
        }
        var saved = VoiceNoteLiveSnapshot(emptyList(), "")
        val result = async {
            runCatching {
                collectVoiceNoteLiveTranscript(waitingEngine, emptyFlow(), "ko-KR", origin, { 1_000 }, available) { saved = it }
            }
        }
        partialArrived.await()
        available.value = false
        assertTrue(result.await().exceptionOrNull() is IllegalStateException)
        assertEquals("실패 전에 인식한 문장", saved.lines.single().original)
        assertEquals("", saved.partial)
    }
}
