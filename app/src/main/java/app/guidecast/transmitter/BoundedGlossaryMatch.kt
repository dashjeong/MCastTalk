package app.guidecast.transmitter

import app.guidecast.core.translation.GlossaryTerm
import java.text.Normalizer
import java.util.Locale

internal data class GlossaryMatchCandidate(val term: GlossaryTerm, val edited: Boolean)

internal object BoundedGlossaryMatch {
    const val MAX_INPUT_CHARS = 4_000
    const val MAX_PREFIXES = 128
    const val MAX_ROWS_PER_TABLE = 128

    fun normalizedInput(text: String): String? =
        text.takeIf { it.length <= MAX_INPUT_CHARS }?.let { Normalizer.normalize(it, Normalizer.Form.NFC) }

    fun prefixes(text: String): List<String> {
        val found = linkedSetOf<String>()
        for (i in 0 until minOf(text.length, MAX_INPUT_CHARS)) {
            for (length in listOf(2, 1)) {
                found.add(text.substring(i, minOf(i + length, text.length)).lowercase(Locale.ROOT))
                if (found.size == MAX_PREFIXES) return found.toList()
            }
        }
        return found.toList()
    }

    fun saturated(prefixCount: Int, overrideCount: Int, referenceCount: Int): Boolean =
        prefixCount >= MAX_PREFIXES || overrideCount >= MAX_ROWS_PER_TABLE || referenceCount >= MAX_ROWS_PER_TABLE

    fun candidates(
        overrides: List<GlossaryMatchCandidate>,
        reference: List<GlossaryMatchCandidate>,
        overriddenTerms: Set<String>,
    ): List<GlossaryMatchCandidate> = (
        overrides.take(MAX_ROWS_PER_TABLE).filter { it.term.enabled } +
            reference.take(MAX_ROWS_PER_TABLE).filter { it.term.enabled && it.term.sourceTerm !in overriddenTerms }
        ).distinctBy { it.term.sourceTerm }
}
