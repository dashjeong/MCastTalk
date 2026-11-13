package app.guidecast.transmitter

internal enum class VoicePreparationPhase { READY, CHECKING, FAILED, NOT_PREPARED }

internal data class VoicePreparationPresentation(val phase: VoicePreparationPhase, val text: String)

internal fun preparationDisplayText(text: String, developerInfo: Boolean): String = if (developerInfo) text else
    text.replace("Galaxy 온디바이스", "휴대폰").replace("Moonshine", "내려받은 음성")
        .replace("Gemma", "통역").replace("ML Kit", "기본 번역").replace("온디바이스", "휴대폰")
        .replace("TTS", "음성").replace("STT", "말소리 인식").replace("엔진", "서비스")

internal fun SpeechVoicePreference.displayLabel(developerInfo: Boolean): String = if (developerInfo) label else when (this) {
    SpeechVoicePreference.AUTO -> "자동 · 설치된 음성"
    SpeechVoicePreference.MOONSHINE -> "앱에서 받는 음성"
    SpeechVoicePreference.SAMSUNG -> "휴대폰 기본 음성"
    SpeechVoicePreference.GOOGLE -> "별도로 설치한 음성"
}

internal fun TranslationModelUiState.voicePreparationPresentation(languageTag: String): VoicePreparationPresentation = when {
    ttsUnavailableReason(languageTag) != null -> VoicePreparationPresentation(
        VoicePreparationPhase.FAILED, "음성 준비 실패 · 설치/설정 확인",
    )
    ttsFallbackReady(languageTag) -> VoicePreparationPresentation(VoicePreparationPhase.READY, "기기 음성 준비됨")
    ttsReady(languageTag) -> VoicePreparationPresentation(VoicePreparationPhase.READY, "음성 준비됨")
    isBusy -> VoicePreparationPresentation(VoicePreparationPhase.CHECKING, "음성 준비 대기 · 확인 중")
    else -> VoicePreparationPresentation(VoicePreparationPhase.NOT_PREPARED, "음성 준비 필요")
}
