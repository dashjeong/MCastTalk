package app.guidecast.core.audio

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Distinguishes classic Bluetooth HFP/SCO and Bluetooth Low Energy (BLE) Audio.
 */
enum class BluetoothRouteType {
    CLASSIC_SCO,
    BLE_HEADSET,
}

/**
 * Abstracted representation of audio device info to enable clean JVM unit testing
 * without requiring Android framework mocks.
 */
interface PlatformAudioDeviceInfo {
    val id: Int
    val type: Int
    val productName: CharSequence
    val address: String
    val isSource: Boolean
    val isSink: Boolean
    val rawDeviceInfo: AudioDeviceInfo? get() = null
}

fun PlatformAudioDeviceInfo.isBluetoothInput(): Boolean =
    isSource && (type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || type == AudioDeviceInfo.TYPE_BLE_HEADSET)

fun PlatformAudioDeviceInfo.isBluetoothOutput(): Boolean =
    isSink && (type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || type == AudioDeviceInfo.TYPE_BLE_HEADSET)

fun PlatformAudioDeviceInfo.bluetoothRouteType(): BluetoothRouteType? = when (type) {
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> BluetoothRouteType.CLASSIC_SCO
    AudioDeviceInfo.TYPE_BLE_HEADSET -> BluetoothRouteType.BLE_HEADSET
    else -> null
}

/**
 * Concrete wrapper for [AudioDeviceInfo] on real Android runtimes.
 */
class AndroidPlatformAudioDeviceInfo(
    val device: AudioDeviceInfo,
) : PlatformAudioDeviceInfo {
    override val id: Int get() = device.id
    override val type: Int get() = device.type
    override val productName: CharSequence get() = device.productName
    override val address: String
        get() = try {
            device.address
        } catch (_: SecurityException) {
            ""
        }
    override val isSource: Boolean get() = device.isSource
    override val isSink: Boolean get() = device.isSink
    override val rawDeviceInfo: AudioDeviceInfo get() = device

    override fun toString(): String =
        "AndroidPlatformAudioDeviceInfo(id=$id, type=$type, label=$productName, isSource=$isSource, isSink=$isSink)"
}

/**
 * Lightweight data class for testing platform audio device contracts in JVM tests.
 */
data class TestPlatformAudioDeviceInfo(
    override val id: Int,
    override val type: Int,
    override val productName: CharSequence,
    override val address: String = "",
    override val isSource: Boolean = false,
    override val isSink: Boolean = false,
    override val rawDeviceInfo: AudioDeviceInfo? = null,
) : PlatformAudioDeviceInfo

/**
 * Actionable, typed exception hierarchy for Bluetooth audio routing failures.
 * Preserves operator authority and prevents silent fallback to built-in mic.
 */
sealed class BluetoothRouteException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause) {

    class PermissionDenied(
        message: String = "BLUETOOTH_CONNECT 권한이 필요합니다.",
        cause: Throwable? = null,
    ) : BluetoothRouteException(message, cause)

    class SinkNotFound(message: String) : BluetoothRouteException(message)

    class SinkRejected(message: String) : BluetoothRouteException(message)

    class AmbiguousRoute(message: String) : BluetoothRouteException(message)

    class RouteTimeout(message: String) : BluetoothRouteException(message)

    class DeviceMismatch(message: String) : BluetoothRouteException(message)

    class RouteDisconnected(message: String) : BluetoothRouteException(message)

    class LegacyScoTimeout(message: String = "Bluetooth SCO 연결 대기 시간 초과 (4000ms)") :
        BluetoothRouteException(message)

    class LegacyScoFailed(val state: Int, message: String = "Bluetooth SCO 연결 실패 (state=$state)") :
        BluetoothRouteException(message)
}

/**
 * Represents an acquired audio route lease. Releasing must be idempotent and restore
 * the previous audio mode and route exactly once.
 */
interface AudioRouteLease : AutoCloseable {
    val isBluetooth: Boolean
    val requestedInput: PlatformAudioDeviceInfo?
    val selectedSink: PlatformAudioDeviceInfo?
    val activeInput: PlatformAudioDeviceInfo?
    val previousMode: Int
    override fun close()

    companion object {
        val NONE: AudioRouteLease = object : AudioRouteLease {
            override val isBluetooth: Boolean = false
            override val requestedInput: PlatformAudioDeviceInfo? = null
            override val selectedSink: PlatformAudioDeviceInfo? = null
            override val activeInput: PlatformAudioDeviceInfo? = null
            override val previousMode: Int = AudioManager.MODE_NORMAL
            override fun close() {}
        }
    }
}

