package app.guidecast.transmitter

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalMonitorChannelOptionsTest {
    @Test fun sourceIsTheDefaultEvenWithoutTranslation() {
        assertEquals(listOf("source"), localMonitorChannels(emptyList()).map { it.channelId })
    }
    @Test fun sourceAndEverySelectedLanguageAreAvailableWithoutDuplicates() {
        val languages = listOf("en", "ja", "zh", "nl", "fr", "source", "en").map {
            BroadcastChannelSnapshot(it, it, it)
        }
        assertEquals(listOf("source", "en", "ja", "zh", "nl", "fr"),
            localMonitorChannels(languages).map { it.channelId })
    }
}
