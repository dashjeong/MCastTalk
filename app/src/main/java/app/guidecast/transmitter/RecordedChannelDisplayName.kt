package app.guidecast.transmitter

import app.guidecast.core.stream.AudioChannelDescriptor
import java.util.Locale

private val recordedLanguageNames = NATIVE_RELAY_LANGUAGE_NAMES.mapKeys { it.key.lowercase(Locale.ROOT) }

/** Restores a display label without changing stored channel identity or audio. */
internal fun recordedChannelDisplayName(channelId: String, languageTag: String, storedName: String = channelId): String {
    if (storedName.isNotBlank() && !storedName.equals(channelId, ignoreCase = true)) return storedName
    if (channelId == "source") return "원음"
    return (recordedLanguageNames[languageTag.lowercase(Locale.ROOT)]
        ?: Locale.forLanguageTag(languageTag).getDisplayLanguage(Locale.KOREAN).ifBlank { languageTag }).take(40)
}

internal fun recordedChannelForPresentation(channel: AudioChannelDescriptor): AudioChannelDescriptor =
    channel.copy(displayName = recordedChannelDisplayName(channel.id, channel.languageTag, channel.displayName))