fun interface CommunicationDeviceChangeListener {
    fun onCommunicationDeviceChanged(device: PlatformAudioDeviceInfo?)
}

/**
 * Abstraction layer over Android [AudioManager] to facilitate unit testing
 * of communication device routing and SCO lifecycle.
 */
interface AudioRoutePlatformAdapter {
    val sdkInt: Int
    var mode: Int
    val isBluetoothScoOn: Boolean

    fun startBluetoothSco()
    fun stopBluetoothSco()

    fun getAvailableCommunicationDevices(): List<PlatformAudioDeviceInfo>
    fun getCommunicationDevice(): PlatformAudioDeviceInfo?
    fun setCommunicationDevice(device: PlatformAudioDeviceInfo): Boolean
    fun clearCommunicationDevice()

    fun addCommunicationDeviceChangeListener(
        executor: Executor,
        listener: CommunicationDeviceChangeListener,
    )
    fun removeCommunicationDeviceChangeListener(
        listener: CommunicationDeviceChangeListener,
    )

    fun getInputDevices(): List<PlatformAudioDeviceInfo>
    fun getOutputDevices(): List<PlatformAudioDeviceInfo>

    suspend fun awaitLegacyScoConnection(timeoutMillis: Long)
}

/**
 * Standard implementation delegating directly to Android's [AudioManager].
 */
class AndroidAudioRoutePlatformAdapter(
    private val context: Context,
    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager,
) : AudioRoutePlatformAdapter {

    private val platformListeners =
        java.util.concurrent.ConcurrentHashMap<CommunicationDeviceChangeListener, AudioManager.OnCommunicationDeviceChangedListener>()

    override val sdkInt: Int
        get() = Build.VERSION.SDK_INT

    override var mode: Int
        get() = audioManager.mode
        set(value) {
            audioManager.mode = value
        }

    @Suppress("DEPRECATION")
    override val isBluetoothScoOn: Boolean
        get() = audioManager.isBluetoothScoOn

    @Suppress("DEPRECATION")
    override fun startBluetoothSco() {
        audioManager.startBluetoothSco()
    }

    @Suppress("DEPRECATION")
    override fun stopBluetoothSco() {
        audioManager.stopBluetoothSco()
    }

    override fun getAvailableCommunicationDevices(): List<PlatformAudioDeviceInfo> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return emptyList()
        return try {
            audioManager.availableCommunicationDevices.map(::AndroidPlatformAudioDeviceInfo)
        } catch (e: SecurityException) {
            throw BluetoothRouteException.PermissionDenied(cause = e)
        }
    }

    override fun getCommunicationDevice(): PlatformAudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return try {
            audioManager.communicationDevice?.let(::AndroidPlatformAudioDeviceInfo)
        } catch (e: SecurityException) {
            throw BluetoothRouteException.PermissionDenied(cause = e)
        }
    }

    override fun setCommunicationDevice(device: PlatformAudioDeviceInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val raw = device.rawDeviceInfo ?: return false
        return try {
            audioManager.setCommunicationDevice(raw)
        } catch (e: SecurityException) {
            throw BluetoothRouteException.PermissionDenied(cause = e)
        }
    }

    override fun clearCommunicationDevice() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                audioManager.clearCommunicationDevice()
            } catch (_: SecurityException) {
            }
        }
    }

    override fun addCommunicationDeviceChangeListener(
        executor: Executor,
        listener: CommunicationDeviceChangeListener,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val platformListener = AudioManager.OnCommunicationDeviceChangedListener { rawDevice ->
                listener.onCommunicationDeviceChanged(rawDevice?.let(::AndroidPlatformAudioDeviceInfo))
            }
            platformListeners[listener] = platformListener
            audioManager.addOnCommunicationDeviceChangedListener(executor, platformListener)
        }
    }

    override fun removeCommunicationDeviceChangeListener(
        listener: CommunicationDeviceChangeListener,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val platformListener = platformListeners.remove(listener) ?: return
            audioManager.removeOnCommunicationDeviceChangedListener(platformListener)
        }
    }

    override fun getInputDevices(): List<PlatformAudioDeviceInfo> {
        return try {
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .map(::AndroidPlatformAudioDeviceInfo)
        } catch (e: SecurityException) {
            throw BluetoothRouteException.PermissionDenied("입력 오디오 장치 목록 조회 권한이 거부되었습니다.", cause = e)
        }
    }

    override fun getOutputDevices(): List<PlatformAudioDeviceInfo> {
        return try {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .map(::AndroidPlatformAudioDeviceInfo)
        } catch (e: SecurityException) {
            throw BluetoothRouteException.PermissionDenied("출력 오디오 장치 목록 조회 권한이 거부되었습니다.", cause = e)
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    override suspend fun awaitLegacyScoConnection(timeoutMillis: Long) {
        if (audioManager.isBluetoothScoOn) return

        val unregistered = AtomicBoolean(false)
        lateinit var scoReceiver: BroadcastReceiver

        fun unregisterReceiverSafely() {
            if (unregistered.compareAndSet(false, true)) {
                runCatching { context.unregisterReceiver(scoReceiver) }
            }
        }

        try {
            withTimeout(timeoutMillis) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    scoReceiver = object : BroadcastReceiver() {
                        override fun onReceive(c: Context?, intent: Intent?) {
                            if (intent?.action != AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) return

                            // If this is a stale sticky broadcast delivered upon registration, ignore it.
                            if (isInitialStickyBroadcast) {
                                return
                            }

                            val state = intent.getIntExtra(
                                AudioManager.EXTRA_SCO_AUDIO_STATE,
                                AudioManager.SCO_AUDIO_STATE_ERROR,
                            )
                            when (state) {
                                AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                                    if (continuation.isActive) {
                                        unregisterReceiverSafely()
                                        continuation.resume(Unit)
                                    }
                                }
                                AudioManager.SCO_AUDIO_STATE_ERROR -> {
                                    if (continuation.isActive) {
                                        unregisterReceiverSafely()
                                        continuation.resumeWithException(
                                            BluetoothRouteException.LegacyScoFailed(state)
                                        )
                                    }
                                }
                                AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                                    // Ignore transitional disconnects if we just requested start
                                }
                            }
                        }
                    }

                    ContextCompat.registerReceiver(
                        context,
                        scoReceiver,
                        IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED),
                        ContextCompat.RECEIVER_EXPORTED,
                    )
                    continuation.invokeOnCancellation { unregisterReceiverSafely() }

                    audioManager.isBluetoothScoOn = true
                    audioManager.startBluetoothSco()
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            unregisterReceiverSafely()
            throw BluetoothRouteException.LegacyScoTimeout()
        } catch (cancellation: CancellationException) {
            unregisterReceiverSafely()
            throw cancellation
        } catch (error: Throwable) {
            unregisterReceiverSafely()
            throw error
        }
    }
}

