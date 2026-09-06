package app.guidecast.transmitter

import app.guidecast.core.audio.AudioInputDevice
import app.guidecast.core.audio.AudioInputKind
import app.guidecast.core.audio.AudioOutputKind
import java.util.Locale

/**
 * Selects one requested local-monitor output without depending on Android's device enumeration
 * order. The actual routed device must still be checked with [LocalMonitorFeedbackPolicy] before
 * and while PCM is rendered because [android.media.AudioTrack.setPreferredDevice] is best-effort.
 */
internal object LocalMonitorOutputRoutePlanner {
    fun eligible(
        input: AudioInputDevice,
        availableOutputs: List<LocalMonitorRoute>,
    ): List<LocalMonitorRoute> = availableOutputs
        .asSequence()
        .filter { route -> route.kind != AudioOutputKind.UNKNOWN }
        .filter { route -> allowedCombination(input.kind, route.kind) }
        .filter { route ->
            LocalMonitorFeedbackPolicy.evaluate(
                input = input,
                inputActive = true,
                output = route,
            ).mayRender
        }
        .sortedWith(routeComparator(input.kind))
        .toList()

    fun select(
        input: AudioInputDevice,
        availableOutputs: List<LocalMonitorRoute>,
    ): LocalMonitorRoute? = eligible(input, availableOutputs).firstOrNull()

    private fun allowedCombination(
        inputKind: AudioInputKind,
        outputKind: AudioOutputKind,
    ): Boolean = when (inputKind) {
        AudioInputKind.BUILT_IN -> outputKind == AudioOutputKind.BLUETOOTH ||
            outputKind == AudioOutputKind.WIRED || outputKind == AudioOutputKind.USB
        AudioInputKind.BLUETOOTH -> outputKind == AudioOutputKind.BUILT_IN_SPEAKER ||
            outputKind == AudioOutputKind.BLUETOOTH || outputKind == AudioOutputKind.WIRED ||
            outputKind == AudioOutputKind.USB
        AudioInputKind.WIRED_HEADSET,
        AudioInputKind.USB,
        -> outputKind == AudioOutputKind.BUILT_IN_SPEAKER ||
            outputKind == AudioOutputKind.BLUETOOTH || outputKind == AudioOutputKind.WIRED ||
            outputKind == AudioOutputKind.USB
        AudioInputKind.WEB_SPEAKER,
        AudioInputKind.DEVICE_PLAYBACK,
        AudioInputKind.OTHER,
        -> outputKind != AudioOutputKind.UNKNOWN
    }

    private fun routeComparator(inputKind: AudioInputKind): Comparator<LocalMonitorRoute> =
        compareBy<LocalMonitorRoute>(
            { route -> priority(inputKind, route.kind) },
            { route -> route.label.trim().lowercase(Locale.ROOT) },
            { route -> route.platformId ?: Int.MAX_VALUE },
            { route -> route.kind.ordinal },
        )

    private fun priority(
        inputKind: AudioInputKind,
        outputKind: AudioOutputKind,
    ): Int = if (inputKind == AudioInputKind.BUILT_IN) {
        when (outputKind) {
            AudioOutputKind.BLUETOOTH -> 0
            AudioOutputKind.WIRED -> 1
            AudioOutputKind.USB -> 2
            AudioOutputKind.OTHER -> 3
            AudioOutputKind.BUILT_IN_SPEAKER,
            AudioOutputKind.UNKNOWN,
            -> Int.MAX_VALUE
        }
    } else {
        when (outputKind) {
            AudioOutputKind.BUILT_IN_SPEAKER -> 0
            AudioOutputKind.BLUETOOTH -> 1
            AudioOutputKind.WIRED -> 2
            AudioOutputKind.USB -> 3
            AudioOutputKind.OTHER -> 4
            AudioOutputKind.UNKNOWN -> Int.MAX_VALUE
        }
    }
}
