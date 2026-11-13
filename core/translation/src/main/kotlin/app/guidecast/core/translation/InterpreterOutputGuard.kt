package app.guidecast.core.translation

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

class InterpreterOutputRejected(val reason: String) : IllegalStateException("통역 결과를 확인하지 못했습니다.")

suspend fun <T> withInterpreterOutputRetry(onRejected: (String, Boolean) -> Unit = { _, _ -> },
    request: suspend (strict: Boolean) -> T): T {
    for (strict in listOf(false, true)) {
        try { return request(strict) }
        catch (error: InterpreterOutputRejected) {
            onRejected(error.reason, strict)
            if (strict) throw error
        }
    }
    error("Unreachable interpreter retry")
}

/** 오프라인 초안과 선택적 다듬기 모두 같은 반복 검사를 거친다. 재시도도 원래 이력과 비교한다. */
suspend fun withOfflineInterpreterOutputGuard(original: String, contextBefore: String?, sourceLanguage: String,
    targetLanguage: String, onRejected: (Boolean) -> Unit = {},
    request: suspend (contextBefore: String?, strict: Boolean) -> String): String {
    val memory = currentCoroutineContext()[TranslationSessionMemoryContext]
    val previous = (memory?.previousByTarget?.get(targetLanguage) ?: memory?.pairs.orEmpty())
        .filter { it.sourceText.trim() !in original.trim() }.map { it.translatedText }
    val shortKorean = sourceLanguage.substringBefore('-').equals("ko", true) &&
        (original.count { !it.isWhitespace() } <= 6 || original.trim().split(Regex("\\s+")).size <= 2)
    for (strict in listOf(false, true)) {
        val withoutContext = strict || shortKorean
        // 안쪽 번역기가 이미 재시도 후 거절했으면 그대로 전파한다. 여기서 재시도를 증폭하지 않는다.
        val output = if (withoutContext) withContext(TranslationSessionMemoryContext("")) { request(null, strict) }
            else request(contextBefore, strict)
        val reason = InterpreterOutputGuard.rejectionReason(original, output, sourceLanguage, targetLanguage, contextBefore, previous)
        if (reason == null) return output
        onRejected(strict)
        if (strict) throw InterpreterOutputRejected(reason)
    }
    error("Unreachable offline interpreter retry")
}

/** 통역 결과만 허용한다. 거절 이유는 원문을 담지 않는 진단 코드다. */
object InterpreterOutputGuard {
    fun rejectionReason(original: String, output: String, sourceLanguage: String, targetLanguage: String,
        contextBefore: String? = null, previousTranslations: List<String> = emptyList()): String? {
        val source = original.trim()
        val translated = output.trim().trimStart('"', '“', '「')
        if (hasMixedScript(source, translated, targetLanguage)) return "mixed_script"
        val knownSource = sourceLanguage.substringBefore('-').lowercase(java.util.Locale.ROOT) in lexicalSourceLanguages
        if (metaPrefixes.any { rule -> (!rule.lexical || knownSource) &&
                rule.prefix.containsMatchIn(translated) && !rule.spoken.containsMatchIn(source) })
            return "meta_prefix"
        val sourceLength = source.codePointCount(0, source.length).coerceAtLeast(1)
        val outputLength = translated.codePointCount(0, translated.length)
        val fromCompact = compactScript(sourceLanguage)
        val toCompact = compactScript(targetLanguage)
        val expansion = if (fromCompact && !toCompact) 2.0 else if (!fromCompact && toCompact) 0.65 else 1.0
        if (isQuestion(source) && !isQuestion(translated) && (answerMarkers.containsMatchIn(translated) ||
            outputLength > maxOf(48.0, sourceLength * expansion * 1.7) && outputLength > sourceLength + 24))
            return "question_answered"
        if (sourceLength >= 12) {
            val (minimum, maximum) = when {
                fromCompact && !toCompact -> 0.3 to 8.0
                !fromCompact && toCompact -> 0.1 to 3.2
                fromCompact -> 0.2 to 4.0
                else -> 0.18 to 5.0
            }
            val ratio = outputLength.toDouble() / sourceLength
            if (ratio < minimum || ratio > maximum) return "length_ratio"
        }
        if (repeatsContext(source, translated, contextBefore, previousTranslations)) return "repeated_context"
        return null
    }

