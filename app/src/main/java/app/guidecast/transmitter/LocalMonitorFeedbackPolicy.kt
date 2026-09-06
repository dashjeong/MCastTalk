package app.guidecast.transmitter

import android.media.AudioDeviceInfo
import app.guidecast.core.audio.AudioInputDevice
import app.guidecast.core.audio.AudioInputKind
import app.guidecast.core.audio.AudioOutputKind
import app.guidecast.core.audio.AudioOutputDevice

internal data class LocalMonitorRoute(
    val platformId: Int?,
    val kind: AudioOutputKind,
    val label: String,
)

internal data class LocalMonitorFeedbackDecision(
    val mayRender: Boolean,
    val warning: String? = null,
)

/** Blocks only local playback; it never changes input capture or broadcast authority. */
internal object LocalMonitorFeedbackPolicy {
    fun evaluate(
        input: AudioInputDevice?,
        inputActive: Boolean,
        output: LocalMonitorRoute,
    ): LocalMonitorFeedbackDecision {
        if (!inputActive || input == null) return LocalMonitorFeedbackDecision(mayRender = true)
        if (output.kind == AudioOutputKind.UNKNOWN) {
            return LocalMonitorFeedbackDecision(
                mayRender = false,
                warning = "실제 출력 경로를 확인할 때까지 로컬 재생을 대기합니다.",
            )
        }
        if (input.kind == AudioInputKind.BUILT_IN &&
            output.kind == AudioOutputKind.BUILT_IN_SPEAKER
        ) {
            return LocalMonitorFeedbackDecision(
                mayRender = false,
                warning = "내장 마이크와 내장 스피커의 하울링을 막기 위해 로컬 출력만 중지했습니다.",
            )
        }
        if (input.kind == AudioInputKind.BLUETOOTH &&
            output.kind == AudioOutputKind.BLUETOOTH
        ) {
            val distinct = clearlyDifferentLabels(input.label, output.label)
            return LocalMonitorFeedbackDecision(
                mayRender = true,
                warning = if (distinct) {
                    "Bluetooth 마이크와 선택 출력이 서로 다른 장치입니다. 실제 경로를 계속 감시합니다."
                } else {
                    "Bluetooth 헤드셋 입출력을 함께 사용합니다. 프로필 전환 시 음질이 낮아질 수 있습니다."
                },
            )
        }
        if (input.kind == AudioInputKind.BUILT_IN && output.kind == AudioOutputKind.BLUETOOTH) {
            return LocalMonitorFeedbackDecision(
                mayRender = true,
                warning = "Bluetooth 스피커 소리가 내장 마이크로 되돌아오면 떨어져 있어도 반복음이 생길 수 있습니다. " +
                    "모니터 음량을 낮추거나 이어폰을 사용하세요. 웹 방송 음량은 바뀌지 않습니다.",
            )
        }
        if (input.kind == AudioInputKind.WIRED_HEADSET &&
            output.kind == AudioOutputKind.WIRED
        ) {
            return LocalMonitorFeedbackDecision(
                mayRender = true,
                warning = "유선 헤드셋 입출력을 함께 사용합니다. 모니터 음량을 낮게 시작하세요.",
            )
        }
        if (input.kind == AudioInputKind.USB && output.kind == AudioOutputKind.USB &&
            !clearlyDifferentLabels(input.label, output.label)
        ) {
            return LocalMonitorFeedbackDecision(
                mayRender = false,
                warning = "같거나 식별할 수 없는 USB 입출력 장치의 되먹임을 막기 위해 로컬 출력만 중지했습니다.",
            )
        }
        return LocalMonitorFeedbackDecision(mayRender = true)
    }

    private fun clearlyDifferentLabels(input: String, output: String): Boolean {
        val normalizedInput = normalizeDeviceLabel(input)
        val normalizedOutput = normalizeDeviceLabel(output)
        if (normalizedInput == null || normalizedOutput == null) return false
        return normalizedInput != normalizedOutput
    }

    private fun normalizeDeviceLabel(label: String): String? {
        val normalized = label.substringBefore('·').lowercase()
            .replace(
                Regex(
                    "bluetooth|블루투스|headset|headphone|이어폰|usb|audio|오디오|" +
                        "device|장치|microphone|마이크|speaker|스피커|통화|미디어|" +
                        "입력|출력|auracast|오라캐스트",
                ),
                "",
            )
            .replace(Regex("[^a-z0-9가-힣]+"), "")
        if (normalized.length < 3) return null
        return normalized
    }
}

internal fun AudioDeviceInfo.toLocalMonitorRoute(): LocalMonitorRoute = LocalMonitorRoute(
    platformId = id,
    kind = when (type) {
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
    },
    label = productName?.toString()?.trim().takeUnless { it.isNullOrEmpty() }
        ?: "Android 미디어 출력",
)

internal fun AudioOutputDevice.toLocalMonitorRoute(): LocalMonitorRoute = LocalMonitorRoute(
    platformId = platformId,
    kind = kind,
    label = label,
)

internal fun unknownLocalMonitorRoute(): LocalMonitorRoute = LocalMonitorRoute(
    platformId = null,
    kind = AudioOutputKind.UNKNOWN,
    label = "출력 경로 확인 중",
)
