package app.guidecast.transmitter

/** Structural checks never silently rewrite recognized speech or claim semantic accuracy. */
internal fun inspectFileTranscript(segments: List<FileSpeechSegment>, durationMs: Long): List<String> {
    FileTranscriptBudget.validate(segments, emptyMap())
    require(segments.isNotEmpty()) { "인식된 음성이 없습니다. 음성 언어와 파일을 확인하세요." }
    require(segments.size <= MAX_FILE_SCRIPT_SEGMENTS) { "문장이 너무 많습니다. 파일을 나누어 변환하세요." }
    require(segments.all { it.text.isNotBlank() && it.text.length <= 20_000 }) { "인식 결과 형식을 확인할 수 없습니다." }
    require(segments.all { it.startMs >= 0 && it.endMs >= it.startMs && it.endMs <= durationMs + 2_000 }) { "오디오와 스크립트 시간 범위가 일치하지 않습니다." }
    require(segments.zipWithNext().all { (a, b) -> a.startMs <= b.startMs }) { "스크립트 시간 순서가 일치하지 않습니다." }
    require(segments.all { s -> s.words.zipWithNext().all { (a, b) -> a.startMs <= b.startMs } &&
        s.words.all { it.startMs in s.startMs..(s.endMs + 1_000) && (it.endMs == null || it.endMs >= it.startMs) }
    }) { "단어의 시간 정보가 일치하지 않습니다." }
    return buildList {
        add("시간 순서·빈 문장·파일 연결 자동 검사 통과. 내용 정확성은 원음을 들으며 확인하세요.")
        if (segments.any { it.timingEstimated || it.words.isEmpty() }) add("일부 구간은 단어 시각을 제공하지 않아 강조 시점을 추정합니다.")
        if (segments.any { it.languageTag == null }) add("언어가 확인되지 않은 구간이 있습니다.")
        if (segments.zipWithNext().any { (a, b) -> a.text == b.text }) add("연속 반복 문장이 있습니다. 원음의 반복인지 확인하세요.")
    }
}

internal fun translationNumbersNeedReview(source: String, translated: String): Boolean {
    val number = Regex("[0-9]+(?:[.,][0-9]+)*")
    fun tokens(text: String) = number.findAll(
        app.guidecast.core.translation.KoreanNumericQuantities.normalizeForTranslation(text, "ko"),
    ).map { it.value }.toList()
    return tokens(source) != tokens(translated)
}
