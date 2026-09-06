package app.guidecast.core.translation

import java.util.ArrayDeque

/**
 * Turns revisable streaming STT hypotheses into small, non-overlapping interpretation units.
 *
 * Audio is never cut into fixed seven-second files. Sentence endings and punctuation win first,
 * then a real acoustic pause, then a semantic prefix that survived repeated STT revisions. A
 * separate tick advances the decision budget even when the recognizer stops sending callbacks.
 * Elapsed time alone never chooses an arbitrary word boundary.
 */
class RealtimeInterpretationSegmenter(
    private val policy: RealtimeInterpretationPolicy = RealtimeInterpretationPolicy(),
) {
    private data class ObservedToken(
        val text: String,
        val firstObservedAtNanos: Long,
    )

    private var sourceSequence: Long? = null
    private var sourceLanguageTag: String? = null
    private var latest: RecognizedUtterance? = null
    private val committedSourceTokens = mutableListOf<String>()
    private var pendingSinceNanos: Long? = null
    private var lastHypothesisChangeAtNanos: Long? = null
    private var previewSequence: Long? = null
    private var previewText: String? = null
    private var nextOutputSequence = 0L
    private var speechActive = false
    private var speechStartedAtNanos: Long? = null
    private var lastSpeechAtNanos: Long? = null
    private var lastAudioObservationAtNanos: Long? = null
    private var continuousQuietStartedAtNanos: Long? = null
    private var lastCommitAtNanos: Long? = null
    private var observedFullTokens = emptyList<ObservedToken>()
    private var recognizerEndpointRequested = false
    private val hypothesisHistory = ArrayDeque<List<String>>()
    private val committedContext = ArrayDeque<String>()

    /** Supplies a conservative PCM-derived speech/pause hint; STT text remains authoritative. */
    fun observeSpeechActivity(isSpeech: Boolean, capturedAtNanos: Long) {
        require(capturedAtNanos >= 0)
        val previousAudioObservation = lastAudioObservationAtNanos
        if (isSpeech) {
            if (!speechActive) speechStartedAtNanos = capturedAtNanos
            speechActive = true
            lastSpeechAtNanos = capturedAtNanos
            continuousQuietStartedAtNanos = null
        } else {
            val observationGapMillis = elapsedMillis(previousAudioObservation, capturedAtNanos)
            if (
                previousAudioObservation == null ||
                speechActive ||
                observationGapMillis > policy.maximumQuietObservationGapMillis
            ) {
                continuousQuietStartedAtNanos = capturedAtNanos
            }
            speechActive = false
        }
        lastAudioObservationAtNanos = capturedAtNanos
    }

    /** Accepts one provider hypothesis and returns UI preview and/or translation-ready events. */
    fun accept(utterance: RecognizedUtterance): List<RecognizedUtterance> {
        require(!utterance.isRetracted) { "Recognition providers cannot retract app output ids" }
        val output = mutableListOf<RecognizedUtterance>()
        if (sourceSequence != null && sourceSequence != utterance.sequence) {
            output += flushPending(utterance.recognizedAtElapsedRealtimeNanos)
            resetSource()
        }
        if (sourceSequence == null) {
            sourceSequence = utterance.sequence
            sourceLanguageTag = utterance.sourceLanguageTag
        }

        val fullTextTokens = utterance.text.toInterpretationTokens()
        if (fullTextTokens != observedFullTokens.map(ObservedToken::text)) {
            lastHypothesisChangeAtNanos = utterance.recognizedAtElapsedRealtimeNanos
        }
        latest = utterance
        observedFullTokens = alignObservedTokens(
            previous = observedFullTokens,
            current = fullTextTokens,
            initialObservationNanos = initialObservationNanos(utterance),
            newTokenObservationNanos = utterance.recognizedAtElapsedRealtimeNanos,
        )
        val residual = residualTokens(observedFullTokens)
        updatePendingSince(residual.oldestObservationNanos())

        if (utterance.isFinal) {
            if (residual.isNotEmpty()) {
                output += commit(
                    tokens = residual.map(ObservedToken::text),
                    nowNanos = utterance.recognizedAtElapsedRealtimeNanos,
                    capturedAtNanos = requireNotNull(residual.oldestObservationNanos()),
                )
            } else {
                retractPreview()?.let(output::add)
            }
            resetSource()
            return output
        }

        if (residual.isEmpty()) {
            retractPreview()?.let(output::add)
            hypothesisHistory.clear()
            updatePendingSince(null)
            return output
        }

        remember(residual.map(ObservedToken::text))
        output += evaluate(
            residual = residual,
            nowNanos = utterance.recognizedAtElapsedRealtimeNanos,
            source = utterance,
        )
        return output
    }

    /**
     * Advances pause/deadline decisions without requiring another recognizer callback. This is not
     * an audio cutter: it can only commit text already present in a usable streaming hypothesis.
     */
    fun tick(nowNanos: Long): List<RecognizedUtterance> {
        val utterance = latest ?: return emptyList()
        if (utterance.isFinal) return emptyList()
        val residual = residualTokens(observedFullTokens)
        if (residual.isEmpty()) return listOfNotNull(retractPreview())
        return evaluate(residual, nowNanos, utterance)
    }

    /** Requests one recognizer endpoint only when usable text itself has remained pending. */
    fun shouldRequestRecognizerEndpoint(nowNanos: Long): Boolean {
        if (!policy.recognizerEndpointEnabled) return false
        if (recognizerEndpointRequested || latest?.isFinal != false) return false
        val pendingSince = pendingSinceNanos ?: return false
        if (elapsedMillis(pendingSince, nowNanos) < policy.recognizerEndpointRequestMillis) {
            return false
        }
        if (residualTokens(observedFullTokens).isEmpty()) return false
        recognizerEndpointRequested = true
        return true
    }

    /** True while a usable, not-yet-committed hypothesis remains buffered. */
    internal fun hasPendingText(): Boolean = residualTokens(observedFullTokens).isNotEmpty()

    /** True only while gap-free quiet PCM observations still cover [requiredMillis]. */
    internal fun hasVerifiedContinuousQuiet(nowNanos: Long, requiredMillis: Long): Boolean =
        verifiedQuietMillis(nowNanos) >= requiredMillis

    /** Flushes the last usable hypothesis when the upstream recognizer changes sessions. */
    fun finish(nowNanos: Long): List<RecognizedUtterance> {
        val output = flushPending(nowNanos)
        resetSource()
        return output
    }

    private fun evaluate(
        residual: List<ObservedToken>,
        nowNanos: Long,
        source: RecognizedUtterance,
    ): List<RecognizedUtterance> {
        val residualText = residual.map(ObservedToken::text)
        val commitCount = chooseCommitCount(residualText, nowNanos)
        if (commitCount <= 0) return listOfNotNull(preview(residual, source, nowNanos))

        val output = mutableListOf<RecognizedUtterance>()
        val committed = residual.take(commitCount)
        output += commit(
            tokens = committed.map(ObservedToken::text),
            nowNanos = nowNanos,
            capturedAtNanos = requireNotNull(committed.oldestObservationNanos()),
        )
        committedSourceTokens += committed.map(ObservedToken::text)
        lastCommitAtNanos = nowNanos

        val tail = residual.drop(commitCount)
        hypothesisHistory.clear()
        if (tail.isNotEmpty()) {
            hypothesisHistory.addLast(tail.map(ObservedToken::text))
            // Each aligned token owns its original observation time, and a newly inserted token
            // starts at the callback where it first appears. The oldest still-pending token drives
            // the safety budget even when a recognizer inserts a newer word before it.
            updatePendingSince(tail.oldestObservationNanos())
            preview(tail, source, nowNanos)?.let(output::add)
        } else {
            updatePendingSince(null)
        }
        return output
    }

    private fun chooseCommitCount(residual: List<String>, nowNanos: Long): Int {
        if (residual.isEmpty()) return 0
        val stable = if (hypothesisHistory.size >= policy.stableHypothesisCount) {
            longestCommonPrefix(hypothesisHistory.toList())
        } else {
            emptyList()
        }
        val elapsedMillis = elapsedMillis(pendingSinceNanos, nowNanos)
        val textQuietMillis = elapsedMillis(lastHypothesisChangeAtNanos, nowNanos)
        val textSettled = textQuietMillis >= policy.utteranceEndTextStabilityMillis
        val verifiedQuietMillis = verifiedQuietMillis(nowNanos)
        val undetectedSpeechQuietReady =
            !speechActive &&
                lastSpeechAtNanos == null &&
                verifiedQuietMillis >=
                policy.undetectedSpeechUtteranceEndSilenceMillis &&
                textQuietMillis >= policy.undetectedSpeechUtteranceEndSilenceMillis
        val quietMillis = when {
            speechActive -> 0L
            lastSpeechAtNanos != null -> verifiedQuietMillis
            undetectedSpeechQuietReady -> verifiedQuietMillis
            else -> 0L
        }

        // A sustained measured pause is an utterance boundary even when the provider emitted only
        // one revisable hypothesis. This is deliberately separate from the wall-clock decision
        // budget: ongoing speech keeps quietMillis at zero and is never hard-cut by this path.
        if (
            quietMillis >= policy.utteranceEndSilenceMillis &&
            textSettled &&
            residual.hasUsefulText()
        ) {
            return residual.size
        }

        val requirePositiveKoreanCompletion = requiresPositiveKoreanCompletion()
        val strongBoundary = stable.indexOfLast { token ->
            isStrongBoundary(token) &&
                (!requirePositiveKoreanCompletion || isPositiveKoreanSentenceEnding(token))
        }
        val stableContinuationTokens = stable.size - (strongBoundary + 1)
        val sentenceConfirmedByContinuation =
            policy.allowStableSentenceContinuationCommit &&
                stableContinuationTokens >= policy.semanticContinuationTailTokens
        if (
            strongBoundary >= 0 &&
            (!policy.requireAcousticPauseForBoundary ||
                (quietMillis >= policy.sentencePauseMillis && textSettled) ||
                sentenceConfirmedByContinuation) &&
            stable.take(strongBoundary + 1).hasUsefulText()
        ) {
            return strongBoundary + 1
        }

        val weakBoundary = stable.indexOfLast { token ->
            isWeakBoundary(token) &&
                (!requirePositiveKoreanCompletion || isPositiveKoreanWeakBoundary(token))
        }
        if (
            elapsedMillis >= policy.weakBoundaryWaitMillis &&
            weakBoundary >= 0 &&
            (!policy.requireAcousticPauseForBoundary ||
                (quietMillis >= policy.phrasePauseMillis && textSettled)) &&
            stable.take(weakBoundary + 1).hasUsefulText()
        ) {
            return weakBoundary + 1
        }

        // A real pause is stronger evidence than a wall-clock deadline. A short stable phrase may
        // therefore complete naturally, while continuous speech still keeps revisable right text.
        if (quietMillis >= policy.unpunctuatedPauseMillis && textSettled) {
            // The inexpensive PCM energy gate is advisory only. It may be confused by wind or
            // music, so a pause can accelerate a commit only after LocalAgreement confirms text.
            val paused = stable.size.takeIf {
                it >= policy.minimumPauseCommitTokens &&
                    stable.lastOrNull()?.let(::isLikelyIncompleteBoundaryToken) != true &&
                    (!policy.requireCompleteKoreanMeaningForUnpunctuatedPause ||
                        sourceLanguageTag?.substringBefore('-')?.equals("ko", ignoreCase = true) != true ||
                        stable.lastOrNull()?.let(::isPositiveKoreanSentenceEnding) == true)
            } ?: 0
            if (paused > 0 && residual.take(paused).hasUsefulText()) return paused
        }
        if (!policy.requireAcousticPauseForBoundary &&
            quietMillis >= policy.phrasePauseMillis &&
            textSettled &&
            stable.size >= policy.minimumContinuousCommitTokens
        ) {
            return stable.size
        }

        if (policy.allowContinuousSpeechCommit &&
            elapsedMillis >= policy.continuousSpeechCommitMillis
        ) {
            val safeCount = stable.size - policy.unstableTailTokenCount
            if (safeCount >= policy.minimumContinuousCommitTokens) return safeCount
        }

        if (policy.allowSemanticContinuousSpeechCommit &&
            elapsedMillis >= policy.semanticContinuousSpeechCommitMillis
        ) {
            // For continuous, unpunctuated Korean speech, only advance at a grammatical phrase
            // boundary that LocalAgreement has kept stable. Keep stable right-context as well as
            // the recognizer's revisable tail so a connective is never translated in isolation.
            val searchablePrefixSize =
                (stable.size - policy.semanticRightContextTokens).coerceAtLeast(0)
            val boundary = stable.take(searchablePrefixSize)
                .indexOfLast(::isKoreanSemanticPhraseBoundary)
            val semanticCount = boundary + 1
            if (semanticCount >= policy.minimumContinuousCommitTokens) return semanticCount
        }

        if (policy.allowContinuousSpeechCommit &&
            elapsedMillis >= policy.maximumDecisionWaitMillis
        ) {
            // Reserve the final ~2 seconds for translation/TTS/web buffering. Never force one or
            // two unstable words merely to satisfy a clock; wait for a final/pause instead.
            val safeCount = stable.size - 1
            if (safeCount >= policy.minimumContinuousCommitTokens) return safeCount
        }
        return 0
    }

    private fun verifiedQuietMillis(nowNanos: Long): Long {
        if (speechActive) return 0L
        val latestObservation = lastAudioObservationAtNanos ?: return 0L
        val quietStart = continuousQuietStartedAtNanos ?: return 0L
        if (
            elapsedMillis(latestObservation, nowNanos) >
            policy.maximumQuietObservationAgeMillis
        ) {
            return 0L
        }
        return elapsedMillis(quietStart, nowNanos)
    }

    private fun requiresPositiveKoreanCompletion(): Boolean =
        policy.requireCompleteKoreanMeaningForUnpunctuatedPause &&
            sourceLanguageTag?.substringBefore('-')?.equals("ko", ignoreCase = true) == true

    private fun commit(
        tokens: List<String>,
        nowNanos: Long,
        capturedAtNanos: Long,
    ): RecognizedUtterance {
        val sequence = previewSequence ?: allocateSequence()
        previewSequence = null
        previewText = null
        val text = tokens.joinToString(" ").take(MAX_UTTERANCE_CHARACTERS)
        val contextBefore = committedContext.joinToString(" ")
            .takeLast(MAX_CONTEXT_CHARACTERS)
            .ifBlank { null }
        return RecognizedUtterance(
            sequence = sequence,
            text = text,
            sourceLanguageTag = requireNotNull(sourceLanguageTag),
            isFinal = true,
            capturedAtElapsedRealtimeNanos = capturedAtNanos,
            recognizedAtElapsedRealtimeNanos = nowNanos,
            contextBefore = contextBefore,
            firstAudioDeadlineElapsedRealtimeNanos = capturedAtNanos +
                policy.listenerMaximumWaitMillis * 1_000_000L,
        ).also {
            committedContext.addLast(text)
            while (committedContext.size > MAX_CONTEXT_SEGMENTS) committedContext.removeFirst()
        }
    }

    private fun preview(
        tokens: List<ObservedToken>,
        source: RecognizedUtterance,
        nowNanos: Long,
    ): RecognizedUtterance? {
        val text = tokens.joinToString(" ") { it.text }.take(MAX_UTTERANCE_CHARACTERS)
        if (text == previewText) return null
        previewText = text
        val capturedAtNanos = tokens.oldestObservationNanos()
            ?: source.capturedAtElapsedRealtimeNanos
        return RecognizedUtterance(
            sequence = previewSequence ?: allocateSequence().also { previewSequence = it },
            text = text,
            sourceLanguageTag = source.sourceLanguageTag,
            isFinal = false,
            capturedAtElapsedRealtimeNanos = capturedAtNanos,
            recognizedAtElapsedRealtimeNanos = nowNanos,
            contextBefore = committedContext.joinToString(" ")
                .takeLast(MAX_CONTEXT_CHARACTERS)
                .ifBlank { null },
            firstAudioDeadlineElapsedRealtimeNanos = capturedAtNanos +
                policy.listenerMaximumWaitMillis * 1_000_000L,
        )
    }

    private fun retractPreview(): RecognizedUtterance? {
        val sequence = previewSequence ?: return null
        previewSequence = null
        previewText = null
        return RecognizedUtterance(
            sequence = sequence,
            text = "",
            sourceLanguageTag = sourceLanguageTag ?: return null,
            isFinal = false,
            capturedAtElapsedRealtimeNanos = pendingSinceNanos ?: 0L,
            recognizedAtElapsedRealtimeNanos = latest
                ?.recognizedAtElapsedRealtimeNanos
                ?: pendingSinceNanos
                ?: 0L,
            contextBefore = null,
            isRetracted = true,
            firstAudioDeadlineElapsedRealtimeNanos = pendingSinceNanos?.plus(
                policy.listenerMaximumWaitMillis * 1_000_000L,
            ),
        )
    }

    private fun flushPending(nowNanos: Long): List<RecognizedUtterance> {
        val utterance = latest ?: return emptyList()
        val residual = residualTokens(observedFullTokens)
        if (residual.isEmpty()) return listOfNotNull(retractPreview())
        return listOf(
            commit(
                tokens = residual.map(ObservedToken::text),
                nowNanos = nowNanos,
                capturedAtNanos = requireNotNull(residual.oldestObservationNanos()),
            ),
        )
    }

    /** Maps an immutable spoken prefix back onto an STT hypothesis that may insert/delete words. */
    private fun residualTokens(full: List<ObservedToken>): List<ObservedToken> {
        if (committedSourceTokens.isEmpty()) return full
        val fullText = full.map(ObservedToken::text)
        if (fullText.size >= committedSourceTokens.size &&
            fullText.subList(0, committedSourceTokens.size) == committedSourceTokens
        ) {
            return full.drop(committedSourceTokens.size)
        }

        val expected = committedSourceTokens.size
        val editDistances = tokenPrefixEditDistances(committedSourceTokens, fullText)

        // A two-to-four token suffix anchor is resistant to a correction near the beginning and
        // safer than dropping a raw count when the recognizer inserts or deletes a word.
        for (anchorSize in minOf(MAX_ALIGNMENT_ANCHOR_TOKENS, expected) downTo 2) {
            val anchor = committedSourceTokens.takeLast(anchorSize)
            val candidates = (anchorSize..fullText.size).filter { boundary ->
                boundary >= anchorSize &&
                    fullText.subList(boundary - anchorSize, boundary) == anchor
            }
            if (candidates.isNotEmpty()) {
                val boundary = candidates.minWithOrNull(
                    compareBy<Int> { editDistances[it] }.thenByDescending { it },
                ) ?: expected.coerceAtMost(full.size)
                return full.drop(boundary)
            }
        }

        // Last-resort bounded edit alignment. Prefer the boundary with the smallest edit cost;
        // when costs tie, keep the one containing more of the old prefix to avoid replay.
        val boundary = (0..fullText.size).minWithOrNull(
            compareBy<Int> { editDistances[it] }.thenByDescending { it },
        ) ?: expected.coerceAtMost(full.size)
        return full.drop(boundary)
    }

    private fun remember(tokens: List<String>) {
        hypothesisHistory.addLast(tokens)
        while (hypothesisHistory.size > policy.stableHypothesisCount) {
            hypothesisHistory.removeFirst()
        }
    }

    private fun allocateSequence(): Long = nextOutputSequence++

    private fun initialObservationNanos(utterance: RecognizedUtterance): Long {
        val speechStart = speechStartedAtNanos
            ?.coerceAtLeast(lastCommitAtNanos ?: 0L)
        return listOfNotNull(
            speechStart,
            utterance.capturedAtElapsedRealtimeNanos,
            utterance.recognizedAtElapsedRealtimeNanos,
        ).minOrNull() ?: utterance.recognizedAtElapsedRealtimeNanos
    }

    /** Carries token ages through exact matches, substitutions, insertions and deletions. */
    private fun alignObservedTokens(
        previous: List<ObservedToken>,
        current: List<String>,
        initialObservationNanos: Long,
        newTokenObservationNanos: Long,
    ): List<ObservedToken> {
        if (current.isEmpty()) return emptyList()
        if (previous.isEmpty()) {
            return current.map { text -> ObservedToken(text, initialObservationNanos) }
        }

        val operations = Array(previous.size + 1) { ByteArray(current.size + 1) }
        for (oldIndex in 1..previous.size) {
            operations[oldIndex][0] = ALIGN_DELETE
        }
        var previousDistances = IntArray(current.size + 1) { it }
        var previousExactMatches = IntArray(current.size + 1)
        var currentDistances = IntArray(current.size + 1)
        var currentExactMatches = IntArray(current.size + 1)
        for (newIndex in 1..current.size) {
            operations[0][newIndex] = ALIGN_INSERT
        }
        for (oldIndex in 1..previous.size) {
            currentDistances[0] = oldIndex
            currentExactMatches[0] = 0
            for (newIndex in 1..current.size) {
                val sameToken = previous[oldIndex - 1].text == current[newIndex - 1]
                var selectedCost = previousDistances[newIndex - 1] +
                    if (sameToken) 0 else 1
                var selectedMatches = previousExactMatches[newIndex - 1] +
                    if (sameToken) 1 else 0
                var selectedOperation = ALIGN_DIAGONAL
                var selectedPriority = if (sameToken) 0 else 3

                val deleteCost = previousDistances[newIndex] + 1
                val deleteMatches = previousExactMatches[newIndex]
                if (
                    deleteCost < selectedCost ||
                    (deleteCost == selectedCost && deleteMatches > selectedMatches) ||
                    (deleteCost == selectedCost && deleteMatches == selectedMatches &&
                        1 < selectedPriority)
                ) {
                    selectedCost = deleteCost
                    selectedMatches = deleteMatches
                    selectedOperation = ALIGN_DELETE
                    selectedPriority = 1
                }

                val insertCost = currentDistances[newIndex - 1] + 1
                val insertMatches = currentExactMatches[newIndex - 1]
                if (
                    insertCost < selectedCost ||
                    (insertCost == selectedCost && insertMatches > selectedMatches) ||
                    (insertCost == selectedCost && insertMatches == selectedMatches &&
                        2 < selectedPriority)
                ) {
                    selectedCost = insertCost
                    selectedMatches = insertMatches
                    selectedOperation = ALIGN_INSERT
                }

                currentDistances[newIndex] = selectedCost
                currentExactMatches[newIndex] = selectedMatches
                operations[oldIndex][newIndex] = selectedOperation
            }
            val completedDistances = currentDistances
            currentDistances = previousDistances
            previousDistances = completedDistances
            val completedMatches = currentExactMatches
            currentExactMatches = previousExactMatches
            previousExactMatches = completedMatches
        }

        val inheritedTimes = arrayOfNulls<Long>(current.size)
        var oldIndex = previous.size
        var newIndex = current.size
        while (oldIndex > 0 || newIndex > 0) {
            when (operations[oldIndex][newIndex]) {
                ALIGN_DIAGONAL -> {
                    inheritedTimes[newIndex - 1] =
                        previous[oldIndex - 1].firstObservedAtNanos
                    oldIndex -= 1
                    newIndex -= 1
                }

                ALIGN_INSERT -> newIndex -= 1
                else -> oldIndex -= 1
            }
        }

        return current.mapIndexed { index, text ->
            ObservedToken(
                text = text,
                firstObservedAtNanos = inheritedTimes[index] ?: newTokenObservationNanos,
            )
        }
    }

    private fun List<ObservedToken>.oldestObservationNanos(): Long? =
        minOfOrNull(ObservedToken::firstObservedAtNanos)

    private fun updatePendingSince(value: Long?) {
        if (pendingSinceNanos != value) recognizerEndpointRequested = false
        pendingSinceNanos = value
    }

    private fun resetSource() {
        sourceSequence = null
        sourceLanguageTag = null
        latest = null
        committedSourceTokens.clear()
        pendingSinceNanos = null
        lastHypothesisChangeAtNanos = null
        previewSequence = null
        previewText = null
        hypothesisHistory.clear()
        observedFullTokens = emptyList()
        recognizerEndpointRequested = false
        speechStartedAtNanos = if (speechActive) lastSpeechAtNanos else null
    }

    private companion object {
        const val MAX_UTTERANCE_CHARACTERS = 2_000
        const val MAX_CONTEXT_CHARACTERS = 300
        const val MAX_CONTEXT_SEGMENTS = 2
        const val MAX_ALIGNMENT_ANCHOR_TOKENS = 4
        const val ALIGN_DIAGONAL: Byte = 0
        const val ALIGN_DELETE: Byte = 1
        const val ALIGN_INSERT: Byte = 2
    }
}

