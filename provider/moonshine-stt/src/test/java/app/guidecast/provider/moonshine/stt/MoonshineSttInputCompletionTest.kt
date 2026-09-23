package app.guidecast.provider.moonshine.stt

import org.junit.Assert.*
import org.junit.Test

class MoonshineSttInputCompletionTest {
    @Test fun nativeFinalsReachTheListenerBeforeReleaseAndEofAcknowledgement() {
        val events = mutableListOf<String>()
        var listenerPresent = true
        finishMoonshineSttInput(
            flushNative = {
                check(listenerPresent)
                events += "native final transcript"
            },
            releaseNative = { listenerPresent = false; events += "stream released" },
            acknowledge = { check(!listenerPresent); events += "eof acknowledged" },
        )
        assertEquals(listOf("native final transcript", "stream released", "eof acknowledged"), events)
    }

    @Test fun failedNativeFinalizationStillReleasesButCannotAcknowledgeSuccess() {
        val failure = IllegalStateException("synthetic flush failure")
        var released = false
        var acknowledged = false
        val actual = runCatching {
            finishMoonshineSttInput(
                flushNative = { throw failure },
                releaseNative = { released = true },
                acknowledge = { acknowledged = true },
            )
        }.exceptionOrNull()
        assertSame(failure, actual)
        assertTrue(released)
        assertFalse(acknowledged)
    }

    @Test fun failedReleaseCannotProduceAnEofSuccessAcknowledgement() {
        var acknowledged = false
        val failure = IllegalStateException("synthetic release failure")
        val actual = runCatching {
            finishMoonshineSttInput({}, { throw failure }, { acknowledged = true })
        }.exceptionOrNull()
        assertSame(failure, actual)
        assertFalse(acknowledged)
    }

    @Test fun cleanupFailureDoesNotHideTheNativeFinalizationFailure() {
        val failure = IllegalStateException("synthetic flush failure")
        val cleanup = IllegalStateException("synthetic release failure")
        val actual = runCatching {
            finishMoonshineSttInput({ throw failure }, { throw cleanup }, { fail("No EOF acknowledgement after failure") })
        }.exceptionOrNull()
        assertSame(failure, actual)
        assertEquals(listOf(cleanup), failure.suppressed.toList())
    }
}
