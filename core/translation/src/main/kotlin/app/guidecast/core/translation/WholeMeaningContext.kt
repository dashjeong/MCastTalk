package app.guidecast.core.translation

/** Keeps a contiguous suffix of whole committed units; never cuts away a qualifier or negation. */
internal fun wholeMeaningContext(prior: Iterable<String>, maxCharacters: Int = 400): String? {
    require(maxCharacters in 1..400)
    val selected = ArrayDeque<String>()
    var length = 0
    for (unit in prior.toList().asReversed()) {
        if (unit.isBlank()) continue
        val added = unit.length + if (selected.isEmpty()) 0 else 1
        // Do not skip an overlong most-recent unit to use unrelated, older context instead.
        if (length + added > maxCharacters) break
        selected.addFirst(unit)
        length += added
    }
    return selected.joinToString(" ").ifBlank { null }
}
