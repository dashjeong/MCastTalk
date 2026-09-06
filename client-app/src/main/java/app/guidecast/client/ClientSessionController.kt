package app.guidecast.client

import app.guidecast.core.stream.PcmAudioFrame
import app.guidecast.core.translation.RecognizedUtterance
import app.guidecast.core.translation.SpeechRecognitionConfig
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ClientSessionPhase {
    IDLE,
    PREPARING_MODELS,
    CONNECTING,
    LISTENING,
    PAUSED,
    ERROR,
}

data class ClientUiState(
    val address: String = "http://192.168.43.1:8787/",
    val pin: String = "",
    val target: ClientTargetLanguage = ClientTargetLanguage.JAPANESE,
    val phase: ClientSessionPhase = ClientSessionPhase.IDLE,
    val statusMessage: String = "송출기 주소와 언어를 선택하세요.",
    val modelReady: Boolean = false,
    val modelProgress: Float? = null,
    val originalTranscript: String = "",
    val translatedTranscript: String = "",
    val translationLatencyMillis: Long? = null,
    val receivedFrames: Long = 0,
    val errorMessage: String? = null,
) {
    val sessionActive: Boolean
        get() = phase == ClientSessionPhase.CONNECTING ||
            phase == ClientSessionPhase.LISTENING ||
            phase == ClientSessionPhase.PAUSED
}

