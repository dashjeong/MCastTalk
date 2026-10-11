package app.guidecast.transmitter

/** Sensitive onset with debounce; an isolated probability spike does not open an activity. */
internal class SpeechProbabilityGate {
    private var onset = 0
    private var quiet = 0
    private var open = false

    fun accept(probability: Float, signalRms: Float = 1f): Boolean {
        require(probability.isFinite() && probability in 0f..1f)
        require(signalRms.isFinite() && signalRms >= 0f)
        if (open) {
            if (probability >= 0.10f) { quiet = 0; return true }
            if (++quiet >= 22) { open = false; onset = 0 }
            return false
        }
        val confidentOnset = probability >= 0.5f || (probability >= 0.25f && signalRms >= 0.02f)
        onset = if (confidentOnset) onset + 1 else 0
        if (onset < 2) return false
        open = true; quiet = 0; onset = 0
        return true
    }
}
