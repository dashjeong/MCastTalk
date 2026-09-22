package app.guidecast.transmitter

import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import org.json.JSONArray
import org.json.JSONObject

/** Three bounded calls: two independent proposals, then the other provider audits the first proposal. */
internal suspend fun compareTeacherTranslations(
    request: CloudReviewRequest,
    secondaryModel: String,
    context: String,
    situation: String,
    previous: TeacherLearningReport?,
    related: List<TeacherLearningReport>,
    previousHuman: SentenceMemoryEntry? = null,
    review: suspend (CloudReviewRequest) -> TeacherReview?,
): ComparativeTeacherResult = supervisorScope {
    val primaryRequest = request.copy(contextBefore = context, situation = situation, comparisonMode = true)
    val secondaryRequest = primaryRequest.copy(provider = request.provider.other(), modelId = secondaryModel)
    val relatedExamples = related.filter { it.original.length <= 400 && (it.after?.length ?: Int.MAX_VALUE) <= 600 }.take(3)
    suspend fun safeReview(value: CloudReviewRequest) = try { review(value) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    val first = async { safeReview(primaryRequest) }
    val second = async { safeReview(secondaryRequest) }
    val primary = first.await()
    val secondary = second.await()
    val validCandidates = listOf(primary, secondary).all { candidate -> candidate != null &&
        conservativeReviewAccepted(request.original, request.draft, candidate.corrected, request.targetLanguageTag) }
    val verification = if (validCandidates && primary != null) safeReview(secondaryRequest.copy(
        draft = primary.corrected, verificationOnly = true,
        referenceTranslation = secondary?.corrected,
        previousTranslation = previous?.after ?: previousHuman?.corrected,
        relatedExamples = relatedExamples.map { ComparativePriorExample(it.original, requireNotNull(it.after), it.comparison?.situation.orEmpty()) },
    )) else null
    val verified = validCandidates && verification != null && primary != null &&
        normalizeMemorySource(verification.corrected) == normalizeMemorySource(primary.corrected) &&
        verification.lessons.isEmpty()
    val changed = primary != null && normalizeMemorySource(primary.corrected) != normalizeMemorySource(request.draft)
    val outcome = when {
        primary == null || secondary == null || validCandidates && verification == null -> TeacherReviewOutcome.UNAVAILABLE
        !validCandidates || !verified -> TeacherReviewOutcome.DISAGREEMENT
        !changed -> TeacherReviewOutcome.UNCHANGED
        primary.lessons.isEmpty() -> TeacherReviewOutcome.NO_LESSON
        else -> TeacherReviewOutcome.PROPOSED
    }
    ComparativeTeacherResult(primary, outcome, ComparativeTeacherEvidence(
        contextBefore = context, situation = situation,
        secondaryProvider = request.provider.other(), secondaryModel = secondaryModel,
        primaryTranslation = primary?.corrected, secondaryTranslation = secondary?.corrected,
        verificationTranslation = verification?.corrected, verified = verified,
        expectedPreviousHash = comparativeVersionHash(previous),
        expectedHumanHash = comparativeHumanHash(previousHuman),
        relatedKeys = relatedExamples.map { it.key },
    ))
}

internal data class ComparativeTeacherResult(val review: TeacherReview?, val outcome: TeacherReviewOutcome,
    val evidence: ComparativeTeacherEvidence)
internal data class ComparativePriorExample(val original: String, val corrected: String, val situation: String) {
    override fun toString() = "ComparativePriorExample(text=redacted)"
}

data class ComparativeTeacherEvidence(
    val contextBefore: String,
    val situation: String,
    val secondaryProvider: CloudReviewProvider,
    val secondaryModel: String,
    val primaryTranslation: String?,
    val secondaryTranslation: String?,
    val verificationTranslation: String?,
    val verified: Boolean,
    val expectedPreviousHash: String,
    val relatedKeys: List<String> = emptyList(),
    val expectedHumanHash: String = comparativeHumanHash(null),
) {
    override fun toString() = "ComparativeTeacherEvidence(verified=$verified, text=redacted)"
    internal fun toJson() = JSONObject().put("context", contextBefore).put("situation", situation)
        .put("provider", secondaryProvider.name).put("model", secondaryModel)
        .put("primary", primaryTranslation ?: JSONObject.NULL).put("secondary", secondaryTranslation ?: JSONObject.NULL)
        .put("verification", verificationTranslation ?: JSONObject.NULL).put("verified", verified)
        .put("previousHash", expectedPreviousHash).put("related", JSONArray(relatedKeys)).put("humanHash", expectedHumanHash)

    internal fun validate() {
        require(contextBefore.length <= 1_000 && situation.length <= 300 && validReviewModel(secondaryModel))
        require((contextBefore + situation).none { it == '\u0000' || (it.code < 32 && it !in "\n\r\t") })
        require(listOf(primaryTranslation, secondaryTranslation, verificationTranslation).all { it == null || it.length in 1..8_000 })
        require(expectedPreviousHash.matches(Regex("[a-f0-9]{64}")))
        require(expectedHumanHash.matches(Regex("[a-f0-9]{64}")))
        require(relatedKeys.size <= 3 && relatedKeys.all { it.matches(Regex("[a-f0-9]{64}")) })
    }
    internal companion object {
        fun fromJson(row: JSONObject) = ComparativeTeacherEvidence(
            row.getString("context"), row.getString("situation"), CloudReviewProvider.valueOf(row.getString("provider")),
            row.getString("model"), row.nullablePortableString("primary"), row.nullablePortableString("secondary"),
            row.nullablePortableString("verification"), row.getBoolean("verified"), row.getString("previousHash"),
            row.getJSONArray("related").let { values -> require(values.length() <= 3); (0 until values.length()).map { values.getString(it) } },
            row.optString("humanHash", comparativeHumanHash(null)),
        ).also { it.validate() }
    }
}

internal fun CloudReviewProvider.other() = if (this == CloudReviewProvider.OPENAI) CloudReviewProvider.GOOGLE else CloudReviewProvider.OPENAI
internal fun comparativeHash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    .joinToString("") { "%02x".format(it) }
internal fun comparativeVersionHash(report: TeacherLearningReport?) = comparativeHash(report?.let {
    listOf(it.key, it.after.orEmpty(), it.createdAtEpochMillis.toString(), it.appliedAtEpochMillis.toString(), it.outcome.name).joinToString("\u0000")
} ?: "absent")
internal fun comparativeHumanHash(entry: SentenceMemoryEntry?) = comparativeHash(entry?.let {
    listOf(it.id.toString(), it.corrected, it.updatedAtEpochMillis.toString(), it.origin.name).joinToString("\u0000")
} ?: "absent")
internal fun comparativeLessonKey(source: String, target: String, register: TranslationRegister, original: String,
    context: String, situation: String) = comparativeHash(listOf(normalizeMemoryLanguage(source), normalizeMemoryLanguage(target),
    register.name, normalizeMemorySource(original), normalizeMemorySource(context), normalizeMemorySource(situation)).joinToString("\u0000"))
internal fun TeacherLearningReport.comparativeKey(): String {
    val evidence = requireNotNull(comparison)
    return comparativeLessonKey(sourceLanguageTag, targetLanguageTag, translationRegister, original, evidence.contextBefore, evidence.situation)
}

/** Similarity discovers review candidates only. It can never authorize applying a correction. */
internal fun comparativeSourceSimilarity(left: String, right: String): Double {
    fun grams(value: String) = normalizeMemorySource(value).lowercase().take(4_000).windowed(2).toSet()
    val a = grams(left); val b = grams(right)
    if (a.isEmpty() || b.isEmpty()) return if (normalizeMemorySource(left) == normalizeMemorySource(right)) 1.0 else 0.0
    return a.intersect(b).size.toDouble() / a.union(b).size
}
