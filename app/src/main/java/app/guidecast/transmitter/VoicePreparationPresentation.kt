package app.guidecast.transmitter

internal enum class VoicePreparationPhase { READY, CHECKING, FAILED, NOT_PREPARED }

internal data class VoicePreparationPresentation(val phase: VoicePreparationPhase, val text: String)

internal fun TranslationModelUiState.voicePreparationPresentation(languageTag: String): VoicePreparationPresentation = when {
    ttsUnavailableReason(languageTag) != null -> VoicePreparationPresentation(
        VoicePreparationPhase.FAILED, "음성 준비 실패 · 설치/설정 확인",
    )
    ttsFallbackReady(languageTag) -> VoicePreparationPresentation(VoicePreparationPhase.READY, "기기 음성 준비됨")
    ttsReady(languageTag) -> VoicePreparationPresentation(VoicePreparationPhase.READY, "Moonshine 음성 준비됨")
    isBusy -> VoicePreparationPresentation(VoicePreparationPhase.CHECKING, "음성 준비 대기 · 확인 중")
    else -> VoicePreparationPresentation(VoicePreparationPhase.NOT_PREPARED, "음성 준비 필요")
}
