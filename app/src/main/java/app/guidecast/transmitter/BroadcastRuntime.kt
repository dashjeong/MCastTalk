package app.guidecast.transmitter

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class BroadcastPhase {
    IDLE,
    STARTING,
    LIVE,
    PAUSED,
    FAILED,
}

enum class BroadcastRunMode { NETWORK, STANDALONE }

enum class InputPhase {
    IDLE,
    STARTING,
    ACTIVE,
    PAUSED,
    FAILED,
}

enum class LocalMonitorPhase {
    IDLE,
    STARTING,
    PLAYING,
    PAUSED,
    FAILED,
}

data class LocalMonitorSnapshot(
    val phase: LocalMonitorPhase = LocalMonitorPhase.IDLE,
    val channelId: String? = null,
    val channelLabel: String? = null,
    val outputRouteLabel: String? = null,
    val requestedOutputDeviceId: Int? = null,
    val volume: Float = 0.25f,
    val renderedFrames: Long = 0,
    val renderedNonSilentFrames: Long = 0,
    val warning: String? = null,
    val errorMessage: String? = null,
)

/** Independent worker state exposed to the operator for one translated language. */
enum class BroadcastChannelWorkerState {
    IDLE,
    ACTIVE,
    DEGRADED,
}

/**
 * Runtime evidence for one language route. No aggregate error is inferred from this object:
 * another language may stay healthy while this one reconnects or falls back.
 */
data class BroadcastChannelSnapshot(
    val channelId: String,
    val languageTag: String,
    val displayName: String,
    val listenerUrl: String? = null,
    val listenerCount: Int = 0,
    /** Browser queue frames discarded for slow listeners on this route. */
    val listenerDroppedFrames: Long = 0,
    val translationProvider: String? = null,
    val synthesisProvider: String? = null,
    val translationState: BroadcastChannelWorkerState = BroadcastChannelWorkerState.IDLE,
    val synthesisState: BroadcastChannelWorkerState = BroadcastChannelWorkerState.IDLE,
    val lastAcceptedSequence: Long? = null,
    val lastTranslatedSequence: Long? = null,
    val lastSynthesizedSequence: Long? = null,
    /** Most recent utterance whose PCM was accepted by the server-side channel registry. */
    val lastPublishedSequence: Long? = null,
    /** Server-side PCM frames published, regardless of whether a listener was connected. */
    val publishedFrameCount: Long = 0,
    /**
     * Successful server WebSocket frame sends to listeners. With multiple listeners one
     * published frame can contribute multiple deliveries; this does not claim browser playback.
     */
    val webSocketDeliveredFrameCount: Long = 0,
    val lastWebSocketDeliveredSequence: Long? = null,
    val lastCompletedSequence: Long? = null,
    val droppedUtterances: Long = 0,
    val lastDroppedSequence: Long? = null,
    val sourceBacklogDrops: Long = 0,
    val speechBacklogDrops: Long = 0,
    val translationFailures: Long = 0,
    val synthesisFailures: Long = 0,
    /** Translation failure states cleared by a later successful sentence on this channel. */
    val translationRecoveries: Long = 0,
    /** TTS failure states cleared by a later successful sentence on this channel. */
    val synthesisRecoveries: Long = 0,
    val lastTranslationElapsedMillis: Long? = null,
    /** Time from starting synthesis to its first PCM, not recognition-final to first PCM. */
    val lastSynthesisFirstPcmMillis: Long? = null,
    val lastSynthesisElapsedMillis: Long? = null,
    val lastTranslationError: String? = null,
    val lastSynthesisError: String? = null,
) {
    val lastError: String?
        get() = lastSynthesisError ?: lastTranslationError
}

enum class InterpreterRelayPhase { IDLE, CONNECTING, READY, RECEIVING, PAUSED, FAILED }

data class BroadcastSnapshot(
    val recordingId: String? = null,
    val broadcastTitle: String = "",
    val recordingWarning: String? = null,
    val isInterpreterRelay: Boolean = false,
    val relayPhase: InterpreterRelayPhase = InterpreterRelayPhase.IDLE,
    val relayPlayedBytes: Long = 0,
    val relayReferenceCharacters: Int = 0,
    val relayReferenceEntries: Int = 0,
    val relayAvailableReferenceEntries: Int = 0,
    val relayContext: RelayContextPresentation? = null,
    val runMode: BroadcastRunMode = BroadcastRunMode.NETWORK,
    val inputPhase: InputPhase = InputPhase.IDLE,
    val inputStopping: Boolean = false,
    val phase: BroadcastPhase = BroadcastPhase.IDLE,
    val accessMode: OperatorAccessMode? = null,
    val listenerUrl: String? = null,
    val speakerUrl: String? = null,
    /** Installation-local Root CA digest copied from the exact running server generation. */
    val caSha256Fingerprint: String? = null,
    /** Fail-safe warning when the downloadable DER bytes do not match the advertised digest. */
    val caFingerprintWarning: String? = null,
    val webSpeakerConnected: Boolean = false,
    val listenerCount: Int = 0,
    /** Aggregate slow-listener queue drops for the current broadcast generation. */
    val listenerDroppedFrames: Long = 0,
    /** Aggregate successful server WebSocket frame sends; not a browser-playback receipt. */
    val webSocketDeliveredFrameCount: Long = 0,
    val translationChannels: List<BroadcastChannelSnapshot> = emptyList(),
    val inputLabel: String? = null,
    val channelSummary: String? = null,
    val translationWarning: String? = null,
    val recognitionErrorMessage: String? = null,
    val inputRms: Float = 0f,
    val inputPeak: Float = 0f,
    val inputFrameCount: Long = 0,
    val recognitionDroppedFrameCount: Long = 0,
    val inputAudibleFrameCount: Long = 0,
    val inputSignalActive: Boolean = false,
    val inputProcessingSummary: String? = null,
    val testToneActive: Boolean = false,
    val inputErrorMessage: String? = null,
    val errorMessage: String? = null,
    val transcripts: List<TranslationTranscriptLine> = emptyList(),
    val translationTestActive: Boolean = false,
    val translationTestLanguageTag: String? = null,
    val translationTestPassed: Boolean = false,
    val translationTestMessage: String? = null,
    val localMonitor: LocalMonitorSnapshot = LocalMonitorSnapshot(),
)

