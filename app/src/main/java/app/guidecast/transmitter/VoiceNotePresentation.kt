package app.guidecast.transmitter

/** Hide only known redundant success copy. Unknown text, including every failure, stays visible. */
internal fun voiceNoteVisibleMessages(messages: List<String?>, detailed: Boolean): List<String> =
    messages.filterNotNull().flatMap { it.lines() }.filter { it.isNotBlank() }.distinct().filter { message ->
        detailed || (message !in ROUTINE_NOTE_MESSAGES && !SAVED_NOTE_MESSAGE.matches(message))
    }

private val ROUTINE_NOTE_MESSAGES = setOf(
    "녹음만 진행 중 · 종료하면 원음을 저장합니다.",
    "녹음·받아쓰기 중 · 말하면 문장이 여기에 나타납니다.",
    "듣고 있습니다",
    "녹음을 저장했습니다. 받아쓰기를 시작할 수 있습니다.",
    "원문과 번역을 저장했습니다. 문장 수정과 원음 대조를 할 수 있습니다.",
)
private val SAVED_NOTE_MESSAGE = Regex("녹음과 [0-9]+개 문장을 저장했습니다\\. 원음 대조·문장 수정·번역을 할 수 있습니다\\.")
