package app.guidecast.provider.gemma.translation

import org.junit.Assert.*
import org.junit.Test

class GemmaEvaluationResponseFormatTest {
    @Test fun ordinaryModelPathsRetainNoResponseConstraint() {
        for (model in listOf("standard", "gpu_optimized", "e4b_it")) {
            assertNull(gemmaEvaluationResponseSchema(model, false))
        }
    }
    @Test fun explicitEvaluationAcceptsOnlyE4b() {
        assertNotNull(gemmaEvaluationResponseSchema("e4b_it", true))
        for (model in listOf("standard", "gpu_optimized", "unknown")) {
            try {
                gemmaEvaluationResponseSchema(model, true)
                fail("Unsupported evaluation variant must fail before native submission")
            } catch (_: IllegalArgumentException) { }
        }
    }
}
