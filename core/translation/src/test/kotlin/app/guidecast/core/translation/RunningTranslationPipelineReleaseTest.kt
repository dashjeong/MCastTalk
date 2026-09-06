package app.guidecast.core.translation

import app.guidecast.core.stream.AudioChannelDescriptor
import app.guidecast.core.stream.AudioStreamRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class RunningTranslationPipelineReleaseTest {
    @Test
    fun `explicit stop immediately releases all fifteen language queues and preserves shared source`() {
        var accepting = true
        var released = 0
        val queues = List(15) {
            Channel<ByteArray>(8, onUndeliveredElement = {
                assertFalse("An intentional stop must not be counted as congestion", accepting)
                released++
            }).apply { repeat(8) { trySend(ByteArray(1024)) } }
        }
        val session = AudioStreamRegistry().configure(listOf(AudioChannelDescriptor("source", "원음", "ko", 16_000)))
        val running = RunningTranslationPipeline(
            health = MutableStateFlow(emptyList()), streamSession = session, ownsStreamSession = false,
            isolationJob = Job(), sourceJob = Job(), workers = List(5) { Job() }, queues = queues,
            stopAcceptingSource = { accepting = false },
        )
        running.close()
        assertEquals(120, released)
        queues.forEach { assertTrue(it.tryReceive().isClosed) }
        assertTrue(session.isActive())
        running.close()
        assertEquals(120, released)
        session.close()
    }

    @Test
    fun `owned stream closes but normal channel close remains drainable`() {
        val queue = Channel<String>(2)
        queue.trySend("complete meaning unit")
        queue.close()
        assertEquals("complete meaning unit", queue.tryReceive().getOrThrow())
        val session = AudioStreamRegistry().configure(listOf(AudioChannelDescriptor("en", "English", "en", 16_000)))
        RunningTranslationPipeline(MutableStateFlow(emptyList()), session, true, Job(), Job(), emptyList(), listOf(queue), {}).close()
        assertFalse(session.isActive())
    }
}
