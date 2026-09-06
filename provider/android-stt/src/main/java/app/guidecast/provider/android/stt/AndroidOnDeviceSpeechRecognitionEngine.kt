package app.guidecast.provider.android.stt

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.ModelDownloadListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import app.guidecast.core.stream.PcmAudioFrame
import app.guidecast.core.translation.RecognizedUtterance
import app.guidecast.core.translation.SpeechRecognitionConfig
import app.guidecast.core.translation.SpeechRecognitionEngine
import app.guidecast.core.translation.normalizeSpeechRecognitionBiasingPhrases
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class OnDeviceSpeechRecognitionCapability(
    val available: Boolean,
    val reason: String? = null,
)

enum class SpeechLanguageReadiness {
    READY,
    DOWNLOAD_SCHEDULED,
    DOWNLOAD_REQUIRED,
    UNSUPPORTED,
}

data class SpeechLanguageStatus(
    val readiness: SpeechLanguageReadiness,
    val message: String,
) {
    val isReady: Boolean get() = readiness == SpeechLanguageReadiness.READY
}

/** Preserves the platform error code so the app can recover without parsing a localized message. */
class AndroidSpeechRecognitionException(
    val errorCode: Int,
    message: String,
) : IllegalStateException(message)

/** Advisory OEM endpoint hints; semantic finality remains owned by the app-level assembler. */
internal object AndroidRecognitionPausePolicy {
    const val MINIMUM_SPEECH_MILLIS = 900L
    const val POSSIBLY_COMPLETE_SILENCE_MILLIS = 900L
    const val COMPLETE_SILENCE_MILLIS = 1_200L
}

/** Keeps an OEM rejection isolated to optional hints for the lifetime of this provider instance. */
internal class AndroidSpeechBiasingPolicy {
    private val hintsEnabled = AtomicBoolean(true)

    fun intentExtra(
        config: SpeechRecognitionConfig,
        sdkInt: Int,
    ): ArrayList<String>? {
        if (
            sdkInt < Build.VERSION_CODES.TIRAMISU ||
            !hintsEnabled.get()
        ) {
            return null
        }
        return try {
            val phrases = config.biasingPhrases
            if (phrases.isEmpty()) {
                null
            } else {
                ArrayList(normalizeSpeechRecognitionBiasingPhrases(phrases))
            }
        } catch (_: RuntimeException) {
            // Cached data may have been mutated after config construction. Optional hints must
            // never make the otherwise valid offline recognition request fail.
            hintsEnabled.set(false)
            null
        }
    }

    fun onSynchronousHintedStartFailure() {
        hintsEnabled.set(false)
    }

    fun onRecognitionError(
        errorCode: Int,
        requestHadHints: Boolean,
    ) {
        if (requestHadHints && errorCode == SpeechRecognizer.ERROR_CLIENT) {
            hintsEnabled.set(false)
        }
    }
}

/** Starts once with hints, then retries once on a fresh recognizer without optional hints. */
internal inline fun <T> startRecognitionWithBiasFallback(
    biasingStrings: ArrayList<String>?,
    createRecognizer: () -> T,
    startListening: (T, ArrayList<String>?) -> Unit,
    destroyRecognizer: (T) -> Unit,
    onHintedStartFailure: () -> Unit,
): T {
    val hintedRecognizer = createRecognizer()
    try {
        startListening(hintedRecognizer, biasingStrings)
        return hintedRecognizer
    } catch (firstFailure: RuntimeException) {
        runCatching { destroyRecognizer(hintedRecognizer) }
        if (biasingStrings == null) throw firstFailure
        onHintedStartFailure()
        val fallbackRecognizer = createRecognizer()
        try {
            startListening(fallbackRecognizer, null)
            return fallbackRecognizer
        } catch (fallbackFailure: RuntimeException) {
            runCatching { destroyRecognizer(fallbackRecognizer) }
            fallbackFailure.addSuppressed(firstFailure)
            throw fallbackFailure
        }
    }
}