    fun repeatsContext(original: String, output: String, contextBefore: String? = null,
        previousTranslations: List<String> = emptyList()): Boolean {
        val translated = normalized(output.trim().trimStart('"', '“', '「'))
        val source = normalized(original)
        val earlier = previousTranslations + contextBefore.orEmpty().split(sentenceSeparator)
        return earlier.any { previous ->
            val sentence = normalized(previous)
            sentence.length >= 12 && sentence in translated && sentence !in source && !spokenReply(source, sentence)
        } || earlier.flatMap { it.split(sentenceSeparator) }.any { previous ->
            val sentence = normalized(previous)
            // 짧은 앞 문장은 문장 끝 기호까지 일치하는 접두 반복만 잡는다.
            val prefix = sentence.trimEnd('.', '!', '?', '。', '！', '？')
            prefix.length >= 2 && (sentence.lastOrNull() ?: '\u0000') in ".!?。！？" &&
                translated.length > prefix.length && translated.startsWith(prefix) &&
                (translated.getOrNull(prefix.length) ?: '\u0000') in ".!?。！？" && prefix !in source &&
                !spokenReply(source, sentence)
        }
    }

    private val sentenceSeparator = Regex("(?<=[.!?。！？])\\s*|\\n+")

    // 흔한 완결 응답은 한국어 표현이 달라도 실제 원문에 같은 응답이 있으면 복사로 오인하지 않는다.
    private fun spokenReply(source: String, previous: String): Boolean = spokenReplyRules.any {
        it.prefix.matches(previous) && it.spoken.containsMatchIn(source)
    }
    private val spokenReplyRules = listOf(
        rule("(?:Hello|Hi|안녕하세요|こんにちは|你好)[.!?。！？]?",
            "^(?:안녕|Hello\\b|Hi\\b|こんにちは|你好|Bonjour\\b|Hola\\b|Guten Tag\\b)"),
        rule("(?:No|Nope|아니요|아니오|아니야|いいえ|不行|不是|Non|Nein)[.!?。！？]?",
            "^(?:아니|아뇨|안[ \\t]*돼|못|No\\b|Nope\\b|Non\\b|Nein\\b|いいえ|不行|不是|لا\\b)"),
        rule("(?:Yes|Yeah|Yep|Sure|네|예|응|はい|是的|Sí|Oui|Ja)[.!?。！？]?",
            "^(?:네[,.! \\t]|네$|예[,.! \\t]|예$|응[,.! \\t]|응$|물론|맞아|맞습|Yes\\b|Yeah\\b|Sure\\b|はい|是的|Sí\\b|Oui\\b|Ja\\b|نعم\\b)"),
        rule("(?:Thank you(?: very much)?|Thanks|감사합니다|고맙습니다|ありがとう|谢谢|謝謝|Merci|Gracias|Danke)[.!?。！？]?",
            "^(?:감사|고맙|Thank(?:s| you)\\b|ありがとう|有難|谢谢|謝謝|Merci\\b|Gracias\\b|Danke\\b|شكرا)"),
        rule("(?:I'm sorry|I am sorry|Sorry|미안합니다|죄송합니다|すみません|ごめん|对不起|對不起)[.!?。！？]?",
            "^(?:미안|죄송|I(?:'m| am) sorry\\b|Sorry\\b|すみません|ごめん|申し訳|对不起|對不起|Désolé|Lo siento|Entschuld|آسف)"),
        rule("(?:Welcome|환영합니다|ようこそ|欢迎|歡迎|Willkommen)[.!?。！？]?",
            "^(?:환영|Welcome\\b|ようこそ|欢迎|歡迎|Bienven|Willkommen\\b|أهلا)"),
    )

