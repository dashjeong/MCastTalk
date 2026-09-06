package app.guidecast.provider.android.tts

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import app.guidecast.core.stream.MAX_SIMULTANEOUS_TRANSLATED_CHANNELS
import app.guidecast.core.stream.PcmAudioFrame
import app.guidecast.core.translation.ExecutionAwareSpeechSynthesisEngine
import app.guidecast.core.translation.SpeechSynthesisEngine
import app.guidecast.core.translation.SpeechSynthesisEngineProvider
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

class AndroidOfflineSpeechSynthesisProvider internal constructor(
    context: Context,
    private val outputSampleRateHz: Int = 16_000,
    private val synthesisAdmission: Semaphore,
    engineFactory: (String, () -> Unit, Semaphore) -> AndroidOfflineSpeechSynthesisEngine,
) : SpeechSynthesisEngineProvider, Closeable {
    private val applicationContext = context.applicationContext
    private val engines = RetiringLanguageEngineRegistry(
        create = { languageTag, onDisposed ->
            engineFactory(languageTag, onDisposed, synthesisAdmission)
        },
        retireWhenIdle = AndroidOfflineSpeechSynthesisEngine::retireWhenIdle,
        forceClose = AndroidOfflineSpeechSynthesisEngine::close,
    )

    constructor(
        context: Context,
        outputSampleRateHz: Int = 16_000,
        preferredEnginePackage: (String) -> String? = { null },
    ) : this(
        context = context,
        outputSampleRateHz = outputSampleRateHz,
        synthesisAdmission = Semaphore(1),
        engineFactory = { languageTag, onDisposed, admission ->
            AndroidOfflineSpeechSynthesisEngine(
                context = context.applicationContext,
                languageTag = languageTag,
                outputSampleRateHz = outputSampleRateHz,
                preferredEnginePackage = { preferredEnginePackage(languageTag) },
                onDisposed = onDisposed,
                synthesisAdmission = admission,
            )
        },
    )

    override fun engineFor(targetLanguageTag: String): SpeechSynthesisEngine =
        engines.getOrCreate(targetLanguageTag)

    suspend fun prepare(languageTags: Collection<String>) {
        require(languageTags.size in 1..MAX_SIMULTANEOUS_TRANSLATED_CHANNELS) {
            "Prepare one to $MAX_SIMULTANEOUS_TRANSLATED_CHANNELS TTS languages"
        }
        languageTags.distinct().forEach { languageTag ->
            engines.getOrCreate(languageTag).prepare()
        }
    }

    /**
     * Releases fallback/standby engines no longer selected by the replacement broadcast.
     * An engine that is currently producing PCM drains that utterance before shutdown.
     */
    fun activateBroadcastLanguages(languageTags: Set<String>) {
        require(languageTags.size in 1..MAX_SIMULTANEOUS_TRANSLATED_CHANNELS) {
            "Retain one to $MAX_SIMULTANEOUS_TRANSLATED_CHANNELS broadcast TTS languages"
        }
        engines.reconcileLanguageOwners(
            mapOf(
                BROADCAST_RETENTION_OWNER to languageTags,
                SETTINGS_RETENTION_OWNER to emptySet(),
            ),
        )
    }

    /** Removes only broadcast retention; in-flight synthesis drains before engine disposal. */
    fun releaseBroadcastLanguages() {
        engines.reconcileLanguages(BROADCAST_RETENTION_OWNER, emptySet())
    }

    /** Settings previews may coexist with a live broadcast; only the union is retained. */
    fun retainSettingsLanguages(languageTags: Set<String>) {
        require(languageTags.size <= MAX_SIMULTANEOUS_TRANSLATED_CHANNELS) {
            "Retain up to $MAX_SIMULTANEOUS_TRANSLATED_CHANNELS settings TTS languages"
        }
        engines.reconcileLanguages(SETTINGS_RETENTION_OWNER, languageTags)
    }

    override fun close() {
        engines.closeAll()
    }

    private companion object {
        const val BROADCAST_RETENTION_OWNER = "broadcast"
        const val SETTINGS_RETENTION_OWNER = "settings"
    }
}

