package app.guidecast.transmitter

import kotlinx.coroutines.CompletableDeferred

/** Local input EOF and provider turn completion are separate observations, not an ASR final guarantee. */
internal class NativeInputDrainState {
    private var requestedValue = false
    private var eosSentValue = false
    private var completedValue = false
    private var activeInputRevision = 0L
    private var completedThroughRevision = 0L
    private var turnStartedAtRevision = 0L
    private var turnInProgress = false
    private var sourceInProgress = false
    private val ended = CompletableDeferred<Boolean>()
    val requested: Boolean @Synchronized get() = requestedValue
    val completed: Boolean @Synchronized get() = completedValue
    @Synchronized fun request(): Boolean {
        if (requestedValue || ended.isCompleted) return false
        requestedValue = true
        return true
    }
    @Synchronized fun audioSent(active: Boolean) { if (active) activeInputRevision++ }
    @Synchronized fun eosSent() {
        check(requestedValue)
        eosSentValue = true
    }
    @Synchronized fun completeQuietInput(): Boolean {
        // Energy is only a conservative pending-input guard, not speech recognition or an ACK.
        if (eosSentValue && !turnInProgress && !sourceInProgress && activeInputRevision <= completedThroughRevision)
            completedValue = true
        return completedValue
    }
    @Synchronized fun observeResponse(hasOutput: Boolean, finished: Boolean, interrupted: Boolean, hasSource: Boolean = false): Boolean {
        if (hasSource) sourceInProgress = true
        if (hasOutput && !turnInProgress) {
            turnInProgress = true
            turnStartedAtRevision = activeInputRevision
        }
        if (finished || interrupted) {
            if (finished && turnInProgress) completedThroughRevision = turnStartedAtRevision
            turnInProgress = false
            sourceInProgress = false
        }
        if (finished && eosSentValue && activeInputRevision <= completedThroughRevision) completedValue = true
        return completedValue
    }
    @Synchronized fun sessionEnded(completedNormally: Boolean) { ended.complete(completedNormally && completedValue) }
    suspend fun awaitEnded(): Boolean = ended.await()
}
