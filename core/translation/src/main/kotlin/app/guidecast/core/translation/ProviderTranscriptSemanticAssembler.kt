package app.guidecast.core.translation

import java.util.ArrayDeque
import java.util.TreeMap

/**
 * Separates an STT provider's immutable line boundary from an interpretation-ready meaning unit.
 *
 * Android segmented recognition and Moonshine VAD may close a line during a breath or immediately
 * before a Korean particle/connective. Those provider finals are useful stability evidence, but
 * they are not translation finals. This assembler retains the bounded, ordered provider lines and
 * lets [RealtimeInterpretationSegmenter] decide the semantic boundary. A continuously observed
 * 3-5 second acoustic pause may close usable text; otherwise only real input EOF (or an explicit
 * owner finish using [finish]) flushes an incomplete tail.
 */
class ProviderTranscriptSemanticAssembler(
    private val policy: RealtimeInterpretationPolicy = sentenceCompletionInterpretationPolicy(),
    private val maximumPendingProviderLines: Int = DEFAULT_MAX_PENDING_PROVIDER_LINES,
) {
    private data class ProviderLine(
        val text: String,
        val sourceLanguageTag: String,
        val capturedAtNanos: Long,
        val isProviderFinal: Boolean,
    )

    private var segmenter = RealtimeInterpretationSegmenter(policy)
    private val providerLines = TreeMap<Long, ProviderLine>()
    private val completedProviderSequences = LinkedHashSet<Long>()
    private val outputSequences = mutableMapOf<Long, Long>()
    private val committedContext = ArrayDeque<String>()
    private var nextOutputSequence = 0L
    private var logicalSourceSequence = 0L
    private var speechActive = false
    private var lastSpeechAtNanos: Long? = null
    private var lastAudioObservationAtNanos: Long? = null
    private var speechEpochObserved = false
    private var providerResultObservedInSpeechEpoch = false
    private var noResultEndpointRequested = false

    init {
        require(maximumPendingProviderLines in 2..128)
    }

    fun observeSpeechActivity(isSpeech: Boolean, capturedAtNanos: Long) {
        require(capturedAtNanos >= 0)
        if (isSpeech && !speechActive) {
            // A new measured voice epoch releases an older provider partial only after all of its
            // text was already committed by the preceding utterance-end silence boundary.
            resetIfAllProviderLinesConsumed(allowCommittedPartialLines = true)
            speechEpochObserved = true
            providerResultObservedInSpeechEpoch = false
            noResultEndpointRequested = false
        }
        speechActive = isSpeech
        lastAudioObservationAtNanos = capturedAtNanos
        if (isSpeech) lastSpeechAtNanos = capturedAtNanos
        segmenter.observeSpeechActivity(isSpeech, capturedAtNanos)
    }

    /** Accepts a partial or provider-final line without promoting provider finality to translation. */
    fun accept(utterance: RecognizedUtterance): List<RecognizedUtterance> {
        require(!utterance.isRetracted) { "Recognition providers cannot retract provider lines" }
        providerResultObservedInSpeechEpoch = true
        if (utterance.sequence in completedProviderSequences) return emptyList()
        if (
            utterance.sequence !in providerLines &&
            providerLines.isNotEmpty() &&
            !segmenter.hasPendingText()
        ) {
            // A genuinely new provider sequence after an utterance-end commit must not remain
            // hidden behind the old partial when quiet speech never opened the VAD gate.
            resetIfAllProviderLinesConsumed(allowCommittedPartialLines = true)
        }
        if (utterance.sequence !in providerLines && providerLines.size >= maximumPendingProviderLines) {
            throw ProviderTranscriptAssemblyOverflowException(
                "음성인식 미완 문장이 ${providerLines.size}개 구간을 넘어 보존할 수 없습니다. " +
                    "텍스트를 임의로 자르지 않고 현재 인식을 중단합니다.",
            )
        }
        providerLines[utterance.sequence] = ProviderLine(
            text = if (utterance.isFinal &&
                utterance.sourceLanguageTag.substringBefore('-').equals("ko", ignoreCase = true)
            ) {
                utterance.text.withoutSpeculativeIncompleteKoreanPunctuation()
            } else {
                utterance.text
            },
            sourceLanguageTag = utterance.sourceLanguageTag,
            capturedAtNanos = utterance.capturedAtElapsedRealtimeNanos,
            isProviderFinal = utterance.isFinal,
        )

        val visibleLines = visibleProviderLines()
        if (visibleLines.none { it.key == utterance.sequence }) return emptyList()
        val aggregate = aggregateUtterance(
            lines = visibleLines.map { it.value },
            nowNanos = utterance.recognizedAtElapsedRealtimeNanos,
        )
        val output = mutableListOf<RecognizedUtterance>()
        output += remap(segmenter.accept(aggregate))
        if (utterance.isFinal) {
            // A provider final is stronger than a single revisable callback, so count the identical
            // aggregate twice for LocalAgreement. It still cannot commit without a semantic boundary
            // plus acoustic pause/confirmed right context under the product policy.
            output += remap(segmenter.accept(aggregate))
        }
        resetIfAllProviderLinesConsumed()
        return output
    }

    fun tick(nowNanos: Long): List<RecognizedUtterance> = remap(segmenter.tick(nowNanos)).also {
        resetIfAllProviderLinesConsumed()
    }

    fun shouldRequestRecognizerEndpoint(nowNanos: Long): Boolean {
        val lastSpeech = lastSpeechAtNanos
        if (
            !speechActive &&
            speechEpochObserved &&
            !providerResultObservedInSpeechEpoch &&
            !noResultEndpointRequested &&
            lastSpeech != null &&
            segmenter.hasVerifiedContinuousQuiet(
                nowNanos = nowNanos,
                requiredMillis = policy.utteranceEndSilenceMillis,
            )
        ) {
            noResultEndpointRequested = true
            return true
        }
        return segmenter.shouldRequestRecognizerEndpoint(nowNanos)
    }

    /**
     * Freezes partial lines whose provider attempt has ended, without flushing them semantically.
     * This lets a following automatic attempt append text instead of being blocked forever behind
     * a provider that errored before sending its line-final callback.
     */
    fun sealProviderAttempt(nowNanos: Long): List<RecognizedUtterance> {
        var changed = false
        providerLines.replaceAll { _, line ->
            if (line.isProviderFinal) {
                line
            } else {
                changed = true
                line.copy(
                    text = if (line.sourceLanguageTag.substringBefore('-').equals("ko", ignoreCase = true)) {
                        line.text.withoutSpeculativeIncompleteKoreanPunctuation()
                    } else {
                        line.text
                    },
                    isProviderFinal = true,
                )
            }
        }
        if (!changed || providerLines.isEmpty()) return emptyList()
        val aggregate = aggregateUtterance(
            lines = visibleProviderLines().map { it.value },
            nowNanos = nowNanos,
        )
        val output = mutableListOf<RecognizedUtterance>()
        output += remap(segmenter.accept(aggregate))
        output += remap(segmenter.accept(aggregate))
        resetIfAllProviderLinesConsumed()
        return output
    }

    /**
     * Flushes the final usable tail exactly once for real input EOF or an explicit owner stop.
     * Automatic recognizer completion, errors and backend restarts must not call this method.
     */
    fun finish(nowNanos: Long): List<RecognizedUtterance> {
        val output = mutableListOf<RecognizedUtterance>()
        // A direct EOF may arrive after an earlier partial hid later completed provider lines.
        // Freeze every line as provider-stable first, then flush the assembled semantic tail once.
        output += sealProviderAttempt(nowNanos)
        output += remap(segmenter.finish(nowNanos))
        providerLines.keys.forEach(::rememberCompletedProviderSequence)
        providerLines.clear()
        resetSegmenter()
        return output
    }

    /**
     * Never expose a later provider line ahead of an earlier revisable line. The first partial is
     * visible for preview; subsequent interleaved lines wait until every preceding line is final.
     */
    private fun visibleProviderLines(): List<Map.Entry<Long, ProviderLine>> {
        val visible = mutableListOf<Map.Entry<Long, ProviderLine>>()
        for (entry in providerLines.entries) {
            visible += entry
            if (!entry.value.isProviderFinal) break
        }
        return visible
    }

    private fun aggregateUtterance(
        lines: List<ProviderLine>,
        nowNanos: Long,
    ): RecognizedUtterance {
        // Android may spell Korean ko-KR while a restarted offline provider spells it ko.
        // Only normalize this known alias; do not merge genuinely different input languages.
        val languages = lines.map { line ->
            val tag = line.sourceLanguageTag.lowercase()
            if (tag == "ko-kr") "ko" else tag
        }.distinct()
        check(languages.size == 1) { "Provider transcript languages changed inside one pending sentence" }
        val text = lines.joinToString(" ") { it.text.trim() }.trim()
        if (text.length > MAX_ASSEMBLED_CHARACTERS) {
            throw ProviderTranscriptAssemblyOverflowException(
                "음성인식 미완 문장이 ${text.length}자로 증가해 안전 상한을 넘었습니다. " +
                    "텍스트를 임의로 자르지 않고 현재 인식을 중단합니다.",
            )
        }
        return RecognizedUtterance(
            sequence = logicalSourceSequence,
            text = text,
            sourceLanguageTag = lines.first().sourceLanguageTag,
            // Provider finality is stability evidence only. The semantic segmenter owns this flag.
            isFinal = false,
            capturedAtElapsedRealtimeNanos = lines.minOf(ProviderLine::capturedAtNanos),
            recognizedAtElapsedRealtimeNanos = nowNanos,
        )
    }

    private fun remap(events: List<RecognizedUtterance>): List<RecognizedUtterance> = events.map { event ->
        val outputSequence = outputSequences.getOrPut(event.sequence) { nextOutputSequence++ }
        val context = committedContext.joinToString(" ")
            .takeLast(MAX_CONTEXT_CHARACTERS)
            .ifBlank { null }
        event.copy(sequence = outputSequence, contextBefore = context).also { mapped ->
            if (mapped.isFinal) {
                committedContext.addLast(mapped.text)
                while (committedContext.size > MAX_CONTEXT_SEGMENTS) committedContext.removeFirst()
            }
            if (mapped.isFinal || mapped.isRetracted) outputSequences.remove(event.sequence)
        }
    }

    private fun resetIfAllProviderLinesConsumed(
        allowCommittedPartialLines: Boolean = false,
    ) {
        if (providerLines.isEmpty()) return
        if (segmenter.hasPendingText()) return
        if (!allowCommittedPartialLines && providerLines.values.any { !it.isProviderFinal }) return
        providerLines.keys.forEach(::rememberCompletedProviderSequence)
        providerLines.clear()
        resetSegmenter()
    }

    private fun resetSegmenter() {
        segmenter = RealtimeInterpretationSegmenter(policy)
        logicalSourceSequence += 1L
        outputSequences.clear()
        val observedAt = lastAudioObservationAtNanos
        val lastSpeech = lastSpeechAtNanos
        when {
            speechActive && lastSpeech != null -> segmenter.observeSpeechActivity(true, lastSpeech)
            !speechActive && lastSpeech != null && observedAt != null -> {
                segmenter.observeSpeechActivity(true, lastSpeech)
                segmenter.observeSpeechActivity(false, observedAt)
            }
        }
    }

    private fun rememberCompletedProviderSequence(sequence: Long) {
        completedProviderSequences += sequence
        while (completedProviderSequences.size > MAX_COMPLETED_PROVIDER_SEQUENCES) {
            completedProviderSequences.remove(completedProviderSequences.first())
        }
    }

    private companion object {
        const val DEFAULT_MAX_PENDING_PROVIDER_LINES = 32
        const val MAX_COMPLETED_PROVIDER_SEQUENCES = 256
        const val MAX_ASSEMBLED_CHARACTERS = 2_000
        const val MAX_CONTEXT_CHARACTERS = 300
        const val MAX_CONTEXT_SEGMENTS = 2
    }
}

private fun elapsedMillis(startNanos: Long, nowNanos: Long): Long =
    ((nowNanos - startNanos) / 1_000_000L).coerceAtLeast(0L)

class ProviderTranscriptAssemblyOverflowException(message: String) : IllegalStateException(message)
