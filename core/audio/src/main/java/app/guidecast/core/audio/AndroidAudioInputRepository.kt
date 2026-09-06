package app.guidecast.core.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidAudioInputRepository(context: Context) : AudioInputRepository {
    private val audioManager = context.applicationContext
        .getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val started = AtomicBoolean(false)
    private val playbackMonitoringStarted = AtomicBoolean(false)

    private val mutableDevices = MutableStateFlow<List<AudioInputDevice>>(emptyList())
    override val availableDevices: StateFlow<List<AudioInputDevice>> = mutableDevices.asStateFlow()
    private val mutableOutputDevices = MutableStateFlow<List<AudioOutputDevice>>(emptyList())
    override val availableOutputDevices: StateFlow<List<AudioOutputDevice>> =
        mutableOutputDevices.asStateFlow()

    private val mutablePreference = MutableStateFlow(AudioInputPreference.Automatic)
    override val preference: StateFlow<AudioInputPreference> = mutablePreference.asStateFlow()

    private val mutableSelected = MutableStateFlow<AudioInputDevice?>(null)
    override val selectedDevice: StateFlow<AudioInputDevice?> = mutableSelected.asStateFlow()

    private val mutablePlaybackCaptureDiagnostics = MutableStateFlow(PlaybackCaptureDiagnostics())
    override val playbackCaptureDiagnostics: StateFlow<PlaybackCaptureDiagnostics> =
        mutablePlaybackCaptureDiagnostics.asStateFlow()

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            updatePlaybackCaptureDiagnostics(configs)
        }
    }

    override fun start() {
        if (started.compareAndSet(false, true)) {
            audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
            try {
                audioManager.registerAudioPlaybackCallback(
                    playbackCallback,
                    Handler(Looper.getMainLooper()),
                )
                playbackMonitoringStarted.set(true)
            } catch (_: RuntimeException) {
                mutablePlaybackCaptureDiagnostics.value = PlaybackCaptureDiagnostics(
                    monitoringAvailable = false,
                )
            }
        }
        refresh()
    }

    override fun refresh() {
        val physicalInputs = try {
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .map(::toDomainDevice)
                .sortedWith(compareBy<AudioInputDevice> { it.kind.ordinal }.thenBy { it.label })
        } catch (_: SecurityException) {
            emptyList()
        }
        val outputs = try {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .map(::toOutputDevice)
                .distinctBy(AudioOutputDevice::platformId)
                .sortedWith(compareBy<AudioOutputDevice> { it.kind.ordinal }.thenBy { it.label })
        } catch (_: SecurityException) {
            emptyList()
        }

        val inputs = buildList {
            add(
                AudioInputDevice(
                    platformId = AudioInputKind.DEVICE_PLAYBACK_ID,
                    kind = AudioInputKind.DEVICE_PLAYBACK,
                    label = "번역 앱/기기 출력",
                ),
            )
            add(
                AudioInputDevice(
                    platformId = AudioInputKind.WEB_SPEAKER_ID,
                    kind = AudioInputKind.WEB_SPEAKER,
                    label = "🌐 강사 웹 마이크 (원격 입력)",
                ),
            )
            addAll(physicalInputs)
        }

        mutableDevices.value = inputs
        mutableOutputDevices.value = outputs
        resolveSelection()
        refreshPlaybackCaptureDiagnostics()
    }

    override fun useAutomaticSelection() {
        mutablePreference.value = AudioInputPreference.Automatic
        resolveSelection()
    }

    override fun selectDevice(platformId: Int) {
        mutablePreference.value = AudioInputPreference.pinned(platformId)
        resolveSelection()
    }

    override fun close() {
        if (started.compareAndSet(true, false)) {
            audioManager.unregisterAudioDeviceCallback(callback)
            if (playbackMonitoringStarted.compareAndSet(true, false)) {
                runCatching { audioManager.unregisterAudioPlaybackCallback(playbackCallback) }
            }
        }
    }

    private fun refreshPlaybackCaptureDiagnostics() {
        if (!playbackMonitoringStarted.get()) {
            mutablePlaybackCaptureDiagnostics.value = PlaybackCaptureDiagnostics(
                monitoringAvailable = false,
            )
            return
        }
        try {
            updatePlaybackCaptureDiagnostics(audioManager.activePlaybackConfigurations)
        } catch (_: SecurityException) {
            mutablePlaybackCaptureDiagnostics.value = PlaybackCaptureDiagnostics(
                monitoringAvailable = false,
            )
        } catch (_: RuntimeException) {
            // A vendor audio service can restart while the app is resuming. Diagnostics must not
            // disable the input or broadcast paths; the next callback/refresh can recover it.
            mutablePlaybackCaptureDiagnostics.value = PlaybackCaptureDiagnostics(
                monitoringAvailable = false,
            )
        }
    }

    private fun updatePlaybackCaptureDiagnostics(configs: List<AudioPlaybackConfiguration>) {
        mutablePlaybackCaptureDiagnostics.value = PlaybackCaptureEligibility.summarize(
            configs.map { config ->
                PlaybackStreamPolicy(
                    usage = config.audioAttributes.usage,
                    allowedCapturePolicy = config.audioAttributes.allowedCapturePolicy,
                )
            },
        ).copy(mediaOutputRouteLabels = mediaOutputRouteLabels())
    }

    @Suppress("DEPRECATION")
    private fun mediaOutputRouteLabels(): List<String> = runCatching {
        val devices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            audioManager.getAudioDevicesForAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
        } else {
            // API 29-32 cannot expose another app's active routed AudioTrack. Show connected
            // output candidates only and keep the UI label explicit about that limitation.
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        }
        devices.map { device ->
            device.productName.toString().trim().ifBlank { device.type.outputTypeLabel() }
        }.distinct()
    }.getOrDefault(emptyList())

    private fun resolveSelection() {
        mutableSelected.value = AudioInputSelectionPolicy.resolve(
            preference = mutablePreference.value,
            available = mutableDevices.value,
        )
    }

    private fun toDomainDevice(device: AudioDeviceInfo): AudioInputDevice = AudioInputDevice(
        platformId = device.id,
        kind = device.type.toInputKind(),
        label = device.productName.toString().ifBlank { device.type.defaultLabel() },
    )

    private fun toOutputDevice(device: AudioDeviceInfo): AudioOutputDevice {
        val routeType = device.type.outputTypeLabel()
        val product = device.productName.toString().trim()
        return AudioOutputDevice(
            platformId = device.id,
            kind = device.type.toOutputKind(),
            label = product.takeIf(String::isNotBlank)
                ?.let { "$it · $routeType" }
                ?: routeType,
        )
    }

    private fun Int.toOutputKind(): AudioOutputKind = when (this) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioOutputKind.BUILT_IN_SPEAKER
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL,
        -> AudioOutputKind.WIRED
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        -> AudioOutputKind.USB
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST,
        AudioDeviceInfo.TYPE_HEARING_AID,
        -> AudioOutputKind.BLUETOOTH
        AudioDeviceInfo.TYPE_UNKNOWN -> AudioOutputKind.UNKNOWN
        else -> AudioOutputKind.OTHER
    }

    private fun Int.toInputKind(): AudioInputKind = when (this) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> AudioInputKind.BUILT_IN

        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        -> AudioInputKind.WIRED_HEADSET

        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        -> AudioInputKind.USB

        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> AudioInputKind.BLUETOOTH
        else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            this == AudioDeviceInfo.TYPE_BLE_HEADSET
        ) {
            AudioInputKind.BLUETOOTH
        } else {
            AudioInputKind.OTHER
        }
    }

    private fun Int.defaultLabel(): String = when (toInputKind()) {
        AudioInputKind.DEVICE_PLAYBACK -> "번역 앱/기기 출력"
        AudioInputKind.WEB_SPEAKER -> "🌐 강사 웹 마이크 (원격 입력)"
        AudioInputKind.BUILT_IN -> "내장 마이크"
        AudioInputKind.WIRED_HEADSET -> "유선 오디오 입력"
        AudioInputKind.USB -> "USB 오디오 입력"
        AudioInputKind.BLUETOOTH -> "Bluetooth 통화 마이크"
        AudioInputKind.OTHER -> "기타 오디오 입력"
    }

    private fun Int.outputTypeLabel(): String = when (this) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth 오디오"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth 통화 오디오"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE 이어폰"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth LE 스피커"
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> "Auracast 수신 출력"
        AudioDeviceInfo.TYPE_HEARING_AID -> "Bluetooth 보청기"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        -> "유선 이어폰"
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        -> "USB 오디오"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "내장 스피커"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "수화부"
        else -> "오디오 출력"
    }
}
