package app.guidecast.provider.gemma.translation

import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags

/**
 * Request GPU speculative decoding only for the verified E4B-IT family.
 * Null preserves the SDK/model default for all other paths, including CPU recovery.
 * This is an execution option, not a promise of identical output or faster translation.
 */
@OptIn(ExperimentalApi::class)
internal fun configureGemmaSpeculativeDecoding(
    variant: GemmaModelVariant,
    backend: GemmaRuntimeBackend,
    applyFlag: (Boolean?) -> Unit = { ExperimentalFlags.enableSpeculativeDecoding = it },
): Boolean? {
    val requested = if (variant == GemmaModelVariant.E4B_IT && backend == GemmaRuntimeBackend.GPU) {
        true
    } else {
        null
    }
    // LiteRT-LM reads this process-wide flag when initializing an engine. Explicitly reset it
    // when switching models/backends; the runtime's inference mutex serializes these calls.
    applyFlag(requested)
    return requested
}
