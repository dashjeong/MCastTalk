package app.guidecast.transmitter

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import app.guidecast.core.translation.ContextualTextTranslationEngine
import app.guidecast.core.translation.FairQueuedTranslationEngineProvider
import app.guidecast.core.translation.FairTranslationQueueConfig
import app.guidecast.core.translation.FairTranslationQueueObserver
import app.guidecast.core.translation.NativeColdLoadTicket
import app.guidecast.core.translation.NativeColdLoadTicketContext
import app.guidecast.core.translation.SelectiveRefinementOutcome
import app.guidecast.core.translation.SelectiveRefinementTranslationEngineProvider
import app.guidecast.core.translation.TextTranslationEngine
import app.guidecast.core.translation.TranslationEngineProvider
import app.guidecast.core.translation.TranslationStyle
import app.guidecast.core.translation.TranslationStyleContext
import app.guidecast.core.translation.currentNativeColdLoadTicket
import app.guidecast.provider.gemma.translation.GemmaBroadcastCapability
import app.guidecast.provider.gemma.translation.GemmaModelReadiness
import app.guidecast.provider.gemma.translation.GemmaModelVariant
import app.guidecast.provider.mlkit.translation.MlKitTranslationProvider
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real ML Kit/Gemma + selective/fair/native admission + production recovery helper integration.
 * This is not BroadcastService/UI end-to-end, audio recognition or a translation quality gate.
 * A follow-up 800 ms timeout is retained as a timeout, even when recovery/readmission is proven.
 * Native observation adds no lease, wait, retry, deadline extension or forced prepared state.
 * Requires the same dedicated device and hash-pinned fixture as ContextualSelectiveReviewDeviceTest.
 */
