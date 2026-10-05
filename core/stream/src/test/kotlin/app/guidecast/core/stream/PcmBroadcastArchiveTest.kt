package app.guidecast.core.stream

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class PcmBroadcastArchiveTest {
    private val source = AudioChannelDescriptor("source", "원음", "ko", 16_000)
    private val translated = AudioChannelDescriptor("en", "영어", "en", 24_000)
    private fun frame(n: Int, sequence: Long? = null) = PcmAudioFrame(byteArrayOf(n.toByte(), 1, 2, 3), n * 1_000_000L, sequence)

    @Test fun recordsFromFirstFrameWithoutListenersAndSnapshotsStayStableAcrossAppend() {
        val folder = Files.createTempDirectory("replay-zero-listener").toFile()
        try {
            PcmBroadcastArchive(folder, segmentByteLimit = 8).use { archive ->
                val registry = AudioStreamRegistry()
                val session = registry.configure(listOf(source, translated))
                val id = archive.begin()
                val recording = archive.attach(id, session)
                session.publish("source", frame(1)); session.publish("en", frame(2, 7))
                archive.flush()
                val frozen = requireNotNull(archive.snapshot(id))
                assertEquals(2, frozen.segments.size)
                assertEquals(0, session.observabilitySnapshot().totalListeners)
                session.publish("source", frame(3)); session.publish("source", frame(4))
                archive.flush()
                assertEquals(4L, frozen.segments.first { it.channel.id == "source" }.committedBytes)
                val stored = requireNotNull(archive.snapshot(id)).segments.filter { it.channel.id == "source" }
                assertEquals(listOf(8L, 4L), stored.map { it.committedBytes })
                assertArrayEquals(frame(1).bytes + frame(3).bytes + frame(4).bytes, stored.flatMap { it.file.readBytes().toList() }.toByteArray())
                recording.close(); archive.finish(id); archive.flush()
                assertEquals("COMPLETED", archive.snapshot(id)?.state)
            }
        } finally { folder.deleteRecursively() }
    }

    @Test fun finiteRecordingOverflowDoesNotDropLiveFramesAndIsPersisted() {
        val folder = Files.createTempDirectory("replay-overflow").toFile()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        try {
            PcmBroadcastArchive(folder, capacity = 2, beforeWrite = {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
            }).use { archive ->
                val registry = AudioStreamRegistry(listenerBufferFrames = 32)
                val session = registry.configure(listOf(source))
                val listener = session.subscribe("source")
                val id = archive.begin(); val recording = archive.attach(id, session)
                session.publish("source", frame(0))
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                repeat(10) { assertTrue(session.tryPublish("source", frame(it + 1)).accepted) }
                repeat(11) { assertNotNull(listener.frames.tryReceive().getOrNull()) }
                assertEquals(0, session.observabilitySnapshot().droppedFrames)
                release.countDown(); recording.close(); archive.flush()
                assertTrue(requireNotNull(archive.snapshot(id)).droppedRecordingFrames > 0)
                listener.close()
            }
        } finally { release.countDown(); folder.deleteRecursively() }
    }

    @Test fun pauseAndResumeRetainLogicalIdWithSeparateGenerationsAndRejectStaleAudio() {
        val folder = Files.createTempDirectory("replay-parts").toFile()
        try {
            PcmBroadcastArchive(folder).use { archive ->
                val registry = AudioStreamRegistry(); val id = archive.begin()
                val first = registry.configure(listOf(source)); val one = archive.attach(id, first)
                first.publish("source", frame(1)); one.close(); archive.flush()
                assertEquals("PAUSED", archive.snapshot(id)?.state)
                val second = registry.configure(listOf(source)); val two = archive.attach(id, second)
                assertEquals(StreamPublishStatus.STALE_SESSION, first.tryPublish("source", frame(99)).status)
                second.publish("source", frame(2)); two.close(); archive.finish(id); archive.flush()
                assertEquals(1, archive.snapshots().size)
                assertEquals(setOf(first.generation, second.generation), archive.snapshot(id)?.segments?.map { it.partId }?.toSet())
            }
            PcmBroadcastArchive(folder).use { reopened ->
                assertEquals("COMPLETED", reopened.snapshots().single().state)
                assertEquals(8L, reopened.snapshots().single().segments.sumOf { it.committedBytes })
            }
        } finally { folder.deleteRecursively() }
    }

    @Test fun storageFailureAndUncleanEndAreVisibleRatherThanReportedAsComplete() {
        val folder = Files.createTempDirectory("replay-io-failure").toFile()
        try {
            PcmBroadcastArchive(folder, beforeWrite = { throw java.io.IOException("synthetic") }).use { archive ->
                val stream = AudioStreamRegistry().configure(listOf(source))
                val id = archive.begin(); archive.attach(id, stream)
                assertTrue(stream.tryPublish("source", frame(1)).accepted)
                archive.flush()
                assertEquals("STORAGE_WRITE_FAILED", archive.snapshot(id)?.failure)
                assertEquals(1L, archive.snapshot(id)?.droppedRecordingFrames)
                try { archive.delete(id); fail("Do not delete active recording") } catch (_: IllegalStateException) { }
                assertNull(archive.snapshot("../another-session"))
            }
            PcmBroadcastArchive(folder).use { reopened ->
                assertEquals("INTERRUPTED", reopened.snapshots().single().state)
            }
        } finally { folder.deleteRecursively() }
    }
}
