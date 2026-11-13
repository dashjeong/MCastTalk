package app.guidecast.core.audio

data class EnhancementTimingEvidence(
    val modelSha256: String,
    val deviceKey: String,
    val inputSamples: Int,
    val futureSamples: Int,
    val measuredP95Ms: Double,
    val qualityVerified: Boolean,
    val sampleRateHz: Int = 16_000,
)

enum class EnhancementBypassReason {
    UNMEASURED, INVALID_EVIDENCE, EVIDENCE_MISMATCH, ALGORITHMIC_DELAY,
    COMPUTE_BUDGET, QUALITY_UNVERIFIED, THERMAL_OR_POWER,
}

/** null permits further live-integration checks; any reason keeps the existing noise path. */
object RealtimeEnhancementAdmission {
    fun decide(
        evidence: EnhancementTimingEvidence?, modelSha256: String, deviceKey: String,
        powerSave: Boolean = false, thermalStatus: Int = 0,
    ): EnhancementBypassReason? {
        if (powerSave || thermalStatus >= 3) return EnhancementBypassReason.THERMAL_OR_POWER
        if (evidence == null) return EnhancementBypassReason.UNMEASURED
        if (evidence.modelSha256 != modelSha256 || evidence.deviceKey != deviceKey)
            return EnhancementBypassReason.EVIDENCE_MISMATCH
        if (!modelSha256.matches(Regex("[a-f0-9]{64}")) || deviceKey.isBlank() ||
            evidence.sampleRateHz != 16_000 || evidence.inputSamples <= 0 ||
            evidence.futureSamples < 0 || !evidence.measuredP95Ms.isFinite() || evidence.measuredP95Ms < 0)
            return EnhancementBypassReason.INVALID_EVIDENCE
        val inputMs = evidence.inputSamples.toDouble() * 1000 / evidence.sampleRateHz
        val bufferedMs = inputMs + evidence.futureSamples.toDouble() * 1000 / evidence.sampleRateHz
        if (bufferedMs >= 160) return EnhancementBypassReason.ALGORITHMIC_DELAY
        // Reserve 40% of the frame duration for capture, STT, translation and scheduler jitter.
        if (evidence.measuredP95Ms > inputMs * 0.6 || bufferedMs + evidence.measuredP95Ms >= 160)
            return EnhancementBypassReason.COMPUTE_BUDGET
        if (!evidence.qualityVerified) return EnhancementBypassReason.QUALITY_UNVERIFIED
        return null
    }
}
