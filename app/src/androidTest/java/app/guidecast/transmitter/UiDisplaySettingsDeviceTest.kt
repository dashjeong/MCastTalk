package app.guidecast.transmitter

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class UiDisplaySettingsDeviceTest {
    @Test fun androidPreferencesDefaultOffAndPersistExplicitDisplaySelection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("ui_display", Context.MODE_PRIVATE)
        val hadValue = preferences.contains("developer_info")
        val previous = preferences.getBoolean("developer_info", false)
        try {
            preferences.edit().remove("developer_info").commit()
            assertFalse(UiDisplaySettings(context).developerInfo.value)
            UiDisplaySettings(context).setDeveloperInfo(true)
            assertTrue(UiDisplaySettings(context).developerInfo.value)
            UiDisplaySettings(context).setDeveloperInfo(false)
            assertFalse(UiDisplaySettings(context).developerInfo.value)
        } finally {
            preferences.edit().apply {
                if (hadValue) putBoolean("developer_info", previous) else remove("developer_info")
            }.commit()
        }
    }
}
