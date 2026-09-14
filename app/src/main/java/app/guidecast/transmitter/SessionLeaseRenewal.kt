package app.guidecast.transmitter

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A cancelled session must never renew another session's resource lease. */
internal fun CoroutineScope.renewSessionLease(
    intervalMillis: Long,
    renewIfCurrent: () -> Boolean,
): Job = launch {
    require(intervalMillis > 0)
    while (true) {
        delay(intervalMillis)
        if (!renewIfCurrent()) return@launch
    }
}
