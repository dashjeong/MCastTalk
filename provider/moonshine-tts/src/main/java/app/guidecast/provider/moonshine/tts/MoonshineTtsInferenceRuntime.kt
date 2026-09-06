package app.guidecast.provider.moonshine.tts

import android.content.Context
import android.os.SystemClock
import android.util.Log
import ai.moonshine.voice.AssetDownloader
import ai.moonshine.voice.ModelCache
import ai.moonshine.voice.ModelSpec
import ai.moonshine.voice.TextToSpeech
import ai.moonshine.voice.TranscriberOption
import ai.moonshine.voice.TtsSynthesisResult
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Native Moonshine work that must only live inside the isolated TTS session process. */
internal class MoonshineTtsInferenceRuntime(
    context: Context,
    private val isolatedLanguageTag: String,
) : Closeable {
    private val applicationContext = context.applicationContext
    private val languageMutexes = ConcurrentHashMap<String, Mutex>()
    // This runtime lives in a process dedicated to one language. Keep native generation serial;
    // sibling voices execute in their own processes and cannot consume this permit.
    private val nativeStreamingPermits = Semaphore(NATIVE_STREAMING_LANES)
    private val loadedEngines = ConcurrentHashMap<String, TextToSpeech>()
    /** Partially initialized owners whose native close threw; never create beside one. */
    private val unconfirmedInitializationOwners = ConcurrentHashMap<String, TextToSpeech>()
    private val outputDirectory = moonshineTtsLanguageIpcDirectory(
        applicationContext.cacheDir,
        isolatedLanguageTag,
    ).apply {
        check(mkdirs() || isDirectory) { "TTS IPC cache directory is unavailable" }
    }

    /**
     * Downloads and verifies the selected voice without constructing its native TTS engine.
     * This phase can legitimately take minutes on first install and must run before the app
     * acquires a process-wide native cold-load ticket.
     */
    suspend fun prepareAssets(
        languageTag: String,
        onProgress: (String, Int, Int, Long, Long) -> Unit,
    ) = languageMutex(languageTag).withLock {
        require(languageTag == isolatedLanguageTag) { "Moonshine TTS process language mismatch" }
        val spec = MoonshineTtsVoiceCatalog.requireVoice(languageTag)
        ensureVoiceModel(languageTag, spec, onProgress)
        Unit
    }

    /** Loads only the native engine after [prepareAssets] established the disk boundary. */
    suspend fun prepare(
        languageTag: String,
        onProgress: (String, Int, Int, Long, Long) -> Unit,
    ) = languageMutex(languageTag).withLock {
        require(languageTag == isolatedLanguageTag) { "Moonshine TTS process language mismatch" }
        val spec = MoonshineTtsVoiceCatalog.requireVoice(languageTag)
        loadEngine(languageTag, spec, onProgress)
        Unit
    }

    suspend fun synthesizeToFile(text: String, languageTag: String): String =
        languageMutex(languageTag).withLock {
            require(languageTag == isolatedLanguageTag) { "Moonshine TTS process language mismatch" }
            require(text.isNotBlank() && text.length <= MAX_TEXT_CHARACTERS) {
                "TTS text is empty or too long"
            }
            val spec = MoonshineTtsVoiceCatalog.requireVoice(languageTag)
            val startedAt = SystemClock.elapsedRealtime()
            val engine = loadEngine(languageTag, spec) {
                    file, fileIndex, fileCount, bytesRead, totalBytes ->
                val progress = downloadProgress(fileIndex, fileCount, bytesRead, totalBytes)
                Log.d(
                    LOG_TAG,
                    "TTS worker repair progress: target=$languageTag, " +
                        "progress=${(progress * 100).roundToInt()}, file=$file",
                )
            }
            Log.i(LOG_TAG, "TTS native synthesis started: target=$languageTag")
            val result = withContext(Dispatchers.Default) { engine.synthesize(text) }
            val samples = normalizeSynthesisResult(result, OUTPUT_SAMPLE_RATE_HZ)
            check(samples.size.toLong() * Short.SIZE_BYTES <= MAX_PCM_BYTES) {
                "Moonshine TTS audio exceeds the safe IPC size"
            }
            cleanupStaleFiles()
            val id = UUID.randomUUID().toString()
            val partial = File(outputDirectory, "$id.pcm.part")
            val completed = File(outputDirectory, "$id.pcm")
            try {
                withContext(Dispatchers.IO) { writePcm16Le(samples, partial) }
                check(partial.renameTo(completed)) { "TTS PCM file could not be finalized" }
                Log.i(
                    LOG_TAG,
                    "TTS worker finished: target=$languageTag, samples=${samples.size}, " +
                        "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
                )
                completed.absolutePath
            } catch (error: Throwable) {
                partial.delete()
                completed.delete()
                throw error
            }
        }

    /**
     * Uses Moonshine's incremental TTS API so a slow Galaxy does not wait for the entire phrase
     * before receiving its first playable PCM. Native production and Binder delivery are
     * decoupled by a bounded queue; delivery follows the PCM clock and can never burst overlapping
     * frames into the browser scheduler.
     */
    suspend fun synthesizeStreaming(
        text: String,
        languageTag: String,
        onProgress: (String, Int, Int, Long, Long) -> Unit,
        onPcm: (ByteArray) -> Unit,
    ) = languageMutex(languageTag).withLock {
        require(languageTag == isolatedLanguageTag) { "Moonshine TTS process language mismatch" }
        require(text.isNotBlank() && text.length <= MAX_TEXT_CHARACTERS) {
            "TTS text is empty or too long"
        }
        val spec = MoonshineTtsVoiceCatalog.requireVoice(languageTag)
        val startedAt = SystemClock.elapsedRealtime()
        val engine = loadEngine(languageTag, spec, onProgress)
        Log.i(LOG_TAG, "TTS native streaming started: target=$languageTag")

        try {
            coroutineScope {
                val pcmFrames = Channel<ByteArray>(capacity = STREAM_BUFFER_FRAMES)
                val cancellationGate = NativeStreamCancellationGate(engine::cancelStream)
                // A coroutine cancellation cannot interrupt a JNI nextChunk() call. Keep a
                // cancellation-only sibling suspended so its cancellation handler can call the
                // native API's dedicated cancelStream() escape hatch while nextChunk() is still
                // blocked. The gate defers that call until pushText/endInput have both returned,
                // so ordinary state-changing engine calls are never raced against cancellation.
                val cancellationWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
                    suspendCancellableCoroutine<Nothing> { continuation ->
                        continuation.invokeOnCancellation {
                            cancellationGate.requestCancellation()
                        }
                    }
                }
                val producer = launch(Dispatchers.Default) {
                    try {
                        nativeStreamingPermits.withPermit {
                            engine.pushText(text)
                            engine.endInput()
                            cancellationGate.markStreaming()
                            currentCoroutineContext().ensureActive()
                            var totalBytes = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val chunk = engine.nextChunk()
                                if (chunk == null) {
                                    if (!engine.isStreaming) break
                                    delay(STREAM_POLL_MILLIS)
                                    continue
                                }
                                // Moonshine may publish a final/status-only chunk. Its own Android
                                // playback path ignores these; only non-empty audio is valid PCM.
                                if (chunk.samples.isNullOrEmptyCompat()) continue
                                totalBytes = emitFloatSamplesAsPcm16LeFrames(
                                    samples = requireNotNull(chunk.samples),
                                    sourceRateHz = chunk.sampleRateHz,
                                    targetRateHz = OUTPUT_SAMPLE_RATE_HZ,
                                    emittedBytes = totalBytes,
                                    maxOutputBytes = MAX_PCM_BYTES,
                                    onFrame = pcmFrames::send,
                                )
                            }
                            check(totalBytes > 0L) { "Moonshine TTS returned no streaming audio" }
                        }
                        pcmFrames.close()
                    } catch (error: Throwable) {
                        // A validation/delivery failure can leave Moonshine's stream open even
                        // though nextChunk() returned. Cancel it on this producer thread before
                        // the warm engine is made available to the next sentence.
                        cancellationGate.requestCancellation()
                        pcmFrames.close(error)
                        throw error
                    } finally {
                        cancellationGate.markFinished()
                    }
                }

                try {
                    var frameCount = 0L
                    var nonSilent = false
                    var nextFrameAtNanos = SystemClock.elapsedRealtimeNanos()
                    for (pcm in pcmFrames) {
                        val now = SystemClock.elapsedRealtimeNanos()
                        val remainingNanos = nextFrameAtNanos - now
                        if (remainingNanos > 0L) {
                            delay((remainingNanos + NANOS_PER_MILLISECOND - 1L) /
                                NANOS_PER_MILLISECOND)
                        }
                        currentCoroutineContext().ensureActive()
                        onPcm(pcm)
                        if (frameCount == 0L) {
                            Log.i(
                                LOG_TAG,
                                "TTS worker first streaming PCM: target=$languageTag, " +
                                    "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
                            )
                        }
                        frameCount++
                        if (!nonSilent) nonSilent = pcm.hasNonSilentPcm16Le()
                        val emittedAt = SystemClock.elapsedRealtimeNanos()
                        val frameDurationNanos = (pcm.size / Short.SIZE_BYTES).toLong() *
                            NANOS_PER_SECOND / OUTPUT_SAMPLE_RATE_HZ
                        nextFrameAtNanos = maxOf(nextFrameAtNanos, emittedAt) + frameDurationNanos
                    }
                    producer.join()
                    check(frameCount > 0L) { "Moonshine TTS returned no streaming frames" }
                    check(nonSilent) { "Moonshine TTS returned silent streaming audio" }
                    Log.i(
                        LOG_TAG,
                        "TTS worker streaming finished: target=$languageTag, frames=$frameCount, " +
                            "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
                    )
                } finally {
                    if (!producer.isCompleted) cancellationGate.requestCancellation()
                    producer.cancel()
                    pcmFrames.cancel()
                    // Normal completion has already closed the gate in producer.finally. This
                    // second close makes watcher cleanup idempotent after error/cancellation.
                    cancellationGate.markFinished()
                    cancellationWatcher.cancel()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }

    private fun languageMutex(languageTag: String): Mutex =
        languageMutexes.getOrPut(languageTag) { Mutex() }

    /**
     * Keeps a verified native voice loaded for the lifetime of the isolated worker. Loading and
     * closing the same model for every sentence retained Moonshine's native allocator arena while
     * still paying the full model-load latency on every utterance.
     */
    private suspend fun loadEngine(
        languageTag: String,
        spec: MoonshineVoiceSpec,
        onProgress: (String, Int, Int, Long, Long) -> Unit,
    ): TextToSpeech {
        loadedEngines[languageTag]?.let { return it }
        check(!unconfirmedInitializationOwners.containsKey(languageTag)) {
            "Moonshine $languageTag TTS native 초기화 실패 후 종료가 확인되지 않았습니다. " +
                "이 방송에서는 Galaxy 대체 음성을 사용합니다."
        }
        requireVoiceModelPrepared(languageTag, spec)
        val startedAt = SystemClock.elapsedRealtime()
        Log.i(LOG_TAG, "TTS worker model load started: target=$languageTag, voice=${spec.voiceId}")
        val candidate = TextToSpeech(applicationContext)
            .language(spec.moonshineLanguageTag)
            .voice(spec.voiceId)
            .onProgress { fraction, file ->
                Log.d(
                    LOG_TAG,
                    "TTS worker model progress: target=$languageTag, " +
                        "progress=${(fraction * 100).roundToInt()}, file=$file",
                )
            }
        try {
            withContext(Dispatchers.IO) { candidate.load() }
            Log.i(
                LOG_TAG,
                "TTS worker model load finished: target=$languageTag, " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
            )
            loadedEngines[languageTag] = candidate
            return candidate
        } catch (error: Throwable) {
            closeOrRetainFailedMoonshineTtsInitialization(
                key = languageTag,
                candidate = candidate,
                retainedOwners = unconfirmedInitializationOwners,
                initializationFailure = error,
                closeCandidate = TextToSpeech::close,
            )
        }
    }

    /**
     * Resolves the official dependency manifest and verifies every byte count before native model
     * loading. This method deliberately lives in the private worker: getTtsDependencies() loads
     * Moonshine JNI even though it does not instantiate a TTS engine.
     */
    private suspend fun ensureVoiceModel(
        languageTag: String,
        spec: MoonshineVoiceSpec,
        onProgress: (String, Int, Int, Long, Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val modelSpec = ModelSpec.tts(spec.moonshineLanguageTag, spec.voiceId)
        val modelRoot = ModelCache.defaultRoot(applicationContext)
        val modelDirectory = ModelCache.directoryFor(applicationContext, modelSpec, null)
        val expectedFiles = resolveOfficialManifest(spec, modelDirectory)

        if (MoonshineTtsModelIntegrity.isReady(modelDirectory, expectedFiles)) {
            Log.d(LOG_TAG, "TTS voice integrity marker verified: target=$languageTag")
            return@withContext
        }

        MoonshineTtsModelIntegrity.invalidateMarker(modelDirectory)
        if (MoonshineTtsModelIntegrity.writeReadyMarkerIfFilesMatch(
                modelDirectory,
                expectedFiles,
            )
        ) {
            // A valid 0.2.3 download has no marker. Preserve its potentially hundreds of
            // megabytes and promote it only after exact manifest validation.
            Log.i(LOG_TAG, "Existing TTS voice validated without redownload: target=$languageTag")
            return@withContext
        }

        val removed = MoonshineTtsModelIntegrity.removeInvalidArtifacts(
            modelRoot = modelRoot,
            modelDirectory = modelDirectory,
            expectedFiles = expectedFiles,
        )
        if (removed > 0) {
            Log.w(LOG_TAG, "Removed $removed invalid TTS artifacts for target=$languageTag")
        }

        val downloader = AssetDownloader()
        retryMoonshineTtsDownload(
            onRetry = { nextAttempt, error ->
                val detail = error.message?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
                Log.w(
                    LOG_TAG,
                    "TTS model download interrupted; retrying $nextAttempt/" +
                        "$MAX_MODEL_DOWNLOAD_ATTEMPTS: target=$languageTag, detail=$detail",
                    error,
                )
                onProgress(
                    "다운로드 연결 복구 $nextAttempt/$MAX_MODEL_DOWNLOAD_ATTEMPTS",
                    0,
                    0,
                    0L,
                    0L,
                )
            },
        ) {
            downloader.ensureModelPresent(
                modelDirectory,
                modelSpec,
            ) { file, fileIndex, fileCount, bytesRead, totalBytes ->
                onProgress(file, fileIndex, fileCount, bytesRead, totalBytes)
            }
        }

        // Resolve again in case a dependency manifest changed during the download. A marker is
        // never written from stale expectations.
        val downloadedManifest = resolveOfficialManifest(spec, modelDirectory)
        if (!MoonshineTtsModelIntegrity.writeReadyMarkerIfFilesMatch(
                modelDirectory,
                downloadedManifest,
            )
        ) {
            MoonshineTtsModelIntegrity.removeInvalidArtifacts(
                modelRoot = modelRoot,
                modelDirectory = modelDirectory,
                expectedFiles = downloadedManifest,
            )
            error("Moonshine TTS download failed official size/hash validation")
        }
    }

    /**
     * Re-checks the small READY manifest immediately before native load, but never downloads or
     * repairs assets while a process-wide cold-load ticket is held. A removed/corrupt cache fails
     * this language into its already-supported Galaxy fallback instead of blocking every model
     * behind network or full-file integrity work.
     */
    private suspend fun requireVoiceModelPrepared(
        languageTag: String,
        spec: MoonshineVoiceSpec,
    ) = withContext(Dispatchers.IO) {
        val modelSpec = ModelSpec.tts(spec.moonshineLanguageTag, spec.voiceId)
        val modelDirectory = ModelCache.directoryFor(applicationContext, modelSpec, null)
        val expectedFiles = resolveOfficialManifest(spec, modelDirectory)
        check(MoonshineTtsModelIntegrity.isReady(modelDirectory, expectedFiles)) {
            "Moonshine $languageTag TTS assets are not prepared or failed integrity verification"
        }
    }

    private fun resolveOfficialManifest(
        spec: MoonshineVoiceSpec,
        modelDirectory: File,
    ): List<MoonshineTtsExpectedFile> {
        val options = listOf(
            TranscriberOption("g2p_root", modelDirectory.absolutePath),
            TranscriberOption("voice", spec.voiceId),
        )
        val manifest = TextToSpeech.getTtsDependencies(spec.moonshineLanguageTag, options)
        val groups = JSONObject(manifest).getJSONArray("groups")
        val manifestEntries = buildList {
            repeat(groups.length()) { groupIndex ->
                val files = groups.getJSONObject(groupIndex).getJSONArray("files")
                repeat(files.length()) { fileIndex ->
                    val entry = files.getJSONObject(fileIndex)
                    add(
                        MoonshineTtsManifestEntry(
                            relativePath = entry.getString("name"),
                            declaredSize = entry.longOrNull("size"),
                            checksum = entry.stringOrNull("checksum"),
                            checksumType = entry.stringOrNull("checksum_type"),
                        ),
                    )
                }
            }
        }
        check(manifestEntries.isNotEmpty()) { "Moonshine TTS dependency manifest is empty" }
        return reconcileMoonshineTtsManifest(spec.voiceId, manifestEntries)
    }

    private fun cleanupStaleFiles() {
        val cutoff = System.currentTimeMillis() - STALE_FILE_MILLIS
        outputDirectory.listFiles()?.forEach { file ->
            // A different language may be writing its own .part file concurrently. Delete only
            // stale files; UUID names and atomic rename keep active outputs independent.
            if (!file.isFile || file.lastModified() < cutoff) {
                file.delete()
            }
        }
    }

    override fun close() {
        closeMoonshineTtsResourceGroups(
            { closeMoonshineTtsResources(loadedEngines, TextToSpeech::close) },
            {
                closeMoonshineTtsResources(
                    unconfirmedInitializationOwners,
                    TextToSpeech::close,
                )
            },
        )
        // Keep a failed engine and its language lock reachable when close throws. The service's
        // bounded retry can then close that exact native owner instead of acknowledging cleanup and
        // creating a replacement beside an unconfirmed runtime.
        if (loadedEngines.isEmpty() && unconfirmedInitializationOwners.isEmpty()) {
            languageMutexes.clear()
        }
        deleteOwnedMoonshineTtsPartialFiles(outputDirectory)
    }

    companion object {
        private const val LOG_TAG = "GuideCastTtsWorker"
        private const val MAX_TEXT_CHARACTERS = 600
        private const val MAX_PCM_BYTES = 32L * 1024 * 1024
        private const val NATIVE_STREAMING_LANES = 1
        private const val MAX_MODEL_DOWNLOAD_ATTEMPTS = 3
        private const val STREAM_BUFFER_FRAMES = 512
        private const val STREAM_POLL_MILLIS = 5L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val STALE_FILE_MILLIS = 60L * 60 * 1_000
        const val IPC_DIRECTORY = "guidecast-tts-ipc"
        const val OUTPUT_SAMPLE_RATE_HZ = 24_000
    }
}

/**
 * Closes every tracked resource, removes only confirmed closes, and rethrows cleanup failure.
 * Retaining a failed value is what makes the service-level close retry meaningful.
 */
internal fun <K, V : Any> closeMoonshineTtsResources(
    resources: MutableMap<K, V>,
    closeResource: (V) -> Unit,
) {
    var closeFailure: Throwable? = null
    resources.entries.toList().forEach { (key, resource) ->
        try {
            closeResource(resource)
            if (resources[key] === resource) resources.remove(key)
        } catch (error: Throwable) {
            val prior = closeFailure
            if (prior == null) {
                closeFailure = error
            } else if (prior !== error) {
                runCatching { prior.addSuppressed(error) }
            }
        }
    }
    closeFailure?.let { throw it }
}

/** Attempts every ownership group but succeeds only when every native close was confirmed. */
internal fun closeMoonshineTtsResourceGroups(vararg closeGroup: () -> Unit) {
    var closeFailure: Throwable? = null
    closeGroup.forEach { close ->
        try {
            close()
        } catch (error: Throwable) {
            val prior = closeFailure
            if (prior == null) {
                closeFailure = error
            } else if (prior !== error) {
                runCatching { prior.addSuppressed(error) }
            }
        }
    }
    closeFailure?.let { throw it }
}

/**
 * A partially initialized native object may still own allocations. If cleanup is not confirmed,
 * retain that exact owner so later requests fail closed and service shutdown can retry it.
 */
internal fun <K, V : Any> closeOrRetainFailedMoonshineTtsInitialization(
    key: K,
    candidate: V,
    retainedOwners: MutableMap<K, V>,
    initializationFailure: Throwable,
    closeCandidate: (V) -> Unit,
): Nothing {
    try {
        closeCandidate(candidate)
    } catch (closeFailure: Throwable) {
        retainedOwners[key] = candidate
        throw MoonshineTtsEngineCloseNotConfirmedException(
            initializationFailure = initializationFailure,
            closeFailure = closeFailure,
        )
    }
    throw initializationFailure
}

internal class MoonshineTtsEngineCloseNotConfirmedException(
    initializationFailure: Throwable,
    closeFailure: Throwable,
) : IllegalStateException(
        "Moonshine TTS native 초기화에 실패했고 부분 runtime 종료도 확인되지 않았습니다.",
        initializationFailure,
    ) {
    init {
        if (closeFailure !== initializationFailure) addSuppressed(closeFailure)
    }
}

internal fun moonshineTtsLanguageIpcDirectory(cacheDirectory: File, languageTag: String): File {
    require(languageTag in MoonshineTtsVoiceCatalog.voices) {
        "Unsupported isolated Moonshine TTS language"
    }
    return File(File(cacheDirectory, MoonshineTtsInferenceRuntime.IPC_DIRECTORY), languageTag)
}

internal fun deleteOwnedMoonshineTtsPartialFiles(outputDirectory: File) {
    outputDirectory.listFiles()
        ?.filter { it.isFile && it.name.endsWith(".part") }
        ?.forEach(File::delete)
}

/** Retries only downloader I/O failures; manifest, native, and model errors fail immediately. */
internal suspend fun <T> retryMoonshineTtsDownload(
    maxAttempts: Int = 3,
    retryDelayMillis: (completedAttempts: Int) -> Long = { completedAttempts ->
        completedAttempts * 750L
    },
    onRetry: (nextAttempt: Int, error: IOException) -> Unit = { _, _ -> },
    operation: () -> T,
): T {
    require(maxAttempts > 0) { "Moonshine download attempts must be positive" }
    repeat(maxAttempts) { attemptIndex ->
        try {
            return operation()
        } catch (error: IOException) {
            val completedAttempts = attemptIndex + 1
            if (completedAttempts >= maxAttempts ||
                !isTransientMoonshineDownloadFailure(error)
            ) {
                throw error
            }
            onRetry(completedAttempts + 1, error)
            delay(retryDelayMillis(completedAttempts).coerceAtLeast(0L))
        }
    }
    error("Moonshine download retry loop ended unexpectedly")
}

/** Permanent HTTP, manifest, storage, path, and permission failures must surface immediately. */
internal fun isTransientMoonshineDownloadFailure(error: IOException): Boolean =
    error is SocketTimeoutException ||
        error is ConnectException ||
        error is SocketException ||
        error is EOFException ||
        (HTTP_STATUS_PATTERN.find(error.message.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.let { status ->
                status == 408 || status == 425 || status == 429 || status in 500..599
            }
            ?: false)

private val HTTP_STATUS_PATTERN = Regex("^HTTP\\s+(\\d{3})\\b")

/**
 * Allows only Moonshine's cancellation-specific API to overlap a blocking nextChunk() call.
 * Cancellation during pushText/endInput is remembered and executed by the producer thread after
 * those calls return; normal completion permanently closes the gate.
 */
internal class NativeStreamCancellationGate(
    private val cancelStream: () -> Unit,
) {
    private enum class State {
        STARTING,
        CANCELLATION_PENDING,
        STREAMING,
        CANCELLATION_SENT,
        FINISHED,
    }

    private val state = AtomicReference(State.STARTING)

    fun requestCancellation() {
        while (true) {
            when (val current = state.get()) {
                State.STARTING -> if (
                    state.compareAndSet(current, State.CANCELLATION_PENDING)
                ) return

                State.CANCELLATION_PENDING,
                State.CANCELLATION_SENT,
                State.FINISHED -> return

                State.STREAMING -> if (state.compareAndSet(current, State.CANCELLATION_SENT)) {
                    runCatching(cancelStream)
                    return
                }
            }
        }
    }

    fun markStreaming() {
        while (true) {
            when (val current = state.get()) {
                State.STARTING -> if (state.compareAndSet(current, State.STREAMING)) return
                State.CANCELLATION_PENDING -> if (
                    state.compareAndSet(current, State.CANCELLATION_SENT)
                ) {
                    runCatching(cancelStream)
                    return
                }

                State.STREAMING,
                State.CANCELLATION_SENT,
                State.FINISHED -> return
            }
        }
    }

    fun markFinished() {
        state.set(State.FINISHED)
    }
}

/** Keeps the native result's declared rate coupled to the samples that are normalized for IPC. */
internal fun normalizeSynthesisResult(
    result: TtsSynthesisResult,
    targetRateHz: Int,
): FloatArray {
    check(result.samples.isNotEmpty() && result.sampleRateHz > 0) {
        "Moonshine TTS returned no audio"
    }
    return if (result.sampleRateHz == targetRateHz) {
        result.samples
    } else {
        resampleLinear(result.samples, result.sampleRateHz, targetRateHz)
    }
}

/**
 * Resamples a native FloatArray directly into ordered 20 ms PCM S16LE frames.
 *
 * The complete output size is checked with Long arithmetic before the first frame is allocated
 * or published. Only the current frame and the bounded channel backlog are resident; there is no
 * second FloatArray, phrase-sized PCM ByteArray, or List<ByteArray> transient amplification.
 */
internal suspend fun emitFloatSamplesAsPcm16LeFrames(
    samples: FloatArray,
    sourceRateHz: Int,
    targetRateHz: Int,
    emittedBytes: Long,
    maxOutputBytes: Long,
    onFrame: suspend (ByteArray) -> Unit,
): Long {
    require(samples.isNotEmpty())
    require(sourceRateHz > 0 && targetRateHz > 0)
    require(emittedBytes >= 0L)
    require(maxOutputBytes > 0L)
    check(emittedBytes <= maxOutputBytes) {
        "Moonshine TTS audio exceeds the safe IPC size"
    }

    val outputSampleCountLong = if (sourceRateHz == targetRateHz || samples.size == 1) {
        samples.size.toLong()
    } else {
        (samples.size.toLong() * targetRateHz.toLong() / sourceRateHz.toLong()).coerceAtLeast(1L)
    }
    check(outputSampleCountLong <= Int.MAX_VALUE.toLong()) {
        "Moonshine TTS audio exceeds the safe IPC size"
    }
    val outputBytes = outputSampleCountLong * Short.SIZE_BYTES.toLong()
    check(outputBytes > 0L && outputBytes <= maxOutputBytes - emittedBytes) {
        "Moonshine TTS audio exceeds the safe IPC size"
    }
    val samplesPerFrameLong = targetRateHz.toLong() * TTS_FRAME_MILLIS / 1_000L
    check(samplesPerFrameLong in 1..(Int.MAX_VALUE / Short.SIZE_BYTES).toLong()) {
        "Moonshine TTS sample rate cannot be framed safely"
    }
    val outputSampleCount = outputSampleCountLong.toInt()
    val samplesPerFrame = samplesPerFrameLong.toInt()
    val resamplingRatio = sourceRateHz.toDouble() / targetRateHz.toDouble()

    var outputOffset = 0
    while (outputOffset < outputSampleCount) {
        val frameSampleCount = minOf(samplesPerFrame, outputSampleCount - outputOffset)
        val frame = ByteArray(frameSampleCount * Short.SIZE_BYTES)
        var frameSampleIndex = 0
        while (frameSampleIndex < frameSampleCount) {
            val outputIndex = outputOffset + frameSampleIndex
            val floatSample = when {
                sourceRateHz == targetRateHz -> samples[outputIndex]
                samples.size == 1 -> samples[0]
                else -> {
                    val position = outputIndex * resamplingRatio
                    val left = floor(position).toInt().coerceIn(0, samples.lastIndex)
                    val right = (left + 1).coerceAtMost(samples.lastIndex)
                    val fraction = (position - left).toFloat()
                    samples[left] + (samples[right] - samples[left]) * fraction
                }
            }
            val sample = floatSampleToPcm16(floatSample)
            val byteOffset = frameSampleIndex * Short.SIZE_BYTES
            frame[byteOffset] = (sample.toInt() and 0xff).toByte()
            frame[byteOffset + 1] = ((sample.toInt() ushr 8) and 0xff).toByte()
            frameSampleIndex++
        }
        onFrame(frame)
        outputOffset += frameSampleCount
    }

    return emittedBytes + outputBytes
}

private fun ByteArray.hasNonSilentPcm16Le(): Boolean {
    var offset = 0
    while (offset < size) {
        if (this[offset].toInt() != 0 || this[offset + 1].toInt() != 0) return true
        offset += Short.SIZE_BYTES
    }
    return false
}

private fun FloatArray?.isNullOrEmptyCompat(): Boolean = this == null || isEmpty()

private fun JSONObject.stringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) getString(key) else null

private fun JSONObject.longOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) getLong(key) else null

internal fun downloadProgress(
    fileIndex: Int,
    fileCount: Int,
    bytesRead: Long,
    totalBytes: Long,
): Float {
    val currentFileProgress = if (totalBytes > 0L) {
        bytesRead.toDouble() / totalBytes.toDouble()
    } else {
        0.0
    }
    return if (fileCount > 0) {
        (((fileIndex - 1).coerceAtLeast(0).toDouble() + currentFileProgress) / fileCount.toDouble())
            .toFloat()
            .coerceIn(0f, 1f)
    } else {
        currentFileProgress.toFloat().coerceIn(0f, 1f)
    }
}

private fun writePcm16Le(samples: FloatArray, destination: File) {
    FileOutputStream(destination).buffered().use { output ->
        val buffer = ByteBuffer.allocate(8 * 1_024).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { floatSample ->
            if (buffer.remaining() < Short.SIZE_BYTES) {
                output.write(buffer.array(), 0, buffer.position())
                buffer.clear()
            }
            buffer.putShort(floatSampleToPcm16(floatSample))
        }
        if (buffer.position() > 0) output.write(buffer.array(), 0, buffer.position())
    }
}

private fun floatSampleToPcm16(floatSample: Float): Short =
    (floatSample.coerceIn(-1f, 1f) * 32_767f)
        .roundToInt()
        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        .toShort()
