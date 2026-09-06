package app.guidecast.transmitter

import app.guidecast.core.translation.SynthesizedPcmStats

/** Session-scoped proof required before the operator UI may show a translation test PASS. */
internal data class TranslationTestAudioEvidence(
    val sessionId: Long,
    val languageTag: String,
    val synthesisPcm: SynthesizedPcmStats? = null,
    val nonSilentPlaybackBytes: Long = 0,
    val playbackWriteFailed: Boolean = false,
) {
    init {
        require(sessionId > 0)
        require(languageTag.isNotBlank())
        require(nonSilentPlaybackBytes >= 0)
    }

    val hasNonSilentSynthesis: Boolean
        get() = synthesisPcm?.isNonSilent(MIN_TRANSLATION_TEST_PEAK) == true

    val hasSuccessfulPlaybackWrite: Boolean
        get() = nonSilentPlaybackBytes > 0 && !playbackWriteFailed

    val passed: Boolean
        get() = hasNonSilentSynthesis && hasSuccessfulPlaybackWrite

    fun withSynthesis(pcm: SynthesizedPcmStats): TranslationTestAudioEvidence =
        copy(synthesisPcm = pcm)

    fun withNonSilentPlaybackWrite(writtenBytes: Int): TranslationTestAudioEvidence {
        require(writtenBytes > 0)
        return copy(nonSilentPlaybackBytes = nonSilentPlaybackBytes + writtenBytes)
    }

    fun withPlaybackWriteFailure(): TranslationTestAudioEvidence =
        copy(playbackWriteFailed = true)
}

internal const val MIN_TRANSLATION_TEST_PEAK = 0.002f
