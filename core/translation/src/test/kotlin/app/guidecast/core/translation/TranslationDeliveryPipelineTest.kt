package app.guidecast.core.translation

import app.guidecast.core.stream.AudioChannelDescriptor
import app.guidecast.core.stream.AudioStreamRegistry
import app.guidecast.core.stream.ChannelAudioPublicationCoordinator
import app.guidecast.core.stream.PcmAudioFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TranslationDeliveryPipelineTest {
    private val languages = listOf("en", "zh", "ja", "ru")
    private fun targets(tags: List<String> = languages) = tags.map { TranslationTarget(it, it, it, 24_000) }
    private fun utterance(sequence: Long) = RecognizedUtterance(sequence, "같은 합성 문장", "ko", true, sequence)
    private val speech = SpeechSynthesisEngineProvider {
        object : SpeechSynthesisEngine {
            override fun synthesize(text: String, languageTag: String) = flowOf(PcmAudioFrame(ByteArray(640) { 8 }, 1))
        }
    }

    @Test fun sharedRequestFansOutFourTicketsAndDuplicateSourceOrSnapshotDoesNotRepeatAudio() = runTest {
        val streams = AudioStreamRegistry()
        val source = MutableSharedFlow<RecognizedUtterance>()
        var requests = 0; var concurrent = 0; var maximumConcurrent = 0
        val running = TranslationBroadcastPipeline(streams, TranslationEngineProvider {
            TextTranslationEngine { _, _, target ->
                val context = currentCoroutineContext()
                context[TranslationDeliveryContext]!!.bindVersions(7, 99)
                val identity = context[TranslationRequestIdentity]!!
                context[SharedTranslationBatchContext]!!.await(identity, "fixture-${identity.sequence}") {
                    concurrent++; maximumConcurrent = maxOf(maximumConcurrent, concurrent); requests++
                    try { languages.associateWith { "$it-${identity.sequence}" } } finally { concurrent-- }
                }.getValue(target)
            }
        }, speech, audioPublicationCoordinator = ChannelAudioPublicationCoordinator(languages))
            .start(this, source, targets())
        val readers = languages.associateWith { running.streamSession.subscribe(it) }
        try {
            runCurrent()
            for (sequence in listOf(10L, 13L)) { source.emit(utterance(sequence)); advanceUntilIdle() }
            source.emit(utterance(10)); advanceUntilIdle()
            val before = running.deliveryTickets.snapshots()
            repeat(20) { assertEquals(before, running.deliveryTickets.snapshots()) }
            assertEquals(2, requests); assertEquals(1, maximumConcurrent)
            assertEquals(8, before.size)
            assertEquals(1, before.map { it.identity.sharedBatchId }.distinct().size)
            assertTrue(before.all { it.identity.attempt == 1 && it.identity.streamEpoch == running.streamSession.generation })
            assertTrue(before.all { it.settingsRevision == 7L && it.corpusRevision == 99L })
            assertTrue(before.all { it.stage == TranslationDeliveryStage.SYNTHESIZED && it.publishedFrames == 1L && it.publishedBytes == 640L })
            for ((_, reader) in readers) {
                assertEquals(listOf(10L, 13L), List(2) { reader.frames.receive().utteranceSequence })
                assertTrue(reader.frames.tryReceive().isFailure)
            }
        } finally { readers.values.forEach { it.close() }; running.close() }
    }

    @Test fun nextTranslationDoesNotRetirePriorCommittedSpeechAndOneLaneRemainsOrdered() = runTest {
        val source = MutableSharedFlow<RecognizedUtterance>()
        val finishFirst = CompletableDeferred<Unit>()
        var translations = 0; var synthesizing = 0; var maximumSynthesizing = 0
        val running = TranslationBroadcastPipeline(AudioStreamRegistry(), TranslationEngineProvider {
            TextTranslationEngine { _, _, _ -> (++translations).toString() }
        }, SpeechSynthesisEngineProvider {
            object : SpeechSynthesisEngine {
                override fun synthesize(text: String, languageTag: String) = flow {
                    synthesizing++; maximumSynthesizing = maxOf(maximumSynthesizing, synthesizing)
                    try {
                        if (text == "1") finishFirst.await()
                        emit(PcmAudioFrame(ByteArray(640) { 8 }, 1))
                    } finally { synthesizing-- }
                }
            }
        }).start(this, source, targets(listOf("en")))
        val reader = running.streamSession.subscribe("en")
        try {
            runCurrent(); source.emit(utterance(1)); runCurrent(); source.emit(utterance(8)); runCurrent()
            assertEquals(2, translations)
            assertEquals(listOf(TranslationDeliveryStage.SYNTHESIZING, TranslationDeliveryStage.SPEECH_QUEUED),
                running.deliveryTickets.snapshots().map { it.stage })
            finishFirst.complete(Unit); advanceUntilIdle()
            assertEquals(listOf(1L, 8L), List(2) { reader.frames.receive().utteranceSequence })
            assertEquals(1, maximumSynthesizing)
        } finally { finishFirst.complete(Unit); reader.close(); running.close() }
    }

    @Test fun supersededStreamQuarantinesReturningTranslationBeforeCaptionsOrSpeech() = runTest {
        val streams = AudioStreamRegistry()
        val source = MutableSharedFlow<RecognizedUtterance>()
        val response = CompletableDeferred<Unit>()
        var captions = 0; var syntheses = 0
        val running = TranslationBroadcastPipeline(streams, TranslationEngineProvider {
            TextTranslationEngine { _, _, _ -> response.await(); "Late fixture result" }
        }, SpeechSynthesisEngineProvider {
            syntheses++; speech.engineFor("en")
        }, observer = object : TranslationPipelineObserver {
            override fun onTranslationCompleted(utterance: RecognizedUtterance, target: TranslationTarget,
                translatedText: String, elapsedMillis: Long) { captions++ }
        }).start(this, source, targets(listOf("en")))
        try {
            runCurrent(); source.emit(utterance(1)); runCurrent()
            val replacement = streams.configure(listOf(AudioChannelDescriptor("en", "en", "en", 24_000)))
            response.complete(Unit); advanceUntilIdle()
            assertEquals(0, captions); assertEquals(0, syntheses)
            assertEquals(TranslationDeliveryStage.CANCELLED, running.deliveryTickets.snapshots().single().stage)
            assertTrue(replacement.isActive()); replacement.close()
        } finally { response.complete(Unit); running.close() }
    }

    @Test fun closeRejectsLateSynthesisAndNewPipelineCanReuseSequenceWithNewEpoch() = runTest {
        val streams = AudioStreamRegistry()
        val source = MutableSharedFlow<RecognizedUtterance>()
        val late = CompletableDeferred<Unit>()
        val translator = TranslationEngineProvider { TextTranslationEngine { _, _, _ -> "Fixture" } }
        val old = TranslationBroadcastPipeline(streams, translator, SpeechSynthesisEngineProvider {
            object : SpeechSynthesisEngine {
                override fun synthesize(text: String, languageTag: String) = flow {
                    emit(PcmAudioFrame(ByteArray(640) { 8 }, 1))
                    withContext(NonCancellable) { late.await() }
                    emit(PcmAudioFrame(ByteArray(640) { 8 }, 2))
                }
            }
        }).start(this, source, targets(listOf("en")))
        runCurrent(); source.emit(utterance(1)); runCurrent(); old.close()
        val next = TranslationBroadcastPipeline(streams, translator, speech)
            .start(this, flowOf(utterance(1)), targets(listOf("en")))
        try {
            late.complete(Unit); advanceUntilIdle()
            val prior = old.deliveryTickets.snapshots().single(); val fresh = next.deliveryTickets.snapshots().single()
            assertEquals(TranslationDeliveryStage.CANCELLED, prior.stage); assertEquals(640L, prior.publishedBytes)
            assertEquals(TranslationDeliveryStage.SYNTHESIZED, fresh.stage); assertEquals(640L, fresh.publishedBytes)
            assertNotEquals(prior.identity.inputScope, fresh.identity.inputScope)
            assertNotEquals(prior.identity.streamEpoch, fresh.identity.streamEpoch)
        } finally { late.complete(Unit); next.close() }
    }

    @Test fun failedLanguageDoesNotReplaySharedRequestOrBlockSiblingAndNextInput() = runTest {
        val source = MutableSharedFlow<RecognizedUtterance>()
        var requests = 0
        val running = TranslationBroadcastPipeline(AudioStreamRegistry(), TranslationEngineProvider {
            TextTranslationEngine { _, _, target ->
                val context = currentCoroutineContext(); val identity = context[TranslationRequestIdentity]!!
                context[SharedTranslationBatchContext]!!.await(identity, "fixture-${identity.sequence}") {
                    requests++; languages.associateWith { "${identity.sequence}" }
                }.getValue(target)
            }
        }, SpeechSynthesisEngineProvider {
            object : SpeechSynthesisEngine {
                override fun synthesize(text: String, languageTag: String) = flow {
                    if (languageTag == "ja" && text == "1") error("Synthetic voice failure")
                    emit(PcmAudioFrame(ByteArray(640) { 8 }, 1))
                }
            }
        }).start(this, source, targets())
        try {
            runCurrent(); source.emit(utterance(1)); advanceUntilIdle(); source.emit(utterance(4)); advanceUntilIdle()
            assertEquals(2, requests)
            val rows = running.deliveryTickets.snapshots()
            assertEquals(1, rows.count { it.stage == TranslationDeliveryStage.SYNTHESIS_FAILED })
            assertEquals(7, rows.count { it.stage == TranslationDeliveryStage.SYNTHESIZED })
            assertTrue(running.health.value.all { it.lastPublishedSequence == 4L })
        } finally { running.close() }
    }

    @Test fun speechOverflowIsExplicitAndDoesNotImposeContiguousSequence() = runTest {
        val source = MutableSharedFlow<RecognizedUtterance>(); val unblock = CompletableDeferred<Unit>()
        val running = TranslationBroadcastPipeline(AudioStreamRegistry(), TranslationEngineProvider {
            TextTranslationEngine { _, _, _ -> "Fixture" }
        }, SpeechSynthesisEngineProvider {
            object : SpeechSynthesisEngine {
                override fun synthesize(text: String, languageTag: String) = flow {
                    unblock.await(); emit(PcmAudioFrame(ByteArray(640) { 8 }, 1))
                }
            }
        }, speechQueueCapacityPerLanguage = 1).start(this, source, targets(listOf("en")))
        val reader = running.streamSession.subscribe("en")
        try {
            runCurrent()
            for (n in listOf(1L, 5L, 9L)) { source.emit(utterance(n)); runCurrent() }
            assertEquals(5L, running.deliveryTickets.snapshots().single { it.stage == TranslationDeliveryStage.DROPPED }.identity.inputSequence)
            unblock.complete(Unit); advanceUntilIdle()
            assertEquals(listOf(1L, 9L), List(2) { reader.frames.receive().utteranceSequence })
            assertEquals(1L, running.health.value.single().speechBacklogDrops)
        } finally { unblock.complete(Unit); reader.close(); running.close() }
    }

    @Test fun explicitlyMismatchedPriorSpeechFrameIsBlockedWithoutStoppingNextUtterance() = runTest {
        val source = MutableSharedFlow<RecognizedUtterance>()
        var translations = 0
        val running = TranslationBroadcastPipeline(AudioStreamRegistry(), TranslationEngineProvider {
            TextTranslationEngine { _, _, _ -> (++translations).toString() }
        }, SpeechSynthesisEngineProvider {
            object : SpeechSynthesisEngine {
                override fun synthesize(text: String, languageTag: String) = flowOf(
                    PcmAudioFrame(ByteArray(640) { 8 }, 1,
                        utteranceSequence = when (text) { "1", "2" -> 1L; else -> 9L }))
            }
        }).start(this, source, targets(listOf("en")))
        val reader = running.streamSession.subscribe("en")
        try {
            runCurrent()
            for (sequence in listOf(1L, 4L, 9L)) { source.emit(utterance(sequence)); advanceUntilIdle() }
            assertEquals(listOf(1L, 9L), List(2) { reader.frames.receive().utteranceSequence })
            assertTrue(reader.frames.tryReceive().isFailure)
            val failed = running.deliveryTickets.snapshots().single { it.identity.inputSequence == 4L }
            assertEquals(TranslationDeliveryStage.SYNTHESIS_FAILED, failed.stage)
            assertEquals(0L, failed.publishedBytes)
            assertEquals(9L, running.health.value.single().lastPublishedSequence)
        } finally { reader.close(); running.close() }
    }

    @Test fun boundedTerminalHistoryCannotAcceptLateCallbacksOrInventPlaybackAcknowledgement() {
        val tickets = TranslationDeliveryTickets("scope", "shared", 17, { true }, terminalCapacity = 4)
        val target = targets(listOf("en")).single()
        val first = tickets.begin(1, target)!!
        tickets.bindVersions(first, 2, null)
        try { tickets.bindVersions(first, 2, 99); fail("Unknown corpus revision was overwritten") }
        catch (_: IllegalStateException) { }
        for (sequence in 1L..300L) {
            val ticket = if (sequence == 1L) first else tickets.begin(sequence * 3, target)!!
            assertTrue(tickets.translated(ticket)); assertFalse(tickets.translated(ticket))
            assertTrue(tickets.beginSpeech(ticket)); assertFalse(tickets.beginSpeech(ticket))
            tickets.published(ticket, 640); tickets.finish(ticket, TranslationDeliveryStage.SYNTHESIZED)
        }
        tickets.published(first, 640)
        assertFalse(tickets.accepts(first)); assertEquals(4, tickets.snapshots().size)
        assertEquals(640L, first.snapshot.publishedBytes); assertNull(first.snapshot.corpusRevision)
        tickets.close(); assertNull(tickets.begin(999, target))
    }

    @Test fun observerCloseBeforePublicationCannotWriteToExternallyOwnedStream() = runTest {
        val streams = AudioStreamRegistry()
        val external = streams.configure(listOf(AudioChannelDescriptor("en", "en", "en", 24_000)))
        val reader = external.subscribe("en")
        val source = MutableSharedFlow<RecognizedUtterance>()
        lateinit var running: RunningTranslationPipeline
        running = TranslationBroadcastPipeline(streams, TranslationEngineProvider {
            TextTranslationEngine { _, _, _ -> "Fixture" }
        }, speech, observer = object : TranslationPipelineObserver {
            override fun onSynthesisAudioStarted(utterance: RecognizedUtterance,
                target: TranslationTarget, elapsedMillis: Long) { running.close() }
        }).start(this, source, targets(listOf("en")), streamSession = external)
        try {
            runCurrent(); source.emit(utterance(1)); advanceUntilIdle()
            assertTrue(external.isActive())
            assertTrue("PCM was written after observer closed the delivery", reader.frames.tryReceive().isFailure)
            val row = running.deliveryTickets.snapshots().single()
            assertEquals(TranslationDeliveryStage.CANCELLED, row.stage)
            assertEquals(0L, row.publishedBytes)
        } finally { running.close(); reader.close(); external.close() }
    }

    @Test fun closeFromAcceptedPublicationObserverPreservesAcceptedFrameCount() = runTest {
        val streams = AudioStreamRegistry()
        val external = streams.configure(listOf(AudioChannelDescriptor("en", "en", "en", 24_000)))
        val reader = external.subscribe("en")
        val source = MutableSharedFlow<RecognizedUtterance>()
        lateinit var running: RunningTranslationPipeline
        val observation = external.observePublishedPcm { _, _ -> running.close() }
        running = TranslationBroadcastPipeline(streams, TranslationEngineProvider {
            TextTranslationEngine { _, _, _ -> "Fixture" }
        }, speech).start(this, source, targets(listOf("en")), streamSession = external)
        try {
            runCurrent(); source.emit(utterance(1)); advanceUntilIdle()
            assertEquals(640, reader.frames.receive().bytes.size)
            assertTrue(reader.frames.tryReceive().isFailure)
            val row = running.deliveryTickets.snapshots().single()
            assertEquals(TranslationDeliveryStage.CANCELLED, row.stage)
            assertEquals(1L, row.publishedFrames)
            assertEquals(640L, row.publishedBytes)
        } finally { observation.close(); running.close(); reader.close(); external.close() }
    }

    @Test fun retiredLocalSequenceCannotReenterAfterFiniteDuplicateWindow() = runTest {
        val source = MutableSharedFlow<RecognizedUtterance>()
        var requests = 0
        val running = TranslationBroadcastPipeline(AudioStreamRegistry(), TranslationEngineProvider {
            TextTranslationEngine { _, _, _ -> requests++; "Fixture" }
        }, speech).start(this, source, targets(listOf("en")))
        val reader = running.streamSession.subscribe("en")
        try {
            runCurrent()
            for (sequence in 1L..257L) {
                source.emit(utterance(sequence)); advanceUntilIdle()
                assertEquals(sequence, reader.frames.receive().utteranceSequence)
            }
            source.emit(utterance(1)); advanceUntilIdle()
            assertEquals("A retired input was translated twice", 257, requests)
            assertTrue(reader.frames.tryReceive().isFailure)
            source.emit(utterance(300)); advanceUntilIdle()
            assertEquals(258, requests)
            assertEquals(300L, reader.frames.receive().utteranceSequence)
        } finally { reader.close(); running.close() }
    }
}