data class RealtimeInterpretationPolicy(
    val stableHypothesisCount: Int = 2,
    val phrasePauseMillis: Long = 420,
    val sentencePauseMillis: Long = 850,
    val unpunctuatedPauseMillis: Long = sentencePauseMillis,
    /** Verified no-voice duration that closes any usable pending partial as one utterance. */
    val utteranceEndSilenceMillis: Long = 3_000,
    /** Prevents old acoustic silence from instantly committing a newly changed ASR partial. */
    val utteranceEndTextStabilityMillis: Long = 500,
    /** Fail-safe boundary when PCM is continuously quiet but no speech frame crossed the VAD. */
    val undetectedSpeechUtteranceEndSilenceMillis: Long = 5_000,
    val maximumQuietObservationGapMillis: Long = 500,
    val maximumQuietObservationAgeMillis: Long = 500,
    val weakBoundaryWaitMillis: Long = 1_600,
    val continuousSpeechCommitMillis: Long = 3_600,
    val maximumDecisionWaitMillis: Long = 4_600,
    val listenerMaximumWaitMillis: Long = 7_000,
    val recognizerEndpointRequestMillis: Long = 4_800,
    val unstableTailTokenCount: Int = 2,
    val minimumContinuousCommitTokens: Int = 3,
    val minimumPauseCommitTokens: Int = 2,
    /** Product mode favors complete meaning over a timer-only prefix commit. */
    val requireAcousticPauseForBoundary: Boolean = false,
    val allowContinuousSpeechCommit: Boolean = true,
    val recognizerEndpointEnabled: Boolean = true,
    /** A complete sentence followed by this many stable words is safe without a literal pause. */
    val allowStableSentenceContinuationCommit: Boolean = false,
    val semanticContinuationTailTokens: Int = 3,
    /** Allows bounded continuous speech commits only at a LocalAgreement-stable phrase boundary. */
    val allowSemanticContinuousSpeechCommit: Boolean = false,
    val semanticContinuousSpeechCommitMillis: Long = continuousSpeechCommitMillis,
    val semanticRightContextTokens: Int = unstableTailTokenCount,
    /** Korean product mode requires positive sentence-final evidence even after a long pause. */
    val requireCompleteKoreanMeaningForUnpunctuatedPause: Boolean = false,
) {
    init {
        require(stableHypothesisCount in 2..4)
        require(phrasePauseMillis in 200..1_000)
        require(sentencePauseMillis in phrasePauseMillis..1_500)
        require(unpunctuatedPauseMillis in sentencePauseMillis..3_000)
        require(utteranceEndSilenceMillis in 3_000..5_000)
        require(utteranceEndTextStabilityMillis in 300..1_000)
        require(undetectedSpeechUtteranceEndSilenceMillis in 5_000..8_000)
        require(maximumQuietObservationGapMillis in 100..1_000)
        require(maximumQuietObservationAgeMillis in 100..1_000)
        require(weakBoundaryWaitMillis in 500..5_000)
        require(continuousSpeechCommitMillis in 2_000..6_000)
        require(maximumDecisionWaitMillis in continuousSpeechCommitMillis..5_000)
        require(listenerMaximumWaitMillis in 6_000..15_000)
        require(recognizerEndpointRequestMillis in maximumDecisionWaitMillis..14_000)
        require(recognizerEndpointRequestMillis <= listenerMaximumWaitMillis)
        require(maximumDecisionWaitMillis < listenerMaximumWaitMillis)
        require(unstableTailTokenCount in 1..3)
        require(minimumContinuousCommitTokens in 2..6)
        require(minimumPauseCommitTokens in 2..4)
        require(semanticContinuationTailTokens in 2..6)
        require(semanticContinuousSpeechCommitMillis in 2_000..6_000)
        require(semanticRightContextTokens in 2..6)
    }
}

