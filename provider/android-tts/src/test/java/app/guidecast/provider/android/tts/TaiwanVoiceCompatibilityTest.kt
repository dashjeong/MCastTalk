package app.guidecast.provider.android.tts

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class TaiwanVoiceCompatibilityTest {
    @Test fun mandarinIsoVoiceCanServeTaiwanWithoutAcceptingMainlandOrNetworkVoice() {
        val taiwan = OfflineVoiceInfo("cmn-tw-local", Locale.forLanguageTag("cmn-TW"))
        val china = OfflineVoiceInfo("cmn-cn-local", Locale.forLanguageTag("cmn-CN"))
        assertEquals(taiwan, selectBestOfflineVoiceInfo(listOf(china, taiwan), "zh-TW"))
        assertNull(selectBestOfflineVoiceInfo(listOf(china), "zh-TW"))
        assertNull(selectBestOfflineVoiceInfo(listOf(taiwan.copy(isNetworkConnectionRequired = true)), "zh-TW"))
        assertNull(selectBestOfflineVoiceInfo(listOf(OfflineVoiceInfo("yue-tw", Locale.forLanguageTag("yue-TW"))), "zh-TW"))
    }
}