    private fun hasMixedScript(source: String, output: String, target: String): Boolean {
        val language = target.substringBefore('-').lowercase(java.util.Locale.ROOT)
        val points = output.codePoints().toArray()
        // 그림 문자와 이모지 조합(키캡 포함)은 모든 대상 언어에서 거절한다. 숫자·통화·단위는 보존한다.
        if (points.any { it in 0x1F000..0x1FAFF || it in 0x2600..0x27BF ||
                it in setOf(0xFE0F, 0x200D, 0x20E3, 0x00A9, 0x00AE, 0x2122) }) return true
        if (points.any { point ->
                val script = Character.UnicodeScript.of(point)
                when (language) {
                    "ko" -> script in setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA)
                    "ja" -> script == Character.UnicodeScript.HANGUL
                    "zh" -> script in setOf(Character.UnicodeScript.HANGUL, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA)
                    else -> false
                }
            }) return true
        if (language !in setOf("ko", "ja")) return false
        val sourceWords = latinWord.findAll(source).map { it.value.lowercase(java.util.Locale.ROOT) }.toSet()
        return latinWord.findAll(output).any { match ->
            val word = match.value
            val lower = word.lowercase(java.util.Locale.ROOT)
            val unit = lower in latinUnits && output.take(match.range.first).trimEnd().lastOrNull()?.isDigit() == true
            val name = word in latinNames || (word.length in 2..6 && word.all { it in 'A'..'Z' } && lower !in latinNonNames) ||
                (word.length >= 3 && word.first().isUpperCase() && word.drop(1).all(Char::isLowerCase) && lower !in latinNonNames)
            val sourceNotation = language == "ja" && lower in sourceWords
            !sourceNotation && !unit && !name
        }
    }

    private val latinWord = Regex("[A-Za-z]+(?:['’-][A-Za-z]+)*")
    // 무제한 대문자 시작 허용은 Univ 같은 잘못된 낱말도 통과시키므로 고유명사만 보수적으로 허용한다.
    private val latinNames = setOf("Sony", "Samsung", "Google", "OpenAI", "Microsoft", "Apple", "YouTube")
    private val latinNonNames = setOf("univ", "toilet", "restroom", "bathroom", "hello", "welcome", "today", "tomorrow", "please", "thank", "thanks", "sorry", "translation", "university", "museum", "entrance", "exit", "guide", "information", "visit", "after", "before", "the", "this", "that", "there", "here", "you", "your", "our", "and", "are", "was", "will", "have", "need", "can", "not", "yes")
    private val latinUnits = setOf("km", "m", "cm", "mm", "kg", "g", "mg", "ml", "l", "hz", "mhz", "gb", "mb", "kw", "w", "v", "a", "s", "ms", "min", "h", "c", "f")

    private fun compactScript(tag: String) = tag.substringBefore('-').lowercase() in setOf("ko", "ja", "zh")
    private fun normalized(text: String) = text.lowercase(java.util.Locale.ROOT).replace(Regex("\\s+"), " ").trim()
    private fun isQuestion(text: String): Boolean = '?' in text || '？' in text || '؟' in text ||
        Regex("(?:까|나요|니|습니까|은가요|인가요|는가요|ですか|ますか|でしょうか)[.!。！\\s]*$", RegexOption.IGNORE_CASE).containsMatchIn(text) ||
        Regex("^(?:who|what|where|when|why|how|can|could|would|will|do|does|did|is|are|was|were|have|has)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)

    private data class MetaRule(val prefix: Regex, val spoken: Regex, val lexical: Boolean)
    private fun rule(prefix: String, spoken: String, lexical: Boolean = false) =
        MetaRule(Regex(prefix, RegexOption.IGNORE_CASE), Regex(spoken, RegexOption.IGNORE_CASE), lexical)
    // 모호한 응답 단어는 원문 쪽 표현을 확인할 수 있는 언어에서만 검사한다.
    private val lexicalSourceLanguages = setOf("ko", "en", "ja", "zh", "es", "fr", "de", "ar")
    private const val ACKNOWLEDGMENT = "물론|^네[, .]|알겠습니다|좋습니다|좋아요|^Yes\\b|^Sure\\b|certainly|of course|understood|understand|all right|okay|もちろん|^はい|わかりました|分かりました|承知|了解|是的|当然|好的|明白|知道|Claro|^Sí\\b|Entendido|Bien sûr|compris|Natürlich|verstanden|بالتأكيد|نعم|فهمت"
    private val answerMarkers = Regex("^(?:(?:Yes|No)[,!.\\s]|You (?:should|can|must|need to)\\b|The answer is\\b|I (?:recommend|suggest)\\b)|(?:가시면|하시면|오시면)\\s*됩니다[.!\\s]*$", RegexOption.IGNORE_CASE)
    private val metaPrefixes = listOf(
        rule("^Sure\\s*[,!:;]", ACKNOWLEDGMENT, lexical = true),
        rule("^Here(?: is|'s|’s)\\b", "\\bHere(?: is|'s|’s)\\b|여기|이것|다음은|이곳|이쪽|ここ|これ|こちら|次は|这是|这里|Voici|Hier|Aquí|هنا", lexical = true),
        rule("^Here(?: is|'s|’s) (?:the |your |a )?translation\\b", "translation|번역|翻訳|译文|traduction|traducción|Übersetzung"),
        rule("^As an AI\\b", "\\bAI\\b|인공지능|人工知能"),
        rule("^I (?:can't|can’t|cannot)\\b(?=.{0,50}\\b(?:translat\\w*|assist\\w*|help|comply|fulfill|provide)\\b)",
            "I (?:can't|can’t|cannot)|수 없|못[ 해하가오]|불가능|어렵|できません|できない|不能|无法|no puedo|ne peux pas|kann nicht", lexical = true),
        rule("^I(?:'m|’m| am) sorry\\b(?=.{0,80}\\b(?:translat\\w*|cannot|can't|can’t|assist\\w*|help)\\b)",
            "sorry|미안|죄송|すみません|申し訳|ごめん|对不起|désolé|siento|leid|آسف", lexical = true),
        rule("^(?:Translation\\s*:|번역\\s*[:：]|번역하면)", "Translation\\s*:|번역\\s*[:：]|번역하면|翻訳|译文|traduction|traducción|Übersetzung"),
        rule("^답변\\s*[:：]", "답변\\s*[:：]|Answer\\s*:"),
        rule("^네,\\s", ACKNOWLEDGMENT, lexical = true),
        rule("^알겠습니다", ACKNOWLEDGMENT, lexical = true),
    )
}
