package app.guidecast.core.audio

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

internal enum class NonBluetoothMicrophoneRouteFailure {
    SELECTED_DEVICE_UNAVAILABLE, PREFERRED_DEVICE_REJECTED, ROUTE_UNCONFIRMED, NOT_AN_INPUT, DIFFERENT_TYPE, DIFFERENT_DEVICE,
}

internal class NonBluetoothMicrophoneRouteException(val reason: NonBluetoothMicrophoneRouteFailure) : IllegalStateException(
    when (reason) {
        NonBluetoothMicrophoneRouteFailure.SELECTED_DEVICE_UNAVAILABLE -> "선택한 마이크 연결을 찾을 수 없습니다. 마이크를 연결하고 다시 선택하세요."
        NonBluetoothMicrophoneRouteFailure.PREFERRED_DEVICE_REJECTED -> "기기가 선택한 마이크 입력을 허용하지 않았습니다. 마이크를 연결하고 다시 선택하세요."
        NonBluetoothMicrophoneRouteFailure.ROUTE_UNCONFIRMED -> "선택한 마이크의 실제 입력 경로를 확인하지 못했습니다. 마이크를 연결하고 다시 선택하세요."
        NonBluetoothMicrophoneRouteFailure.NOT_AN_INPUT -> "선택한 장치의 마이크 입력을 확인할 수 없습니다. 입력용 마이크를 다시 선택하세요."
        NonBluetoothMicrophoneRouteFailure.DIFFERENT_TYPE -> "선택한 마이크 종류와 실제 입력이 다릅니다. 연결을 확인하고 마이크를 다시 선택하세요."
        NonBluetoothMicrophoneRouteFailure.DIFFERENT_DEVICE -> "선택한 마이크 대신 다른 장치에서 소리가 들어옵니다. 사용할 마이크를 다시 선택하세요."
    },
)

internal fun requireSelectedMicrophoneAvailable(preferredDeviceId: Int?, resolved: PlatformAudioDeviceInfo?) {
    if (preferredDeviceId != null && resolved == null)
        throw NonBluetoothMicrophoneRouteException(NonBluetoothMicrophoneRouteFailure.SELECTED_DEVICE_UNAVAILABLE)
}

/** A preferred device request alone does not prove which microphone supplies the PCM. */
internal fun verifyNonBluetoothMicrophoneRoute(expected: PlatformAudioDeviceInfo?, routed: PlatformAudioDeviceInfo?) {
    // Bluetooth uses its existing profile/address/name policy, including Android ID changes.
    if (expected == null || expected.isBluetoothInput()) return
    if (!expected.isSource) throw NonBluetoothMicrophoneRouteException(NonBluetoothMicrophoneRouteFailure.NOT_AN_INPUT)
    if (routed == null) throw NonBluetoothMicrophoneRouteException(NonBluetoothMicrophoneRouteFailure.ROUTE_UNCONFIRMED)
    if (!routed.isSource) throw NonBluetoothMicrophoneRouteException(NonBluetoothMicrophoneRouteFailure.NOT_AN_INPUT)
    if (routed.type != expected.type) throw NonBluetoothMicrophoneRouteException(NonBluetoothMicrophoneRouteFailure.DIFFERENT_TYPE)
    if (routed.id != expected.id) throw NonBluetoothMicrophoneRouteException(NonBluetoothMicrophoneRouteFailure.DIFFERENT_DEVICE)
}

/** Checks a positive read before it can reach processing, history or a provider. */
internal inline fun <T> withVerifiedNonBluetoothMicrophoneRoute(
    expected: PlatformAudioDeviceInfo?, getRoutedDevice: () -> PlatformAudioDeviceInfo?, process: () -> T,
): T {
    if (expected != null && !expected.isBluetoothInput()) verifyNonBluetoothMicrophoneRoute(expected, getRoutedDevice())
    return process()
}

/** No PCM is read until the selected wired/USB/built-in route is confirmed. */
internal suspend fun awaitNonBluetoothMicrophoneRoute(
    expected: PlatformAudioDeviceInfo?, timeoutMillis: Long = 2_000, pollIntervalMillis: Long = 25,
    getRoutedDevice: () -> PlatformAudioDeviceInfo?,
): PlatformAudioDeviceInfo? {
    if (expected == null || expected.isBluetoothInput()) return getRoutedDevice()
    require(timeoutMillis > 0 && pollIntervalMillis > 0)
    var lastMismatch: NonBluetoothMicrophoneRouteException? = null
    val confirmed = withTimeoutOrNull(timeoutMillis) {
        while (true) {
            val actual = getRoutedDevice()
            try {
                verifyNonBluetoothMicrophoneRoute(expected, actual)
                return@withTimeoutOrNull requireNotNull(actual)
            } catch (mismatch: NonBluetoothMicrophoneRouteException) { lastMismatch = mismatch }
            delay(pollIntervalMillis)
        }
        @Suppress("UNREACHABLE_CODE") null
    }
    return confirmed ?: throw (lastMismatch ?: NonBluetoothMicrophoneRouteException(NonBluetoothMicrophoneRouteFailure.ROUTE_UNCONFIRMED))
}
