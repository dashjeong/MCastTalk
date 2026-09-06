package app.guidecast.provider.moonshine.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class MoonshineTtsDownloadProgressTest {
    @Test
    fun downloaderOneBasedFileIndexDoesNotSkipFirstFileFraction() {
        assertEquals(0f, downloadProgress(1, 4, 0, 100), 0.0001f)
        assertEquals(0.125f, downloadProgress(1, 4, 50, 100), 0.0001f)
        assertEquals(0.25f, downloadProgress(2, 4, 0, 100), 0.0001f)
        assertEquals(1f, downloadProgress(4, 4, 100, 100), 0.0001f)
    }

    @Test
    fun progressIsClampedForUnknownOrUnexpectedTotals() {
        assertEquals(0f, downloadProgress(1, 1, 50, 0), 0.0001f)
        assertEquals(1f, downloadProgress(1, 1, 200, 100), 0.0001f)
        assertEquals(0.5f, downloadProgress(0, 0, 50, 100), 0.0001f)
    }
}
