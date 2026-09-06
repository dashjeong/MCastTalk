package app.guidecast.transmitter

/** Preference is not readiness: every selected voice still requires an offline synthesis check. */
enum class SpeechVoicePreference(val label: String, val enginePackage: String? = null) {
    AUTO("자동 · 설치된 음성"),
    MOONSHINE("Moonshine · 다운로드/준비"),
    SAMSUNG("Samsung 우선", "com.samsung.SMT"),
    GOOGLE("Google 우선", "com.google.android.tts"),
}

internal fun preferInstalledAndroidVoice(
    preference: SpeechVoicePreference,
    moonshineReady: Boolean,
): Boolean = when (preference) {
    SpeechVoicePreference.AUTO -> !moonshineReady
    SpeechVoicePreference.MOONSHINE -> false
    SpeechVoicePreference.SAMSUNG, SpeechVoicePreference.GOOGLE -> true
}

internal class InstalledOfflineVoiceSelected : IllegalStateException(
    "설치된 오프라인 음성 선택 · Moonshine 다운로드는 음성 옵션에서 별도로 요청하세요.",
)