internal class AndroidOfflineSpeechSynthesisEngine internal constructor(
    private val context: Context,
    private val languageTag: String,
    private val outputSampleRateHz: Int,
    private val preferredEnginePackage: () -> String? = { null },
    private val onDisposed: () -> Unit,
    private val candidateEngineResolver: (PackageManager?, String?) -> List<String?> = ::resolveCandidateTtsEngines,
    private val ttsClientFactory: suspend (Context, String?, (Int) -> Unit) -> AndroidTtsClient = ::createDefaultTtsClient,
    private val binderExecutor: ExecutorService = defaultBinderExecutor,
    private val synthesisAdmission: Semaphore = Semaphore(1),
) : ExecutionAwareSpeechSynthesisEngine, Closeable {

    internal constructor(
        context: Context,
        languageTag: String,
        outputSampleRateHz: Int,
        preferredPackage: String?,
        onDisposed: () -> Unit,
        candidateEngineResolver: (PackageManager?, String?) -> List<String?> = ::resolveCandidateTtsEngines,
        ttsClientFactory: suspend (Context, String?, (Int) -> Unit) -> AndroidTtsClient = ::createDefaultTtsClient,
        binderExecutor: ExecutorService = defaultBinderExecutor,
        synthesisAdmission: Semaphore = Semaphore(1),
    ) : this(
        context = context,
        languageTag = languageTag,
        outputSampleRateHz = outputSampleRateHz,
        preferredEnginePackage = { preferredPackage },
        onDisposed = onDisposed,
        candidateEngineResolver = candidateEngineResolver,
        ttsClientFactory = ttsClientFactory,
        binderExecutor = binderExecutor,
        synthesisAdmission = synthesisAdmission,
    )
    private data class Request(
        val outputFile: File,
        val pcmBridge: BoundedPcmFrameBridge,
        val generation: Long,
        val markExecutionStarted: () -> Unit,
        val conversionLock: Any = Any(),
        @Volatile var converter: PcmStreamConverter? = null,
    )

    private val initializationMutex = Mutex()
    private val lifecycleLock = Any()
    private val operationLifecycle = RetirableResourceLifecycle {
        try {
            shutdownNow()
        } finally {
            onDisposed()
        }
    }
    private val requests = ConcurrentHashMap<String, Request>()
    private val generation = AtomicLong(0L)
    private val failedEnginePackages = mutableSetOf<String?>()
    private var textToSpeech: AndroidTtsClient? = null
    private var activeEnginePackage: String? = null
    private var lastAppliedPreference: String? = null
    private var pendingInitialization: AndroidTtsInitializationAttempt<AndroidTtsClient>? = null
    private var closed = false

    suspend fun prepare() {
        operationLifecycle.acquire().use {
            ensureReady()
        }
    }

    override val maximumExecutionStartWaitMillis: Long
        get() = EXECUTION_START_WAIT_MILLIS

    override fun synthesize(
        text: String,
        languageTag: String,
        onExecutionStarted: () -> Unit,
    ): Flow<PcmAudioFrame> = flow {
        operationLifecycle.acquire().use {
            require(text.length <= 2_000) { "TTS utterance is too long" }
            require(languageTag == this@AndroidOfflineSpeechSynthesisEngine.languageTag) {
                "TTS engine language mismatch"
            }

            val admission = AndroidTtsSynthesisAdmission.acquire(
                semaphore = synthesisAdmission,
                timeoutMillis = maximumExecutionStartWaitMillis,
                onExecutionStarted = onExecutionStarted,
            )

            var emittedPcmBytes = 0L
            var lastError: Throwable? = null

            try {
                while (true) {
                    val engine = ensureReady()
                    val requestGeneration = generation.get()
                    val utteranceId = UUID.randomUUID().toString()
                    val outputFile = File.createTempFile(
                        "guidecast-tts-",
                        ".audio",
                        context.cacheDir ?: context.filesDir,
                    )
                    val pcmBridge = BoundedPcmFrameBridge(
                        sampleRateHz = outputSampleRateHz,
                        frameDurationMillis = TTS_FRAME_MILLIS,
                        capacity = TTS_LIVE_BUFFER_FRAMES,
                    )
                    requests[utteranceId] = Request(
                        outputFile = outputFile,
                        pcmBridge = pcmBridge,
                        generation = requestGeneration,
                        markExecutionStarted = admission::markExecutionStarted,
                    )

                    try {
                        val executionResult = runCatching {
                            check(
                                engine.synthesizeToFile(
                                    text,
                                    Bundle(),
                                    outputFile,
                                    utteranceId,
                                ) == TextToSpeech.SUCCESS,
                            ) {
                                "Android TTS rejected the synthesis request"
                            }

                            val pacer = SampleDurationPacer(outputSampleRateHz)
                            for (frame in pcmBridge.frames) {
                                emit(
                                    PcmAudioFrame(
                                        bytes = frame,
                                        capturedAtElapsedRealtimeNanos = pacer.awaitTurn(frame.size),
                                    ),
                                )
                                emittedPcmBytes += frame.size
                            }

                            pcmBridge.recoveryStartByteOffset()?.let { recoveryStart ->
                                streamRecoveredWavePcmFrames(
                                    file = outputFile,
                                    outputSampleRateHz = outputSampleRateHz,
                                    frameBytes = pcmBridge.frameBytes,
                                    startByteOffset = recoveryStart,
                                ).collect { frame ->
                                    emit(
                                        PcmAudioFrame(
                                            bytes = frame,
                                            capturedAtElapsedRealtimeNanos = pacer.awaitTurn(frame.size),
                                        ),
                                    )
                                    emittedPcmBytes += frame.size
                                }
                            }

                            check(emittedPcmBytes > 0L) {
                                "Android TTS completed without PCM output"
                            }
                        }

                        if (executionResult.isSuccess) {
                            return@flow
                        }

                        val error = executionResult.exceptionOrNull()
                            ?: IllegalStateException("Unknown Android TTS synthesis error")

                        error.findCancellation()?.let { cancelled ->
                            // Explicit cancellation after submit stops only this language engine.
                            runCatching { engine.stop() }
                            throw cancelled
                        }

                        if (emittedPcmBytes > 0L) {
                            // Partial PCM already emitted: never re-synthesize the full sentence.
                            invalidateActiveEngine(error)
                            throw IllegalStateException(
                                "Android TTS failed after emitting partial PCM; replaying sentence is prohibited: ${error.message}",
                                error,
                            )
                        }

                        Log.w(
                            LOG_TAG,
                            "Android TTS execution failed before PCM output for " +
                                "target=$languageTag: ${error.message}. " +
                                "Invalidate and reselect next candidate.",
                        )
                        lastError = error
                        invalidateActiveEngine(error)
                    } finally {
                        requests.remove(utteranceId)
                        pcmBridge.abort()
                        outputFile.delete()
                    }
                }
            } finally {
                // Covers initialization/submit failure, missing vendor callbacks and cancellation.
                // A request-local atomic guard makes late callbacks harmless.
                admission.close()
            }
        }
    }

    fun retireWhenIdle() {
        operationLifecycle.retireWhenIdle()
    }

    fun invalidateIfIdle(): Boolean {
        val latestPreference = preferredEnginePackage()
        val clientToShutdown = synchronized(lifecycleLock) {
            if (closed || textToSpeech == null || requests.isNotEmpty()) {
                return false
            }
            if (lastAppliedPreference != latestPreference) {
                generation.incrementAndGet()
                val current = textToSpeech
                textToSpeech = null
                activeEnginePackage = null
                failedEnginePackages.clear()
                current
            } else {
                null
            }
        } ?: return false
        shutdownClient(clientToShutdown)
        return true
    }

    private suspend fun ensureReady(): AndroidTtsClient = initializationMutex.withLock {
        val latestPreference = preferredEnginePackage()
        val supersededClient = synchronized(lifecycleLock) {
            check(!closed) { "Android TTS engine is closed" }
            val current = textToSpeech
            if (current != null) {
                val preferenceChanged = lastAppliedPreference != latestPreference
                if (preferenceChanged && requests.isEmpty()) {
                    Log.i(
                        LOG_TAG,
                        "Preferred TTS engine changed ($activeEnginePackage -> $latestPreference) while idle for $languageTag. Re-initializing.",
                    )
                    generation.incrementAndGet()
                    textToSpeech = null
                    activeEnginePackage = null
                    failedEnginePackages.clear()
                    current
                } else {
                    return@synchronized current
                }
            } else {
                null
            }
        }
        if (supersededClient != null && synchronized(lifecycleLock) { textToSpeech === supersededClient }) {
            return@withLock supersededClient
        } else if (supersededClient != null) {
            shutdownClient(supersededClient)
        }

        val candidateEngines = candidateEngineResolver(context.packageManager, latestPreference)
        var lastError: Throwable? = null

        for (enginePackage in candidateEngines) {
            if (enginePackage in failedEnginePackages) {
                continue
            }

            val attempt = AndroidTtsInitializationAttempt<AndroidTtsClient>(
                successfulStatus = TextToSpeech.SUCCESS,
                failure = { status ->
                    IllegalStateException("Android TTS initialization failed: $status")
                },
                discard = ::shutdownClient,
            )
            val accepted = synchronized(lifecycleLock) {
                if (closed || textToSpeech != null) {
                    false
                } else {
                    pendingInitialization = attempt
                    true
                }
            }
            if (!accepted) {
                val error = IllegalStateException("Android TTS initialization was superseded")
                attempt.abort(error)
                throw error
            }

            var initializedClient: AndroidTtsClient? = null
            try {
                val client = withTimeout(INIT_TIMEOUT_MILLIS) {
                    constructAndAwaitAndroidTtsInitialization(attempt) {
                        val candidate = ttsClientFactory(context, enginePackage, attempt::reportStatus)
                        attempt.attach(candidate)
                    }
                }.also { initializedClient = it }

                configureOfflineVoice(client)
                client.setOnUtteranceProgressListener(listener)
                val installed = synchronized(lifecycleLock) {
                    if (!closed && pendingInitialization === attempt && textToSpeech == null) {
                        pendingInitialization = null
                        textToSpeech = client
                        activeEnginePackage = enginePackage
                        lastAppliedPreference = latestPreference
                        true
                    } else {
                        false
                    }
                }
                check(installed) { "Android TTS engine closed during initialization" }
                Log.i(
                    LOG_TAG,
                    "Android TTS engine initialized: target=$languageTag, " +
                        "engine=${enginePackage ?: "default"}",
                )
                return@withLock client
            } catch (error: Throwable) {
                synchronized(lifecycleLock) {
                    if (pendingInitialization === attempt) pendingInitialization = null
                }
                attempt.abort(error)
                initializedClient?.let(::shutdownClient)

                // An outer timeout cancels this caller, unlike this candidate's own timeout.
                // Never start another vendor engine after the caller has cancelled preparation.
                currentCoroutineContext().ensureActive()

                val cancellation = error.findCancellation()
                if (cancellation != null && cancellation !is TimeoutCancellationException && error !is TimeoutCancellationException) {
                    // External cancellation: stop trying further candidate engines and rethrow immediately
                    throw cancellation
                }

                failedEnginePackages.add(enginePackage)
                lastError = error
                Log.w(
                    LOG_TAG,
                    "TTS engine candidate '${enginePackage ?: "default"}' unavailable for $languageTag: " +
                        (error.message ?: error.javaClass.simpleName),
                )
            }
        }

        failedEnginePackages.clear()
        throw lastError ?: IllegalStateException("설치된 오프라인 TTS 음성이 없습니다: $languageTag")
    }

    private fun invalidateActiveEngine(error: Throwable) {
        val clientToShutdown = synchronized(lifecycleLock) {
            generation.incrementAndGet()
            val current = textToSpeech
            textToSpeech = null
            activeEnginePackage?.let { failedEnginePackages.add(it) }
            activeEnginePackage = null
            current
        }
        clientToShutdown?.let(::shutdownClient)
        Log.i(LOG_TAG, "Invalidated Android TTS engine generation for $languageTag: ${error.message}")
    }

    private suspend fun configureOfflineVoice(client: AndroidTtsClient) {
        val availableVoices = queryVoices(client, VOICE_QUERY_TIMEOUT_MILLIS)
        val voice = selectOfflineVoice(availableVoices, languageTag)
            ?: error("오프라인 TTS 음성을 찾지 못했습니다 (target=$languageTag, engine=${client.defaultEngine})")
        check(client.setVoice(voice) == TextToSpeech.SUCCESS) {
            "오프라인 TTS 음성을 선택하지 못했습니다: $languageTag"
        }
        check(client.setPitch(1.0f) == TextToSpeech.SUCCESS) {
            "오프라인 TTS 음성 피치를 고정하지 못했습니다: $languageTag"
        }
        check(client.setSpeechRate(NATURAL_SPEECH_RATE) == TextToSpeech.SUCCESS) {
            "오프라인 TTS 재생 속도를 고정하지 못했습니다: $languageTag"
        }
    }

    /**
     * Queries offline voices without blocking the caller or child coroutines indefinitely.
     *
     * NOTE: The underlying Android [TextToSpeech.getVoices] is a synchronous Binder IPC call.
     * If the vendor TTS service freezes inside kernel Binder ioctl, coroutine cancellation cannot
     * terminate the blocked OS thread. Therefore, this call is executed via [FutureTask] on an
     * independent, fixed-size daemon [ThreadPoolExecutor] (2 threads, bounded queue of 14).
     *
     * The caller coroutine is decoupled using [suspendCancellableCoroutine] with [withTimeout].
     * If the timeout expires or the calling job is cancelled, the caller resumes immediately without
     * waiting for the blocked daemon thread. [FutureTask.cancel] is invoked, and any late completion
     * or callback from the abandoned task is discarded.
     * If the daemon thread pool queue is saturated due to frozen threads, subsequent queries fail fast
     * with [RejectedExecutionException] rather than leaking unbounded IO threads.
     */
    private suspend fun queryVoices(client: AndroidTtsClient, timeoutMillis: Long): Set<android.speech.tts.Voice> {
        return try {
            withTimeout(timeoutMillis) {
                suspendCancellableCoroutine { continuation ->
                    val futureTask = object : FutureTask<Set<android.speech.tts.Voice>>(Callable {
                        runCatching { client.getVoices() }.getOrNull().orEmpty()
                    }) {
                        override fun done() {
                            if (!isCancelled && continuation.isActive) {
                                try {
                                    val voices = get()
                                    continuation.resumeWith(Result.success(voices))
                                } catch (_: Throwable) {
                                    continuation.resumeWith(Result.success(emptySet()))
                                }
                            }
                        }
                    }

                    continuation.invokeOnCancellation {
                        futureTask.cancel(true)
                    }

                    try {
                        binderExecutor.execute(futureTask)
                    } catch (rejected: RejectedExecutionException) {
                        Log.w(LOG_TAG, "Voice query rejected (queue full/hung): ${rejected.message}")
                        if (continuation.isActive) {
                            continuation.resumeWith(Result.success(emptySet()))
                        }
                    }
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            Log.w(LOG_TAG, "Timed out querying voices from TTS engine (package=${client.defaultEngine})")
            emptySet()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.w(LOG_TAG, "Failed querying voices from TTS engine: ${error.message}")
            emptySet()
        }
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) {
            val request = requests[utteranceId] ?: return
            if (request.generation != generation.get()) return
            request.markExecutionStarted()
        }

        override fun onBeginSynthesis(
            utteranceId: String,
            sampleRateInHz: Int,
            audioFormat: Int,
            channelCount: Int,
        ) {
            val request = requests[utteranceId] ?: return
            if (request.generation != generation.get()) return
            request.markExecutionStarted()
            runCatching {
                PcmStreamConverter(
                    inputSampleRateHz = sampleRateInHz,
                    inputEncoding = audioFormat,
                    inputChannelCount = channelCount,
                    outputSampleRateHz = outputSampleRateHz,
                )
            }.onSuccess { converter ->
                synchronized(request.conversionLock) { request.converter = converter }
            }.onFailure(request.pcmBridge::fail)
        }

        override fun onAudioAvailable(utteranceId: String, audio: ByteArray) {
            val request = requests[utteranceId] ?: return
            if (request.generation != generation.get()) return
            request.markExecutionStarted()
            runCatching {
                synchronized(request.conversionLock) {
                    request.converter?.convert(audio) ?: ByteArray(0)
                }
            }.onSuccess { converted ->
                if (converted.isNotEmpty()) request.pcmBridge.offer(converted)
            }.onFailure(request.pcmBridge::fail)
        }

        override fun onDone(utteranceId: String) {
            val request = requests[utteranceId] ?: return
            if (request.generation != generation.get()) return
            request.pcmBridge.finish()
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String) {
            fail(utteranceId, TextToSpeech.ERROR)
        }

        override fun onError(utteranceId: String, errorCode: Int) {
            fail(utteranceId, errorCode)
        }

        private fun fail(utteranceId: String, errorCode: Int) {
            val request = requests[utteranceId] ?: return
            if (request.generation != generation.get()) return
            request.pcmBridge.fail(
                IllegalStateException("Android TTS synthesis failed: $errorCode"),
            )
        }
    }

    override fun close() {
        operationLifecycle.closeNow()
    }

    private fun shutdownNow() {
        val (pending, active) = synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            generation.incrementAndGet()
            val snapshot = pendingInitialization to textToSpeech
            pendingInitialization = null
            textToSpeech = null
            activeEnginePackage = null
            snapshot
        }
        requests.values.forEach { request ->
            request.pcmBridge.fail(IllegalStateException("Android TTS engine closed"))
            request.outputFile.delete()
        }
        requests.clear()
        pending?.abort(IllegalStateException("Android TTS engine closed during initialization"))
        active?.let(::shutdownClient)
    }

    private fun shutdownClient(client: AndroidTtsClient) {
        val mainLooper = android.os.Looper.getMainLooper()
        if (mainLooper != null && android.os.Looper.myLooper() === mainLooper) {
            try {
                defaultBinderExecutor.execute { runCatching { client.shutdown() } }
            } catch (rejected: RejectedExecutionException) {
                Log.w(LOG_TAG, "TTS cleanup queue is saturated; no blocking cleanup on main thread")
            }
        } else {
            runCatching { client.shutdown() }
        }
    }

    internal companion object {
        private const val LOG_TAG = "AndroidOfflineTts"
        // Slightly below the engine default improves guide-style articulation without pitch shift.
        const val NATURAL_SPEECH_RATE = 0.95f
        const val TTS_FRAME_MILLIS = 20
        const val TTS_LIVE_BUFFER_FRAMES = 25
        const val INIT_TIMEOUT_MILLIS = 5_000L
        const val VOICE_QUERY_TIMEOUT_MILLIS = 3_000L
        const val EXECUTION_START_WAIT_MILLIS = 10_000L

        internal val defaultBinderExecutor: ExecutorService by lazy {
            ThreadPoolExecutor(
                2,
                2,
                0L,
                TimeUnit.MILLISECONDS,
                ArrayBlockingQueue(14),
                ThreadFactory { runnable ->
                    Thread(runnable, "guidecast-tts-binder").apply { isDaemon = true }
                },
                ThreadPoolExecutor.AbortPolicy(),
            )
        }
    }
}

