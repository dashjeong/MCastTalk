package app.mcasttalk.windows.host

import java.time.Instant
import java.util.UUID

const val MAX_CHAT_TEXT_CHARS = 2_000

data class ChatSendCommand(val text: String, val recipientId: String?, val recipientPresenceId: String?)

fun parseChatSend(value: FlatJsonObject): ChatSendCommand {
    val text = value.requiredString("text")
    require(text.length <= MAX_CHAT_TEXT_CHARS) { "Chat text must be at most $MAX_CHAT_TEXT_CHARS characters" }
    require(text.none { it.isISOControl() && it !in "\n\r\t" }) {
        "Chat text contains unsupported control characters"
    }
    val recipientId = value.optionalString("recipientId")
    val recipientPresenceId = value.optionalString("recipientPresenceId")
    recipientId?.let { requireValidIdentifier(it, "recipientId") }
    if (recipientId == null) {
        require(recipientPresenceId == null) { "Public chat must not specify a private recipient presence" }
    } else {
        require(recipientPresenceId != null &&
            runCatching { UUID.fromString(recipientPresenceId).toString() == recipientPresenceId }.getOrDefault(false)) {
            "Private chat requires the current recipientPresenceId"
        }
    }
    return ChatSendCommand(text, recipientId, recipientPresenceId)
}

fun chatMessage(
    roomId: String,
    sender: Participant,
    recipient: Participant?,
    text: String,
    translationStatus: String = "unavailable",
): String = encodeJson(
    linkedMapOf(
        "protocolVersion" to CONTROL_PROTOCOL_VERSION,
        "type" to "CHAT_MESSAGE",
        "roomId" to roomId,
        "messageId" to UUID.randomUUID().toString(),
        "senderId" to sender.id,
        "senderAccountId" to sender.accountId,
        "senderUsername" to sender.username,
        "senderPresenceId" to sender.presenceId,
        "senderDisplayName" to sender.displayName,
        "sentAt" to Instant.now().toString(),
        "scope" to if (recipient == null) "room" else "private",
        "recipientId" to recipient?.id,
        "recipientPresenceId" to recipient?.presenceId,
        "recipientDisplayName" to recipient?.displayName,
        "recipientUsername" to recipient?.username,
        "originalText" to text,
        "sourceLanguage" to sender.preferences.inputLanguage,
        "translationStatus" to translationStatus,
    )
)
