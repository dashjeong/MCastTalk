package app.guidecast.transmitter

import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class RestrictedGeminiTrialTest {
    @get:Rule val temporary = TemporaryFolder()
    private val setup get() = RestrictedTrialFixture.setup()
    private fun trial(now: () -> Long, setup: String = this.setup, pcm: ByteArray = RestrictedTrialFixture.pcm,
        journal: File = temporary.newFile(), approved: Boolean = true) = RestrictedGeminiTrial(
        if (approved) RestrictedTrialApproval.read(RestrictedTrialFixture.approval(setup, pcm, now = now()), now()) else null,
        TrialPermitJournal(journal), setup, pcm, now, { now() * 1_000_000L })
    private suspend fun attempt(t: RestrictedGeminiTrial, wire: RestrictedTrialFixture.Wire,
        setup: String = this.setup, response: String? = RestrictedTrialFixture.output()): Throwable? {
        wire.incoming.send("{\"setupComplete\":{}}")
        if (response != null) wire.incoming.send(response)
        return runCatching { RestrictedTrialFixture.drive(t, wire, setup, 1) }.exceptionOrNull()
    }
    @Test fun missingApprovalFailsBeforeDelegateConnect() = runTest {
        val w = RestrictedTrialFixture.Wire()
        assertEquals("EXPLICIT_APPROVAL_MISSING", (attempt(trial({ testScheduler.currentTime }, approved = false), w) as TrialBlocked).reasonCode)
        assertEquals(0, w.opens)
    }
    @Test fun everyApprovalFieldIsRequiredAndBudgetIsNotAssumed() {
        val valid = RestrictedTrialFixture.approval(setup)
        assertNotNull(RestrictedTrialApproval.read(valid, 0))
        for (key in valid.keys) assertNull(key, RestrictedTrialApproval.read(valid - key, 0))
        for ((k, v) in mapOf("approvedBudgetUsd" to "1000", "approvedModel" to "other", "approvedConnections" to "3",
            "approvalExpiresAtMillis" to "0", "reviewedSetupTextTokens" to "1025"))
            assertNull(k, RestrictedTrialApproval.read(valid + (k to v), 0))
    }
    @Test fun exactTwoSessionsAndThreeResponsesThenNoThirdAttempt() = runTest {
        val receipt = RestrictedTrialFixture.happyTwoSessionRun(temporary.newFile()) { testScheduler.currentTime }
        assertEquals(2, receipt.getInt("connection_attempts")); assertEquals(9600L, receipt.getLong("input_bytes"))
        assertEquals(14400L, receipt.getLong("output_bytes")); assertEquals("0.0045", java.math.BigDecimal(receipt.getString("estimated_usd")).stripTrailingZeros().toPlainString())
    }
    @Test fun usedApprovalCannotRestartOrResumeInNewController() = runTest {
        val journal = temporary.newFile(); val t = trial({ testScheduler.currentTime }, journal = journal)
        val w = RestrictedTrialFixture.Wire(); assertNull(attempt(t, w))
        val recreated = trial({ testScheduler.currentTime }, journal = journal)
        assertEquals("APPROVAL_ALREADY_CONSUMED", (attempt(recreated, w) as TrialBlocked).reasonCode)
        assertEquals(1, w.opens)
    }
    @Test fun malformedPersistentPermitFailsBeforeConnection() = runTest {
        val journal = temporary.newFile().apply { writeText("partial-write") }
        val t = trial({ testScheduler.currentTime }, journal = journal); val w = RestrictedTrialFixture.Wire()
        assertEquals("PERMIT_JOURNAL_INVALID", (attempt(t, w) as TrialBlocked).reasonCode)
        assertEquals(0, w.opens)
    }
    @Test fun missingRuntimeConsentNeverConnects() = runTest {
        val t = trial({ testScheduler.currentTime }); val w = RestrictedTrialFixture.Wire()
        val error = runCatching { t.wrap(w).connect("API0_OPAQUE_FIXTURE", { false }) {} }.exceptionOrNull()
        assertEquals("AUTHORIZATION_REVOKED", (error as TrialBlocked).reasonCode); assertEquals(0, w.opens)
    }
    @Test fun revocationWhileReceiveIsBlockedClosesAndCannotRetry() = runTest {
        val t = trial({ testScheduler.currentTime }); val w = RestrictedTrialFixture.Wire()
        var allowed = true
        launch { delay(35); allowed = false }
        val error = runCatching { t.wrap(w).connect("API0_OPAQUE_FIXTURE", { allowed }) { it.receive() } }.exceptionOrNull()
        assertEquals("AUTHORIZATION_REVOKED", (error as TrialBlocked).reasonCode)
        assertEquals(50L, testScheduler.currentTime); assertEquals(1, w.closes)
        assertTrue(attempt(t, w) is TrialBlocked); assertEquals(1, w.opens)
    }
    @Test fun simultaneousAttemptNeverOpensSecondDelegateAndInvalidatesRun() = runTest {
        val t = trial({ testScheduler.currentTime }); val w = RestrictedTrialFixture.Wire()
        supervisorScope {
            val first = async { runCatching { t.wrap(w).connect("API0_OPAQUE_FIXTURE", { true }) { it.receive() } }.exceptionOrNull() }
            runCurrent()
            val second = runCatching { t.wrap(w).connect("API0_OPAQUE_FIXTURE", { true }) {} }.exceptionOrNull()
            assertEquals("CONCURRENT_OR_FAILED_ATTEMPT", (second as TrialBlocked).reasonCode)
            assertTrue(first.await() is TrialBlocked)
        }
        assertEquals(1, w.opens); assertEquals(1, w.closes)
    }
    @Test fun connectionHandshakeIsInsideSixtySecondDeadlineAndCannotRetry() = runTest {
        var opens = 0; var closes = 0
        val wire = object : GeminiLiveWire {
            override suspend fun connect(key: String, authorized: () -> Boolean, block: suspend (RealtimeSocket) -> Unit) {
                opens++; try { delay(60_001) } finally { closes++ }
            }
        }
        val t = trial({ testScheduler.currentTime })
        val error = runCatching { t.wrap(wire).connect("not-read", { true }) {} }.exceptionOrNull()
        assertTrue(error is CancellationException); assertEquals(60_000L, testScheduler.currentTime)
        assertEquals(1, opens); assertEquals(1, closes)
        assertTrue(runCatching { t.wrap(wire).connect("not-read", { true }) {} }.exceptionOrNull() is TrialBlocked)
        assertEquals(1, opens)
    }
    @Test fun blockedReceiveAlsoTimesOutAtSixtySeconds() = runTest {
        val t = trial({ testScheduler.currentTime }); val w = RestrictedTrialFixture.Wire()
        assertTrue(attempt(t, w, response = null) is CancellationException)
        assertEquals(60_000L, testScheduler.currentTime); assertEquals(1, w.closes)
    }
    @Test fun approvalExpiryCanBeEarlierThanSixtySeconds() = runTest {
        val values = RestrictedTrialFixture.approval(setup) + ("approvalExpiresAtMillis" to "50")
        val t = RestrictedGeminiTrial(RestrictedTrialApproval.read(values, 0), TrialPermitJournal(temporary.newFile()),
            setup, RestrictedTrialFixture.pcm, nowMillis = { testScheduler.currentTime },
            nowNanos = { testScheduler.currentTime * 1_000_000L })
        assertTrue(attempt(t, RestrictedTrialFixture.Wire(), response = null) is CancellationException)
        assertEquals(50L, testScheduler.currentTime)
    }
    @Test fun changedPcmAndAddedSilenceAreRejectedBeforeTheyReachWire() = runTest {
        for (bytes in listOf(ByteArray(3200), RestrictedTrialFixture.pcm.copyOf().also { it[100] = 4 })) {
            val w = RestrictedTrialFixture.Wire(); w.incoming.send("{\"setupComplete\":{}}")
            val t = trial({ testScheduler.currentTime })
            val error = runCatching { t.wrap(w).connect("API0_OPAQUE_FIXTURE", { true }) { s ->
                s.send(setup); s.receive(); s.send(RestrictedTrialFixture.input(bytes))
            } }.exceptionOrNull()
            assertEquals("INPUT_DIFFERS_FROM_APPROVED_PCM", (error as TrialBlocked).reasonCode)
            assertEquals(1, w.sent.size)
        }
    }
    @Test fun approvedInputCannotBeExtendedBeyondPlannedClip() = runTest {
        val t = trial({ testScheduler.currentTime }); val w = RestrictedTrialFixture.Wire()
        w.incoming.send("{\"setupComplete\":{}}")
        val error = runCatching { t.wrap(w).connect("API0_OPAQUE_FIXTURE", { true }) { s ->
            s.send(setup); s.receive(); s.send(RestrictedTrialFixture.input()); s.send(RestrictedTrialFixture.input())
        } }.exceptionOrNull()
        assertEquals("UNPLANNED_ADDITIONAL_INPUT", (error as TrialBlocked).reasonCode)
        assertEquals(3200L, t.countsOnly().getLong("input_bytes"))
        assertEquals(1, w.sent.count { JSONObject(it).optJSONObject("realtimeInput")?.has("audioStreamEnd") == true })
    }
    @Test fun totalThirtyEightPointFourSecondsIsSampleByteBoundNotWallInjectionTime() = runTest {
        val pcm = ByteArray(409_600) { 2 }; val t = trial({ testScheduler.currentTime }, pcm = pcm)
        val w = RestrictedTrialFixture.Wire()
        for (n in 1..2) {
            w.incoming.send("{\"setupComplete\":{}}"); repeat(n) { w.incoming.send(RestrictedTrialFixture.output()) }
            RestrictedTrialFixture.drive(t, w, setup, n, pcm)
        }
        assertEquals(1_228_800L, t.countsOnly().getLong("input_bytes"))
        val tooLarge = trial({ testScheduler.currentTime }, pcm = ByteArray(412_800))
        assertEquals("UNAPPROVED_OR_UNALIGNED_PREPARED_PCM", (attempt(tooLarge, w) as TrialBlocked).reasonCode)
        assertEquals(2, w.opens)
    }
    @Test fun outputBeyondTwentySecondsIsNotForwardedAndNextConnectionIsBlocked() = runTest {
        val t = trial({ testScheduler.currentTime }); val w = RestrictedTrialFixture.Wire()
        w.incoming.send("{\"setupComplete\":{}}")
        repeat(11) { w.incoming.send(RestrictedTrialFixture.output(96_000, complete = false, usage = null)) }
        val error = runCatching { RestrictedTrialFixture.drive(t, w, setup, 1) }.exceptionOrNull()
        assertEquals("OUTPUT_DURATION_LIMIT", (error as TrialBlocked).reasonCode)
        assertEquals(960_000L, t.countsOnly().getLong("output_bytes")); assertEquals(1, w.closes)
        assertTrue(attempt(t, w) is TrialBlocked); assertEquals(1, w.opens)
    }
    @Test fun exactThreeTwentySecondOutputsMeetSixtySecondTotalButNoMoreConnection() = runTest {
        val t = trial({ testScheduler.currentTime }); val w = RestrictedTrialFixture.Wire()
        for (n in 1..2) {
            w.incoming.send("{\"setupComplete\":{}}")
            repeat(n) { repeat(10) { packet -> w.incoming.send(RestrictedTrialFixture.output(96_000,
                complete = packet == 9, usage = if (packet == 9) RestrictedTrialFixture.usage() else null)) } }
            RestrictedTrialFixture.drive(t, w, setup, n)
        }
        assertEquals(2_880_000L, t.countsOnly().getLong("output_bytes"))
        assertEquals(2, t.countsOnly().getInt("completed_sessions"))
        assertEquals("CONNECTION_LIMIT", (attempt(t, w) as TrialBlocked).reasonCode)
        assertEquals(2, w.opens)
    }
    @Test fun unexpectedResponseAfterExpectedTurnClosesWithoutWaitingForUsage() = runTest {
        val t = trial({ testScheduler.currentTime }); val w = RestrictedTrialFixture.Wire()
        w.incoming.send("{\"setupComplete\":{}}"); w.incoming.send(RestrictedTrialFixture.output(usage = null))
        w.incoming.send(RestrictedTrialFixture.output())
        val error = runCatching { RestrictedTrialFixture.drive(t, w, setup, 1) }.exceptionOrNull()
        assertEquals("UNEXPECTED_RESPONSE", (error as TrialBlocked).reasonCode)
        assertEquals(4800L, t.countsOnly().getLong("output_bytes"))
    }
    @Test fun missingUsageStopsAfterGraceAndCannotOpenSessionTwo() = runTest {
        val t = trial({ testScheduler.currentTime }); val w = RestrictedTrialFixture.Wire()
        assertTrue(attempt(t, w, response = RestrictedTrialFixture.output(usage = null)) is CancellationException)
        assertEquals(1000L, testScheduler.currentTime)
        assertTrue(attempt(t, w) is TrialBlocked); assertEquals(1, w.opens)
    }
    @Test fun terminalControlsEveryNineHundredMillisCannotExtendUsageDeadline() = runTest {
        val t = trial({ testScheduler.currentTime }); val w = RestrictedTrialFixture.Wire()
        backgroundScope.launch {
            repeat(70) { delay(900); w.incoming.send("{\"serverContent\":{\"waitingForInput\":true}}") }
        }
        assertTrue(attempt(t, w, response = RestrictedTrialFixture.output(usage = null)) is CancellationException)
        assertEquals(1000L, testScheduler.currentTime)
        assertEquals(1, w.closes); assertTrue(attempt(t, w) is TrialBlocked); assertEquals(1, w.opens)
    }
    @Test fun terminalUsageJustBeforeAbsoluteDeadlineCanCompleteSession() = runTest {
        val t = trial({ testScheduler.currentTime }); val w = RestrictedTrialFixture.Wire()
        backgroundScope.launch {
            delay(900); w.incoming.send("{\"serverContent\":{\"waitingForInput\":true}}")
            delay(99); w.incoming.send(JSONObject().put("usageMetadata", RestrictedTrialFixture.usage()).toString())
        }
        assertNull(attempt(t, w, response = RestrictedTrialFixture.output(usage = null)))
        assertEquals(999L, testScheduler.currentTime)
        assertEquals(1, t.countsOnly().getInt("completed_sessions")); assertEquals(1, w.closes)
    }
    @Test fun terminalUsageAfterAbsoluteDeadlineCannotBeAcceptedOrEnableFollowup() = runTest {
        val t = trial({ testScheduler.currentTime }); val w = RestrictedTrialFixture.Wire()
        backgroundScope.launch {
            delay(900); w.incoming.send("{\"serverContent\":{\"waitingForInput\":true}}")
            delay(101); w.incoming.send(JSONObject().put("usageMetadata", RestrictedTrialFixture.usage()).toString())
        }
        assertTrue(attempt(t, w, response = RestrictedTrialFixture.output(usage = null)) is CancellationException)
        assertEquals(1000L, testScheduler.currentTime)
        assertEquals(0, t.countsOnly().getInt("completed_sessions")); assertEquals(1, w.closes)
        assertTrue(attempt(t, w) is TrialBlocked); assertEquals(1, w.opens)
    }
    @Test fun malformedMissingThoughtCacheAndModalityUsageNeverDefaultsToZero() = runTest {
        val examples = listOf(
            RestrictedTrialFixture.usage().apply { remove("thoughtsTokenCount") },
            RestrictedTrialFixture.usage().apply { remove("cachedContentTokenCount") },
            RestrictedTrialFixture.usage().put("promptTokenCount", "100"),
            RestrictedTrialFixture.usage().put("promptTokenCount", 100.5),
            RestrictedTrialFixture.usage().put("responseTokenCount", -1),
            RestrictedTrialFixture.usage().put("totalTokenCount", 999),
            RestrictedTrialFixture.usage().apply { remove("responseTokensDetails") },
            RestrictedTrialFixture.usage(outputAudio = 2049))
        for (usage in examples) {
            val t = trial({ testScheduler.currentTime }); val w = RestrictedTrialFixture.Wire()
            assertTrue(attempt(t, w, response = RestrictedTrialFixture.output(usage = usage)) is TrialBlocked)
            assertTrue(attempt(t, w) is TrialBlocked); assertEquals(1, w.opens)
        }
    }
    @Test fun duplicateUsageWithinSameTurnIsNotDoubleAdded() = runTest {
        val t = trial({ testScheduler.currentTime }); val w = RestrictedTrialFixture.Wire()
        w.incoming.send("{\"setupComplete\":{}}")
        w.incoming.send(RestrictedTrialFixture.output(complete = false))
        w.incoming.send(JSONObject().put("serverContent", JSONObject().put("turnComplete", true)).put("usageMetadata", RestrictedTrialFixture.usage()).toString())
        RestrictedTrialFixture.drive(t, w, setup, 1)
        assertEquals("0.0015", java.math.BigDecimal(t.countsOnly().getString("estimated_usd")).stripTrailingZeros().toPlainString())
    }
    @Test fun estimateThresholdStopsCurrentAndAllFollowingSessions() = runTest {
        val t = trial({ testScheduler.currentTime }); val w = RestrictedTrialFixture.Wire()
        assertEquals("ESTIMATE_STOP_THRESHOLD", (attempt(t, w, response = RestrictedTrialFixture.output(
            usage = RestrictedTrialFixture.usage(promptAudio = 40_000))) as TrialBlocked).reasonCode)
        assertTrue(attempt(t, w) is TrialBlocked); assertEquals(1, w.opens)
    }
    @Test fun transcriptionBillingCoverageStaysUnknownAndBlocksFollowup() = runTest {
        val setup = RestrictedTrialFixture.setup(true); val t = trial({ testScheduler.currentTime }, setup)
        val w = RestrictedTrialFixture.Wire()
        assertTrue(attempt(t, w, setup) is CancellationException)
        assertEquals("UNKNOWN", t.countsOnly().getJSONArray("sessions").getJSONObject(0).getString("transcription_cost_coverage"))
        assertTrue(attempt(t, w, setup) is TrialBlocked); assertEquals(1, w.opens)
    }
    @Test fun toolsResumptionTextAndChangedOutputTokenLimitFailBeforeConnect() = runTest {
        for (mutate in listOf<(JSONObject) -> Unit>(
            { it.put("tools", org.json.JSONArray()) }, { it.put("sessionResumption", JSONObject()) },
            { it.getJSONObject("generationConfig").put("maxOutputTokens", 2049) })) {
            val root = JSONObject(setup); mutate(root.getJSONObject("setup")); val changed = root.toString()
            val t = trial({ testScheduler.currentTime }, changed); val w = RestrictedTrialFixture.Wire()
            assertTrue(attempt(t, w, changed) is TrialBlocked); assertEquals(0, w.opens)
        }
    }
    @Test fun serverToolOrReconnectRequestAndOpaqueFailureAreSanitized() = runTest {
        for (raw in listOf("{\"toolCall\":{}}", "{\"goAway\":{}}", "{\"sessionResumptionUpdate\":{}}")) {
            val t = trial({ testScheduler.currentTime }); val w = RestrictedTrialFixture.Wire()
            assertEquals("SERVER_TOOL_RESUME_ERROR_OR_UNKNOWN", (attempt(t, w, response = raw) as TrialBlocked).reasonCode)
        }
        val t = trial({ testScheduler.currentTime })
        val wire = object : GeminiLiveWire {
            override suspend fun connect(key: String, authorized: () -> Boolean, block: suspend (RealtimeSocket) -> Unit) { error("must-not-be-logged-private-marker") }
        }
        val failure = runCatching { t.wrap(wire).connect("opaque", { true }) {} }.exceptionOrNull()!!
        assertEquals("TRANSPORT_OR_PROTOCOL_FAILED", failure.message); assertNull(failure.cause)
    }
}
