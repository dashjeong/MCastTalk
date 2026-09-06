package app.guidecast.provider.moonshine.tts

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoonshineTtsIpcDirectoryIsolationTest {
    @Test
    fun closingOneLanguageDeletesOnlyItsOwnPartialFiles() {
        val cache = Files.createTempDirectory("guidecast-tts-ipc-test").toFile()
        try {
            val english = moonshineTtsLanguageIpcDirectory(cache, "en").apply { mkdirs() }
            val japanese = moonshineTtsLanguageIpcDirectory(cache, "ja").apply { mkdirs() }
            assertNotEquals(english.canonicalPath, japanese.canonicalPath)
            val englishPartial = File(english, "english.pcm.part").apply { writeBytes(byteArrayOf(1)) }
            val japanesePartial = File(japanese, "japanese.pcm.part").apply { writeBytes(byteArrayOf(2)) }

            deleteOwnedMoonshineTtsPartialFiles(english)

            assertFalse(englishPartial.exists())
            assertTrue(japanesePartial.isFile)
        } finally {
            cache.deleteRecursively()
        }
    }
}
