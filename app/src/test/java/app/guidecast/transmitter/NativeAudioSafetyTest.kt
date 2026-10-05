package app.guidecast.transmitter

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NativeAudioSafetyTest {
    @Test fun officialOpenAiRoutesShareOnlyTheirOwnDestinationCredentialScopes() {
        val text = TranslationApiOptions(provider = TranslationApiProvider.OPENAI)
        val audio = text.copy(provider = TranslationApiProvider.OPENAI_REALTIME, realtimeAudio = true)
        assertEquals(text.credentialScope, audio.credentialScope)
        assertEquals(listOf("OPENAI:https://api.openai.com/v1", "OPENAI_REALTIME:https://api.openai.com/v1"), audio.readableCredentialScopes)
        val compatible = text.copy(provider = TranslationApiProvider.COMPATIBLE)
        assertFalse(compatible.readableCredentialScopes.any { it in text.readableCredentialScopes })
        assertEquals(listOf("COMPATIBLE:https://api.openai.com/v1"), compatible.readableCredentialScopes)
        val otherDestination = text.copy(baseUrl = "https://example.org/v1")
        assertFalse(otherDestination.readableCredentialScopes.any { it in text.readableCredentialScopes })
    }
    @Test fun savedTextRouteDoesNotTurnIntoAudioAndAudioChoiceRoundTripsWithoutPermission() {
        val text = onlineServiceChoice(TranslationApiOptions(), false)
        assertFalse(text.usesNativeLiveAudio)
        assertFalse(TranslationApiOptions.fromPortable(text.portable().apply { remove("realtimeAudio") }).usesNativeLiveAudio)
        val audio = openAiAudioChoice(text)
        assertTrue(audio.usesNativeLiveAudio)
        assertEquals(text.credentialScope, audio.credentialScope)
        val restored = TranslationApiOptions.fromPortable(audio.portable())
        assertTrue(restored.usesNativeLiveAudio)
        assertFalse(restored.allowOnline); assertFalse(restored.allowLiveAudio)
        assertFalse(serviceExperience(restored).supportsLearningComparison)
        assertTrue(serviceExperience(restored).supportsReferences)
        assertFalse(restored.allowDomainReferences)
        val dedicated = restored.copy(provider = TranslationApiProvider.GEMINI_LIVE,
            model = GEMINI_LIVE_TRANSLATE, baseUrl = "https://generativelanguage.googleapis.com/v1beta")
        assertFalse(serviceExperience(dedicated).supportsReferences)
        assertTrue(serviceExperience(restored).transmitted.contains("마이크 음성"))
        assertFalse(onlineServiceChoice(audio, false).usesNativeLiveAudio)
    }
    @Test fun offlineCannotBecomeNativeAudioThroughStaleChoiceFlag() {
        assertFalse(TranslationApiOptions(realtimeAudio = true).usesNativeLiveAudio)
        val restored = TranslationApiOptions.fromPortable(JSONObject().put("provider", "LOCAL").put("realtimeAudio", true))
        assertFalse(restored.usesNativeLiveAudio)
    }
    @Test fun transcriptIdentitySurvivesLateSourceAndCancelledRowsAreNotFinal() {
        val segments = OpenAiAudioSegments("en", "ko")
        val first = segments.accept(OpenAiAudioEvent("input1", translation = "Hello"), 10)
        val second = segments.accept(OpenAiAudioEvent("input2", source = "다음"), 20)
        val late = segments.accept(OpenAiAudioEvent("input1", source = "안녕", translation = "Hello", finished = true), 30)
        assertEquals(first.sequence, late.sequence); assertNotEquals(second.sequence, late.sequence)
        assertEquals(10L, late.capturedAtElapsedRealtimeNanos)
        assertEquals("안녕", late.sourceText); assertEquals("Hello", late.translations["en"])
        assertFalse(segments.accept(OpenAiAudioEvent("input1", finished = true, interrupted = true), 40).isFinal)
    }
    @Test fun pacingAndInterruptionDiscardOldFramesWithoutBlockingNextTurn() = runTest {
        val delivered = mutableListOf<String>(); var dropped = 0
        val queue = NativeAudioOutputQueue({ event, _ -> delivered += event.inputId }, { dropped++ }, nowNanos = { testScheduler.currentTime * 1_000_000 })
        val job = launch { queue.run() }
        assertTrue(queue.offer(OpenAiAudioEvent("old", audio = List(3) { ByteArray(4_800) })))
        runCurrent(); assertEquals(listOf("old"), delivered)
        queue.interrupt("old")
        assertTrue(queue.offer(OpenAiAudioEvent("new", audio = listOf(ByteArray(4_800)))))
        advanceTimeBy(101); runCurrent()
        assertEquals(listOf("old", "new"), delivered); assertEquals(2, dropped)
        queue.close(); job.cancelAndJoin()
    }
    @Test fun publicationCostIsIncludedInPcmPacingWithoutSkippingOrCatchUpBursts() = runTest {
        for (cost in listOf(20L, 130L)) {
            val starts = mutableListOf<Long>()
            val queue = NativeAudioOutputQueue({ _, _ ->
                starts += testScheduler.currentTime
                delay(cost)
            }, nowNanos = { testScheduler.currentTime * 1_000_000 })
            val runner = launch { queue.run() }
            queue.offer(OpenAiAudioEvent("paced", audio = List(30) { ByteArray(4800) }))
            advanceUntilIdle()
            assertEquals(30, starts.size)
            assertTrue(starts.zipWithNext().all { (a, b) -> b - a == maxOf(100L, cost) })
            queue.close(); runner.cancelAndJoin()
        }
    }
    @Test fun fractionalPcmIntervalsDoNotAddOneRoundedMillisecondPerChunk() = runTest {
        val starts = mutableListOf<Long>()
        val queue = NativeAudioOutputQueue({ _, _ -> starts += testScheduler.currentTime },
            nowNanos = { testScheduler.currentTime * 1_000_000 })
        val runner = launch { queue.run() }
        queue.offer(OpenAiAudioEvent("fractional", audio = List(100) { ByteArray(1000) }))
        advanceUntilIdle()
        assertEquals(100, starts.size)
        assertTrue(starts.last() in 2062L..2063L)
        queue.close(); runner.cancelAndJoin()
    }
    @Test fun idleRestartPublishesNewFirstFrameImmediatelyWithoutOldDeadlineOrDuplicateAudio() = runTest {
        val starts = mutableListOf<Pair<String, Long>>()
        val queue = NativeAudioOutputQueue({ event, _ -> starts += event.inputId to testScheduler.currentTime },
            nowNanos = { testScheduler.currentTime * 1_000_000 })
        val runner = launch { queue.run() }
        queue.offer(OpenAiAudioEvent("A", audio = listOf(ByteArray(4800)))); runCurrent()
        advanceTimeBy(5000); runCurrent()
        queue.offer(OpenAiAudioEvent("B", audio = List(2) { ByteArray(4800) })); runCurrent()
        assertEquals(listOf("A" to 0L, "B" to 5000L), starts)
        advanceTimeBy(101); runCurrent(); assertEquals("B" to 5100L, starts.last())
        queue.close(); runner.cancelAndJoin()
    }
    @Test fun repeatedPublicationJitterKeepsExactOrderAndNoUnboundedCatchUpBurst() = runTest {
        val starts = mutableListOf<Pair<Int, Long>>(); var index = 0
        val queue = NativeAudioOutputQueue({ _, _ ->
            val current = index++; starts += current to testScheduler.currentTime
            delay(if (current % 10 == 9) 140 else 20)
        }, nowNanos = { testScheduler.currentTime * 1_000_000 })
        val runner = launch { queue.run() }
        queue.offer(OpenAiAudioEvent("jitter", audio = List(100) { ByteArray(4800) }))
        advanceUntilIdle()
        assertEquals((0 until 100).toList(), starts.map { it.first })
        assertTrue(starts.zipWithNext().all { (a, b) -> b.second - a.second == if (a.first % 10 == 9) 140L else 100L })
        queue.close(); runner.cancelAndJoin()
    }
    @Test fun queueIsBoundedAndCloseDiscardsPendingAudio() = runTest {
        var discarded = 0
        val queue = NativeAudioOutputQueue({ _, _ -> }, { discarded++ }, maxQueuedBytes = 48000)
        assertTrue(queue.offer(OpenAiAudioEvent("one", audio = List(10) { ByteArray(4_800) })))
        assertFalse(queue.offer(OpenAiAudioEvent("two", audio = listOf(ByteArray(4_800)))))
        queue.close(); assertEquals(11, discarded)
    }
    @Test fun threeAndTenSecondServerBurstsRemainPacedAndInterruptible() = runTest {
        val delivered = mutableListOf<String>(); var discardedBytes = 0
        val queue = NativeAudioOutputQueue({ event, _ -> delivered += event.inputId }, { discardedBytes += it }, nowNanos = { testScheduler.currentTime * 1_000_000 })
        val runner = launch { queue.run() }
        assertTrue(queue.offer(OpenAiAudioEvent("three", inputSequence = 1, audio = List(30) { ByteArray(4800) })))
        assertTrue(queue.offer(OpenAiAudioEvent("ten", inputSequence = 2, audio = List(100) { ByteArray(4800) })))
        runCurrent(); advanceTimeBy(3001); runCurrent()
        assertEquals(30, delivered.count { it == "three" })
        queue.interrupt("ten", 2)
        assertTrue(queue.offer(OpenAiAudioEvent("next", inputSequence = 3, audio = listOf(ByteArray(4800)))))
        advanceTimeBy(101); runCurrent(); assertEquals("next", delivered.last())
        assertEquals(99 * 4800, discardedBytes)
        queue.close(); runner.cancelAndJoin()
    }
    @Test fun byteBudgetRejectsEntireOverloadBeforeAnyAudioIsAdmitted() = runTest {
        var delivered = 0; var discarded = 0
        val queue = NativeAudioOutputQueue({ _, _ -> delivered++ }, { discarded += it })
        assertFalse(queue.offer(OpenAiAudioEvent("large", audio = List(401) { ByteArray(4800) })))
        val runner = launch { queue.run() }; runCurrent()
        assertEquals(0, delivered); assertEquals(401 * 4800, discarded)
        queue.close(); runner.cancelAndJoin()
    }
    @Test fun threeHundredRetiredTurnsKeepOldDequeuedAudioBlockedAndCurrentAudioAllowed() {
        val retired = NativeAudioRetiredTurns()
        repeat(300) { retired.retire(it.toLong()) }
        repeat(300) { assertFalse(retired.allows(it.toLong())) }
        assertTrue(retired.allows(301))
    }
    @Test fun absentUsageHasOneUnknownBoundaryAndCannotBorrowPriorReportedCounts() = runTest {
        val samples = mutableListOf<OpenAiAudioEvent>()
        val output = NativeAudioOutputQueue({ _, _ -> })
        val router = NativeAudioEventRouter(output, {}, {}, { samples += it })
        val old = OpenAiAudioEvent("A", finished = true, status = "completed",
            usage = OpenAiAudioUsage(1, 2, 3, null, null, null, null, null))
        router.accept(old)
        val missing = OpenAiAudioEvent("B", finished = true, status = "completed")
        router.accept(missing); router.accept(missing)
        router.accept(missing.copy(usageKind = NativeAudioUsageKind.TRANSCRIPTION, sourceFinal = true))
        assertEquals(3, samples.size)
        assertNotNull(samples[0].usage); assertNull(samples[1].usage); assertNull(samples[2].usage)
        output.close()
    }
    @Test fun interruptionWaitsForInFlightPublicationBeforeCallerFlushes() = runTest {
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val states = mutableListOf<String>()
        val queue = NativeAudioOutputQueue({ _, _ -> entered.complete(Unit); finish.await(); states += "published" })
        queue.offer(OpenAiAudioEvent("old", audio = listOf(ByteArray(4_800))))
        val runner = launch { queue.run() }; entered.await()
        val interruption = launch { queue.interrupt("old"); states += "flush" }
        runCurrent(); assertTrue(states.isEmpty())
        finish.complete(Unit); runCurrent(); assertEquals(listOf("published", "flush"), states)
        interruption.join(); queue.close(); runner.cancelAndJoin()
    }
}
