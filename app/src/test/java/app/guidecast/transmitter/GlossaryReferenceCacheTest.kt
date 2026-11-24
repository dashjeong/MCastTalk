package app.guidecast.transmitter

import java.security.MessageDigest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GlossaryReferenceCacheTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun sameSizeOldOrCorruptReferenceRequiresReplacement() {
        val cache = folder.newFile()
        val current = byteArrayOf(1, 2, 3)
        val hash = MessageDigest.getInstance("SHA-256").digest(current).joinToString("") { "%02x".format(it) }
        cache.writeBytes(byteArrayOf(1, 2, 0))
        assertFalse(matchesGlossaryReference(cache, 3, hash) {})
        cache.writeBytes(current)
        assertTrue(matchesGlossaryReference(cache, 3, hash) {})
    }
}