/**
 * Product interpretation policy for every supported device. Translation receives a complete
 * stable phrase after a measured pause or confirmed right context. Provider line-final callbacks
 * are only stability evidence; elapsed wall time alone never cuts a continuing sentence.
 */
fun sentenceCompletionInterpretationPolicy(): RealtimeInterpretationPolicy =
    RealtimeInterpretationPolicy(
        stableHypothesisCount = 2,
        phrasePauseMillis = 900,
        sentencePauseMillis = 1_200,
        unpunctuatedPauseMillis = 1_800,
        utteranceEndSilenceMillis = 3_000,
        utteranceEndTextStabilityMillis = 500,
        undetectedSpeechUtteranceEndSilenceMillis = 5_000,
        maximumQuietObservationGapMillis = 500,
        maximumQuietObservationAgeMillis = 500,
        weakBoundaryWaitMillis = 1_800,
        continuousSpeechCommitMillis = 4_600,
        maximumDecisionWaitMillis = 4_900,
        // Twelve seconds is a one-shot recovery ceiling, never a repeating PCM/audio cutter.
        // Natural provider finals, measured pauses and stable semantic boundaries normally commit
        // earlier. Only a stretch with no safe boundary at all requests one endpoint at the ceiling;
        // the flushed text remains in context for the following recognition session.
        listenerMaximumWaitMillis = 12_000,
        recognizerEndpointRequestMillis = 12_000,
        unstableTailTokenCount = 2,
        minimumContinuousCommitTokens = 4,
        minimumPauseCommitTokens = 2,
        requireAcousticPauseForBoundary = true,
        allowContinuousSpeechCommit = false,
        recognizerEndpointEnabled = true,
        allowStableSentenceContinuationCommit = true,
        semanticContinuationTailTokens = 3,
        // Provider lines and elapsed time are not meaning boundaries. During uninterrupted speech,
        // wait for a complete sentence confirmed by right context instead of committing a Korean
        // connective such as "하지만" or "없는데" as an isolated translation request.
        allowSemanticContinuousSpeechCommit = false,
        semanticContinuousSpeechCommitMillis = 4_600,
        semanticRightContextTokens = 2,
        requireCompleteKoreanMeaningForUnpunctuatedPause = true,
    )

