package app.guidecast.transmitter

import org.junit.Assert.*
import org.junit.Test

class UiDisplaySettingsTest {
    private class Preferences : UiDisplayPreferenceStore {
        var value = false
        override fun readDeveloperInfo() = value
        override fun writeDeveloperInfo(enabled: Boolean) { value = enabled }
    }

    @Test fun freshSettingsHideTechnicalInformation() {
        assertFalse(UiDisplaySettings(Preferences()).developerInfo.value)
    }

    @Test fun changingDisplayUpdatesObserversAndSurvivesSettingsRecreation() {
        val preferences = Preferences()
        val settings = UiDisplaySettings(preferences)
        settings.setDeveloperInfo(true)
        assertTrue(settings.developerInfo.value)
        assertTrue(UiDisplaySettings(preferences).developerInfo.value)
        settings.setDeveloperInfo(false)
        assertFalse(settings.developerInfo.value)
        assertFalse(UiDisplaySettings(preferences).developerInfo.value)
    }
}
