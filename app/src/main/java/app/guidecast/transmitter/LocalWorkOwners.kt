package app.guidecast.transmitter

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job

/** A destroyed screen may still be saving a note. Its completion cannot release a newer owner. */
internal class LocalWorkOwners {
    private val owners = mutableSetOf<Any>()
    private val mutableActive = MutableStateFlow(false)
    val active = mutableActive.asStateFlow()

    @Synchronized fun tryAcquire(owner: Any): Boolean {
        if (owners.any { it !== owner }) return false
        owners += owner
        mutableActive.value = true
        return true
    }
    @Synchronized fun setActive(owner: Any, active: Boolean) {
        if (active) owners += owner else owners -= owner
        mutableActive.value = owners.isNotEmpty()
    }
    @Synchronized fun hasOther(owner: Any): Boolean = owners.any { it !== owner }
}

/** Cancellation requests completion; resources still belong to child jobs until they finish. */
internal fun retireLocalWork(job: Job?, onComplete: () -> Unit) {
    if (job == null) onComplete()
    else {
        job.cancel()
        job.invokeOnCompletion { onComplete() }
    }
}