private fun elapsedMillis(startNanos: Long?, nowNanos: Long): Long =
    ((nowNanos - (startNanos ?: nowNanos)) / 1_000_000L).coerceAtLeast(0L)

private fun String.toInterpretationTokens(): List<String> = trim()
    .split(WHITESPACE)
    .filter(String::isNotBlank)

private fun longestCommonPrefix(hypotheses: List<List<String>>): List<String> {
    if (hypotheses.isEmpty()) return emptyList()
    val shortest = hypotheses.minOf(List<String>::size)
    var index = 0
    while (index < shortest && hypotheses.all { it[index] == hypotheses.first()[index] }) index += 1
    return hypotheses.first().take(index)
}

private fun tokenPrefixEditDistances(left: List<String>, right: List<String>): IntArray {
    var previous = IntArray(right.size + 1) { it }
    left.forEachIndexed { leftIndex, leftToken ->
        val current = IntArray(right.size + 1)
        current[0] = leftIndex + 1
        right.forEachIndexed { rightIndex, rightToken ->
            current[rightIndex + 1] = minOf(
                current[rightIndex] + 1,
                previous[rightIndex + 1] + 1,
                previous[rightIndex] + if (leftToken == rightToken) 0 else 1,
            )
        }
        previous = current
    }
    return previous
}