enum class LiveOutputState(val label: String) {
    QUEUED("통역 대기 · 청취 미확인"), GENERATING("통역 생성 중 · 청취 미확인"),
    GENERATED("통역 생성 완료 · 청취 미확인"), CANCELLED("통역 취소됨 · 청취 미확인"),
    INCOMPLETE("통역 미완료 · 청취 미확인"),
}

enum class NativeAudioEndReason(val label: String, val outputState: LiveOutputState) {
    STOPPED("통역 중지", LiveOutputState.CANCELLED),
    CONSENT_REVOKED("온라인 전송 동의 해제", LiveOutputState.CANCELLED),
    SESSION_ENDED("입력 세션 종료", LiveOutputState.CANCELLED),
    TIMEOUT("시간 제한 종료", LiveOutputState.INCOMPLETE),
    OVERLOAD("대기열 초과", LiveOutputState.INCOMPLETE),
    FAILURE("연결 또는 처리 종료", LiveOutputState.INCOMPLETE),
}

enum class TranscriptAudioAlignment { UTTERANCE_SEQUENCE, NATIVE_PAIR_UNCONFIRMED }

data class TranslationTranscriptLine(
    val sequence: Long,
    val sourceText: String,
    val capturedAtElapsedRealtimeNanos: Long,
    val isFinal: Boolean = false,
    val translations: Map<String, String> = emptyMap(),
    val translationLatencyMillis: Map<String, Long> = emptyMap(),
    val firstAudioLatencyMillis: Map<String, Long> = emptyMap(),
    val synthesisLatencyMillis: Map<String, Long> = emptyMap(),
    /** Retained with the source, so a later operator language change cannot relabel corrections. */
    val sourceLanguageTag: String? = null,
    /** Live providers segment each language independently; never imply cross-language alignment. */
    val liveSegmentLanguage: String? = null,
    val liveOutputState: LiveOutputState? = null,
    val liveSourceFinal: Boolean? = null,
    val nativeAudioSessionId: Long? = null,
    val liveEndReason: NativeAudioEndReason? = null,
    val recordingAlignment: TranscriptAudioAlignment = if (liveSegmentLanguage != null)
        TranscriptAudioAlignment.NATIVE_PAIR_UNCONFIRMED else TranscriptAudioAlignment.UTTERANCE_SEQUENCE,
    val liveSourceFailed: Boolean = false,
    val liveSourceExpired: Boolean = false,
) {
    val sourceStatusLabel: String get() = when {
        liveSourceExpired -> "원문 자막 대기 만료 · 사용량 미확인"
        liveSourceFailed -> "자막 인식 실패 · 음성 통역은 계속"
        liveSegmentLanguage != null && liveSourceFinal == null -> "전사 수신 · 확정 대응 미확인"
        liveSourceFinal == true || (liveSourceFinal == null && isFinal) -> "확정"
        liveSourceFinal == false && liveOutputState in setOf(LiveOutputState.GENERATED, LiveOutputState.CANCELLED, LiveOutputState.INCOMPLETE) -> "인식 미완료"
        else -> "인식 중"
    }
    val liveStatusLabel: String? get() = liveOutputState?.let {
        "원문 인식 ${if (liveSourceExpired) "대기 만료" else if (liveSourceFailed) "실패" else if (liveSourceFinal == true) "완료" else "미완료"} · ${it.label}" +
            (liveEndReason?.let { reason -> " · ${reason.label}" } ?: "")
    }
}

/** A closed session may only finalize its own unfinished rows, even after a new session starts. */
internal fun terminalizeNativeAudioTranscripts(lines: List<TranslationTranscriptLine>, sessionId: Long,
    reason: NativeAudioEndReason, target: String? = null): List<TranslationTranscriptLine> = lines.map { row ->
    if (row.nativeAudioSessionId == sessionId && (target == null || row.liveSegmentLanguage == target) && row.liveOutputState in setOf(LiveOutputState.QUEUED, LiveOutputState.GENERATING))
        row.copy(isFinal = false, liveOutputState = reason.outputState, liveEndReason = reason)
    else row
}

class BroadcastRuntime {
    private val mutableState = MutableStateFlow(BroadcastSnapshot())
    val state: StateFlow<BroadcastSnapshot> = mutableState.asStateFlow()
    val inputRequestEpoch: Long get() = requestEpoch.get()

    fun invalidateInputRequest(): Long = requestEpoch.incrementAndGet()

    internal fun update(snapshot: BroadcastSnapshot) {
        mutableState.value = snapshot
    }

    internal fun update(transform: (BroadcastSnapshot) -> BroadcastSnapshot) {
        mutableState.update(transform)
    }

    private companion object {
        val requestEpoch = AtomicLong(0)
    }
}

enum class OperatorAccessMode {
    QR_TOKEN,
    PIN,
    OPEN,
}