class ContextualReviewRecoveryDeviceTest {
    @Test fun timedOutReviewCanPrepareOnceAndReadmitANewRequest(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        require(args.getString("dedicatedCorpusDevice") == "true") { "DEDICATED_QUALITY_DEVICE_REQUIRED" }
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as GuideCastApplication
        val base = File(checkNotNull(app.getExternalFilesDir(null)), "benchmark").canonicalFile
        val name = args.getString("contextualQualityManifest") ?: "contextual-quality-round12.json"
        require(name.matches(Regex("[A-Za-z0-9._-]{1,100}\\.json")))
        val file = File(base, name).canonicalFile
        check(file.parentFile == base && file.isFile && file.length() in 1L..64_000L)
        val bytes = file.readBytes()
        check(sha256(bytes) == FIXTURE_SHA256) { "PUBLIC_FIXTURE_SHA256_MISMATCH" }
        val fixture = JSONObject(bytes.toString(Charsets.UTF_8))
        check(fixture.getInt("schemaVersion") == 1 && fixture.getString("sourceLanguage") == "ko" &&
            fixture.getString("targetLanguage") == "en" && fixture.getJSONArray("cases").length() == 10)
        val capability = GemmaBroadcastCapability.detect(app)
        val trace = NativeTrace()
        val rows = JSONArray()
        val result = JSONObject().put("state", "PREPARING").put("schemaVersion", 1)
            .put("scope", "REAL_PROVIDER_RECOVERY_HELPER_INTEGRATION_NOT_BROADCAST_SERVICE_E2E")
            .put("fixtureSha256", FIXTURE_SHA256).put("fixtureId", fixture.getString("fixtureId"))
            .put("reviewBudgetMs", 800).put("draftBudgetMs", 3_000).put("gemmaProviderDeadlineMs", 10_000)
            .put("translationQualityVerified", false).put("audioRecognitionExecuted", false)
            .put("externalAiApiUsed", false).put("cases", rows)
            .put("eventColumns", JSONArray(listOf("elapsedMs", "callId", "eventCode")))
            .put("eventCodes", JSONObject().put("1", "NATIVE_SUBMITTED").put("2", "NATIVE_TERMINAL")
                .put("3", "RECOVERY_CLEANUP_ENTERED").put("4", "RECOVERY_WARMUP_ENTERED")
                .put("5", "RECOVERY_RESULT").put("6", "REVIEW_DELEGATE_ENTERED"))
            .put("nativeObservationDoesNotAcquireAnAdditionalLease", true)
        val output = File(base, "contextual-recovery-results-${System.currentTimeMillis()}.json")
        fun save() { result.put("events", trace.snapshot()); output.writeText(result.toString(2)) }
        val gemma = app.gemmaTranslationProvider
        val mlKit = MlKitTranslationProvider(app, "ko", requireWifiForModels = false)
        val job = SupervisorJob()
        val scope = CoroutineScope(Dispatchers.Default + job)
        val current = AtomicInteger()
        val sessionActive = AtomicBoolean(true)
        val calls = ConcurrentHashMap<Int, Call>()
        val cleanupCalls = AtomicInteger()
        val warmupCalls = AtomicInteger()
        val cleanupResets = AtomicInteger()
        val recoveryDone = CompletableDeferred<SelectiveRefinementRecoveryResult>()
        var recovery: SelectiveRefinementRecovery? = null
        var fair: FairQueuedTranslationEngineProvider? = null
        val started = SystemClock.elapsedRealtime()
        base.mkdirs()
        save()
        try {
            app.withTranslationBackendUse {
                withTimeout(600_000L) {
                    check(capability.supported) { "BROADCAST_GEMMA_MEMORY_CAPABILITY_REQUIRED" }
                    val serialize = shouldSerializeNativeColdLoads(capability.constrainedMemoryMode, 1,
                        capability.loadPermittedNow)
                    gemma.selectModel(GemmaModelVariant.STANDARD)
                    val manager = gemma.modelManager
                    val expected = fixture.getJSONObject("model")
                    check(expected.getString("sha256") == GemmaModelVariant.STANDARD.sha256 &&
                        expected.getLong("bytes") == GemmaModelVariant.STANDARD.sizeBytes &&
                        expected.getString("revision") == GemmaModelVariant.STANDARD.revision)
                    manager.refresh()
                    check(manager.status.value.readiness in setOf(GemmaModelReadiness.VERIFIED, GemmaModelReadiness.READY))
                    // A normal warmup is insufficient to turn VERIFIED into production READY.
                    // Re-run the provider's real semantic self-test, then use its verified marker.
                    app.withProcessNativeColdLoadLease(ProcessNativeColdLoadKeys.GEMMA_MODEL, serialize) {
                        trace.observe(0) { gemma.selfTest() }
                    }
                    trace.awaitTerminal(0)
                    manager.markRuntimeReady()
                    check(gemma.hasActivePreparedWorker() && !gemma.isAutomaticRetryBlocked())
                    result.put("initialSelfTestSucceeded", true).put("initialReadiness", manager.status.value.readiness.name)
                    withTimeout(180_000L) {
                        mlKit.prepareModels(setOf("en"), "ko", isNativeOwnerCurrent = { true },
                            reconcileNativeTargets = true, initializeWithNativeAdmission = { target, initialize ->
                                app.withProcessNativeColdLoadLease(ProcessNativeColdLoadKeys.mlKitTranslation(target), serialize) {
                                    initialize()
                                }
                            })
                        app.withProcessNativeColdLoadLease(ProcessNativeColdLoadKeys.mlKitTranslation("en"), serialize) {
                            mlKit.warm(listOf("en"))
                        }
                    }
                    check(mlKit.hasActivePreparedWorker("en"))
                    val gate = NativeFirstUseGate(
                        maxParallelInitializations = translationSupportPreparationParallelism(capability.constrainedMemoryMode, 1),
                        maxParallelReloads = translationSupportReloadParallelism(capability.constrainedMemoryMode, 1),
                        beforeFirstUse = { key, _ ->
                            when {
                                key.startsWith("gemma:") && !gemma.hasActivePreparedWorker() ->
                                    app.acquireProcessNativeColdLoadLease(ProcessNativeColdLoadKeys.GEMMA_MODEL, serialize)
                                key.startsWith("mlkit:") && !mlKit.hasActivePreparedWorker("en") ->
                                    app.acquireProcessNativeColdLoadLease(ProcessNativeColdLoadKeys.mlKitTranslation("en"), serialize)
                                else -> null
                            }
                        },
                        isInitializationCurrent = { key -> if (key.startsWith("gemma:"))
                            gemma.hasActivePreparedWorker() else mlKit.hasActivePreparedWorker("en") },
                    )
                    fun recoveryAllowed() = sessionActive.get() && manager.status.value.readiness == GemmaModelReadiness.READY &&
                        !gemma.isAutomaticRetryBlocked()
                    val coordinator = SelectiveRefinementRecovery(scope, ::recoveryAllowed,
                        gemma::hasActivePreparedWorker, recover = {
                            check(recoveryAllowed())
                            gate.initialize("gemma:en") {
                                check(recoveryAllowed())
                                warmupCalls.incrementAndGet()
                                trace.event(RECOVERY_CALL, 4)
                                trace.observe(RECOVERY_CALL) {
                                    gemma.warmup("en", "ko", if (capability.constrainedMemoryMode) 90_000L else 30_000L)
                                }
                            }
                        }, onResult = {
                            trace.event(RECOVERY_CALL, 5)
                            recoveryDone.complete(it)
                        }, cleanupLatestFailure = {
                            cleanupCalls.incrementAndGet()
                            trace.event(RECOVERY_CALL, 3)
                            app.resetGemmaAfterFailureIf(::recoveryAllowed).also { if (it) cleanupResets.incrementAndGet() }
                        })
                    recovery = coordinator
                    val review = TranslationEngineProvider { target ->
                        val delegate = gemma.engineFor(target) as ContextualTextTranslationEngine
                        object : ContextualTextTranslationEngine {
                            override suspend fun translateWithContext(text: String, contextBefore: String?,
                                sourceLanguageTag: String, targetLanguageTag: String): String {
                                val id = current.get()
                                calls.getValue(id).reviewCalls.incrementAndGet()
                                trace.event(id, 6)
                                return trace.observe(id) {
                                    delegate.translateWithContext(text, contextBefore, sourceLanguageTag, targetLanguageTag)
                                }
                            }
                        }
                    }.withNativeFirstUseGate(gate, "gemma")
                    val queue = FairQueuedTranslationEngineProvider(review, scope,
                        FairTranslationQueueConfig(maxPendingPerLanguage = 2,
                            queueWaitTimeoutMillis = 30_000L, inferenceTimeoutMillis = 15_000L),
                        FairTranslationQueueObserver { calls.getValue(current.get()).queueTerminal.complete(Unit) })
                    fair = queue
                    val draft = TranslationEngineProvider { target ->
                        val delegate = mlKit.withNativeFirstUseGate(gate, "mlkit").engineFor(target)
                        TextTranslationEngine { text, source, language ->
                            val call = calls.getValue(current.get())
                            call.draftCalls.incrementAndGet()
                            delegate.translate(text, source, language).also { call.draft = it }
                        }
                    }
                    val selective = SelectiveRefinementTranslationEngineProvider(draft,
                        TranslationEngineProvider { target ->
                            calls.getValue(current.get()).queued = true
                            queue.engineFor(target)
                        },
                        reviewerAvailable = { it == "en" && gemma.hasActivePreparedWorker() && recoveryAllowed() },
                        onDiagnostic = {
                            calls.getValue(current.get()).outcome = it.outcome
                            calls.getValue(current.get()).preparedAtDiagnostic = gemma.hasActivePreparedWorker()
                            coordinator.onDiagnostic(it.outcome)
                        })
                    suspend fun request(fixtureIndex: Int): Call {
                        val id = current.incrementAndGet()
                        val call = Call()
                        calls[id] = call
                        val item = fixture.getJSONArray("cases").getJSONObject(fixtureIndex)
                        val began = SystemClock.elapsedRealtime()
                        val translated = withContext(TranslationStyleContext(TranslationStyle.AUTO)) {
                            (selective.engineFor("en") as ContextualTextTranslationEngine).translateWithContext(
                                item.getString("originalText"), item.getString("contextBefore"), "ko", "en")
                        }
                        if (call.queued) withTimeout(20_000L) { call.queueTerminal.await() }
                        rows.put(JSONObject().put("callId", id).put("fixtureCaseId", item.getString("id"))
                            .put("elapsedMs", SystemClock.elapsedRealtime() - began).put("outcome", call.outcome?.name)
                            .put("draftCalls", call.draftCalls.get()).put("reviewDelegateCalls", call.reviewCalls.get())
                            .put("preparedAtDiagnostic", call.preparedAtDiagnostic)
                            .put("finalEqualsDraft", translated == call.draft).put("preparedAfter", gemma.hasActivePreparedWorker())
                            .put("finalSha256", sha256(translated.toByteArray())).put("draftSha256", sha256(call.draft.toByteArray())))
                        call.finalEqualsDraft = translated == call.draft
                        save()
                        assertEquals("Draft must execute once per input", 1, call.draftCalls.get())
                        return call
                    }
                    result.put("state", "RUNNING")
                    check(gemma.hasActivePreparedWorker() && recoveryAllowed()) { "PREPARED_REVIEWER_REQUIRED" }
                    val first = request(0)
                    assertEquals("This regression requires a real default-budget cancellation", SelectiveRefinementOutcome.REVIEW_TIMED_OUT, first.outcome)
                    assertTrue(first.finalEqualsDraft)
                    assertEquals(1, first.reviewCalls.get())
                    // If FairQueue propagates invalidation after the timeout diagnostic, a real
                    // next input (not an invented diagnostic) triggers the helper's unavailable path.
                    if (cleanupCalls.get() == 0 && !gemma.hasActivePreparedWorker()) request(1)
                    val recovered = withTimeout(150_000L) { recoveryDone.await() }
                    assertEquals(SelectiveRefinementRecoveryResult.RECOVERED, recovered)
                    trace.awaitTerminal(1)
                    trace.awaitTerminal(RECOVERY_CALL)
                    assertEquals(1, trace.submissions(1))
                    assertEquals(1, cleanupCalls.get())
                    assertEquals(1, warmupCalls.get())
                    assertTrue(gemma.hasActivePreparedWorker())
                    result.put("preparedBeforeFollowup", true).put("recoveryResult", recovered.name)
                    val followupId = current.get() + 1
                    val followup = request(1)
                    assertEquals("A real new request must re-enter the reviewer", 1, followup.reviewCalls.get())
                    trace.awaitTerminal(followupId)
                    assertEquals(1, trace.submissions(followupId))
                    assertEquals("No second automatic recovery is permitted", 1, cleanupCalls.get())
                    assertEquals(1, warmupCalls.get())
                    result.put("followupOutcome", followup.outcome?.name)
                        .put("followupTranslationQualityPassed", false)
                        .put("state", "RECOVERY_AND_READMISSION_OBSERVED_QUALITY_NOT_ASSERTED")
                }
            }
        } catch (error: Throwable) {
            result.put("failedStage", result.optString("state")).put("state", "FAILED")
                .put("failureType", error.javaClass.simpleName)
            throw error
        } finally {
            sessionActive.set(false)
            recovery?.close()
            withContext(NonCancellable + Dispatchers.IO) {
                fair?.close()
                job.cancelAndJoin()
                val gemmaCleanup = runCatching { withTimeout(150_000L) { gemma.resetEngineSafely() } }
                val draftCleanup = runCatching { mlKit.close() }
                result.put("cleanupCalls", cleanupCalls.get()).put("cleanupResets", cleanupResets.get())
                    .put("recoveryWarmupCalls", warmupCalls.get()).put("executedRequests", calls.size)
                    .put("reviewerCleanupSucceeded", gemmaCleanup.isSuccess).put("draftCleanupSucceeded", draftCleanup.isSuccess)
                    .put("elapsedMs", SystemClock.elapsedRealtime() - started)
                if (gemmaCleanup.isFailure || draftCleanup.isFailure) result.put("state", "CLEANUP_FAILED")
                save()
            }
        }
        assertEquals("Inspect the recovery receipt; this assertion does not assess translation quality",
            "RECOVERY_AND_READMISSION_OBSERVED_QUALITY_NOT_ASSERTED", result.getString("state"))
    }