private class AndroidTtsSynthesisAdmission(
    private val semaphore: Semaphore,
    private val onExecutionStarted: () -> Unit,
) : Closeable {
    private val released = AtomicBoolean(false)

    fun markExecutionStarted() {
        if (released.compareAndSet(false, true)) {
            try {
                onExecutionStarted()
            } finally {
                semaphore.release()
            }
        }
    }

    override fun close() {
        if (released.compareAndSet(false, true)) semaphore.release()
    }

    companion object {
        suspend fun acquire(
            semaphore: Semaphore,
            timeoutMillis: Long,
            onExecutionStarted: () -> Unit,
        ): AndroidTtsSynthesisAdmission {
            var acquired = false
            var transferred = false
            try {
                val completed = withTimeoutOrNull(timeoutMillis) {
                    semaphore.acquire()
                    acquired = true
                    true
                } ?: false
                check(completed) { "Android TTS execution queue wait timed out" }
                return AndroidTtsSynthesisAdmission(
                    semaphore = semaphore,
                    onExecutionStarted = onExecutionStarted,
                ).also { transferred = true }
            } finally {
                if (acquired && !transferred) semaphore.release()
            }
        }
    }
}

internal interface AndroidTtsClient {
    val defaultEngine: String?
    fun getVoices(): Set<android.speech.tts.Voice>
    fun setVoice(voice: android.speech.tts.Voice): Int
    fun setPitch(pitch: Float): Int
    fun setSpeechRate(speechRate: Float): Int
    fun setOnUtteranceProgressListener(listener: UtteranceProgressListener): Int
    fun synthesizeToFile(text: CharSequence, params: Bundle, file: File, utteranceId: String): Int
    fun stop(): Int
    fun shutdown()
}

