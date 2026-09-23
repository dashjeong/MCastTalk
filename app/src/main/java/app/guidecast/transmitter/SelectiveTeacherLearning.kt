package app.guidecast.transmitter

import java.security.MessageDigest
import java.util.Locale

/** Diagnostic signals are candidates, not proof of an incorrect translation. No text is retained here. */
enum class TeacherLearningSignal(val label: String) {
    NUMBERS("숫자 불일치"), UNTRANSLATED("원문 그대로 출력"), TARGET_SCRIPT("목표 언어 확인 필요"),
    LENGTH("누락·과도한 확장 의심"), UNSTABLE("동일 원문의 번역 변동"), USER_REQUEST("사용자 재검토 요청"),
}

enum class TeacherLesson(val label: String) {
    MEANING("의미·누락 보완"), TERMINOLOGY("용어 일관성"), REGISTER("상황에 맞는 문체"),
    FLUENCY("자연스러운 표현"), NUMBERS("숫자·단위 보존"), NEGATION("부정·조건 보존"),
}

internal data class TeacherReview(val corrected: String, val lessons: Set<TeacherLesson> = emptySet()) {
    override fun toString() = "TeacherReview(lessons=$lessons, text=redacted)"
}

data class TeacherLearningProgress(
    val examined: Long = 0, val selected: Long = 0, val skipped: Long = 0,
    val learned: Long = 0, val unchanged: Long = 0, val rejected: Long = 0,
    val reused: Long = 0, val lastSignals: Set<TeacherLearningSignal> = emptySet(),
    val proposed: Long = 0,
)

/** A bounded delta index. Successful/unchanged reviews cool down for a day; failures for 5 minutes.
 * The source/draft/model/style fingerprint changes when the problem changes, allowing a new increment.
 * Normal repeated sentences are never selected merely because they are frequent.
 */
internal class SelectiveTeacherLearning(private val clockMillis: () -> Long) {
    private data class Observation(val draftHash: String, val changed: Boolean)
    private val observations = linkedMapOf<String, Observation>()
    private val reviewedUntil = linkedMapOf<String, Long>()
    private val active = mutableSetOf<String>()
    private val starts = ArrayDeque<Long>()

    @Synchronized fun signals(request: CloudReviewRequest, explicitlyRequested: Boolean): Set<TeacherLearningSignal> {
        if (!reviewTextWithinBounds(request.original, request.draft)) return emptySet()
        val source = normalizeMemorySource(request.original)
        val draft = normalizeMemorySource(request.draft)
        val result = linkedSetOf<TeacherLearningSignal>()
        if (explicitlyRequested) result += TeacherLearningSignal.USER_REQUEST
        if (numbers(source) != numbers(draft)) result += TeacherLearningSignal.NUMBERS
        val differentLanguage = normalizeMemoryLanguage(request.sourceLanguageTag) != normalizeMemoryLanguage(request.targetLanguageTag)
        if (differentLanguage && source.count(Char::isLetter) >= 8 && source.equals(draft, true)) result += TeacherLearningSignal.UNTRANSLATED
        if (!targetScriptMatches(draft, request.targetLanguageTag)) result += TeacherLearningSignal.TARGET_SCRIPT
        if (source.length >= 24 && (draft.length * 5 < source.length || draft.length > source.length * 4)) result += TeacherLearningSignal.LENGTH
        val sourceKey = hash(listOf(request.sourceLanguageTag, request.targetLanguageTag, request.translationRegister.name, source))
        val draftHash = hash(listOf(draft))
        val previous = observations[sourceKey]
        val changed = previous != null && (previous.changed || previous.draftHash != draftHash)
        if (changed) result += TeacherLearningSignal.UNSTABLE
        observations.remove(sourceKey)
        observations[sourceKey] = Observation(draftHash, changed)
        trim(observations)
        return result
    }

    @Synchronized fun acquire(request: CloudReviewRequest): Boolean {
        val key = key(request)
        val now = clockMillis()
        while (starts.isNotEmpty() && now - starts.first() >= 60_000) starts.removeFirst()
        if (key in active || (reviewedUntil[key] ?: Long.MIN_VALUE) > now || starts.size >= 2 || active.size >= 2) return false
        active += key
        starts.addLast(now)
        return true
    }

    @Synchronized fun finish(request: CloudReviewRequest, completed: Boolean) {
        val key = key(request)
        if (!active.remove(key)) return
        reviewedUntil.remove(key)
        reviewedUntil[key] = clockMillis() + if (completed) 86_400_000 else 300_000
        trim(reviewedUntil)
    }

    internal fun key(request: CloudReviewRequest) = hash(listOf(request.provider.name, request.modelId,
        request.sourceLanguageTag, request.targetLanguageTag, request.translationRegister.name,
        normalizeMemorySource(request.original), normalizeMemorySource(request.draft)))

    private fun <T> trim(map: MutableMap<String, T>) { while (map.size > 512) map.remove(map.keys.first()) }
    private fun hash(parts: List<String>): String = MessageDigest.getInstance("SHA-256")
        .digest(parts.joinToString("\u0000").toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun numbers(text: String) = Regex("[+-]?[0-9]+(?:[.,:/-][0-9]+)*(?:[%％])?")
        .findAll(text).map { it.value }.sorted().toList()
}

internal fun targetScriptMatches(text: String, target: String): Boolean {
    val expected = when (target.lowercase(Locale.ROOT).substringBefore('-')) {
        "ko" -> setOf(Character.UnicodeScript.HANGUL)
        "ja" -> setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA)
        "zh" -> setOf(Character.UnicodeScript.HAN)
        "en", "fr", "de", "es", "vi", "nl" -> setOf(Character.UnicodeScript.LATIN)
        "ar" -> setOf(Character.UnicodeScript.ARABIC)
        else -> return true
    }
    return text.codePoints().toArray().any { Character.UnicodeScript.of(it) in expected }
}
