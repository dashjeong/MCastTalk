package app.mcasttalk.windows.host

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class RoomSocketHub(
    private val outgoingCapacity: Int = 32,
    private val sendTimeoutMillis: Long = 5_000,
) {
    private val rooms = ConcurrentHashMap<String, MutableMap<String, Connection>>()

    init {
        require(outgoingCapacity > 0)
        require(sendTimeoutMillis > 0)
    }

    /** An identity token owned by exactly one successful socket registration. */
    class Connection internal constructor(
        val presenceId: String,
        capacity: Int,
        private val timeoutMillis: Long,
        private val sendFrame: suspend (Frame) -> Unit,
        private val disconnect: () -> Unit,
        private val isAuthorized: () -> Boolean,
        private val onUnauthorized: () -> Unit,
    ) {
        private val pending = Channel<Frame>(capacity)
        private val stopped = AtomicBoolean(false)
        private var writer: Job? = null

        internal fun start(scope: CoroutineScope) {
            writer = scope.launch {
                try {
                    for (frame in pending) {
                        if (!checkAuthorization()) break
                        withTimeout(timeoutMillis) { sendFrame(frame) }
                    }
                } catch (_: TimeoutCancellationException) {
                    fail()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    fail()
                }
            }
        }

        /** Never suspend a room producer for an individual recipient. */
        fun send(message: String): Boolean = send(Frame.Text(message))

        fun sendBinary(data: ByteArray): Boolean = send(Frame.Binary(true, data))

        fun send(frame: Frame): Boolean {
            if (!checkAuthorization()) return false
            if (pending.trySend(frame).isSuccess) return true
            fail()
            return false
        }

        internal fun stop() {
            if (stopped.compareAndSet(false, true)) {
                pending.cancel()
                writer?.cancel()
            }
        }

        private fun fail() {
            if (stopped.compareAndSet(false, true)) {
                pending.cancel()
                writer?.cancel()
                disconnect()
            }
        }

        private fun checkAuthorization(): Boolean {
            if (stopped.get()) return false
            if (isAuthorized()) return true
            if (stopped.compareAndSet(false, true)) {
                pending.cancel()
                writer?.cancel()
                onUnauthorized()
            }
            return false
        }
    }

    fun attach(
        roomId: String,
        participant: Participant,
        session: DefaultWebSocketServerSession,
        isAuthorized: () -> Boolean,
        onUnauthorized: () -> Unit,
    ): Connection = attachFrame(
        roomId,
        participant.id,
        session,
        sendFrame = { session.send(it) },
        disconnect = { session.cancel(CancellationException("Outbound client is too slow or disconnected")) },
        presenceId = participant.presenceId,
        isAuthorized = isAuthorized,
        onUnauthorized = onUnauthorized,
    )

    internal fun attach(
        roomId: String,
        participantId: String,
        scope: CoroutineScope,
        sendMessage: suspend (String) -> Unit,
        disconnect: () -> Unit,
        presenceId: String = UUID.randomUUID().toString(),
        isAuthorized: () -> Boolean = { true },
        onUnauthorized: () -> Unit = disconnect,
    ): Connection = attachFrame(
        roomId,
        participantId,
        scope,
        sendFrame = { if (it is Frame.Text) sendMessage(it.readText()) },
        disconnect = disconnect,
        presenceId = presenceId,
        isAuthorized = isAuthorized,
        onUnauthorized = onUnauthorized,
    )

    internal fun attachFrame(
        roomId: String,
        participantId: String,
        scope: CoroutineScope,
        sendFrame: suspend (Frame) -> Unit,
        disconnect: () -> Unit,
        presenceId: String = UUID.randomUUID().toString(),
        isAuthorized: () -> Boolean = { true },
        onUnauthorized: () -> Unit = disconnect,
    ): Connection {
        val connection = Connection(presenceId, outgoingCapacity, sendTimeoutMillis, sendFrame, disconnect, isAuthorized, onUnauthorized)
        rooms.compute(roomId) { _, existing ->
            val room = existing ?: mutableMapOf()
            require(!room.containsKey(participantId)) { "participantId already has a socket" }
            room[participantId] = connection
            room
        }
        connection.start(scope)
        return connection
    }

    fun detach(roomId: String, participantId: String, connection: Connection): Boolean {
        var removed = false
        rooms.computeIfPresent(roomId) { _, room ->
            if (room[participantId] === connection) {
                room.remove(participantId)
                removed = true
            }
            room.takeUnless { it.isEmpty() }
        }
        if (removed) connection.stop()
        return removed
    }

    fun broadcast(roomId: String, message: String) {
        // Copy under the same per-room map lock used for attach/detach. Sending is nonblocking.
        var connections: List<Connection> = emptyList()
        rooms.computeIfPresent(roomId) { _, room ->
            connections = room.values.toList()
            room
        }
        connections.forEach { it.send(message) }
    }

    fun broadcastBinary(roomId: String, data: ByteArray, excludeParticipantId: String? = null) {
        var connections: List<Connection> = emptyList()
        rooms.computeIfPresent(roomId) { _, room ->
            connections = room.filterKeys { it != excludeParticipantId }.values.toList()
            room
        }
        // Do not share mutable transport frame/buffer ownership across sessions.
        connections.forEach { it.sendBinary(data.copyOf()) }
    }

    fun sendPrivate(
        roomId: String,
        senderId: String,
        senderConnection: Connection,
        recipientId: String,
        expectedRecipientPresenceId: String,
        message: String,
    ): Boolean = sendTargeted(roomId, senderId, senderConnection, recipientId, expectedRecipientPresenceId, message, true)

    fun sendTargeted(
        roomId: String,
        senderId: String,
        senderConnection: Connection,
        recipientId: String,
        expectedRecipientPresenceId: String,
        message: String,
        echo: Boolean = false,
    ): Boolean {
        var accepted = false
        rooms.computeIfPresent(roomId) { _, room ->
            val recipient = room[recipientId]
            if (room[senderId] === senderConnection && (!echo || senderId != recipientId) &&
                recipient != null && recipient.presenceId == expectedRecipientPresenceId) {
                // Generation, exact socket identity and queue admission share the
                // room lock; a replacement socket can never receive an old DM.
                accepted = recipient.send(message)
                if (accepted && echo) senderConnection.send(message)
            }
            room
        }
        return accepted
    }
}