private class DefaultAndroidTtsClient(private val tts: TextToSpeech) : AndroidTtsClient {
    override val defaultEngine: String? get() = runCatching { tts.defaultEngine }.getOrNull()
    override fun getVoices(): Set<android.speech.tts.Voice> = runCatching { tts.voices }.getOrNull().orEmpty()
    override fun setVoice(voice: android.speech.tts.Voice): Int = tts.setVoice(voice)
    override fun setPitch(pitch: Float): Int = tts.setPitch(pitch)
    override fun setSpeechRate(speechRate: Float): Int = tts.setSpeechRate(speechRate)
    override fun setOnUtteranceProgressListener(listener: UtteranceProgressListener): Int =
        tts.setOnUtteranceProgressListener(listener)
    override fun synthesizeToFile(text: CharSequence, params: Bundle, file: File, utteranceId: String): Int =
        tts.synthesizeToFile(text, params, file, utteranceId)
    override fun stop(): Int = tts.stop()
    override fun shutdown() = tts.shutdown()
}

internal suspend fun createDefaultTtsClient(
    context: Context,
    enginePackage: String?,
    onInit: (Int) -> Unit,
): AndroidTtsClient = withContext(Dispatchers.Main.immediate) {
    val tts = if (enginePackage != null) {
        TextToSpeech(context, onInit, enginePackage)
    } else {
        TextToSpeech(context, onInit)
    }
    DefaultAndroidTtsClient(tts)
}