private fun List<String>.hasUsefulText(): Boolean =
    joinToString("").count(Char::isLetterOrDigit) >= 2 ||
        singleOrNull()?.trimEnd('.', '!', '?', '。', '！', '？') in KOREAN_COMPLETE_SHORT_REPLIES

// Explicit complete answers, not a blanket one-character rule that would commit "왜" or "안".
private val KOREAN_COMPLETE_SHORT_REPLIES = setOf("네", "예")

private fun isStrongBoundary(token: String): Boolean {
    val normalized = token.trimEnd('"', '\'', '”', '’', ')', ']', '}')
    val withoutTrailingPunctuation = normalized.trimEnd('.', '!', '?', '。', '！', '？', ',', ';', ':')
    if (isLikelyIncompleteBoundaryToken(normalized)) return false
    if (normalized.lastOrNull() in STRONG_PUNCTUATION) return true
    return withoutTrailingPunctuation in KOREAN_COMPLETE_SHORT_REPLIES ||
        KOREAN_SENTENCE_ENDINGS.any(withoutTrailingPunctuation::endsWith)
}

/** Protects common Korean fragments when a recognizer speculatively appends sentence punctuation. */
private fun isLikelyIncompleteKoreanBoundary(word: String): Boolean =
    word in KOREAN_INCOMPLETE_BOUNDARY_WORDS ||
        KOREAN_INCOMPLETE_BOUNDARY_ENDINGS.any(word::endsWith)

