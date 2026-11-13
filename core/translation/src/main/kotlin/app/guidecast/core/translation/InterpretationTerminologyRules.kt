package app.guidecast.core.translation

/** 온라인과 기기 내 통역에 같은 용어 우선순위를 전달한다. 자막 원문은 고치지 않는다. */
const val INTERPRETATION_TERMINOLOGY_RULES =
    " Explicit BRIEFING terms and GLOSSARY preferred terms take priority over fallible earlier translations and CONTEXT terminology. " +
    "In BRIEFING terms, source=translation supplies the preferred target spelling when appropriate for the target language. " +
    "For an unambiguous event name in the current recognition within an edit distance of one character of a supplied term, " +
    "interpret it as that term for translation only; do not propagate an earlier mistranslation. " +
    "Do not guess if multiple terms fit or change ordinary words, numbers or negation. " +
    "Conventional Korean greetings such as 안녕하십니까? are salutations, not information-seeking questions. "