class ClientSessionController(
    private val application: GuideCastClientApplication,
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val paused = AtomicBoolean(false)
    private val readyTargets = mutableSetOf<ClientTargetLanguage>()
    private val mutableState = MutableStateFlow(ClientUiState())
    val state: StateFlow<ClientUiState> = mutableState.asStateFlow()
    private var modelJob: Job? = null
    private var sessionJob: Job? = null
    private var audioPlayer: PcmAudioPlayer? = null

    fun updateAddress(value: String) {
        if (state.value.sessionActive) return
        mutableState.update { it.copy(address = value.take(2_048), errorMessage = null) }
    }

    fun updatePin(value: String) {
        if (state.value.sessionActive) return
        mutableState.update { it.copy(pin = value.filter(Char::isDigit).take(8), errorMessage = null) }
    }

    fun selectTarget(target: ClientTargetLanguage) {
        if (state.value.sessionActive) return
        mutableState.update {
            it.copy(
                target = target,
                modelReady = target in readyTargets,
                statusMessage = if (target in readyTargets) {
                    "${target.displayName} 언어팩 준비됨"
                } else {
                    "${target.displayName} 언어팩을 준비하세요."
                },
                errorMessage = null,
            )
        }
    }

    fun prepareSelectedModels() {
        if (modelJob?.isActive == true || state.value.sessionActive) return
        val target = state.value.target
        modelJob = scope.launch {
            runCatching { prepareModels(target) }
                .onSuccess {
                    mutableState.update {
                        it.copy(
                            phase = ClientSessionPhase.IDLE,
                            statusMessage = "${target.displayName} 오프라인 언어팩 준비 완료",
                            modelReady = true,
                            modelProgress = 1f,
                            errorMessage = null,
                        )
                    }
                }
                .onFailure(::reportError)
        }
    }

    fun start(address: String, pin: String, target: ClientTargetLanguage) {
        modelJob?.cancel()
        modelJob = null
        stopSession(resetMessage = false)
        mutableState.update {
            it.copy(
                address = address,
                pin = pin,
                target = target,
                phase = ClientSessionPhase.CONNECTING,
                statusMessage = "클라이언트 통역을 준비하고 있습니다.",
                originalTranscript = "",
                translatedTranscript = "",
                translationLatencyMillis = null,
                receivedFrames = 0,
                errorMessage = null,
            )
        }
        paused.set(false)
        sessionJob = scope.launch {
            try {
                prepareModels(target)
                runSession(address, pin, target)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                reportError(error)
            }
        }
    }

    fun pause() {
        if (state.value.phase != ClientSessionPhase.LISTENING) return
        paused.set(true)
        audioPlayer?.pauseAndFlush()
        mutableState.update {
            it.copy(phase = ClientSessionPhase.PAUSED, statusMessage = "통역 청취 일시정지")
        }
    }

    fun resume() {
        if (state.value.phase != ClientSessionPhase.PAUSED) return
        paused.set(false)
        audioPlayer?.resume()
        mutableState.update {
            it.copy(phase = ClientSessionPhase.LISTENING, statusMessage = "실시간 통역 청취 중")
        }
    }

    fun stop() {
        stopSession(resetMessage = true)
    }

    private suspend fun prepareModels(target: ClientTargetLanguage) {
        if (target in readyTargets) return
        mutableState.update {
            it.copy(
                phase = ClientSessionPhase.PREPARING_MODELS,
                statusMessage = "한국어 음성인식 언어팩 확인 중",
                modelProgress = null,
                errorMessage = null,
            )
        }
        val speechStatus = application.speechRecognitionEngine.prepareLanguage("ko-KR")
        check(speechStatus.isReady) { speechStatus.message }
        mutableState.update { it.copy(statusMessage = "${target.displayName} 번역 언어팩 확인 중") }
        application.translationProvider.modelManager.prepare(setOf(target.translationTag))
        mutableState.update { it.copy(statusMessage = "${target.displayName} Moonshine 음성팩 확인 중") }
        application.speechSynthesisProvider.prepare(listOf(target.ttsTag))
        readyTargets += target
        mutableState.update { it.copy(modelReady = true, modelProgress = 1f) }
    }

    private suspend fun runSession(
        address: String,
        pin: String,
        target: ClientTargetLanguage,
    ) = coroutineScope {
        val audioFrames = Channel<PcmAudioFrame>(
            capacity = 16,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val utterances = Channel<RecognizedUtterance>(
            capacity = 2,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val player = PcmAudioPlayer().also { audioPlayer = it }
        val remote = GuideCastRemoteClient()
        var frameCount = 0L

        try {
            val recognition = launch {
                application.speechRecognitionEngine.recognize(
                    frames = audioFrames.receiveAsFlow(),
                    config = SpeechRecognitionConfig(sourceLanguageTag = "ko-KR"),
                ).collect { utterance ->
                    if (paused.get()) return@collect
                    mutableState.update { it.copy(originalTranscript = utterance.text) }
                    if (utterance.isFinal) utterances.trySend(utterance)
                }
            }
            val translation = launch {
                val translator = application.translationProvider.engineFor(target.translationTag)
                val synthesizer = application.speechSynthesisProvider.engineFor(target.ttsTag)
                for (utterance in utterances) {
                    if (paused.get()) continue
                    val mark = TimeSource.Monotonic.markNow()
                    val translated = target.postProcess(
                        translator.translate(
                            utterance.text,
                            utterance.sourceLanguageTag,
                            target.translationTag,
                        ),
                    )
                    mutableState.update {
                        it.copy(
                            translatedTranscript = translated,
                            translationLatencyMillis = mark.elapsedNow().inWholeMilliseconds,
                        )
                    }
                    synthesizer.synthesize(translated, target.ttsTag).collect { frame ->
                        if (!paused.get()) player.write(frame.bytes)
                    }
                }
            }
            remote.listen(
                address = address,
                pin = pin,
                onConnected = { config ->
                    require(config.sampleRateHz == 16_000) {
                        "한국어 원음은 16 kHz PCM이어야 합니다. 현재 ${config.sampleRateHz} Hz입니다."
                    }
                    mutableState.update {
                        it.copy(
                            phase = ClientSessionPhase.LISTENING,
                            statusMessage = "실시간 통역 청취 중",
                            errorMessage = null,
                        )
                    }
                },
                onFrame = { frame ->
                    if (!paused.get()) {
                        audioFrames.trySend(frame)
                        frameCount += 1
                        if (frameCount == 1L || frameCount % 10L == 0L) {
                            mutableState.update { it.copy(receivedFrames = frameCount) }
                        }
                    }
                },
            )
            recognition.cancel()
            translation.cancel()
            if (state.value.sessionActive) error("송출기 연결이 종료되었습니다.")
        } finally {
            audioFrames.close()
            utterances.close()
            remote.close()
            player.close()
            if (audioPlayer === player) audioPlayer = null
        }
    }

    private fun reportError(error: Throwable) {
        val message = error.message?.take(300) ?: "클라이언트 통역을 계속할 수 없습니다."
        mutableState.update {
            it.copy(
                phase = ClientSessionPhase.ERROR,
                statusMessage = "확인이 필요합니다.",
                errorMessage = message,
            )
        }
    }

    private fun stopSession(resetMessage: Boolean) {
        sessionJob?.cancel()
        sessionJob = null
        paused.set(false)
        audioPlayer?.close()
        audioPlayer = null
        if (resetMessage) {
            mutableState.update {
                it.copy(
                    phase = ClientSessionPhase.IDLE,
                    statusMessage = "통역 청취를 중지했습니다.",
                    errorMessage = null,
                )
            }
        }
    }

    override fun close() {
        stopSession(resetMessage = false)
        modelJob?.cancel()
        scope.cancel()
    }
}
