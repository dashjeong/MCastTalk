package app.guidecast.transmitter

/** Shared by conversion, persistence and portable import before allocating a playable library entry. */
internal object FileTranscriptBudget {
    const val MAX_CONTENT_CHARS = 20_000_000L
    const val MAX_ENCODED_CHARS = 20_000_000L
    const val MAX_WORDS = 250_000L
    const val MAX_LANGUAGES = 8
    const val MAX_LIBRARY_FILES = 1_000
    const val MAX_METADATA_CHARS = 16_000
    const val MAX_NOTES = 20
    const val MAX_NOTE_CHARS = 512
    const val MAX_ARCHIVE_ROW_CHARS = 32_000L
    const val MAX_ARCHIVE_LANGUAGES = 7
    const val TOO_LARGE = "스크립트가 안전하게 열 수 있는 크기를 넘습니다. 원본 파일을 구간별로 나누어 변환하세요. 기존 스크립트는 유지합니다."
    const val LIBRARY_FULL = "파일 보관함은 최대 1,000개입니다. 새 파일을 추가하려면 불필요한 보관 항목을 정리하세요. 기존 파일과 스크립트는 유지합니다."

    fun validateMetadata(name: String, uri: String, notes: List<String>) {
        require(name.length <= 500 && uri.length <= 8_192 && notes.size <= MAX_NOTES && notes.all { it.length <= MAX_NOTE_CHARS } &&
            name.length + uri.length + notes.sumOf { it.length } <= MAX_METADATA_CHARS) { "파일 설명 또는 확인 안내가 너무 깁니다. 기존 스크립트는 유지합니다." }
    }

    fun validate(segments: List<FileSpeechSegment>, translations: Map<String, List<String>>) {
        require(segments.size <= MAX_FILE_SCRIPT_SEGMENTS && segments.map { it.id }.distinct().size == segments.size) {
            "문장 식별자가 중복되거나 문장 수가 너무 많습니다."
        }
        require(translations.size <= MAX_LANGUAGES) { TOO_LARGE }
        var chars = 0L
        var words = 0L
        segments.forEach { segment ->
            require(segment.text.length <= 65_536 && segment.words.size <= 4_096) { TOO_LARGE }
            chars += segment.text.length
            words += segment.words.size
            segment.words.forEach { chars += it.text.length }
        }
        translations.values.forEach { lines -> lines.forEach { chars += it.length } }
        require(chars <= MAX_CONTENT_CHARS && words <= MAX_WORDS) { TOO_LARGE }
    }
}
