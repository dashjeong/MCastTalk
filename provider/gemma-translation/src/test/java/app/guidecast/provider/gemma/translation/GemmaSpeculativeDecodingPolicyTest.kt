package app.guidecast.provider.gemma.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GemmaSpeculativeDecodingPolicyTest {
    @Test fun switchingFromE4bGpuDoesNotLeakTheGlobalFlagIntoOtherModels() {
        // The SDK's Android classes use Java 21 bytecode; host gates run on Java 17.
        // Capture the actual setter calls without claiming native decoding is tested here.
        val applied = mutableListOf<Boolean?>()
        assertEquals(true, configureGemmaSpeculativeDecoding(
            GemmaModelVariant.E4B_IT, GemmaRuntimeBackend.GPU, applied::add,
        ))
        assertNull(configureGemmaSpeculativeDecoding(
            GemmaModelVariant.STANDARD, GemmaRuntimeBackend.GPU, applied::add,
        ))
        assertEquals(listOf(true, null), applied)
    }

    @Test fun cpuInitializationClearsAFormerGpuExperimentWithoutForcingItOff() {
        val applied = mutableListOf<Boolean?>()
        configureGemmaSpeculativeDecoding(
            GemmaModelVariant.E4B_IT, GemmaRuntimeBackend.GPU, applied::add,
        )
        assertNull(configureGemmaSpeculativeDecoding(
            GemmaModelVariant.E4B_IT, GemmaRuntimeBackend.CPU, applied::add,
        ))
        assertEquals(listOf(true, null), applied)
    }
}