private fun Throwable.findCancellation(): CancellationException? {
    var current: Throwable? = this
    repeat(8) {
        if (current is CancellationException) return current
        val next = current?.cause
        if (next == null || next === current) return null
        current = next
    }
    return null
}

internal const val SAMSUNG_TTS_PACKAGE = "com.samsung.SMT"
internal const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"

internal fun orderCandidateTtsEngines(
    installedPackages: Collection<String>,
    preferredPackage: String? = null,
): List<String?> {
    val priorityEngines = listOf(SAMSUNG_TTS_PACKAGE, GOOGLE_TTS_PACKAGE)
    val candidates = mutableListOf<String?>()
    if (preferredPackage != null) {
        candidates.add(preferredPackage)
    }
    if (null !in candidates) {
        candidates.add(null) // Default system engine first
    }
    for (pkg in priorityEngines) {
        if (pkg in installedPackages && pkg !in candidates) {
            candidates.add(pkg)
        }
    }
    for (pkg in installedPackages) {
        if (pkg !in candidates) {
            candidates.add(pkg)
        }
    }
    return candidates
}

internal fun resolveCandidateTtsEngines(
    packageManager: PackageManager?,
    preferredPackage: String? = null,
): List<String?> {
    val installed = if (packageManager != null) {
        val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
        packageManager.queryIntentServices(intent, 0)
            .mapNotNull { it.serviceInfo?.packageName }
            .distinct()
    } else {
        emptyList()
    }
    return orderCandidateTtsEngines(installed, preferredPackage)
}

