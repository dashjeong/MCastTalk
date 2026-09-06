package app.guidecast.provider.moonshine.tts

internal data class MoonshineVoiceSpec(
    val moonshineLanguageTag: String,
    val displayName: String,
    val voiceId: String,
)

internal object MoonshineTtsVoiceCatalog {
    val voices: Map<String, MoonshineVoiceSpec> = linkedMapOf(
        "en" to MoonshineVoiceSpec("en-us", "영어", "kokoro_af_heart"),
        "ja" to MoonshineVoiceSpec("ja-jp", "일본어", "kokoro_jf_alpha"),
        "zh" to MoonshineVoiceSpec("zh-hans", "중국어", "kokoro_zf_xiaoxiao"),
        "nl" to MoonshineVoiceSpec("nl-nl", "네덜란드어", "piper_nl_NL-mls-medium"),
        // Moonshine 0.1.5 advertises es_ES-davefx-medium, but its native streaming
        // manifest asks for four split-stage files that are absent from both official
        // distribution endpoints. es_MX-ald-medium is the release catalog voice whose exact
        // five-file streaming bundle remains available on the official CDN.
        "es" to MoonshineVoiceSpec("es-mx", "스페인어", "piper_es_MX-ald-medium"),
        "ar" to MoonshineVoiceSpec("ar-msa", "아랍어", "piper_ar_JO-kareem-medium"),
    )

    fun requireVoice(languageTag: String): MoonshineVoiceSpec =
        requireNotNull(voices[languageTag]) {
            "Moonshine TTS target is not supported: $languageTag"
        }
}
