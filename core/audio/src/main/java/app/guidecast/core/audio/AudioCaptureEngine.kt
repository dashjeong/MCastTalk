package app.guidecast.core.audio

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

data class AudioCaptureConfig(
    val sampleRateHz: Int = 16_000,
    val enableNoiseSuppressor: Boolean = true,
    // Platform AGC can raise steady room sound between phrases. Keep it opt-in; the device noise
    // suppressor and the RNNoise path already provide their own voice-oriented processing.
    val enableAutomaticGain: Boolean = false,
    val enableEchoCanceler: Boolean = false,
    val noiseMode: MicrophoneNoiseMode = MicrophoneNoiseMode.DEVICE,
)

/** Selects requested session effects; OEM processing inside an audio source remains outside this API. */
internal fun AudioCaptureConfig.platformMicrophoneEffects(): AudioCaptureConfig = when (noiseMode) {
    // Request every controllable session effect off. The capture source itself may still be
    // processed by the device, especially for Bluetooth VOICE_COMMUNICATION routes.
    MicrophoneNoiseMode.OFF -> copy(
        enableNoiseSuppressor = false,
        enableAutomaticGain = false,
        enableEchoCanceler = false,
    )
    MicrophoneNoiseMode.DEVICE -> this
    // Avoid controllable platform AGC/NS ahead of RNNoise: AGC can pump background sound and
    // stacking two suppressors can damage consonants. Explicit echo cancellation remains separate.
    MicrophoneNoiseMode.AI -> copy(
        enableNoiseSuppressor = false,
        enableAutomaticGain = false,
    )
}

data class PcmFrame(
    val bytes: ByteArray,
    val sampleRateHz: Int,
    val capturedAtElapsedRealtimeNanos: Long,
)

enum class AudioProcessingMode {
    INACTIVE,
    MICROPHONE_VOICE,
    PLAYBACK_PASSTHROUGH,
}

/** Actual Android audio effects attached to the current capture session. */
data class AudioProcessingStatus(
    val mode: AudioProcessingMode = AudioProcessingMode.INACTIVE,
    val noiseSuppressorActive: Boolean = false,
    val automaticGainActive: Boolean = false,
    val echoCancelerActive: Boolean = false,
    val clientSilenced: Boolean = false,
    val noiseReductionSummary: String? = null,
) {
    val operatorSummary: String?
        get() = when (mode) {
            AudioProcessingMode.INACTIVE -> null
            AudioProcessingMode.PLAYBACK_PASSTHROUGH ->
                "앱 출력 무가공 전달 · 잡음 억제/레벨 조정 미적용"
            AudioProcessingMode.MICROPHONE_VOICE -> if (clientSilenced) {
                "Android가 마이크 입력을 차단 중입니다. 다른 녹음·통화 앱과 마이크 접근 설정을 확인하세요."
            } else {
                val activeEffects = buildList {
                    if (noiseSuppressorActive) add("잡음 억제")
                    if (automaticGainActive) add("음성 레벨 자동 조정")
                    if (echoCancelerActive) add("반향 제거")
                }
                when {
                    noiseReductionSummary != null && activeEffects.isEmpty() -> noiseReductionSummary
                    noiseReductionSummary != null ->
                        "$noiseReductionSummary + ${activeEffects.joinToString(" + ")} 활성"
                    activeEffects.isEmpty() ->
                        "제어 가능한 단말 음성 정제 효과 비활성 또는 상태 확인 불가 · " +
                            "기기 입력 처리 여부는 단말에 따름"
                    else -> activeEffects.joinToString(" + ") + " 활성"
                }
            }
        }
}

/**
 * Produces low-latency mono PCM frames. The caller must hold RECORD_AUDIO and, for Bluetooth on
 * Android 12+, BLUETOOTH_CONNECT. Broadcast code should collect this flow from a foreground
 * microphone service.
 */
