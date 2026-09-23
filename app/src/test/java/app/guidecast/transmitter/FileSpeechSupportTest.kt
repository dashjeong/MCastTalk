package app.guidecast.transmitter

import org.junit.Assert.*
import org.junit.Test

class FileSpeechSupportTest {
    @Test fun manualKoreanUsesAvailableAppModelBeforeAndroid13() {
        assertNull(fileSpeechSupportFailure("ko-KR", 30, false, true))
        assertNotNull(fileSpeechSupportFailure("ko-KR", 30, false, false))
    }
    @Test fun automaticAndPlatformOnlyLanguagesStillRequireTheirActualServices() {
        assertNotNull(fileSpeechSupportFailure(null, 33, true, true))
        assertNotNull(fileSpeechSupportFailure(null, 34, false, true))
        assertNull(fileSpeechSupportFailure(null, 34, true, false))
        listOf("fr-FR", "de-DE", "vi-VN").forEach { language ->
            assertNotNull(fileSpeechSupportFailure(language, 30, true, true))
            assertNotNull(fileSpeechSupportFailure(language, 33, false, true))
            assertNull(fileSpeechSupportFailure(language, 33, true, false))
        }
    }
    @Test fun installedPlatformServiceCannotOverrideAnUnavailableAppLanguage() {
        assertEquals("language unavailable", fileSpeechSupportFailure("en-US", 35, true, false, "language unavailable"))
    }
}
