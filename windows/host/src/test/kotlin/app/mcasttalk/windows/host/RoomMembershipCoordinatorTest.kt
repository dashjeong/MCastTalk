package app.mcasttalk.windows.host

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoomMembershipCoordinatorTest {
    @Test fun languageAdmissionIsAtomicAndRejectedUpdatePreservesExistingPreferences()=runTest {
        val directory=RoomDirectory();val hub=RoomSocketHub()
        val coordinator=RoomMembershipCoordinator(directory,hub) { people -> require(people.map { it.preferences.inputLanguage }.toSet().size<=1) }
        val alice=participant("alice");val bob=participant("bobby").withPreferences(ParticipantPreferences.create("ja",listenLanguage="ja"))
        coordinator.join(ROOM,alice) { hub.attach(ROOM,alice.id,backgroundScope,{},{},presenceId=alice.presenceId) }
        assertThrows(IllegalArgumentException::class.java) { coordinator.join(ROOM,bob) { error("Must reject before attaching a socket") } }
        assertEquals(listOf(alice),directory.snapshot(ROOM)!!.participants)
        val second=participant("second")
        coordinator.join(ROOM,second) { hub.attach(ROOM,second.id,backgroundScope,{},{},presenceId=second.presenceId) }
        assertThrows(IllegalArgumentException::class.java) { coordinator.updatePreferences(ROOM,second,bob.preferences) }
        assertEquals(second.preferences,directory.snapshot(ROOM)!!.participants.first { it.id==second.id }.preferences)
    }
    @Test
    fun concurrentJoinCannotDisappearBetweenSnapshotAndSocketAttach() = runTest {
        val fixture = Fixture(backgroundScope)
        val alice = participant("alice")
        val bob = participant("bob")
        val aliceMessages = mutableListOf<String>()
        val bobMessages = mutableListOf<String>()
        duringPausedJoin(fixture, alice, aliceMessages) { fixture.join(bob, bobMessages) }
        runCurrent()

        assertEquals(listOf(
            snapshot(alice), joined(alice), joined(bob),
        ), aliceMessages)
        assertEquals(listOf(snapshot(alice, bob), joined(bob)), bobMessages)
        assertEquals(listOf(alice, bob), fixture.directory.snapshot(ROOM)!!.participants)
    }

    @Test
    fun concurrentLeaveCannotLeaveAGhostInTheNewcomerRoster() = runTest {
        val fixture = Fixture(backgroundScope)
        val alice = participant("alice")
        val bob = participant("bob")
        val bobConnection = fixture.join(bob, mutableListOf())
        val aliceMessages = mutableListOf<String>()
        duringPausedJoin(fixture, alice, aliceMessages) {
            assertTrue(fixture.coordinator.leave(ROOM, bob, bobConnection))
        }
        runCurrent()

        assertEquals(listOf(
            snapshot(alice, bob), joined(alice), participantEventMessage("PARTICIPANT_LEFT", ROOM, bob),
        ), aliceMessages)
        assertEquals(listOf(alice), fixture.directory.snapshot(ROOM)!!.participants)
    }

    @Test
    fun concurrentPreferenceUpdateFollowsTheInitialSnapshot() = runTest {
        val fixture = Fixture(backgroundScope)
        val alice = participant("alice")
        val bob = participant("bob")
        fixture.join(bob, mutableListOf())
        val aliceMessages = mutableListOf<String>()
        val newPreferences = ParticipantPreferences.create("en", null, "ja", null, null)
        duringPausedJoin(fixture, alice, aliceMessages) {
            fixture.coordinator.updatePreferences(ROOM, bob, newPreferences)
        }
        runCurrent()

        assertEquals(listOf(
            snapshot(alice, bob), joined(alice),
            participantEventMessage("PARTICIPANT_UPDATED", ROOM, bob.withPreferences(newPreferences)),
        ), aliceMessages)
        assertEquals(newPreferences, fixture.directory.snapshot(ROOM)!!.participants.first { it.id == bob.id }.preferences)
    }

    @Test
    fun chatCannotOvertakeTheNewcomerInitialSnapshot() = runTest {
        val fixture = Fixture(backgroundScope)
        val alice = participant("alice")
        val bob = participant("bob")
        val bobConnection = fixture.join(bob, mutableListOf())
        val aliceMessages = mutableListOf<String>()
        duringPausedJoin(fixture, alice, aliceMessages) {
            fixture.coordinator.sendChat(ROOM, bob, bobConnection, ChatSendCommand("hello", null, null))
        }
        runCurrent()

        assertEquals(3, aliceMessages.size)
        assertEquals(snapshot(alice, bob), aliceMessages[0])
        assertEquals(joined(alice), aliceMessages[1])
        assertEquals("CHAT_MESSAGE", parseFlatJsonObject(aliceMessages[2]).requiredString("type"))
        assertEquals("hello", parseFlatJsonObject(aliceMessages[2]).requiredString("originalText"))
    }

    @Test
    fun failedAttachRollsBackWithoutPublishingAPhantomParticipant() = runTest {
        val fixture = Fixture(backgroundScope)
        val bob = participant("bob")
        val bobMessages = mutableListOf<String>()
        fixture.join(bob, bobMessages)
        runCurrent()
        bobMessages.clear()
        val alice = participant("alice")
        assertThrows(IllegalStateException::class.java) {
            fixture.coordinator.join(ROOM, alice) { error("fixture attachment failure") }
        }
        runCurrent()
        assertTrue(bobMessages.isEmpty())
        assertEquals(listOf(bob), fixture.directory.snapshot(ROOM)!!.participants)
        val aliceMessages = mutableListOf<String>()
        fixture.join(alice, aliceMessages)
        runCurrent()
        assertEquals(listOf(snapshot(alice, bob), joined(alice)), aliceMessages)
    }

    /**
     * Stop after directory.join has produced a snapshot, before socket attach. A competing
     * operation must be blocked on the coordinator monitor, not slip through that old gap.
     * The hook blocks only test fixture threads; production attach never waits on a latch.
     */
    private fun duringPausedJoin(
        fixture: Fixture,
        participant: Participant,
        messages: MutableList<String>,
        concurrentOperation: () -> Unit,
    ) {
        val workers = Executors.newFixedThreadPool(2)
        val enteredGap = CountDownLatch(1)
        val releaseAttach = CountDownLatch(1)
        val operationStarted = CountDownLatch(1)
        val operationThread = AtomicReference<Thread>()
        try {
            val joining = workers.submit {
                fixture.join(participant, messages) {
                    enteredGap.countDown()
                    check(releaseAttach.await(5, TimeUnit.SECONDS)) { "Test did not release attach" }
                }
            }
            assertTrue(enteredGap.await(5, TimeUnit.SECONDS))
            val mutation = workers.submit {
                operationThread.set(Thread.currentThread())
                operationStarted.countDown()
                concurrentOperation()
            }
            assertTrue(operationStarted.await(5, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (operationThread.get().state != Thread.State.BLOCKED && !mutation.isDone && System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertEquals("Membership mutation must wait for snapshot queue admission", Thread.State.BLOCKED, operationThread.get().state)
            releaseAttach.countDown()
            joining.get(5, TimeUnit.SECONDS)
            mutation.get(5, TimeUnit.SECONDS)
        } finally {
            releaseAttach.countDown()
            workers.shutdownNow()
        }
    }

    private class Fixture(private val scope: CoroutineScope) {
        val directory = RoomDirectory()
        private val hub = RoomSocketHub()
        val coordinator = RoomMembershipCoordinator(directory, hub)

        fun join(participant: Participant, messages: MutableList<String>, beforeAttach: () -> Unit = {}): RoomSocketHub.Connection =
            coordinator.join(ROOM, participant) {
                beforeAttach()
                hub.attach(ROOM, participant.id, scope, { messages.add(it) }, {}, participant.presenceId)
            }
    }

    companion object {
        private const val ROOM = "meeting"
        private fun participant(id: String) = Participant(id, id, ParticipantPreferences.create("ko", null, "ko", null, null))
        private fun snapshot(vararg participants: Participant) =
            roomSnapshotMessage("ROOM_JOINED", RoomSnapshot(ROOM, participants.sortedBy { it.id }))
        private fun joined(participant: Participant) = participantEventMessage("PARTICIPANT_JOINED", ROOM, participant)
    }
}