class AudioCaptureEngine(
    context: Context,
    val routeController: BluetoothAudioRouteController = BluetoothAudioRouteController(context),
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext
        .getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mutableProcessingStatus = MutableStateFlow(AudioProcessingStatus())
    private val processingGeneration = AtomicLong(0)
    val processingStatus: StateFlow<AudioProcessingStatus> = mutableProcessingStatus.asStateFlow()
    private val mutableClientSilenced = MutableStateFlow(false)
    val clientSilenced: StateFlow<Boolean> = mutableClientSilenced.asStateFlow()

    @SuppressLint("MissingPermission")
    fun frames(
        preferredDeviceId: Int?,
        config: AudioCaptureConfig = AudioCaptureConfig(),
    ): Flow<PcmFrame> = callbackFlow {
        require(config.noiseMode != MicrophoneNoiseMode.AI || config.sampleRateHz == 16_000)
        val captureRate = if (config.noiseMode == MicrophoneNoiseMode.AI) 48_000 else config.sampleRateHz
        val minBufferBytes = AudioRecord.getMinBufferSize(
            captureRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBufferBytes > 0) { "Unsupported audio capture format: $minBufferBytes" }

        val routeLease = routeController.acquireRoute(preferredDeviceId)
        val platformDevice = routeLease.activeInput?.rawDeviceInfo
            ?: preferredDeviceId?.let(::findInputDevice)
        val recorder = try {
            AudioRecord.Builder()
                .setAudioSource(platformDevice.captureAudioSource(isBluetoothRoute = routeLease.isBluetooth))
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(captureRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(minBufferBytes * 2)
                .build()
        } catch (error: Throwable) {
            routeLease.close()
            throw error
        }

        if (platformDevice != null) {
            val preferredSet = recorder.setPreferredDevice(platformDevice)
            if (!preferredSet && !routeLease.isBluetooth) {
                recorder.release()
                routeLease.close()
                throw BluetoothRouteException.DeviceMismatch("Android rejected audio input device ${platformDevice.id}")
            }
        }
        check(recorder.state == AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            routeLease.close()
            "AudioRecord failed to initialize"
        }

        val effects = AudioEffects.attach(recorder.audioSessionId, config.platformMicrophoneEffects())
        try {
            recorder.startRecording()
        } catch (error: Exception) {
            effects.close()
            recorder.release()
            routeLease.close()
            throw error
        }
        if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            effects.close()
            recorder.release()
            routeLease.close()
            error("Android did not start microphone recording")
        }

        // Bounded wait and verification of AudioRecord.routedDevice
        try {
            routeController.awaitAndVerifyRoutedDevice(
                requestedInput = routeLease.requestedInput,
                getRoutedDevice = { recorder.routedDevice?.let(::AndroidPlatformAudioDeviceInfo) },
            )
        } catch (mismatch: Throwable) {
            effects.close()
            runCatching { recorder.stop() }
            recorder.release()
            routeLease.close()
            throw mismatch
        }

        val routingListener = AudioRecord.OnRoutingChangedListener { record ->
            try {
                routeController.verifyRoutedDevice(routeLease.requestedInput, record.routedDevice)
            } catch (disconnected: Throwable) {
                close(disconnected)
            }
        }
        recorder.addOnRoutingChangedListener(routingListener, Handler(Looper.getMainLooper()))

        val processingSession = processingGeneration.incrementAndGet()
        var noiseSummary = when (config.noiseMode) {
            MicrophoneNoiseMode.OFF -> "앱 소음 감소 끄기 요청 · 단말 입력 처리는 별도"
            MicrophoneNoiseMode.DEVICE -> null
            MicrophoneNoiseMode.AI -> "RNNoise AI 소음 감소 · 오프라인"
        }
        fun publishProcessing() {
            if (processingGeneration.get() == processingSession) {
                mutableProcessingStatus.value = effects.status.copy(
                    clientSilenced = mutableClientSilenced.value,
                    noiseReductionSummary = noiseSummary,
                )
            }
        }
        val denoiser = if (config.noiseMode == MicrophoneNoiseMode.AI) {
            val filter = try { NativeRnNoiseFilter() }
            catch (error: LinkageError) {
                noiseSummary = "AI 엔진을 열지 못했습니다 · 원본 마이크로 계속합니다."
                null
            } catch (error: Exception) {
                noiseSummary = "AI 엔진 초기화 실패 · 원본 마이크로 계속합니다."
                null
            }
            RnNoiseMicrophoneProcessor(filter ?: object : NoiseFrameFilter {
                override fun process(frame: FloatArray) = Unit
                override fun close() = Unit
            }) { message -> noiseSummary = message; publishProcessing() }
        } else null
        publishProcessing()
        // Android can keep read() successful while replacing PCM with zeros. This is a policy
        // signal, not a loudness threshold: expose it without changing privacy permissions.
        fun publishSilencing(silenced: Boolean) {
            if (processingGeneration.get() != processingSession) return
            mutableClientSilenced.value = silenced
            publishProcessing()
        }
        val recordingCallback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                configs.firstOrNull { it.clientAudioSessionId == recorder.audioSessionId }
                    ?.let { publishSilencing(it.isClientSilenced) }
            }
        }
        // Optional policy telemetry must never turn a working microphone into a startup failure.
        val callbackRegistered = runCatching {
            recorder.registerAudioRecordingCallback(ContextCompat.getMainExecutor(appContext), recordingCallback)
            true
        }.getOrDefault(false)
        runCatching { recorder.activeRecordingConfiguration }
            .getOrNull()?.let { publishSilencing(it.isClientSilenced) }

        val readJob = launch(Dispatchers.IO) {
            val buffer = ByteArray(minBufferBytes)
            while (true) {
                val count = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (count > 0) {
                    val bytes = denoiser?.process(buffer.copyOf(count)) ?: buffer.copyOf(count)
                    if (bytes.isEmpty()) continue
                    send(
                        PcmFrame(
                            bytes = bytes,
                            sampleRateHz = config.sampleRateHz,
                            capturedAtElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
                        ),
                    )
                } else if (count < 0) {
                    close(IllegalStateException("AudioRecord read failed: $count"))
                    break
                }
            }
        }

        awaitClose {
            if (callbackRegistered) runCatching { recorder.unregisterAudioRecordingCallback(recordingCallback) }
            recorder.removeOnRoutingChangedListener(routingListener)
            readJob.cancel()
            runCatching { recorder.stop() }
            denoiser?.close()
            effects.close()
            recorder.release()
            routeLease.close()
            if (processingGeneration.compareAndSet(processingSession, processingSession + 1)) {
                mutableProcessingStatus.value = AudioProcessingStatus()
                mutableClientSilenced.value = false
            }
        }
    // Keep only a short, lossless hand-off reserve. A deep/drop-oldest capture queue masks CPU
    // overload by deleting sentence onsets and then playing increasingly stale recognition input.
    }.buffer(capacity = CAPTURE_HANDOFF_FRAMES)

    /**
     * Captures eligible MEDIA/GAME/UNKNOWN playback authorized by a MediaProjection token.
     * GuideCast's own UID is excluded so a local preview cannot feed back into translation or the
     * source channel while the operator is capturing another translation app.
     */
    @SuppressLint("MissingPermission")
    fun playbackFrames(
        mediaProjection: MediaProjection,
        targetUid: Int? = null,
        config: AudioCaptureConfig = AudioCaptureConfig(),
    ): Flow<PcmFrame> = callbackFlow {
        val minBufferBytes = AudioRecord.getMinBufferSize(
            config.sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBufferBytes > 0) { "Unsupported playback capture format: $minBufferBytes" }

        val playbackConfigBuilder = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
        if (targetUid != null) {
            require(targetUid != Process.myUid()) { "GuideCast itself cannot be a playback target" }
            playbackConfigBuilder.addMatchingUid(targetUid)
        } else {
            playbackConfigBuilder.excludeUid(Process.myUid())
        }
        val playbackConfig = playbackConfigBuilder.build()
        val recorder = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(playbackConfig)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(config.sampleRateHz)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(minBufferBytes * 2)
            .build()
        check(recorder.state == AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            "Internal playback AudioRecord failed to initialize"
        }

        recorder.startRecording()
        if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            recorder.release()
            error("Android did not start internal playback recording")
        }
        val playbackStatus = AudioProcessingStatus(
            mode = AudioProcessingMode.PLAYBACK_PASSTHROUGH,
        )
        val processingSession = processingGeneration.incrementAndGet()
        mutableProcessingStatus.value = playbackStatus

        val readJob = launch(Dispatchers.IO) {
            val buffer = ByteArray(minBufferBytes)
            while (true) {
                val count = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (count > 0) {
                    send(
                        PcmFrame(
                            bytes = buffer.copyOf(count),
                            sampleRateHz = config.sampleRateHz,
                            capturedAtElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
                        ),
                    )
                } else if (count < 0) {
                    close(IllegalStateException("Playback AudioRecord read failed: $count"))
                    break
                }
            }
        }

        awaitClose {
            readJob.cancel()
            runCatching { recorder.stop() }
            recorder.release()
            if (processingGeneration.compareAndSet(processingSession, processingSession + 1)) {
                mutableProcessingStatus.value = AudioProcessingStatus()
            }
        }
    }.buffer(capacity = CAPTURE_HANDOFF_FRAMES)

    private fun findInputDevice(id: Int): AudioDeviceInfo? = audioManager
        .getDevices(AudioManager.GET_DEVICES_INPUTS)
        .firstOrNull { it.id == id }

    private fun AudioDeviceInfo?.captureAudioSource(isBluetoothRoute: Boolean = false): Int = when {
        isBluetoothRoute -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
        this == null -> MediaRecorder.AudioSource.MIC
        type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_HEADSET ->
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        else -> MediaRecorder.AudioSource.MIC
    }

    private companion object {
        const val CAPTURE_HANDOFF_FRAMES = 2
    }
}

