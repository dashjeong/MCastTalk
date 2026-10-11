package app.guidecast.transmitter

import kotlinx.coroutines.CompletableDeferred

/** Stable result retained even after the input worker releases its resource owner. */
internal class InputCompletionReceipt {
    private val result = CompletableDeferred<Boolean>()
    fun complete(successful: Boolean) { result.complete(successful) }
    suspend fun await(): Boolean = result.await()
}

internal suspend fun awaitInputCompletions(receipts: List<InputCompletionReceipt>): Boolean {
    var successful = true
    for (receipt in receipts) {
        val completed = receipt.await()
        successful = completed && successful
    }
    return successful
}