/** Serializes callback ownership so a destroyed recognizer cannot affect its replacement. */
internal class RecognitionCallbackGeneration {
    class Token internal constructor()

    private val lock = Any()
    private var activeToken: Token? = null

    fun activate(): Token = Token().also { token ->
        synchronized(lock) {
            activeToken = token
        }
    }

    fun dispatch(
        token: Token,
        callback: () -> Unit,
    ) {
        synchronized(lock) {
            if (activeToken === token) callback()
        }
    }

    fun invalidateBeforeDestroy(
        token: Token,
        destroy: () -> Unit,
    ) {
        synchronized(lock) {
            if (activeToken === token) activeToken = null
        }
        destroy()
    }
}

private data class GenerationBoundSpeechRecognizer(
    val recognizer: SpeechRecognizer,
    val callbackToken: RecognitionCallbackGeneration.Token,
)

class AndroidOnDeviceSpeechRecognitionEngine(
    context: Context,
) : SpeechRecognitionEngine {
    private val applicationContext = context.applicationContext
    private val speechBiasingPolicy = AndroidSpeechBiasingPolicy()

    fun capability(): OnDeviceSpeechRecognitionCapability = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ->
            OnDeviceSpeechRecognitionCapability(
                available = false,
                reason = "마이크 PCM 입력 번역은 Android 13 이상이 필요합니다.",
            )

        !SpeechRecognizer.isOnDeviceRecognitionAvailable(applicationContext) ->
            OnDeviceSpeechRecognitionCapability(
                available = false,
                reason = "기기에 온디바이스 음성인식 서비스가 설치되어 있지 않습니다.",
            )

        else -> OnDeviceSpeechRecognitionCapability(available = true)
    }

    suspend fun languageStatus(languageTag: String): SpeechLanguageStatus {
        val capability = capability()
        if (!capability.available) {
            return SpeechLanguageStatus(
                SpeechLanguageReadiness.UNSUPPORTED,
                capability.reason ?: "온디바이스 음성인식을 사용할 수 없습니다.",
            )
        }
        val support = withTimeout(5_000L) { queryRecognitionSupport(languageTag) }
        val requestedLanguage = Locale.forLanguageTag(languageTag).language
        fun List<String>.containsRequestedLanguage(): Boolean = any {
            Locale.forLanguageTag(it).language == requestedLanguage
        }
        return when {
            support.installedOnDeviceLanguages.containsRequestedLanguage() ->
                SpeechLanguageStatus(
                    SpeechLanguageReadiness.READY,
                    "${languageTag.recognitionLanguageLabel()} 오프라인 음성인식 준비됨",
                )

            support.pendingOnDeviceLanguages.containsRequestedLanguage() ->
                SpeechLanguageStatus(
                    SpeechLanguageReadiness.DOWNLOAD_SCHEDULED,
                    "${languageTag.recognitionLanguageLabel()} 음성 모델을 다운로드 중입니다. " +
                        "완료 후 다시 확인하세요.",
                )

            support.supportedOnDeviceLanguages.containsRequestedLanguage() ->
                SpeechLanguageStatus(
                    SpeechLanguageReadiness.DOWNLOAD_REQUIRED,
                    "${languageTag.recognitionLanguageLabel()} 오프라인 음성 모델 다운로드가 필요합니다.",
                )

            else -> SpeechLanguageStatus(
                SpeechLanguageReadiness.UNSUPPORTED,
                "이 기기의 온디바이스 음성인식기가 " +
                    "${languageTag.recognitionLanguageLabel()}를 지원하지 않습니다.",
            )
        }
    }

    suspend fun prepareLanguage(languageTag: String): SpeechLanguageStatus {
        val current = languageStatus(languageTag)
        if (current.readiness != SpeechLanguageReadiness.DOWNLOAD_REQUIRED) return current

        val intent = baseRecognizerIntent(languageTag)
        return withContext(Dispatchers.Main.immediate) {
            val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(applicationContext)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    suspendCancellableCoroutine { continuation ->
                        recognizer.triggerModelDownload(
                            intent,
                            applicationContext.mainExecutor,
                            object : ModelDownloadListener {
                                override fun onProgress(completedPercent: Int) = Unit

                                override fun onSuccess() {
                                    if (continuation.isActive) {
                                        continuation.resume(
                                            SpeechLanguageStatus(
                                                SpeechLanguageReadiness.READY,
                                                "${languageTag.recognitionLanguageLabel()} " +
                                                    "오프라인 음성인식 준비됨",
                                            ),
                                        )
                                    }
                                }

                                override fun onScheduled() {
                                    if (continuation.isActive) {
                                        continuation.resume(
                                            SpeechLanguageStatus(
                                                SpeechLanguageReadiness.DOWNLOAD_SCHEDULED,
                                                "${languageTag.recognitionLanguageLabel()} " +
                                                    "음성 모델 다운로드를 예약했습니다.",
                                            ),
                                        )
                                    }
                                }

                                override fun onError(error: Int) {
                                    if (continuation.isActive) {
                                        continuation.resumeWithException(
                                            IllegalStateException(
                                                "${languageTag.recognitionLanguageLabel()} " +
                                                    "음성 모델 다운로드 오류: $error",
                                            ),
                                        )
                                    }
                                }
                            },
                        )
                    }
                } else {
                    recognizer.triggerModelDownload(intent)
                    SpeechLanguageStatus(
                        SpeechLanguageReadiness.DOWNLOAD_SCHEDULED,
                        "${languageTag.recognitionLanguageLabel()} 음성 모델 다운로드를 요청했습니다. " +
                            "완료 후 다시 확인하세요.",
                    )
                }
            } finally {
                recognizer.destroy()
            }
        }
    }

    override fun recognize(
        frames: Flow<PcmAudioFrame>,
        config: SpeechRecognitionConfig,
    ): Flow<RecognizedUtterance> = callbackFlow {
        val capability = capability()
        check(capability.available) { capability.reason ?: "온디바이스 음성인식을 사용할 수 없습니다." }

        val pipe = ParcelFileDescriptor.createPipe()
        val recognitionInput = pipe[0]
        val audioOutput = pipe[1]
        val sequence = AtomicLong(0)
        var lastSegmentText: String? = null

        fun emitResult(
            results: Bundle,
            isFinal: Boolean,
            isSegment: Boolean = false,
            isSessionFinal: Boolean = false,
        ) {
            val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: return
            if (isSessionFinal && text == lastSegmentText) return
            if (isSegment) lastSegmentText = text
            val activeSequence = sequence.get()
            trySend(
                RecognizedUtterance(
                    sequence = activeSequence,
                    text = text.take(MAX_UTTERANCE_CHARACTERS),
                    sourceLanguageTag = config.sourceLanguageTag,
                    isFinal = isFinal,
                    capturedAtElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
                ),
            )
            if (isFinal) sequence.compareAndSet(activeSequence, activeSequence + 1)
        }

        val biasingStrings = speechBiasingPolicy.intentExtra(config, Build.VERSION.SDK_INT)
        val activeRequestHadHints = AtomicBoolean(biasingStrings != null)
        val recognizerClosing = AtomicBoolean(false)
        val callbackGeneration = RecognitionCallbackGeneration()

        fun listenerFor(token: RecognitionCallbackGeneration.Token): RecognitionListener =
            object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) =
                    callbackGeneration.dispatch(token) {}

                override fun onBeginningOfSpeech() = callbackGeneration.dispatch(token) {}

                override fun onRmsChanged(rmsdB: Float) = callbackGeneration.dispatch(token) {}

                override fun onBufferReceived(buffer: ByteArray?) =
                    callbackGeneration.dispatch(token) {}

                override fun onEndOfSpeech() = callbackGeneration.dispatch(token) {}

                override fun onError(error: Int) = callbackGeneration.dispatch(token) {
                    if (!recognizerClosing.get()) {
                        speechBiasingPolicy.onRecognitionError(
                            errorCode = error,
                            requestHadHints = activeRequestHadHints.get(),
                        )
                    }
                    callbackGeneration.invalidateBeforeDestroy(token) {}
                    close(
                        AndroidSpeechRecognitionException(
                            error,
                            error.messageForRecognitionError(config.sourceLanguageTag),
                        ),
                    )
                }

                override fun onResults(results: Bundle) = callbackGeneration.dispatch(token) {
                    emitResult(results, isFinal = true, isSessionFinal = true)
                    callbackGeneration.invalidateBeforeDestroy(token) {}
                    close()
                }

                override fun onPartialResults(partialResults: Bundle) =
                    callbackGeneration.dispatch(token) {
                        emitResult(partialResults, isFinal = false)
                    }

                override fun onEvent(eventType: Int, params: Bundle?) =
                    callbackGeneration.dispatch(token) {}

                override fun onLanguageDetection(results: Bundle) =
                    callbackGeneration.dispatch(token) {}

                override fun onSegmentResults(segmentResults: Bundle) =
                    callbackGeneration.dispatch(token) {
                        emitResult(segmentResults, isFinal = true, isSegment = true)
                    }

                override fun onEndOfSegmentedSession() = callbackGeneration.dispatch(token) {
                    callbackGeneration.invalidateBeforeDestroy(token) {}
                    close()
                }
            }

        val activeRecognizer = try {
            withContext(Dispatchers.Main.immediate) {
                startRecognitionWithBiasFallback(
                    biasingStrings = biasingStrings,
                    createRecognizer = {
                        val recognizer =
                            SpeechRecognizer.createOnDeviceSpeechRecognizer(applicationContext)
                        val token = callbackGeneration.activate()
                        try {
                            recognizer.setRecognitionListener(listenerFor(token))
                            GenerationBoundSpeechRecognizer(recognizer, token)
                        } catch (error: RuntimeException) {
                            callbackGeneration.invalidateBeforeDestroy(token) {
                                runCatching { recognizer.destroy() }
                            }
                            throw error
                        }
                    },
                    startListening = { activeRecognizer, activeBiasingStrings ->
                        activeRecognizer.recognizer.startListening(
                            config.toRecognizerIntent(recognitionInput, activeBiasingStrings),
                        )
                    },
                    destroyRecognizer = { recognizer ->
                        callbackGeneration.invalidateBeforeDestroy(recognizer.callbackToken) {
                            recognizer.recognizer.destroy()
                        }
                    },
                    onHintedStartFailure = {
                        activeRequestHadHints.set(false)
                        speechBiasingPolicy.onSynchronousHintedStartFailure()
                    },
                )
            }
        } catch (error: RuntimeException) {
            runCatching { audioOutput.close() }
            runCatching { recognitionInput.close() }
            throw error
        }

        val writerJob: Job = launch(Dispatchers.IO) {
            FileOutputStream(audioOutput.fileDescriptor).use { output ->
                frames.collect { frame ->
                    check(frame.bytes.size % Short.SIZE_BYTES == 0) {
                        "Speech recognition requires PCM 16-bit frames"
                    }
                    output.write(frame.bytes)
                }
            }
        }

        writerJob.invokeOnCompletion { error ->
            runCatching { audioOutput.close() }
            if (error != null) close(error)
        }

        awaitClose {
            recognizerClosing.set(true)
            writerJob.cancel()
            runCatching { audioOutput.close() }
            runCatching { recognitionInput.close() }
            applicationContext.mainExecutor.execute {
                callbackGeneration.invalidateBeforeDestroy(activeRecognizer.callbackToken) {
                    activeRecognizer.recognizer.cancel()
                    activeRecognizer.recognizer.destroy()
                }
            }
        }
    }

    private fun SpeechRecognitionConfig.toRecognizerIntent(
        audioSource: ParcelFileDescriptor,
        biasingStrings: ArrayList<String>?,
    ): Intent = baseRecognizerIntent(sourceLanguageTag).apply {
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, audioSource)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, channelCount)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, sampleRateHz)
        if (biasingStrings != null) {
            putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, biasingStrings)
        }
        // For an injected audio source the Android contract requires the segmented-session mode
        // itself to be EXTRA_AUDIO_SOURCE. The former silence-extra value is valid only for a live
        // recognizer microphone session and Samsung's recognizer rejects that mixed request with
        // ERROR_CLIENT before consuming any PCM.
        // Match the app's conservative phrase/sentence pause ladder. These OEM hints remain
        // advisory, and onSegmentResults is still treated as provider stability rather than a
        // translation-ready sentence by GalaxySpeechRecognitionEngine.
        putExtra(
            RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
            AndroidRecognitionPausePolicy.MINIMUM_SPEECH_MILLIS,
        )
        putExtra(
            RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
            AndroidRecognitionPausePolicy.POSSIBLY_COMPLETE_SILENCE_MILLIS,
        )
        putExtra(
            RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
            AndroidRecognitionPausePolicy.COMPLETE_SILENCE_MILLIS,
        )
        putExtra(
            RecognizerIntent.EXTRA_SEGMENTED_SESSION,
            RecognizerIntent.EXTRA_AUDIO_SOURCE,
        )
    }

    private fun baseRecognizerIntent(languageTag: String): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Ask supporting Galaxy recognizers for low-latency internal punctuation while
                // preventing a speculative trailing period from turning every partial result
                // into a false sentence boundary. OEM services may legally ignore both hints.
                putExtra(
                    RecognizerIntent.EXTRA_ENABLE_FORMATTING,
                    RecognizerIntent.FORMATTING_OPTIMIZE_LATENCY,
                )
                putExtra(RecognizerIntent.EXTRA_HIDE_PARTIAL_TRAILING_PUNCTUATION, true)
            }
        }

    private suspend fun queryRecognitionSupport(languageTag: String): RecognitionSupport =
        withContext(Dispatchers.Main.immediate) {
            val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(applicationContext)
            try {
                suspendCancellableCoroutine { continuation ->
                    recognizer.checkRecognitionSupport(
                        baseRecognizerIntent(languageTag),
                        applicationContext.mainExecutor,
                        object : RecognitionSupportCallback {
                            override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                                if (continuation.isActive) continuation.resume(recognitionSupport)
                            }

                            override fun onError(error: Int) {
                                if (continuation.isActive) {
                                    continuation.resumeWithException(
                                        IllegalStateException("음성인식 지원 확인 오류: $error"),
                                    )
                                }
                            }
                        },
                    )
                }
            } finally {
                recognizer.destroy()
            }
        }

    private fun Int.messageForRecognitionError(languageTag: String): String = when (this) {
        SpeechRecognizer.ERROR_AUDIO -> "음성인식 오디오 입력 오류"
        SpeechRecognizer.ERROR_CLIENT -> "음성인식 요청이 취소되었습니다."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "음성인식 마이크 권한이 없습니다."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
            "${languageTag.recognitionLanguageLabel()} 음성인식을 지원하지 않습니다."
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            "${languageTag.recognitionLanguageLabel()} 오프라인 음성 모델이 설치되지 않았습니다."
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        -> "오프라인 음성인식기가 네트워크를 요청했습니다."

        SpeechRecognizer.ERROR_NO_MATCH -> "음성을 인식하지 못했습니다."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "음성인식기가 사용 중입니다."
        SpeechRecognizer.ERROR_SERVER,
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
        -> "온디바이스 음성인식 서비스 오류"

        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "음성 입력 시간이 초과되었습니다."
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "음성인식 요청이 너무 많습니다."
        else -> "음성인식 오류: $this"
    }

    private companion object {
        const val MAX_UTTERANCE_CHARACTERS = 2_000
    }
}

private fun String.recognitionLanguageLabel(): String =
    Locale.forLanguageTag(this).getDisplayLanguage(Locale.KOREAN)
        .takeIf(String::isNotBlank)
        ?: substringBefore('-')