private fun isPositiveKoreanSentenceEnding(token: String): Boolean {
    val normalized = token.trimEnd('"', '\'', '”', '’', ')', ']', '}')
        .trimEnd('.', '!', '?', '。', '！', '？', ',', ';', ':', '，', '；', '：')
    return !isLikelyIncompleteKoreanBoundary(normalized) &&
        (normalized in KOREAN_COMPLETE_SHORT_REPLIES || KOREAN_SENTENCE_ENDINGS.any(normalized::endsWith))
}

private fun isPositiveKoreanWeakBoundary(token: String): Boolean {
    val normalized = token.trimEnd('"', '\'', '”', '’', ')', ']', '}')
        .trimEnd(',', ';', ':', '，', '；', '：')
    if (isLikelyIncompleteKoreanBoundary(normalized)) return false
    return isPositiveKoreanSentenceEnding(normalized) ||
        KOREAN_CONNECTIVE_ENDINGS.any(normalized::endsWith) ||
        (normalized.length >= 3 && normalized.endsWith("고") &&
            normalized !in KOREAN_NON_BOUNDARY_GO_WORDS)
}

private fun isWeakBoundary(token: String): Boolean {
    val normalized = token.trimEnd('"', '\'', '”', '’', ')', ']', '}')
    return !isLikelyIncompleteBoundaryToken(normalized) &&
        normalized.lastOrNull() in WEAK_PUNCTUATION
}

