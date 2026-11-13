package app.guidecast.transmitter

import app.guidecast.core.audio.AudioCaptureDiagnosticSnapshot
import kotlinx.coroutines.delay

// pmtest는 alpha를 상속하므로 DEBUG 값과 별도로 시험 빌드인지 확인한다.
internal fun megaphoneDiagnosticsEnabled(debuggable: Boolean, buildType: String): Boolean =
    debuggable || buildType == "pmtest"

/** 입력이 멎어도 같은 주기로 관측한다. 진단 실패는 오디오 작업으로 전파하지 않는다. */
internal suspend fun runMegaphoneDiagnostics(enabled: Boolean, sample: () -> String, write: (String) -> Unit) {
    if (!enabled) return
    while (true) {
        delay(2_000)
        runCatching { write(sample()) }
    }
}

/** 문장·장치 이름·식별자 대신 숫자와 닫힌 상태 집합만 받는다. */
internal data class MegaphoneDiagnosticState(
    val nowMillis: Long,
    val inputGeneration: Long = 0,
    val mode: BroadcastRunMode = BroadcastRunMode.NETWORK,
    val input: InputPhase = InputPhase.IDLE,
    val turn: LocalInterpretationPhase = LocalInterpretationPhase.OFF,
    val capture: AudioCaptureDiagnosticSnapshot = AudioCaptureDiagnosticSnapshot(),
    val audioMode: Int = -1,
    val output: LocalMonitorPhase = LocalMonitorPhase.IDLE,
    val outputPlaying: Int = 0,
    val outputWrittenBytes: Long = 0,
)

/** PCM·문장 보관 없이 셈만 한다. 누계는 서비스 수명, 최대 크기는 직전 관측 이후 기준이다. */
internal class MegaphoneDiagnosticCounters {
    private var inputFrames = 0L
    private var inputWindowFrames = 0L
    private var inputPeak = -1f
    private var zeroPcmFrames = 0L
    private var processedFrames = 0L
    private var offeredFrames = 0L
    private var rejectedFrames = 0L
    private var recognitionFrames = 0L
    private var silencedFrames = 0L
    private var captureSilencedFrames = 0L

    @Synchronized fun read(peak: Float) {
        inputFrames++; inputWindowFrames++
        inputPeak = maxOf(inputPeak, peak)
        if (peak == 0f) zeroPcmFrames++
    }
    @Synchronized fun processed() { processedFrames++ }
    @Synchronized fun handoff(accepted: Boolean) { if (accepted) offeredFrames++ else rejectedFrames++ }
    @Synchronized fun recognizer(silenced: Boolean) {
        recognitionFrames++
        if (silenced) silencedFrames++
    }
    @Synchronized fun captureCopy(silenced: Boolean) { if (silenced) captureSilencedFrames++ }

    @Synchronized fun line(state: MegaphoneDiagnosticState): String {
        val capture = state.capture
        val record = when (capture.state) {
            "NOT_STARTED", "RECORDING", "CLOSED", "FAILED" -> capture.state
            else -> "UNKNOWN"
        }
        val readResult = when (capture.lastReadResult) {
            "NOT_READ", "PCM_READ", "ZERO_READ", "READ_ERROR" -> capture.lastReadResult
            else -> "UNKNOWN"
        }
        fun age(nanos: Long?) = nanos?.let { (state.nowMillis - it / 1_000_000).coerceAtLeast(0) } ?: -1L
        fun flag(value: Boolean?) = when (value) { true -> 1; false -> 0; null -> -1 }
        return ("t_ms=${state.nowMillis} input_generation=${state.inputGeneration} mode=${state.mode.name} " +
            "input=${state.input.name} input_frames=$inputFrames input_window_frames=$inputWindowFrames input_peak=$inputPeak " +
            "zero_pcm_frames=$zeroPcmFrames processed_frames=$processedFrames offered_frames=$offeredFrames " +
            "rejected_frames=$rejectedFrames recognition_frames=$recognitionFrames silenced_frames=$silencedFrames " +
            "capture_silenced_frames=$captureSilencedFrames turn=${state.turn.name} record=$record " +
            "record_session=${capture.session} record_state=${capture.recorderState ?: -1} " +
            "recording_state=${capture.recordingState ?: -1} source=${capture.audioSource ?: -1} " +
            "record_creates=${capture.recorderCreations} record_recreates=${(capture.recorderCreations - 1).coerceAtLeast(0)} " +
            "native_restores=UNKNOWN route_changes=${capture.routeChanges} route_type=${capture.routedDeviceType ?: -1} " +
            "read_calls=${capture.readCalls} zero_reads=${capture.zeroReads} read_errors=${capture.readErrors} " +
            "read_result=$readResult read_age_ms=${age(capture.lastReadElapsedNanos)} " +
            "read_attempt_age_ms=${age(capture.lastReadAttemptElapsedNanos)} " +
            "client_silenced=${flag(capture.clientSilenced)} mic_muted=${flag(capture.systemMicrophoneMuted)} " +
            "audio_mode=${state.audioMode} output=${state.output.name} output_playing=${state.outputPlaying} " +
            "output_written_bytes=${state.outputWrittenBytes}").also {
                inputWindowFrames = 0; inputPeak = -1f
            }
    }
}
