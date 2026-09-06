package app.guidecast.provider.moonshine.tts

import android.content.Context
import android.os.IBinder
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import ai.moonshine.voice.ModelCache
import ai.moonshine.voice.ModelSpec
import app.guidecast.core.stream.PcmAudioFrame
import app.guidecast.core.translation.SpeechSynthesisEngine
import app.guidecast.core.translation.SpeechSynthesisEngineProvider
import app.guidecast.core.stream.MAX_SIMULTANEOUS_TRANSLATED_CHANNELS
import app.guidecast.core.translation.currentNativeColdLoadTicket
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID
import kotlin.math.floor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal const val MAX_MOONSHINE_BROADCAST_LANGUAGES =
    MAX_SIMULTANEOUS_TRANSLATED_CHANNELS

enum class MoonshineTtsReadiness {
    NOT_INSTALLED,
    DOWNLOADING,
    READY,
    FAILED,
}

data class MoonshineTtsStatus(
    val languageTag: String,
    val displayName: String,
    val voiceId: String,
    val readiness: MoonshineTtsReadiness = MoonshineTtsReadiness.NOT_INSTALLED,
    val progress: Float = 0f,
    val currentFile: String? = null,
    val errorMessage: String? = null,
)

/**
 * Official Moonshine Voice Android TTS used by google-gemma/gemma-translator's audio stack.
 *
 * Moonshine's native runtime retains a very large allocator arena after TextToSpeech.close().
 * Model checks and utterances run in one private process per supported output language. A native
 * fatal therefore falls back only that language while the microphone, server, and sibling voice
 * processes remain alive. Components are unbound normally; the app never kills its own process.
 */
