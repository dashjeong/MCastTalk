package app.guidecast.transmitter

import app.guidecast.core.stream.PcmAudioFrame
import app.guidecast.core.translation.RecognizedUtterance
import app.guidecast.core.translation.SpeechRecognitionConfig
import app.guidecast.core.translation.SpeechRecognitionEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PreparedFileSpeechTranscriptionTest {
    private fun event(final: Boolean) = RecognizedUtterance(0, if (final) "완료 문장" else "부분 문장", "ko-KR", final, 0)
    private val sample = FilePcmFrame(ByteArray(640) { 1 }, 0, 20)

    @Test fun finiteFileCompletesEvenWhenLiveProviderDoesNotCloseOnEof() = runTest {
        var framesSeen = 0
        val engine = object : SpeechRecognitionEngine {
            override fun recognize(frames: Flow<PcmAudioFrame>, config: SpeechRecognitionConfig) = flow {
                frames.collect {
                    framesSeen++
                    if (framesSeen == 1) emit(event(false))
                }
                emit(event(true))
                awaitCancellation()
            }
        }
        val result = transcribePreparedFileFrames(flowOf(sample), engine, "ko-KR", { 20 },
            nowNanos = { testScheduler.currentTime * 1_000_000 }, finalDrainMillis = 100)
        assertEquals("완료 문장", result.segments.single().text)
        assertEquals(20, result.durationMs)
        assertEquals(101, framesSeen)
        assertTrue(result.segments.all { it.endMs <= 20 && it.timingEstimated && it.words.isEmpty() })
    }

    @Test fun providerEndingBeforeConsumingFileIsNotReportedAsComplete() = runTest {
        val engine = object : SpeechRecognitionEngine {
            override fun recognize(frames: Flow<PcmAudioFrame>, config: SpeechRecognitionConfig) = emptyFlow<RecognizedUtterance>()
        }
        val failure = runCatching {
            transcribePreparedFileFrames(flowOf(sample), engine, "ko-KR", { 20 }, nowNanos = { 0 })
        }.exceptionOrNull()
        assertTrue(failure is FileTranscriptionException)
    }

    @Test fun cancellationStopsDecodedInputAndDoesNotYieldAFinishedResult() = runTest {
        val started = CompletableDeferred<Unit>()
        var closed = false
        var completed = false
        val source = flow {
            try { emit(sample); started.complete(Unit); awaitCancellation() }
            finally { closed = true }
        }
        val engine = object : SpeechRecognitionEngine {
            override fun recognize(frames: Flow<PcmAudioFrame>, config: SpeechRecognitionConfig) = flow<RecognizedUtterance> {
                frames.collect { }
            }
        }
        val job = launch {
            transcribePreparedFileFrames(source, engine, "ko-KR", { null }, nowNanos = { 0 })
            completed = true
        }
        started.await()
        job.cancelAndJoin()
        assertTrue(closed)
        assertFalse(completed)
    }

    @Test fun terminalReadinessFailureCannotReportPartialFileAsSuccessful() = runTest {
        val available = MutableStateFlow(true)
        val engine = object : SpeechRecognitionEngine {
            override fun recognize(frames: Flow<PcmAudioFrame>, config: SpeechRecognitionConfig) = flow {
                frames.collect { emit(event(false)); available.value = false }
            }
        }
        val result = async { runCatching {
            transcribePreparedFileFrames(flowOf(sample), engine, "ko-KR", { 20 }, available, nowNanos = { 0 })
        } }
        assertTrue(result.await().exceptionOrNull() is FileTranscriptionException)
    }

    @Test fun sourcePolicyPreservesPlatformOnlyFileLanguages() {
        assertTrue(FileSpeechTranscriber.usesAppRecognition("ko-KR"))
        assertTrue(FileSpeechTranscriber.usesAppRecognition("en-US"))
        assertFalse(FileSpeechTranscriber.usesAppRecognition("fr-FR"))
        assertFalse(FileSpeechTranscriber.usesAppRecognition("de-DE"))
        assertFalse(FileSpeechTranscriber.usesAppRecognition("vi-VN"))
    }

    @Test fun retractedPreviewAndDuplicateFinalDoNotDuplicateTheScript() {
        val transcript = PreparedFileTranscript(0, "ko-KR")
        transcript.accept(event(false), 10)
        transcript.accept(RecognizedUtterance(0, "", "ko-KR", false, 0, isRetracted = true), 10)
        assertTrue(transcript.finish(10).isEmpty())
        transcript.accept(event(true), 20)
        transcript.accept(event(true), 20)
        assertEquals(listOf("완료 문장"), transcript.finish(20).map { it.text })
    }

    @Test fun preparationDelayAndSlowConsumptionDoNotShiftMediaStart() = runTest {
        var seen = 0
        val engine = object : SpeechRecognitionEngine {
            override fun recognize(frames: Flow<PcmAudioFrame>, config: SpeechRecognitionConfig) = flow {
                delay(1_000) // Engine session startup before the first decoded sample is accepted.
                frames.collect { frame ->
                    seen++
                    if (seen == 1) delay(900) // Consumption slower than the 20 ms media interval.
                    if (seen == 2) emit(RecognizedUtterance(0, "두 번째 구간 문장입니다.", "ko-KR", true,
                        frame.capturedAtElapsedRealtimeNanos))
                }
                awaitCancellation()
            }
        }
        val result = transcribePreparedFileFrames(
            flowOf(sample, sample.copy(startMs = 20, endMs = 40)), engine, "ko-KR", { 40 },
            nowNanos = { testScheduler.currentTime * 1_000_000 }, finalDrainMillis = 100,
        )
        assertEquals(20L, result.segments.single().startMs)
        assertEquals(40L, result.segments.single().endMs)
        assertEquals(40L, result.durationMs)
    }

    @Test fun boundedMediaTimelineKeepsEstablishedPendingOnsetAfterOldFramesExpire() {
        val timeline = FileMediaTimeline(maximumFrames = 2)
        timeline.record(1_000_000_000, 0, 20)
        val transcript = PreparedFileTranscript(0, "ko-KR", timeline::mediaMillis)
        val partial = RecognizedUtterance(0, "시작한 문장", "ko-KR", false, 1_000_000_000)
        transcript.accept(partial, 20)
        timeline.record(2_000_000_000, 20, 40)
        timeline.record(5_000_000_000, 40, 60)
        assertNull(timeline.mediaMillis(1_000_000_000))
        assertEquals(25L, requireNotNull(timeline.mediaMillis(2_005_000_000)))
        assertEquals(40L, requireNotNull(timeline.mediaMillis(4_000_000_000))) // Waiting adds no media.
        transcript.accept(partial.copy(text = "시작한 문장입니다.", isFinal = true), 60)
        assertEquals(0L, transcript.finish(60).single().startMs)
    }

    @Test fun lateFinalCannotClipFollowingUtteranceToItsCallbackTime() {
        val timeline = FileMediaTimeline()
        timeline.record(1_000_000_000, 0, 20)
        val transcript = PreparedFileTranscript(0, "ko-KR", timeline::mediaMillis)
        transcript.accept(RecognizedUtterance(0, "첫 문장입니다.", "ko-KR", true, 1_000_000_000), 2_000)
        timeline.record(3_000_000_000, 1_000, 1_020)
        transcript.accept(RecognizedUtterance(1, "다음 문장입니다.", "ko-KR", true, 3_000_000_000), 3_000)
        val result = transcript.finish(3_000)
        assertEquals(1_000L, result[0].endMs)
        assertEquals(1_000L, result[1].startMs)
        assertTrue(result.zipWithNext().all { (first, next) -> first.endMs <= next.startMs })
    }

    @Test fun finalDrainTimeoutDoesNotPromoteAnIncompleteHypothesisToSuccessfulScript() = runTest {
        var seen = false
        val engine = object : SpeechRecognitionEngine {
            override fun recognize(frames: Flow<PcmAudioFrame>, config: SpeechRecognitionConfig) = flow {
                frames.collect {
                    if (!seen) { seen = true; emit(event(false)) }
                }
                delay(10_000)
                emit(event(true))
            }
        }
        val failure = runCatching {
            transcribePreparedFileFrames(flowOf(sample), engine, "ko-KR", { 20 },
                nowNanos = { testScheduler.currentTime * 1_000_000 }, finalDrainMillis = 100)
        }.exceptionOrNull()
        assertTrue(failure is FileTranscriptionException)
        assertTrue(failure?.message.orEmpty().contains("확정되지 않아"))
    }
}
