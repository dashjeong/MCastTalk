package app.guidecast.transmitter

internal data class SpeechCorrectionChange(val prefix: String, val removed: String, val added: String, val suffix: String)

/** Linear, bounded preview; never splits a supplementary Unicode character or edits the source. */
internal fun speechCorrectionChange(original: String, corrected: String): SpeechCorrectionChange {
    val before = original.codePoints().toArray()
    val after = corrected.codePoints().toArray()
    var prefix = 0
    while (prefix < minOf(before.size, after.size) && before[prefix] == after[prefix]) prefix++
    var suffix = 0
    while (suffix < minOf(before.size, after.size) - prefix &&
        before[before.lastIndex - suffix] == after[after.lastIndex - suffix]) suffix++
    return SpeechCorrectionChange(
        String(before, 0, prefix), String(before, prefix, before.size - prefix - suffix),
        String(after, prefix, after.size - prefix - suffix), String(before, before.size - suffix, suffix),
    )
}
