package app.guidecast.transmitter

import app.guidecast.core.audio.AudioInputDevice
import app.guidecast.core.audio.AudioInputKind
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException

/** Restoring microphone input keeps the original broadcast languages and archive owner. */
internal data class StreamingTranslationRequest(
    val translationLanguages: List<String>,
    val sourceLanguageTag: String,
    val useGemma: Boolean,
    val selectiveTranslationRefinement: Boolean,
    val archiveSessionId: Long?,
    val broadcastGeneration: Long,
    val recognitionSequences: StreamingRecognitionSequences = StreamingRecognitionSequences(),
)

/** Preparation may finish after the operator stopped input or selected another device. */
internal data class StreamingInputPreparationRequest(
    val inputGeneration: Long,
    val broadcastGeneration: Long,
    val inputPlatformId: Int,
    val inputKind: AudioInputKind,
) {
    fun matches(currentInputGeneration: Long, currentBroadcastGeneration: Long,
        inputRequested: Boolean, snapshot: BroadcastSnapshot, selected: AudioInputDevice?): Boolean =
        inputRequested && inputGeneration == currentInputGeneration &&
            broadcastGeneration == currentBroadcastGeneration && !snapshot.isInterpreterRelay &&
            snapshot.phase in setOf(BroadcastPhase.LIVE, BroadcastPhase.PAUSED) &&
            snapshot.inputPhase == InputPhase.STARTING && selected?.platformId == inputPlatformId &&
            selected?.kind == inputKind
}

internal fun canTurnInputOff(phase: InputPhase, preparationPending: Boolean = false): Boolean =
    preparationPending || phase in setOf(InputPhase.STARTING, InputPhase.ACTIVE)

internal data class StreamingInputStopRequest(val token: Long, val jobs: List<Job>)

/** Capture cancellation is immediate, but its hardware owner remains alive until teardown ends. */
internal class StreamingInputStopCompletion {
    private var token = 0L
    private val stoppingJobs = mutableSetOf<Job>()

    @Synchronized fun beginStop(job: Job?): StreamingInputStopRequest {
        stoppingJobs.removeAll { it.isCompleted }
        job?.takeUnless { it.isCompleted }?.let(stoppingJobs::add)
        return StreamingInputStopRequest(++token, stoppingJobs.toList())
    }

    @Synchronized fun supersede() { token++ }

    @Synchronized fun completeIfReady(expectedToken: Long, onCompleted: () -> Unit): Boolean {
        stoppingJobs.removeAll { it.isCompleted }
        if (token != expectedToken || stoppingJobs.isNotEmpty()) return false
        token++
        onCompleted()
        return true
    }
}

/** Each recognizer restarts its own sequence counter; history identities must remain distinct. */
internal fun nextStreamingRecognitionSequenceBase(sequences: Iterable<Long>): Long {
    val highest = sequences.maxOrNull() ?: return 0
    require(highest >= 0 && highest < Long.MAX_VALUE) { "방송 스크립트 번호를 더 생성할 수 없습니다." }
    return highest + 1
}

internal data class StreamingRecognitionRun(val token: Long, val base: Long)

/** Clearing or trimming visible captions must not release identities already used by the archive. */
internal class StreamingRecognitionSequences {
    private var nextSequence = 0L
    private var runToken = 0L
    private var exhausted = false

    @Synchronized fun beginRun(visibleSequences: Iterable<Long>): StreamingRecognitionRun {
        check(!exhausted) { "방송 스크립트 번호를 더 생성할 수 없습니다." }
        nextSequence = maxOf(nextSequence, nextStreamingRecognitionSequenceBase(visibleSequences))
        return StreamingRecognitionRun(++runToken, nextSequence)
    }

    @Synchronized fun reserve(run: StreamingRecognitionRun, localSequence: Long): Long {
        if (run.token != runToken) throw CancellationException("Recognition input was replaced")
        require(localSequence >= 0)
        val sequence = Math.addExact(run.base, localSequence)
        if (sequence == Long.MAX_VALUE) exhausted = true
        else nextSequence = maxOf(nextSequence, sequence + 1)
        return sequence
    }
}