private fun isLikelyIncompleteBoundaryToken(token: String): Boolean {
    val normalized = token.trimEnd('"', '\'', '”', '’', ')', ']', '}')
        .trimEnd('.', '!', '?', '。', '！', '？', ',', ';', ':', '，', '；', '：')
    return isLikelyIncompleteKoreanBoundary(normalized) ||
        token.matches(NON_TERMINAL_LATIN_ABBREVIATION) ||
        token.matches(NON_TERMINAL_DECIMAL)
}

/** Removes only recognizer-added boundary punctuation from a known incomplete Korean tail. */
internal fun String.withoutSpeculativeIncompleteKoreanPunctuation(): String {
    val normalized = trim()
    val tokenStart = normalized.indexOfLast(Char::isWhitespace) + 1
    val token = normalized.substring(tokenStart)
    val lexical = token.trimEnd(
        '.', '!', '?', '。', '！', '？', ',', ';', ':', '，', '；', '：',
    )
    if (lexical == token) return normalized
    val supportedBoundary = when (token.lastOrNull()) {
        in STRONG_PUNCTUATION -> isPositiveKoreanSentenceEnding(token)
        in WEAK_PUNCTUATION -> isPositiveKoreanWeakBoundary(token)
        else -> true
    }
    if (supportedBoundary) return normalized
    return normalized.substring(0, tokenStart) + lexical
}

