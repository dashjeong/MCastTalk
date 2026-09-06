package app.guidecast.provider.gemma.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaLatencyWatchdogTest {

    @Test
    fun nominalInferenceDurationsDoNotTriggerThrottling() {
        val watchdog = GemmaLatencyWatchdog()

        // Three fast inferences (~350ms on Galaxy S23 Adreno GPU)
        watchdog.recordInference(320L)
        watchdog.recordInference(350L)
        watchdog.recordInference(380L)

        assertEquals(350L, watchdog.averageLatencyMillis())
        assertFalse(watchdog.shouldThrottleToLightweight())
    }

    @Test
    fun singleSpikeAboveHardCeilingTriggersImmediateCooldown() {
        val watchdog = GemmaLatencyWatchdog()

        watchdog.recordInference(400L)
        watchdog.recordInference(1_650L) // Exceeds 1,600ms ceiling

        assertTrue("Spike above hard ceiling must trigger throttling", watchdog.shouldThrottleToLightweight())

        // Next turn consumes cooldown turn 1
        watchdog.recordInference(300L)
        assertTrue("Still in cooldown turn 2", watchdog.shouldThrottleToLightweight())

        // Next turn consumes cooldown turn 2
        watchdog.recordInference(300L)
        assertFalse("Cooldown expired, returns to normal", watchdog.shouldThrottleToLightweight())
    }

    @Test
    fun rollingAverageAboveThresholdTriggersThrottling() {
        val watchdog = GemmaLatencyWatchdog()

        // Prolonged heavy load causing average > 1,200ms
        watchdog.recordInference(1_250L)
        watchdog.recordInference(1_300L)
        watchdog.recordInference(1_200L)

        assertTrue(watchdog.averageLatencyMillis() >= 1_200L)
        assertTrue(watchdog.shouldThrottleToLightweight())
    }

    @Test
    fun resetClearsAllHistoryAndCooldown() {
        val watchdog = GemmaLatencyWatchdog()

        watchdog.recordInference(1_800L)
        assertTrue(watchdog.shouldThrottleToLightweight())

        watchdog.reset()
        assertEquals(0L, watchdog.averageLatencyMillis())
        assertFalse(watchdog.shouldThrottleToLightweight())
    }
}
