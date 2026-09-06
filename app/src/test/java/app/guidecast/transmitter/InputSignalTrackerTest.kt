package app.guidecast.transmitter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputSignalTrackerTest {
    @Test
    fun singleTransientNeverLatchesSignalSuccess() {
        val tracker = InputSignalTracker()

        assertFalse(tracker.observe(rms = 0.3f, peak = 0.8f, elapsedRealtimeMillis = 0).signalActive)
        repeat(20) { index ->
            assertFalse(
                tracker.observe(
                    rms = 0f,
                    peak = 0f,
                    elapsedRealtimeMillis = (index + 1L) * 20L,
                ).signalActive,
            )
        }
    }

    @Test
    fun sustainedPcmConfirmsThenExpiresAfterQuietHold() {
        val tracker = InputSignalTracker()
        var nowMillis = 0L
        repeat(5) {
            nowMillis += 20
            tracker.observe(rms = 0.2f, peak = 0.4f, elapsedRealtimeMillis = nowMillis)
        }
        val lastAudibleMillis = nowMillis + 20
        assertTrue(
            tracker.observe(rms = 0.2f, peak = 0.4f, elapsedRealtimeMillis = lastAudibleMillis)
                .signalActive,
        )
        assertTrue(
            tracker.observe(
                rms = 0f,
                peak = 0f,
                elapsedRealtimeMillis = lastAudibleMillis + 2_499,
            )
                .signalActive,
        )
        assertFalse(
            tracker.observe(
                rms = 0f,
                peak = 0f,
                elapsedRealtimeMillis = lastAudibleMillis + 2_500,
            )
                .signalActive,
        )
    }
}
