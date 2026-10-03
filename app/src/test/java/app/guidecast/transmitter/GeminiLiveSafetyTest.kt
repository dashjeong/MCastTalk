package app.guidecast.transmitter

import app.guidecast.core.stream.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GeminiLiveSafetyTest {
    @Test fun threeLanguageDiagnosticsSeparateInputLossAndOutputWithoutSavingContent() {
        val languages = listOf("en", "zh-Hans", "ja").associateWith { GeminiLiveCounters() }
        languages.values.forEach { it.capture(640) }
        languages.getValue("en").event(GeminiLiveEvent("private source", "private result", true, false, listOf(ByteArray(960)), null))
        languages.getValue("zh-Hans").loss(LiveAudioLoss.BEFORE_READY, 640)
        languages.getValue("ja").event(GeminiLiveEvent(null, null, false, true, emptyList(), null))
        assertTrue(languages.getValue("en").summary().contains("outputBytes=960 turns=1"))
        assertTrue(languages.getValue("zh-Hans").summary().contains("BEFORE_READYBytes=640"))
        assertTrue(languages.getValue("ja").summary().contains("interruptions=1"))
        languages.values.forEach {
            assertTrue(it.summary().contains("captureFrames=1 captureBytes=640"))
            assertFalse(it.summary().contains("private"))
        }
    }

    @Test fun preparationOverflowAndAbandonedInputAreDistinctAndNeverReplayed() = runTest {
        val monitor = GeminiLiveMonitor()
        val input = GeminiLiveInput { reason, bytes -> monitor.loss("en", reason, bytes) }
        repeat(3) { input.offer(ByteArray(640) { 1 }) }
        assertEquals(1920L, monitor.state.value.lossesByLanguage.getValue("en").getValue(LiveAudioLoss.BEFORE_READY).bytes)
        input.markReady()
        input.offer(ByteArray(640) { 2 })
        assertEquals(2.toByte(), input.frames.first()[0])
        repeat(5) { assertTrue(input.offer(ByteArray(640))) }
        assertFalse(input.offer(ByteArray(640)))
        val loss = monitor.state.value.lossesByLanguage.getValue("en")
        assertEquals(1L, loss.getValue(LiveAudioLoss.INPUT_OVERFLOW).frames)
        assertEquals(5L, loss.getValue(LiveAudioLoss.INPUT_ABANDONED).frames)
        input.markReady() // A late setup callback cannot reopen a cancelled connection.
        input.offer(ByteArray(640))
        assertEquals(6L, monitor.state.value.lossesByLanguage.getValue("en").getValue(LiveAudioLoss.INPUT_ABANDONED).frames)
    }

    @Test fun realListenerQueueOverflowAndStaleSessionAreVisiblePerLanguage() {
        val registry = AudioStreamRegistry(listenerBufferFrames = 1)
        val descriptors = listOf(AudioChannelDescriptor("en", "English", "en", 24000))
        val session = registry.configure(descriptors)
        val listener = session.subscribe("en")
        val monitor = GeminiLiveMonitor()
        fun publish() = recordLivePublication(session.tryPublish("en", PcmAudioFrame(ByteArray(960), 1)), 960) { reason, bytes -> monitor.loss("en", reason, bytes) }
        publish(); publish()
        assertEquals(1L, monitor.state.value.lossesByLanguage.getValue("en").getValue(LiveAudioLoss.LISTENER_OVERFLOW).frames)
        registry.configure(descriptors)
        publish()
        recordLivePublication(null, 960) { reason, bytes -> monitor.loss("en", reason, bytes) }
        assertEquals(2L, monitor.state.value.lossesByLanguage.getValue("en").getValue(LiveAudioLoss.OUTPUT_BLOCKED).frames)
        listener.close()
    }

    @Test fun audioWithoutSourceAndLateSourceRemainAnExplicitIndependentSegment() {
        val english = GeminiLiveSegments("en", "ko", 100)
        val japanese = GeminiLiveSegments("ja", "ko", 200)
        fun event(source: String? = null, finished: Boolean = false, interrupted: Boolean = false) =
            GeminiLiveEvent(source, null, finished, interrupted, listOf(ByteArray(640)), null)
        val unknown = requireNotNull(english.accept(event(), 1))
        assertEquals("", unknown.sourceText)
        assertEquals("en", unknown.liveSegmentLanguage)
        val late = requireNotNull(english.accept(event("원문", true), 2))
        assertEquals(unknown.sequence, late.sequence)
        assertEquals(1L, late.capturedAtElapsedRealtimeNanos)
        assertEquals("원문", late.sourceText)
        assertNotEquals(late.sequence, requireNotNull(japanese.accept(event("다른 분절"), 2)).sequence)
        assertNull(english.accept(event(interrupted = true), 3))
        assertEquals("", requireNotNull(english.accept(event(), 4)).sourceText)
    }
}
