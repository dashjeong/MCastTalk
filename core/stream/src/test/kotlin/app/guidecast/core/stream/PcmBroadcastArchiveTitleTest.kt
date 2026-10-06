package app.guidecast.core.stream

import java.io.File
import java.nio.file.Files
import java.util.Properties
import org.junit.Assert.*
import org.junit.Test

class PcmBroadcastArchiveTitleTest {
    private val source = AudioChannelDescriptor("source", "원음", "ko", 16_000)
    private val pcm = PcmAudioFrame(byteArrayOf(1, 0, 2, 0), 10)

    @Test fun titlePersistsAcrossRenameRestartAndRetainsEveryRecordedByte() {
        val folder = Files.createTempDirectory("recording-title-restart").toFile()
        try {
            val id = PcmBroadcastArchive(folder).use { archive ->
                val id = archive.begin("  첫  안내  ")
                val stream = AudioStreamRegistry().configure(listOf(source))
                archive.attach(id, stream).use { stream.publish("source", pcm) }
                archive.finish(id); archive.flush()
                assertTrue(archive.rename(id, "  입학\n안내\t방송  "))
                assertEquals("입학 안내 방송", archive.snapshot(id)?.title)
                id
            }
            PcmBroadcastArchive(folder).use { archive ->
                val recording = requireNotNull(archive.snapshot(id))
                assertEquals("입학 안내 방송", recording.title)
                assertEquals("COMPLETED", recording.state)
                assertEquals(4L, recording.segments.sumOf { it.committedBytes })
                assertArrayEquals(pcm.bytes, recording.segments.single().file.readBytes())
                assertTrue(archive.rename(id, "두 번째 이름"))
            }
            PcmBroadcastArchive(folder).use { archive -> assertEquals("두 번째 이름", archive.snapshot(id)?.title) }
        } finally { folder.deleteRecursively() }
    }

    @Test fun titleAndIdentitySurvivePauseAndResumeWhileDeleteRemainsProtected() {
        val folder = Files.createTempDirectory("recording-title-pause").toFile()
        try {
            PcmBroadcastArchive(folder).use { archive ->
                val id = archive.begin("강의")
                val registry = AudioStreamRegistry()
                val first = registry.configure(listOf(source))
                archive.attach(id, first).use { first.publish("source", pcm) }
                archive.flush()
                assertEquals("PAUSED", archive.snapshot(id)?.state)
                assertTrue(archive.rename(id, "특강"))
                try { archive.delete(id); fail("A paused recording must remain protected") } catch (_: IllegalStateException) { }
                val second = registry.configure(listOf(source))
                archive.attach(id, second).use { second.publish("source", pcm) }
                archive.finish(id); archive.flush()
                assertEquals(id, archive.snapshots().single().id)
                assertEquals("특강", archive.snapshots().single().title)
                assertEquals(setOf(first.generation, second.generation), archive.snapshots().single().segments.map { it.partId }.toSet())
                assertTrue(archive.delete(id))
                assertNull(archive.snapshot(id))
            }
        } finally { folder.deleteRecursively() }
    }

    @Test fun oldMetadataWithoutTitleCanBeReadAndRenamedWithoutRemovingTrackProperties() {
        val folder = Files.createTempDirectory("recording-title-legacy").toFile()
        try {
            val id = PcmBroadcastArchive(folder).use { archive ->
                val id = archive.begin()
                val stream = AudioStreamRegistry().configure(listOf(source))
                archive.attach(id, stream).use { stream.publish("source", pcm) }
                archive.finish(id); archive.flush(); id
            }
            val metadata = File(folder, "$id/session.properties")
            val properties = Properties().apply { metadata.inputStream().use(::load); remove("title") }
            metadata.outputStream().use { properties.store(it, null) }
            PcmBroadcastArchive(folder).use { archive ->
                assertEquals("", archive.snapshot(id)?.title)
                assertTrue(archive.rename(id, "오래된 방송"))
                assertEquals(4L, requireNotNull(archive.snapshot(id)).segments.single().committedBytes)
            }
        } finally { folder.deleteRecursively() }
    }

    @Test fun rejectedAndFailedRenamesLeaveThePreviousTitleIntact() {
        val folder = Files.createTempDirectory("recording-title-atomic").toFile()
        try {
            PcmBroadcastArchive(folder).use { archive ->
                val id = archive.begin("보존할 이름"); archive.finish(id); archive.flush()
                assertFalse(archive.rename(id, "\n\t "))
                assertFalse(archive.rename(id, "가".repeat(RECORDING_TITLE_MAX_CODE_POINTS + 1)))
                assertFalse(archive.rename("../outside", "제목"))
                assertFalse(archive.rename("00000000-0000-0000-0000-000000000000", "제목"))
                val blockedStaging = File(folder, "$id/session.properties.tmp").apply { mkdir() }
                assertFalse(archive.rename(id, "저장되지 않은 이름"))
                assertEquals("보존할 이름", archive.snapshot(id)?.title)
                blockedStaging.delete()
                assertTrue(archive.rename(id, "다시 저장"))
            }
        } finally { folder.deleteRecursively() }
    }

    @Test fun titleLimitCountsUnicodeCharactersRatherThanUtf16SurrogatePairs() {
        val title = "😀".repeat(RECORDING_TITLE_MAX_CODE_POINTS)
        assertEquals(title, normalizedRecordingTitle(title))
        assertNull(normalizedRecordingTitle(title + "😀"))
        assertEquals("안내 방송", normalizedRecordingTitle("안내\u0000\n 방송"))
    }
}
