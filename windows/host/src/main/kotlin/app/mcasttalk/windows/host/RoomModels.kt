package app.mcasttalk.windows.host

import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val identifierPattern = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{2,63}$")
private val languageTagPattern = Regex("^[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*$")

enum class AudioMode(val wireValue: String) {
    TRANSLATED("translated"),
    ORIGINAL("original"),
    BOTH("both"),
    CAPTIONS_ONLY("captions_only"),
    ;

    companion object {
        fun fromWire(value: String?): AudioMode =
            entries.firstOrNull { it.wireValue == value } ?: TRANSLATED
    }
}

enum class ServiceMode(val wireValue: String) {
    MULTILINGUAL("multilingual"),
    VOICE("voice"),
    NOTES("notes"),
    FILES("files"),
    ;

    companion object {
        fun fromWire(value: String?): ServiceMode =
            entries.firstOrNull { it.wireValue == value } ?: MULTILINGUAL
    }
}

enum class ParticipantRole(val wireValue: String) {
    SPEAKER("speaker"),
    LISTENER("listener"),
    MODERATOR("moderator"),
    ;

    companion object {
        fun fromWire(value: String?): ParticipantRole =
            entries.firstOrNull { it.wireValue == value } ?: SPEAKER
    }
}

enum class AudioDuckingMode(val wireValue: String) {
    TRANSLATED_ONLY("translated_only"),
    DUCKED_ORIGINAL("ducked_original"),
    ORIGINAL_ONLY("original_only"),
    ;

    companion object {
        fun fromWire(value: String?): AudioDuckingMode =
            entries.firstOrNull { it.wireValue == value } ?: DUCKED_ORIGINAL
    }
}

data class ParticipantPreferences(
    val inputLanguage: String,
    val publishLanguage: String,
    val listenLanguage: String,
    val displayLanguage: String,
    val audioMode: AudioMode,
    val duckingMode: AudioDuckingMode = AudioDuckingMode.DUCKED_ORIGINAL,
    val serviceMode: ServiceMode = ServiceMode.MULTILINGUAL,
    val secondaryOriginalLanguage: String? = null,
) {
    fun understands(language: String): Boolean = language == listenLanguage || language == secondaryOriginalLanguage

    fun wantsTranslatedAudio(source: String): Boolean = !understands(source) &&
        audioMode in setOf(AudioMode.TRANSLATED, AudioMode.BOTH) &&
        duckingMode != AudioDuckingMode.ORIGINAL_ONLY

    companion object {
        fun create(
            inputLanguage: String,
            publishLanguage: String? = null,
            listenLanguage: String,
            displayLanguage: String? = null,
            audioMode: String? = null,
            duckingMode: String? = null,
            serviceMode: String? = null,
            secondaryOriginalLanguage: String? = null,
        ): ParticipantPreferences {
            val normalizedInput = normalizeLanguageTag(inputLanguage)
            val normalizedListen = normalizeLanguageTag(listenLanguage)
            return ParticipantPreferences(
                inputLanguage = normalizedInput,
                publishLanguage = normalizeLanguageTag(publishLanguage ?: normalizedInput),
                listenLanguage = normalizedListen,
                displayLanguage = normalizeLanguageTag(displayLanguage ?: normalizedListen),
                audioMode = AudioMode.fromWire(audioMode),
                duckingMode = AudioDuckingMode.fromWire(duckingMode),
                serviceMode = ServiceMode.fromWire(serviceMode),
                secondaryOriginalLanguage = secondaryOriginalLanguage?.takeIf(String::isNotBlank)?.let(::normalizeLanguageTag)
                    ?.takeUnless { it == normalizedListen },
            )
        }
    }
}

data class Participant(
    val id: String,
    val displayName: String,
    val preferences: ParticipantPreferences,
    val presenceId: String = UUID.randomUUID().toString(),
    val accountId: String? = null,
    val username: String? = null,
    val role: ParticipantRole = ParticipantRole.SPEAKER,
) {
    init {
        requireValidIdentifier(id, "participantId")
        require(displayName.isNotBlank()) { "displayName is required" }
        require(displayName.length <= 64) { "displayName must be 64 characters or fewer" }
        require(!displayName.any(Char::isISOControl)) {
            "displayName must not contain control characters"
        }
    }

    fun withPreferences(updated: ParticipantPreferences): Participant =
        copy(preferences = updated)
}

