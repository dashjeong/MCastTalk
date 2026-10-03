package app.guidecast.core.translation

import app.guidecast.core.stream.AudioChannelDescriptor
import app.guidecast.core.stream.AudioStreamRegistry
import app.guidecast.core.stream.PcmAudioFrame
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SharedLanguageBroadcastTest {
    @Test fun twoListenersShareOneTranslationAndOneSynthesis() = runTest {
        val registry = AudioStreamRegistry()
        val session = registry.configure(listOf(AudioChannelDescriptor("en", "English", "en", 16_000)))
        val listeners = List(2) { session.subscribe("en") }
        val source = MutableSharedFlow<RecognizedUtterance>()
        var requests = 0
        var synthesis = 0
        val running = TranslationBroadcastPipeline(registry,
            TranslationEngineProvider { TextTranslationEngine { _, _, _ -> requests++; "Hello" } },
            SpeechSynthesisEngineProvider { object : SpeechSynthesisEngine {
                override fun synthesize(text: String, languageTag: String) =
                    flowOf(PcmAudioFrame(ByteArray(640) { 8 }, 1L)).also { synthesis++ }
            } }).start(this, source, listOf(TranslationTarget("en", "English", "en", 16_000)), "ko", session)
        runCurrent()
        source.emit(RecognizedUtterance(1, "안녕", "ko", true, 1L))
        runCurrent()
        val first = listeners[0].frames.tryReceive().getOrNull()
        val second = listeners[1].frames.tryReceive().getOrNull()
        assertNotNull(first); assertNotNull(second)
        assertEquals(1, requests); assertEquals(1, synthesis)
        assertArrayEquals(first!!.bytes, second!!.bytes)
        assertEquals(1L, first.utteranceSequence)
        listeners.forEach { it.close() }; running.close(); session.close()
    }
}