/**
 * Controller managing Bluetooth audio route leasing, sink selection on Android 12+,
 * SCO lifecycle on API 29-30, and routing verification for [AudioRecord].
 */
class BluetoothAudioRouteController(
    private val platformAdapter: AudioRoutePlatformAdapter,
    private val mainExecutor: Executor = Executor { command -> command.run() },
) {
    constructor(context: Context) : this(AndroidAudioRoutePlatformAdapter(context), ContextCompat.getMainExecutor(context))

    companion object {
        const val COMMUNICATION_ROUTE_TIMEOUT_MILLIS: Long = 3_000L
        const val LEGACY_SCO_TIMEOUT_MILLIS: Long = 4_000L
        const val ROUTED_DEVICE_TIMEOUT_MILLIS: Long = 2_000L
        const val ROUTED_DEVICE_POLL_INTERVAL_MILLIS: Long = 50L
    }

    /**
     * Resolves a compatible communication sink device from [AudioRoutePlatformAdapter.getAvailableCommunicationDevices].
     *
     * Strict contract:
     * - Only considers sinks (`isSink == true`).
     * - Matches classic SCO (`TYPE_BLUETOOTH_SCO`) only with SCO sinks.
     * - Matches BLE Headset (`TYPE_BLE_HEADSET`) only with BLE Headset sinks.
     * - Prioritizes exact non-blank address match.
     * - Then prioritizes exact non-blank product name match.
     * - Throws [BluetoothRouteException.SinkNotFound] if no compatible sink is available.
     */
    fun resolveCompatibleSink(requestedInput: PlatformAudioDeviceInfo): PlatformAudioDeviceInfo {
        val available = platformAdapter.getAvailableCommunicationDevices()
        val expectedType = requestedInput.type

        // 1. Sinks only, strictly matching Bluetooth profile type
        val candidateSinks = available.filter { it.isSink && it.type == expectedType }
        if (candidateSinks.isEmpty()) {
            throw BluetoothRouteException.SinkNotFound(
                "호환되는 Bluetooth 통신 출력 장치(sink)를 찾을 수 없습니다: " +
                    "name=${requestedInput.productName} (type=$expectedType, id=${requestedInput.id})"
            )
        }

        // Strict identity precedence:
        // A) If requested address is nonblank and any candidates expose addresses, only exact address matches are eligible.
        // A definite address mismatch must NOT fall through to product name or sole-candidate acceptance.
        val reqAddress = requestedInput.address.trim()
        val candidatesWithAddress = candidateSinks.filter { it.address.isNotBlank() }

        if (reqAddress.isNotEmpty() && candidatesWithAddress.isNotEmpty()) {
            val exactAddressMatches = candidatesWithAddress.filter {
                it.address.equals(reqAddress, ignoreCase = true)
            }
            if (exactAddressMatches.isEmpty()) {
                throw BluetoothRouteException.SinkNotFound(
                    "요청된 Bluetooth 장치 주소($reqAddress)와 일치하는 통신 출력 장치를 찾을 수 없습니다."
                )
            }
            if (exactAddressMatches.size == 1) {
                return exactAddressMatches.single()
            }
            // If multiple sinks have the exact same address, disambiguate with product name
            val reqName = requestedInput.productName.toString().trim()
            if (reqName.isNotEmpty() && !isGenericPlatformLabel(reqName)) {
                val nameMatches = exactAddressMatches.filter {
                    isCompatibleIdentityName(reqName, it.productName.toString())
                }
                if (nameMatches.size == 1) return nameMatches.single()
            }
            throw BluetoothRouteException.AmbiguousRoute(
                "동일 주소($reqAddress)의 통신 출력 장치가 여러 개 발견되었으나 식별 정보가 일치하지 않습니다: " +
                    exactAddressMatches.joinToString { "${it.productName}(id=${it.id})" }
            )
        }

        // B) Only when address comparison is unavailable may normalized product name be used.
        val reqName = requestedInput.productName.toString().trim()
        val isReqNameMeaningful = reqName.isNotEmpty() && !isGenericPlatformLabel(reqName)

        if (isReqNameMeaningful) {
            val nameMatches = candidateSinks.filter {
                it.productName.isNotBlank() && isCompatibleIdentityName(reqName, it.productName.toString())
            }
            if (nameMatches.size == 1) {
                return nameMatches.single()
            }
            if (nameMatches.size > 1) {
                throw BluetoothRouteException.AmbiguousRoute(
                    "동일 프로필 및 이름을 가진 통신 출력 장치가 여러 개 발견되었습니다: " +
                        nameMatches.joinToString { "${it.productName}(id=${it.id}, addr=${it.address})" }
                )
            }
        }

        // C) If multiple candidates exist without identity match, reject as ambiguous
        if (candidateSinks.size > 1) {
            throw BluetoothRouteException.AmbiguousRoute(
                "Bluetooth 통신 출력 장치가 여러 개 발견되었으나 식별 정보가 일치하지 않습니다: " +
                    candidateSinks.joinToString { "${it.productName}(id=${it.id}, addr=${it.address})" }
            )
        }

        // D) Sole-candidate fallback is allowed only when usable address/name identity is unavailable,
        // not when identity is observably contradictory.
        val soleCandidate = candidateSinks.single()
        val soleName = soleCandidate.productName.toString().trim()
        if (isReqNameMeaningful && soleName.isNotEmpty() && !isGenericPlatformLabel(soleName)) {
            if (!isCompatibleIdentityName(reqName, soleName)) {
                throw BluetoothRouteException.SinkNotFound(
                    "요청된 Bluetooth 장치 이름($reqName)과 일치하는 통신 출력 장치를 찾을 수 없습니다 (후보: $soleName)"
                )
            }
        }

        return soleCandidate
    }

    /**
     * Re-resolves the active input device from [AudioRoutePlatformAdapter.getInputDevices]
     * after the communication route has been activated.
     */
    fun resolveActiveInput(
        requestedInput: PlatformAudioDeviceInfo,
        selectedSink: PlatformAudioDeviceInfo,
    ): PlatformAudioDeviceInfo {
        val currentInputs = platformAdapter.getInputDevices().filter { it.isSource && it.type == requestedInput.type }
        if (currentInputs.isEmpty()) {
            throw BluetoothRouteException.DeviceMismatch(
                "Bluetooth 통신 경로 설정 후 활성 마이크 입력을 찾지 못했습니다: sink=${selectedSink.productName}"
            )
        }

        // Strict identity precedence:
        // A) If requested or sink address is nonblank and any candidates expose addresses, only exact address matches are eligible.
        // A definite address mismatch must NOT fall through to product name or sole-candidate acceptance.
        val targetAddress = requestedInput.address.trim().ifEmpty { selectedSink.address.trim() }
        val candidatesWithAddress = currentInputs.filter { it.address.isNotBlank() }

        if (targetAddress.isNotEmpty() && candidatesWithAddress.isNotEmpty()) {
            val exactAddressMatches = candidatesWithAddress.filter {
                it.address.equals(targetAddress, ignoreCase = true)
            }
            if (exactAddressMatches.isEmpty()) {
                throw BluetoothRouteException.DeviceMismatch(
                    "요청된 Bluetooth 장치 주소($targetAddress)와 일치하는 활성 마이크 입력 장치를 찾을 수 없습니다."
                )
            }
            if (exactAddressMatches.size == 1) {
                return exactAddressMatches.single()
            }
            val targetName = requestedInput.productName.toString().trim().ifEmpty {
                selectedSink.productName.toString().trim()
            }
            if (targetName.isNotEmpty() && !isGenericPlatformLabel(targetName)) {
                val nameMatches = exactAddressMatches.filter {
                    isCompatibleIdentityName(targetName, it.productName.toString())
                }
                if (nameMatches.size == 1) return nameMatches.single()
            }
            throw BluetoothRouteException.AmbiguousRoute(
                "동일 주소($targetAddress)의 활성 마이크 입력 장치가 여러 개 발견되었으나 식별 정보가 일치하지 않습니다: " +
                    exactAddressMatches.joinToString { "${it.productName}(id=${it.id})" }
            )
        }

        // B) Only when address comparison is unavailable may normalized product name be used.
        val targetName = requestedInput.productName.toString().trim().ifEmpty {
            selectedSink.productName.toString().trim()
        }
        val isTargetNameMeaningful = targetName.isNotEmpty() && !isGenericPlatformLabel(targetName)

        if (isTargetNameMeaningful) {
            val nameMatches = currentInputs.filter {
                it.productName.isNotBlank() && isCompatibleIdentityName(targetName, it.productName.toString())
            }
            if (nameMatches.size == 1) {
                return nameMatches.single()
            }
            if (nameMatches.size > 1) {
                throw BluetoothRouteException.AmbiguousRoute(
                    "동일 프로필 및 이름을 가진 활성 마이크 입력 장치가 여러 개 발견되었습니다: " +
                        nameMatches.joinToString { "${it.productName}(id=${it.id}, addr=${it.address})" }
                )
            }
        }

        // C) Match requested input ID if present and not contradictory
        val idMatch = currentInputs.firstOrNull { it.id == requestedInput.id }
        if (idMatch != null) return idMatch

        // D) If multiple candidates exist without identity match, reject as ambiguous
        if (currentInputs.size > 1) {
            throw BluetoothRouteException.AmbiguousRoute(
                "동일 프로필(type=${requestedInput.type})의 활성 마이크 입력 장치가 여러 개 발견되었으나 식별 정보가 일치하지 않습니다: " +
                    currentInputs.joinToString { "${it.productName}(id=${it.id}, addr=${it.address})" }
            )
        }

        // E) Sole-candidate fallback is allowed only when usable address/name identity is unavailable,
        // not when identity is observably contradictory.
        val soleCandidate = currentInputs.single()
        val soleName = soleCandidate.productName.toString().trim()
        if (isTargetNameMeaningful && soleName.isNotEmpty() && !isGenericPlatformLabel(soleName)) {
            if (!isCompatibleIdentityName(targetName, soleName)) {
                throw BluetoothRouteException.DeviceMismatch(
                    "요청된 Bluetooth 장치 이름($targetName)과 일치하는 활성 마이크 입력 장치를 찾을 수 없습니다 (후보: $soleName)"
                )
            }
        }

        return soleCandidate
    }

    /**
     * Acquires an audio route lease for the requested audio input device.
     * If the requested device is not Bluetooth, returns [AudioRouteLease.NONE].
     *
     * On failure, cleans up immediately and throws an actionable [BluetoothRouteException].
     * Silent fallback to built-in mic is strictly prohibited.
     */
    suspend fun acquireRoute(requestedInputId: Int?): AudioRouteLease {
        if (requestedInputId == null) {
            return AudioRouteLease.NONE
        }

        val requestedInput = try {
            platformAdapter.getInputDevices().firstOrNull { it.id == requestedInputId }
        } catch (e: SecurityException) {
            throw BluetoothRouteException.PermissionDenied("입력 오디오 장치 목록 조회 권한이 거부되었습니다.", cause = e)
        } ?: throw BluetoothRouteException.DeviceMismatch(
            "요청된 오디오 입력 장치를 찾을 수 없습니다: id=$requestedInputId"
        )

        if (!requestedInput.isBluetoothInput()) {
            return AudioRouteLease.NONE
        }

        return if (platformAdapter.sdkInt >= Build.VERSION_CODES.S) {
            acquireRouteApi31(requestedInput)
        } else {
            acquireRouteLegacySco(requestedInput)
        }
    }

    private suspend fun acquireRouteApi31(requestedInput: PlatformAudioDeviceInfo): AudioRouteLease {
        val selectedSink = resolveCompatibleSink(requestedInput)
        val previousMode = platformAdapter.mode

        // Enter communication mode
        platformAdapter.mode = AudioManager.MODE_IN_COMMUNICATION

        // Request communication sink
        val setSuccess = platformAdapter.setCommunicationDevice(selectedSink)
        if (!setSuccess) {
            platformAdapter.mode = previousMode
            throw BluetoothRouteException.SinkRejected(
                "Android 플랫폼이 통신 장치 설정을 거부했습니다: sink=${selectedSink.productName} (id=${selectedSink.id})"
            )
        }

        // Await confirmation of communication device
        var listenerToRemove: CommunicationDeviceChangeListener? = null
        try {
            val currentDevice = platformAdapter.getCommunicationDevice()
            if (currentDevice?.id != selectedSink.id) {
                withTimeout(COMMUNICATION_ROUTE_TIMEOUT_MILLIS) {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        val listener = CommunicationDeviceChangeListener { changedDevice ->
                            if (changedDevice?.id == selectedSink.id && continuation.isActive) {
                                continuation.resume(Unit)
                            }
                        }
                        listenerToRemove = listener
                        platformAdapter.addCommunicationDeviceChangeListener(mainExecutor, listener)
                        continuation.invokeOnCancellation {
                            platformAdapter.removeCommunicationDeviceChangeListener(listener)
                        }
                    }
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            listenerToRemove?.let { platformAdapter.removeCommunicationDeviceChangeListener(it) }
            platformAdapter.clearCommunicationDevice()
            platformAdapter.mode = previousMode
            throw BluetoothRouteException.RouteTimeout(
                "Bluetooth 통신 경로 활성화 대기 시간 초과: ${selectedSink.productName}"
            )
        } catch (cancellation: CancellationException) {
            listenerToRemove?.let { platformAdapter.removeCommunicationDeviceChangeListener(it) }
            platformAdapter.clearCommunicationDevice()
            platformAdapter.mode = previousMode
            throw cancellation
        } catch (error: Throwable) {
            listenerToRemove?.let { platformAdapter.removeCommunicationDeviceChangeListener(it) }
            platformAdapter.clearCommunicationDevice()
            platformAdapter.mode = previousMode
            throw error
        } finally {
            listenerToRemove?.let { platformAdapter.removeCommunicationDeviceChangeListener(it) }
        }

        // Re-resolve active input device after route is established
        val activeInput = try {
            resolveActiveInput(requestedInput, selectedSink)
        } catch (e: Throwable) {
            platformAdapter.clearCommunicationDevice()
            platformAdapter.mode = previousMode
            throw e
        }

        val closed = AtomicBoolean(false)
        return object : AudioRouteLease {
            override val isBluetooth: Boolean = true
            override val requestedInput: PlatformAudioDeviceInfo = requestedInput
            override val selectedSink: PlatformAudioDeviceInfo = selectedSink
            override val activeInput: PlatformAudioDeviceInfo = activeInput
            override val previousMode: Int = previousMode

            override fun close() {
                if (closed.compareAndSet(false, true)) {
                    platformAdapter.clearCommunicationDevice()
                    platformAdapter.mode = previousMode
                }
            }
        }
    }

    private suspend fun acquireRouteLegacySco(requestedInput: PlatformAudioDeviceInfo): AudioRouteLease {
        val previousMode = platformAdapter.mode
        val startedSco = !platformAdapter.isBluetoothScoOn

        if (startedSco) {
            platformAdapter.mode = AudioManager.MODE_IN_COMMUNICATION
            try {
                platformAdapter.awaitLegacyScoConnection(LEGACY_SCO_TIMEOUT_MILLIS)
            } catch (error: Throwable) {
                platformAdapter.stopBluetoothSco()
                platformAdapter.mode = previousMode
                throw error
            }
        }

        val activeInput = platformAdapter.getInputDevices()
            .firstOrNull { it.isSource && it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            ?: requestedInput

        val closed = AtomicBoolean(false)
        return object : AudioRouteLease {
            override val isBluetooth: Boolean = true
            override val requestedInput: PlatformAudioDeviceInfo = requestedInput
            override val selectedSink: PlatformAudioDeviceInfo? = null
            override val activeInput: PlatformAudioDeviceInfo = activeInput
            override val previousMode: Int = previousMode

            override fun close() {
                if (closed.compareAndSet(false, true)) {
                    if (startedSco) {
                        platformAdapter.stopBluetoothSco()
                        platformAdapter.mode = previousMode
                    }
                }
            }
        }
    }

    /**
     * Verifies that the actual routed device on an initialized/recording [AudioRecord]
     * is compatible with the requested route.
     *
     * Strict requirements:
     * - Null is rejected when Bluetooth was requested.
     * - Requested SCO must route to SCO (AudioDeviceInfo.TYPE_BLUETOOTH_SCO).
     * - Requested BLE must route to BLE (AudioDeviceInfo.TYPE_BLE_HEADSET).
     * - Equal addresses prove identity and bypass display name discrepancies.
     * - Unequal addresses reject even if product names match.
     * - If address cannot be compared, normalized name is checked only when meaningful (avoiding false rejections on generic platform labels).
     */
    fun verifyRoutedDevice(
        requestedInput: PlatformAudioDeviceInfo?,
        routedDevice: AudioDeviceInfo?,
    ) {
        verifyRoutedDevice(requestedInput, routedDevice?.let(::AndroidPlatformAudioDeviceInfo))
    }

    /**
     * Overload taking [PlatformAudioDeviceInfo] for pure JVM test verification.
     */
    fun verifyRoutedDevice(
        requestedInput: PlatformAudioDeviceInfo?,
        routedDevice: PlatformAudioDeviceInfo?,
    ) {
        if (requestedInput == null || !requestedInput.isBluetoothInput()) {
            return
        }

        if (routedDevice == null) {
            throw BluetoothRouteException.DeviceMismatch(
                "Bluetooth 마이크(${requestedInput.productName})가 요청되었으나 실제 오디오 경로가 확인되지 않았습니다 (routedDevice=null)."
            )
        }

        // Profile match: SCO must be SCO, BLE must be BLE
        when (requestedInput.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> {
                if (routedDevice.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                    throw BluetoothRouteException.DeviceMismatch(
                        "Classic Bluetooth SCO 마이크(${requestedInput.productName})가 요청되었으나 실제 라우팅된 장치 유형은 " +
                            "${routedDevice.type}(${routedDevice.productName})입니다."
                    )
                }
            }
            AudioDeviceInfo.TYPE_BLE_HEADSET -> {
                if (routedDevice.type != AudioDeviceInfo.TYPE_BLE_HEADSET) {
                    throw BluetoothRouteException.DeviceMismatch(
                        "BLE Audio 마이크(${requestedInput.productName})가 요청되었으나 실제 라우팅된 장치 유형은 " +
                            "${routedDevice.type}(${routedDevice.productName})입니다."
                    )
                }
            }
            else -> {
                if (routedDevice.type != requestedInput.type) {
                    throw BluetoothRouteException.DeviceMismatch(
                        "Bluetooth 마이크(${requestedInput.productName}, type=${requestedInput.type})가 요청되었으나 " +
                            "실제 라우팅된 장치 유형은 ${routedDevice.type}(${routedDevice.productName})입니다."
                    )
                }
            }
        }

        // Identity check:
        // 1. Equal addresses prove identity; do not reject later because Android reports different source/sink display names.
        val reqAddr = requestedInput.address.trim()
        val routedAddr = routedDevice.address.trim()

        if (reqAddr.isNotEmpty() && routedAddr.isNotEmpty()) {
            if (!reqAddr.equals(routedAddr, ignoreCase = true)) {
                throw BluetoothRouteException.DeviceMismatch(
                    "요청된 Bluetooth 마이크 주소($reqAddr)와 실제 라우팅된 장치 주소($routedAddr)가 일치하지 않습니다."
                )
            }
            return
        }

        // 2. If address cannot be compared, use normalized name only when meaningful.
        // Do not make a generic platform label cause a false rejection after the controller already selected an unambiguous single route.
        val reqName = requestedInput.productName.toString().trim()
        val routedName = routedDevice.productName.toString().trim()

        if (reqName.isNotEmpty() && routedName.isNotEmpty()) {
            if (!isGenericPlatformLabel(reqName) && !isGenericPlatformLabel(routedName)) {
                if (!isCompatibleIdentityName(reqName, routedName)) {
                    throw BluetoothRouteException.DeviceMismatch(
                        "요청된 Bluetooth 마이크 이름($reqName)과 실제 라우팅된 장치 이름($routedName)이 일치하지 않습니다."
                    )
                }
            }
        }
    }

    /**
     * Bounded awaiter and verifier for [AudioRecord.routedDevice].
     *
     * Repeatedly polls [getRoutedDevice] within [timeoutMillis] until [verifyRoutedDevice]
     * succeeds on a non-null device.
     *
     * If timeout expires without a valid routed device, throws [BluetoothRouteException].
     */
    suspend fun awaitAndVerifyRoutedDevice(
        requestedInput: PlatformAudioDeviceInfo?,
        timeoutMillis: Long = ROUTED_DEVICE_TIMEOUT_MILLIS,
        pollIntervalMillis: Long = ROUTED_DEVICE_POLL_INTERVAL_MILLIS,
        getRoutedDevice: () -> PlatformAudioDeviceInfo?,
    ): PlatformAudioDeviceInfo? {
        if (requestedInput == null || !requestedInput.isBluetoothInput()) {
            return getRoutedDevice()
        }

        var lastException: BluetoothRouteException? = null
        try {
            withTimeout(timeoutMillis) {
                while (true) {
                    val current = getRoutedDevice()
                    if (current != null) {
                        try {
                            verifyRoutedDevice(requestedInput, current)
                            return@withTimeout
                        } catch (e: BluetoothRouteException) {
                            lastException = e
                        }
                    } else {
                        lastException = BluetoothRouteException.DeviceMismatch(
                            "Bluetooth 마이크(${requestedInput.productName}) 경로 대기 중 routedDevice가 null입니다."
                        )
                    }
                    delay(pollIntervalMillis)
                }
            }
        } catch (_: TimeoutCancellationException) {
            throw lastException ?: BluetoothRouteException.RouteTimeout(
                "Bluetooth 마이크(${requestedInput.productName}) 오디오 라우팅 확인 대기 시간(${timeoutMillis}ms) 초과"
            )
        }

        val finalDevice = getRoutedDevice()
        verifyRoutedDevice(requestedInput, finalDevice)
        return finalDevice
    }

    private fun isGenericPlatformLabel(name: String): Boolean {
        val normalized = name.trim().lowercase()
        return normalized.isBlank() ||
            normalized == "bluetooth" ||
            normalized == "bluetooth headset" ||
            normalized == "bluetooth sco" ||
            normalized == "bluetooth audio" ||
            normalized == "bluetooth audio device" ||
            normalized == "headset" ||
            normalized == "audio device"
    }

    private fun isCompatibleIdentityName(name1: String, name2: String): Boolean {
        if (name1.equals(name2, ignoreCase = true)) return true
        val clean1 = name1.replace(Regex("\\((Mic|Speaker|LE|Input|Output)\\)", RegexOption.IGNORE_CASE), "").trim()
        val clean2 = name2.replace(Regex("\\((Mic|Speaker|LE|Input|Output)\\)", RegexOption.IGNORE_CASE), "").trim()
        if (clean1.equals(clean2, ignoreCase = true)) return true
        if (clean1.isNotBlank() && clean2.isNotBlank()) {
            if (clean1.contains(clean2, ignoreCase = true) || clean2.contains(clean1, ignoreCase = true)) {
                return true
            }
        }
        return false
    }
}
