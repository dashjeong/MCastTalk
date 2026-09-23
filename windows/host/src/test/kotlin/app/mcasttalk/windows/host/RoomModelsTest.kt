package app.mcasttalk.windows.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertNotEquals

class RoomModelsTest {
    @Test
    fun normalizesLanguageTagsAndDefaultsPublishAndDisplay() {
        val preferences = ParticipantPreferences.create(
            inputLanguage = "KO",
            publishLanguage = null,
            listenLanguage = "zh-cn",
            displayLanguage = null,
            audioMode = null,
        )

        assertEquals("ko", preferences.inputLanguage)
        assertEquals("ko", preferences.publishLanguage)
        assertEquals("zh-CN", preferences.listenLanguage)
        assertEquals("zh-CN", preferences.displayLanguage)
        assertEquals(AudioMode.TRANSLATED, preferences.audioMode)
    }

    @Test
    fun deduplicatesTargetLanguagesAcrossParticipants() {
        val directory = RoomDirectory(maxParticipantsPerRoom = 4)
        directory.join("room-101", participant("user-a", "ko", "en"))
        val snapshot = directory.join("room-101", participant("user-b", "en", "en"))

        assertEquals(setOf("en"), snapshot.targetLanguages)
        assertEquals(2, snapshot.participants.size)
    }

    @Test
    fun rejectsDuplicateParticipantAndCapacityOverflow() {
        val directory = RoomDirectory(maxParticipantsPerRoom = 1)
        directory.join("room-101", participant("user-a", "ko", "en"))

        assertThrows(IllegalArgumentException::class.java) {
            directory.join("room-101", participant("user-a", "ko", "en"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            directory.join("room-101", participant("user-b", "en", "ko"))
        }
    }

    @Test
    fun presenceIsServerGeneratedAndStaleCleanupCannotRemoveReplacement() {
        val claimed = "00000000-0000-0000-0000-000000000000"
        val payload = """{"type":"JOIN_ROOM","participantId":"user-a","displayName":"Alice","inputLanguage":"ko","listenLanguage":"en","presenceId":"$claimed"}"""
        val first = parseJoinRoom(parseFlatJsonObject(payload)).participant
        val replacement = parseJoinRoom(parseFlatJsonObject(payload)).participant
        assertNotEquals(claimed, first.presenceId)
        assertNotEquals(first.presenceId, replacement.presenceId)
        val directory = RoomDirectory()
        directory.join("room-101", first)
        directory.leave("room-101", first.id, first.presenceId)
        directory.join("room-101", replacement)
        directory.leave("room-101", first.id, first.presenceId)
        assertEquals(replacement.presenceId, directory.snapshot("room-101")!!.participants.single().presenceId)
        assertThrows(IllegalArgumentException::class.java) {
            directory.updatePreferences("room-101", first.id, first.preferences, first.presenceId)
        }
    }

    @Test
    fun concurrentLastLeaveAndNewJoinCannotCreateAnOrphanRoom() {
        val workers = Executors.newFixedThreadPool(2)
        try {
            repeat(500) {
                val directory = RoomDirectory()
                val previous = participant("previous", "ko", "en")
                val newcomer = participant("newcomer", "en", "ko")
                directory.join("room-101", previous)
                val start = CountDownLatch(1)
                val leaving = workers.submit {
                    start.await()
                    directory.leave("room-101", previous.id, previous.presenceId)
                }
                val joining = workers.submit {
                    start.await()
                    directory.join("room-101", newcomer)
                }
                start.countDown()
                leaving.get(5, TimeUnit.SECONDS)
                joining.get(5, TimeUnit.SECONDS)
                assertEquals(listOf(newcomer), directory.snapshot("room-101")!!.participants)
            }
        } finally { workers.shutdownNow() }
    }

    private fun participant(
        id: String,
        input: String,
        listen: String,
    ): Participant =
        Participant(
            id = id,
            displayName = id,
            preferences = ParticipantPreferences.create(
                inputLanguage = input,
                publishLanguage = null,
                listenLanguage = listen,
                displayLanguage = null,
                audioMode = null,
            ),
        )
}
