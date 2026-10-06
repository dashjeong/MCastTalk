package app.guidecast.transmitter

import org.junit.Assert.*
import org.junit.Test

class InputSignalGuidanceTest {
    @Test fun oneLoudTransientNeverReportsSustainedHighInput() {
        val tracker = InputSignalGuidanceTracker()
        assertEquals(InputSignalLevel.RECEIVING, tracker.observe(0.6f, 1f, 1, 0).level)
        assertEquals(InputSignalLevel.WAITING, tracker.observe(0f, 0f, 2, 100).level)
        assertEquals(InputSignalLevel.WAITING, tracker.observe(0f, 0f, 3, 700).level)
    }

    @Test fun repeatedOldSamplesDoNotTurnElapsedTimeIntoAHighOrLowWarning() {
        val tracker = InputSignalGuidanceTracker()
        tracker.observe(0.5f, 1f, 1, 0)
        assertEquals(InputSignalLevel.RECEIVING, tracker.observe(0.5f, 1f, 1, 700).level)
        val low = InputSignalGuidanceTracker()
        low.observe(0.0005f, 0.001f, 1, 0)
        assertEquals(InputSignalLevel.WAITING, low.observe(0.0005f, 0.001f, 1, 2_100).level)
    }

    @Test fun severalFreshHighPeaksOverTheStableWindowProduceAnActionableWarning() {
        val tracker = InputSignalGuidanceTracker()
        tracker.observe(0.5f, 0.99f, 1, 0)
        tracker.observe(0.5f, 0.99f, 2, 300)
        assertEquals(InputSignalLevel.RECEIVING, tracker.observe(0.5f, 0.99f, 3, 599).level)
        val advice = tracker.observe(0.5f, 0.99f, 4, 600)
        assertEquals(InputSignalLevel.HIGH, advice.level)
        assertTrue(advice.attention)
        assertTrue(advice.detail.contains("마이크"))
        assertEquals(InputSignalLevel.RECEIVING, tracker.observe(0.1f, 0.2f, 5, 700).level)
    }

    @Test fun quietFramesRemainWaitingWhileContinuouslySmallNonzeroInputGetsDistanceAdvice() {
        val tracker = InputSignalGuidanceTracker()
        repeat(5) { assertEquals(InputSignalLevel.WAITING, tracker.observe(0f, 0f, it + 1L, it * 600L).level) }
        val low = InputSignalGuidanceTracker()
        repeat(4) { assertEquals(InputSignalLevel.WAITING, low.observe(0.0005f, 0.001f, it + 1L, it * 600L).level) }
        val advice = low.observe(0.0005f, 0.001f, 5, 2_000)
        assertEquals(InputSignalLevel.LOW, advice.level)
        assertTrue(advice.detail.contains("가까이"))
    }

    @Test fun gapCaptureRestartAndClockRollbackEachRestartTheObservationWindow() {
        val tracker = InputSignalGuidanceTracker()
        tracker.observe(0.5f, 1f, 1, 0)
        tracker.observe(0.5f, 1f, 2, 300)
        assertEquals(InputSignalLevel.RECEIVING, tracker.observe(0.5f, 1f, 3, 2_000).level)
        tracker.observe(0.5f, 1f, 4, 2_300)
        assertEquals(InputSignalLevel.RECEIVING, tracker.observe(0.5f, 1f, 1, 2_600).level)
        assertEquals(InputSignalLevel.RECEIVING, tracker.observe(0.5f, 1f, 2, 2_000).level)
    }

    @Test fun blockedAndStoppedStatesOverrideSignalEnergyAndBadTelemetryCannotReportSuccess() {
        val tracker = InputSignalGuidanceTracker()
        assertEquals(InputSignalLevel.BLOCKED, tracker.observe(0.2f, 0.5f, 20, 100, clientSilenced = true).level)
        assertEquals(InputSignalLevel.BLOCKED, tracker.observe(0.2f, 0.5f, 21, 200, systemMuted = true).level)
        assertEquals(InputSignalLevel.INACTIVE, tracker.observe(0.2f, 0.5f, 21, 200, inputActive = false).level)
        assertEquals(InputSignalLevel.WAITING, tracker.observe(Float.NaN, 1f, 1, 0).level)
        assertEquals(InputSignalLevel.WAITING, tracker.observe(0.2f, Float.POSITIVE_INFINITY, 1, 0).level)
        assertEquals(InputSignalLevel.WAITING, tracker.observe(-0.2f, 1f, 1, 0).level)
        assertEquals(InputSignalLevel.WAITING, tracker.observe(0.2f, 1f, 0, 0).level)
    }
}
