package app.guidecast.transmitter

import android.os.RemoteException
import org.junit.Assert.*
import org.junit.Test

class SpeechWorkerFailureLatchTest {
    @Test fun workerDeathBlocksOnlyAffectedLanguageUntilExplicitRetry() {
        val latch = SpeechWorkerFailureLatch()
        latch.record("ja", IllegalStateException("lost worker", RemoteException()))
        latch.check("ja") // One transient reclaim still gets the normal recovery attempt.
        latch.record("ja", IllegalStateException("worker died again", RemoteException()))
        repeat(20) { assertTrue(runCatching { latch.check("ja") }.isFailure) }
        latch.check("en")
        latch.check("zh")
        latch.reset(listOf("en"))
        assertTrue(runCatching { latch.check("ja") }.isFailure)
        latch.reset(listOf("ja"))
        latch.check("ja")
    }

    @Test fun ordinaryFailureDoesNotDisableHealthyWorker() {
        val latch = SpeechWorkerFailureLatch()
        latch.record("en", IllegalStateException("first PCM timeout"))
        latch.check("en")
    }

    @Test fun dualFailurePreservesSuppressedWorkerDeathAndDiagnosticFailureCannotBreakRecovery() {
        val latch = SpeechWorkerFailureLatch { error("OS query unavailable") }
        val error = IllegalStateException("both voices failed", IllegalStateException("no offline voice"))
        error.addSuppressed(RemoteException())
        latch.record("ja", error)
        latch.record("ja", error)
        val failure = runCatching { latch.check("ja") }.exceptionOrNull()
        assertSame(error, failure?.cause)
        assertTrue(failure?.message.orEmpty().contains("설정에서 다시 준비"))
    }

    @Test fun completedSpeechResetsTransientFailureCount() {
        val latch = SpeechWorkerFailureLatch()
        repeat(5) {
            latch.record("en", RemoteException())
            latch.check("en")
            latch.completed("en")
        }
    }
}
