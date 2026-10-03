package app.guidecast.core.translation

/** Experimental controls, deliberately separate from product retrieval and model weights. */
object RagPocPlan {
    enum class Arm { NONE, RELEVANT, IRRELEVANT }
    // All six permutations balance first-order carryover, not just which arm starts first.
    private val orders = listOf(
        listOf(Arm.NONE, Arm.RELEVANT, Arm.IRRELEVANT), listOf(Arm.NONE, Arm.IRRELEVANT, Arm.RELEVANT),
        listOf(Arm.RELEVANT, Arm.NONE, Arm.IRRELEVANT), listOf(Arm.RELEVANT, Arm.IRRELEVANT, Arm.NONE),
        listOf(Arm.IRRELEVANT, Arm.NONE, Arm.RELEVANT), listOf(Arm.IRRELEVANT, Arm.RELEVANT, Arm.NONE),
    )
    fun order(caseIndex: Int, repeat: Int): List<Arm> {
        require(caseIndex >= 0 && repeat >= 0)
        return orders[((caseIndex.toLong() + repeat) % orders.size).toInt()]
    }
    fun validateReferences(relevant: String, irrelevant: String, budget: Int = 600) {
        require(budget in 1..1200)
        require(relevant.isNotBlank() && irrelevant.isNotBlank())
        require(relevant != irrelevant && relevant.length == irrelevant.length && relevant.length <= budget)
    }
    val requiredMeasurements = setOf("retrievalMs", "prefillMs", "decodeMs", "listenerFirstAudioMs",
        "pssKb", "thermalStatus", "queueDepth", "meaningErrors", "termErrors", "omissions", "additions")
    fun missingMeasurements(measurements: Map<String, Double?>): Set<String> = requiredMeasurements.filterTo(linkedSetOf()) {
        val value = measurements[it]
        value == null || !value.isFinite() || value < 0
    }
}