private class AudioEffects(
    private val noiseSuppressor: NoiseSuppressor?,
    private val automaticGain: AutomaticGainControl?,
    private val echoCanceler: AcousticEchoCanceler?,
) : Closeable {
    val status = AudioProcessingStatus(
        mode = AudioProcessingMode.MICROPHONE_VOICE,
        noiseSuppressorActive = runCatching { noiseSuppressor?.enabled == true }.getOrDefault(false),
        automaticGainActive = runCatching { automaticGain?.enabled == true }.getOrDefault(false),
        echoCancelerActive = runCatching { echoCanceler?.enabled == true }.getOrDefault(false),
    )
    override fun close() {
        runCatching { noiseSuppressor?.release() }
        runCatching { automaticGain?.release() }
        runCatching { echoCanceler?.release() }
    }

    companion object {
        fun attach(sessionId: Int, config: AudioCaptureConfig) = AudioEffects(
            noiseSuppressor = configureAudioEffect(
                requestedEnabled = config.enableNoiseSuppressor,
                create = { NoiseSuppressor.create(sessionId) },
                setEnabled = { effect, enabled -> effect.enabled = enabled },
                isEnabled = { it.enabled },
                release = { it.release() },
            ),
            automaticGain = configureAudioEffect(
                requestedEnabled = config.enableAutomaticGain,
                create = { AutomaticGainControl.create(sessionId) },
                setEnabled = { effect, enabled -> effect.enabled = enabled },
                isEnabled = { it.enabled },
                release = { it.release() },
            ),
            echoCanceler = configureAudioEffect(
                requestedEnabled = config.enableEchoCanceler,
                create = { AcousticEchoCanceler.create(sessionId) },
                setEnabled = { effect, enabled -> effect.enabled = enabled },
                isEnabled = { it.enabled },
                release = { it.release() },
            ),
        )
    }
}

/** Keeps a verified handle alive for the capture lifetime, including explicitly disabled effects. */
internal fun <T> configureAudioEffect(
    requestedEnabled: Boolean,
    create: () -> T?,
    setEnabled: (T, Boolean) -> Unit,
    isEnabled: (T) -> Boolean,
    release: (T) -> Unit,
): T? {
    val effect = runCatching(create).getOrNull() ?: return null
    return try {
        setEnabled(effect, requestedEnabled)
        check(isEnabled(effect) == requestedEnabled) { "Android audio effect rejected requested state" }
        effect
    } catch (_: Throwable) {
        runCatching { release(effect) }
        null
    }
}
