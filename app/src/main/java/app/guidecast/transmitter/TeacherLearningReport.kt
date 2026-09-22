package app.guidecast.transmitter

import org.json.JSONArray
import org.json.JSONObject

enum class TeacherReviewOutcome(val label: String) {
    PROPOSED("승인 대기 · 개선안 준비됨"), HELD("사용자가 보류"), APPROVED("승인 후 적용됨"),
    LEARNED("보정 지식에 반영"), UNCHANGED("변경 불필요 · 학습 생략"),
    REJECTED("보류 · 내용 검사 미통과"), NO_LESSON("보류 · 개선 근거 없음"),
    NOT_SAVED("미반영 · 사용자 확정 또는 저장 제한"), UNAVAILABLE("미반영 · 응답 없음/시간 초과"),
    UNDONE("사용자가 학습 취소"), IMPORTED("이관된 비교 기록 · 적용 권한 없음"),
    DISAGREEMENT("검증 보류 · 모델 불일치/내용 검사"),
}

/** Only selected examples, never the complete transcript. This is user content, not diagnostic logging. */
data class TeacherLearningReport(
    val key: String, val sourceLanguageTag: String, val targetLanguageTag: String,
    val translationRegister: TranslationRegister, val original: String, val before: String, val after: String?,
    val signals: Set<TeacherLearningSignal>, val lessons: Set<TeacherLesson>, val outcome: TeacherReviewOutcome,
    val provider: CloudReviewProvider, val modelId: String,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
    val appliedAtEpochMillis: Long? = null,
    val comparison: ComparativeTeacherEvidence? = null,
) {
    override fun toString() = "TeacherLearningReport(outcome=$outcome, text=redacted)"
    internal fun toJson() = JSONObject().put("id", key).put("createdAt", createdAtEpochMillis)
        .put("sourceLanguage", sourceLanguageTag).put("targetLanguage", targetLanguageTag)
        .put("register", translationRegister.name).put("original", original).put("before", before)
        .put("after", after ?: JSONObject.NULL).put("signals", JSONArray(signals.map { it.name }))
        .put("lessons", JSONArray(lessons.map { it.name })).put("outcome", outcome.name)
        .put("provider", provider.name).put("model", modelId)
        .put("appliedAt", appliedAtEpochMillis ?: JSONObject.NULL)
        .put("comparison", comparison?.toJson() ?: JSONObject.NULL)

    internal companion object {
        fun fromJson(row: JSONObject) = TeacherLearningReport(
            key = row.getString("id"), sourceLanguageTag = row.getString("sourceLanguage"),
            targetLanguageTag = row.getString("targetLanguage"), translationRegister = TranslationRegister.valueOf(row.getString("register")),
            original = row.getString("original"), before = row.getString("before"),
            after = if (row.isNull("after")) null else row.getString("after"),
            signals = row.getJSONArray("signals").let { values -> require(values.length() <= TeacherLearningSignal.entries.size); (0 until values.length()).map { TeacherLearningSignal.valueOf(values.getString(it)) }.toSet() },
            lessons = row.getJSONArray("lessons").let { values -> require(values.length() <= TeacherLesson.entries.size); (0 until values.length()).map { TeacherLesson.valueOf(values.getString(it)) }.toSet() },
            outcome = TeacherReviewOutcome.valueOf(row.getString("outcome")), provider = CloudReviewProvider.valueOf(row.getString("provider")),
            modelId = row.getString("model"), createdAtEpochMillis = row.getLong("createdAt"),
            appliedAtEpochMillis = if (row.isNull("appliedAt")) null else row.getLong("appliedAt"),
            comparison = row.optJSONObject("comparison")?.let(ComparativeTeacherEvidence::fromJson),
        )
    }
}

internal data class TeacherCheck(val label: String, val before: Boolean, val after: Boolean?)

/** These are directly computed checks, not a semantic quality score or a teacher's self-rating. */
internal fun TeacherLearningReport.checks(): List<TeacherCheck> {
    fun numeric(text: String) = Regex("[+-]?[0-9]+(?:[.,:/-][0-9]+)*(?:[%％])?").findAll(text).map { it.value }.sorted().toList()
    return listOf(
        TeacherCheck("숫자 일치", numeric(original) == numeric(before), after?.let { numeric(original) == numeric(it) }),
        TeacherCheck("목표 언어 문자 포함", targetScriptMatches(before, targetLanguageTag), after?.let { targetScriptMatches(it, targetLanguageTag) }),
        TeacherCheck("보수적 내용 검사", conservativeReviewAccepted(original, before, before, targetLanguageTag),
            after?.let { conservativeReviewAccepted(original, before, it, targetLanguageTag) }),
    )
}

internal fun teacherReportExport(rows: List<TeacherLearningReport>): String = JSONObject()
    .put("format", "mcasttalk.teacher-learning-report").put("version", 1)
    .put("scope", "Selected local sentence corrections; no model-weight training; checks are not semantic quality measurements.")
    .put("reports", JSONArray(rows.map { report -> report.toJson().put("checks", JSONArray(report.checks().map { check ->
        JSONObject().put("name", check.label).put("before", check.before).put("after", check.after ?: JSONObject.NULL)
    })) })).toString(2)

internal fun validateTeacherReport(report: TeacherLearningReport) {
    report.comparison?.validate()
    require(report.key.matches(Regex("[a-f0-9]{64}")))
    normalizeMemoryLanguage(report.sourceLanguageTag); normalizeMemoryLanguage(report.targetLanguageTag)
    require(reviewTextWithinBounds(report.original, report.before))
    require(report.after == null || reviewTextWithinBounds(report.original, report.after))
    require(report.signals.isNotEmpty() && validReviewModel(report.modelId))
    require(report.createdAtEpochMillis in 0..253_402_300_799_999L)
    require(report.appliedAtEpochMillis == null || report.appliedAtEpochMillis in 0..253_402_300_799_999L)
}
