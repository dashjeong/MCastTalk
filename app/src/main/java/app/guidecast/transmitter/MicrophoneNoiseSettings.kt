package app.guidecast.transmitter

import android.content.Context
import app.guidecast.core.audio.MicrophoneNoiseMode
import app.guidecast.core.audio.AudioInputKind
import android.media.AudioDeviceInfo
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class MicrophoneInputGroup(val label: String) {
    BUILT_IN("내장 마이크"), WIRED_USB("유선·USB 마이크"), BLUETOOTH("블루투스 마이크"), OTHER("기타 마이크");

    companion object {
        fun forKind(kind: AudioInputKind?): MicrophoneInputGroup = when (kind) {
            null, AudioInputKind.BUILT_IN -> BUILT_IN
            AudioInputKind.WIRED_HEADSET, AudioInputKind.USB -> WIRED_USB
            AudioInputKind.BLUETOOTH -> BLUETOOTH
            else -> OTHER
        }

        fun forDeviceType(type: Int?): MicrophoneInputGroup = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> BUILT_IN
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> WIRED_USB
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> BLUETOOTH
            else -> OTHER
        }
    }
}

data class MicrophoneInputProfile(val noiseMode: MicrophoneNoiseMode, val nearSpeakerFocus: Boolean)

class MicrophoneNoiseSettings internal constructor(context: Context, preferenceName: String) {
    constructor(context: Context) : this(context, "microphone_noise")
    private val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    private val mutableMode = MutableStateFlow(runCatching {
        MicrophoneNoiseMode.valueOf(preferences.getString("mode", null) ?: "DEVICE")
    }.getOrDefault(MicrophoneNoiseMode.DEVICE))
    val mode = mutableMode.asStateFlow()
    private val mutableFocus = MutableStateFlow(preferences.getBoolean("near_speaker_focus", false))
    val nearSpeakerFocus = mutableFocus.asStateFlow()
    private val mutableProfiles = MutableStateFlow(readProfiles())
    val profiles = mutableProfiles.asStateFlow()

    private fun readProfiles() = MicrophoneInputGroup.entries.associateWith { group ->
        MicrophoneInputProfile(
            runCatching {
                MicrophoneNoiseMode.valueOf(preferences.getString("mode_${group.name}", null) ?: mutableMode.value.name)
            }.getOrDefault(mutableMode.value),
            preferences.getBoolean("focus_${group.name}", mutableFocus.value),
        )
    }

    fun profileFor(kind: AudioInputKind?) = mutableProfiles.value.getValue(MicrophoneInputGroup.forKind(kind))

    fun selectProfile(group: MicrophoneInputGroup, profile: MicrophoneInputProfile) {
        preferences.edit().putString("mode_${group.name}", profile.noiseMode.name)
            .putBoolean("focus_${group.name}", profile.nearSpeakerFocus).apply()
        mutableProfiles.value = readProfiles()
    }

    fun exportProfiles(): JSONObject = JSONObject().apply {
        mutableProfiles.value.forEach { (group, profile) ->
            put(group.name, JSONObject().put("noiseMode", profile.noiseMode.name)
                .put("nearSpeakerFocus", profile.nearSpeakerFocus))
        }
    }

    fun importProfiles(value: JSONObject) {
        MicrophoneInputGroup.entries.forEach { group ->
            value.optJSONObject(group.name)?.let { row ->
                val previous = mutableProfiles.value.getValue(group)
                selectProfile(group, MicrophoneInputProfile(
                    if (row.has("noiseMode")) MicrophoneNoiseMode.valueOf(row.getString("noiseMode")) else previous.noiseMode,
                    if (row.has("nearSpeakerFocus")) row.getBoolean("nearSpeakerFocus") else previous.nearSpeakerFocus,
                ))
            }
        }
    }
    fun selectFocus(enabled: Boolean) {
        preferences.edit().putBoolean("near_speaker_focus", enabled).apply()
        mutableFocus.value = enabled
        mutableProfiles.value = readProfiles()
    }
    fun select(mode: MicrophoneNoiseMode) {
        preferences.edit().putString("mode", mode.name).apply()
        mutableMode.value = mode
        mutableProfiles.value = readProfiles()
    }
}
