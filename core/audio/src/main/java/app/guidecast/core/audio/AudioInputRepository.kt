package app.guidecast.core.audio

import kotlinx.coroutines.flow.StateFlow

interface AudioInputRepository : AutoCloseable {
    val availableDevices: StateFlow<List<AudioInputDevice>>
    val availableOutputDevices: StateFlow<List<AudioOutputDevice>>
    val preference: StateFlow<AudioInputPreference>
    val selectedDevice: StateFlow<AudioInputDevice?>
    val playbackCaptureDiagnostics: StateFlow<PlaybackCaptureDiagnostics>

    fun start()

    fun refresh()

    fun useAutomaticSelection()

    fun selectDevice(platformId: Int)
}
