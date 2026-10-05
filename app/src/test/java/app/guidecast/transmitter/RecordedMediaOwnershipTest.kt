package app.guidecast.transmitter

import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class RecordedMediaOwnershipTest {
    @Test fun shareDeletionIsRecordingScopedAndExpirationSurvivesRestart() {
        val folder = Files.createTempDirectory("synthetic-share-policy").toFile()
        var now = 1000L
        val revoked = mutableListOf<String>()
        try {
            val store = RecordedBroadcastShareStore(folder, { now }, { revoked.add(it.name) })
            val a = UUID.randomUUID().toString(); val b = UUID.randomUUID().toString()
            val first = store.create(a) { it.writeText("synthetic A") }
            val second = store.create(b) { it.writeText("synthetic B") }
            val legacyA = folder.resolve("MCastTalk-123-${UUID.randomUUID()}.zip").apply { writeText("legacy A"); setLastModified(now) }
            val legacyB = folder.resolve("MCastTalk-456-${UUID.randomUUID()}.zip").apply { writeText("legacy B"); setLastModified(now) }
            now += RecordedBroadcastShareStore.RETENTION_MILLIS - 1
            store.cleanup(); assertTrue(first.isFile); assertTrue(second.isFile)
            assertEquals("synthetic B", second.readText()) // delayed receiver within lifetime
            store.deleteRecording(a, uniqueLegacyStartMillis = 123)
            assertFalse(first.exists()); assertTrue(second.exists()); assertFalse(legacyA.exists()); assertTrue(legacyB.exists())
            assertEquals(setOf(first.name, legacyA.name), revoked.toSet())
            now++
            RecordedBroadcastShareStore(folder, { now }, { revoked.add(it.name) }).cleanup()
            assertFalse(second.exists()); assertFalse(legacyB.exists())
            assertEquals(setOf(first.name, legacyA.name, second.name, legacyB.name), revoked.toSet())
        } finally { folder.deleteRecursively() }
    }
    @Test fun failedShareLeavesNoPartialCopyAndUnrelatedFilesAreNotDeleted() {
        val folder = Files.createTempDirectory("synthetic-share-failure").toFile()
        try {
            val unrelated = folder.resolve("user-file.txt").apply { writeText("preserve") }
            val store = RecordedBroadcastShareStore(folder, { Long.MAX_VALUE })
            try { store.create(UUID.randomUUID().toString()) { it.writeText("partial"); error("synthetic failure") }; fail() }
            catch (_: IllegalStateException) { }
            store.cleanup(); assertEquals(listOf(unrelated.name), folder.list()?.toList())
        } finally { folder.deleteRecursively() }
    }
    @Test fun inputAdmissionStopsPlaybackBeforeAnyInputAndRejectsLateAudioStart() {
        val ownership = RecordedPlaybackOwnership(); val order = mutableListOf<String>()
        val playing = ownership.beginPlayback { order.add("speaker-flushed") }
        playing.whileActive { order.add("speaker-started") }
        ownership.beginInput(); order.add("input-admitted")
        assertEquals(listOf("speaker-started", "speaker-flushed", "input-admitted"), order)
        try { playing.whileActive { fail("late AudioTrack start") }; fail() } catch (_: IllegalStateException) { }
        try { ownership.beginPlayback { fail() }; fail() } catch (_: IllegalStateException) { }
        ownership.pauseInput()
        val next = ownership.beginPlayback { order.add("next-flushed") }; next.whileActive { order.add("next-started") }
        playing.close(); assertEquals("next-started", order.last())
        ownership.beginInput(); assertEquals("next-flushed", order.last())
    }
    @Test fun normalPlaybackReleaseDoesNotCancelItsSuccessfulCompletion() {
        val ownership = RecordedPlaybackOwnership(); var cancellations = 0
        val played = ownership.beginPlayback { cancellations++ }; played.release()
        ownership.beginInput(); assertEquals(0, cancellations)
    }
}
