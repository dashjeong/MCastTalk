package app.guidecast.core.translation

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** An explicit local synthesis request, never an inferred claim about a speaker's emotion. */
data class SpeechExpressionProfile(val rate: Float = 0.95f, val pitch: Float = 1.0f) {
    init {
        require(rate.isFinite() && rate in 0.9f..1.1f)
        require(pitch.isFinite() && pitch in 0.9f..1.1f)
    }
    companion object { val BASELINE = SpeechExpressionProfile() }
}

/** Scoped to one collection: no shared mutable setting can leak into another language/job. */
class SpeechExpressionContext(val profile: SpeechExpressionProfile) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SpeechExpressionContext>
}
