package app.guidecast.transmitter

import app.guidecast.provider.gemma.translation.GemmaModelReadiness
import kotlinx.coroutines.CancellationException

internal data class GemmaRuntimeVerificationOutcome(
    val readiness: GemmaModelReadiness,
    val translation: String?,
    val ttsDegradedReason: String? = null,
    val failureMessage: String? = null,
)

/**
 * Coordinates Gemma runtime verification independently from speech synthesis.
 *
 * Gemma model readiness (READY) depends strictly on file verification and actual Gemma
 * translation inference. If speech synthesis preparation or synthesis fails (e.g. offline voice
 * download failure or missing Android TTS), the Gemma translator remains READY and speech synthesis
 * degradation is recorded separately as a per-language diagnostic.
 */
internal suspend fun executeGemmaVerification(
    markEngineTesting: suspend () -> Unit,
    selfTest: suspend () -> String,
    markRuntimeReady: suspend () -> Unit,
    markRuntimeFailure: suspend (String) -> Unit,
    probeTts: (suspend (String) -> Unit)? = null,
): GemmaRuntimeVerificationOutcome {
    markEngineTesting()
    val translation = try {
        selfTest()
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        val message = "Gemma Translator 추론 실패: ${error.message ?: error.javaClass.simpleName}"
        markRuntimeFailure(message)
        return GemmaRuntimeVerificationOutcome(
            readiness = GemmaModelReadiness.FAILED,
            translation = null,
            failureMessage = message,
        )
    }

    // Translation inference succeeded: Gemma is ready for broadcast translation.
    markRuntimeReady()

    var ttsDegradedReason: String? = null
    if (probeTts != null) {
        try {
            probeTts(translation)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            ttsDegradedReason = error.message ?: error.javaClass.simpleName
        }
    }

    return GemmaRuntimeVerificationOutcome(
        readiness = GemmaModelReadiness.READY,
        translation = translation,
        ttsDegradedReason = ttsDegradedReason,
    )
}