data class RoomSnapshot(
    val roomId: String,
    val participants: List<Participant>,
) {
    val targetLanguages: Set<String>
        get() = participants.flatMapTo(linkedSetOf()) {
            listOf(it.preferences.listenLanguage, it.preferences.displayLanguage)
        }
}

class RoomDirectory(
    private val maxParticipantsPerRoom: Int = 8,
) {
    private val rooms = ConcurrentHashMap<String, MutableMap<String, Participant>>()

    init {
        require(maxParticipantsPerRoom > 0)
    }

    fun join(roomId: String, participant: Participant): RoomSnapshot {
        requireValidIdentifier(roomId, "roomId")
        var result: RoomSnapshot? = null
        rooms.compute(roomId) { _, existing ->
            val room = existing ?: mutableMapOf()
            require(!room.containsKey(participant.id)) {
                "participantId is already connected"
            }
            require(room.size < maxParticipantsPerRoom) {
                "room capacity reached"
            }
            room[participant.id] = participant
            result = snapshotLocked(roomId, room)
            room
        }
        return checkNotNull(result)
    }

    fun updatePreferences(
        roomId: String,
        participantId: String,
        preferences: ParticipantPreferences,
        expectedPresenceId: String,
    ): RoomSnapshot {
        var result: RoomSnapshot? = null
        rooms.computeIfPresent(roomId) { _, room ->
            val current = room[participantId] ?: error("participant not found")
            require(current.presenceId == expectedPresenceId) { "Participant connection has changed" }
            room[participantId] = current.withPreferences(preferences)
            result = snapshotLocked(roomId, room)
            room
        }
        return result ?: error("room not found")
    }

    fun leave(roomId: String, participantId: String, expectedPresenceId: String): RoomSnapshot? {
        var result: RoomSnapshot? = null
        rooms.computeIfPresent(roomId) { _, room ->
            if (room[participantId]?.presenceId == expectedPresenceId) {
                room.remove(participantId)
            }
            result = snapshotLocked(roomId, room)
            room.takeUnless { it.isEmpty() }
        }
        return result
    }

    fun snapshot(roomId: String): RoomSnapshot? {
        var result: RoomSnapshot? = null
        rooms.computeIfPresent(roomId) { _, room ->
            result = snapshotLocked(roomId, room)
            room
        }
        return result
    }

    /** Called through the membership coordinator; only aggregate counts leave the server. */
    internal fun statistics(): Map<String, Int> {
        val counts = rooms.keys.mapNotNull { snapshot(it)?.participants?.size }
        return mapOf("activeRooms" to counts.size, "joinedConnections" to counts.sum())
    }

    internal fun plans():List<List<Participant>> = rooms.keys.mapNotNull { snapshot(it)?.participants }

    private fun snapshotLocked(
        roomId: String,
        room: Map<String, Participant>,
    ): RoomSnapshot =
        RoomSnapshot(
            roomId = roomId,
            participants = room.values.sortedBy { it.id },
        )
}

fun requireValidIdentifier(value: String, fieldName: String) {
    require(identifierPattern.matches(value)) {
        "$fieldName must be 3-64 URL-safe characters"
    }
}

fun normalizeLanguageTag(value: String): String {
    val trimmed = value.trim()
    require(languageTagPattern.matches(trimmed)) {
        "Invalid BCP 47 language tag: $value"
    }
    val parts = trimmed.split("-")
    return parts.mapIndexed { index, part ->
        when {
            index == 0 -> part.lowercase(Locale.ROOT)
            part.length == 2 && part.all(Char::isLetter) -> part.uppercase(Locale.ROOT)
            part.length == 4 && part.all(Char::isLetter) ->
                part.lowercase(Locale.ROOT).replaceFirstChar(Char::titlecase)
            else -> part.lowercase(Locale.ROOT)
        }
    }.joinToString("-")
}
