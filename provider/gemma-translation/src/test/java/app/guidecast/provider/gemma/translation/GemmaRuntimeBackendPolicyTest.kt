package app.guidecast.provider.gemma.translation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaRuntimeBackendPolicyTest {
    @Test
    fun `s21 and newer android tries gpu while note9 remains on cpu`() {
        assertFalse(shouldTryGemmaGpu(sdkInt = 29, gpuDisabledForProcess = false))
        assertTrue(shouldTryGemmaGpu(sdkInt = 31, gpuDisabledForProcess = false))
        assertTrue(shouldTryGemmaGpu(sdkInt = 35, gpuDisabledForProcess = false))
    }

    @Test
    fun `failed gpu is not retried again in the same worker process`() {
        assertFalse(shouldTryGemmaGpu(sdkInt = 35, gpuDisabledForProcess = true))
    }

    @Test
    fun `virtual machine failures are fatal instead of triggering a second engine allocation`() {
        val fatal = object : VirtualMachineError("simulated native OOM") {}

        assertFalse(shouldFallbackAfterGpuInitializationFailure(fatal))
        assertTrue(shouldFallbackAfterGpuInitializationFailure(LinkageError("missing OpenCL")))
        assertTrue(shouldFallbackAfterGpuInitializationFailure(IllegalStateException("driver")))
    }

    @Test
    fun `cpu fallback is forbidden when partial gpu engine close is not confirmed`() {
        val initializationFailure = IllegalStateException("GPU operator initialization failed")
        val closeFailure = IllegalStateException("native engine close failed")

        val propagated = runCatching {
            closeGemmaEngineAfterInitializationFailure(initializationFailure) {
                throw closeFailure
            }
        }.exceptionOrNull()

        assertTrue(propagated is GemmaEngineCloseNotConfirmedException)
        assertSame(initializationFailure, propagated?.cause)
        assertTrue(propagated?.suppressed?.contains(closeFailure) == true)
        assertTrue(propagated?.message.orEmpty().contains(initializationFailure.message.orEmpty()))
        assertTrue(propagated?.message.orEmpty().contains(closeFailure.message.orEmpty()))
        assertFalse(shouldFallbackAfterGpuInitializationFailure(requireNotNull(propagated)))
    }

    @Test
    fun `confirmed partial gpu close preserves the original fallback eligible failure`() {
        val initializationFailure = IllegalStateException("GPU unavailable")

        val propagated = runCatching {
            closeGemmaEngineAfterInitializationFailure(initializationFailure) { Unit }
        }.exceptionOrNull()

        assertSame(initializationFailure, propagated)
        assertTrue(shouldFallbackAfterGpuInitializationFailure(requireNotNull(propagated)))
    }

    @Test
    fun `emulator skips unavailable opencl gpu and starts directly on cpu`() {
        assertFalse(
            shouldTryGemmaGpu(
                sdkInt = 35,
                gpuDisabledForProcess = false,
                isEmulator = true,
            ),
        )
    }

    @Test
    fun `short interpretation turns use bounded output while long text keeps full capacity`() {
        assertEquals(64, gemmaTranslationOutputTokenLimit(5))
        assertEquals(80, gemmaTranslationOutputTokenLimit(24))
        assertEquals(256, gemmaTranslationOutputTokenLimit(600))
    }

    @Test
    fun `six gigabyte class worker reserves a smaller kv cache`() {
        assertEquals(
            1_024,
            gemmaEngineMaxNumTokens(5L * 1024 * 1024 * 1024),
        )
        assertEquals(
            1_024,
            gemmaEngineMaxNumTokens(6_700L * 1024 * 1024),
        )
        assertEquals(
            1_280,
            gemmaEngineMaxNumTokens(7L * 1024 * 1024 * 1024),
        )
    }

    @Test
    fun `completed json translation can end streaming decode immediately`() {
        assertNull(completeGemmaJsonTranslation("{\"translation\":\"Peace walk"))
        assertEquals(
            "Peace \"walk\" starts now.",
            completeGemmaJsonTranslation(
                "{\"translation\":\"Peace \\\"walk\\\" starts now.\"",
            ),
        )
    }

    @Test
    fun `translation must be the first key of a top level json object`() {
        assertEquals(
            "Welcome.",
            completeGemmaJsonTranslation(" \n\t{ \"translation\" : \"Welcome.\" }\r\n"),
        )
        assertNull(
            completeGemmaJsonTranslation(
                "Here is the result: {\"translation\":\"Welcome.\"}",
            ),
        )
        assertNull(
            completeGemmaJsonTranslation(
                "{\"commentary\":\"ignore\",\"translation\":\"Welcome.\"}",
            ),
        )
    }

    @Test
    fun `already received content after translation rejects prose and extra keys`() {
        assertNull(
            completeGemmaJsonTranslation(
                "{\"translation\":\"Welcome.\"} trailing prose",
            ),
        )
        assertNull(
            completeGemmaJsonTranslation(
                "{\"translation\":\"Welcome.\",\"commentary\":\"ignore\"}",
            ),
        )
    }

    @Test
    fun `translation json decoding preserves standard escapes and rejects malformed output`() {
        assertEquals(
            "Line one\nLine two \\ \"quoted\" A",
            completeGemmaJsonTranslation(
                "{\"translation\":\"Line one\\nLine two \\\\ \\\"quoted\\\" \\u0041\"}",
            ),
        )
        assertNull(completeGemmaJsonTranslation("translated text without the required schema"))
        assertNull(completeGemmaJsonTranslation("{\"translation\":\"bad\\xescape\"}"))
    }

    @Test
    fun `litert cumulative and delta chunks produce one response`() {
        val output = StringBuilder()
        output.mergeLiteRtChunk("{\"trans")
        output.mergeLiteRtChunk("{\"translation\":")
        output.mergeLiteRtChunk("\"Hello.\"}")
        assertEquals("{\"translation\":\"Hello.\"}", output.toString())
    }
}