/**
 * Conservative Korean phrase boundaries for simultaneous interpretation without punctuation.
 *
 * This is intentionally narrower than "any old stable token": connective/adverbial endings can
 * close a translatable meaning unit while the following stable tokens preserve right context.
 * Punctuated tokens are excluded because commas require a measured pause and sentence punctuation
 * is handled by [isStrongBoundary]. A full morphological analyzer may replace this predicate, but
 * the fallback must remain conservative and deterministic offline.
 */
private fun isKoreanSemanticPhraseBoundary(token: String): Boolean {
    val normalized = token.trimEnd('"', '\'', '”', '’', ')', ']', '}')
    if (normalized.lastOrNull() in STRONG_PUNCTUATION ||
        normalized.lastOrNull() in WEAK_PUNCTUATION
    ) {
        return false
    }
    val word = normalized.trim()
    if (word in KOREAN_SEMANTIC_BOUNDARY_WORDS) return true
    if (KOREAN_CONNECTIVE_ENDINGS.any(word::endsWith)) return true
    // "-고" is productive, but common two-syllable nouns (사고/광고/최고/원고...) and the
    // conjunction "그리고" must not become false boundaries. Requiring a longer eojeol is a
    // deliberately conservative offline approximation (이동하고/이어지고/설명하고).
    return word.length >= 3 && word.endsWith("고") && word !in KOREAN_NON_BOUNDARY_GO_WORDS
}

private val WHITESPACE = Regex("\\s+")
private val NON_TERMINAL_LATIN_ABBREVIATION =
    Regex("(?i)(?:(?:[a-z]\\.){2,}|(?:dr|mr|mrs|ms|no|vs|etc)\\.)")
private val NON_TERMINAL_DECIMAL = Regex("[+-]?\\d+\\.\\d+%?\\.?")
private val STRONG_PUNCTUATION = setOf('.', '!', '?', '。', '！', '？')
private val WEAK_PUNCTUATION = setOf(',', ';', ':', '，', '；', '：')
private val KOREAN_SENTENCE_ENDINGS = listOf(
    "습니다", "니다", "습니까", "합니까", "입니다", "합니다", "됩니다", "있습니다", "없습니다",
    "십시오", "주세요", "하세요", "해요", "했어요", "돼요", "예요", "이에요",
    "아요", "어요", "나요", "까요", "네요", "군요", "랍니다", "죠",
)
private val KOREAN_CONNECTIVE_ENDINGS = listOf(
    "으면서", "면서", "지만", "았지만", "었지만", "는데", "은데", "인데",
    "으므로", "므로", "으니까", "니까", "도록", "거나", "든지", "으려고", "려고",
    "자마자", "더니", "다가", "어서", "아서",
)
private val KOREAN_SEMANTIC_BOUNDARY_WORDS = setOf(
    "동안", "이후", "후에", "뒤에", "다음에", "다음으로", "이어서", "따라", "통해", "위해",
)
private val KOREAN_NON_BOUNDARY_GO_WORDS = setOf(
    "그리고", "그러고",
)
private val KOREAN_INCOMPLETE_BOUNDARY_WORDS = setOf(
    "내부의", "때문에", "하지만", "어떻게", "그리고", "그러나", "따라서", "그래서",
    "우리가", "저희가", "제가", "이게", "그게", "이유는", "안", "하지",
)
private val KOREAN_INCOMPLETE_BOUNDARY_ENDINGS = listOf(
    "없는데", "있는데", "하는데", "했는데", "인데", "으면서", "면서", "지만",
    "으므로", "므로", "으니까", "니까", "도록", "거나", "든지", "으려고", "려고",
    "자마자", "더니", "다가", "어서", "아서", "에서의", "까지의", "으로의", "와의", "과의",
)
