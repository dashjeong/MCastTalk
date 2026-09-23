package app.mcasttalk.windows.host

const val CONTROL_PROTOCOL_VERSION = 1
const val MAX_CONTROL_MESSAGE_CHARS = 16 * 1024

data class JoinRoomCommand(
    val participant: Participant,
)

fun parseJoinRoom(value: FlatJsonObject): JoinRoomCommand {
    require(value.requiredString("type") == "JOIN_ROOM") {
        "First message must be JOIN_ROOM"
    }
    val role = ParticipantRole.fromWire(value.optionalString("role"))
    return JoinRoomCommand(
        participant = Participant(
            id = value.requiredString("participantId"),
            displayName = value.requiredString("displayName").trim(),
            preferences = value.toPreferences(),
            role = role,
        )
    )
}

fun FlatJsonObject.toPreferences(): ParticipantPreferences =
    ParticipantPreferences.create(
        inputLanguage = requiredString("inputLanguage"),
        publishLanguage = optionalString("publishLanguage"),
        listenLanguage = requiredString("listenLanguage"),
        displayLanguage = optionalString("displayLanguage"),
        audioMode = optionalString("audioMode"),
        duckingMode = optionalString("duckingMode"),
        serviceMode = optionalString("serviceMode"),
        secondaryOriginalLanguage = optionalString("secondaryOriginalLanguage"),
    )

fun roomSnapshotMessage(type: String, snapshot: RoomSnapshot): String =
    encodeJson(
        linkedMapOf(
            "protocolVersion" to CONTROL_PROTOCOL_VERSION,
            "type" to type,
            "roomId" to snapshot.roomId,
            "participants" to snapshot.participants.map(::participantJson),
            "targetLanguages" to snapshot.targetLanguages.sorted(),
        )
    )

fun participantEventMessage(
    type: String,
    roomId: String,
    participant: Participant,
): String =
    encodeJson(
        linkedMapOf(
            "protocolVersion" to CONTROL_PROTOCOL_VERSION,
            "type" to type,
            "roomId" to roomId,
            "participant" to participantJson(participant),
        )
    )

fun subtitleChunkMessage(
    roomId: String,
    speakerId: String,
    originalText: String,
    translatedText: String,
    sourceLanguage: String,
    targetLanguage: String,
    isFinal: Boolean = true,
): String =
    encodeJson(
        linkedMapOf(
            "protocolVersion" to CONTROL_PROTOCOL_VERSION,
            "type" to "SUBTITLE_CHUNK",
            "roomId" to roomId,
            "speakerId" to speakerId,
            "originalText" to originalText,
            "translatedText" to translatedText,
            "sourceLanguage" to sourceLanguage,
            "targetLanguage" to targetLanguage,
            "isFinal" to isFinal,
            "timestamp" to System.currentTimeMillis(),
        )
    )

fun errorMessage(code: String, message: String): String =
    encodeJson(
        linkedMapOf(
            "protocolVersion" to CONTROL_PROTOCOL_VERSION,
            "type" to "ERROR",
            "code" to code,
            "message" to message,
        )
    )

fun pongMessage(): String =
    encodeJson(
        linkedMapOf(
            "protocolVersion" to CONTROL_PROTOCOL_VERSION,
            "type" to "PONG",
        )
    )

private fun participantJson(participant: Participant): Map<String, Any?> =
    linkedMapOf(
        "participantId" to participant.id,
        "accountId" to participant.accountId,
        "username" to participant.username,
        "presenceId" to participant.presenceId,
        "displayName" to participant.displayName,
        "role" to participant.role.wireValue,
        "inputLanguage" to participant.preferences.inputLanguage,
        "publishLanguage" to participant.preferences.publishLanguage,
        "listenLanguage" to participant.preferences.listenLanguage,
        "displayLanguage" to participant.preferences.displayLanguage,
        "audioMode" to participant.preferences.audioMode.wireValue,
        "duckingMode" to participant.preferences.duckingMode.wireValue,
        "serviceMode" to participant.preferences.serviceMode.wireValue,
        "secondaryOriginalLanguage" to (participant.preferences.secondaryOriginalLanguage ?: ""),
    )
