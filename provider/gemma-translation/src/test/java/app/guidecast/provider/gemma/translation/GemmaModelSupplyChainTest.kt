package app.guidecast.provider.gemma.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaModelSupplyChainTest {
    @Test
    fun publicModelDownloadIsPinnedToTheVerifiedImmutableRevision() {
        assertEquals(40, GemmaModelManager.MODEL_REVISION.length)
        assertFalse(GemmaModelManager.MODEL_DOWNLOAD_URL.contains("/resolve/main/"))
        assertTrue(
            GemmaModelManager.MODEL_DOWNLOAD_URL.contains(
                "/resolve/${GemmaModelManager.MODEL_REVISION}/",
            ),
        )
        assertEquals(2_588_147_712L, GemmaModelManager.MODEL_SIZE_BYTES)
        assertEquals(
            "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
            GemmaModelManager.MODEL_SHA256,
        )
    }
}
