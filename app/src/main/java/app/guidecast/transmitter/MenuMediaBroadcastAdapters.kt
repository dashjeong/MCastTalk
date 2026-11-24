package app.guidecast.transmitter

/** Stored text is reused verbatim; an unfinished or missing translation is not synthesized. */
internal fun voiceNoteBroadcastCaptions(note: VoiceNote): List<MenuBroadcastCaption> =
    note.lines.map { line ->
        MenuBroadcastCaption(
            startMs = line.startMs,
            endMs = line.endMs,
            original = line.original,
            translations = if (line.translation.isBlank()) emptyMap()
                else mapOf(note.targetLanguage to line.translation),
            translationProviders = line.googleContribution?.takeIf { line.translation.isNotBlank() }
                ?.let { mapOf(note.targetLanguage to it.name) }.orEmpty(),
        )
    }

/** Library translations are indexed by the same source segment ordinal. */
internal fun fileBroadcastCaptions(entry: FileLibraryEntry): List<MenuBroadcastCaption> =
    entry.segments.mapIndexed { index, segment ->
        MenuBroadcastCaption(
            startMs = segment.startMs,
            endMs = segment.endMs,
            original = segment.text,
            translations = entry.translations.mapNotNull { (language, lines) ->
                lines.getOrNull(index)?.takeIf { it.isNotBlank() }?.let { language to it }
            }.toMap(),
            translationProviders = entry.googleContributions.mapNotNull { (language, origins) ->
                origins[index]?.takeIf { !entry.translations[language]?.getOrNull(index).isNullOrBlank() }
                    ?.let { language to it.name }
            }.toMap(),
        )
    }

/** A menu's own output may accompany its work; another output owns the shared server. */
internal fun menuBroadcastBlocks(origin: MenuBroadcastOrigin, state: MenuBroadcastState): Boolean =
    state.isActive && state.origin != origin
