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
 * acoustic pause may confirm a linguistically complete unit; it never invents completion of a
 * dependent phrase. Product mode also flushes the unchanged whole tail after eight seconds of
 * continuously measured quiet, without closing the microphone. Strict policy can disable this
 * idle cap. Real input EOF (or an explicit owner finish using [finish]) also flushes the tail.
 * A bounded capacity recovery also
 * finishes the usable tail at a provider boundary and accepts the triggering next line.
 */
class ProviderTranscriptSemanticAssembler(
    private val policy: RealtimeInterpretationPolicy = sentenceCompletionInterpretationPolicy(),
    private val maximumPendingProviderLines: Int = DEFAULT_MAX_PENDING_PROVIDER_LINES,
    private val onRecovery: (TranscriptAssemblyRecovery) -> Unit = {},
) {
    private data class ProviderLine(
        val text: String,
        val sourceLanguageTag: String,
        val capturedAtNanos: Long,
        val isProviderFinal: Boolean,
        val originalText: String,
    )

    private var segmenter = newSegmenter()
    private val providerLines = TreeMap<Long, ProviderLine>()
    private val completedProviderSequences = LinkedHashSet<Long>()
    private data class RetiredPartial(val prefix: String, val speechEpoch: Long)
    private val retiredPartials = mutableMapOf<Long, RetiredPartial>()
    private val outputSequences = mutableMapOf<Long, Long>()
    private val committedContext = ArrayDeque<String>()
    private var nextOutputSequence = 0L
    private var logicalSourceSequence = 0L
    private var speechActive = false
    private var speechEpoch = 0L
    private var lastSpeechAtNanos: Long? = null
    private var lastAudioObservationAtNanos: Long? = null
    private var speechEpochObserved = false
    private var providerResultObservedInSpeechEpoch = false
    private var noResultEndpointRequested = false
    private var providerRefreshRequired = false

    init {
        require(maximumPendingProviderLines in 2..128)
    }

    fun observeSpeechActivity(isSpeech: Boolean, capturedAtNanos: Long) {
        require(capturedAtNanos >= 0)
        if (isSpeech && !speechActive) {
            // A new measured voice epoch releases an older provider partial only after all of its
            // text was already committed by the preceding utterance-end silence boundary.
            resetIfAllProviderLinesConsumed(allowCommittedPartialLines = true)
            speechEpoch += 1L
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
        val retired = retiredPartials[utterance.sequence]
        if (utterance.sequence in completedProviderSequences) {
            // A native recognizer may append the next utterance to the same still-open line.
            // Reopen only on a new partial in a newly measured voice epoch. A late line-final
            // callback alone cannot reopen an already published interpretation.
            if (retired != null && !utterance.isFinal && speechActive &&
                speechEpoch > retired.speechEpoch) {
                completedProviderSequences.remove(utterance.sequence)
            } else return emptyList()
        }
        val pendingText = retired?.let { utterance.text.withoutCommittedProviderPrefix(it.prefix) }
            ?: utterance.text
        if (pendingText.isBlank()) return emptyList()
        if (
            utterance.sequence !in providerLines &&
            providerLines.isNotEmpty() &&
            !segmenter.hasPendingText()
        ) {
            // A genuinely new provider sequence after an utterance-end commit must not remain
            // hidden behind the old partial when quiet speech never opened the VAD gate.
            resetIfAllProviderLinesConsumed(allowCommittedPartialLines = true)
        }
        val proposed = ProviderLine(
            text = if (utterance.isFinal) {
                pendingText.withoutSpeculativeIncompletePunctuation(utterance.sourceLanguageTag)
            } else {
                pendingText
            },
            sourceLanguageTag = utterance.sourceLanguageTag,
            capturedAtNanos = utterance.capturedAtElapsedRealtimeNanos,
            isProviderFinal = utterance.isFinal,
            originalText = utterance.text,
        )
        // Validate before mutation: a rejected callback must not poison seal/finish and every retry.
        if (proposed.text.length > MAX_ASSEMBLED_CHARACTERS) {
            throw ProviderTranscriptAssemblyOverflowException("단일 인식 결과가 안전 상한을 넘었습니다.")
        }
        val candidate = TreeMap(providerLines).apply { put(utterance.sequence, proposed) }
        val reason = when {
            candidate.size > maximumPendingProviderLines -> TranscriptAssemblyRecovery.PROVIDER_LIMIT
            candidate.values.sumOf { it.text.trim().length + 1 } - 1 > MAX_ASSEMBLED_CHARACTERS ->
                TranscriptAssemblyRecovery.CHARACTER_LIMIT
            else -> null
        }
        if (reason != null) {
            if (utterance.sequence in providerLines) {
                throw ProviderTranscriptAssemblyOverflowException("인식 결과 수정이 문장 보존 상한을 넘었습니다.")
            }
            // Emergency capacity boundary only, never a timer or arbitrary character cut. Preserve
            // the entire usable tail once, then accept the triggering line into a fresh buffer.
            val recovered = finish(utterance.recognizedAtElapsedRealtimeNanos)
            onRecovery(reason)
            return recovered + accept(utterance)
        }
        // Validate hidden/interleaved lines too, before changing the recoverable buffer.
        val allAggregate = aggregateUtterance(candidate.values.toList(), utterance.recognizedAtElapsedRealtimeNanos)
        val visibleLines = visibleProviderLines(candidate)
        providerLines[utterance.sequence] = proposed
        if (visibleLines.none { it.key == utterance.sequence }) return emptyList()
        val aggregate = if (visibleLines.size == candidate.size) allAggregate else aggregateUtterance(
            lines = visibleLines.map { it.value },
            nowNanos = utterance.recognizedAtElapsedRealtimeNanos,
        )
        providerRefreshRequired = false
        val output = mutableListOf<RecognizedUtterance>()
        output += remap(segmenter.accept(aggregate))
        if (utterance.isFinal) {
            // A provider final is stronger than a single revisable callback, so count the identical
            // aggregate twice for LocalAgreement. It still cannot commit without a semantic boundary
            // plus acoustic pause/confirmed right context under the product policy.
            output += remap(segmenter.accept(aggregate))
        }
        discardCommittedProviderLines()
        resetIfAllProviderLinesConsumed()
        return output
    }

    fun tick(nowNanos: Long): List<RecognizedUtterance> {
        val output = remap(segmenter.tick(nowNanos)).toMutableList()
        // A whole partial committed by measured silence is no longer revisable. Retire its
        // provider id now, rather than allowing a late changed final to publish a second tail.
        // Prefix matching below protects partial lines whose uncommitted words still remain.
        val retiredPartial = discardCommittedProviderLines(
            allowCommittedPartialLines = !speechActive,
        )
        if ((retiredPartial || providerRefreshRequired) && providerLines.isNotEmpty()) {
            // An interleaved later result may have been hidden behind that revisable line.
            // Publish it in order instead of dropping it when resetting the old partial.
            val visible = visibleProviderLines()
            val aggregate = aggregateUtterance(visible.map { it.value }, nowNanos)
            providerRefreshRequired = false
            output += remap(segmenter.accept(aggregate))
            if (visible.all { it.value.isProviderFinal }) {
                output += remap(segmenter.accept(aggregate))
            }
            discardCommittedProviderLines()
        }
        resetIfAllProviderLinesConsumed()
        return output
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
                    text = line.text.withoutSpeculativeIncompletePunctuation(line.sourceLanguageTag),
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
        discardCommittedProviderLines()
        resetIfAllProviderLinesConsumed()
        return output
    }

    /**
     * Flushes the final usable tail exactly once for real input EOF or an explicit owner stop.
     * Ordinary automatic completion and backend restarts preserve their tail instead. Capacity
     * recovery and an explicit operator reconnect may call this to resume without losing that tail.
     */
    fun finish(nowNanos: Long): List<RecognizedUtterance> {
        val output = mutableListOf<RecognizedUtterance>()
        // A direct EOF may arrive after an earlier partial hid later completed provider lines.
        // Freeze every line as provider-stable first, then flush the assembled semantic tail once.
        output += sealProviderAttempt(nowNanos)
        output += remap(segmenter.finish(nowNanos))
        providerLines.keys.forEach(::rememberCompletedProviderSequence)
        providerLines.clear()
        providerRefreshRequired = false
        resetSegmenter()
        return output
    }

    /**
     * Never expose a later provider line ahead of an earlier revisable line. The first partial is
     * visible for preview; subsequent interleaved lines wait until every preceding line is final.
     */
    private fun visibleProviderLines(lines: Map<Long, ProviderLine> = providerLines): List<Map.Entry<Long, ProviderLine>> {
        val visible = mutableListOf<Map.Entry<Long, ProviderLine>>()
        for (entry in lines.entries) {
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
        val context = wholeMeaningContext(committedContext)
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
        val retiredPartial = discardCommittedProviderLines(allowCommittedPartialLines)
        // Hidden interleaved lines were never presented to the segmenter. They must survive
        // a new voice epoch and be reevaluated by the independent ticker, not be marked done.
        if (providerLines.isNotEmpty()) {
            if (retiredPartial) providerRefreshRequired = true
            return
        }
        resetSegmenter()
    }

    /** Returns whether a committed partial was retired, exposing previously hidden results. */
    private fun discardCommittedProviderLines(allowCommittedPartialLines: Boolean = false): Boolean {
        var retiredPartial = false
        while (providerLines.isNotEmpty()) {
            val first = providerLines.firstEntry()
            if ((!first.value.isProviderFinal && !allowCommittedPartialLines) ||
                !segmenter.discardCommittedProviderPrefix(first.value.text)) break
            retiredPartial = retiredPartial || !first.value.isProviderFinal
            if (!first.value.isProviderFinal) {
                retiredPartials[first.key] = RetiredPartial(
                    // A provider line is bounded to 2,000 characters. Never accumulate a
                    // session-long prefix when a vendor reuses the same id with fresh text.
                    prefix = first.value.originalText,
                    speechEpoch = speechEpoch,
                )
            } else retiredPartials.remove(first.key)
            providerLines.remove(first.key)
            rememberCompletedProviderSequence(first.key)
        }
        return retiredPartial
    }

    private fun resetSegmenter() {
        segmenter = newSegmenter()
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
            val oldest = completedProviderSequences.first()
            completedProviderSequences.remove(oldest)
            retiredPartials.remove(oldest)
        }
    }

    private fun newSegmenter() = RealtimeInterpretationSegmenter(policy) {
        onRecovery(TranscriptAssemblyRecovery.IDLE_SILENCE_LIMIT)
    }

    private companion object {
        const val DEFAULT_MAX_PENDING_PROVIDER_LINES = 32
        const val MAX_COMPLETED_PROVIDER_SEQUENCES = 256
        const val MAX_ASSEMBLED_CHARACTERS = 2_000
        const val MAX_CONTEXT_SEGMENTS = 2
    }
}

private fun String.withoutCommittedProviderPrefix(prefix: String): String {
    val words = trim().split(Regex("\\s+"))
    val committed = prefix.trim().split(Regex("\\s+"))
    if (words.take(committed.size) == committed) return words.drop(committed.size).joinToString(" ")
    // Orthographic ASR revisions can join/split Korean spaces without changing any character.
    // Find a true token boundary with the same complete committed text, not a raw token count.
    val joinedPrefix = committed.joinToString("")
    words.indices.firstOrNull { words.take(it + 1).joinToString("") == joinedPrefix }
        ?.let { return words.drop(it + 1).joinToString(" ") }

    // Reuse the segmenter's bounded edit distances. A 2–4-token suffix anchor must survive,
    // and the best prefix alignment must have only a small correction cost. A fresh unrelated
    // utterance using the same vendor id remains intact; never drop words by approximate count.
    val distances = tokenPrefixEditDistances(committed, words)
    val maximumCorrectionCost = maxOf(1, committed.size / 3)
    for (anchorSize in minOf(4, committed.size) downTo 2) {
        val anchor = committed.takeLast(anchorSize)
        val candidates = (anchorSize..words.size).filter { boundary ->
            words.subList(boundary - anchorSize, boundary) == anchor &&
                distances[boundary] <= maximumCorrectionCost
        }
        candidates.minWithOrNull(compareBy<Int> { distances[it] }.thenByDescending { it })
            ?.let { return words.drop(it).joinToString(" ") }
    }
    // A one-word surviving ending also needs a multi-word old prefix and a very small edit
    // cost. This handles punctuation or a single repaired word, without erasing a new sentence
    // that happens to begin with the same subject but has a different predicate.
    if (committed.size >= 3) {
        val candidates = words.indices.filter { index ->
            words[index] == committed.last() && distances[index + 1] <= maximumCorrectionCost
        }
        candidates.minWithOrNull(compareBy<Int> { distances[it + 1] }.thenByDescending { it })
            ?.let { return words.drop(it + 1).joinToString(" ") }
    }
    return this
}

private fun String.withoutSpeculativeIncompletePunctuation(languageTag: String): String =
    when (languageTag.substringBefore('-').lowercase()) {
        "ko" -> withoutSpeculativeIncompleteKoreanPunctuation()
        "en" -> withoutSpeculativeIncompleteEnglishPunctuation()
        else -> this
    }

private fun elapsedMillis(startNanos: Long, nowNanos: Long): Long =
    ((nowNanos - startNanos) / 1_000_000L).coerceAtLeast(0L)

class ProviderTranscriptAssemblyOverflowException(message: String) : IllegalStateException(message)

enum class TranscriptAssemblyRecovery { PROVIDER_LIMIT, CHARACTER_LIMIT, IDLE_SILENCE_LIMIT }
