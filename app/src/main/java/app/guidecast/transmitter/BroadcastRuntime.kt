package app.guidecast.transmitter

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

data class BroadcastSnapshot(
    val inputPhase: InputPhase = InputPhase.IDLE,
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
    val inputRms: Float = 0f,
    val inputPeak: Float = 0f,
    val inputFrameCount: Long = 0,
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
)

class BroadcastRuntime {
    private val mutableState = MutableStateFlow(BroadcastSnapshot())
    val state: StateFlow<BroadcastSnapshot> = mutableState.asStateFlow()

    internal fun update(snapshot: BroadcastSnapshot) {
        mutableState.value = snapshot
    }

    internal fun update(transform: (BroadcastSnapshot) -> BroadcastSnapshot) {
        mutableState.update(transform)
    }
}

enum class OperatorAccessMode {
    QR_TOKEN,
    PIN,
    OPEN,
}
