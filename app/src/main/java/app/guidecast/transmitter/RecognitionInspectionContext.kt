package app.guidecast.transmitter

import app.guidecast.core.translation.RecognizedUtterance
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Request-scoped, in-memory inspection seam for a caller-owned public-corpus test.
 * Normal recording/broadcast calls never install it. It has no persistence, network output,
 * global listener or effect on recognition decisions; user speech is not added to diagnostics.
 */
internal class RecognitionInspectionContext(
    val onProviderResult: (attemptId: Long, backend: String, utterance: RecognizedUtterance, accepted: Boolean) -> Unit,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RecognitionInspectionContext>
}
