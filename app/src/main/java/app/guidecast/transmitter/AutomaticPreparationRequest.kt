package app.guidecast.transmitter

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine

/** Immutable selection key coalesces UI changes without reacting to model-progress updates. */
internal data class AutomaticPreparationRequest(
    val source: String, val targets: Set<String>, val voices: Map<String, String>, val enabled: Boolean,
)

/** Opening the service home or a local-note/file workspace must not download broadcast models. */
internal fun Flow<AutomaticPreparationRequest>.inTranslationWorkspace(active: Flow<Boolean>) =
    combine(active) { request, visible -> request.copy(enabled = request.enabled && visible) }

internal suspend fun Flow<AutomaticPreparationRequest>.prepareAutomatically(
    canPrepare: () -> Boolean,
    prepare: suspend (AutomaticPreparationRequest) -> Unit,
) {
    distinctUntilChanged().collectLatest { request ->
        if (!request.enabled || request.targets.isEmpty()) return@collectLatest
        delay(750L)
        while (!canPrepare()) delay(250L)
        prepare(request)
        // No timer retry: the next attempt requires a changed selection or explicit user retry.
    }
}