class MoonshineSpeechSynthesisProvider(
    context: Context,
    private val outputSampleRateHz: Int = OUTPUT_SAMPLE_RATE_HZ,
) : SpeechSynthesisEngineProvider, Closeable {
    private val applicationContext = context.applicationContext
    /** Namespaces Binder request ids across providers and main-process restarts. */
    private val clientId = UUID.randomUUID().toString()
    private val engines = ConcurrentHashMap<String, MoonshineSpeechSynthesisEngine>()
    private val nextRequestId = AtomicLong(1L)
    private val nextClientOperationId = AtomicLong(1L)
    private val clientOperations = MoonshineTtsClientOperationRegistry()
    private val languageSelectionMutex = Mutex()
    /** Binder identity that actually completed this language's native engine warm-up. */
    private val warmedWorkerBinders = ConcurrentHashMap<String, IBinder>()
    /**
     * A missed close acknowledgement is tracked by exact language/process generation. A late
     * shutdown callback or Binder death releases only that generation, so a transient long
     * utterance cannot permanently downgrade every future broadcast until app restart.
     */
    private val nativeRetirements = MoonshineTtsNativeRetirementTracker()
    private val workerConnections = MoonshineTtsVoiceCatalog.voices.keys.associateWith { languageTag ->
        MoonshineTtsWorkerConnection(
            applicationContext = applicationContext,
            languageTag = languageTag,
            serviceClass = MoonshineTtsWorkerServices.forLanguage(languageTag),
        )
    }
    @Volatile private var cacheSchemaState = MoonshineTtsCacheSchema.ensureCurrent(applicationContext)
    private val mutableStatuses = MutableStateFlow(
        MoonshineTtsVoiceCatalog.voices.map { (languageTag, spec) ->
            spec.toStatus(
                languageTag = languageTag,
                ready = cacheSchemaState.ready && isVoiceModelPresent(spec),
            )
        },
    )
    val statuses: StateFlow<List<MoonshineTtsStatus>> = mutableStatuses.asStateFlow()

    init {
        require(outputSampleRateHz == OUTPUT_SAMPLE_RATE_HZ) {
            "Moonshine streaming output is fixed at $OUTPUT_SAMPLE_RATE_HZ Hz"
        }
        when {
            cacheSchemaState.clearedDirectoryCount > 0 -> Log.i(
                LOG_TAG,
                "Removed ${cacheSchemaState.clearedDirectoryCount} legacy TTS cache directories",
            )

            !cacheSchemaState.ready -> Log.e(
                LOG_TAG,
                "TTS cache schema migration failed: ${cacheSchemaState.errorMessage}",
            )
        }
    }

    override fun engineFor(targetLanguageTag: String): SpeechSynthesisEngine =
        engine(targetLanguageTag)

    /**
     * Backward-compatible complete preparation. BroadcastService uses the two explicit phases so
     * only [warm] is enclosed by process-wide native cold-load admission.
     */
    suspend fun prepare(languageTags: Collection<String>) {
        prepareLanguages(languageTags, "Moonshine TTS") { languageTag ->
            // Keep direct multi-language callers failure-isolated: one asset failure must not
            // prevent verified sibling voices from completing their own warm-up.
            prepareAssetsLanguage(languageTag)
            warmLanguage(languageTag)
        }
    }

    /** Downloads and integrity-checks voices without loading a native TTS engine. */
    suspend fun prepareAssets(languageTags: Collection<String>) {
        prepareLanguages(languageTags, "Moonshine TTS asset") { languageTag ->
            prepareAssetsLanguage(languageTag)
        }
    }

    /** Loads the already-verified native voice engine in its language-isolated process. */
    suspend fun warm(languageTags: Collection<String>) {
        prepareLanguages(languageTags, "Moonshine TTS warm-up") { languageTag ->
            warmLanguage(languageTag)
        }
    }

    private suspend fun prepareLanguages(
        languageTags: Collection<String>,
        stageLabel: String,
        operation: suspend (String) -> Unit,
    ) {
        require(languageTags.size in 1..MAX_MOONSHINE_BROADCAST_LANGUAGES) {
            "Prepare one to $MAX_MOONSHINE_BROADCAST_LANGUAGES Moonshine TTS languages"
        }
        val failures = supervisorScope {
            languageTags.distinct().map { languageTag ->
                async {
                    try {
                        operation(languageTag)
                        null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        languageTag to error
                    }
                }
            }.awaitAll().filterNotNull()
        }
        if (failures.isNotEmpty()) throw moonshinePreparationFailure(stageLabel, failures)
    }

    fun isReady(languageTag: String): Boolean =
        statuses.value.firstOrNull { it.languageTag == languageTag }?.readiness ==
            MoonshineTtsReadiness.READY

    /** True only when this exact live Binder completed native engine warm-up. */
    fun hasActiveWorker(languageTag: String): Boolean =
        warmedWorkerBinders[languageTag]?.let { binder ->
            workerConnections[languageTag]?.hasActiveConnection(binder) == true
        } ?: false

    /**
     * Retires voices removed by a replacement broadcast before it warms another language.
     * Publishing the selected set and cancelling removed-language client work is one transaction,
     * so a late child from the old session cannot bind an unselected extra worker again.
     */
    suspend fun reconcileLanguages(languageTags: Set<String>): Map<String, String> =
        languageSelectionMutex.withLock {
            val selected = languageTags.toSet()
            require(selected.size <= MAX_MOONSHINE_BROADCAST_LANGUAGES) {
                "Reconcile zero to $MAX_MOONSHINE_BROADCAST_LANGUAGES Moonshine TTS languages"
            }
            require(selected.all { it in workerConnections }) {
                "Moonshine TTS selection contains an unsupported language"
            }
            withContext(NonCancellable) {
                val previousSelection = clientOperations.selectedLanguagesForTest()
                    ?: workerConnections.filterValues { it.hasActiveConnection() }.keys
                val retained = previousSelection.intersect(selected)
                val removedConnections = workerConnections.filter { (languageTag, connection) ->
                    languageTag !in selected && connection.hasActiveConnection()
                }
                // Publish a provisional boundary first. Newly added voices remain closed until
                // every removed runtime acknowledges native close, preventing an A5 -> B5 swap
                // from briefly creating six resident voice processes.
                val provisionalSelection = if (removedConnections.isEmpty()) selected else retained
                clientOperations.selectLanguagesAndCancelUnselected(
                    languageTags = provisionalSelection,
                    timeoutMillis = CLIENT_LANGUAGE_RETIRE_DRAIN_MILLIS,
                )
                val failures = supervisorScope {
                    removedConnections.map { (languageTag, connection) ->
                        async {
                            val retired = connection.disconnectAndAwaitNativeClose(
                                timeoutMillis = WORKER_LANGUAGE_RETIRE_TIMEOUT_MILLIS,
                                retirementTracker = nativeRetirements,
                            )
                            languageTag to retired
                        }
                    }.awaitAll()
                }.filterNot { (_, retired) -> retired }
                    .associate { (languageTag, _) ->
                        languageTag to "선택 해제 음성 작업 공간 정리 시간이 초과됐습니다."
                    }.toMutableMap()

                // The native close acknowledgement may arrive after the first short client-job
                // drain. Re-snapshot now so an operation that did finish inside the close window
                // cannot permanently downgrade this provider on stale evidence.
                val postRetirementSelection =
                    clientOperations.selectLanguagesAndCancelUnselected(
                        languageTags = provisionalSelection,
                        timeoutMillis = CLIENT_LANGUAGE_RETIRE_DRAIN_MILLIS,
                    )
                postRetirementSelection.pendingLanguageTags.forEach { languageTag ->
                    failures.putIfAbsent(
                        languageTag,
                        "선택 해제 음성 작업이 종료 기한을 넘었습니다.",
                    )
                }
                val nativeSelection = planMoonshineTtsNativeSelection(
                    requestedLanguageTags = selected,
                    retainedLanguageTags = retained,
                    unresolvedGenerations = nativeRetirements.snapshot(),
                    maximumResidentGenerations = MAX_MOONSHINE_BROADCAST_LANGUAGES,
                )
                clientOperations.selectLanguagesAndCancelUnselected(
                    languageTags = nativeSelection.admittedLanguageTags,
                    timeoutMillis = CLIENT_LANGUAGE_RETIRE_DRAIN_MILLIS,
                )
                nativeSelection.blockedLanguageTags.forEach { languageTag ->
                    failures.putIfAbsent(
                        languageTag,
                        "이전 음성 작업 공간의 native 종료를 아직 확인하지 못해 " +
                            "이 채널은 Galaxy 대체 음성을 사용합니다.",
                    )
                }
                (workerConnections.keys - selected).forEach { languageTag ->
                    engines.remove(languageTag)
                    warmedWorkerBinders.remove(languageTag)
                }
                failures
            }
        }

    private suspend fun prepareAssetsLanguage(languageTag: String) {
        val schema = ensureCacheSchemaCurrent()
        check(schema.ready) {
            "Moonshine 음성 캐시를 안전하게 갱신하지 못했습니다: " +
                (schema.errorMessage ?: "알 수 없는 오류")
        }
        val spec = MoonshineTtsVoiceCatalog.requireVoice(languageTag)
        val baseStatus = spec.toStatus(languageTag)
        val previousStatus = statuses.value.firstOrNull { it.languageTag == languageTag }
            ?: baseStatus
        updateStatus(
            baseStatus.copy(
                readiness = MoonshineTtsReadiness.DOWNLOADING,
                currentFile = "Moonshine ${spec.voiceId}",
            ),
        )
        try {
            // Asset download and integrity verification deliberately do not create TextToSpeech.
            // First-install network time therefore never occupies the aggregate native-load lane.
            withWorker(languageTag) { worker ->
                prepareAssetsRemote(worker, languageTag) {
                        currentFile, fileIndex, fileCount, bytesRead, totalBytes ->
                    val progress = downloadProgress(
                        fileIndex = fileIndex,
                        fileCount = fileCount,
                        bytesRead = bytesRead,
                        totalBytes = totalBytes,
                    )
                    updateStatus(
                        baseStatus.copy(
                            readiness = MoonshineTtsReadiness.DOWNLOADING,
                            progress = progress,
                            currentFile = currentFile,
                        ),
                    )
                }
            }
            updateStatus(baseStatus.copy(readiness = MoonshineTtsReadiness.READY, progress = 1f))
        } catch (cancelled: CancellationException) {
            // Session replacement and user stop are not model failures. Restore the exact status
            // observed before this attempt so a cancelled download cannot become a false FAILED.
            updateStatus(previousStatus)
            throw cancelled
        } catch (error: Throwable) {
            val detail = error.message ?: error.javaClass.simpleName
            updateStatus(
                baseStatus.copy(
                    readiness = MoonshineTtsReadiness.FAILED,
                    errorMessage = detail,
                ),
            )
            throw IllegalStateException(
                "${spec.displayName} Moonshine 음성 자산을 준비하지 못했습니다: $detail",
                error,
            )
        }
    }

    private suspend fun warmLanguage(languageTag: String) {
        val spec = MoonshineTtsVoiceCatalog.requireVoice(languageTag)
        val baseStatus = spec.toStatus(languageTag)
        val previousStatus = statuses.value.firstOrNull { it.languageTag == languageTag }
            ?: baseStatus
        val voiceModelPresent = isVoiceModelPresent(spec)
        check(isVoiceModelPreparedForWarmup(previousStatus.readiness, voiceModelPresent)) {
            "${spec.displayName} Moonshine 음성 자산을 먼저 준비하세요."
        }
        try {
            withWorker(languageTag) { worker ->
                try {
                    warmRemote(worker, languageTag)
                    warmedWorkerBinders[languageTag] = worker.asBinder()
                    updateStatus(baseStatus.copy(readiness = MoonshineTtsReadiness.READY, progress = 1f))
                } catch (error: Throwable) {
                    warmedWorkerBinders.remove(languageTag, worker.asBinder())
                    throw error
                }
            }
        } catch (cancelled: CancellationException) {
            updateStatus(previousStatus)
            throw cancelled
        } catch (error: Throwable) {
            val detail = error.message ?: error.javaClass.simpleName
            updateStatus(
                baseStatus.copy(
                    readiness = MoonshineTtsReadiness.FAILED,
                    errorMessage = detail,
                ),
            )
            throw IllegalStateException(
                "${spec.displayName} Moonshine native 음성을 불러오지 못했습니다: $detail",
                error,
            )
        }
    }

    private fun engine(languageTag: String): MoonshineSpeechSynthesisEngine {
        val spec = MoonshineTtsVoiceCatalog.requireVoice(languageTag)
        return engines.getOrPut(languageTag) {
            MoonshineSpeechSynthesisEngine(
                languageTag = languageTag,
                spec = spec,
                synthesizeStreaming = { text, onFrame ->
                    withWorker(languageTag) { worker ->
                        synthesizeRemoteStreaming(worker, text, languageTag, onFrame)
                    }
                },
                currentStatus = {
                    statuses.value.firstOrNull { it.languageTag == languageTag }
                },
                onStatus = ::updateStatus,
            )
        }
    }

    private fun updateStatus(status: MoonshineTtsStatus) {
        mutableStatuses.update { current ->
            current.map { if (it.languageTag == status.languageTag) status else it }
        }
    }

    private fun ensureCacheSchemaCurrent(): MoonshineTtsCacheSchemaState {
        cacheSchemaState.takeIf { it.ready }?.let { return it }
        return MoonshineTtsCacheSchema.ensureCurrent(applicationContext).also {
            cacheSchemaState = it
        }
    }

    private suspend fun prepareAssetsRemote(
        worker: IGuideCastMoonshineTts,
        languageTag: String,
        onProgress: (String?, Int, Int, Long, Long) -> Unit,
    ) {
        val result = requestRemote(
            worker = worker,
            onProgress = onProgress,
            transferNativeColdLoadTicket = false,
            start = { requestId, callback ->
                worker.prepareAssets(clientId, requestId, languageTag, callback)
            },
        )
        check(result == MoonshineTtsClientResult.Prepared) {
            "Moonshine TTS asset preparation returned an unexpected result"
        }
    }

    private suspend fun warmRemote(
        worker: IGuideCastMoonshineTts,
        languageTag: String,
    ) {
        val result = requestRemote(
            worker = worker,
            start = { requestId, callback ->
                worker.prepare(clientId, requestId, languageTag, callback)
            },
        )
        check(result == MoonshineTtsClientResult.Prepared) {
            "Moonshine TTS native warm-up returned an unexpected result"
        }
    }

    private suspend fun synthesizeRemote(
        worker: IGuideCastMoonshineTts,
        text: String,
        languageTag: String,
    ): String {
        val result = requestRemote(
            worker = worker,
            start = { requestId, callback ->
                worker.synthesizeToFile(clientId, requestId, text, languageTag, callback)
            },
        )
        return (result as? MoonshineTtsClientResult.Synthesized)?.path
            ?: error("Moonshine TTS synthesis returned no output path")
    }

    private suspend fun synthesizeRemoteStreaming(
        worker: IGuideCastMoonshineTts,
        text: String,
        languageTag: String,
        onFrame: suspend (PcmAudioFrame) -> Unit,
    ) {
        val nativeTicket = currentNativeColdLoadTicket()
        val expectedRequestId = nextRequestId.getAndIncrement()
        check(expectedRequestId > 0L) { "Moonshine TTS request identifier overflow" }
        val frames = Channel<PcmAudioFrame>(capacity = STREAM_IPC_BUFFER_FRAMES)
        val streamCompleted = AtomicBoolean(false)
        val workerFinished = AtomicBoolean(false)
        val clientFinished = AtomicBoolean(false)
        val nativeSubmissionPlanned = AtomicBoolean(false)
        val nativeFinished = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>(null)
        val workerBinder = worker.asBinder()
        val deathLinked = AtomicBoolean(false)
        lateinit var deathRecipient: IBinder.DeathRecipient

        fun unlinkNativeDeathRecipient() {
            if (deathLinked.compareAndSet(true, false)) {
                runCatching { workerBinder.unlinkToDeath(deathRecipient, 0) }
            }
        }

        fun unlinkIfFullyTerminal() {
            if (
                moonshineRequestMayUnlinkDeathRecipient(
                    clientFinished = clientFinished.get(),
                    nativeSubmissionPlanned = nativeSubmissionPlanned.get(),
                    nativeFinished = nativeFinished.get(),
                )
            ) {
                unlinkNativeDeathRecipient()
            }
        }

        fun finishNativeAdmission() {
            if (nativeFinished.compareAndSet(false, true)) {
                nativeTicket?.completeNative()
                unlinkIfFullyTerminal()
            }
        }

        fun fail(error: Throwable) {
            if (failure.compareAndSet(null, error)) frames.close(error)
        }

        fun completeStreamIfFullyTerminal() {
            if (
                moonshineStreamCanComplete(
                    streamCompleted = streamCompleted.get(),
                    workerFinished = workerFinished.get(),
                    failurePresent = failure.get() != null,
                )
            ) {
                frames.close()
            }
        }

        val callback = object : IGuideCastMoonshineTtsCallback.Stub() {
            override fun onProgress(
                requestId: Long,
                currentFile: String?,
                fileIndex: Int,
                fileCount: Int,
                bytesRead: Long,
                totalBytes: Long,
            ) = Unit

            override fun onPrepared(requestId: Long) = Unit

            override fun onSynthesized(requestId: Long, path: String?) {
                if (requestId == expectedRequestId) {
                    fail(IllegalStateException("Moonshine TTS returned a file for a stream request"))
                }
            }

            override fun onPcmChunk(requestId: Long, pcm: ByteArray?) {
                if (requestId != expectedRequestId) return
                val frame = pcm?.toBinderPcmAudioFrame(
                    capturedAtElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
                    maximumFrameBytes = MAX_STREAM_IPC_FRAME_BYTES,
                )
                if (frame == null) {
                    fail(IllegalStateException("Moonshine TTS returned an invalid PCM stream frame"))
                    return
                }
                // The first structurally valid frame proves this Binder owns a warm engine for
                // future liveness checks. Native admission itself remains owned until onFinished
                // or Binder death, as required by NativeColdLoadTicket's terminal boundary.
                warmedWorkerBinders[languageTag] = workerBinder
                val sendResult = frames.trySend(frame)
                if (sendResult.isFailure && sendResult.exceptionOrNull() == null) {
                    fail(IllegalStateException("Moonshine TTS PCM stream exceeded its bounded buffer"))
                }
            }

            override fun onStreamCompleted(requestId: Long) {
                if (requestId == expectedRequestId) {
                    streamCompleted.set(true)
                    completeStreamIfFullyTerminal()
                }
            }

            override fun onError(requestId: Long, message: String?) {
                if (requestId == expectedRequestId) {
                    fail(
                        IllegalStateException(
                            message.orEmpty().ifBlank { "Moonshine TTS streaming request failed" },
                        ),
                    )
                }
            }

            override fun onFinished(requestId: Long) {
                if (requestId != expectedRequestId) return
                workerFinished.set(true)
                finishNativeAdmission()
                completeStreamIfFullyTerminal()
            }
        }
        deathRecipient = IBinder.DeathRecipient {
            warmedWorkerBinders.remove(languageTag, workerBinder)
            finishNativeAdmission()
            fail(RemoteException("Moonshine TTS worker died during PCM streaming"))
        }
        var receivedFrames = 0L
        try {
            workerBinder.linkToDeath(deathRecipient, 0)
            deathLinked.set(true)
            try {
                nativeSubmissionPlanned.set(true)
                nativeTicket?.transferToNative()
                worker.synthesizeStreaming(clientId, expectedRequestId, text, languageTag, callback)
            } catch (error: Throwable) {
                finishNativeAdmission()
                throw error
            }
            for (frame in frames) {
                receivedFrames++
                onFrame(frame)
            }
            failure.get()?.let { throw it }
            check(streamCompleted.get() && receivedFrames > 0L) {
                "Moonshine TTS streaming returned no PCM"
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (receivedFrames > 0L) {
                throw MoonshineTtsStreamInterruptedAfterOutputException(error)
            }
            throw error
        } finally {
            clientFinished.set(true)
            frames.cancel()
            if (!workerFinished.get()) runCatching { worker.cancel(clientId, expectedRequestId) }
            // Caller cancellation does not prove that the worker left native code. Preserve the
            // death link until onFinished or process death owns the transferred ticket.
            unlinkIfFullyTerminal()
        }
    }

    private suspend fun requestRemote(
        worker: IGuideCastMoonshineTts,
        onProgress: (String?, Int, Int, Long, Long) -> Unit = { _, _, _, _, _ -> },
        transferNativeColdLoadTicket: Boolean = true,
        start: (Long, IGuideCastMoonshineTtsCallback) -> Unit,
    ): MoonshineTtsClientResult {
        val nativeTicket = currentNativeColdLoadTicket().takeIf {
            transferNativeColdLoadTicket
        }
        val requestId = nextRequestId.getAndIncrement()
        check(requestId > 0L) { "Moonshine TTS request identifier overflow" }
        val workerBinder = worker.asBinder()
        val activeCallback = AtomicReference<MoonshineTtsClientCallback?>(null)
        val workerDied = AtomicBoolean(false)
        val clientFinished = AtomicBoolean(false)
        val nativeSubmissionPlanned = AtomicBoolean(false)
        val nativeFinished = AtomicBoolean(false)
        val deathLinked = AtomicBoolean(false)
        lateinit var deathRecipient: IBinder.DeathRecipient

        fun unlinkDeathRecipient() {
            if (deathLinked.compareAndSet(true, false)) {
                runCatching { workerBinder.unlinkToDeath(deathRecipient, 0) }
            }
        }

        fun unlinkIfFullyTerminal() {
            if (
                moonshineRequestMayUnlinkDeathRecipient(
                    clientFinished = clientFinished.get(),
                    nativeSubmissionPlanned = nativeSubmissionPlanned.get(),
                    nativeFinished = nativeFinished.get(),
                )
            ) {
                unlinkDeathRecipient()
            }
        }

        fun finishNativeAdmission() {
            if (nativeFinished.compareAndSet(false, true)) {
                nativeTicket?.completeNative()
                unlinkIfFullyTerminal()
            }
        }

        deathRecipient = IBinder.DeathRecipient {
            workerDied.set(true)
            finishNativeAdmission()
            activeCallback.get()?.failAfterWorkerDeath()
        }
        workerBinder.linkToDeath(deathRecipient, 0)
        deathLinked.set(true)
        return try {
            awaitMoonshineTtsRequest(
                start = { clientCallback ->
                    val lifecycleCallback = clientCallback.withNativeFinished(
                        onNativeFinished = ::finishNativeAdmission,
                    )
                    activeCallback.set(lifecycleCallback)
                    if (workerDied.get() || !workerBinder.isBinderAlive) {
                        lifecycleCallback.failAfterWorkerDeath()
                    } else {
                        try {
                            nativeSubmissionPlanned.set(true)
                            nativeTicket?.transferToNative()
                            start(requestId, lifecycleCallback.toAidlCallback(requestId))
                        } catch (error: Throwable) {
                            finishNativeAdmission()
                            throw error
                        }
                    }
                },
                cancel = { worker.cancel(clientId, requestId) },
                onProgress = onProgress,
                discardLateSynthesis = ::discardLateIpcOutput,
            )
        } finally {
            clientFinished.set(true)
            activeCallback.set(null)
            unlinkIfFullyTerminal()
        }
    }

    private fun MoonshineTtsClientCallback.toAidlCallback(
        expectedRequestId: Long,
    ): IGuideCastMoonshineTtsCallback = object : IGuideCastMoonshineTtsCallback.Stub() {
        override fun onProgress(
            requestId: Long,
            currentFile: String?,
            fileIndex: Int,
            fileCount: Int,
            bytesRead: Long,
            totalBytes: Long,
        ) {
            if (requestId == expectedRequestId) {
                onProgress(currentFile, fileIndex, fileCount, bytesRead, totalBytes)
            }
        }

        override fun onPrepared(requestId: Long) {
            if (requestId == expectedRequestId) onPrepared()
        }

        override fun onSynthesized(requestId: Long, path: String?) {
            if (requestId == expectedRequestId && path != null) onSynthesized(path)
        }

        override fun onPcmChunk(requestId: Long, pcm: ByteArray?) = Unit

        override fun onStreamCompleted(requestId: Long) = Unit

        override fun onError(requestId: Long, message: String?) {
            if (requestId == expectedRequestId) onError(message.orEmpty())
        }

        override fun onFinished(requestId: Long) {
            if (requestId == expectedRequestId) onFinished()
        }
    }

    private fun discardLateIpcOutput(path: String) {
        runCatching {
            val root = File(
                applicationContext.cacheDir,
                MoonshineTtsInferenceRuntime.IPC_DIRECTORY,
            ).canonicalFile
            val file = File(path).canonicalFile
            val languageDirectory = file.parentFile
            if (languageDirectory?.parentFile == root &&
                languageDirectory.name in MoonshineTtsVoiceCatalog.voices &&
                file.name.endsWith(".pcm")
            ) {
                file.delete()
            }
        }
    }

    /**
     * Uses only this language's worker. Asset/hot calls retry one transport death; a cold-load
     * ticket is single-transfer, so its failure returns to the outer gate for a fresh admission.
     */
    private suspend fun <T> withWorker(
        languageTag: String,
        operation: suspend (IGuideCastMoonshineTts) -> T,
    ): T = coroutineScope {
        val nativeColdLoadTicketPresent = currentNativeColdLoadTicket() != null
        val connection = requireNotNull(workerConnections[languageTag]) {
            "No isolated Moonshine TTS worker for $languageTag"
        }
        val operationId = nextClientOperationId.getAndIncrement()
        check(operationId > 0L) { "Moonshine TTS client operation identifier overflow" }
        val request = async(start = CoroutineStart.LAZY) {
            var firstConnectionFailure: Throwable? = null
            repeat(MAX_WORKER_CONNECTION_ATTEMPTS) { attempt ->
                val activeWorker = connection.worker()
                try {
                    return@async operation(activeWorker)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    currentCoroutineContext().ensureActive()
                    val connectionFailure = error.isWorkerConnectionFailure()
                    // isBinderAlive can become false immediately after worker() checks it and before
                    // the AIDL transaction starts. Always detach that dead binding. One transport-only
                    // retry closes this unavoidable TOCTOU window; model/native synthesis failures are
                    // never retried because doing so could amplify memory pressure.
                    if (connectionFailure) {
                        warmedWorkerBinders.remove(languageTag, activeWorker.asBinder())
                        connection.invalidate(activeWorker)
                    }
                    if (!shouldRetryMoonshineWorkerConnectionFailure(
                            connectionFailure = connectionFailure,
                            completedAttempts = attempt + 1,
                            maximumAttempts = MAX_WORKER_CONNECTION_ATTEMPTS,
                            nativeColdLoadTicketPresent = nativeColdLoadTicketPresent,
                        )
                    ) {
                        throw IllegalStateException(
                            moonshineWorkerFailureMessage(error),
                            error,
                        )
                    }
                    firstConnectionFailure = error
                    Log.w(
                        LOG_TAG,
                        "$languageTag TTS worker connection was lost; reconnecting once",
                        error,
                    )
                }
            }
            throw IllegalStateException(
                "통역 음성 작업 공간에 다시 연결하지 못했습니다.",
                firstConnectionFailure,
            )
        }
        if (!clientOperations.register(operationId, languageTag, request)) {
            val rejectedByLanguageBoundary = !clientOperations.acceptsLanguage(languageTag)
            request.cancel(CancellationException("Moonshine TTS resources are being released"))
            if (rejectedByLanguageBoundary) {
                throw IllegalStateException(
                    "Moonshine $languageTag 음성은 이전 native 작업 공간 종료 확인 전까지 " +
                        "Galaxy 대체 음성을 사용합니다.",
                )
            }
            throw CancellationException(
                "Moonshine TTS resources are being released",
            )
        }
        try {
            request.start()
            request.await()
        } finally {
            clientOperations.finish(operationId, request)
        }
    }

    suspend fun releaseNativeResources() = withContext(NonCancellable) {
        try {
            val drained = clientOperations.pauseAndCancel(CLIENT_RELEASE_DRAIN_MILLIS)
            if (!drained) {
                Log.w(
                    LOG_TAG,
                    "TTS client release timed out; native worker will close only after active calls return",
                )
            }
            // Connection state is protected by workerStateLock. Do not wait for a client slot or
            // the binding mutex: a late bind callback already releases only its obsolete binding.
            // The service defers runtime.close() until its bounded native dispatcher is truly idle.
            workerConnections.values.forEach { connection ->
                connection.disconnect(stopService = true)
            }
            warmedWorkerBinders.clear()
        } finally {
            clientOperations.clearLanguageSelection()
            clientOperations.resume()
        }
    }

    override fun close() {
        engines.clear()
        warmedWorkerBinders.clear()
        clientOperations.closeAndCancel()
        workerConnections.values.forEach(MoonshineTtsWorkerConnection::close)
    }

    private fun isVoiceModelPresent(spec: MoonshineVoiceSpec): Boolean = runCatching {
        val modelSpec = ModelSpec.tts(spec.moonshineLanguageTag, spec.voiceId)
        val directory = File(ModelCache.defaultRoot(applicationContext), ModelCache.key(modelSpec))
        MoonshineTtsModelIntegrity.hasReadySnapshot(
            directory,
            MoonshineTtsPinnedDependencies.forVoice(spec.voiceId),
        )
    }.getOrDefault(false)

    companion object {
        const val OUTPUT_SAMPLE_RATE_HZ = MoonshineTtsInferenceRuntime.OUTPUT_SAMPLE_RATE_HZ
        private const val LOG_TAG = "GuideCastTts"
        // The second attempt is reserved for RemoteException/DeadObjectException only. All model,
        // native-runtime, and synthesis errors still fail on their first attempt.
        private const val MAX_WORKER_CONNECTION_ATTEMPTS = 2
        private const val CLIENT_RELEASE_DRAIN_MILLIS = 500L
        private const val CLIENT_LANGUAGE_RETIRE_DRAIN_MILLIS = 500L
        private const val WORKER_LANGUAGE_RETIRE_TIMEOUT_MILLIS = 2_000L
        private const val STREAM_IPC_BUFFER_FRAMES = 64
        private const val MAX_STREAM_IPC_FRAME_BYTES = 4 * 1_024
    }
}

/**
 * Reserves one native-process slot for every generation whose shutdown is still unconfirmed.
 * A successor for the same language is never admitted beside that generation, while healthy
 * sibling languages keep their own slots. Retained workers are preferred so a replacement does
 * not evict a voice that is already warm merely because another language timed out while closing.
 */
internal data class MoonshineTtsNativeSelection(
    val admittedLanguageTags: Set<String>,
    val blockedLanguageTags: Set<String>,
)

internal fun planMoonshineTtsNativeSelection(
    requestedLanguageTags: Set<String>,
    retainedLanguageTags: Set<String>,
    unresolvedGenerations: Set<MoonshineTtsWorkerGeneration>,
    maximumResidentGenerations: Int,
): MoonshineTtsNativeSelection {
    require(maximumResidentGenerations > 0)
    require(retainedLanguageTags.all { it in requestedLanguageTags })

    val unresolvedLanguageTags = unresolvedGenerations
        .mapTo(linkedSetOf()) { it.languageTag }
    val availableSlots =
        (maximumResidentGenerations - unresolvedGenerations.size).coerceAtLeast(0)
    val eligibleLanguageTags = linkedSetOf<String>().apply {
        addAll(retainedLanguageTags.filterNot(unresolvedLanguageTags::contains))
        addAll(
            requestedLanguageTags.filter { languageTag ->
                languageTag !in retainedLanguageTags && languageTag !in unresolvedLanguageTags
            },
        )
    }
    val admittedLanguageTags = eligibleLanguageTags
        .take(availableSlots)
        .toCollection(linkedSetOf())
    return MoonshineTtsNativeSelection(
        admittedLanguageTags = admittedLanguageTags,
        blockedLanguageTags = requestedLanguageTags
            .filterNot(admittedLanguageTags::contains)
            .toCollection(linkedSetOf()),
    )
}

/**
 * Warm-up requires verified voice assets on disk. When a previous attempt failed (e.g. from a
 * transient worker connection drop), preserve the operator's ability to re-warm the voice without
 * forcing a redundant re-download of intact model artifacts.
 */
internal fun isVoiceModelPreparedForWarmup(
    readiness: MoonshineTtsReadiness,
    voiceModelPresentOnDisk: Boolean,
): Boolean = readiness == MoonshineTtsReadiness.READY || voiceModelPresentOnDisk

/** Keep the typed death cause across the multi-language status boundary, not only its text. */
internal fun moonshinePreparationFailure(
    stageLabel: String,
    failures: List<Pair<String, Throwable>>,
): IllegalStateException {
    require(failures.isNotEmpty())
    return IllegalStateException(
        "$stageLabel 실패: " + failures.joinToString { (language, error) ->
            "$language=${error.message ?: error.javaClass.simpleName}"
        },
        failures.first().second,
    ).also { result -> failures.drop(1).forEach { result.addSuppressed(it.second) } }
}

/**
 * A NativeColdLoadTicket is single-transfer. Binder death completes that ticket, so retrying in
 * the same coroutine would try to transfer a CLOSED ticket to the replacement worker. Fail the
 * current native request and let the outer gate acquire a fresh ticket. Asset preparation and hot
 * operations have no ticket and retain the existing one-time transport retry.
 */
internal fun shouldRetryMoonshineWorkerConnectionFailure(
    connectionFailure: Boolean,
    completedAttempts: Int,
    maximumAttempts: Int,
    nativeColdLoadTicketPresent: Boolean,
): Boolean {
    require(completedAttempts > 0)
    require(maximumAttempts > 0)
    return connectionFailure &&
        !nativeColdLoadTicketPresent &&
        completedAttempts < maximumAttempts
}

/**
 * Binder already unmarshals an incoming cross-process `byte[]` into a client-owned array. Keep
 * that exact immutable-by-contract frame through the bounded queue instead of allocating and
 * copying it a second time for every 20 ms of every language.
 */
internal fun ByteArray.toBinderPcmAudioFrame(
    capturedAtElapsedRealtimeNanos: Long,
    maximumFrameBytes: Int,
): PcmAudioFrame? {
    if (isEmpty() || size % Short.SIZE_BYTES != 0 || size > maximumFrameBytes) return null
    return PcmAudioFrame(
        bytes = this,
        capturedAtElapsedRealtimeNanos = capturedAtElapsedRealtimeNanos,
    )
}

/** Death observation belongs to both the client result/stream and transferred native lifetime. */
internal fun moonshineRequestMayUnlinkDeathRecipient(
    clientFinished: Boolean,
    nativeSubmissionPlanned: Boolean,
    nativeFinished: Boolean,
): Boolean = clientFinished && (!nativeSubmissionPlanned || nativeFinished)

internal fun moonshineStreamCanComplete(
    streamCompleted: Boolean,
    workerFinished: Boolean,
    failurePresent: Boolean,
): Boolean = streamCompleted && workerFinished && !failurePresent

private fun MoonshineTtsClientCallback.failAfterWorkerDeath() {
    // This callback is in the client process, so preserve RemoteException instead of flattening it
    // to an AIDL error string. withWorker() can detach the dead binding; ticket-free asset/hot work
    // may reconnect once, while a cold-load request returns to the outer gate for a fresh ticket.
    onFailure(RemoteException("Moonshine TTS worker died before completing the request"))
    onFinished()
}

/** Keeps cold-load ownership alive after the result continuation itself has been cancelled. */
private fun MoonshineTtsClientCallback.withNativeFinished(
    onNativeFinished: () -> Unit,
): MoonshineTtsClientCallback = object : MoonshineTtsClientCallback {
    override fun onProgress(
        currentFile: String?,
        fileIndex: Int,
        fileCount: Int,
        bytesRead: Long,
        totalBytes: Long,
    ) = this@withNativeFinished.onProgress(
        currentFile,
        fileIndex,
        fileCount,
        bytesRead,
        totalBytes,
    )

    override fun onPrepared() = this@withNativeFinished.onPrepared()

    override fun onSynthesized(path: String) = this@withNativeFinished.onSynthesized(path)

    override fun onError(message: String) = this@withNativeFinished.onError(message)

    override fun onFailure(error: Throwable) = this@withNativeFinished.onFailure(error)

    override fun onFinished() {
        onNativeFinished()
        this@withNativeFinished.onFinished()
    }
}

/** Keeps the worker's actionable model/native/transport detail in product-visible status. */
internal fun moonshineWorkerFailureMessage(error: Throwable): String {
    val detail = generateSequence(error) { it.cause }
        .map { cause ->
            cause.message
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: cause.javaClass.simpleName
        }
        .distinct()
        .take(2)
        .joinToString(" <- ")
        .take(MAX_VISIBLE_FAILURE_DETAIL_CHARACTERS)
    return "통역 음성 작업이 중단되었습니다: $detail. " +
        "방송은 계속되며 다음 문장에서 다시 시도합니다."
}

private fun MoonshineVoiceSpec.toStatus(
    languageTag: String,
    ready: Boolean = false,
) = MoonshineTtsStatus(
    languageTag = languageTag,
    displayName = displayName,
    voiceId = voiceId,
    readiness = if (ready) MoonshineTtsReadiness.READY else MoonshineTtsReadiness.NOT_INSTALLED,
    progress = if (ready) 1f else 0f,
)

private fun Throwable.isWorkerConnectionFailure(): Boolean {
    var current: Throwable? = this
    repeat(MAX_CAUSE_DEPTH) {
        // Retrying after audible PCM would repeat the beginning of a translated sentence.
        if (current is MoonshineTtsStreamInterruptedAfterOutputException) return false
        if (current is RemoteException) return true
        val next = current?.cause
        if (next == null || next === current) return false
        current = next
    }
    return false
}

private class MoonshineTtsStreamInterruptedAfterOutputException(cause: Throwable) :
    IllegalStateException(
        "통역 음성 송출이 문장 중간에 중단되었습니다. 다음 문장부터 다시 시도합니다.",
        cause,
    )

private const val MAX_CAUSE_DEPTH = 8
private const val MAX_VISIBLE_FAILURE_DETAIL_CHARACTERS = 300

private class MoonshineSpeechSynthesisEngine(
    private val languageTag: String,
    private val spec: MoonshineVoiceSpec,
    private val synthesizeStreaming: suspend (
        String,
        suspend (PcmAudioFrame) -> Unit,
    ) -> Unit,
    private val currentStatus: () -> MoonshineTtsStatus?,
    private val onStatus: (MoonshineTtsStatus) -> Unit,
) : SpeechSynthesisEngine {
    override fun synthesize(text: String, languageTag: String): Flow<PcmAudioFrame> = channelFlow {
        require(languageTag == this@MoonshineSpeechSynthesisEngine.languageTag) {
            "Moonshine TTS engine language mismatch"
        }
        require(text.isNotBlank() && text.length <= MAX_TEXT_CHARACTERS) {
            "TTS text is empty or too long"
        }
        val startedAt = SystemClock.elapsedRealtime()
        val baseStatus = spec.toStatus(languageTag)
        val previousStatus = currentStatus() ?: baseStatus
        onStatus(baseStatus.copy(readiness = MoonshineTtsReadiness.DOWNLOADING))
        Log.i(LOG_TAG, "TTS isolated synthesis queued: target=$languageTag, chars=${text.length}")
        var frameCount = 0L
        try {
            synthesizeStreaming(text) { frame ->
                send(frame)
                frameCount++
            }
        } catch (cancelled: CancellationException) {
            onStatus(previousStatus)
            throw cancelled
        } catch (error: Throwable) {
            onStatus(
                baseStatus.copy(
                    readiness = MoonshineTtsReadiness.FAILED,
                    errorMessage = error.message ?: error.javaClass.simpleName,
                ),
            )
            throw error
        }
        check(frameCount > 0L) { "Moonshine TTS streaming returned no PCM" }
        onStatus(baseStatus.copy(readiness = MoonshineTtsReadiness.READY, progress = 1f))
        Log.i(
            LOG_TAG,
            "TTS PCM streaming finished: target=$languageTag, frames=$frameCount, " +
                "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
        )
    }

    companion object {
        private const val LOG_TAG = "GuideCastTts"
        private const val MAX_TEXT_CHARACTERS = 600
    }
}

/** Emits the completed native phrase at microphone-like cadence instead of one CPU burst. */
internal suspend fun emitPacedPcmFrames(
    input: InputStream,
    sampleRateHz: Int,
    onFrame: suspend (ByteArray) -> Unit,
    pace: suspend (Long) -> Unit = { millis -> delay(millis) },
) {
    require(sampleRateHz > 0)
    val bytesPerFrame = sampleRateHz * TTS_FRAME_MILLIS / 1_000 * Short.SIZE_BYTES
    check(bytesPerFrame > 0 && bytesPerFrame % Short.SIZE_BYTES == 0)
    val buffer = ByteArray(bytesPerFrame)
    while (true) {
        var count = 0
        while (count < buffer.size) {
            val read = input.read(buffer, count, buffer.size - count)
            if (read < 0) break
            count += read
        }
        if (count == 0) break
        check(count % Short.SIZE_BYTES == 0) { "TTS PCM file is truncated" }
        onFrame(buffer.copyOf(count))
        // The first frame is immediate; this delay spaces every subsequent frame by 20 ms.
        pace(TTS_FRAME_MILLIS.toLong())
    }
}

private fun validatePcmFile(context: Context, path: String): File {
    val root = File(context.cacheDir, MoonshineTtsInferenceRuntime.IPC_DIRECTORY).canonicalFile
    val file = File(path).canonicalFile
    val languageDirectory = file.parentFile
    check(
        languageDirectory?.parentFile == root &&
            languageDirectory.name in MoonshineTtsVoiceCatalog.voices &&
            file.name.endsWith(".pcm"),
    ) {
        "TTS worker returned an invalid audio path"
    }
    check(file.isFile && file.length() in 2..MAX_PCM_BYTES && file.length() % 2L == 0L) {
        "TTS worker returned an invalid audio file"
    }
    return file
}

internal fun resampleLinear(source: FloatArray, sourceRate: Int, targetRate: Int): FloatArray {
    require(sourceRate > 0 && targetRate > 0)
    if (source.isEmpty() || sourceRate == targetRate) return source.copyOf()
    if (source.size == 1) return floatArrayOf(source[0])
    val outputSize = ((source.size.toLong() * targetRate) / sourceRate).toInt().coerceAtLeast(1)
    val ratio = sourceRate.toDouble() / targetRate
    return FloatArray(outputSize) { index ->
        val position = index * ratio
        val left = floor(position).toInt().coerceIn(0, source.lastIndex)
        val right = (left + 1).coerceAtMost(source.lastIndex)
        val fraction = (position - left).toFloat()
        source[left] + (source[right] - source[left]) * fraction
    }
}

private const val MAX_PCM_BYTES = 32L * 1024 * 1024
internal const val TTS_FRAME_MILLIS = 20
