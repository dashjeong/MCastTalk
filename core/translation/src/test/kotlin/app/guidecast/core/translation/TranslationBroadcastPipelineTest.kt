package app.guidecast.core.translation

import app.guidecast.core.stream.AudioChannelDescriptor
import app.guidecast.core.stream.AudioStreamRegistry
import app.guidecast.core.stream.ChannelAudioPublicationCoordinator
import app.guidecast.core.stream.PcmAudioFrame
import app.guidecast.core.stream.StreamPublishStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TranslationBroadcastPipelineTest {
    @Test
    fun `oversize correction keeps full translation and speech with warning`() = runTest {
        val source = MutableSharedFlow<RecognizedUtterance>()
        val warnings = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val originalTranslation = "字".repeat(100)
        val running = TranslationBroadcastPipeline(
            AudioStreamRegistry(), TranslationEngineProvider { TextTranslationEngine { _, _, _ -> originalTranslation } },
            SpeechSynthesisEngineProvider { object : SpeechSynthesisEngine {
                override fun synthesize(text: String, languageTag: String) = flow {
                    spoken.add(text)
                    emit(PcmAudioFrame("audible".encodeToByteArray().toEvenPcm(), 1L))
                }
            } },
            glossaryTerms = { _, _, _ -> listOf(GlossaryTerm("ko", "ja", "용어", "語".repeat(512), "字")) },
            onGlossaryWarning = { warnings.add(it) },
        ).start(this, source, listOf(TranslationTarget("ja", "Japanese", "ja", 16_000)), "ko")
        yield()
        source.emit(RecognizedUtterance(1, "용어입니다.", "ko", true, 1L))
        advanceUntilIdle()
        assertEquals(listOf(originalTranslation), spoken)
        assertEquals(1, warnings.size)
        running.close()
    }
    @Test
    fun `glossary snapshot corrects committed subtitle and exact speech once with provider context retained`() = runTest {
        val streams = AudioStreamRegistry()
        val source = MutableSharedFlow<RecognizedUtterance>(extraBufferCapacity = 1)
        val captions = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        var preferred = "DMZ Peace Trail"
        val running = TranslationBroadcastPipeline(
            streams,
            TranslationEngineProvider { object : ContextualTextTranslationEngine {
                override suspend fun translateWithContext(text: String, contextBefore: String?, sourceLanguageTag: String, targetLanguageTag: String): String {
                    assertEquals("이전 문맥", contextBefore)
                    assertTrue(kotlinx.coroutines.currentCoroutineContext()[TranslationGlossaryContext]!!.hints.contains(preferred))
                    return "Walk along peace road."
                }
            } },
            SpeechSynthesisEngineProvider { object : SpeechSynthesisEngine {
                override fun synthesize(text: String, languageTag: String) = flow {
                    spoken.add(text)
                    emit(PcmAudioFrame(text.encodeToByteArray().toEvenPcm(), 1L))
                }
            } },
            observer = object : TranslationPipelineObserver {
                override fun onTranslationCompleted(utterance: RecognizedUtterance, target: TranslationTarget, translatedText: String, elapsedMillis: Long) {
                    captions.add(translatedText)
                    preferred = "Changed after commitment"
                }
            },
            glossaryTerms = { _, _, _ -> listOf(GlossaryTerm("ko", "en", "평화의 길", preferred, "peace road")) },
        ).start(this, source, listOf(TranslationTarget("en", "English", "en", 16_000)), "ko")
        yield()
        source.emit(RecognizedUtterance(1, "평화의 길을 걷습니다.", "ko", true, 1L, contextBefore = "이전 문맥"))
        advanceUntilIdle()
        assertEquals(listOf("Walk along DMZ Peace Trail."), captions)
        assertEquals(captions, spoken)
        running.close()
    }
    @Test
    fun `original audio coexists with one three and five actual translation pipelines`() = runTest {
        for (count in listOf(1, 3, 5)) {
            val languages = listOf("en", "ja", "zh", "nl", "es").take(count)
            val streams = AudioStreamRegistry()
            val descriptors = languages.map { AudioChannelDescriptor(it, it, it, 16_000) }
            val session = streams.configure(listOf(AudioChannelDescriptor("source", "Original", "ko", 16_000)) + descriptors)
            val sourceListener = session.subscribe("source")
            val listeners = languages.associateWith { session.subscribe(it) }
            val source = MutableSharedFlow<RecognizedUtterance>(extraBufferCapacity = 1)
            val synthesized = mutableListOf<String>()
            val running = TranslationBroadcastPipeline(
                streams = streams,
                translationEngines = TranslationEngineProvider {
                    TextTranslationEngine { text, _, language -> "$language:$text" }
                },
                speechEngines = SpeechSynthesisEngineProvider {
                    object : SpeechSynthesisEngine {
                        override fun synthesize(text: String, languageTag: String): Flow<PcmAudioFrame> = flow {
                            synthesized += languageTag
                            emit(PcmAudioFrame(text.encodeToByteArray().toEvenPcm(), 1L))
                        }
                    }
                },
            ).start(this, source, languages.map { TranslationTarget(it, it, it, 16_000) }, "ko", session)
            yield()
            session.tryPublish("source", PcmAudioFrame("original".encodeToByteArray().toEvenPcm(), 1L))
            source.emit(RecognizedUtterance(1, "안내", "ko", true, 1L))
            for (language in languages) {
                val frame = withTimeout(1_000) { listeners.getValue(language).frames.receive() }
                assertEquals("$language:안내", frame.bytes.toTextPcm())
                assertEquals(1L, frame.utteranceSequence)
            }
            assertEquals("original", sourceListener.frames.receive().bytes.toTextPcm())
            assertEquals(languages.toSet(), synthesized.toSet())
            running.close()
            assertTrue("Translation stop must not close original broadcast", session.isActive())
            listeners.values.forEach { it.close() }
            sourceListener.close()
            session.close()
        }
    }

    @Test
    fun `shared fair engine lets three and seven channels outwait old inference deadline`() = runTest {
        for (count in listOf(3, 7)) {
            val languages = listOf("en", "ja", "zh", "zh-TW", "vi", "es", "ar").take(count)
            val streams = AudioStreamRegistry()
            val session = streams.configure(
                listOf(AudioChannelDescriptor("source", "Original", "ko", 16_000)) +
                    languages.map { AudioChannelDescriptor(it.lowercase(), it, it, 16_000) },
            )
            val sourceListener = session.subscribe("source")
            val listeners = languages.associateWith { session.subscribe(it.lowercase()) }
            val timings = mutableListOf<FairTranslationTiming>()
            val fairProvider = FairQueuedTranslationEngineProvider(
                delegate = TranslationEngineProvider {
                    TextTranslationEngine { text, _, target ->
                        delay(1_000)
                        "$target:$text"
                    }
                },
                parentScope = this,
                config = FairTranslationQueueConfig(
                    maxPendingPerLanguage = 2,
                    queueWaitTimeoutMillis = 20_000,
                    inferenceTimeoutMillis = 4_000,
                ),
                observer = FairTranslationQueueObserver { timings += it },
                nanoTime = { testScheduler.currentTime * 1_000_000L },
            )
            val utterances = MutableSharedFlow<RecognizedUtterance>(extraBufferCapacity = 1)
            val running = TranslationBroadcastPipeline(
                streams = streams,
                translationEngines = fairProvider,
                speechEngines = SpeechSynthesisEngineProvider {
                    object : SpeechSynthesisEngine {
                        override fun synthesize(text: String, languageTag: String) =
                            flowOf(PcmAudioFrame(text.encodeToByteArray().toEvenPcm(), 1L))
                    }
                },
                translationTimeoutMillis = 4_000,
            ).start(
                scope = this,
                utterances = utterances,
                targets = languages.map { TranslationTarget(it.lowercase(), it, it, 16_000) },
                sourceLanguageTag = "ko",
                streamSession = session,
            )
            runCurrent()

            val original = PcmAudioFrame("original".encodeToByteArray().toEvenPcm(), 1L)
            session.tryPublish("source", original)
            utterances.emit(RecognizedUtterance(1, "안내", "ko", true, 1L))
            advanceUntilIdle()

            assertEquals(original.bytes.toList(), sourceListener.frames.receive().bytes.toList())
            languages.forEach { language ->
                val frame = listeners.getValue(language).frames.receive()
                assertTrue(frame.bytes.any { it != 0.toByte() })
                assertEquals("$language:안내", frame.bytes.toTextPcm())
            }
            assertEquals(count, timings.size)
            assertTrue(timings.all { it.inferenceMillis == 1_000L })
            if (count == 7) {
                assertTrue(timings.maxOf { it.queueWaitMillis } > 4_000L)
            }
            assertTrue(running.health.value.all { it.lastCompletedSequence == 1L && it.lastError == null })

            running.close()
            fairProvider.close()
            listeners.values.forEach { it.close() }
            sourceListener.close()
            session.close()
            advanceUntilIdle()
        }
    }

    @Test
    fun `uses an externally configured stream session without replacing or closing it`() = runTest {
        val streams = AudioStreamRegistry()
        val descriptor = AudioChannelDescriptor("en", "English", "en-US", 16_000)
        val externalSession = streams.configure(listOf(descriptor))
        val generation = externalSession.generation
        val listener = externalSession.subscribe("en")
        val utterances = MutableSharedFlow<RecognizedUtterance>(extraBufferCapacity = 1)
        val running = TranslationBroadcastPipeline(
            streams = streams,
            translationEngines = TranslationEngineProvider {
                TextTranslationEngine { text, _, _ -> "translated:$text" }
            },
            speechEngines = SpeechSynthesisEngineProvider {
                object : SpeechSynthesisEngine {
                    override fun synthesize(text: String, languageTag: String) =
                        flowOf(PcmAudioFrame(text.encodeToByteArray().toEvenPcm(), 1L))
                }
            },
        ).start(
            scope = this,
            utterances = utterances,
            targets = listOf(TranslationTarget("en", "English", "en-US", 16_000)),
            streamSession = externalSession,
        )
        yield()

        assertTrue(running.streamSession === externalSession)
        assertEquals(generation, streams.currentSession().generation)
        utterances.emit(RecognizedUtterance(1, "안내", "ko-KR", true, 1L))
        val translatedFrame = withTimeout(1_000) { listener.frames.receive() }
        assertEquals(
            "translated:안내",
            translatedFrame.bytes.toTextPcm(),
        )
        assertEquals(1L, translatedFrame.utteranceSequence)

        running.close()
        assertTrue(externalSession.isActive())
        val ownerFrame = PcmAudioFrame("owner".encodeToByteArray().toEvenPcm(), 2L)
        assertEquals(
            StreamPublishStatus.PUBLISHED,
            externalSession.tryPublish("en", ownerFrame).status,
        )
        assertEquals("owner", withTimeout(1_000) { listener.frames.receive() }.bytes.toTextPcm())
        listener.close()
        externalSession.close()
    }

    @Test
    fun `publication gate blocks one whole language item without blocking its sibling`() = runTest {
        val coordinator = ChannelAudioPublicationCoordinator(listOf("en", "ja"))
        val englishLease = coordinator.acquireChannel("en")
        val utterances = MutableSharedFlow<RecognizedUtterance>(extraBufferCapacity = 1)
        val running = TranslationBroadcastPipeline(
            streams = AudioStreamRegistry(),
            translationEngines = TranslationEngineProvider {
                TextTranslationEngine { text, _, languageTag -> "$languageTag:$text" }
            },
            speechEngines = SpeechSynthesisEngineProvider {
                object : SpeechSynthesisEngine {
                    override fun synthesize(text: String, languageTag: String) =
                        flowOf(PcmAudioFrame(audibleTestPcm(), 1L))
                }
            },
            audioPublicationCoordinator = coordinator,
        ).start(
            scope = this,
            utterances = utterances,
            targets = listOf(
                TranslationTarget("en", "English", "en", 24_000),
                TranslationTarget("ja", "Japanese", "ja", 24_000),
            ),
        )
        runCurrent()

        utterances.emit(RecognizedUtterance(1, "안내", "ko", true, 1L))
        runCurrent()

        val waiting = running.health.value.associateBy { it.channelId }
        assertEquals(null, waiting.getValue("en").lastCompletedSequence)
        assertEquals(1L, waiting.getValue("ja").lastCompletedSequence)

        englishLease.close()
        advanceUntilIdle()

        assertEquals(1L, running.health.value.single { it.channelId == "en" }.lastCompletedSequence)
        running.close()
    }

    @Test
    fun `closes only a stream session created and still owned by the pipeline`() = runTest {
        val streams = AudioStreamRegistry()
        val running = TranslationBroadcastPipeline(
            streams = streams,
            translationEngines = TranslationEngineProvider {
                TextTranslationEngine { text, _, _ -> text }
            },
            speechEngines = SpeechSynthesisEngineProvider {
                object : SpeechSynthesisEngine {
                    override fun synthesize(text: String, languageTag: String) = emptyFlow<PcmAudioFrame>()
                }
            },
        ).start(
            scope = this,
            utterances = MutableSharedFlow(),
            targets = listOf(TranslationTarget("en", "English", "en", 24_000)),
        )
        val ownedSession = running.streamSession
        assertTrue(ownedSession.isActive())

        running.close()

        assertFalse(ownedSession.isActive())
        assertEquals(
            StreamPublishStatus.STALE_SESSION,
            ownedSession.tryPublish("en", PcmAudioFrame(byteArrayOf(1, 0), 1L)).status,
        )
    }

    @Test
    fun `closing an old owned pipeline cannot close its replacement session`() = runTest {
        val streams = AudioStreamRegistry()
        val target = TranslationTarget("en", "English", "en", 24_000)
        val running = TranslationBroadcastPipeline(
            streams = streams,
            translationEngines = TranslationEngineProvider {
                TextTranslationEngine { text, _, _ -> text }
            },
            speechEngines = SpeechSynthesisEngineProvider {
                object : SpeechSynthesisEngine {
                    override fun synthesize(text: String, languageTag: String) = emptyFlow<PcmAudioFrame>()
                }
            },
        ).start(this, MutableSharedFlow(), listOf(target))
        val replacement = streams.configure(
            listOf(AudioChannelDescriptor("en", "English", "en", 24_000)),
        )

        running.close()

        assertTrue(replacement.isActive())
        replacement.close()
    }

    @Test
    fun `rejects mismatched external channels without reconfiguring the registry`() = runTest {
        val streams = AudioStreamRegistry()
        val externalSession = streams.configure(
            listOf(
                AudioChannelDescriptor("source", "Original", "ko", 16_000),
                AudioChannelDescriptor("en", "English", "en", 24_000),
            ),
        )

        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            TranslationBroadcastPipeline(
                streams = streams,
                translationEngines = TranslationEngineProvider {
                    TextTranslationEngine { text, _, _ -> text }
                },
                speechEngines = SpeechSynthesisEngineProvider {
                    object : SpeechSynthesisEngine {
                        override fun synthesize(text: String, languageTag: String) =
                            emptyFlow<PcmAudioFrame>()
                    }
                },
            ).start(
                scope = this,
                utterances = MutableSharedFlow(),
                targets = listOf(TranslationTarget("ja", "日本語", "ja", 24_000)),
                streamSession = externalSession,
            )
        }

        assertTrue(externalSession.isActive())
        assertEquals(externalSession.generation, streams.currentSession().generation)
        externalSession.close()
    }

    @Test
    fun `stale session publication degrades only the affected language worker`() = runTest {
        val streams = AudioStreamRegistry()
        val descriptors = listOf(
            AudioChannelDescriptor("en", "English", "en", 24_000),
            AudioChannelDescriptor("ja", "日本語", "ja", 24_000),
        )
        val externalSession = streams.configure(descriptors)
        val english = externalSession.subscribe("en")
        val japaneseSpeechGate = CompletableDeferred<Unit>()
        val utterances = MutableSharedFlow<RecognizedUtterance>(extraBufferCapacity = 1)
        val running = TranslationBroadcastPipeline(
            streams = streams,
            translationEngines = TranslationEngineProvider { target ->
                TextTranslationEngine { text, _, _ -> "$target:$text" }
            },
            speechEngines = SpeechSynthesisEngineProvider { engineLanguageTag ->
                object : SpeechSynthesisEngine {
                    override fun synthesize(text: String, languageTag: String) = flow {
                        if (engineLanguageTag == "ja") japaneseSpeechGate.await()
                        emit(PcmAudioFrame(text.encodeToByteArray().toEvenPcm(), 1L))
                    }
                }
            },
        ).start(
            scope = this,
            utterances = utterances,
            targets = listOf(
                TranslationTarget("en", "English", "en", 24_000),
                TranslationTarget("ja", "日本語", "ja", 24_000),
            ),
            streamSession = externalSession,
        )
        yield()

        utterances.emit(RecognizedUtterance(1, "평화 안내", "ko", true, 1L))
        assertEquals(
            "en:평화 안내",
            withTimeout(1_000) { english.frames.receive() }.bytes.toTextPcm(),
        )
        streams.configure(descriptors)
        japaneseSpeechGate.complete(Unit)
        advanceUntilIdle()

        val englishHealth = running.health.value.single { it.channelId == "en" }
        val japaneseHealth = running.health.value.single { it.channelId == "ja" }
        assertEquals(1L, englishHealth.lastCompletedSequence)
        assertEquals(null, englishHealth.lastError)
        assertEquals(1L, japaneseHealth.synthesisFailures)
        assertEquals(TranslationWorkerState.DEGRADED, japaneseHealth.synthesisState)
        assertTrue(japaneseHealth.lastError.orEmpty().contains("replaced"))
        running.close()
        english.close()
    }

    @Test
    fun `translates and publishes each target language independently`() = runTest {
        val streams = AudioStreamRegistry()
        val utterances = MutableSharedFlow<RecognizedUtterance>(extraBufferCapacity = 1)
        val pipeline = TranslationBroadcastPipeline(
            streams = streams,
            translationEngines = TranslationEngineProvider { target ->
                TextTranslationEngine { text, _, _ -> "$target:$text" }
            },
            speechEngines = SpeechSynthesisEngineProvider {
                object : SpeechSynthesisEngine {
                    override fun synthesize(text: String, languageTag: String) =
                        flowOf(PcmAudioFrame(text.encodeToByteArray().toEvenPcm(), 1L))
                }
            },
        )
        val running = pipeline.start(
            scope = this,
            utterances = utterances,
            targets = listOf(
                TranslationTarget("en", "English", "en-US", 16_000),
                TranslationTarget("ja", "日本語", "ja-JP", 16_000),
            ),
        )
        val english = streams.subscribe("en")
        val japanese = streams.subscribe("ja")
        yield()

        utterances.emit(RecognizedUtterance(1, "안녕하세요", "ko-KR", true, 1L))

        withTimeout(1_000) {
            assertEquals("en-US:안녕하세요", english.frames.receive().bytes.toTextPcm())
            assertEquals("ja-JP:안녕하세요", japanese.frames.receive().bytes.toTextPcm())
        }
        running.close()
        english.close()
        japanese.close()
    }

    @Test
    fun `transient translation engine lookup failure recovers on next sentence per language`() =
        runTest {
            var englishLookups = 0
            val streams = AudioStreamRegistry()
            val utterances = MutableSharedFlow<RecognizedUtterance>(extraBufferCapacity = 2)
            val running = TranslationBroadcastPipeline(
                streams = streams,
                translationEngines = TranslationEngineProvider { target ->
                    if (target == "en" && ++englishLookups == 1) {
                        error("translation worker reconnecting")
                    }
                    TextTranslationEngine { text, _, language -> "$language:$text" }
                },
                speechEngines = SpeechSynthesisEngineProvider {
                    object : SpeechSynthesisEngine {
                        override fun synthesize(text: String, languageTag: String) =
                            flowOf(PcmAudioFrame(text.encodeToByteArray().toEvenPcm(), 1L))
                    }
                },
            ).start(
                this,
                utterances,
                listOf("en", "ja").map { TranslationTarget(it, it, it, 24_000) },
            )
            val english = streams.subscribe("en")
            val japanese = streams.subscribe("ja")
            runCurrent()

            utterances.emit(RecognizedUtterance(1, "첫 문장", "ko", true, 1L))
            runCurrent()
            assertTrue(english.frames.tryReceive().isFailure)
            assertEquals("ja:첫 문장", japanese.frames.tryReceive().getOrThrow().bytes.toTextPcm())

            utterances.emit(RecognizedUtterance(2, "둘째 문장", "ko", true, 2L))
            advanceUntilIdle()

            assertEquals("en:둘째 문장", english.frames.tryReceive().getOrThrow().bytes.toTextPcm())
            assertEquals("ja:둘째 문장", japanese.frames.tryReceive().getOrThrow().bytes.toTextPcm())
            assertEquals(2L, running.health.value.single { it.channelId == "en" }.lastCompletedSequence)
            assertTrue(running.health.value.all { it.lastError == null })
            running.close()
            english.close()
            japanese.close()
        }

    @Test
    fun `provider cancellation degrades only that translation item and next sentence recovers`() =
        runTest {
            var englishCalls = 0
            val streams = AudioStreamRegistry()
            val utterances = MutableSharedFlow<RecognizedUtterance>(extraBufferCapacity = 2)
            val running = TranslationBroadcastPipeline(
                streams = streams,
                translationEngines = TranslationEngineProvider { target ->
                    TextTranslationEngine { text, _, _ ->
                        if (target == "en" && ++englishCalls == 1) {
                            throw CancellationException("English provider cancelled one task")
                        }
                        "$target:$text"
                    }
                },
                speechEngines = SpeechSynthesisEngineProvider {
                    object : SpeechSynthesisEngine {
                        override fun synthesize(text: String, languageTag: String) =
                            flowOf(PcmAudioFrame(text.encodeToByteArray().toEvenPcm(), 1L))
                    }
                },
            ).start(
                this,
                utterances,
                listOf("en", "ja").map { TranslationTarget(it, it, it, 24_000) },
            )
            val english = streams.subscribe("en")
            val japanese = streams.subscribe("ja")
            runCurrent()

            utterances.emit(RecognizedUtterance(1, "first", "ko", true, 1L))
            runCurrent()
            assertTrue(english.frames.tryReceive().isFailure)
            assertEquals("ja:first", japanese.frames.tryReceive().getOrThrow().bytes.toTextPcm())
            val degraded = running.health.value.single { it.channelId == "en" }
            assertEquals(1L, degraded.translationFailures)
            assertEquals(0L, degraded.translationRecoveries)
            assertEquals(TranslationWorkerState.DEGRADED, degraded.translationState)

            utterances.emit(RecognizedUtterance(2, "second", "ko", true, 2L))
            advanceUntilIdle()

            assertEquals("en:second", english.frames.tryReceive().getOrThrow().bytes.toTextPcm())
            assertEquals("ja:second", japanese.frames.tryReceive().getOrThrow().bytes.toTextPcm())
            val recovered = running.health.value.single { it.channelId == "en" }
            assertEquals(2L, recovered.lastCompletedSequence)
            assertEquals(1L, recovered.translationRecoveries)
            assertEquals(0L, recovered.synthesisRecoveries)
            assertEquals(TranslationWorkerState.IDLE, recovered.translationState)
            assertEquals(null, recovered.lastError)
            val unaffected = running.health.value.single { it.channelId == "ja" }
            assertEquals(0L, unaffected.translationFailures)
            assertEquals(0L, unaffected.translationRecoveries)
            running.close()
            english.close()
            japanese.close()
        }

    @Test
    fun `transient speech engine lookup failure recovers on next translated sentence`() = runTest {
        var speechLookups = 0
        val streams = AudioStreamRegistry()
        val utterances = MutableSharedFlow<RecognizedUtterance>(extraBufferCapacity = 2)
        val running = TranslationBroadcastPipeline(
            streams = streams,
            translationEngines = TranslationEngineProvider {
                TextTranslationEngine { text, _, _ -> text }
            },
            speechEngines = SpeechSynthesisEngineProvider {
                if (++speechLookups == 1) error("speech worker reconnecting")
                object : SpeechSynthesisEngine {
                    override fun synthesize(text: String, languageTag: String) =
                        flowOf(PcmAudioFrame(text.encodeToByteArray().toEvenPcm(), 1L))
                }
            },
        ).start(
            this,
            utterances,
            listOf(TranslationTarget("en", "English", "en", 24_000)),
        )
        val listener = streams.subscribe("en")
        runCurrent()

        utterances.emit(RecognizedUtterance(1, "첫 문장", "ko", true, 1L))
        runCurrent()
        assertTrue(listener.frames.tryReceive().isFailure)

        utterances.emit(RecognizedUtterance(2, "둘째 문장", "ko", true, 2L))
        advanceUntilIdle()

        assertEquals("둘째 문장", listener.frames.tryReceive().getOrThrow().bytes.toTextPcm())
        val health = running.health.value.single()
        assertEquals(2L, health.lastCompletedSequence)
        assertEquals(null, health.lastError)
        running.close()
        listener.close()
    }

    @Test
    fun `provider cancellation degrades only that speech item and next sentence recovers`() =
        runTest {
            var englishCalls = 0
            val streams = AudioStreamRegistry()
            val utterances = MutableSharedFlow<RecognizedUtterance>(extraBufferCapacity = 2)
            val running = TranslationBroadcastPipeline(
                streams = streams,
                translationEngines = TranslationEngineProvider { target ->
                    TextTranslationEngine { text, _, _ -> "$target:$text" }
                },
                speechEngines = SpeechSynthesisEngineProvider { target ->
                    object : SpeechSynthesisEngine {
                        override fun synthesize(text: String, languageTag: String): Flow<PcmAudioFrame> =
                            flow {
                                if (target == "en" && ++englishCalls == 1) {
                                    throw CancellationException("English TTS cancelled one task")
                                }
                                emit(PcmAudioFrame(text.encodeToByteArray().toEvenPcm(), 1L))
                            }
                    }
                },
            ).start(
                this,
                utterances,
                listOf("en", "ja").map { TranslationTarget(it, it, it, 24_000) },
            )
            val english = streams.subscribe("en")
            val japanese = streams.subscribe("ja")
            runCurrent()

            utterances.emit(RecognizedUtterance(1, "first", "ko", true, 1L))
            runCurrent()
            assertTrue(english.frames.tryReceive().isFailure)
            assertEquals("ja:first", japanese.frames.tryReceive().getOrThrow().bytes.toTextPcm())
            val degraded = running.health.value.single { it.channelId == "en" }
            assertEquals(1L, degraded.synthesisFailures)
            assertEquals(0L, degraded.synthesisRecoveries)
            assertEquals(TranslationWorkerState.DEGRADED, degraded.synthesisState)

            utterances.emit(RecognizedUtterance(2, "second", "ko", true, 2L))
            advanceUntilIdle()

            assertEquals("en:second", english.frames.tryReceive().getOrThrow().bytes.toTextPcm())
            assertEquals("ja:second", japanese.frames.tryReceive().getOrThrow().bytes.toTextPcm())
            val recovered = running.health.value.single { it.channelId == "en" }
            assertEquals(2L, recovered.lastCompletedSequence)
            assertEquals(0L, recovered.translationRecoveries)
            assertEquals(1L, recovered.synthesisRecoveries)
            assertEquals(TranslationWorkerState.IDLE, recovered.synthesisState)
            assertEquals(null, recovered.lastError)
            val unaffected = running.health.value.single { it.channelId == "ja" }
            assertEquals(0L, unaffected.synthesisFailures)
            assertEquals(0L, unaffected.synthesisRecoveries)
            running.close()
            english.close()
            japanese.close()
        }

    @Test
    fun `five channels isolate simultaneous translation and speech faults then recover independently`() =
        runTest {
            val languages = listOf("en", "ja", "zh", "nl", "es")
            val translationCalls = mutableMapOf<String, Int>()
            val synthesisCalls = mutableMapOf<String, Int>()
            val streams = AudioStreamRegistry()
            val utterances = MutableSharedFlow<RecognizedUtterance>(extraBufferCapacity = 2)
            val running = TranslationBroadcastPipeline(
                streams = streams,
                translationEngines = TranslationEngineProvider { target ->
                    TextTranslationEngine { text, _, _ ->
                        val call = translationCalls.getOrDefault(target, 0) + 1
                        translationCalls[target] = call
                        if (target == "en" && call == 1) {
                            error("English translator restarted")
                        }
                        "$target:$text"
                    }
                },
                speechEngines = SpeechSynthesisEngineProvider { target ->
                    object : SpeechSynthesisEngine {
                        override fun synthesize(
                            text: String,
                            languageTag: String,
                        ): Flow<PcmAudioFrame> = flow {
                            val call = synthesisCalls.getOrDefault(target, 0) + 1
                            synthesisCalls[target] = call
                            if (target == "zh" && call == 1) {
                                error("Chinese voice worker restarted")
                            }
                            emit(PcmAudioFrame(text.encodeToByteArray().toEvenPcm(), 1L))
                        }
                    }
                },
            ).start(
                this,
                utterances,
                languages.map { TranslationTarget(it, it, it, 24_000) },
            )
            val listeners = languages.associateWith { running.streamSession.subscribe(it) }
            runCurrent()

            utterances.emit(RecognizedUtterance(1, "first", "ko", true, 1L))
            advanceUntilIdle()

            assertTrue(listeners.getValue("en").frames.tryReceive().isFailure)
            assertTrue(listeners.getValue("zh").frames.tryReceive().isFailure)
            listOf("ja", "nl", "es").forEach { language ->
                assertEquals(
                    "$language:first",
                    listeners.getValue(language).frames.tryReceive().getOrThrow().bytes.toTextPcm(),
                )
            }
            assertEquals(
                setOf("en", "zh"),
                running.health.value.filter { it.lastError != null }.map { it.channelId }.toSet(),
            )

            utterances.emit(RecognizedUtterance(2, "second", "ko", true, 2L))
            advanceUntilIdle()

            languages.forEach { language ->
                assertEquals(
                    "$language:second",
                    listeners.getValue(language).frames.tryReceive().getOrThrow().bytes.toTextPcm(),
                )
            }
            val health = running.health.value.associateBy { it.channelId }
            assertEquals(1L, health.getValue("en").translationFailures)
            assertEquals(1L, health.getValue("en").translationRecoveries)
            assertEquals(0L, health.getValue("en").synthesisFailures)
            assertEquals(1L, health.getValue("zh").synthesisFailures)
            assertEquals(1L, health.getValue("zh").synthesisRecoveries)
            assertEquals(0L, health.getValue("zh").translationFailures)
            listOf("ja", "nl", "es").forEach { language ->
                val channel = health.getValue(language)
                assertEquals(0L, channel.translationFailures)
                assertEquals(0L, channel.translationRecoveries)
                assertEquals(0L, channel.synthesisFailures)
                assertEquals(0L, channel.synthesisRecoveries)
            }
            assertTrue(health.values.all { it.lastCompletedSequence == 2L && it.lastError == null })

            running.close()
            listeners.values.forEach { it.close() }
        }

    @Test
    fun `rejects more than seven target languages`() = runTest {
        val pipeline = TranslationBroadcastPipeline(
            AudioStreamRegistry(),
            TranslationEngineProvider { TextTranslationEngine { text, _, _ -> text } },
            SpeechSynthesisEngineProvider {
                object : SpeechSynthesisEngine {
                    override fun synthesize(text: String, languageTag: String) =
                        flowOf(PcmAudioFrame(byteArrayOf(0, 0), 1L))
                }
            },
        )

        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            pipeline.start(
                this,
                MutableSharedFlow(),
                listOf("en", "ja", "zh", "zh-TW", "vi", "nl", "de", "es").map {
                    TranslationTarget(it, it, "$it-${it.uppercase()}", 16_000)
                },
            )
        }
    }

    @Test
    fun `five ordered channels isolate one language translation failure`() = runTest {
        val languageTags = listOf("en", "ja", "zh", "nl", "es")
        val streams = AudioStreamRegistry()
        val utterances = MutableSharedFlow<RecognizedUtterance>(extraBufferCapacity = 1)
        val running = TranslationBroadcastPipeline(
            streams = streams,
            translationEngines = TranslationEngineProvider { target ->
                TextTranslationEngine { text, _, _ ->
                    if (target == "ja") error("Japanese model unavailable")
                    "$target:$text"
                }
            },
            speechEngines = SpeechSynthesisEngineProvider {
                object : SpeechSynthesisEngine {
                    override fun synthesize(text: String, languageTag: String) =
                        flowOf(PcmAudioFrame(text.encodeToByteArray().toEvenPcm(), 1L))
                }
            },
        ).start(
            scope = this,
            utterances = utterances,
            targets = languageTags.map { TranslationTarget(it, it, it, 24_000) },
            sourceLanguageTag = "ko-KR",
        )
        val listeners = languageTags.associateWith(streams::subscribe)
        runCurrent()

        utterances.emit(RecognizedUtterance(1, "평화 걷기를 시작합니다", "ko-KR", true, 1L))
        advanceUntilIdle()

        assertEquals(languageTags, streams.channels.value.map { it.id })
        assertEquals(languageTags, running.health.value.map { it.channelId })
        languageTags.filterNot { it == "ja" }.forEach { languageTag ->
            assertEquals(
                "$languageTag:평화 걷기를 시작합니다",
                requireNotNull(listeners[languageTag]).frames.tryReceive().getOrThrow()
                    .bytes.toTextPcm(),
            )
            val health = running.health.value.single { it.channelId == languageTag }
            assertEquals(1L, health.lastTranslatedSequence)
            assertEquals(1L, health.lastCompletedSequence)
            assertEquals(TranslationWorkerState.IDLE, health.translationState)
            assertEquals(TranslationWorkerState.IDLE, health.synthesisState)
        }
        assertTrue(requireNotNull(listeners["ja"]).frames.tryReceive().isFailure)
        val japaneseHealth = running.health.value.single { it.channelId == "ja" }
        assertEquals(1L, japaneseHealth.translationFailures)
        assertEquals(TranslationWorkerState.DEGRADED, japaneseHealth.translationState)
        assertTrue(japaneseHealth.lastError.orEmpty().contains("Japanese model unavailable"))

        running.close()
        listeners.values.forEach { it.close() }
    }

    @Test
    fun `five ordered channels isolate one language speech failure and recover next sentence`() =
        runTest {
            val languageTags = listOf("en", "ja", "zh", "nl", "es")
            val streams = AudioStreamRegistry()
            val utterances = MutableSharedFlow<RecognizedUtterance>(extraBufferCapacity = 2)
            val running = TranslationBroadcastPipeline(
                streams = streams,
                translationEngines = TranslationEngineProvider {
                    TextTranslationEngine { text, _, target -> "$target:$text" }
                },
                speechEngines = SpeechSynthesisEngineProvider { target ->
                    object : SpeechSynthesisEngine {
                        override fun synthesize(text: String, languageTag: String) = flow {
                            if (target == "ja" && text.endsWith(":첫 문장")) {
                                error("Japanese voice worker unavailable")
                            }
                            emit(PcmAudioFrame(text.encodeToByteArray().toEvenPcm(), 1L))
                        }
                    }
                },
            ).start(
                scope = this,
                utterances = utterances,
                targets = languageTags.map { TranslationTarget(it, it, it, 24_000) },
                sourceLanguageTag = "ko-KR",
            )
            val listeners = languageTags.associateWith(streams::subscribe)
            runCurrent()

            utterances.emit(RecognizedUtterance(1, "첫 문장", "ko-KR", true, 1L))
            advanceUntilIdle()

            languageTags.filterNot { it == "ja" }.forEach { languageTag ->
                assertEquals(
                    "$languageTag:첫 문장",
                    requireNotNull(listeners[languageTag]).frames.tryReceive().getOrThrow()
                        .bytes.toTextPcm(),
                )
                assertEquals(
                    1L,
                    running.health.value.single { it.channelId == languageTag }
                        .lastCompletedSequence,
                )
            }
            assertTrue(requireNotNull(listeners["ja"]).frames.tryReceive().isFailure)
            val failedJapanese = running.health.value.single { it.channelId == "ja" }
            assertEquals(1L, failedJapanese.synthesisFailures)
            assertEquals(TranslationWorkerState.DEGRADED, failedJapanese.synthesisState)
            assertTrue(
                failedJapanese.lastSynthesisError.orEmpty()
                    .contains("Japanese voice worker unavailable"),
            )

            utterances.emit(RecognizedUtterance(2, "둘째 문장", "ko-KR", true, 2L))
            advanceUntilIdle()

            languageTags.forEach { languageTag ->
                assertEquals(
                    "$languageTag:둘째 문장",
                    requireNotNull(listeners[languageTag]).frames.tryReceive().getOrThrow()
                        .bytes.toTextPcm(),
                )
                val recovered = running.health.value.single { it.channelId == languageTag }
                assertEquals(2L, recovered.lastCompletedSequence)
                assertEquals(TranslationWorkerState.IDLE, recovered.synthesisState)
            }
            assertEquals(1L, running.health.value.single { it.channelId == "ja" }.synthesisFailures)

            running.close()
            listeners.values.forEach { it.close() }
        }

    @Test
    fun `rejects duplicate target languages even when channel ids differ`() = runTest {
        val pipeline = TranslationBroadcastPipeline(
            AudioStreamRegistry(),
            TranslationEngineProvider { TextTranslationEngine { text, _, _ -> text } },
            SpeechSynthesisEngineProvider {
                object : SpeechSynthesisEngine {
                    override fun synthesize(text: String, languageTag: String) =
                        flowOf(PcmAudioFrame(audibleTestPcm(), 1L))
                }
            },
        )

        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            pipeline.start(
                this,
                MutableSharedFlow(),
                listOf(
                    TranslationTarget("english-us", "English", "en-US", 24_000),
                    TranslationTarget("english-copy", "English copy", "EN-us", 24_000),
                ),
            )
        }
    }

    @Test
    fun `rejects a configured target equal to the source language`() = runTest {
        val pipeline = TranslationBroadcastPipeline(
            AudioStreamRegistry(),
            TranslationEngineProvider { TextTranslationEngine { text, _, _ -> text } },
            SpeechSynthesisEngineProvider {
                object : SpeechSynthesisEngine {
                    override fun synthesize(text: String, languageTag: String) =
                        flowOf(PcmAudioFrame(audibleTestPcm(), 1L))
                }
            },
        )

        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            pipeline.start(
                scope = this,
                utterances = MutableSharedFlow(),
                targets = listOf(TranslationTarget("ko", "한국어", "ko", 24_000)),
                sourceLanguageTag = "ko-KR",
            )
        }
    }

    @Test
    fun `a dynamic source equal to one target rejects only that target`() = runTest {
        var translations = 0
        val streams = AudioStreamRegistry()
        val utterances = MutableSharedFlow<RecognizedUtterance>(extraBufferCapacity = 1)
        val running = TranslationBroadcastPipeline(
            streams,
            TranslationEngineProvider {
                TextTranslationEngine { text, _, target ->
                    translations += 1
                    "$target:$text"
                }
            },
            SpeechSynthesisEngineProvider {
                object : SpeechSynthesisEngine {
                    override fun synthesize(text: String, languageTag: String) =
                        flowOf(PcmAudioFrame(text.encodeToByteArray().toEvenPcm(), 1L))
                }
            },
        ).start(
            this,
            utterances,
            listOf(
                TranslationTarget("ko", "한국어", "ko", 24_000),
                TranslationTarget("en", "English", "en", 24_000),
            ),
        )
        val korean = streams.subscribe("ko")
        val english = streams.subscribe("en")
        runCurrent()

        utterances.emit(RecognizedUtterance(1, "안내", "ko-KR", true, 1L))
        advanceUntilIdle()

        assertEquals(1, translations)
        assertTrue(korean.frames.tryReceive().isFailure)
        assertEquals("en:안내", english.frames.tryReceive().getOrThrow().bytes.toTextPcm())
        val koreanHealth = running.health.value.single { it.channelId == "ko" }
        assertEquals(1L, koreanHealth.translationFailures)
        assertEquals(TranslationWorkerState.DEGRADED, koreanHealth.translationState)
        assertEquals(1L, running.health.value.single { it.channelId == "en" }.lastCompletedSequence)

        running.close()
        korean.close()
        english.close()
    }

    @Test
    fun `rejects an unbounded recognition result`() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            RecognizedUtterance(
                sequence = 1,
                text = "가".repeat(2_001),
                sourceLanguageTag = "ko-KR",
                isFinal = true,
                capturedAtElapsedRealtimeNanos = 1L,
            )
        }
    }

    @Test
    fun `accepts bounded note9 first-audio recovery deadline`() {
        TranslationBroadcastPipeline(
            streams = AudioStreamRegistry(),
            translationEngines = TranslationEngineProvider {
                TextTranslationEngine { text, _, _ -> text }
            },
            speechEngines = SpeechSynthesisEngineProvider {
                object : SpeechSynthesisEngine {
                    override fun synthesize(text: String, languageTag: String) =
                        emptyFlow<PcmAudioFrame>()
                }
            },
            firstAudioTimeoutMillis = 65_000L,
            synthesisFrameIdleTimeoutMillis = 15_000L,
        )
    }

    @Test
    fun `reports source translation and completed speech to advisory observer`() = runTest {
        val events = mutableListOf<String>()
        val streams = AudioStreamRegistry()
        val utterances = MutableSharedFlow<RecognizedUtterance>(extraBufferCapacity = 1)
        val running = TranslationBroadcastPipeline(
            streams = streams,
            translationEngines = TranslationEngineProvider {
                TextTranslationEngine { text, _, _ -> "hello:$text" }
            },
            speechEngines = SpeechSynthesisEngineProvider {
                object : SpeechSynthesisEngine {
                    override fun synthesize(text: String, languageTag: String) =
                        flowOf(PcmAudioFrame(audibleTestPcm(), 1L))
                }
            },
            observer = object : TranslationPipelineObserver {
                override fun onSourceRecognized(utterance: RecognizedUtterance) {
                    events += "source:${utterance.text}"
                }

                override fun onTranslationCompleted(
                    utterance: RecognizedUtterance,
                    target: TranslationTarget,
                    translatedText: String,
                    elapsedMillis: Long,
                ) {
                    events += "translation:$translatedText"
                }

                override fun onSynthesisCompleted(
                    utterance: RecognizedUtterance,
                    target: TranslationTarget,
                    elapsedMillis: Long,
                ) {
                    events += "speech:${target.channelId}"
                }
            },
        ).start(
            this,
            utterances,
            listOf(TranslationTarget("en", "English", "en-US", 24_000)),
        )
        yield()

        utterances.emit(RecognizedUtterance(7, "안녕하세요", "ko-KR", true, 1L))
        advanceUntilIdle()

        assertEquals(
            listOf("source:안녕하세요", "translation:hello:안녕하세요", "speech:en"),
            events,
        )
        running.close()
    }

    @Test
    fun `reports interim speech immediately but translates only the final sentence`() = runTest {
        val sourceEvents = mutableListOf<RecognizedUtterance>()
        var translationCalls = 0
        val utterances = MutableSharedFlow<RecognizedUtterance>(extraBufferCapacity = 2)
        val running = TranslationBroadcastPipeline(
            streams = AudioStreamRegistry(),
            translationEngines = TranslationEngineProvider {
                TextTranslationEngine { text, _, _ ->
                    translationCalls += 1
                    text
                }
            },
            speechEngines = SpeechSynthesisEngineProvider {
                object : SpeechSynthesisEngine {
                    override fun synthesize(text: String, languageTag: String) =
                        flowOf(PcmAudioFrame(audibleTestPcm(), 1L))
                }
            },
            observer = object : TranslationPipelineObserver {
                override fun onSourceRecognized(utterance: RecognizedUtterance) {
                    sourceEvents += utterance
                }
            },
        ).start(
            this,
            utterances,
            listOf(TranslationTarget("en", "English", "en", 24_000)),
        )
        yield()

        utterances.emit(RecognizedUtterance(3, "안녕하", "ko-KR", false, 1L))
        advanceUntilIdle()
        assertEquals(listOf("안녕하"), sourceEvents.map { it.text })
        assertEquals(0, translationCalls)

        utterances.emit(RecognizedUtterance(3, "안녕하세요", "ko-KR", true, 1L))
        advanceUntilIdle()
        assertEquals(listOf("안녕하", "안녕하세요"), sourceEvents.map { it.text })
        assertEquals(1, translationCalls)
        running.close()
    }

    @Test
    fun `retraction reaches transcript observer but never enters translation queue`() = runTest {
        val sourceEvents = mutableListOf<RecognizedUtterance>()
        var translationCalls = 0
        val utterances = MutableSharedFlow<RecognizedUtterance>(extraBufferCapacity = 1)
        val running = TranslationBroadcastPipeline(
            streams = AudioStreamRegistry(),
            translationEngines = TranslationEngineProvider {
                TextTranslationEngine { text, _, _ ->
                    translationCalls += 1
                    text
                }
            },
            speechEngines = SpeechSynthesisEngineProvider {
                object : SpeechSynthesisEngine {
                    override fun synthesize(text: String, languageTag: String) = emptyFlow<PcmAudioFrame>()
                }
            },
            observer = object : TranslationPipelineObserver {
                override fun onSourceRecognized(utterance: RecognizedUtterance) {
                    sourceEvents += utterance
                }
            },
        ).start(
            this,
            utterances,
            listOf(TranslationTarget("en", "English", "en", 24_000)),
        )
        yield()

        utterances.emit(
            RecognizedUtterance(9, "", "ko-KR", false, 1L, isRetracted = true),
        )
        advanceUntilIdle()

        assertTrue(sourceEvents.single().isRetracted)
        assertEquals(0, translationCalls)
        running.close()
    }

    @Test
    fun `duplicate final sequence is translated and spoken exactly once`() = runTest {
        var translationCalls = 0
        val utterances = MutableSharedFlow<RecognizedUtterance>(extraBufferCapacity = 2)
        val running = TranslationBroadcastPipeline(
            streams = AudioStreamRegistry(),
            translationEngines = TranslationEngineProvider {
                TextTranslationEngine { text, _, _ ->
                    translationCalls += 1
                    text
                }
            },
            speechEngines = SpeechSynthesisEngineProvider {
                object : SpeechSynthesisEngine {
                    override fun synthesize(text: String, languageTag: String) =
                        flowOf(PcmAudioFrame(audibleTestPcm(), 1L))
                }
            },
        ).start(
            this,
            utterances,
            listOf(TranslationTarget("en", "English", "en", 24_000)),
        )
        yield()

        val final = RecognizedUtterance(22, "중복 금지", "ko-KR", true, 1L)
        utterances.emit(final)
        utterances.emit(final)
        advanceUntilIdle()

        assertEquals(1, translationCalls)
        running.close()
    }

    @Test
    fun `empty synthesis Flow is reported as failed speech`() = runTest {
        val health = runPcmFailureTest(emptyFlow())

        assertEquals(null, health.lastSynthesisPcm)
        assertEquals(null, health.lastCompletedSequence)
        assertTrue(requireNotNull(health.lastError).contains("비무음 PCM"))
    }

    @Test
    fun `all-zero synthesis is not accepted as completed speech`() = runTest {
        val health = runPcmFailureTest(
            flowOf(PcmAudioFrame(ByteArray(640), 1L)),
        )

        assertEquals(null, health.lastSynthesisPcm)
        assertEquals(null, health.lastCompletedSequence)
        assertTrue(requireNotNull(health.lastError).contains("비무음 PCM"))
    }

    @Test
    fun `non-silent synthesis reports aggregate PCM and remains observable in health`() = runTest {
        val positiveHalf = byteArrayOf(0x00, 0x40)
        val negativeHalf = byteArrayOf(0x00, 0xC0.toByte())
        val result = runPcmObservationTest(
            flowOf(
                PcmAudioFrame(positiveHalf, 1L),
                PcmAudioFrame(negativeHalf, 2L),
            ),
        )

        assertEquals(2L, result.observed.frameCount)
        assertEquals(4L, result.observed.byteCount)
        assertEquals(2L, result.observed.sampleCount)
        assertEquals(2L, result.observed.nonZeroSampleCount)
        assertEquals(0.5f, result.observed.rms, 0.0001f)
        assertEquals(0.5f, result.observed.peak, 0.0001f)
        assertTrue(result.observed.isNonSilent(minimumPeak = 0.002f))
        assertEquals(result.observed, result.health.lastSynthesisPcm)
        assertEquals(null, result.health.lastError)
    }

    @Test
    fun `PCM quality metrics span frame boundaries without retaining audio`() = runTest {
        val result = runPcmObservationTest(
            flowOf(
                PcmAudioFrame(pcmSamples(0, 0, Short.MAX_VALUE.toInt()), 1L),
                PcmAudioFrame(pcmSamples(Short.MIN_VALUE.toInt(), 0, 0, 0), 2L),
            ),
        )

        assertEquals(7L, result.observed.sampleCount)
        assertEquals(2L, result.observed.nonZeroSampleCount)
        assertEquals(2f / 7f, result.observed.clippingRatio, 0.0001f)
        assertEquals(3L, result.observed.longestZeroRunSamples)
        assertTrue(result.observed.maximumBoundaryJump > 1.99f)
        assertEquals(1f, result.observed.zeroCrossingRatio, 0.0001f)
        assertTrue(result.observed.qualityWarnings(sampleRateHz = 24_000).contains("클리핑 후보"))
        assertTrue(result.observed.qualityWarnings(sampleRateHz = 24_000).contains("클릭·팝 후보"))
    }

    @Test
    fun `continues pipeline diagnostics but suppresses audio while broadcast is paused`() = runTest {
        var publishAudio = false
        val streams = AudioStreamRegistry()
        val utterances = MutableSharedFlow<RecognizedUtterance>(extraBufferCapacity = 1)
        val running = TranslationBroadcastPipeline(
            streams = streams,
            translationEngines = TranslationEngineProvider {
                TextTranslationEngine { text, _, _ -> text }
            },
            speechEngines = SpeechSynthesisEngineProvider {
                object : SpeechSynthesisEngine {
                    override fun synthesize(text: String, languageTag: String) =
                        flowOf(PcmAudioFrame(audibleTestPcm(), 1L))
                }
            },
            shouldPublishAudio = { publishAudio },
        ).start(
            this,
            utterances,
            listOf(TranslationTarget("en", "English", "en-US", 24_000)),
        )
        val listener = streams.subscribe("en")
        yield()

        utterances.emit(RecognizedUtterance(1, "paused", "ko-KR", true, 1L))
        advanceUntilIdle()
        assertTrue(listener.frames.tryReceive().isFailure)
        assertEquals(1L, running.health.value.single().lastSynthesizedSequence)
        assertEquals(null, running.health.value.single().lastPublishedSequence)
        assertEquals(null, running.health.value.single().lastCompletedSequence)

        publishAudio = true
        utterances.emit(RecognizedUtterance(2, "live", "ko-KR", true, 2L))
        advanceUntilIdle()
        assertEquals(audibleTestPcm().toList(), listener.frames.receive().bytes.toList())
        assertEquals(2L, running.health.value.single().lastPublishedSequence)
        assertEquals(1L, running.health.value.single().publishedFrameCount)
        running.close()
        listener.close()
    }

    @Test
    fun `bounded queue preserves every finalized utterance under translation backpressure`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val streams = AudioStreamRegistry()
        val utterances = MutableSharedFlow<RecognizedUtterance>(extraBufferCapacity = 4)
        val pipeline = TranslationBroadcastPipeline(
            streams = streams,
            translationEngines = TranslationEngineProvider {
                TextTranslationEngine { text, _, _ ->
                    if (text == "첫 문장") gate.await()
                    text
                }
            },
            speechEngines = SpeechSynthesisEngineProvider {
                object : SpeechSynthesisEngine {
                    override fun synthesize(text: String, languageTag: String) =
                        flowOf(PcmAudioFrame(text.encodeToByteArray().toEvenPcm(), 1L))
                }
            },
            queueCapacityPerLanguage = 1,
        )
        val running = pipeline.start(
            scope = this,
            utterances = utterances,
            targets = listOf(TranslationTarget("en", "English", "en-US", 16_000)),
        )
        yield()

        utterances.emit(RecognizedUtterance(1, "첫 문장", "ko-KR", true, 1L))
        yield()
        utterances.emit(RecognizedUtterance(2, "오래된 문장", "ko-KR", true, 2L))
        utterances.emit(RecognizedUtterance(3, "최신 문장", "ko-KR", true, 3L))
        yield()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(0L, running.health.value.single().droppedUtterances)
        assertEquals(3L, running.health.value.single().lastCompletedSequence)
        running.close()
    }

    @Test
    fun `hung translation times out and the newest queued sentence continues`() = runTest {
        val utterances = MutableSharedFlow<RecognizedUtterance>(extraBufferCapacity = 2)
        val running = TranslationBroadcastPipeline(
            streams = AudioStreamRegistry(),
            translationEngines = TranslationEngineProvider {
                TextTranslationEngine { text, _, _ ->
                    if (text == "멈춘 문장") delay(Long.MAX_VALUE)
                    text
                }
            },
            speechEngines = SpeechSynthesisEngineProvider {
                object : SpeechSynthesisEngine {
                    override fun synthesize(text: String, languageTag: String) =
                        flowOf(PcmAudioFrame(audibleTestPcm(), 1L))
                }
            },
            translationTimeoutMillis = 500,
        ).start(
            this,
            utterances,
            listOf(TranslationTarget("en", "English", "en", 24_000)),
        )
        yield()

        utterances.emit(RecognizedUtterance(1, "멈춘 문장", "ko-KR", true, 1L))
        yield()
        utterances.emit(RecognizedUtterance(2, "최신 문장", "ko-KR", true, 2L))
        advanceTimeBy(501)
        advanceUntilIdle()

        val health = running.health.value.single()
        assertEquals(1L, health.droppedUtterances)
        assertEquals(2L, health.lastCompletedSequence)
        assertEquals(null, health.lastError)
        running.close()
    }

    @Test
    fun `priority translation timeout does not relax independent channel deadline`() = runTest {
        val utterances = MutableSharedFlow<RecognizedUtterance>(extraBufferCapacity = 1)
        val running = TranslationBroadcastPipeline(
            streams = AudioStreamRegistry(),
            translationEngines = TranslationEngineProvider {
                TextTranslationEngine { text, _, _ ->
                    delay(750)
                    text
                }
            },
            speechEngines = SpeechSynthesisEngineProvider {
                object : SpeechSynthesisEngine {
                    override fun synthesize(text: String, languageTag: String) =
                        flowOf(PcmAudioFrame(audibleTestPcm(), 1L))
                }
            },
            translationTimeoutMillis = 500,
            translationTimeoutMillisByChannel = mapOf("en" to 1_000),
        ).start(
            this,
            utterances,
            listOf(
                TranslationTarget("en", "English", "en", 24_000),
                TranslationTarget("ja", "Japanese", "ja", 24_000),
            ),
        )
        runCurrent()

        utterances.emit(RecognizedUtterance(1, "분리 기한", "ko-KR", true, 1L))
        advanceTimeBy(501)
        runCurrent()

        val japaneseAtDeadline = running.health.value.single { it.channelId == "ja" }
        val englishAtDeadline = running.health.value.single { it.channelId == "en" }
        assertEquals(TranslationWorkerState.DEGRADED, japaneseAtDeadline.translationState)
        assertEquals(1L, japaneseAtDeadline.translationFailures)
        assertEquals(TranslationWorkerState.ACTIVE, englishAtDeadline.translationState)

        advanceTimeBy(250)
        advanceUntilIdle()
        assertEquals(
            1L,
            running.health.value.single { it.channelId == "en" }.lastCompletedSequence,
        )
        running.close()
    }

    @Test
    fun `translated script remains queued for speech after capture deadline`() = runTest {
        val streams = AudioStreamRegistry()
        val utterances = MutableSharedFlow<RecognizedUtterance>(extraBufferCapacity = 1)
        val running = TranslationBroadcastPipeline(
            streams = streams,
            translationEngines = TranslationEngineProvider {
                TextTranslationEngine { text, _, _ -> text }
            },
            speechEngines = SpeechSynthesisEngineProvider {
                object : SpeechSynthesisEngine {
                    override fun synthesize(text: String, languageTag: String) =
                        kotlinx.coroutines.flow.flow {
                            delay(1_000)
                            emit(PcmAudioFrame(audibleTestPcm(), 1L))
                        }
                }
            },
            currentElapsedRealtimeNanos = { testScheduler.currentTime * 1_000_000L },
        ).start(
            this,
            utterances,
            listOf(TranslationTarget("en", "English", "en", 24_000)),
        )
        val listener = streams.subscribe("en")
        yield()

        utterances.emit(
            RecognizedUtterance(
                sequence = 3,
                text = "기한 시험",
                sourceLanguageTag = "ko-KR",
                isFinal = true,
                capturedAtElapsedRealtimeNanos = 0,
                firstAudioDeadlineElapsedRealtimeNanos = 600L.ms,
            ),
        )
        advanceTimeBy(601)
        advanceUntilIdle()

        val health = running.health.value.single()
        assertEquals(0L, health.droppedUtterances)
        assertEquals(3L, health.lastCompletedSequence)
        assertEquals(null, health.lastError)
        assertEquals(audibleTestPcm().toList(), listener.frames.tryReceive().getOrThrow().bytes.toList())
        running.close()
        listener.close()
    }

    @Test
    fun `successful translation receives an independent bounded speech window`() = runTest {
        val streams = AudioStreamRegistry()
        val utterances = MutableSharedFlow<RecognizedUtterance>(extraBufferCapacity = 1)
        val running = TranslationBroadcastPipeline(
            streams = streams,
            translationEngines = TranslationEngineProvider {
                TextTranslationEngine { text, _, _ ->
                    delay(400)
                    text
                }
            },
            speechEngines = SpeechSynthesisEngineProvider {
                object : SpeechSynthesisEngine {
                    override fun synthesize(text: String, languageTag: String) = flow {
                        delay(300)
                            emit(PcmAudioFrame(audibleTestPcm(), 1L))
                    }
                }
            },
            currentElapsedRealtimeNanos = { testScheduler.currentTime * 1_000_000L },
        ).start(
            this,
            utterances,
            listOf(TranslationTarget("en", "English", "en", 24_000)),
        )
        val listener = streams.subscribe("en")
        runCurrent()

        utterances.emit(
            RecognizedUtterance(
                sequence = 4,
                text = "누적 기한 시험",
                sourceLanguageTag = "ko-KR",
                isFinal = true,
                capturedAtElapsedRealtimeNanos = 0,
                firstAudioDeadlineElapsedRealtimeNanos = 600L.ms,
            ),
        )
        advanceUntilIdle()

        val health = running.health.value.single()
        assertEquals(700L, testScheduler.currentTime)
        assertEquals(0L, health.droppedUtterances)
        assertEquals(4L, health.lastCompletedSequence)
        assertEquals(audibleTestPcm().toList(), listener.frames.tryReceive().getOrThrow().bytes.toList())
        running.close()
        listener.close()
    }

    @Test
    fun `slow TTS in one of four languages does not block other languages or later segments`() =
        runTest {
            val slowJapanese = CompletableDeferred<Unit>()
            val streams = AudioStreamRegistry()
            val utterances = MutableSharedFlow<RecognizedUtterance>(extraBufferCapacity = 2)
            val running = TranslationBroadcastPipeline(
                streams = streams,
                translationEngines = TranslationEngineProvider {
                    TextTranslationEngine { text, _, target -> "$target:$text" }
                },
                speechEngines = SpeechSynthesisEngineProvider {
                    object : SpeechSynthesisEngine {
                        override fun synthesize(text: String, languageTag: String) = flow {
                            if (languageTag == "ja" && text.endsWith(":첫 문장")) {
                                slowJapanese.await()
                            }
                            emit(PcmAudioFrame(text.encodeToByteArray().toEvenPcm(), 1L))
                        }
                    }
                },
            ).start(
                scope = this,
                utterances = utterances,
                targets = listOf("en", "ja", "zh", "nl").map { languageTag ->
                    TranslationTarget(languageTag, languageTag, languageTag, 24_000)
                },
            )
            val listeners = listOf("en", "ja", "zh", "nl").associateWith(streams::subscribe)
            runCurrent()

            utterances.emit(RecognizedUtterance(1, "첫 문장", "ko", true, 1L))
            runCurrent()
            utterances.emit(RecognizedUtterance(2, "둘째 문장", "ko", true, 2L))
            runCurrent()

            listOf("en", "zh", "nl").forEach { languageTag ->
                val listener = requireNotNull(listeners[languageTag])
                assertEquals("$languageTag:첫 문장", listener.frames.tryReceive().getOrThrow().bytes.toTextPcm())
                assertEquals("$languageTag:둘째 문장", listener.frames.tryReceive().getOrThrow().bytes.toTextPcm())
            }
            assertTrue(requireNotNull(listeners["ja"]).frames.tryReceive().isFailure)
            assertEquals(
                2L,
                running.health.value.single { it.channelId == "en" }.lastCompletedSequence,
            )
            assertEquals(
                null,
                running.health.value.single { it.channelId == "ja" }.lastCompletedSequence,
            )

            slowJapanese.complete(Unit)
            advanceUntilIdle()

            val japanese = requireNotNull(listeners["ja"])
            assertEquals("ja:첫 문장", japanese.frames.tryReceive().getOrThrow().bytes.toTextPcm())
            assertEquals("ja:둘째 문장", japanese.frames.tryReceive().getOrThrow().bytes.toTextPcm())
            assertTrue(running.health.value.all { it.lastCompletedSequence == 2L })
            running.close()
            listeners.values.forEach { it.close() }
        }

    @Test
    fun `a saturated language drops only its oldest backlog and never blocks the other channels`() =
        runTest {
            val slowJapanese = CompletableDeferred<Unit>()
            val streams = AudioStreamRegistry()
            val utterances = MutableSharedFlow<RecognizedUtterance>(extraBufferCapacity = 16)
            val running = TranslationBroadcastPipeline(
                streams = streams,
                translationEngines = TranslationEngineProvider {
                    TextTranslationEngine { text, _, target -> "$target:$text" }
                },
                speechEngines = SpeechSynthesisEngineProvider {
                    object : SpeechSynthesisEngine {
                        override fun synthesize(text: String, languageTag: String) = flow {
                            if (languageTag == "ja" && text.endsWith(":문장 1")) {
                                slowJapanese.await()
                            }
                            emit(PcmAudioFrame(text.encodeToByteArray().toEvenPcm(), 1L))
                        }
                    }
                },
                queueCapacityPerLanguage = 1,
                speechQueueCapacityPerLanguage = 1,
            ).start(
                scope = this,
                utterances = utterances,
                targets = listOf("en", "ja", "zh", "nl", "es").map { languageTag ->
                    TranslationTarget(languageTag, languageTag, languageTag, 24_000)
                },
            )
            runCurrent()

            (1L..12L).forEach { sequence ->
                utterances.emit(
                    RecognizedUtterance(
                        sequence,
                        "문장 $sequence",
                        "ko",
                        true,
                        sequence,
                    ),
                )
                runCurrent()
            }

            listOf("en", "zh", "nl", "es").forEach { languageTag ->
                assertEquals(
                    12L,
                    running.health.value.single { it.channelId == languageTag }
                        .lastCompletedSequence,
                )
            }
            val stalled = running.health.value.single { it.channelId == "ja" }
            assertTrue(stalled.droppedUtterances > 0L)
            assertTrue(stalled.lastError.orEmpty().contains("최신 안내"))

            slowJapanese.complete(Unit)
            advanceUntilIdle()

            assertEquals(
                12L,
                running.health.value.single { it.channelId == "ja" }.lastCompletedSequence,
            )
            running.close()
        }

    @Test
    fun `an already late finalized segment remains translated and spoken`() = runTest {
        val activeGate = CompletableDeferred<Unit>()
        val translated = mutableListOf<String>()
        val utterances = MutableSharedFlow<RecognizedUtterance>(extraBufferCapacity = 3)
        val running = TranslationBroadcastPipeline(
            streams = AudioStreamRegistry(),
            translationEngines = TranslationEngineProvider {
                TextTranslationEngine { text, _, _ ->
                    if (text == "처리 중") activeGate.await()
                    translated += text
                    text
                }
            },
            speechEngines = SpeechSynthesisEngineProvider {
                object : SpeechSynthesisEngine {
                    override fun synthesize(text: String, languageTag: String) =
                        flowOf(PcmAudioFrame(audibleTestPcm(), 1L))
                }
            },
            queueCapacityPerLanguage = 1,
            currentElapsedRealtimeNanos = { testScheduler.currentTime * 1_000_000L },
        ).start(
            this,
            utterances,
            listOf(TranslationTarget("en", "English", "en", 24_000)),
        )
        runCurrent()

        utterances.emit(RecognizedUtterance(1, "처리 중", "ko", true, 0L))
        runCurrent()
        utterances.emit(
            RecognizedUtterance(
                sequence = 2,
                text = "유효한 대기 문장",
                sourceLanguageTag = "ko",
                isFinal = true,
                capturedAtElapsedRealtimeNanos = 0L,
                firstAudioDeadlineElapsedRealtimeNanos = 10_000L.ms,
            ),
        )
        runCurrent()
        advanceTimeBy(1L)
        utterances.emit(
            RecognizedUtterance(
                sequence = 3,
                text = "이미 만료된 문장",
                sourceLanguageTag = "ko",
                isFinal = true,
                capturedAtElapsedRealtimeNanos = 0L,
                firstAudioDeadlineElapsedRealtimeNanos = 500_000L,
            ),
        )
        runCurrent()

        activeGate.complete(Unit)
        advanceUntilIdle()

        val health = running.health.value.single()
        assertEquals(listOf("처리 중", "유효한 대기 문장", "이미 만료된 문장"), translated)
        assertEquals(3L, health.lastCompletedSequence)
        assertEquals(0L, health.droppedUtterances)
        assertEquals(null, health.lastError)
        running.close()
    }

    @Test
    fun `slow paced speech does not block the next subtitle translation`() = runTest {
        val firstSpeechGate = CompletableDeferred<Unit>()
        val translated = mutableListOf<String>()
        val utterances = MutableSharedFlow<RecognizedUtterance>(extraBufferCapacity = 2)
        val running = TranslationBroadcastPipeline(
            streams = AudioStreamRegistry(),
            translationEngines = TranslationEngineProvider {
                TextTranslationEngine { text, _, _ ->
                    translated += text
                    "translated:$text"
                }
            },
            speechEngines = SpeechSynthesisEngineProvider {
                object : SpeechSynthesisEngine {
                    override fun synthesize(text: String, languageTag: String) = flow {
                        if (text.endsWith("첫 문장")) firstSpeechGate.await()
                            emit(PcmAudioFrame(audibleTestPcm(), 1L))
                    }
                }
            },
        ).start(
            this,
            utterances,
            listOf(TranslationTarget("en", "English", "en", 24_000)),
        )
        runCurrent()

        utterances.emit(RecognizedUtterance(1, "첫 문장", "ko", true, 1L))
        runCurrent()
        utterances.emit(RecognizedUtterance(2, "둘째 문장", "ko", true, 2L))
        runCurrent()

        assertEquals(listOf("첫 문장", "둘째 문장"), translated)
        assertEquals(null, running.health.value.single().lastCompletedSequence)

        firstSpeechGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(2L, running.health.value.single().lastCompletedSequence)
        running.close()
    }

    @Test
    fun `stalled language drops only its oldest unspoken audio and keeps newest guidance`() =
        runTest {
            val englishFirstSpeechGate = CompletableDeferred<Unit>()
            val spoken = mutableMapOf(
                "en" to mutableListOf<String>(),
                "ja" to mutableListOf(),
            )
            val utterances = MutableSharedFlow<RecognizedUtterance>(extraBufferCapacity = 5)
            val running = TranslationBroadcastPipeline(
                streams = AudioStreamRegistry(),
                translationEngines = TranslationEngineProvider {
                    TextTranslationEngine { text, _, targetLanguage ->
                        "$targetLanguage:$text"
                    }
                },
                speechEngines = SpeechSynthesisEngineProvider {
                    object : SpeechSynthesisEngine {
                        override fun synthesize(text: String, languageTag: String) = flow {
                            spoken.getValue(languageTag) += text
                            if (languageTag == "en" && text == "en:1") {
                                englishFirstSpeechGate.await()
                            }
                            emit(PcmAudioFrame(audibleTestPcm(), 1L))
                        }
                    }
                },
                speechQueueCapacityPerLanguage = 2,
            ).start(
                this,
                utterances,
                listOf(
                    TranslationTarget("en", "English", "en", 24_000),
                    TranslationTarget("ja", "Japanese", "ja", 24_000),
                ),
            )
            runCurrent()

            (1L..5L).forEach { sequence ->
                utterances.emit(
                    RecognizedUtterance(sequence, sequence.toString(), "ko", true, sequence),
                )
                runCurrent()
            }

            val waitingHealth = running.health.value.associateBy { it.channelId }
            assertEquals(5L, waitingHealth.getValue("en").lastTranslatedSequence)
            assertEquals(2L, waitingHealth.getValue("en").droppedUtterances)
            assertEquals(2L, waitingHealth.getValue("en").synthesisFailures)
            assertTrue(
                waitingHealth.getValue("en").lastSynthesisError.orEmpty()
                    .contains("자막은 보존"),
            )
            assertEquals(5L, waitingHealth.getValue("ja").lastCompletedSequence)
            assertEquals(0L, waitingHealth.getValue("ja").droppedUtterances)
            assertEquals(listOf("ja:1", "ja:2", "ja:3", "ja:4", "ja:5"), spoken.getValue("ja"))

            englishFirstSpeechGate.complete(Unit)
            advanceUntilIdle()

            val recoveredHealth = running.health.value.associateBy { it.channelId }
            assertEquals(listOf("en:1", "en:4", "en:5"), spoken.getValue("en"))
            assertEquals(5L, recoveredHealth.getValue("en").lastCompletedSequence)
            assertEquals(1L, recoveredHealth.getValue("en").synthesisRecoveries)
            assertEquals(null, recoveredHealth.getValue("en").lastSynthesisError)
            running.close()
        }

    @Test
    fun `reports first audible frame once before synthesis completion`() = runTest {
        val events = mutableListOf<String>()
        val utterances = MutableSharedFlow<RecognizedUtterance>(extraBufferCapacity = 1)
        val running = TranslationBroadcastPipeline(
            streams = AudioStreamRegistry(),
            translationEngines = TranslationEngineProvider {
                TextTranslationEngine { text, _, _ -> text }
            },
            speechEngines = SpeechSynthesisEngineProvider {
                object : SpeechSynthesisEngine {
                    override fun synthesize(text: String, languageTag: String) = flowOf(
                        PcmAudioFrame(audibleTestPcm(), 1L),
                        PcmAudioFrame(audibleTestPcm(highByte = 0x30), 2L),
                    )
                }
            },
            observer = object : TranslationPipelineObserver {
                override fun onSynthesisAudioStarted(
                    utterance: RecognizedUtterance,
                    target: TranslationTarget,
                    elapsedMillis: Long,
                ) {
                    events += "started"
                }

                override fun onSynthesisAudioCompleted(
                    utterance: RecognizedUtterance,
                    target: TranslationTarget,
                    elapsedMillis: Long,
                    pcm: SynthesizedPcmStats,
                ) {
                    events += "completed"
                }
            },
        ).start(
            scope = this,
            utterances = utterances,
            targets = listOf(TranslationTarget("en", "English", "en", 24_000)),
        )
        yield()

        utterances.emit(RecognizedUtterance(1, "test", "ko-KR", true, 1L))
        advanceUntilIdle()

        assertEquals(listOf("started", "completed"), events)
        running.close()
    }

    @Test
    fun `leading primary silence is streamed but does not start first-audio telemetry`() = runTest {
        val streams = AudioStreamRegistry()
        val releaseAudiblePrimary = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val silent = PcmAudioFrame(byteArrayOf(0, 0, 0, 0), 1L)
        val audible = PcmAudioFrame(audibleTestPcm() + audibleTestPcm(), 2L)
        val utterances = MutableSharedFlow<RecognizedUtterance>(extraBufferCapacity = 1)
        val running = TranslationBroadcastPipeline(
            streams = streams,
            translationEngines = TranslationEngineProvider {
                TextTranslationEngine { text, _, _ -> text }
            },
            speechEngines = SpeechSynthesisEngineProvider {
                object : SpeechSynthesisEngine {
                    override fun synthesize(text: String, languageTag: String) = flow {
                        emit(silent)
                        releaseAudiblePrimary.await()
                        emit(audible)
                    }
                }
            },
            observer = firstAudioEventObserver(events),
        ).start(
            scope = this,
            utterances = utterances,
            targets = listOf(TranslationTarget("en", "English", "en", 24_000)),
        )
        val listener = running.streamSession.subscribe("en")
        yield()

        utterances.emit(RecognizedUtterance(1, "test", "ko-KR", true, 1L))
        runCurrent()

        val receivedSilence = listener.frames.tryReceive().getOrThrow()
        assertTrue(silent.bytes.contentEquals(receivedSilence.bytes))
        assertEquals(1L, receivedSilence.utteranceSequence)
        assertTrue(events.isEmpty())
        assertEquals(null, running.health.value.single().lastFirstAudioElapsedMillis)

        releaseAudiblePrimary.complete(Unit)
        advanceUntilIdle()

        val receivedAudible = listener.frames.tryReceive().getOrThrow()
        assertTrue(audible.bytes.contentEquals(receivedAudible.bytes))
        assertEquals(1L, receivedAudible.utteranceSequence)
        assertEquals(listOf("started", "completed"), events)
        assertNotNull(running.health.value.single().lastFirstAudioElapsedMillis)
        running.close()
        listener.close()
    }

    @Test
    fun `repeated one lsb frames are quiet and cannot extend the absolute first-audio deadline`() =
        runTest {
        val streams = AudioStreamRegistry()
        val utterances = MutableSharedFlow<RecognizedUtterance>(extraBufferCapacity = 1)
        val running = TranslationBroadcastPipeline(
            streams = streams,
            translationEngines = TranslationEngineProvider {
                TextTranslationEngine { text, _, _ -> text }
            },
            speechEngines = SpeechSynthesisEngineProvider {
                object : SpeechSynthesisEngine {
                    override fun synthesize(text: String, languageTag: String) = flow {
                        while (true) {
                            emit(PcmAudioFrame(byteArrayOf(1, 0, 1, 0), 1L))
                            delay(400L)
                        }
                    }
                }
            },
            firstAudioTimeoutMillis = 1_000L,
            synthesisFrameIdleTimeoutMillis = 500L,
        ).start(
            scope = this,
            utterances = utterances,
            targets = listOf(TranslationTarget("en", "English", "en", 24_000)),
        )
        val listener = running.streamSession.subscribe("en")
        runCurrent()

        utterances.emit(RecognizedUtterance(1, "test", "ko-KR", true, 1L))
        runCurrent()
        advanceTimeBy(999L)
        runCurrent()
        assertEquals(0L, running.health.value.single().synthesisFailures)

        advanceTimeBy(2L)
        advanceUntilIdle()
        val health = running.health.value.single()
        assertEquals(1L, health.synthesisFailures)
        assertEquals(TranslationWorkerState.DEGRADED, health.synthesisState)
        assertEquals(null, health.lastFirstAudioElapsedMillis)
        assertEquals(null, health.lastCompletedSequence)
        assertTrue(listener.frames.tryReceive().isSuccess)

        running.close()
        listener.close()
    }

    @Test
    fun `bounded provider queue waits do not consume first idle or total synthesis budgets`() =
        runTest {
            val streams = AudioStreamRegistry()
            val utterances = MutableSharedFlow<RecognizedUtterance>(extraBufferCapacity = 1)
            val queuedSpeech = object : ExecutionAwareSpeechSynthesisEngine {
                override val maximumExecutionStartWaitMillis = 700L

                override fun maximumExecutionStartWaitCount(text: String, languageTag: String) = 2

                override fun synthesize(
                    text: String,
                    languageTag: String,
                    onExecutionStarted: () -> Unit,
                ): Flow<PcmAudioFrame> = flow { error("Queue callbacks are required") }

                override fun synthesize(
                    text: String,
                    languageTag: String,
                    onExecutionWaitStarted: () -> Unit,
                    onExecutionStarted: () -> Unit,
                ) = flow {
                    repeat(2) { index ->
                        onExecutionWaitStarted()
                        delay(600L)
                        onExecutionStarted()
                        emit(PcmAudioFrame(audibleTestPcm(), index.toLong() + 1L))
                    }
                }
            }
            val running = TranslationBroadcastPipeline(
                streams = streams,
                translationEngines = TranslationEngineProvider {
                    TextTranslationEngine { text, _, _ -> text }
                },
                speechEngines = SpeechSynthesisEngineProvider { queuedSpeech },
                firstAudioTimeoutMillis = 500L,
                synthesisFrameIdleTimeoutMillis = 500L,
                synthesisTotalTimeoutMillis = 500L,
                currentElapsedRealtimeNanos = { testScheduler.currentTime * 1_000_000L },
            ).start(
                scope = this,
                utterances = utterances,
                targets = listOf(TranslationTarget("en", "English", "en", 24_000)),
            )
            val listener = running.streamSession.subscribe("en")
            runCurrent()

            utterances.emit(RecognizedUtterance(1, "test", "ko-KR", true, 1L))
            advanceUntilIdle()

            assertTrue(listener.frames.tryReceive().isSuccess)
            assertTrue(listener.frames.tryReceive().isSuccess)
            assertEquals(0L, running.health.value.single().synthesisFailures)
            assertEquals(1L, running.health.value.single().lastSynthesizedSequence)
            running.close()
            listener.close()
        }

    @Test
    fun `post-admission first PCM stall still consumes the unchanged active deadline`() = runTest {
        val streams = AudioStreamRegistry()
        val utterances = MutableSharedFlow<RecognizedUtterance>(extraBufferCapacity = 1)
        val stalledSpeech = object : ExecutionAwareSpeechSynthesisEngine {
            override val maximumExecutionStartWaitMillis = 700L

            override fun synthesize(
                text: String,
                languageTag: String,
                onExecutionStarted: () -> Unit,
            ): Flow<PcmAudioFrame> = flow { error("Queue callbacks are required") }

            override fun synthesize(
                text: String,
                languageTag: String,
                onExecutionWaitStarted: () -> Unit,
                onExecutionStarted: () -> Unit,
            ) = flow {
                onExecutionWaitStarted()
                delay(600L)
                onExecutionStarted()
                delay(501L)
                emit(PcmAudioFrame(audibleTestPcm(), 1L))
            }
        }
        val running = TranslationBroadcastPipeline(
            streams = streams,
            translationEngines = TranslationEngineProvider {
                TextTranslationEngine { text, _, _ -> text }
            },
            speechEngines = SpeechSynthesisEngineProvider { stalledSpeech },
            firstAudioTimeoutMillis = 500L,
            synthesisFrameIdleTimeoutMillis = 500L,
            synthesisTotalTimeoutMillis = 2_000L,
            currentElapsedRealtimeNanos = { testScheduler.currentTime * 1_000_000L },
        ).start(
            scope = this,
            utterances = utterances,
            targets = listOf(TranslationTarget("en", "English", "en", 24_000)),
        )
        runCurrent()

        utterances.emit(RecognizedUtterance(1, "test", "ko-KR", true, 1L))
        advanceUntilIdle()

        assertEquals(1L, running.health.value.single().synthesisFailures)
        assertEquals(null, running.health.value.single().lastFirstAudioElapsedMillis)
        running.close()
    }

    @Test
    fun `periodic audible frames cannot retain a channel lease beyond total synthesis ceiling`() =
        runTest {
            var synthesisCalls = 0
            val streams = AudioStreamRegistry()
            val coordinator = ChannelAudioPublicationCoordinator(listOf("en"))
            val utterances = MutableSharedFlow<RecognizedUtterance>(extraBufferCapacity = 2)
            val running = TranslationBroadcastPipeline(
                streams = streams,
                translationEngines = TranslationEngineProvider {
                    TextTranslationEngine { text, _, _ -> text }
                },
                speechEngines = SpeechSynthesisEngineProvider {
                    object : SpeechSynthesisEngine {
                        override fun synthesize(text: String, languageTag: String) = flow {
                            synthesisCalls += 1
                            if (synthesisCalls == 1) {
                                while (true) {
                                    emit(PcmAudioFrame(audibleTestPcm(), 1L))
                                    delay(100L)
                                }
                            } else {
                                emit(PcmAudioFrame(audibleTestPcm(), 2L))
                            }
                        }
                    }
                },
                firstAudioTimeoutMillis = 500L,
                synthesisFrameIdleTimeoutMillis = 500L,
                synthesisTotalTimeoutMillis = 1_000L,
                audioPublicationCoordinator = coordinator,
            ).start(
                scope = this,
                utterances = utterances,
                targets = listOf(TranslationTarget("en", "English", "en", 24_000)),
            )
            val listener = running.streamSession.subscribe("en")
            runCurrent()

            utterances.emit(RecognizedUtterance(1, "first", "ko", true, 1L))
            runCurrent()
            advanceTimeBy(1_001L)
            runCurrent()
            assertEquals(1L, running.health.value.single().synthesisFailures)

            utterances.emit(RecognizedUtterance(2, "second", "ko", true, 2L))
            advanceUntilIdle()
            val recovered = running.health.value.single()
            assertEquals(2L, recovered.lastCompletedSequence)
            assertEquals(1L, recovered.synthesisRecoveries)
            assertEquals(null, recovered.lastError)

            running.close()
            listener.close()
        }

    @Test
    fun `silent failed primary starts telemetry only when audible fallback arrives`() = runTest {
        val streams = AudioStreamRegistry()
        val events = mutableListOf<String>()
        val silentPrimary = PcmAudioFrame(byteArrayOf(0, 0), 1L)
        val audibleFallback = PcmAudioFrame(audibleTestPcm() + audibleTestPcm(), 2L)
        val utterances = MutableSharedFlow<RecognizedUtterance>(extraBufferCapacity = 1)
        val running = TranslationBroadcastPipeline(
            streams = streams,
            translationEngines = TranslationEngineProvider {
                TextTranslationEngine { text, _, _ -> text }
            },
            speechEngines = SpeechSynthesisEngineProvider {
                object : SpeechSynthesisEngine {
                    override fun synthesize(text: String, languageTag: String) = flow {
                        try {
                            emit(silentPrimary)
                            error("primary stopped before audible speech")
                        } catch (_: IllegalStateException) {
                            // The fallback may legitimately take longer than the post-audio idle
                            // timeout. Leading silence is not the first audio boundary.
                            delay(750L)
                            emit(audibleFallback)
                        }
                    }
                }
            },
            observer = firstAudioEventObserver(events),
            firstAudioTimeoutMillis = 1_000L,
            synthesisFrameIdleTimeoutMillis = 500L,
        ).start(
            scope = this,
            utterances = utterances,
            targets = listOf(TranslationTarget("en", "English", "en", 24_000)),
        )
        val listener = running.streamSession.subscribe("en")
        yield()

        utterances.emit(RecognizedUtterance(1, "test", "ko-KR", true, 1L))
        runCurrent()

        val receivedPrimarySilence = listener.frames.tryReceive().getOrThrow()
        assertTrue(silentPrimary.bytes.contentEquals(receivedPrimarySilence.bytes))
        assertEquals(1L, receivedPrimarySilence.utteranceSequence)
        assertTrue(events.isEmpty())
        assertEquals(null, running.health.value.single().lastFirstAudioElapsedMillis)

        advanceTimeBy(750L)
        runCurrent()

        val receivedFallback = listener.frames.tryReceive().getOrThrow()
        assertTrue(audibleFallback.bytes.contentEquals(receivedFallback.bytes))
        assertEquals(1L, receivedFallback.utteranceSequence)
        assertEquals(listOf("started", "completed"), events)
        assertNotNull(running.health.value.single().lastFirstAudioElapsedMillis)
        running.close()
        listener.close()
    }

    private fun firstAudioEventObserver(events: MutableList<String>) =
        object : TranslationPipelineObserver {
            override fun onSynthesisAudioStarted(
                utterance: RecognizedUtterance,
                target: TranslationTarget,
                elapsedMillis: Long,
            ) {
                events += "started"
            }

            override fun onSynthesisAudioCompleted(
                utterance: RecognizedUtterance,
                target: TranslationTarget,
                elapsedMillis: Long,
                pcm: SynthesizedPcmStats,
            ) {
                events += "completed"
            }
        }

    private suspend fun kotlinx.coroutines.test.TestScope.runPcmObservationTest(
        synthesis: Flow<PcmAudioFrame>,
    ): PcmObservationResult {
        val observed = CompletableDeferred<SynthesizedPcmStats>()
        val utterances = MutableSharedFlow<RecognizedUtterance>(extraBufferCapacity = 1)
        val running = TranslationBroadcastPipeline(
            streams = AudioStreamRegistry(),
            translationEngines = TranslationEngineProvider {
                TextTranslationEngine { text, _, _ -> text }
            },
            speechEngines = SpeechSynthesisEngineProvider {
                object : SpeechSynthesisEngine {
                    override fun synthesize(text: String, languageTag: String) = synthesis
                }
            },
            observer = object : TranslationPipelineObserver {
                override fun onSynthesisAudioCompleted(
                    utterance: RecognizedUtterance,
                    target: TranslationTarget,
                    elapsedMillis: Long,
                    pcm: SynthesizedPcmStats,
                ) {
                    observed.complete(pcm)
                }
            },
        ).start(
            this,
            utterances,
            listOf(TranslationTarget("en", "English", "en", 24_000)),
        )
        yield()
        utterances.emit(RecognizedUtterance(1, "test", "ko-KR", true, 1L))
        advanceUntilIdle()

        val stats = withTimeout(1_000) { observed.await() }
        val health = running.health.value.single()
        assertNotNull(health.lastSynthesisPcm)
        running.close()
        return PcmObservationResult(stats, health)
    }

    private suspend fun kotlinx.coroutines.test.TestScope.runPcmFailureTest(
        synthesis: Flow<PcmAudioFrame>,
    ): TranslationChannelHealth {
        val utterances = MutableSharedFlow<RecognizedUtterance>(extraBufferCapacity = 1)
        val running = TranslationBroadcastPipeline(
            streams = AudioStreamRegistry(),
            translationEngines = TranslationEngineProvider {
                TextTranslationEngine { text, _, _ -> text }
            },
            speechEngines = SpeechSynthesisEngineProvider {
                object : SpeechSynthesisEngine {
                    override fun synthesize(text: String, languageTag: String) = synthesis
                }
            },
        ).start(
            this,
            utterances,
            listOf(TranslationTarget("en", "English", "en", 24_000)),
        )
        yield()
        utterances.emit(RecognizedUtterance(1, "test", "ko-KR", true, 1L))
        advanceUntilIdle()

        val health = running.health.value.single()
        running.close()
        return health
    }

    private data class PcmObservationResult(
        val observed: SynthesizedPcmStats,
        val health: TranslationChannelHealth,
    )

    private fun audibleTestPcm(highByte: Int = 0x20): ByteArray =
        byteArrayOf(0, highByte.toByte())

    private fun pcmSamples(vararg samples: Int): ByteArray = ByteArray(samples.size * 2).also { bytes ->
        samples.forEachIndexed { index, value ->
            val sample = value.toShort().toInt()
            bytes[index * 2] = (sample and 0xff).toByte()
            bytes[index * 2 + 1] = ((sample shr 8) and 0xff).toByte()
        }
    }

    private fun ByteArray.toEvenPcm(): ByteArray = if (size % 2 == 0) this else this + 0

    private fun ByteArray.toTextPcm(): String =
        dropLastWhile { it == 0.toByte() }.toByteArray().decodeToString()

    private val Long.ms: Long get() = this * 1_000_000L
}
