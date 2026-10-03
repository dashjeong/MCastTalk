package app.guidecast.core.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class MicrophoneFocusTest {
    @Test fun defaultAndExternalRoutesNeverChangeCapturePreferences() {
        val forbidden: () -> Boolean = { error("Must not change this route") }
        assertEquals(MicrophoneFocusStatus.OFF, requestNearSpeakerFocus(false, true, forbidden, forbidden))
        assertEquals(MicrophoneFocusStatus.EXTERNAL, requestNearSpeakerFocus(true, false, forbidden, forbidden))
        assertEquals(MicrophoneFocusStatus.UNKNOWN, requestNearSpeakerFocus(true, null, forbidden, forbidden))
        assertEquals(false, AudioCaptureConfig().nearSpeakerFocus)
    }

    @Test fun rejectedDirectionDoesNotSkipIndependentFieldRequestOrStopInput() {
        var fieldRequests = 0
        val status = requestNearSpeakerFocus(true, true, { throw IllegalStateException("OEM rejection") }, {
            fieldRequests++; true
        })
        assertEquals(MicrophoneFocusStatus.PARTIAL, status)
        assertEquals(1, fieldRequests)
        assertEquals(MicrophoneFocusStatus.UNSUPPORTED,
            requestNearSpeakerFocus(true, true, { false }, { false }))
        assertEquals(MicrophoneFocusStatus.ACCEPTED,
            requestNearSpeakerFocus(true, true, { true }, { true }))
    }
}
