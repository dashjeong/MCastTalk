package app.guidecast.core.translation

import java.util.ArrayDeque
import java.util.LinkedHashMap

/**
 * Keeps revisable state per provider line while exposing one ordered transcript sequence.
 *
 * Streaming backends may publish line B before line A's late final correction. Sharing one
 * segmenter would flush A and later speak it twice. This coordinator keeps both lines independent,
 * remaps their local preview ids to globally unique ids, and supplies shared committed context.
 */
class ConcurrentRealtimeInterpretationSegmenter(
    private val policy: RealtimeInterpretationPolicy = RealtimeInterpretationPolicy(),
) {
    private data class SourceState(
        val segmenter: RealtimeInterpretationSegmenter,
        val outputSequences: MutableMap<Long, Long> = mutableMapOf(),
    )

    private val sources = LinkedHashMap<Long, SourceState>()
    private val completedSourceSequences = LinkedHashSet<Long>()
    private val committedContext = ArrayDeque<String>()
    private var nextOutputSequence = 0L
    private var speechActive = false
    private var lastSpeechAtNanos: Long? = null
    private var lastAudioObservationAtNanos: Long? = null

    fun observeSpeechActivity(isSpeech: Boolean, capturedAtNanos: Long) {
        require(capturedAtNanos >= 0)
        lastAudioObservationAtNanos = capturedAtNanos
        if (isSpeech) {
            lastSpeechAtNanos = capturedAtNanos
        }
        speechActive = isSpeech
        sources.values.forEach { state ->
            state.segmenter.observeSpeechActivity(isSpeech, capturedAtNanos)
        }
    }

    fun accept(utterance: RecognizedUtterance): List<RecognizedUtterance> {
        if (utterance.sequence in completedSourceSequences) return emptyList()
        val output = mutableListOf<RecognizedUtterance>()
        if (utterance.sequence !in sources && sources.size >= MAX_ACTIVE_SOURCES) {
            val oldest = sources.entries.first()
            output += remap(
                state = oldest.value,
                events = oldest.value.segmenter.finish(
                    utterance.recognizedAtElapsedRealtimeNanos,
                ),
            )
            sources.remove(oldest.key)
            rememberCompletedSource(oldest.key)
        }
        val state = sources.getOrPut(utterance.sequence) { newSourceState(utterance) }
        output += remap(state, state.segmenter.accept(utterance))
        if (utterance.isFinal) {
            sources.remove(utterance.sequence)
            rememberCompletedSource(utterance.sequence)
        }
        return output
    }

    fun tick(nowNanos: Long): List<RecognizedUtterance> = buildList {
        sources.values.forEach { state -> addAll(remap(state, state.segmenter.tick(nowNanos))) }
    }

    fun shouldRequestRecognizerEndpoint(nowNanos: Long): Boolean = sources.values
        .any { state -> state.segmenter.shouldRequestRecognizerEndpoint(nowNanos) }

    fun finish(nowNanos: Long): List<RecognizedUtterance> = buildList {
        sources.forEach { (sequence, state) ->
            addAll(remap(state, state.segmenter.finish(nowNanos)))
            rememberCompletedSource(sequence)
        }
        sources.clear()
    }

    private fun newSourceState(utterance: RecognizedUtterance): SourceState {
        val segmenter = RealtimeInterpretationSegmenter(policy)
        val lastSpeech = lastSpeechAtNanos
        val observedAt = lastAudioObservationAtNanos
        when {
            speechActive -> {
                // A provider may start line B while continuous speech keeps line A alive. Seed B
                // from B's own first result/current PCM, never from the whole session's onset.
                val sourceOnset = minOf(
                    utterance.capturedAtElapsedRealtimeNanos,
                    utterance.recognizedAtElapsedRealtimeNanos,
                    lastSpeech ?: utterance.recognizedAtElapsedRealtimeNanos,
                )
                segmenter.observeSpeechActivity(true, sourceOnset)
                if (lastSpeech != null && lastSpeech > sourceOnset) {
                    segmenter.observeSpeechActivity(true, lastSpeech)
                }
            }

            lastSpeech != null -> {
                val sourceOnset = minOf(
                    utterance.capturedAtElapsedRealtimeNanos,
                    utterance.recognizedAtElapsedRealtimeNanos,
                    lastSpeech,
                )
                segmenter.observeSpeechActivity(true, sourceOnset)
                segmenter.observeSpeechActivity(false, observedAt ?: lastSpeech)
            }
        }
        return SourceState(segmenter)
    }

    private fun rememberCompletedSource(sequence: Long) {
        completedSourceSequences += sequence
        while (completedSourceSequences.size > MAX_COMPLETED_SOURCES) {
            completedSourceSequences.remove(completedSourceSequences.first())
        }
    }

    private fun remap(
        state: SourceState,
        events: List<RecognizedUtterance>,
    ): List<RecognizedUtterance> = events.map { event ->
        val outputSequence = state.outputSequences.getOrPut(event.sequence) {
            nextOutputSequence++
        }
        val context = committedContext.joinToString(" ")
            .takeLast(MAX_CONTEXT_CHARACTERS)
            .ifBlank { null }
        event.copy(
            sequence = outputSequence,
            contextBefore = context,
        ).also { mapped ->
            if (mapped.isFinal) {
                committedContext.addLast(mapped.text)
                while (committedContext.size > MAX_CONTEXT_SEGMENTS) {
                    committedContext.removeFirst()
                }
            }
            if (mapped.isFinal || mapped.isRetracted) {
                state.outputSequences.remove(event.sequence)
            }
        }
    }

    private companion object {
        const val MAX_ACTIVE_SOURCES = 8
        const val MAX_COMPLETED_SOURCES = 256
        const val MAX_CONTEXT_CHARACTERS = 300
        const val MAX_CONTEXT_SEGMENTS = 2
    }
}
