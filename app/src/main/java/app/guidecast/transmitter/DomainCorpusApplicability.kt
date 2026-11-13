package app.guidecast.transmitter

import app.guidecast.core.translation.TranslationStyle
import kotlinx.coroutines.CancellationException

internal data class DomainApplicability(val used: Boolean, val reason: String) {
    val message: String get() = (if (used) "지금 통역에 사용" else "사용 안 함") + " · " + reason
}

/** 적용 가능 여부이며 특정 문장이 이미 치환되었다는 뜻은 아니다. 완전 일치 조건은 유지한다. */
internal fun domainCorpusApplicability(
    profile: DomainCorpusProfile, options: TranslationApiOptions, local: OperatorOptions,
    style: TranslationStyle = options.tone, indexedStyles: Set<TranslationStyle>? = setOf(profile.style),
): DomainApplicability {
    fun unused(reason: String) = DomainApplicability(false, reason)
    if (!profile.active) return unused("자료 모음을 켜 주세요.")
    if (!local.translationEnabled) return unused("통역이 꺼져 있습니다.")
    if (DomainCorpusRepository.normalizeSourceLanguageTag(local.sourceLanguageTag) != profile.sourceLanguageTag ||
        local.targetLanguageTags.none { DomainCorpusRepository.normalizeTargetLanguageTag(it) == profile.targetLanguageTag })
        return unused("지금 고른 언어와 다릅니다.")
    if (indexedStyles == null) return unused("저장은 됐지만 적용 준비가 됐는지는 확인하지 못했습니다.")
    val exact = style == TranslationStyle.AUTO || style in indexedStyles
    val hints = style == TranslationStyle.AUTO || profile.style == TranslationStyle.AUTO || profile.style == style
    if (!exact && !hints) return unused("지금 고른 말투와 다릅니다.")
    if (options.usesNativeLiveAudio) {
        if (!serviceExperience(options).supportsReferences) return unused("이 음성 통역은 참고 자료를 받지 않습니다.")
        if (!options.allowDomainReferences) return unused("참고 자료를 보낼 수 있게 허용해 주세요.")
        return unused("음성 통역은 다음 연결에서 참고 자료를 사용합니다. 연결할 때 포함된 자료를 확인해 주세요.")
    }
    if (options.provider != TranslationApiProvider.LOCAL) {
        // 묶음 요청만 완전 일치 예문을 참고 자료로 바꿔 담는다.
        if (!hints && options.provider !in setOf(TranslationApiProvider.OPENAI, TranslationApiProvider.GEMINI))
            return unused("이 방식에서는 자료 모음과 통역 말투가 같아야 참고합니다.")
        if (!options.allowOnline || !options.hasKey) return unused("온라인 통역 이용 동의와 이용 키를 확인해 주세요.")
        if (!options.allowDomainReferences) return unused("참고 자료를 보낼 수 있게 허용해 주세요.")
        return DomainApplicability(true, "관련 문장이 있으면 참고합니다. 저장한 문장으로 바로 바꾸지는 않습니다.")
    }
    if (exact) return DomainApplicability(true, "언어·말투·원문이 완전히 같을 때 적용합니다.")
    if (local.useGemma) return DomainApplicability(true, "참고만 합니다. 바로 적용하려면 자료와 통역 말투가 같아야 합니다.")
    return unused("자료와 통역 말투가 달라 바로 적용할 수 없습니다.")
}

/** 저장 뒤 적용 준비만 실패한 경우 다시 가져오도록 유도하지 않는다. */
internal fun completeDomainImport(saved: DomainImportResult.Success, publishIndex: () -> Unit): DomainImportResult.Success =
    try { publishIndex(); saved } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { saved.copy(indexReady = false) }
