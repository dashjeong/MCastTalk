package app.guidecast.transmitter

internal data class VoiceNoteFinding(
    val id: String,
    val lineIndex: Int,
    val start: Int,
    val end: Int,
    val before: String,
    val after: String,
    val sourceFingerprint: String,
    val reason: String = "맞춤법·띄어쓰기·문맥을 참고한 AI 수정 후보입니다. 원음과 대조한 뒤 선택하세요.",
)

/** Bounded character alignment yields individual spans, including repeated words and insertions. */
internal fun voiceNoteFindings(index: Int, source: String, corrected: String, offset: Int = 0,
    fullSource: String = source): List<VoiceNoteFinding> {
    require(source.length <= 600 && corrected.length <= 1_200)
    if (source == corrected) return emptyList()
    val originalPoints = source.codePoints().toArray()
    val correctedPoints = corrected.codePoints().toArray()
    val offsets = IntArray(originalPoints.size + 1)
    originalPoints.forEachIndexed { i, point -> offsets[i + 1] = offsets[i] + Character.charCount(point) }
    val width = correctedPoints.size + 1
    val table = IntArray((originalPoints.size + 1) * width)
    for (i in originalPoints.indices.reversed()) for (j in correctedPoints.indices.reversed()) {
        table[i * width + j] = if (originalPoints[i] == correctedPoints[j]) 1 + table[(i + 1) * width + j + 1]
            else maxOf(table[(i + 1) * width + j], table[i * width + j + 1])
    }
    val result = mutableListOf<VoiceNoteFinding>()
    var i = 0; var j = 0
    val fingerprint = voiceNoteSourceFingerprint(fullSource)
    while (i < originalPoints.size || j < correctedPoints.size) {
        if (i < originalPoints.size && j < correctedPoints.size && originalPoints[i] == correctedPoints[j]) { i++; j++; continue }
        val start = i
        val replacement = StringBuilder()
        while (i < originalPoints.size || j < correctedPoints.size) {
            if (i < originalPoints.size && j < correctedPoints.size && originalPoints[i] == correctedPoints[j]) break
            if (j < correctedPoints.size && (i == originalPoints.size || table[i * width + j + 1] >= table[(i + 1) * width + j])) {
                replacement.appendCodePoint(correctedPoints[j++])
            } else i++
        }
        // Alignment uses code points; selection offsets stay UTF-16 for Compose.
        val safeStart = offsets[start]; val safeEnd = offsets[i]
        val after = replacement.toString()
        result += VoiceNoteFinding("$index:${offset + safeStart}:${offset + safeEnd}:$fingerprint",
            index, offset + safeStart, offset + safeEnd, source.substring(safeStart, safeEnd), after, fingerprint)
    }
    return result
}

/** Only explicitly selected, non-overlapping suggestions from the current snapshot can apply. */
internal fun applyVoiceNoteFindings(note: VoiceNote, findings: List<VoiceNoteFinding>): VoiceNote {
    require(findings.isNotEmpty() && findings.map { it.id }.distinct().size == findings.size)
    val grouped = findings.groupBy { it.lineIndex }
    require(grouped.keys.all { it in note.lines.indices })
    val lines = note.lines.mapIndexed { index, current ->
        val changes = grouped[index]?.sortedBy { it.start } ?: return@mapIndexed current
        var previousEnd = -1
        changes.forEach {
            require(it.sourceFingerprint == voiceNoteSourceFingerprint(current.original)) { "문장이 변경됐습니다. 다시 검사하세요." }
            require(it.start >= previousEnd && it.start in 0..current.original.length && it.end in it.start..current.original.length)
            require(current.original.substring(it.start, it.end) == it.before)
            previousEnd = it.end
        }
        var text = current.original
        changes.asReversed().forEach { text = text.replaceRange(it.start, it.end, it.after) }
        require(text.isNotBlank() && text.length <= 65_536)
        val result = current.archiveTranslation(note.targetLanguage).corrected(text, current.translation, false)
        result.copy(translation = result.translations[note.targetLanguage]
            ?.takeIf { result.translationIsCurrent(note.targetLanguage) }?.text.orEmpty())
    }
    return note.copy(lines = lines)
}

/** Undo working text without discarding translations made in other languages since the edit. */
internal fun restoreVoiceNoteCorrection(current: VoiceNote, before: VoiceNote): VoiceNote {
    require(current.id == before.id && current.lines.size == before.lines.size)
    return current.copy(lines = current.lines.mapIndexed { index, line ->
        val old = before.lines[index].archiveTranslation(before.targetLanguage)
        val archived = line.archiveTranslation(current.targetLanguage)
        var restored = archived.copy(original = old.original, edited = old.edited,
            translations = old.translations + archived.translations)
        val variants = restored.translations.mapValues { (language, recent) ->
            if (restored.translationIsCurrent(language)) recent else old.translations[language]
                ?.takeIf { it.sourceFingerprint == voiceNoteSourceFingerprint(if (it.fromOriginal) restored.originalTranscript else restored.original) }
                ?: recent
        }
        restored = restored.copy(translations = variants)
        restored.copy(translation = variants[current.targetLanguage]
            ?.takeIf { restored.translationIsCurrent(current.targetLanguage) }?.text.orEmpty())
    })
}
