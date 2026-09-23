package app.guidecast.provider.gemma.translation

import app.guidecast.core.translation.SourceSemanticCue
import app.guidecast.core.translation.SourceSemanticEvidence

/** Renders shared source-only evidence as optional prompt data, never replacement text. */
internal object SourceSemanticHints {
    private const val HUMAN_SUBJECT_HINT =
        "The quantified 분 subject denotes people (honorific person-count classifier), not elapsed time. " +
            "Its count, participants and statement/question meaning remain unchanged."

    fun extract(sourceLanguage: String, sourceText: String): String =
        SourceSemanticEvidence.classify(sourceLanguage, sourceText).joinToString(" ") { cue ->
            when (cue) {
                SourceSemanticCue.KOREAN_HONORIFIC_PERSON_SUBJECT -> HUMAN_SUBJECT_HINT
            }
        }
}
