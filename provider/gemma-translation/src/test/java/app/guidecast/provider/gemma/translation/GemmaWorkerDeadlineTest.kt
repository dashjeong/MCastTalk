package app.guidecast.provider.gemma.translation

import org.junit.Assert.*
import org.junit.Test

class GemmaWorkerDeadlineTest {
    @Test fun `normal terminal disarms expiry`() {
        val state = GemmaDeadlineState()
        assertTrue(state.complete())
        assertFalse(state.expire())
        assertFalse(state.complete())
    }

    @Test fun `expiry owns terminal only once`() {
        val state = GemmaDeadlineState()
        assertTrue(state.expire())
        assertFalse(state.expire())
        assertFalse(state.complete())
    }

    @Test fun `guard excludes UI other providers and other applications`() {
        val pkg = "app.guidecast.transmitter.alpha"
        assertTrue(isDedicatedGemmaWorker(pkg, "$pkg:gemma_inference"))
        listOf(pkg, "$pkg:moonshine", "$pkg:gemma_inference_extra", "other:gemma_inference")
            .forEach { assertFalse(isDedicatedGemmaWorker(pkg, it)) }
        assertFalse(isDedicatedGemmaWorker("", ":gemma_inference"))
    }
}
