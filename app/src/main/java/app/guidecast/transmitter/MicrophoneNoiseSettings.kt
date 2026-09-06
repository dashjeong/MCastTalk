package app.guidecast.transmitter

import android.content.Context
import app.guidecast.core.audio.MicrophoneNoiseMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class MicrophoneNoiseSettings(context: Context) {
    private val preferences = context.getSharedPreferences("microphone_noise", Context.MODE_PRIVATE)
    private val mutableMode = MutableStateFlow(runCatching {
        MicrophoneNoiseMode.valueOf(preferences.getString("mode", null) ?: "DEVICE")
    }.getOrDefault(MicrophoneNoiseMode.DEVICE))
    val mode = mutableMode.asStateFlow()
    fun select(mode: MicrophoneNoiseMode) {
        preferences.edit().putString("mode", mode.name).apply()
        mutableMode.value = mode
    }
}
