package app.guidecast.provider.android.tts

import android.speech.tts.TextToSpeech
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidOfflineVoiceSelectionTest {

    private fun createVoiceInfo(
        name: String,
        locale: Locale,
        quality: Int = 300,
        latency: Int = 300,
        isNetworkRequired: Boolean = false,
        features: Set<String> = emptySet(),
    ): OfflineVoiceInfo = OfflineVoiceInfo(
        name = name,
        locale = locale,
        quality = quality,
        latency = latency,
        isNetworkConnectionRequired = isNetworkRequired,
        features = features,
    )

    @Test
    fun `zh-TW selects traditional Taiwan voice over mainland China voice`() {
        val zhCnVoice = createVoiceInfo("cmn-cn-x-ccc", Locale.SIMPLIFIED_CHINESE, quality = 500)
        val zhTwVoice = createVoiceInfo("cmn-tw-x-ttt", Locale.TRADITIONAL_CHINESE, quality = 300)

        val selected = selectBestOfflineVoiceInfo(listOf(zhCnVoice, zhTwVoice), "zh-TW")
        assertNotNull(selected)
        assertEquals(zhTwVoice.name, selected?.name)
    }

    @Test
    fun `zh-TW rejects zh-CN voice when no traditional voice is installed`() {
        val zhCnVoice = createVoiceInfo("cmn-cn-x-ccc", Locale.SIMPLIFIED_CHINESE)

        val selected = selectBestOfflineVoiceInfo(listOf(zhCnVoice), "zh-TW")
        assertNull("zh-TW must not select a Simplified Chinese voice", selected)
    }

    @Test
    fun `zh selects simplified China voice over traditional voice`() {
        val zhCnVoice = createVoiceInfo("cmn-cn-x-ccc", Locale.SIMPLIFIED_CHINESE)
        val zhTwVoice = createVoiceInfo("cmn-tw-x-ttt", Locale.TRADITIONAL_CHINESE)

        val selected = selectBestOfflineVoiceInfo(listOf(zhTwVoice, zhCnVoice), "zh")
        assertNotNull(selected)
        assertEquals(zhCnVoice.name, selected?.name)
    }

    @Test
    fun `vi selects Vietnamese offline voice`() {
        val viVoice = createVoiceInfo("vie-vnm-x-vvv", Locale.forLanguageTag("vi-VN"))
        val enVoice = createVoiceInfo("eng-usa-x-eee", Locale.US)

        val selected = selectBestOfflineVoiceInfo(listOf(enVoice, viVoice), "vi")
        assertNotNull(selected)
        assertEquals(viVoice.name, selected?.name)
    }

    @Test
    fun `ignores network required voices`() {
        val networkVoice = createVoiceInfo("cmn-tw-x-net", Locale.TRADITIONAL_CHINESE, isNetworkRequired = true)
        val selected = selectBestOfflineVoiceInfo(listOf(networkVoice), "zh-TW")
        assertNull(selected)
    }

    @Test
    fun `ignores uninstalled voices`() {
        val uninstalledVoice = createVoiceInfo(
            name = "cmn-tw-x-uninst",
            locale = Locale.TRADITIONAL_CHINESE,
            features = setOf(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED),
        )
        val selected = selectBestOfflineVoiceInfo(listOf(uninstalledVoice), "zh-TW")
        assertNull(selected)
    }

    @Test
    fun `prefers higher quality and lower latency among compatible voices`() {
        val voiceNormal = createVoiceInfo("cmn-tw-normal", Locale.TRADITIONAL_CHINESE, quality = 300, latency = 300)
        val voiceHigh = createVoiceInfo("cmn-tw-high", Locale.TRADITIONAL_CHINESE, quality = 400, latency = 300)
        val voiceLowLatency = createVoiceInfo("cmn-tw-low-lat", Locale.TRADITIONAL_CHINESE, quality = 400, latency = 100)

        val selected = selectBestOfflineVoiceInfo(listOf(voiceNormal, voiceHigh, voiceLowLatency), "zh-TW")
        assertEquals(voiceLowLatency.name, selected?.name)
    }
}
