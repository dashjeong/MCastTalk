package app.guidecast.provider.gemma.translation

import java.util.Locale

/**
 * DMZ 평화걷기 및 한국 관광 전문 용어 앵커 (Google Experience Optimization)
 *
 * 온디바이스 SLM(Gemma 4 E2B)이 자의적으로 직역하기 쉬운 고유명사 및 군사·역사·관광 용어를
 * 정확하게 번역할 수 있도록 프롬프트에 경량 힌트(최대 3개)를 주입합니다.
 */
internal object GemmaDomainTerminologyAnchor {

    private data class TermTranslation(
        val korean: String,
        val english: String,
        val japanese: String,
        val simplifiedChinese: String,
        val spanish: String,
    ) {
        fun forTargetLanguage(targetLanguageCode: String): String = when (targetLanguageCode) {
            "en" -> english
            "ja" -> japanese
            "zh" -> simplifiedChinese
            "es" -> spanish
            else -> english
        }
    }

    private val GLOSSARY = listOf(
        TermTranslation("제3땅굴", "the 3rd Infiltration Tunnel", "第3トンネル", "第三地道", "el tercer túnel de infiltración"),
        TermTranslation("제 3 땅굴", "the 3rd Infiltration Tunnel", "第3トンネル", "第三地道", "el tercer túnel de infiltración"),
        TermTranslation("제3 땅굴", "the 3rd Infiltration Tunnel", "第3トンネル", "第三地道", "el tercer túnel de infiltración"),
        TermTranslation("도라전망대", "Dora Observatory", "都羅展望台", "都罗展望台", "el observatorio Dora"),
        TermTranslation("도라산역", "Dorasan Station", "都羅山駅", "都罗山站", "la estación de Dorasan"),
        TermTranslation("도라산", "Mount Dora", "都羅山", "都罗山", "el monte Dora"),
        TermTranslation("임진각", "Imjingak", "臨津閣", "临津阁", "Imjingak"),
        TermTranslation("평화누리", "Pyeonghwa Nuri Park", "平和ヌリ公園", "和平世界公园", "el parque Pyeonghwa Nuri"),
        TermTranslation("비무장지대", "the DMZ (Demilitarized Zone)", "非武装地帯 (DMZ)", "非军事区 (DMZ)", "la Zona Desmilitarizada (DMZ)"),
        TermTranslation("군사분계선", "the Military Demarcation Line (MDL)", "軍事境界線 (MDL)", "军事分界线 (MDL)", "la Línea de Demarcación Militar (MDL)"),
        TermTranslation("판문점", "Panmunjom", "板門店", "板门店", "Panmunjom"),
        TermTranslation("자유의 집", "House of Freedom", "自由の家", "自由之家", "la Casa de la Libertad"),
        TermTranslation("평화의 길", "DMZ Peace Trail", "平和の道", "和平之路", "el Sendero de la Paz de la DMZ"),
        TermTranslation("유엔사", "the United Nations Command (UNC)", "国連軍司令部", "联合国军司令部", "el Comando de las Naciones Unidas"),
    )

    private const val MAX_TERMS_PER_PROMPT = 3

    /**
     * 한국어 원문에 포함된 핵심 관광/DMZ 용어를 추출하여 프롬프트 주입용 힌트 문자열을 생성합니다.
     */
    fun extractGlossaryHints(
        sourceText: String,
        targetLanguageTag: String,
    ): String? {
        if (sourceText.isBlank()) return null
        val targetCode = targetLanguageTag.substringBefore('-').lowercase(Locale.ROOT)

        val matchedTerms = mutableListOf<String>()
        for (term in GLOSSARY) {
            if (matchedTerms.size >= MAX_TERMS_PER_PROMPT) break
            if (sourceText.contains(term.korean)) {
                val targetTerm = term.forTargetLanguage(targetCode)
                matchedTerms.add("\"${term.korean}\": \"$targetTerm\"")
            }
        }

        if (matchedTerms.isEmpty()) return null
        return matchedTerms.joinToString(", ")
    }
}
