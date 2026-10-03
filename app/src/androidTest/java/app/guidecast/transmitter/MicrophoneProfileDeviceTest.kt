package app.guidecast.transmitter

import androidx.test.platform.app.InstrumentationRegistry
import app.guidecast.core.audio.AudioInputKind
import app.guidecast.core.audio.MicrophoneNoiseMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MicrophoneProfileDeviceTest {
    @Test fun independentInputProfilesPersistAndPortableBackupExcludesUnknownFields() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "microphone-profile-test-${System.nanoTime()}"
        val restoredName = "$name-restored"
        try {
            val settings = MicrophoneNoiseSettings(context, name)
            val original = settings.profiles.value
            settings.selectProfile(MicrophoneInputGroup.BUILT_IN,
                MicrophoneInputProfile(MicrophoneNoiseMode.DEVICE, true))
            settings.selectProfile(MicrophoneInputGroup.WIRED_USB,
                MicrophoneInputProfile(MicrophoneNoiseMode.AI, false))
            settings.selectProfile(MicrophoneInputGroup.BLUETOOTH,
                MicrophoneInputProfile(MicrophoneNoiseMode.OFF, false))
            val reopened = MicrophoneNoiseSettings(context, name)
            assertEquals(MicrophoneNoiseMode.DEVICE, reopened.profileFor(AudioInputKind.BUILT_IN).noiseMode)
            assertEquals(true, reopened.profileFor(AudioInputKind.BUILT_IN).nearSpeakerFocus)
            assertEquals(MicrophoneNoiseMode.AI, reopened.profileFor(AudioInputKind.WIRED_HEADSET).noiseMode)
            assertEquals(reopened.profileFor(AudioInputKind.WIRED_HEADSET), reopened.profileFor(AudioInputKind.USB))
            assertEquals(MicrophoneNoiseMode.OFF, reopened.profileFor(AudioInputKind.BLUETOOTH).noiseMode)
            assertEquals(original.getValue(MicrophoneInputGroup.OTHER), reopened.profileFor(AudioInputKind.OTHER))
            val rows = reopened.exportProfiles().apply {
                getJSONObject("BUILT_IN").put("futureSecret", "synthetic-private-field")
                put("futureRoute", JSONObject().put("futureSecret", "not-exportable"))
            }
            val backup = JSONObject().put("type", "settings").put("microphoneProfiles", rows)
            PortableSettings.validate(backup)
            val safe = PortableSettings.sanitized(backup).getJSONObject("microphoneProfiles")
            assertFalse(safe.has("futureRoute"))
            assertFalse(safe.getJSONObject("BUILT_IN").has("futureSecret"))
            val restored = MicrophoneNoiseSettings(context, restoredName)
            restored.importProfiles(safe)
            assertEquals(reopened.profiles.value, restored.profiles.value)
        } finally {
            // Only this fixture's disposable stores; no operator preferences are removed.
            context.deleteSharedPreferences(name)
            context.deleteSharedPreferences(restoredName)
        }
    }
}