/** Closes the cancellation window between constructing Android TTS and awaiting OnInit. */
internal suspend fun <T : Any> constructAndAwaitAndroidTtsInitialization(
    attempt: AndroidTtsInitializationAttempt<T>,
    construct: suspend () -> Unit,
): T = try {
    construct()
    attempt.await()
} catch (error: Throwable) {
    attempt.abort(error)
    throw error
}

/**
 * Owns a TTS object between constructor return and asynchronous OnInit completion.
 *
 * Android may report OnInit before or after the constructor returns. A coroutine timeout or
 * provider close may race either event. This hand-off disposes the candidate exactly once unless
 * a successful waiter has taken ownership, and ignores every late callback after abort.
 */
internal class AndroidTtsInitializationAttempt<T : Any>(
    private val successfulStatus: Int,
    private val failure: (Int) -> Throwable,
    private val discard: (T) -> Unit,
) {
    private val lock = Any()
    private var candidate: T? = null
    private var candidateAttached = false
    private var status: Int? = null
    private var waiter: CancellableContinuation<T>? = null
    private var terminalError: Throwable? = null
    private var successfulDeliveryStarted = false

    fun attach(value: T) {
        var discardValue: T? = null
        var delivery: Delivery<T>? = null
        synchronized(lock) {
            check(!candidateAttached) { "TTS initialization candidate already attached" }
            candidateAttached = true
            if (terminalError != null || successfulDeliveryStarted) {
                discardValue = value
            } else {
                candidate = value
                delivery = successDeliveryLocked()
            }
        }
        discardValue?.let(discard)
        delivery?.deliver()
    }

    fun reportStatus(value: Int) {
        var discarded: T? = null
        var delivery: Delivery<T>? = null
        synchronized(lock) {
            if (terminalError == null && !successfulDeliveryStarted && status == null) {
                status = value
                if (value == successfulStatus) {
                    delivery = successDeliveryLocked()
                } else {
                    val error = failure(value)
                    terminalError = error
                    discarded = candidate.also { candidate = null }
                    waiter?.let { continuation ->
                        waiter = null
                        delivery = Delivery.Failure(continuation, error)
                    }
                }
            }
        }
        discarded?.let(discard)
        delivery?.deliver()
    }

    suspend fun await(): T = suspendCancellableCoroutine { continuation ->
        var delivery: Delivery<T>? = null
        synchronized(lock) {
            check(waiter == null && !successfulDeliveryStarted) {
                "TTS initialization supports exactly one waiter"
            }
            val error = terminalError
            if (error != null) {
                delivery = Delivery.Failure(continuation, error)
            } else {
                waiter = continuation
                delivery = successDeliveryLocked()
            }
        }
        continuation.invokeOnCancellation { cause ->
            abort(cause ?: CancellationException("TTS initialization was cancelled"))
        }
        delivery?.deliver()
    }

    fun abort(error: Throwable) {
        var discarded: T? = null
        var failureDelivery: Delivery<T>? = null
        synchronized(lock) {
            // resume(value, onCancellation) owns disposal after a successful hand-off begins.
            if (terminalError != null || successfulDeliveryStarted) return
            terminalError = error
            discarded = candidate.also { candidate = null }
            waiter?.let { continuation ->
                waiter = null
                failureDelivery = Delivery.Failure(continuation, error)
            }
        }
        discarded?.let(discard)
        failureDelivery?.deliver()
    }

    private fun successDeliveryLocked(): Delivery<T>? {
        if (status != successfulStatus) return null
        val activeCandidate = candidate ?: return null
        val activeWaiter = waiter ?: return null
        successfulDeliveryStarted = true
        candidate = null
        waiter = null
        return Delivery.Success(activeWaiter, activeCandidate, discard)
    }

    private sealed interface Delivery<T : Any> {
        fun deliver()

        class Success<T : Any>(
            private val continuation: CancellableContinuation<T>,
            private val value: T,
            private val discard: (T) -> Unit,
        ) : Delivery<T> {
            override fun deliver() {
                continuation.resume(value) { _, cancelledValue, _ -> discard(cancelledValue) }
            }
        }

        class Failure<T : Any>(
            private val continuation: CancellableContinuation<T>,
            private val error: Throwable,
        ) : Delivery<T> {
            override fun deliver() {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    }
}

/**
 * Whole-buffer decoder retained only as a small unit-test oracle.
 * Production recovery uses [streamRecoveredWavePcmFrames] and never calls this function.
 */
internal fun decodeWaveTtsOutput(wave: ByteArray, outputSampleRateHz: Int): ByteArray {
    require(outputSampleRateHz > 0)
    require(wave.size >= 12 && wave.matchesAscii(0, "RIFF") && wave.matchesAscii(8, "WAVE")) {
        "Android TTS fallback output is not a WAV file"
    }
    val bytes = ByteBuffer.wrap(wave).order(ByteOrder.LITTLE_ENDIAN)
    var offset = 12
    var format: WaveFormat? = null
    var data: ByteArray? = null
    while (offset + 8 <= wave.size) {
        val chunkSize = bytes.getInt(offset + 4)
        require(chunkSize >= 0) { "Android TTS WAV chunk size is invalid" }
        val contentOffset = offset + 8
        val contentEnd = contentOffset.toLong() + chunkSize
        require(contentEnd <= wave.size) { "Android TTS WAV chunk is truncated" }
        when {
            wave.matchesAscii(offset, "fmt ") -> {
                require(chunkSize >= 16) { "Android TTS WAV format is truncated" }
                format = WaveFormat(
                    code = bytes.getShort(contentOffset).toInt() and 0xffff,
                    channelCount = bytes.getShort(contentOffset + 2).toInt() and 0xffff,
                    sampleRateHz = bytes.getInt(contentOffset + 4),
                    bitsPerSample = bytes.getShort(contentOffset + 14).toInt() and 0xffff,
                )
            }

            wave.matchesAscii(offset, "data") -> {
                data = wave.copyOfRange(contentOffset, contentEnd.toInt())
            }
        }
        offset = contentEnd.toInt() + (chunkSize and 1)
    }
    val waveFormat = requireNotNull(format) { "Android TTS WAV format chunk is missing" }
    val waveData = requireNotNull(data) { "Android TTS WAV data chunk is missing" }
    val encoding = when {
        waveFormat.code == WAVE_FORMAT_PCM && waveFormat.bitsPerSample == 8 ->
            AudioFormat.ENCODING_PCM_8BIT
        waveFormat.code == WAVE_FORMAT_PCM && waveFormat.bitsPerSample == 16 ->
            AudioFormat.ENCODING_PCM_16BIT
        waveFormat.code == WAVE_FORMAT_IEEE_FLOAT && waveFormat.bitsPerSample == 32 ->
            AudioFormat.ENCODING_PCM_FLOAT
        else -> error(
            "Unsupported Android TTS WAV format: " +
                "code=${waveFormat.code}, bits=${waveFormat.bitsPerSample}",
        )
    }
    return PcmStreamConverter(
        inputSampleRateHz = waveFormat.sampleRateHz,
        inputEncoding = encoding,
        inputChannelCount = waveFormat.channelCount,
        outputSampleRateHz = outputSampleRateHz,
    ).convert(waveData).also {
        check(it.isNotEmpty()) { "Android TTS WAV contains no PCM" }
    }
}

private data class WaveFormat(
    val code: Int,
    val channelCount: Int,
    val sampleRateHz: Int,
    val bitsPerSample: Int,
)

private fun ByteArray.matchesAscii(offset: Int, value: String): Boolean =
    offset >= 0 && offset + value.length <= size && value.indices.all { index ->
        this[offset + index].toInt() and 0xff == value[index].code
    }

private const val WAVE_FORMAT_PCM = 1
private const val WAVE_FORMAT_IEEE_FLOAT = 3

internal enum class ChineseScriptVariant {
    SIMPLIFIED,
    TRADITIONAL,
    UNSPECIFIED,
}

internal data class OfflineVoiceInfo(
    val name: String,
    val locale: Locale,
    val quality: Int = 300,
    val latency: Int = 300,
    val isNetworkConnectionRequired: Boolean = false,
    val features: Set<String> = emptySet(),
) {
    companion object {
        fun fromVoice(voice: android.speech.tts.Voice): OfflineVoiceInfo? {
            val locale = runCatching { voice.locale }.getOrNull() ?: return null
            val name = runCatching { voice.name }.getOrNull().orEmpty()
            val quality = runCatching { voice.quality }.getOrDefault(300)
            val latency = runCatching { voice.latency }.getOrDefault(300)
            val network = runCatching { voice.isNetworkConnectionRequired }.getOrDefault(false)
            val features = runCatching { voice.features }.getOrNull().orEmpty()
            return OfflineVoiceInfo(name, locale, quality, latency, network, features)
        }
    }
}

internal fun resolveChineseScriptVariant(locale: Locale, voiceName: String = ""): ChineseScriptVariant {
    val country = locale.country.uppercase(Locale.ROOT)
    val script = locale.script.uppercase(Locale.ROOT)
    val name = voiceName.lowercase(Locale.ROOT)

    if (script == "HANT" || country in setOf("TW", "HK", "MO", "TWN", "HKG", "MAC")) {
        return ChineseScriptVariant.TRADITIONAL
    }
    if (script == "HANS" || country in setOf("CN", "SG", "CHN", "SGP")) {
        return ChineseScriptVariant.SIMPLIFIED
    }
    if (name.contains("hant") || name.contains("zh-tw") || name.contains("zh_tw") ||
        name.contains("cmn-tw") || name.contains("cmn_tw") || name.contains("traditional")
    ) {
        return ChineseScriptVariant.TRADITIONAL
    }
    if (name.contains("hans") || name.contains("zh-cn") || name.contains("zh_cn") ||
        name.contains("cmn-cn") || name.contains("cmn_cn") || name.contains("simplified")
    ) {
        return ChineseScriptVariant.SIMPLIFIED
    }
    return ChineseScriptVariant.UNSPECIFIED
}

internal fun isVoiceCompatible(voice: OfflineVoiceInfo, targetLocale: Locale, languageTag: String): Boolean {
    fun canonicalLanguage(language: String): String = when (language.lowercase(Locale.ROOT)) {
        "cmn", "zho", "chi" -> "zh" // Android engines may report Mandarin using ISO-639-3.
        else -> language.lowercase(Locale.ROOT)
    }
    if (canonicalLanguage(voice.locale.language) != canonicalLanguage(targetLocale.language)) {
        return false
    }
    if (voice.isNetworkConnectionRequired) {
        return false
    }
    if (TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in voice.features) {
        return false
    }
    if (targetLocale.language.equals("zh", ignoreCase = true)) {
        val targetVariant = resolveChineseScriptVariant(targetLocale, languageTag)
        val voiceVariant = resolveChineseScriptVariant(voice.locale, voice.name)
        if (targetVariant != ChineseScriptVariant.UNSPECIFIED &&
            voiceVariant != ChineseScriptVariant.UNSPECIFIED &&
            targetVariant != voiceVariant
        ) {
            return false
        }
    }
    return true
}

internal fun scoreVoiceMatch(voice: OfflineVoiceInfo, targetLocale: Locale, languageTag: String): Int {
    var score = 0
    if (targetLocale.country.isNotBlank() &&
        voice.locale.country.equals(targetLocale.country, ignoreCase = true)
    ) {
        score += 1000
    }
    if (targetLocale.script.isNotBlank() &&
        voice.locale.script.equals(targetLocale.script, ignoreCase = true)
    ) {
        score += 1000
    }
    if (targetLocale.language.equals("zh", ignoreCase = true)) {
        val targetVariant = resolveChineseScriptVariant(targetLocale, languageTag)
        val voiceVariant = resolveChineseScriptVariant(voice.locale, voice.name)
        if (targetVariant != ChineseScriptVariant.UNSPECIFIED && targetVariant == voiceVariant) {
            score += 500
        }
        if (targetVariant == ChineseScriptVariant.UNSPECIFIED && voiceVariant == ChineseScriptVariant.SIMPLIFIED) {
            score += 200
        }
    }
    return score
}

internal fun selectBestOfflineVoiceInfo(
    availableVoices: Collection<OfflineVoiceInfo>,
    languageTag: String,
): OfflineVoiceInfo? {
    val targetLocale = Locale.forLanguageTag(languageTag)
    return availableVoices
        .asSequence()
        .filter { isVoiceCompatible(it, targetLocale, languageTag) }
        .sortedWith(
            compareByDescending<OfflineVoiceInfo> { scoreVoiceMatch(it, targetLocale, languageTag) }
                .thenByDescending { it.quality }
                .thenBy { it.latency }
                .thenBy { it.name }
        )
        .firstOrNull()
}

internal fun selectOfflineVoice(
    availableVoices: Collection<android.speech.tts.Voice>,
    languageTag: String,
): android.speech.tts.Voice? {
    val targetLocale = Locale.forLanguageTag(languageTag)
    val candidates = availableVoices.mapNotNull { voice ->
        val info = OfflineVoiceInfo.fromVoice(voice) ?: return@mapNotNull null
        if (!isVoiceCompatible(info, targetLocale, languageTag)) return@mapNotNull null
        voice to info
    }
    return candidates
        .sortedWith(
            compareByDescending<Pair<android.speech.tts.Voice, OfflineVoiceInfo>> {
                scoreVoiceMatch(it.second, targetLocale, languageTag)
            }
                .thenByDescending { it.second.quality }
                .thenBy { it.second.latency }
                .thenBy { it.second.name }
        )
        .firstOrNull()?.first
}
