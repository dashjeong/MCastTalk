package app.guidecast.core.translation

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

enum class BriefingFormality(val label: String) { FORMAL("격식"), NORMAL("보통"), FRIENDLY("친근") }

data class InterpretationBriefing(val topic: String = "", val situation: String = "", val speaker: String = "",
    val audience: String = "", val formality: BriefingFormality = BriefingFormality.FORMAL, val notes: String = "",
    val configured: Boolean = false, val terms: String = "") {
    fun bounded(): InterpretationBriefing {
        var remaining = 600 - formality.label.length
        fun field(value: String): String {
            val text = value.trim().take(remaining).dropLastWhile { Character.isHighSurrogate(it) }
            remaining -= text.length
            return text
        }
        val boundedTerms = field(terms)
        return copy(terms = boundedTerms, topic = field(topic), situation = field(situation), speaker = field(speaker),
            audience = field(audience), notes = field(notes))
    }

    fun referenceText(): String {
        val brief = bounded()
        if (listOf(brief.topic, brief.situation, brief.speaker, brief.audience, brief.notes, brief.terms).all(String::isBlank) &&
            brief.formality == BriefingFormality.FORMAL && !brief.configured) return ""
        val fields = listOf("register" to brief.formality.label, "terms" to brief.terms, "topic" to brief.topic,
            "situation" to brief.situation, "speaker" to brief.speaker, "audience" to brief.audience, "notes" to brief.notes)
        return buildString {
            for ((label, value) in fields) {
                if (value.isBlank() || length + label.length + 3 >= 600) continue
                if (isNotEmpty()) append('\n')
                append(label).append(": ")
                append(value.take(600 - length).dropLastWhile { Character.isHighSurrogate(it) })
            }
        }
    }

    fun recognitionHints(sourceLanguage: String = "ko"): List<String> {
        val brief = bounded()
        val names = buildList {
            addAll(brief.terms.split(Regex("[,，\n]+" )).map { it.substringBefore('=').trim() }.filter(String::isNotBlank))
            addAll(brief.speaker.split(Regex("[\\s,·/()]+" )).filter(String::isNotBlank))
            val text = brief.topic + " " + brief.notes
            Regex("[\"'‘“「『]([^\"'’”」』\\n]{1,80})[\"'’”」』]").findAll(text).forEach { add(it.groupValues[1]) }
            Regex("(?<![A-Za-z0-9])[A-Za-z][A-Za-z0-9._-]{1,79}(?![A-Za-z0-9])").findAll(text).forEach { add(it.value) }
        }
        return mergeInterpretationRecognitionHints(names.filter { matchesRecognitionScript(it, sourceLanguage) }, emptyList(), emptyList(), emptyList())
    }
}

private fun matchesRecognitionScript(text: String, language: String): Boolean {
    val scripts = when (language.substringBefore('-').lowercase()) {
        "ko" -> setOf(Character.UnicodeScript.HANGUL)
        "ja" -> setOf(Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HAN)
        "zh" -> setOf(Character.UnicodeScript.HAN)
        "ru", "uk", "bg" -> setOf(Character.UnicodeScript.CYRILLIC)
        "ar", "fa", "ur" -> setOf(Character.UnicodeScript.ARABIC)
        "hi", "mr", "ne" -> setOf(Character.UnicodeScript.DEVANAGARI)
        "th" -> setOf(Character.UnicodeScript.THAI)
        else -> setOf(Character.UnicodeScript.LATIN)
    }
    val letters = text.codePoints().toArray().filter(Character::isLetter)
    return letters.isNotEmpty() && letters.all {
        Character.UnicodeScript.of(it) in scripts ||
            (language.substringBefore('-').equals("ja", true) && it in setOf(0x30FC, 0xFF70))
    }
}

class InterpretationBriefingContext(val reference: String) : AbstractCoroutineContextElement(Key) {
    init { require(reference.length <= 600) }
    companion object Key : CoroutineContext.Key<InterpretationBriefingContext>
}

fun mergeInterpretationRecognitionHints(briefing: List<String>, corrections: List<String>, glossary: List<String>,
    sentenceMemory: List<String>): List<String> {
    val selected = linkedSetOf<String>()
    var total = 0
    for (group in listOf(briefing, corrections, glossary, sentenceMemory)) for (candidate in group) {
        if (selected.size >= MAX_SPEECH_RECOGNITION_BIASING_PHRASES) return selected.toList()
        val phrase = java.text.Normalizer.normalize(candidate.trim(), java.text.Normalizer.Form.NFC)
        if (phrase in selected || runCatching { normalizeSpeechRecognitionBiasingPhrases(listOf(phrase)) }.isFailure) continue
        val size = phrase.codePointCount(0, phrase.length)
        if (total + size > MAX_SPEECH_RECOGNITION_BIASING_TOTAL_CODE_POINTS) continue
        selected += phrase
        total += size
    }
    return selected.toList()
}
