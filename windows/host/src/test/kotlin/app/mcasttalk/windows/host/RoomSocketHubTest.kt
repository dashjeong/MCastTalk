package app.mcasttalk.windows.host

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoomSocketHubTest {
    @Test
    fun revocationDiscardsAlreadyQueuedMessagesBeforeTransportSend() = runTest {
        val hub = RoomSocketHub()
        val received = mutableListOf<String>()
        var authorized = true
        var rejected = 0
        val connection = hub.attach("room", "user", backgroundScope, { received.add(it) }, {},
            isAuthorized = { authorized }, onUnauthorized = { rejected++ })
        assertTrue(connection.send("private queued message"))
        authorized = false
        runCurrent()
        assertTrue(received.isEmpty())
        assertEquals(1, rejected)
        assertFalse(connection.send("another message"))
    }

    @Test
    fun blockedRecipientDoesNotDelayHealthyRecipientAndTimesOut() = runTest {
        val hub = RoomSocketHub(outgoingCapacity = 4, sendTimeoutMillis = 100)
        val received = mutableListOf<String>()
        var slowDisconnects = 0
        var healthyDisconnects = 0
        hub.attach("room", "slow", backgroundScope, { awaitCancellation() }, { slowDisconnects++ })
        hub.attach("room", "fast", backgroundScope, { received.add(it) }, { healthyDisconnects++ })

        hub.broadcast("room", "first")
        hub.broadcast("room", "second")
        runCurrent()

        assertEquals(listOf("first", "second"), received)
        assertEquals(0, slowDisconnects)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(1, slowDisconnects)

        hub.broadcast("room", "after-timeout")
        runCurrent()
        assertEquals(listOf("first", "second", "after-timeout"), received)
        assertEquals(0, healthyDisconnects)
        assertEquals(1, slowDisconnects)
    }

    @Test
    fun queueOverflowDisconnectsOnlyTheSlowRecipientWithoutWaitingForTimeout() = runTest {
        val hub = RoomSocketHub(outgoingCapacity = 1, sendTimeoutMillis = 60_000)
        val received = mutableListOf<String>()
        var slowDisconnects = 0
        var healthyDisconnects = 0
        hub.attach("room", "slow", backgroundScope, { awaitCancellation() }, { slowDisconnects++ })
        hub.attach("room", "fast", backgroundScope, { received.add(it) }, { healthyDisconnects++ })

        repeat(3) { index ->
            hub.broadcast("room", "message-$index")
            runCurrent()
        }

        assertEquals(1, slowDisconnects)
        assertEquals(0, healthyDisconnects)
        assertEquals(listOf("message-0", "message-1", "message-2"), received)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun staleDetachCannotRemoveReplacementSocket() = runTest {
        val hub = RoomSocketHub()
        val received = mutableListOf<String>()
        val previous = hub.attach("room", "user", backgroundScope, {}, {})
        assertTrue(hub.detach("room", "user", previous))
        val replacement = hub.attach("room", "user", backgroundScope, { received.add(it) }, {})

        assertFalse(hub.detach("room", "user", previous))
        hub.broadcast("room", "still-connected")
        runCurrent()

        assertEquals(listOf("still-connected"), received)
        assertTrue(hub.detach("room", "user", replacement))
    }

    @Test
    fun sendFailureDisconnectsOnceAndKeepsTheOtherRecipientWorking() = runTest {
        val hub = RoomSocketHub()
        val received = mutableListOf<String>()
        var failedDisconnects = 0
        hub.attach("room", "failed", backgroundScope, { error("broken transport") }, { failedDisconnects++ })
        hub.attach("room", "fast", backgroundScope, { received.add(it) }, {})

        hub.broadcast("room", "first")
        runCurrent()
        hub.broadcast("room", "second")
        runCurrent()

        assertEquals(1, failedDisconnects)
        assertEquals(listOf("first", "second"), received)
    }

    @Test
    fun privateDeliveryChecksBothSocketOwnershipAndRecipientGeneration() = runTest {
        val hub = RoomSocketHub()
        val senderMessages = mutableListOf<String>()
        val recipientMessages = mutableListOf<String>()
        val sender = hub.attach("room", "sender", backgroundScope, { senderMessages.add(it) }, {})
        val previous = hub.attach("room", "recipient", backgroundScope, {}, {})
        assertTrue(hub.detach("room", "recipient", previous))
        val replacement = hub.attach("room", "recipient", backgroundScope, { recipientMessages.add(it) }, {})
        assertFalse(hub.sendPrivate("room", "sender", sender, "recipient", previous.presenceId, "stale target"))
        runCurrent()
        assertTrue(senderMessages.isEmpty())
        assertTrue(recipientMessages.isEmpty())
        assertTrue(hub.sendPrivate("room", "sender", sender, "recipient", replacement.presenceId, "new target"))
        runCurrent()
        assertEquals(listOf("new target"), senderMessages)
        assertEquals(listOf("new target"), recipientMessages)
        assertTrue(hub.detach("room", "sender", sender))
        hub.attach("room", "sender", backgroundScope, {}, {})
        assertFalse(hub.sendPrivate("room", "sender", sender, "recipient", replacement.presenceId, "stale sender"))
        runCurrent()
        assertEquals(listOf("new target"), recipientMessages)
    }
}
