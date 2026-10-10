package app.guidecast.transmitter

import app.guidecast.core.stream.AudioChannelDescriptor
import java.util.Locale

private val recordedLanguageNames = NATIVE_RELAY_LANGUAGE_NAMES.mapKeys { it.key.lowercase(Locale.ROOT) }

/** Restores a display label without changing stored channel identity or audio. */
internal fun recordedChannelDisplayName(channelId: String, languageTag: String, storedName: String = channelId): String {
    if (channelId == "source") return storedName.takeIf {
        it.isNotBlank() && !it.equals(channelId, ignoreCase = true)
    } ?: "원음"
    val language = Locale.forLanguageTag(languageTag)
    val genericLanguageName = language.getDisplayLanguage(Locale.KOREAN)
    val hasCustomName = storedName.isNotBlank() &&
        !storedName.equals(channelId, ignoreCase = true) &&
        !storedName.equals(languageTag, ignoreCase = true) && storedName != genericLanguageName
    if (hasCustomName) return storedName
    return (recordedLanguageNames[languageTag.lowercase(Locale.ROOT)]
        ?: language.getDisplayName(Locale.KOREAN).replace(" (", "(").ifBlank { languageTag }).take(40)
}

internal fun recordedChannelForPresentation(channel: AudioChannelDescriptor): AudioChannelDescriptor =
    channel.copy(displayName = recordedChannelDisplayName(channel.id, channel.languageTag, channel.displayName))
