package app.guidecast.transmitter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechSynthesisRecoveryTimingTest {
    @Test
    fun `legacy profile no longer relaxes audio deadline in S21 product`() {
        val timing = speechSynthesisRecoveryTiming(
            sdkInt = 29,
            totalMemoryBytes = 8L * 1024 * 1024 * 1024,
            isLowRamDevice = false,
        )

        assertEquals(2_000L, timing.primaryFirstFrameTimeoutMillis)
        assertEquals(2_000L, timing.continuationPrimaryFirstFrameTimeoutMillis)
        assertEquals(4_500L, timing.pipelineFirstAudioTimeoutMillis)
        assertEquals(4_500L, timing.pipelineFrameIdleTimeoutMillis)
        assertEquals(15_000L, timing.pipelineTotalTimeoutMillis)
        assertNull(timing.compatibilityNotice)
    }

    @Test
    fun `modern s23 class device keeps strict realtime timing`() {
        val timing = speechSynthesisRecoveryTiming(
            sdkInt = 35,
            totalMemoryBytes = 8L * 1024 * 1024 * 1024,
            isLowRamDevice = false,
        )

        assertEquals(2_000L, timing.primaryFirstFrameTimeoutMillis)
        assertEquals(2_000L, timing.continuationPrimaryFirstFrameTimeoutMillis)
        assertEquals(1_500L, timing.fallbackFirstFrameTimeoutMillis)
        assertEquals(4_500L, timing.pipelineFirstAudioTimeoutMillis)
        assertEquals(4_500L, timing.pipelineFrameIdleTimeoutMillis)
        assertEquals(15_000L, timing.pipelineTotalTimeoutMillis)
        assertNull(timing.compatibilityNotice)
    }

    @Test
    fun `six gigabyte modern device keeps channel isolated realtime deadline`() {
        val timing = speechSynthesisRecoveryTiming(
            sdkInt = 35,
            totalMemoryBytes = 6L * 1024 * 1024 * 1024,
            isLowRamDevice = false,
        )

        assertEquals(2_000L, timing.primaryFirstFrameTimeoutMillis)
        assertEquals(1_500L, timing.fallbackFirstFrameTimeoutMillis)
        assertEquals(4_500L, timing.pipelineFirstAudioTimeoutMillis)
        assertEquals(15_000L, timing.pipelineTotalTimeoutMillis)
        assertNull(timing.compatibilityNotice)
    }

    @Test
    fun `standard s23 recovery bounds stalled primary to product target without long silence`() {
        val timing = speechSynthesisRecoveryTiming(
            sdkInt = 35,
            totalMemoryBytes = 8L * 1024 * 1024 * 1024,
            isLowRamDevice = false,
        )

        assertEquals(2_000L, timing.primaryFirstFrameTimeoutMillis)
        assertEquals(2_000L, timing.continuationPrimaryFirstFrameTimeoutMillis)
        assertEquals(1_500L, timing.fallbackFirstFrameTimeoutMillis)
        assertEquals(4_500L, timing.pipelineFirstAudioTimeoutMillis)
        assertEquals(4_500L, timing.pipelineFrameIdleTimeoutMillis)
        assertEquals(15_000L, timing.pipelineTotalTimeoutMillis)
        assertTrue(timing.primaryFirstFrameTimeoutMillis <= 2_000L)
        assertTrue(timing.continuationPrimaryFirstFrameTimeoutMillis <= 2_000L)
        assertTrue(timing.fallbackFirstFrameTimeoutMillis <= 1_500L)
        assertTrue(timing.pipelineFirstAudioTimeoutMillis <= 4_500L)
        assertTrue(timing.pipelineFrameIdleTimeoutMillis <= 4_500L)
        assertTrue(timing.pipelineTotalTimeoutMillis <= 15_000L)
        assertTrue(
            timing.primaryFirstFrameTimeoutMillis + timing.fallbackFirstFrameTimeoutMillis <
                timing.pipelineFirstAudioTimeoutMillis,
        )
    }

    @Test
    fun `android low ram classification does not silently relax real time deadline`() {
        val timing = speechSynthesisRecoveryTiming(
            sdkInt = 35,
            totalMemoryBytes = 6L * 1024 * 1024 * 1024,
            isLowRamDevice = true,
        )

        assertEquals(2_000L, timing.primaryFirstFrameTimeoutMillis)
        assertNull(timing.compatibilityNotice)
    }

    @Test
    fun `every timing leaves fallback recovery inside its outer watchdog`() {
        listOf(
            speechSynthesisRecoveryTiming(29, 8L * 1024 * 1024 * 1024, false),
            speechSynthesisRecoveryTiming(35, 8L * 1024 * 1024 * 1024, false),
        ).forEach { timing ->
            assertTrue(
                timing.primaryFirstFrameTimeoutMillis +
                    timing.fallbackFirstFrameTimeoutMillis <
                    timing.pipelineFirstAudioTimeoutMillis,
            )
            assertTrue(
                timing.primaryFirstFrameTimeoutForClause(1) +
                    timing.fallbackFirstFrameTimeoutMillis <
                    timing.pipelineFrameIdleTimeoutMillis,
            )
            assertTrue(timing.pipelineTotalTimeoutMillis > timing.pipelineFirstAudioTimeoutMillis)
        }
    }
}
