package app.guidecast.core.translation

import app.guidecast.core.stream.AudioChannelDescriptor
import app.guidecast.core.stream.ChannelAudioPublicationCoordinator
import app.guidecast.core.stream.AudioStreamRegistry
import app.guidecast.core.stream.MAX_SIMULTANEOUS_TRANSLATED_CHANNELS
import app.guidecast.core.stream.PcmAudioFrame
import app.guidecast.core.stream.StreamPublishStatus
import app.guidecast.core.stream.StreamSession
import app.guidecast.core.stream.StreamSessionSupersededException
import java.io.Closeable
import java.util.LinkedHashSet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext

enum class TranslationWorkerState {
    IDLE,
    ACTIVE,
    DEGRADED,
}

/**
 * Observable health for one independently scheduled language channel.
 *
 * Translation and synthesis have separate states because the next subtitle may be translated
 * while the preceding sentence is still being spoken. Failures are counted per stage so an
 * operator can identify one degraded language without treating the whole broadcast as failed.
 */
data class TranslationChannelHealth(
    val channelId: String,
    val targetLanguageTag: String = channelId,
    val lastAcceptedSequence: Long? = null,
    val lastTranslatedSequence: Long? = null,
    val lastSynthesizedSequence: Long? = null,
    val lastPublishedSequence: Long? = null,
    val publishedFrameCount: Long = 0,
    /** Backward-compatible publication completion marker; never means synthesis-only. */
    val lastCompletedSequence: Long? = null,
    val droppedUtterances: Long = 0,
    val translationFailures: Long = 0,
    val synthesisFailures: Long = 0,
    /** Failed translation items followed by a later successful item on this same channel. */
    val translationRecoveries: Long = 0,
    /** Failed synthesis items followed by a later successful item on this same channel. */
    val synthesisRecoveries: Long = 0,
    val translationState: TranslationWorkerState = TranslationWorkerState.IDLE,
    val synthesisState: TranslationWorkerState = TranslationWorkerState.IDLE,
    val lastTranslationElapsedMillis: Long? = null,
    val lastFirstAudioElapsedMillis: Long? = null,
    val lastSynthesisElapsedMillis: Long? = null,
    val lastTranslationError: String? = null,
    val lastSynthesisError: String? = null,
    val lastError: String? = null,
    val lastSynthesisPcm: SynthesizedPcmStats? = null,
)

