package app.guidecast.transmitter

import app.guidecast.core.stream.AudioChannelDescriptor
import app.guidecast.core.stream.AudioStreamRegistry
import app.guidecast.core.stream.PcmAudioFrame
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class NativeAudioSessionEndTest {
    @Test fun consentRevocationClearsClassicPreviewQueueAndBlocksAlreadyDequeuedWrite() = runTest {
        val coordinator = TranslationSessionCoordinator()
        val owner = coordinator.beginSession()
        val stream = AudioStreamRegistry().configure(listOf(AudioChannelDescriptor("en", "English", "en", 24000)))
        val preview = stream.subscribeLocalMonitor("en", preserveNativeAudio = true)
        try {
            repeat(2) { stream.tryPublish("en", PcmAudioFrame(ByteArray(960), 1, 100)) }
            val inFlight = requireNotNull(preview.receiveNext())
            var trackPending = 480
            var transcriptEnded = false
            val released = handleNativeAudioSessionEnd(NativeAudioEndReason.CONSENT_REVOKED,
                { transcriptEnded = true }, { releaseNativeAudioConsentOwner(coordinator, owner,
                    { stream.discardQueuedAudio("en") }, {}, { trackPending = 0 }) })
            assertTrue(released)
            assertTrue(transcriptEnded)
            assertFalse(coordinator.isSessionCurrent(owner))
            assertEquals(0, preview.bufferSnapshot().pendingFrames)
            assertEquals(0, trackPending)
            var bytesWritten = 0
            assertFalse(writeLocalMonitorPcm(inFlight.bytes, { coordinator.isSessionCurrent(owner) },
                { _, _, count -> bytesWritten += count; count }))
            assertEquals(0, bytesWritten)
        } finally { preview.close(); stream.close() }
    }

    @Test fun consentRevocationClearsClassicMonitorAndPreservesAnotherChannel() = runTest {
        val coordinator = TranslationSessionCoordinator()
        val owner = coordinator.beginSession()
        val stream = AudioStreamRegistry().configure(listOf(
            AudioChannelDescriptor("en", "English", "en", 24000), AudioChannelDescriptor("source", "Original", "ko", 16000)))
        val monitor = stream.subscribeLocalMonitor("en", preserveNativeAudio = true)
        val original = stream.subscribe("source")
        try {
            repeat(2) { stream.tryPublish("en", PcmAudioFrame(ByteArray(960), 1, 100)) }
            stream.tryPublish("source", PcmAudioFrame(ByteArray(640), 1))
            val inFlight = requireNotNull(monitor.receiveNext())
            var trackPending = 480
            handleNativeAudioSessionEnd(NativeAudioEndReason.CONSENT_REVOKED, {}, {
                releaseNativeAudioConsentOwner(coordinator, owner, { stream.discardQueuedAudio("en") },
                    { trackPending = 0 }, {})
            })
            assertEquals(0, monitor.bufferSnapshot().pendingFrames)
            assertEquals(0, trackPending)
            var written = 0
            writeLocalMonitorPcm(inFlight.bytes, { coordinator.isSessionCurrent(owner) },
                { _, _, count -> written += count; count })
            assertEquals(0, written)
            assertNotNull(original.frames.tryReceive().getOrNull())
        } finally { monitor.close(); original.close(); stream.close() }
    }

    @Test fun oldConsentCallbackCannotReleaseTheNewOwner() {
        val coordinator = TranslationSessionCoordinator()
        val old = coordinator.beginSession()
        val current = coordinator.beginSession()
        var cleanup = 0
        assertFalse(handleNativeAudioSessionEnd(NativeAudioEndReason.CONSENT_REVOKED, {}, {
            releaseNativeAudioConsentOwner(coordinator, old, { cleanup++ }, { cleanup++ }, { cleanup++ })
        }))
        assertEquals(0, cleanup)
        assertTrue(coordinator.isSessionCurrent(current))
    }

    @Test fun revokedOwnerIsInvalidBeforeEveryCleanupAndNestedCloseIsIdempotent() {
        val coordinator = TranslationSessionCoordinator()
        val owner = coordinator.beginSession()
        val steps = mutableListOf<String>()
        fun checkReleased(step: String) { assertFalse(coordinator.isSessionCurrent(owner)); steps += step }
        assertTrue(handleNativeAudioSessionEnd(NativeAudioEndReason.CONSENT_REVOKED, {}, {
            releaseNativeAudioConsentOwner(coordinator, owner, { checkReleased("discard") },
                { checkReleased("monitor") }, {
                    checkReleased("preview")
                    assertFalse(handleNativeAudioSessionEnd(NativeAudioEndReason.STOPPED, {}, { fail("Nested close released twice"); false }))
                })
        }))
        assertEquals(listOf("discard", "monitor", "preview"), steps)
        assertFalse(releaseNativeAudioConsentOwner(coordinator, owner, { fail("Repeated discard") }, {}, {}))
    }

    @Test fun oneCleanupFailureCannotLeaveThePreviewOrMonitorUncleaned() {
        val coordinator = TranslationSessionCoordinator()
        val owner = coordinator.beginSession()
        var monitorStopped = false
        var previewStopped = false
        assertThrows(IllegalStateException::class.java) {
            releaseNativeAudioConsentOwner(coordinator, owner, { throw IllegalStateException("Synthetic queue failure") },
                { monitorStopped = true }, { previewStopped = true })
        }
        assertTrue(monitorStopped); assertTrue(previewStopped)
        assertFalse(coordinator.isSessionCurrent(owner))
    }
}
