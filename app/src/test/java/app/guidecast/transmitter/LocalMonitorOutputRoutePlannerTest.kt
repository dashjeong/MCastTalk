package app.guidecast.transmitter

import app.guidecast.core.audio.AudioInputDevice
import app.guidecast.core.audio.AudioInputKind
import app.guidecast.core.audio.AudioOutputKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalMonitorOutputRoutePlannerTest {
    @Test
    fun builtInMicrophoneOffersOnlyBluetoothWiredAndUsbInPreferredOrder() {
        val eligible = LocalMonitorOutputRoutePlanner.eligible(
            input(AudioInputKind.BUILT_IN, "내장 마이크"),
            listOf(
                route(1, AudioOutputKind.BUILT_IN_SPEAKER, "Phone speaker"),
                route(2, AudioOutputKind.USB, "USB DAC"),
                route(3, AudioOutputKind.WIRED, "유선 이어폰"),
                route(4, AudioOutputKind.BLUETOOTH, "JBL Flip 7"),
                route(5, AudioOutputKind.OTHER, "HDMI"),
                route(null, AudioOutputKind.UNKNOWN, "출력 경로 확인 중"),
            ),
        )

        assertEquals(listOf(4, 3, 2), eligible.map(LocalMonitorRoute::platformId))
    }

    @Test
    fun builtInMicrophoneFallsBackToWiredThenUsb() {
        val input = input(AudioInputKind.BUILT_IN, "내장 마이크")
        val usb = route(2, AudioOutputKind.USB, "USB DAC")
        val wired = route(3, AudioOutputKind.WIRED, "유선 이어폰")

        assertEquals(
            wired,
            LocalMonitorOutputRoutePlanner.select(input, listOf(usb, wired)),
        )
        assertEquals(
            usb,
            LocalMonitorOutputRoutePlanner.select(input, listOf(usb)),
        )
    }

    @Test
    fun builtInMicrophoneReturnsNullWhenOnlyPhoneSpeakerOrUnknownIsAvailable() {
        val selected = select(
            AudioInputKind.BUILT_IN,
            "내장 마이크",
            route(1, AudioOutputKind.BUILT_IN_SPEAKER, "Phone speaker"),
            route(5, AudioOutputKind.OTHER, "HDMI"),
            route(null, AudioOutputKind.UNKNOWN, "출력 경로 확인 중"),
        )

        assertNull(selected)
    }

    @Test
    fun bluetoothInputOffersSupportedChoicesAndDefaultsToPhoneSpeaker() {
        val eligible = LocalMonitorOutputRoutePlanner.eligible(
            input(AudioInputKind.BLUETOOTH, "Galaxy Buds3 Pro"),
            listOf(
                route(8, AudioOutputKind.WIRED, "유선 출력"),
                route(7, AudioOutputKind.BLUETOOTH, "Galaxy Buds3 Pro"),
                route(2, AudioOutputKind.USB, "USB DAC"),
                route(1, AudioOutputKind.BUILT_IN_SPEAKER, "Phone speaker"),
                route(5, AudioOutputKind.OTHER, "HDMI"),
            ),
        )

        assertEquals(listOf(1, 7, 8, 2), eligible.map(LocalMonitorRoute::platformId))
        assertEquals(1, eligible.first().platformId)
    }

    @Test
    fun sameBluetoothOutputRemainsAnExplicitEligibleChoice() {
        val eligible = LocalMonitorOutputRoutePlanner.eligible(
            input(AudioInputKind.BLUETOOTH, "Rode Wireless Pro"),
            listOf(
                route(10, AudioOutputKind.BLUETOOTH, "Rode Wireless Pro"),
                route(11, AudioOutputKind.BLUETOOTH, "JBL Flip 7"),
            ),
        )

        assertEquals(listOf(11, 10), eligible.map(LocalMonitorRoute::platformId))
    }

    @Test
    fun sameUsbOutputIsRemovedWhileDistinctUsbOutputRemainsSelectable() {
        val eligible = LocalMonitorOutputRoutePlanner.eligible(
            input(AudioInputKind.USB, "Rode USB"),
            listOf(
                route(10, AudioOutputKind.USB, "Rode USB"),
                route(11, AudioOutputKind.USB, "Focusrite USB"),
            ),
        )

        assertEquals(listOf(11), eligible.map(LocalMonitorRoute::platformId))
    }

    @Test
    fun virtualAndOtherInputsPreferPhoneSpeakerButKeepExplicitExternalChoices() {
        val inputKinds = listOf(
            AudioInputKind.WEB_SPEAKER,
            AudioInputKind.DEVICE_PLAYBACK,
            AudioInputKind.OTHER,
        )
        val outputs = listOf(
            route(8, AudioOutputKind.WIRED, "유선 출력"),
            route(7, AudioOutputKind.BLUETOOTH, "JBL Flip 7"),
            route(6, AudioOutputKind.OTHER, "HDMI"),
            route(1, AudioOutputKind.BUILT_IN_SPEAKER, "Phone speaker"),
        )

        inputKinds.forEach { kind ->
            val eligible = LocalMonitorOutputRoutePlanner.eligible(input(kind, "입력 $kind"), outputs)
            assertEquals("Unexpected default for $kind", 1, eligible.first().platformId)
            assertEquals("Unexpected choices for $kind", 4, eligible.size)
        }
    }

    @Test
    fun usbInputReturnsNullWhenOnlySameUsbOrUnknownRouteExists() {
        val selected = select(
            AudioInputKind.USB,
            "Rode USB",
            route(10, AudioOutputKind.USB, "Rode USB"),
            route(null, AudioOutputKind.UNKNOWN, "출력 경로 확인 중"),
        )

        assertNull(selected)
    }

    @Test
    fun bluetoothSelectionUsesDeterministicLabelOrderingForExplicitChoices() {
        val input = input(AudioInputKind.BLUETOOTH, "Guide microphone")
        val alpha = route(21, AudioOutputKind.BLUETOOTH, "Alpha speaker")
        val zulu = route(20, AudioOutputKind.BLUETOOTH, "Zulu speaker")

        val forward = LocalMonitorOutputRoutePlanner.eligible(input, listOf(zulu, alpha))
        val reversed = LocalMonitorOutputRoutePlanner.eligible(input, listOf(alpha, zulu))

        assertEquals(listOf(alpha, zulu), forward)
        assertEquals(listOf(alpha, zulu), reversed)
    }

    @Test
    fun wiredAndUsbInputsPreferPhoneSpeakerWhenItIsAvailable() {
        listOf(AudioInputKind.WIRED_HEADSET, AudioInputKind.USB).forEach { inputKind ->
            val selected = select(
                inputKind,
                "외부 입력",
                route(9, AudioOutputKind.BLUETOOTH, "JBL Flip 7"),
                route(1, AudioOutputKind.BUILT_IN_SPEAKER, "Phone speaker"),
            )

            assertEquals("Unexpected route for $inputKind", 1, selected?.platformId)
        }
    }

    @Test
    fun bluetoothInputAllowsSameBluetoothOutputWithWarningPolicy() {
        val selected = select(
            AudioInputKind.BLUETOOTH,
            "Rode Wireless Pro",
            route(10, AudioOutputKind.BLUETOOTH, "Rode Wireless Pro"),
        )

        assertEquals(10, selected?.platformId)
    }

    private fun select(
        inputKind: AudioInputKind,
        inputLabel: String,
        vararg routes: LocalMonitorRoute,
    ): LocalMonitorRoute? = LocalMonitorOutputRoutePlanner.select(
        input = input(inputKind, inputLabel),
        availableOutputs = routes.toList(),
    )

    private fun input(kind: AudioInputKind, label: String) = AudioInputDevice(
        platformId = 17,
        kind = kind,
        label = label,
    )

    private fun route(
        platformId: Int?,
        kind: AudioOutputKind,
        label: String,
    ) = LocalMonitorRoute(
        platformId = platformId,
        kind = kind,
        label = label,
    )
}
