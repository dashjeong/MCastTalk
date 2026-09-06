package app.guidecast.transmitter

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

/** Only Android's explicit policy signal triggers recovery; quiet speech never does. */
internal suspend fun observeSustainedCaptureSilencing(
    states: Flow<Boolean>,
    settleMillis: Long = 750L,
    onSilenced: () -> Unit,
) {
    require(settleMillis > 0)
    states.collectLatest { silenced ->
        if (silenced) {
            delay(settleMillis)
            onSilenced()
        }
    }
}
