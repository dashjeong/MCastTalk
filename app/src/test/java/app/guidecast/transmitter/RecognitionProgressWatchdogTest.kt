package app.guidecast.transmitter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecognitionProgressWatchdogTest {
    @Test fun speechWithoutTextRefreshesTheSessionWithoutInferringBackendFailure() {
        for (alternateReady in listOf(false, true)) {
            assertEquals(RecognitionRecoveryAction.RESTART_SESSION,
                recognitionRecoveryAction(RecognitionProgressStalledException(), alternateReady))
        }
    }

    @Test fun callbackLossWithoutAnyHypothesisRecoversAfterTwentySeconds() {
        val watchdog = RecognitionProgressWatchdog()
        watchdog.beginAttempt(7, 0)
        for (time in 20L..20_000L step 20) watchdog.observeInput(time, true, 20)
        assertEquals(7L, watchdog.stalledAttempt(20_000))
        assertNull(watchdog.stalledAttempt(20_100)) // One recovery request per attempt.
    }

    @Test fun silencePausedInputAndSlowChangingHypothesesAreNotStalls() {
        val watchdog = RecognitionProgressWatchdog()
        watchdog.beginAttempt(1, 0)
        for (time in 20L..30_000L step 20) watchdog.observeInput(time, false, 20)
        assertNull(watchdog.stalledAttempt(30_000))
        for (time in 30_020L..50_000L step 20) watchdog.observeInput(time, true, 20)
        assertNull(watchdog.stalledAttempt(53_000)) // Input paused, not a provider failure.
        watchdog.onChangedTranscript(1, 53_000)
        watchdog.observeInput(53_020, true, 20)
        assertNull(watchdog.stalledAttempt(53_020))
    }

    @Test fun oldAttemptCallbacksCannotResetTheCurrentDeadline() {
        val watchdog = RecognitionProgressWatchdog()
        watchdog.beginAttempt(1, 0)
        watchdog.beginAttempt(2, 10_000)
        watchdog.endAttempt(1)
        for (time in 10_020L..30_000L step 20) watchdog.observeInput(time, true, 20)
        watchdog.onChangedTranscript(1, 30_000)
        assertEquals(2L, watchdog.stalledAttempt(30_000))
        watchdog.endAttempt(2)
        assertNull(watchdog.stalledAttempt(60_000))
    }

    @Test fun eightVirtualHoursOfSpeechSilenceAndRecurringStallsHaveNoPermanentStall() {
        val watchdog = RecognitionProgressWatchdog()
        var attempt = 0L
        var recovered = 0
        watchdog.beginAttempt(attempt, 0)
        // One hour periods include normal speech, ten minutes silence, and a callback-loss event.
        for (now in 100L..(8L * 60 * 60 * 1_000) step 100) {
            val minute = (now / 60_000) % 60
            val isSpeech = minute < 50
            val callbackLost = minute == 20L && now % 60_000 < 25_000
            watchdog.observeInput(now, isSpeech, 100)
            if (isSpeech && !callbackLost && now % 4_000 == 0L) {
                watchdog.onChangedTranscript(attempt, now)
            }
            watchdog.stalledAttempt(now)?.let {
                assertEquals(attempt, it)
                recovered++
                watchdog.endAttempt(attempt)
                watchdog.beginAttempt(++attempt, now)
            }
        }
        assertEquals(8, recovered)
    }
}
