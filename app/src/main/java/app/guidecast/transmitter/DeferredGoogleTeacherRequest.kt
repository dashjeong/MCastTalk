package app.guidecast.transmitter

import app.guidecast.core.translation.requireProtectedTranslationMeaning
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** This request's current_text binds the reference. It is not a match to any native output row. */
internal suspend fun requestDeferredGoogleTeacher(settings: TranslationApiSettings,
    permit: DeferredGoogleTeacherPermit, source: DeferredTeacherSource,
    grant: DeferredTeacherGrant, stillAllowed: () -> Boolean,
    http: BoundedCloudHttps = BoundedCloudHttps(),
    usageObserved: (attempted: Boolean, raw: GeminiBatchUsage, knownUsd: String, heldUsd: String) -> Unit = { _, _, _, _ -> },
): DeferredTeacherBatch {
    return requestDeferredGoogleTeacherWithAccess(permit, source, grant, stillAllowed,
        authorized = { settings.deferredTeacherAuthorized(permit) }, keyForPurpose = { settings.deferredTeacherKey(permit) },
        budget = TranslationDispatchBudget(settings.onlineRequestBudget), http = http, usageObserved = usageObserved)
}

/** Narrow test seam for purpose authorization and a shared ledger; production always uses Settings above. */
internal suspend fun requestDeferredGoogleTeacherWithAccess(permit: DeferredGoogleTeacherPermit,
    source: DeferredTeacherSource, grant: DeferredTeacherGrant, stillAllowed: () -> Boolean,
    authorized: () -> Boolean, keyForPurpose: () -> String?, budget: TranslationDispatchBudget,
    http: BoundedCloudHttps,
    usageObserved: (attempted: Boolean, raw: GeminiBatchUsage, knownUsd: String, heldUsd: String) -> Unit = { _, _, _, _ -> },
): DeferredTeacherBatch {
    fun allowed() = stillAllowed() && authorized() &&
        grant.generation == permit.generation && grant.targets == permit.targets
    check(allowed())
    require(source.originalCompletionObserved && source.source in setOf("ko", "ko-KR") &&
        reviewedRelayExampleTextValid(source.original))
    val options = permit.textOptions
    val request = JSONObject(GeminiTranslationBatch.request(options, options.tone, source.original,
        null, source.source, permit.targets)).apply {
        getJSONObject("generationConfig").put("maxOutputTokens", grant.maximumOutputTokens)
    }.toString()
    val ticket = budget.reserve(options, request, grant.maximumOutputTokens, batch = true)
        ?: throw TranslationBudgetBlocked()
    val requestId = UUID.randomUUID().toString()
    val attempted = AtomicBoolean(false)
    var raw: String? = null
    val started = System.nanoTime()
    try {
        check(allowed())
        val key = requireNotNull(keyForPurpose())
        check(allowed())
        raw = http.postObserved(options.endpoint, CloudReviewProvider.GOOGLE, key, request,
            8_000, ::allowed, observeTransport = { event ->
                if (event.phase == CloudTransportPhase.ATTEMPT) attempted.set(true)
            })
        check(allowed())
        val translations = GeminiTranslationBatch.result(options, requireNotNull(raw), permit.targets)
        translations.forEach { (target, value) ->
            require(reviewedRelayTranslationTextValid(value, target))
            requireProtectedTranslationMeaning(source.original, value, source.source, target)
        }
        check(allowed())
        return DeferredTeacherBatch(requestId, source.original, source.source, permit.targets, translations)
    } finally {
        // Unknown post-attempt usage is held, not zeroed or retried. Credentials/raw text are not logged.
        budget.settle(ticket, raw, attempted.get())
        val observed = geminiBatchUsage(raw)
        val ledger = budget.snapshot()
        runCatching { usageObserved(attempted.get(), observed, ledger.known.toPlainString(), ledger.held.toPlainString()) }
        RuntimeDiagnosticLog.durableRecord("deferred_teacher_usage", "request_id=$requestId " +
            "provider=GEMINI target_count=${permit.targets.size} attempted=${attempted.get()} " +
            "elapsed_ms=${(System.nanoTime() - started) / 1_000_000} ${geminiBatchUsage(raw).diagnostic()}")
    }
}