    private class Call {
        val draftCalls = AtomicInteger()
        val reviewCalls = AtomicInteger()
        val queueTerminal = CompletableDeferred<Unit>()
        @Volatile var draft = ""
        @Volatile var outcome: SelectiveRefinementOutcome? = null
        @Volatile var preparedAtDiagnostic = false
        @Volatile var queued = false
        var finalEqualsDraft = false
    }

    /** Observer only: forwards an existing ticket and adds no admission or native waiting. */
    private class NativeTrace {
        private val started = SystemClock.elapsedRealtime()
        private val events = mutableListOf<List<Long>>()
        private val submitted = ConcurrentHashMap<Int, AtomicInteger>()
        private val terminals = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()
        fun event(id: Int, code: Int) = synchronized(events) {
            events.add(listOf(SystemClock.elapsedRealtime() - started, id.toLong(), code.toLong())); Unit
        }
        fun snapshot() = synchronized(events) { JSONArray(events.map { JSONArray(it) }) }
        fun submissions(id: Int) = submitted[id]?.get() ?: 0
        suspend fun awaitTerminal(id: Int) = withTimeout(150_000L) {
            checkNotNull(terminals[id]) { "NATIVE_CALL_NOT_OBSERVED" }.await()
        }
        suspend fun <T> observe(id: Int, block: suspend () -> T): T {
            val delegate = currentNativeColdLoadTicket()
            val terminal = CompletableDeferred<Unit>()
            check(terminals.putIfAbsent(id, terminal) == null) { "DUPLICATE_NATIVE_CALL_ID" }
            val ticket = object : NativeColdLoadTicket {
                override fun transferToNative() {
                    delegate?.transferToNative()
                    submitted.computeIfAbsent(id) { AtomicInteger() }.incrementAndGet()
                    event(id, 1)
                }
                override fun completeNative() { delegate?.completeNative(); event(id, 2); terminal.complete(Unit) }
                override fun close() { delegate?.close() }
            }
            return withContext(NativeColdLoadTicketContext(ticket)) { block() }
        }
    }

    private companion object {
        const val RECOVERY_CALL = 100
        const val FIXTURE_SHA256 = "0350057708c2043bf954a72d827fb320d7a476fbe2098ec17d398077a707a708"
        fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
