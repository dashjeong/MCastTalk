package app.guidecast.provider.android.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CandidateTtsEnginesTest {
    @Test
    fun `null packageManager returns only default engine`() {
        val candidates = resolveCandidateTtsEngines(null)
        assertEquals(listOf<String?>(null), candidates)
    }

    @Test
    fun `default engine is always primary candidate`() {
        val candidates = orderCandidateTtsEngines(emptyList())
        assertNull(candidates.first())
    }

    @Test
    fun `orders samsung before google when both are installed`() {
        val installed = listOf("com.google.android.tts", "com.samsung.SMT")
        val candidates = orderCandidateTtsEngines(installed)
        assertEquals(
            listOf(null, "com.samsung.SMT", "com.google.android.tts"),
            candidates,
        )
    }

    @Test
    fun `orders priority engines before third party engines`() {
        val installed = listOf(
            "org.thirdparty.tts",
            "com.google.android.tts",
            "com.samsung.SMT",
            "com.another.tts",
        )
        val candidates = orderCandidateTtsEngines(installed)
        assertEquals(
            listOf(
                null,
                "com.samsung.SMT",
                "com.google.android.tts",
                "org.thirdparty.tts",
                "com.another.tts",
            ),
            candidates,
        )
    }

    @Test
    fun `preserves installed order for non-priority engines`() {
        val installed = listOf("org.engine.b", "org.engine.a")
        val candidates = orderCandidateTtsEngines(installed)
        assertEquals(
            listOf(null, "org.engine.b", "org.engine.a"),
            candidates,
        )
    }

    @Test
    fun `deduplicates repeated packages across priority and installed list`() {
        val installed = listOf(
            "com.samsung.SMT",
            "com.samsung.SMT",
            "com.custom.tts",
            "com.google.android.tts",
            "com.custom.tts",
            "com.google.android.tts",
        )
        val candidates = orderCandidateTtsEngines(installed)
        assertEquals(
            listOf(null, "com.samsung.SMT", "com.google.android.tts", "com.custom.tts"),
            candidates,
        )
    }

    @Test
    fun `empty installed packages returns only default null candidate`() {
        val candidates = orderCandidateTtsEngines(emptyList())
        assertEquals(listOf<String?>(null), candidates)
    }

    @Test
    fun `preferredPackage is ordered first when specified`() {
        val installed = listOf("com.google.android.tts", "com.samsung.SMT")
        val candidates = orderCandidateTtsEngines(installed, preferredPackage = "com.google.android.tts")
        assertEquals(
            listOf("com.google.android.tts", null, "com.samsung.SMT"),
            candidates,
        )
    }

    @Test
    fun `preferredPackage outside priority engines is ordered first`() {
        val installed = listOf("com.custom.tts", "com.samsung.SMT", "com.google.android.tts")
        val candidates = orderCandidateTtsEngines(installed, preferredPackage = "com.custom.tts")
        assertEquals(
            listOf("com.custom.tts", null, "com.samsung.SMT", "com.google.android.tts"),
            candidates,
        )
    }

    @Test
    fun `null preferredPackage preserves default engine first`() {
        val installed = listOf("com.google.android.tts", "com.samsung.SMT")
        val candidates = orderCandidateTtsEngines(installed, preferredPackage = null)
        assertEquals(
            listOf(null, "com.samsung.SMT", "com.google.android.tts"),
            candidates,
        )
    }

    @Test
    fun `resolveCandidateTtsEngines passes preferredPackage correctly`() {
        val candidates = resolveCandidateTtsEngines(null, preferredPackage = "com.google.android.tts")
        assertEquals(listOf("com.google.android.tts", null), candidates)
    }
}
