package app.guidecast.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class AudioRecordingStartupTest {
    @Test fun `projection revocation during startup releases recorder and preserves failure`() {
        val revoked = SecurityException("projection revoked")
        var releases = 0
        val thrown = assertThrows(SecurityException::class.java) {
            startAudioRecordingOrRelease(
                start = { throw revoked },
                isRecording = { error("No recording state after failed start") },
                release = { releases++; error("already revoked") },
            )
        }
        assertSame(revoked, thrown)
        assertEquals(1, releases)
    }

    @Test fun `rejected recording state releases recorder before retry`() {
        var releases = 0
        repeat(20) {
            assertThrows(IllegalStateException::class.java) {
                startAudioRecordingOrRelease({}, { false }, { releases++ })
            }
        }
        assertEquals(20, releases)
    }

    @Test fun `started recorder remains owned by capture teardown`() {
        var releases = 0
        startAudioRecordingOrRelease({}, { true }, { releases++ })
        assertEquals(0, releases)
    }
}
