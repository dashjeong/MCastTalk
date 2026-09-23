package app.mcasttalk.windows.host

import io.ktor.server.websocket.DefaultWebSocketServerSession

/**
 * Orders membership state with its outbound queue admission, including the initial snapshot.
 * The stable application-level monitor cannot be removed/replaced when a room becomes empty.
 * No operation in this monitor suspends or waits for network I/O. Lock order is coordinator
 * first, then directory/hub per-key map operations; callers must not mutate either directly.
 */
class RoomMembershipCoordinator(
    private val directory: RoomDirectory,
    private val socketHub: RoomSocketHub,
    private val validateLanguagePlan: (List<Participant>) -> Unit = {},
) {
    private val membershipLock = Any()

    fun statistics(): Map<String, Int> = synchronized(membershipLock) { directory.statistics() }

    internal fun <T> withLanguagePlans(action:(List<List<Participant>>)->T):T = synchronized(membershipLock) { action(directory.plans()) }

    internal fun previewLanguagePlan(roomId:String, candidate:Participant) = synchronized(membershipLock) {
        val people=directory.snapshot(roomId)?.participants.orEmpty()
        require(people.none { it.id==candidate.id && it.accountId!=candidate.accountId }) { "Participant identity is already in use" }
        validateLanguagePlan(people.filterNot { it.id==candidate.id }+candidate)
    }

    fun recipients(roomId: String, sender: Participant, recipientId: String? = null,
                   recipientPresenceId: String? = null): List<Participant> = synchronized(membershipLock) {
        val participants = directory.snapshot(roomId)?.participants.orEmpty()
        require(participants.any { it.id == sender.id && it.presenceId == sender.presenceId }) { "Sender left room" }
        if (recipientId == null) participants else {
            val recipient = participants.firstOrNull { it.id == recipientId && it.presenceId == recipientPresenceId }
            require(recipient != null && recipient.id != sender.id) { "Recipient connection has changed" }
            listOf(sender, recipient)
        }
    }

    fun deliver(roomId: String, sender: Participant, connection: RoomSocketHub.Connection,
                recipient: Participant, message: String): Boolean = synchronized(membershipLock) {
        socketHub.sendTargeted(roomId, sender.id, connection, recipient.id, recipient.presenceId, message)
    }

    fun signal(roomId: String, sender: Participant, connection: RoomSocketHub.Connection, value: FlatJsonObject) {
        val targetId = value.requiredString("targetId")
        val presence = value.requiredString("targetPresenceId")
        val kind = value.requiredString("signalType")
        val sdp = value.requiredString("sdp")
        require(kind in setOf("offer", "answer")) { "Unsupported RTC signal" }
        require(sdp.length <= 12_000 && sdp.startsWith("v=0\r\n")) { "Invalid SDP" }
        require(!sdp.contains("m=application")) { "Data channels are not supported" }
        if (sender.role == ParticipantRole.LISTENER) {
            require(!Regex("(?m)^a=(sendrecv|sendonly)\\r?$").containsMatchIn(sdp)) { "Listeners cannot publish media" }
        }
        val target = recipients(roomId, sender, targetId, presence).last()
        require(deliver(roomId, sender, connection, target, encodeJson(mapOf(
            "type" to "RTC_SIGNAL", "senderId" to sender.id, "senderPresenceId" to sender.presenceId,
            "signalType" to kind, "sdp" to sdp,
        )))) { "Recipient is no longer available" }
    }

    fun join(
        roomId: String,
        participant: Participant,
        session: DefaultWebSocketServerSession,
        isAuthorized: () -> Boolean,
        onUnauthorized: () -> Unit,
    ): RoomSocketHub.Connection = join(roomId, participant) {
        socketHub.attach(roomId, participant, session, isAuthorized, onUnauthorized)
    }

    internal fun join(
        roomId: String,
        participant: Participant,
        attach: () -> RoomSocketHub.Connection,
    ): RoomSocketHub.Connection = synchronized(membershipLock) {
        validateLanguagePlan(directory.snapshot(roomId)?.participants.orEmpty()+participant)
        val snapshot = directory.join(roomId, participant)
        var connection: RoomSocketHub.Connection? = null
        try {
            val attached = attach()
            connection = attached
            check(attached.send(roomSnapshotMessage("ROOM_JOINED", snapshot))) {
                "Initial room snapshot could not be queued"
            }
            socketHub.broadcast(roomId, participantEventMessage("PARTICIPANT_JOINED", roomId, participant))
            attached
        } catch (error: Throwable) {
            connection?.let { socketHub.detach(roomId, participant.id, it) }
            directory.leave(roomId, participant.id, participant.presenceId)
            throw error
        }
    }

    fun leave(roomId: String, participant: Participant, connection: RoomSocketHub.Connection): Boolean =
        synchronized(membershipLock) {
            if (!socketHub.detach(roomId, participant.id, connection)) return@synchronized false
            directory.leave(roomId, participant.id, participant.presenceId)
            socketHub.broadcast(roomId, participantEventMessage("PARTICIPANT_LEFT", roomId, participant))
            true
        }

    fun updatePreferences(
        roomId: String,
        participant: Participant,
        preferences: ParticipantPreferences,
    ): Participant = synchronized(membershipLock) {
        val people=directory.snapshot(roomId)?.participants.orEmpty()
        require(people.any { it.id==participant.id && it.presenceId==participant.presenceId }) { "Participant connection has changed" }
        validateLanguagePlan(people.map { if(it.id==participant.id)it.withPreferences(preferences)else it })
        val snapshot = directory.updatePreferences(roomId, participant.id, preferences, participant.presenceId)
        val updated = snapshot.participants.first { it.id == participant.id }
        socketHub.broadcast(roomId, participantEventMessage("PARTICIPANT_UPDATED", roomId, updated))
        updated
    }

    fun sendChat(
        roomId: String,
        sender: Participant,
        senderConnection: RoomSocketHub.Connection,
        chat: ChatSendCommand,
    ) = synchronized(membershipLock) {
        val participants = directory.snapshot(roomId)?.participants.orEmpty()
        require(participants.any { it.id == sender.id && it.presenceId == sender.presenceId }) {
            "Sender is no longer in this room"
        }
        require(chat.recipientId != sender.id) { "Choose another participant for a private message" }
        val recipient = chat.recipientId?.let { recipientId ->
            participants.firstOrNull { it.id == recipientId }
                ?: throw IllegalArgumentException("Recipient is no longer in this room")
        }
        require(recipient == null || recipient.presenceId == chat.recipientPresenceId) {
            "Recipient connection has changed; select the participant again"
        }
        val event = chatMessage(roomId, sender, recipient, chat.text)
        if (recipient == null) {
            socketHub.broadcast(roomId, event)
        } else {
            require(socketHub.sendPrivate(
                roomId, sender.id, senderConnection, recipient.id,
                checkNotNull(chat.recipientPresenceId), event,
            )) { "Recipient is no longer available" }
        }
    }
}
