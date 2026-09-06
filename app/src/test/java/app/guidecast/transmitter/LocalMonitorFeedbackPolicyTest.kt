package app.guidecast.transmitter

import app.guidecast.core.audio.AudioInputDevice
import app.guidecast.core.audio.AudioInputKind
import app.guidecast.core.audio.AudioOutputKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMonitorFeedbackPolicyTest {
    @Test
    fun builtInMicrophoneToBuiltInSpeakerBlocksOnlyMonitor() {
        val result = evaluate(AudioInputKind.BUILT_IN, "내장 마이크", speaker())

        assertFalse(result.mayRender)
        assertTrue(result.warning.orEmpty().contains("로컬 출력만 중지"))
    }

    @Test
    fun builtInMicrophoneToBluetoothSpeakerIsAllowed() {
        val result = evaluate(
                AudioInputKind.BUILT_IN,
                "내장 마이크",
                route(AudioOutputKind.BLUETOOTH, "JBL Flip 7"),
            )
        assertTrue(result.mayRender)
        assertTrue(result.warning.orEmpty().contains("반복음"))
    }

    @Test
    fun sameBluetoothNameIsAllowedWithProfileWarning() {
        val result = evaluate(
            AudioInputKind.BLUETOOTH,
            "Galaxy Buds3 Pro",
            route(AudioOutputKind.BLUETOOTH, "Galaxy Buds3 Pro · Bluetooth 통화 오디오"),
        )

        assertTrue(result.mayRender)
        assertTrue(result.warning.orEmpty().contains("프로필 전환"))
    }

    @Test
    fun genericBluetoothNamesAreAllowedWithProfileWarning() {
        val result = evaluate(
            AudioInputKind.BLUETOOTH,
            "Bluetooth",
            route(AudioOutputKind.BLUETOOTH, "Bluetooth"),
        )

        assertTrue(result.mayRender)
        assertTrue(result.warning.orEmpty().contains("음질이 낮아질 수 있습니다"))
    }

    @Test
    fun clearlyDifferentBluetoothNamesAreAllowedWithWarning() {
        val result = evaluate(
            AudioInputKind.BLUETOOTH,
            "Rode Wireless Pro",
            route(AudioOutputKind.BLUETOOTH, "JBL Flip 7"),
        )

        assertTrue(result.mayRender)
        assertTrue(result.warning.orEmpty().contains("서로 다른"))
    }

    @Test
    fun sameWiredHeadsetIsAllowedWithLowVolumeWarning() {
        val result = evaluate(
            AudioInputKind.WIRED_HEADSET,
            "Guide headset",
            route(AudioOutputKind.WIRED, "Guide headset"),
        )

        assertTrue(result.mayRender)
        assertTrue(result.warning.orEmpty().contains("음량을 낮게"))
    }

    @Test
    fun sameUsbDeviceIsBlockedButClearlyDifferentUsbOutputIsAllowed() {
        val same = evaluate(
            AudioInputKind.USB,
            "Rode USB",
            route(AudioOutputKind.USB, "Rode USB"),
        )
        val distinct = evaluate(
            AudioInputKind.USB,
            "Rode USB",
            route(AudioOutputKind.USB, "Focusrite USB"),
        )

        assertFalse(same.mayRender)
        assertTrue(same.warning.orEmpty().contains("USB"))
        assertTrue(distinct.mayRender)
    }

    @Test
    fun unknownRouteWaitsInsteadOfWritingAudio() {
        assertFalse(
            evaluate(
                AudioInputKind.USB,
                "USB microphone",
                unknownLocalMonitorRoute(),
            ).mayRender,
        )
    }

    @Test
    fun noActiveInputDoesNotBlockMonitoring() {
        val input = AudioInputDevice(1, AudioInputKind.BUILT_IN, "내장 마이크")
        val result = LocalMonitorFeedbackPolicy.evaluate(
            input = input,
            inputActive = false,
            output = speaker(),
        )

        assertTrue(result.mayRender)
    }

    private fun evaluate(
        inputKind: AudioInputKind,
        inputLabel: String,
        output: LocalMonitorRoute,
    ) = LocalMonitorFeedbackPolicy.evaluate(
        input = AudioInputDevice(17, inputKind, inputLabel),
        inputActive = true,
        output = output,
    )

    private fun speaker() = route(AudioOutputKind.BUILT_IN_SPEAKER, "Phone speaker")

    private fun route(kind: AudioOutputKind, label: String) = LocalMonitorRoute(
        platformId = 9,
        kind = kind,
        label = label,
    )
}