class TranslationBroadcastPipeline(
    private val streams: AudioStreamRegistry,
    private val translationEngines: TranslationEngineProvider,
    private val speechEngines: SpeechSynthesisEngineProvider,
    private val queueCapacityPerLanguage: Int = 4,
    // Sixteen finalized meaning units absorb normal guide-speech bursts without allowing one
    // stalled language to replay minutes of obsolete directions after it recovers.
    private val sourceDispatchCapacityPerLanguage: Int = 16,
    // Bounded live TTS FIFO: keeps a balanced reserve (8 utterances) to absorb natural
    // conversational bursts without premature drops, while preserving strict per-language isolation
    // and bounded live-edge latency. Overflows drop the oldest pending phrase and are explicitly
    // recorded in health metrics (droppedUtterances, synthesisFailures).
    private val speechQueueCapacityPerLanguage: Int = 8,
    private val observer: TranslationPipelineObserver = TranslationPipelineObserver.NONE,
    private val shouldPublishAudio: () -> Boolean = { true },
    private val translationTimeoutMillis: Long = 4_000L,
    private val translationTimeoutMillisByChannel: Map<String, Long> = emptyMap(),
    private val translationTimeoutMillisForChannel: ((String) -> Long)? = null,
    private val firstAudioTimeoutMillis: Long = 7_000L,
    private val synthesisFrameIdleTimeoutMillis: Long = 2_000L,
    /** Absolute ceiling even when a broken provider keeps emitting audible frames forever. */
    private val synthesisTotalTimeoutMillis: Long = 120_000L,
    private val currentElapsedRealtimeNanos: () -> Long = System::nanoTime,
    private val audioPublicationCoordinator: ChannelAudioPublicationCoordinator? = null,
    private val glossaryTerms: suspend (String, String, String) -> List<GlossaryTerm> = { _, _, _ -> emptyList() },
    private val onGlossaryWarning: (String) -> Unit = {},
) {
    init {
        require(queueCapacityPerLanguage in 1..8)
        require(sourceDispatchCapacityPerLanguage in 2..64)
        require(speechQueueCapacityPerLanguage in 1..32)
        require(translationTimeoutMillis in 500..15_000)
        require(translationTimeoutMillisByChannel.values.all { it in 500..15_000 })
        // This is a recovery deadline, not a latency target. Note 9-class devices can need a
        // longer bounded window while the native voice worker is cold; accepting that policy here
        // prevents pipeline construction from crashing before the microphone starts. Product
        // latency is measured independently from this failure ceiling.
        require(firstAudioTimeoutMillis in 500..120_000)
        require(synthesisFrameIdleTimeoutMillis in 500..15_000)
        require(synthesisTotalTimeoutMillis in firstAudioTimeoutMillis..180_000L)
    }

    fun start(
        scope: CoroutineScope,
        utterances: Flow<RecognizedUtterance>,
        targets: List<TranslationTarget>,
        sourceLanguageTag: String? = null,
        streamSession: StreamSession? = null,
    ): RunningTranslationPipeline {
        require(
            targets.isNotEmpty() && targets.size <= MAX_SIMULTANEOUS_TRANSLATED_CHANNELS,
        ) { "Choose one to $MAX_SIMULTANEOUS_TRANSLATED_CHANNELS target languages" }
        require(targets.map { it.channelId }.toSet().size == targets.size) {
            "Translation channel ids must be unique"
        }
        require(targets.none { it.channelId == "source" }) {
            "Original audio is not a translation target"
        }
        require(targets.map { normalizedLanguageTag(it.languageTag) }.toSet().size == targets.size) {
            "Translation target languages must be unique"
        }
        targets.forEach { target ->
            require(LANGUAGE_TAG.matches(target.languageTag)) { "Invalid target language tag" }
        }
        sourceLanguageTag?.let { source ->
            require(LANGUAGE_TAG.matches(source)) { "Invalid source language tag" }
            require(targets.none { sameLanguage(it.languageTag, source) }) {
                "Source language cannot also be a target language"
            }
        }

        val channelDescriptors = targets.map { target ->
            AudioChannelDescriptor(
                id = target.channelId,
                displayName = target.displayName,
                languageTag = target.languageTag,
                sampleRateHz = target.speechSampleRateHz,
            )
        }
        val ownsStreamSession = streamSession == null
        val activeStreamSession = streamSession?.also { provided ->
            // The broadcast owner also publishes original audio. Validate every translation
            // descriptor strictly, but never turn that independent source into a TTS target.
            require(provided.channels.filterNot { it.id == "source" } == channelDescriptors) {
                "Provided translation channels must exactly match translation targets"
            }
            require(provided.isActive()) {
                "Provided stream session was already superseded or closed"
            }
        } ?: streams.configure(channelDescriptors)

        val isolationJob = SupervisorJob(scope.coroutineContext[Job])
        val isolatedScope = CoroutineScope(scope.coroutineContext + isolationJob)
        val mutableHealth = MutableStateFlow(
            targets.map {
                TranslationChannelHealth(
                    channelId = it.channelId,
                    targetLanguageTag = it.languageTag,
                )
            },
        )
        val acceptingSource = AtomicBoolean(true)
        val translationQueues = targets.associateWith {
            Channel<RecognizedUtterance>(
                capacity = queueCapacityPerLanguage,
            )
        }
        val sourceDispatchQueues = targets.associateWith { target ->
            Channel<RecognizedUtterance>(
                capacity = sourceDispatchCapacityPerLanguage,
                // A stalled language must not hold the source fan-out or any other language.
                // Prefer the newest completed meaning unit and make every per-language
                // skip visible only after its independent dispatch reserve is truly exhausted.
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
                onUndeliveredElement = {
                    if (acceptingSource.get()) {
                        mutableHealth.updateChannel(target.channelId) { health ->
                            health.copy(
                                droppedUtterances = health.droppedUtterances + 1,
                                translationState = TranslationWorkerState.DEGRADED,
                                lastTranslationError = BACKLOG_DROP_MESSAGE,
                                lastError = BACKLOG_DROP_MESSAGE,
                            )
                        }
                    }
                },
            )
        }
        val speechQueues = targets.associateWith { target ->
            Channel<TranslatedSpeechWork>(
                capacity = speechQueueCapacityPerLanguage,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
                onUndeliveredElement = {
                    if (acceptingSource.get()) {
                        mutableHealth.updateChannel(target.channelId) { health ->
                            health.copy(
                                droppedUtterances = health.droppedUtterances + 1,
                                synthesisFailures = health.synthesisFailures + 1,
                                synthesisState = TranslationWorkerState.DEGRADED,
                                lastSynthesisError = SPEECH_BACKLOG_DROP_MESSAGE,
                                lastError = SPEECH_BACKLOG_DROP_MESSAGE,
                            )
                        }
                    }
                },
            )
        }
        val sourceDispatchWorkers = targets.map { target ->
            isolatedScope.launch {
                try {
                    for (utterance in requireNotNull(sourceDispatchQueues[target])) {
                        requireNotNull(translationQueues[target]).send(utterance)
                    }
                } finally {
                    requireNotNull(translationQueues[target]).close()
                }
            }
        }
        val translationWorkers = targets.map { target ->
            isolatedScope.launch {
                try {
                    for (utterance in requireNotNull(translationQueues[target])) {
                        try {
                            mutableHealth.updateChannel(target.channelId) {
                                it.copy(translationState = TranslationWorkerState.ACTIVE)
                            }
                            val translationMark = TimeSource.Monotonic.markNow()
                            // Provider workers can be recreated after Android reclaims an
                            // isolated model process. Acquire inside the per-item recovery scope:
                            // one failed reconnect must not kill this language forever.
                            val translator = translationEngines.engineFor(target.languageTag)
                            // Gemma is used only by the selected priority channel. Its larger
                            // recovery ceiling must not make the four ML Kit channels wait for
                            // stale work for the same 15 seconds.
                            val configuredTimeoutMillis = translationTimeoutMillisForChannel
                                ?.invoke(target.channelId)
                                ?: translationTimeoutMillisByChannel[target.channelId]
                                ?: translationTimeoutMillis
                            check(configuredTimeoutMillis in 500..15_000) {
                                "Invalid translation deadline for ${target.channelId}"
                            }
                            // Fair shared engines enforce queue and actual inference deadlines
                            // separately. Keep an outer safety bound without counting normal queue
                            // wait against the old 4-second per-engine watchdog.
                            val queuedEngine = translator as? BoundedQueuedTranslationEngine
                            val queueBudget = queuedEngine?.maximumCallDurationMillis ?: configuredTimeoutMillis
                            check(queueBudget in 500..90_000) { "Invalid shared translation budget" }
                            val targetTimeoutMillis = if (queuedEngine == null) configuredTimeoutMillis
                                else maxOf(configuredTimeoutMillis, queueBudget + 100L)
                            // Take one snapshot: edits affect the next utterance, never a subtitle
                            // that has already committed its exact TTS input.
                            val terms = glossaryTerms(utterance.text, utterance.sourceLanguageTag, target.languageTag)
                            val rawTranslation = withContext(TranslationGlossaryContext(GlossaryTerms.hints(terms))) {
                              withTimeout(targetTimeoutMillis) {
                                if (translator is ContextualTextTranslationEngine) {
                                    translator.translateWithContext(
                                        text = utterance.text,
                                        contextBefore = utterance.contextBefore,
                                        sourceLanguageTag = utterance.sourceLanguageTag,
                                        targetLanguageTag = target.languageTag,
                                    )
                                } else {
                                    translator.translate(
                                        utterance.text,
                                        utterance.sourceLanguageTag,
                                        target.languageTag,
                                    )
                                }
                              }
                            }
                            val translated = try {
                                GlossaryTerms.correct(rawTranslation, terms)
                            } catch (error: GlossaryExpansionException) {
                                // Bad correction data must not truncate a sentence or silence a
                                // healthy translator/TTS. Keep its complete translation and warn.
                                onGlossaryWarning("${target.languageTag}: ${error.message} 해당 문장은 교정 전 번역으로 계속합니다.")
                                rawTranslation
                            }
                            val translationElapsedMillis =
                                translationMark.elapsedNow().inWholeMilliseconds
                            observer.onTranslationCompleted(
                                utterance = utterance,
                                target = target,
                                translatedText = translated,
                                elapsedMillis = translationElapsedMillis,
                            )
                            mutableHealth.updateChannel(target.channelId) {
                                val recovered = it.lastTranslationError != null
                                it.copy(
                                    lastTranslatedSequence = utterance.sequence,
                                    translationRecoveries = it.translationRecoveries +
                                        if (recovered) 1 else 0,
                                    translationState = TranslationWorkerState.IDLE,
                                    lastTranslationElapsedMillis = translationElapsedMillis,
                                    lastTranslationError = null,
                                    lastError = it.lastSynthesisError,
                                )
                            }
                            // A published translation is a committed transcript. Queue its speech
                            // separately so PCM pacing never blocks later subtitles; if this one
                            // language falls farther behind than its small reserve, its oldest
                            // unspoken item is dropped explicitly to preserve live guidance.
                            requireNotNull(speechQueues[target]).send(
                                TranslatedSpeechWork(utterance, translated),
                            )
                        } catch (timeout: TimeoutCancellationException) {
                            mutableHealth.updateChannel(target.channelId) {
                                it.copy(
                                    droppedUtterances = it.droppedUtterances + 1,
                                    translationFailures = it.translationFailures + 1,
                                    translationState = TranslationWorkerState.DEGRADED,
                                    lastTranslationError = TRANSLATION_TIMEOUT_MESSAGE,
                                    lastError = TRANSLATION_TIMEOUT_MESSAGE,
                                )
                            }
                        } catch (cancelled: CancellationException) {
                            // Google Task and isolated provider workers can report their own
                            // cancellation while this broadcast/session is still active. Treat
                            // that as one language's recoverable item failure; only propagate a
                            // real parent/session cancellation. Otherwise this worker exits
                            // forever while its UI incorrectly remains ACTIVE.
                            currentCoroutineContext().ensureActive()
                            val message = cancelled.message ?: TRANSLATION_CANCELLED_MESSAGE
                            mutableHealth.updateChannel(target.channelId) {
                                it.copy(
                                    droppedUtterances = it.droppedUtterances + 1,
                                    translationFailures = it.translationFailures + 1,
                                    translationState = TranslationWorkerState.DEGRADED,
                                    lastTranslationError = message,
                                    lastError = message,
                                )
                            }
                        } catch (error: Throwable) {
                            val message = error.message ?: error::class.simpleName
                            mutableHealth.updateChannel(target.channelId) {
                                it.copy(
                                    droppedUtterances = it.droppedUtterances + 1,
                                    translationFailures = it.translationFailures + 1,
                                    translationState = TranslationWorkerState.DEGRADED,
                                    lastTranslationError = message,
                                    lastError = message,
                                )
                            }
                        }
                    }
                } finally {
                    requireNotNull(speechQueues[target]).close()
                }
            }
        }
        val speechWorkers = targets.map { target ->
            isolatedScope.launch {
                for (work in requireNotNull(speechQueues[target])) {
                    val utterance = work.utterance
                    var publicationLease: Closeable? = null
                    try {
                        publicationLease = audioPublicationCoordinator?.acquireChannel(target.channelId)
                        mutableHealth.updateChannel(target.channelId) {
                            it.copy(synthesisState = TranslationWorkerState.ACTIVE)
                        }
                        // As with translation, an engine lookup can fail transiently while its
                        // private process reconnects. Keep the language worker alive so the next
                        // already-translated sentence can retry instead of becoming permanently
                        // silent.
                        val speech = speechEngines.engineFor(target.languageTag)
                        val synthesisMark = TimeSource.Monotonic.markNow()
                        val pcmAccumulator = PcmS16LeAccumulator()
                        var firstAudibleFrameReported = false
                        var publishedFramesForWork = 0L
                        // Once translated text is visible it becomes a committed speech work item.
                        // Do not cancel that TTS because the original capture-time SLA has passed;
                        // the synthesis provider has its own bounded primary-to-fallback recovery.
                        collectSpeechPcmWithTimeout(
                            speech = speech,
                            text = work.translatedText,
                            languageTag = target.languageTag,
                            firstFrameTimeoutMillis = firstAudioTimeoutMillis,
                            frameIdleTimeoutMillis = synthesisFrameIdleTimeoutMillis,
                            totalTimeoutMillis = synthesisTotalTimeoutMillis,
                            currentElapsedRealtimeNanos = currentElapsedRealtimeNanos,
                        ) { frame ->
                            val frameIsAudible = pcmAccumulator.add(frame)
                            if (!firstAudibleFrameReported && frameIsAudible) {
                                firstAudibleFrameReported = true
                                val firstAudioElapsedMillis =
                                    synthesisMark.elapsedNow().inWholeMilliseconds
                                observer.onSynthesisAudioStarted(
                                    utterance = utterance,
                                    target = target,
                                    elapsedMillis = firstAudioElapsedMillis,
                                )
                                mutableHealth.updateChannel(target.channelId) {
                                    it.copy(lastFirstAudioElapsedMillis = firstAudioElapsedMillis)
                                }
                            }
                            if (shouldPublishAudio()) {
                                val correlatedFrame = if (frame.utteranceSequence == null) {
                                    frame.copy(utteranceSequence = utterance.sequence)
                                } else {
                                    frame
                                }
                                when (
                                    activeStreamSession.tryPublish(
                                        target.channelId,
                                        correlatedFrame,
                                    ).status
                                ) {
                                    StreamPublishStatus.PUBLISHED -> publishedFramesForWork += 1
                                    StreamPublishStatus.STALE_SESSION ->
                                        throw StreamSessionSupersededException()
                                    StreamPublishStatus.UNKNOWN_CHANNEL -> error(
                                        "Translation channel ${target.channelId} is missing from " +
                                            "stream session ${activeStreamSession.generation}",
                                    )
                                }
                            }
                            frameIsAudible
                        }
                        val pcm = pcmAccumulator.stats()
                        check(
                            pcm.isNonSilent(
                                minimumRms = MIN_AUDIBLE_PCM_RMS,
                                minimumPeak = MIN_AUDIBLE_PCM_PEAK,
                            ),
                        ) {
                            "번역문은 생성됐지만 TTS가 비무음 PCM을 만들지 못했습니다."
                        }
                        val synthesisElapsedMillis = synthesisMark.elapsedNow().inWholeMilliseconds
                        observer.onSynthesisAudioCompleted(
                            utterance = utterance,
                            target = target,
                            elapsedMillis = synthesisElapsedMillis,
                            pcm = pcm,
                        )
                        mutableHealth.updateChannel(target.channelId) {
                            val recovered = it.lastSynthesisError != null
                            it.copy(
                                lastSynthesizedSequence = utterance.sequence,
                                lastPublishedSequence = if (publishedFramesForWork > 0L) {
                                    utterance.sequence
                                } else {
                                    it.lastPublishedSequence
                                },
                                publishedFrameCount =
                                    it.publishedFrameCount + publishedFramesForWork,
                                lastCompletedSequence = if (publishedFramesForWork > 0L) {
                                    utterance.sequence
                                } else {
                                    it.lastCompletedSequence
                                },
                                synthesisRecoveries = it.synthesisRecoveries +
                                    if (recovered) 1 else 0,
                                synthesisState = TranslationWorkerState.IDLE,
                                lastSynthesisElapsedMillis = synthesisElapsedMillis,
                                lastSynthesisError = null,
                                lastError = it.lastTranslationError,
                                lastSynthesisPcm = pcm,
                            )
                        }
                    } catch (timeout: TimeoutCancellationException) {
                        mutableHealth.updateChannel(target.channelId) {
                            it.copy(
                                droppedUtterances = it.droppedUtterances + 1,
                                synthesisFailures = it.synthesisFailures + 1,
                                synthesisState = TranslationWorkerState.DEGRADED,
                                lastSynthesisError = SYNTHESIS_TIMEOUT_MESSAGE,
                                lastError = SYNTHESIS_TIMEOUT_MESSAGE,
                            )
                        }
                    } catch (cancelled: CancellationException) {
                        // A synthesis provider may cancel only its current request (for example
                        // after an isolated worker reconnect). Keep this language FIFO alive for
                        // the next committed translation unless the owning session itself ended.
                        currentCoroutineContext().ensureActive()
                        val message = cancelled.message ?: SYNTHESIS_CANCELLED_MESSAGE
                        mutableHealth.updateChannel(target.channelId) {
                            it.copy(
                                droppedUtterances = it.droppedUtterances + 1,
                                synthesisFailures = it.synthesisFailures + 1,
                                synthesisState = TranslationWorkerState.DEGRADED,
                                lastSynthesisError = message,
                                lastError = message,
                            )
                        }
                    } catch (error: Throwable) {
                        val message = error.message ?: error::class.simpleName
                        mutableHealth.updateChannel(target.channelId) {
                            it.copy(
                                droppedUtterances = it.droppedUtterances + 1,
                                synthesisFailures = it.synthesisFailures + 1,
                                synthesisState = TranslationWorkerState.DEGRADED,
                                lastSynthesisError = message,
                                lastError = message,
                            )
                        }
                    } finally {
                        publicationLease?.close()
                    }
                }
            }
        }

        val sourceJob = isolatedScope.launch {
            val processedFinalSequences = LinkedHashSet<Long>()
            try {
                utterances.collect { utterance ->
                    observer.onSourceRecognized(utterance)
                    if (utterance.isRetracted || !utterance.isFinal) return@collect
                    if (!processedFinalSequences.add(utterance.sequence)) return@collect
                    while (processedFinalSequences.size > MAX_DEDUPLICATED_SEQUENCES) {
                        processedFinalSequences.remove(processedFinalSequences.first())
                    }
                    if (hasExpiredFirstAudioDeadline(utterance)) {
                        targets.forEach { target ->
                            mutableHealth.updateChannel(target.channelId) {
                                it.copy(
                                    lastError = LATE_UTTERANCE_RECOVERY_MESSAGE,
                                )
                            }
                        }
                    }
                    // Independent dispatch workers may wait behind their own language without
                    // blocking this source or any healthy channel. The reserve preserves normal
                    // bursts; only a genuinely stalled language reaches its explicit drop policy.
                    targets.forEach { target ->
                        if (sameLanguage(utterance.sourceLanguageTag, target.languageTag)) {
                            mutableHealth.updateChannel(target.channelId) {
                                it.copy(
                                    lastAcceptedSequence = utterance.sequence,
                                    droppedUtterances = it.droppedUtterances + 1,
                                    translationFailures = it.translationFailures + 1,
                                    translationState = TranslationWorkerState.DEGRADED,
                                    lastTranslationError = SOURCE_EQUALS_TARGET_MESSAGE,
                                    lastError = SOURCE_EQUALS_TARGET_MESSAGE,
                                )
                            }
                        } else {
                            mutableHealth.updateChannel(target.channelId) {
                                it.copy(lastAcceptedSequence = utterance.sequence)
                            }
                            requireNotNull(sourceDispatchQueues[target]).trySend(utterance)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableHealth.update { channels ->
                    channels.map { channel ->
                        channel.copy(lastError = error.message ?: "음성 인식이 중단되었습니다.")
                    }
                }
            } finally {
                acceptingSource.set(false)
                sourceDispatchQueues.values.forEach { it.close() }
            }
        }

        val queues = sourceDispatchQueues.values + translationQueues.values + speechQueues.values
        isolationJob.invokeOnCompletion {
            acceptingSource.set(false)
            queues.forEach { it.cancel() }
        }
        return RunningTranslationPipeline(
            health = mutableHealth.asStateFlow(),
            streamSession = activeStreamSession,
            ownsStreamSession = ownsStreamSession,
            isolationJob = isolationJob,
            sourceJob = sourceJob,
            workers = sourceDispatchWorkers + translationWorkers + speechWorkers,
            queues = queues,
            stopAcceptingSource = { acceptingSource.set(false) },
        )
    }

    private fun MutableStateFlow<List<TranslationChannelHealth>>.updateChannel(
        channelId: String,
        transform: (TranslationChannelHealth) -> TranslationChannelHealth,
    ) {
        update { channels ->
            channels.map { if (it.channelId == channelId) transform(it) else it }
        }
    }

    private fun hasExpiredFirstAudioDeadline(utterance: RecognizedUtterance): Boolean {
        val deadline = utterance.firstAudioDeadlineElapsedRealtimeNanos ?: return false
        return deadline - currentElapsedRealtimeNanos() <= 0L
    }

    private companion object {
        const val MAX_DEDUPLICATED_SEQUENCES = 256
    }
}

private data class TranslatedSpeechWork(
    val utterance: RecognizedUtterance,
    val translatedText: String,
)

private const val LATE_UTTERANCE_RECOVERY_MESSAGE =
    "설정된 첫 통역 음성 목표를 넘었지만 확정 문장을 유지해 복구 처리 중입니다."

private const val SOURCE_EQUALS_TARGET_MESSAGE =
    "입력 언어와 같은 출력 언어는 통역 채널로 사용할 수 없습니다."

private const val BACKLOG_DROP_MESSAGE =
    "통역 적체로 오래된 문장 1개를 건너뛰고 최신 안내를 계속합니다."

private const val SPEECH_BACKLOG_DROP_MESSAGE =
    "음성 적체로 오래된 통역 음성 1개를 건너뛰고 최신 안내를 계속합니다. 자막은 보존됩니다."

private const val TRANSLATION_TIMEOUT_MESSAGE =
    "번역 처리 시간이 초과됐습니다. 다음 문장을 계속합니다."

private const val SYNTHESIS_TIMEOUT_MESSAGE =
    "통역 처리 기한을 넘어 다음 문장으로 진행했습니다."

private const val TRANSLATION_CANCELLED_MESSAGE =
    "번역 제공자가 현재 문장을 취소했습니다. 다음 문장에서 자동 복구합니다."

private const val SYNTHESIS_CANCELLED_MESSAGE =
    "음성 제공자가 현재 문장을 취소했습니다. 다음 문장에서 자동 복구합니다."

private sealed interface SpeechStreamEvent {
    data object ExecutionWaitStarted : SpeechStreamEvent
    data object ExecutionStarted : SpeechStreamEvent
    data class Frame(val value: PcmAudioFrame) : SpeechStreamEvent
}

private suspend fun collectSpeechPcmWithTimeout(
    speech: SpeechSynthesisEngine,
    text: String,
    languageTag: String,
    firstFrameTimeoutMillis: Long,
    frameIdleTimeoutMillis: Long,
    totalTimeoutMillis: Long,
    currentElapsedRealtimeNanos: () -> Long,
    consume: suspend (PcmAudioFrame) -> Boolean,
) {
    if (speech !is ExecutionAwareSpeechSynthesisEngine) {
        collectPcmWithTimeout(
            frames = speech.synthesize(text, languageTag),
            firstFrameTimeoutMillis = firstFrameTimeoutMillis,
            frameIdleTimeoutMillis = frameIdleTimeoutMillis,
            totalTimeoutMillis = totalTimeoutMillis,
            consume = consume,
        )
        return
    }
    collectExecutionAwarePcmWithTimeout(
        speech = speech,
        text = text,
        languageTag = languageTag,
        firstFrameTimeoutMillis = firstFrameTimeoutMillis,
        frameIdleTimeoutMillis = frameIdleTimeoutMillis,
        totalTimeoutMillis = totalTimeoutMillis,
        currentElapsedRealtimeNanos = currentElapsedRealtimeNanos,
        consume = consume,
    )
}

/**
 * Preserves the original first/idle/total active-work budgets while excluding only explicitly
 * reported, individually bounded provider-admission intervals. The reported count is also bounded
 * so a broken engine cannot manufacture an unlimited series of paused intervals.
 */
private suspend fun collectExecutionAwarePcmWithTimeout(
    speech: ExecutionAwareSpeechSynthesisEngine,
    text: String,
    languageTag: String,
    firstFrameTimeoutMillis: Long,
    frameIdleTimeoutMillis: Long,
    totalTimeoutMillis: Long,
    currentElapsedRealtimeNanos: () -> Long,
    consume: suspend (PcmAudioFrame) -> Boolean,
) = supervisorScope {
    val maximumWaitCount = speech.maximumExecutionStartWaitCount(text, languageTag)
    require(maximumWaitCount in 1..100) { "Invalid speech execution wait count" }
    require(speech.maximumExecutionStartWaitMillis > 0L)

    // Control callbacks are synchronous/non-suspending, so retain their strict order in a channel.
    // PCM still has a one-frame permit and callback count is capped, making this channel logically
    // bounded to one PCM frame plus at most two control events per declared admission.
    val events = Channel<SpeechStreamEvent>(capacity = Channel.UNLIMITED)
    val pcmSlot = kotlinx.coroutines.sync.Semaphore(1)
    val controlEventCount = AtomicInteger(0)
    fun reportControlEvent(event: SpeechStreamEvent) {
        if (controlEventCount.incrementAndGet() > maximumWaitCount * 2) {
            events.close(IllegalStateException("Speech synthesis emitted excess execution events"))
            return
        }
        events.trySend(event)
    }
    val producer = launch {
        try {
            speech.synthesize(
                text = text,
                languageTag = languageTag,
                onExecutionWaitStarted = {
                    reportControlEvent(SpeechStreamEvent.ExecutionWaitStarted)
                },
                onExecutionStarted = {
                    reportControlEvent(SpeechStreamEvent.ExecutionStarted)
                },
            ).collect { frame ->
                var ownsPcmSlot = false
                try {
                    pcmSlot.acquire()
                    ownsPcmSlot = true
                    events.send(SpeechStreamEvent.Frame(frame))
                    ownsPcmSlot = false
                } finally {
                    if (ownsPcmSlot) pcmSlot.release()
                }
            }
            events.close()
        } catch (error: Throwable) {
            events.close(error)
        }
    }

    var firstAudibleFrameReceived = false
    var phaseRemainingNanos = firstFrameTimeoutMillis.toTimeoutNanos()
    var totalRemainingNanos = totalTimeoutMillis.toTimeoutNanos()
    var activeIntervalStartedNanos = currentElapsedRealtimeNanos()
    var waitingForExecution = false
    var waitCount = 0

    suspend fun receiveWithin(timeoutNanos: Long): SpeechStreamEvent? {
        val timeoutMillis = timeoutNanos.toCeilingTimeoutMillis()
        val result = withTimeout(timeoutMillis) { events.receiveCatching() }
        if (result.isClosed) {
            result.exceptionOrNull()?.let { throw it }
            return null
        }
        return result.getOrThrow()
    }

    suspend fun consumeFrame(frame: PcmAudioFrame) {
        val audible = consume(frame)
        if (!firstAudibleFrameReceived && audible) {
            firstAudibleFrameReceived = true
            phaseRemainingNanos = frameIdleTimeoutMillis.toTimeoutNanos()
        } else if (firstAudibleFrameReceived) {
            phaseRemainingNanos = frameIdleTimeoutMillis.toTimeoutNanos()
        }
    }

    try {
        while (true) {
            if (waitingForExecution) {
                when (
                    val event = receiveWithin(
                        speech.maximumExecutionStartWaitMillis.toTimeoutNanos(),
                    ) ?: break
                ) {
                    SpeechStreamEvent.ExecutionWaitStarted ->
                        error("Speech execution wait intervals cannot overlap")
                    SpeechStreamEvent.ExecutionStarted -> {
                        waitingForExecution = false
                        activeIntervalStartedNanos = currentElapsedRealtimeNanos()
                    }
                    is SpeechStreamEvent.Frame -> {
                        // A frame itself proves execution even if a vendor omitted its start
                        // callback. Do not charge the preceding, explicitly bounded queue interval.
                        waitingForExecution = false
                        activeIntervalStartedNanos = currentElapsedRealtimeNanos()
                        try {
                            consumeFrame(event.value)
                        } finally {
                            pcmSlot.release()
                        }
                    }
                }
                continue
            }

            val event = receiveWithin(minOf(phaseRemainingNanos, totalRemainingNanos)) ?: break
            val now = currentElapsedRealtimeNanos()
            val elapsed = (now - activeIntervalStartedNanos).coerceAtLeast(0L)
            phaseRemainingNanos = (phaseRemainingNanos - elapsed).coerceAtLeast(0L)
            totalRemainingNanos = (totalRemainingNanos - elapsed).coerceAtLeast(0L)
            check(phaseRemainingNanos > 0L && totalRemainingNanos > 0L) {
                "Speech synthesis active-work deadline exceeded"
            }
            activeIntervalStartedNanos = now

            when (event) {
                SpeechStreamEvent.ExecutionWaitStarted -> {
                    waitCount += 1
                    check(waitCount <= maximumWaitCount) {
                        "Speech synthesis exceeded its bounded execution wait count"
                    }
                    waitingForExecution = true
                }
                SpeechStreamEvent.ExecutionStarted -> Unit
                is SpeechStreamEvent.Frame -> try {
                    consumeFrame(event.value)
                } finally {
                    pcmSlot.release()
                }
            }
        }
    } finally {
        producer.cancel()
        events.cancel()
    }
}

private fun Long.toTimeoutNanos(): Long =
    if (this > Long.MAX_VALUE / 1_000_000L) Long.MAX_VALUE else this * 1_000_000L

private fun Long.toCeilingTimeoutMillis(): Long =
    (this / 1_000_000L + if (this % 1_000_000L == 0L) 0L else 1L).coerceAtLeast(1L)

private suspend fun collectPcmWithTimeout(
    frames: Flow<PcmAudioFrame>,
    firstFrameTimeoutMillis: Long,
    frameIdleTimeoutMillis: Long,
    totalTimeoutMillis: Long,
    consume: suspend (PcmAudioFrame) -> Boolean,
) = withTimeout(totalTimeoutMillis) {
    supervisorScope {
    val channel = Channel<PcmAudioFrame>(capacity = 1)
    val producer = launch {
        try {
            frames.collect(channel::send)
            channel.close()
        } catch (error: Throwable) {
            channel.close(error)
        }
    }
    try {
        var streamClosed = false
        // The first-audio budget is absolute. Receiving leading silent frames must not restart
        // the clock, otherwise a broken native voice that emits silence forever can occupy this
        // language FIFO forever and replay an obsolete backlog after a late recovery.
        withTimeout(firstFrameTimeoutMillis) {
            while (!streamClosed) {
                val result = channel.receiveCatching()
                if (result.isClosed) {
                    result.exceptionOrNull()?.let { throw it }
                    streamClosed = true
                    return@withTimeout
                }
                if (consume(result.getOrThrow())) return@withTimeout
            }
        }
        while (!streamClosed) {
            val result = withTimeout(frameIdleTimeoutMillis) { channel.receiveCatching() }
            if (result.isClosed) {
                result.exceptionOrNull()?.let { throw it }
                break
            }
            consume(result.getOrThrow())
        }
    } finally {
        producer.cancel()
        channel.close()
    }
    }
}

private class PcmS16LeAccumulator {
    private var frameCount = 0L
    private var byteCount = 0L
    private var sampleCount = 0L
    private var nonZeroSampleCount = 0L
    private var sumSquares = 0.0
    private var sum = 0.0
    private var peak = 0
    private var clippedSampleCount = 0L
    private var currentZeroRunSamples = 0L
    private var longestZeroRunSamples = 0L
    private var previousSample: Int? = null
    private var maximumAdjacentJump = 0
    private var comparableSignPairs = 0L
    private var zeroCrossingCount = 0L

    /** Records [frame] and returns true only when it contains an audible PCM16 sample. */
    fun add(frame: PcmAudioFrame): Boolean {
        frameCount += 1
        byteCount += frame.bytes.size
        var frameSampleCount = 0L
        var frameSumSquares = 0.0
        var framePeak = 0
        var offset = 0
        while (offset < frame.bytes.size) {
            val sample = (
                (frame.bytes[offset + 1].toInt() shl 8) or
                    (frame.bytes[offset].toInt() and 0xff)
                ).toShort().toInt()
            val magnitude = if (sample == Short.MIN_VALUE.toInt()) 32_768 else abs(sample)
            sampleCount += 1
            frameSampleCount += 1
            if (sample != 0) {
                nonZeroSampleCount += 1
                currentZeroRunSamples = 0L
            } else {
                currentZeroRunSamples += 1L
                if (currentZeroRunSamples > longestZeroRunSamples) {
                    longestZeroRunSamples = currentZeroRunSamples
                }
            }
            if (magnitude >= 32_700) clippedSampleCount += 1L
            if (magnitude > peak) peak = magnitude
            if (magnitude > framePeak) framePeak = magnitude
            previousSample?.let { previous ->
                val jump = kotlin.math.abs(sample - previous)
                if (jump > maximumAdjacentJump) maximumAdjacentJump = jump
                if (previous != 0 && sample != 0) {
                    comparableSignPairs += 1L
                    if ((previous < 0) != (sample < 0)) zeroCrossingCount += 1L
                }
            }
            previousSample = sample
            sumSquares += sample.toDouble() * sample.toDouble()
            sum += sample.toDouble()
            frameSumSquares += sample.toDouble() * sample.toDouble()
            offset += Short.SIZE_BYTES
        }
        if (frameSampleCount == 0L) return false
        val frameRms = (sqrt(frameSumSquares / frameSampleCount) / 32_768.0).toFloat()
        val normalizedFramePeak = framePeak / 32_768f
        return frameRms > MIN_AUDIBLE_PCM_RMS || normalizedFramePeak > MIN_AUDIBLE_PCM_PEAK
    }

    fun stats(): SynthesizedPcmStats = SynthesizedPcmStats(
        frameCount = frameCount,
        byteCount = byteCount,
        sampleCount = sampleCount,
        nonZeroSampleCount = nonZeroSampleCount,
        rms = if (sampleCount == 0L) {
            0f
        } else {
            (sqrt(sumSquares / sampleCount) / 32_768.0).toFloat()
        },
        peak = peak / 32_768f,
        dcOffset = if (sampleCount == 0L) 0f else (sum / sampleCount / 32_768.0).toFloat(),
        clippingRatio = if (sampleCount == 0L) {
            0f
        } else {
            clippedSampleCount.toFloat() / sampleCount
        },
        longestZeroRunSamples = longestZeroRunSamples,
        maximumBoundaryJump = maximumAdjacentJump / 32_768f,
        zeroCrossingRatio = if (comparableSignPairs == 0L) {
            0f
        } else {
            zeroCrossingCount.toFloat() / comparableSignPairs
        },
    )
}

class RunningTranslationPipeline internal constructor(
    val health: StateFlow<List<TranslationChannelHealth>>,
    val streamSession: StreamSession,
    private val ownsStreamSession: Boolean,
    private val isolationJob: Job,
    private val sourceJob: Job,
    private val workers: List<Job>,
    private val queues: Collection<Channel<*>>,
    private val stopAcceptingSource: () -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Explicit stop discards pending work; normal source completion still drains it.
        // Mark intentional shutdown before cancel invokes onUndeliveredElement callbacks.
        stopAcceptingSource()
        if (ownsStreamSession) streamSession.close()
        sourceJob.cancel()
        queues.forEach { it.cancel() }
        workers.forEach(Job::cancel)
        isolationJob.cancel()
    }
}

private fun normalizedLanguageTag(languageTag: String): String = languageTag.lowercase()

/**
 * Treats a language-only tag as equivalent to any explicit locale of that language, while
 * preserving meaningful locale-to-locale targets such as zh-CN -> zh-TW.
 */
private fun sameLanguage(first: String, second: String): Boolean {
    val normalizedFirst = normalizedLanguageTag(first).split('-')
    val normalizedSecond = normalizedLanguageTag(second).split('-')
    return normalizedFirst == normalizedSecond ||
        (normalizedFirst.first() == normalizedSecond.first() &&
            (normalizedFirst.size == 1 || normalizedSecond.size == 1))
}
