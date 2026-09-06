package app.guidecast.provider.android.tts

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import app.guidecast.core.stream.MAX_SIMULTANEOUS_TRANSLATED_CHANNELS
import java.io.File
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout

class AndroidOfflineSpeechSynthesisEngineTest {

    @get:Rule
    val testDeadline: Timeout = Timeout.seconds(30)

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `healthy fallback remains initialized until preference actually changes`() = runBlocking {
        var preference: String? = "pkg.missing"
        var constructions = 0
        val voice = TestVoice("ko", Locale.KOREA)
        val engine = AndroidOfflineSpeechSynthesisEngine(
            context = FakeContext(tempFolder.newFolder()), languageTag = "ko",
            outputSampleRateHz = 16_000, onDisposed = {},
            preferredEnginePackage = { preference },
            candidateEngineResolver = { _, pref -> listOfNotNull(pref, "pkg.healthy") },
            ttsClientFactory = { _, pkg, init ->
                constructions++
                init(TextToSpeech.SUCCESS)
                FakeTtsClient(pkg, if (pkg == "pkg.healthy") setOf(voice) else emptySet())
            },
        )
        try {
            engine.prepare()
            assertEquals(2, constructions)
            assertFalse(engine.invalidateIfIdle())
            engine.prepare()
            assertEquals("Do not retry a missing preferred voice every sentence", 2, constructions)
            preference = null
            assertTrue("Switching back to automatic is a real preference change", engine.invalidateIfIdle())
            engine.prepare()
            assertEquals(3, constructions)
        } finally { engine.close() }
    }

    private class FakeContext(private val dir: File) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getCacheDir(): File = dir
        override fun getFilesDir(): File = dir
        override fun getPackageManager(): PackageManager? = null
        override fun getMainExecutor(): Executor = Executor { it.run() }
    }

    private class TestVoice(
        private val voiceName: String,
        private val voiceLocale: Locale,
        private val voiceQuality: Int = 300,
        private val voiceLatency: Int = 300,
        private val voiceIsNetwork: Boolean = false,
        private val voiceFeatures: Set<String> = emptySet(),
    ) : android.speech.tts.Voice(
        voiceName,
        voiceLocale,
        voiceQuality,
        voiceLatency,
        voiceIsNetwork,
        voiceFeatures,
    ) {
        override fun getName(): String = voiceName
        override fun getLocale(): Locale = voiceLocale
        override fun getQuality(): Int = voiceQuality
        override fun getLatency(): Int = voiceLatency
        override fun isNetworkConnectionRequired(): Boolean = voiceIsNetwork
        override fun getFeatures(): MutableSet<String> = voiceFeatures.toMutableSet()
    }

    private class FakeTtsClient(
        override val defaultEngine: String?,
        val availableVoices: Set<android.speech.tts.Voice> = emptySet(),
        val synthesizeImpl: (text: CharSequence, file: File, utteranceId: String, listener: UtteranceProgressListener?) -> Int =
            { _, _, _, _ -> TextToSpeech.SUCCESS },
        val voiceQueryDelayMillis: Long = 0L,
    ) : AndroidTtsClient {
        var isShutdown = false
        var stopCount = 0
        var selectedVoice: android.speech.tts.Voice? = null
        var listener: UtteranceProgressListener? = null

        override fun getVoices(): Set<android.speech.tts.Voice> {
            if (voiceQueryDelayMillis > 0L) {
                Thread.sleep(voiceQueryDelayMillis)
            }
            return availableVoices
        }

        override fun setVoice(voice: android.speech.tts.Voice): Int {
            selectedVoice = voice
            return TextToSpeech.SUCCESS
        }

        override fun setPitch(pitch: Float): Int = TextToSpeech.SUCCESS
        override fun setSpeechRate(speechRate: Float): Int = TextToSpeech.SUCCESS

        override fun setOnUtteranceProgressListener(listener: UtteranceProgressListener): Int {
            this.listener = listener
            return TextToSpeech.SUCCESS
        }

        override fun synthesizeToFile(
            text: CharSequence,
            params: Bundle,
            file: File,
            utteranceId: String,
        ): Int {
            return synthesizeImpl(text, file, utteranceId, listener)
        }

        override fun stop(): Int {
            stopCount++
            return TextToSpeech.SUCCESS
        }

        override fun shutdown() {
            isShutdown = true
        }
    }

    private fun dummyPcmWaveFile(file: File, sampleRate: Int = 16_000, durationFrames: Int = 2) {
        val sampleCount = sampleRate * 20 / 1000 * durationFrames
        val pcmBytes = ByteArray(sampleCount * 2) { 1 } // Non-zero PCM16
        val waveBytes = decodeOrEncodeWave(pcmBytes, sampleRate)
        file.writeBytes(waveBytes)
    }

    private fun decodeOrEncodeWave(pcmData: ByteArray, sampleRate: Int): ByteArray {
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + pcmData.size)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1.toShort()) // PCM
        header.putShort(1.toShort()) // mono
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2)
        header.putShort(2.toShort()) // block align
        header.putShort(16.toShort()) // bits per sample
        header.put("data".toByteArray())
        header.putInt(pcmData.size)
        return header.array() + pcmData
    }

    @Test
    fun `reselects next candidate engine when first candidate fails init`(): Unit = runBlocking {
        val context = FakeContext(tempFolder.newFolder())
        val koreanVoice = TestVoice("ko-KR-voice", Locale.KOREA)
        val candidate1 = FakeTtsClient("pkg.broken")
        val candidate2 = FakeTtsClient("pkg.healthy", setOf(koreanVoice))

        val engine = AndroidOfflineSpeechSynthesisEngine(
            context = context,
            languageTag = "ko",
            outputSampleRateHz = 16_000,
            onDisposed = {},
            candidateEngineResolver = { _, _ -> listOf("pkg.broken", "pkg.healthy") },
            ttsClientFactory = { _, pkg, onInit ->
                if (pkg == "pkg.broken") {
                    onInit(TextToSpeech.ERROR)
                    candidate1
                } else {
                    onInit(TextToSpeech.SUCCESS)
                    candidate2
                }
            },
        )

        engine.prepare()
        assertSame(koreanVoice, candidate2.selectedVoice)
        assertTrue(candidate1.isShutdown)
        assertFalse(candidate2.isShutdown)
    }

    @Test
    fun `reselects next candidate engine when first candidate has no matching offline voice`(): Unit = runBlocking {
        val context = FakeContext(tempFolder.newFolder())
        val frenchVoice = TestVoice("fr-FR-voice", Locale.FRANCE)
        val koreanVoice = TestVoice("ko-KR-voice", Locale.KOREA)

        val candidate1 = FakeTtsClient("pkg.no_voice", setOf(frenchVoice))
        val candidate2 = FakeTtsClient("pkg.with_voice", setOf(koreanVoice))

        val engine = AndroidOfflineSpeechSynthesisEngine(
            context = context,
            languageTag = "ko",
            outputSampleRateHz = 16_000,
            onDisposed = {},
            candidateEngineResolver = { _, _ -> listOf("pkg.no_voice", "pkg.with_voice") },
            ttsClientFactory = { _, pkg, onInit ->
                onInit(TextToSpeech.SUCCESS)
                if (pkg == "pkg.no_voice") candidate1 else candidate2
            },
        )

        engine.prepare()
        assertSame(koreanVoice, candidate2.selectedVoice)
        assertTrue(candidate1.isShutdown)
    }

    @Test
    fun `runtime failure before any pcm output reselects next candidate engine`(): Unit = runBlocking {
        val context = FakeContext(tempFolder.newFolder())
        val koreanVoice = TestVoice("ko-KR-voice", Locale.KOREA)

        val candidate1 = FakeTtsClient(
            defaultEngine = "pkg.fails_at_runtime",
            availableVoices = setOf(koreanVoice),
            synthesizeImpl = { _, _, _, _ -> TextToSpeech.ERROR }, // Fails synthesis immediately
        )
        val candidate2 = FakeTtsClient(
            defaultEngine = "pkg.healthy",
            availableVoices = setOf(koreanVoice),
            synthesizeImpl = { _, file, utteranceId, listener ->
                dummyPcmWaveFile(file, 16_000, 2)
                listener?.onBeginSynthesis(utteranceId, 16_000, AudioFormat.ENCODING_PCM_16BIT, 1)
                val pcm = ByteArray(640) { 5 }
                listener?.onAudioAvailable(utteranceId, pcm)
                listener?.onDone(utteranceId)
                TextToSpeech.SUCCESS
            },
        )

        val engine = AndroidOfflineSpeechSynthesisEngine(
            context = context,
            languageTag = "ko",
            outputSampleRateHz = 16_000,
            onDisposed = {},
            candidateEngineResolver = { _, _ -> listOf("pkg.fails_at_runtime", "pkg.healthy") },
            ttsClientFactory = { _, pkg, onInit ->
                onInit(TextToSpeech.SUCCESS)
                if (pkg == "pkg.fails_at_runtime") candidate1 else candidate2
            },
        )

        val frames = engine.synthesize("안녕하세요", "ko").toList()
        assertTrue("Must have received frames from candidate 2", frames.isNotEmpty())
        assertTrue("Candidate 1 must have been invalidated and shut down", candidate1.isShutdown)
        assertFalse(candidate2.isShutdown)
    }

    @Test
    fun `prohibits full sentence replay after partial pcm has been emitted`(): Unit = runBlocking {
        val context = FakeContext(tempFolder.newFolder())
        val koreanVoice = TestVoice("ko-KR-voice", Locale.KOREA)
        val candidate2SynthesizeCalled = AtomicBoolean(false)

        val candidate1 = FakeTtsClient(
            defaultEngine = "pkg.fails_midway",
            availableVoices = setOf(koreanVoice),
            synthesizeImpl = { _, file, utteranceId, listener ->
                dummyPcmWaveFile(file, 16_000, 4)
                listener?.onBeginSynthesis(utteranceId, 16_000, AudioFormat.ENCODING_PCM_16BIT, 1)
                // Emit partial PCM:
                val pcm = ByteArray(640) { 10 }
                listener?.onAudioAvailable(utteranceId, pcm)
                // Now fail with error after PCM has already been delivered:
                listener?.onError(utteranceId, TextToSpeech.ERROR)
                TextToSpeech.SUCCESS
            },
        )
        val candidate2 = FakeTtsClient(
            defaultEngine = "pkg.candidate2",
            availableVoices = setOf(koreanVoice),
            synthesizeImpl = { _, _, _, _ ->
                candidate2SynthesizeCalled.set(true)
                TextToSpeech.SUCCESS
            },
        )

        val engine = AndroidOfflineSpeechSynthesisEngine(
            context = context,
            languageTag = "ko",
            outputSampleRateHz = 16_000,
            onDisposed = {},
            candidateEngineResolver = { _, _ -> listOf("pkg.fails_midway", "pkg.candidate2") },
            ttsClientFactory = { _, pkg, onInit ->
                onInit(TextToSpeech.SUCCESS)
                if (pkg == "pkg.fails_midway") candidate1 else candidate2
            },
        )

        val exception = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                engine.synthesize("긴 문장입니다", "ko").collect {}
            }
        }

        assertTrue(
            "Exception message must state that replaying sentence is prohibited after partial PCM",
            exception.message?.contains("replaying sentence is prohibited") == true,
        )
        assertFalse(
            "Candidate 2 must NOT have been called to re-synthesize or replay the sentence",
            candidate2SynthesizeCalled.get(),
        )
        assertTrue("Failed candidate 1 must be shut down for future calls", candidate1.isShutdown)
    }

    @Test
    fun `explicit cancellation stops engine and propagates CancellationException without failover`(): Unit = runBlocking {
        val context = FakeContext(tempFolder.newFolder())
        val englishVoice = TestVoice("en-US-voice", Locale.US)
        val candidate2Called = AtomicBoolean(false)

        val candidate1 = FakeTtsClient(
            defaultEngine = "pkg.client",
            availableVoices = setOf(englishVoice),
            synthesizeImpl = { _, file, utteranceId, listener ->
                dummyPcmWaveFile(file, 16_000, 10)
                listener?.onBeginSynthesis(utteranceId, 16_000, AudioFormat.ENCODING_PCM_16BIT, 1)
                listener?.onAudioAvailable(utteranceId, ByteArray(640) { 3 })
                TextToSpeech.SUCCESS
            },
        )
        val candidate2 = FakeTtsClient(
            defaultEngine = "pkg.backup",
            availableVoices = setOf(englishVoice),
            synthesizeImpl = { _, _, _, _ ->
                candidate2Called.set(true)
                TextToSpeech.SUCCESS
            },
        )

        val engine = AndroidOfflineSpeechSynthesisEngine(
            context = context,
            languageTag = "en",
            outputSampleRateHz = 16_000,
            onDisposed = {},
            candidateEngineResolver = { _, _ -> listOf("pkg.client", "pkg.backup") },
            ttsClientFactory = { _, pkg, onInit ->
                onInit(TextToSpeech.SUCCESS)
                if (pkg == "pkg.client") candidate1 else candidate2
            },
        )

        val error = assertThrows(CancellationException::class.java) {
            runBlocking {
                engine.synthesize("Hello world", "en").collect {
                    cancel(CancellationException("user cancelled"))
                }
            }
        }

        assertEquals("user cancelled", error.message)
        assertEquals("Stop must be called on cancelled client", 1, candidate1.stopCount)
        assertFalse("Cancellation must not trigger failover to candidate 2", candidate2Called.get())
    }

    @Test
    fun `late callbacks from older generation are safely dropped`(): Unit = runBlocking {
        val context = FakeContext(tempFolder.newFolder())
        val koreanVoice = TestVoice("ko-KR-voice", Locale.KOREA)
        var staleListener: UtteranceProgressListener? = null

        val candidate1 = FakeTtsClient(
            defaultEngine = "pkg.engine1",
            availableVoices = setOf(koreanVoice),
            synthesizeImpl = { _, _, _, listener ->
                staleListener = listener
                TextToSpeech.ERROR // trigger invalidation
            },
        )
        val candidate2 = FakeTtsClient(
            defaultEngine = "pkg.engine2",
            availableVoices = setOf(koreanVoice),
            synthesizeImpl = { _, file, utteranceId, listener ->
                dummyPcmWaveFile(file, 16_000, 1)
                listener?.onBeginSynthesis(utteranceId, 16_000, AudioFormat.ENCODING_PCM_16BIT, 1)
                listener?.onAudioAvailable(utteranceId, ByteArray(640) { 1 })
                listener?.onDone(utteranceId)
                TextToSpeech.SUCCESS
            },
        )

        val engine = AndroidOfflineSpeechSynthesisEngine(
            context = context,
            languageTag = "ko",
            outputSampleRateHz = 16_000,
            onDisposed = {},
            candidateEngineResolver = { _, _ -> listOf("pkg.engine1", "pkg.engine2") },
            ttsClientFactory = { _, pkg, onInit ->
                onInit(TextToSpeech.SUCCESS)
                if (pkg == "pkg.engine1") candidate1 else candidate2
            },
        )

        val frames = engine.synthesize("테스트", "ko").toList()
        assertEquals(1, frames.size)

        // Simulate late callbacks arriving from engine 1
        assertNotNull(staleListener)
        staleListener?.onAudioAvailable("old-id", ByteArray(640) { 99 })
        staleListener?.onDone("old-id")
        staleListener?.onError("old-id", -1)
        // Verify no crashes or unexpected state corruption
    }

    @Test
    fun `cross-language stop isolation prevents interference`(): Unit = runBlocking {
        val context = FakeContext(tempFolder.newFolder())
        val englishVoice = TestVoice("en-US-voice", Locale.US)
        val japaneseVoice = TestVoice("ja-JP-voice", Locale.JAPANESE)

        val englishClient = FakeTtsClient("pkg.en", setOf(englishVoice), synthesizeImpl = { _, _, id, listener ->
            listener?.onBeginSynthesis(id, 16_000, AudioFormat.ENCODING_PCM_16BIT, 1)
            listener?.onAudioAvailable(id, ByteArray(640) { 3 })
            TextToSpeech.SUCCESS
        })
        val japaneseClient = FakeTtsClient("pkg.ja", setOf(japaneseVoice))

        val englishEngine = AndroidOfflineSpeechSynthesisEngine(
            context = context,
            languageTag = "en",
            outputSampleRateHz = 16_000,
            onDisposed = {},
            candidateEngineResolver = { _, _ -> listOf("pkg.en") },
            ttsClientFactory = { _, _, onInit ->
                onInit(TextToSpeech.SUCCESS)
                englishClient
            },
        )
        val japaneseEngine = AndroidOfflineSpeechSynthesisEngine(
            context = context,
            languageTag = "ja",
            outputSampleRateHz = 16_000,
            onDisposed = {},
            candidateEngineResolver = { _, _ -> listOf("pkg.ja") },
            ttsClientFactory = { _, _, onInit ->
                onInit(TextToSpeech.SUCCESS)
                japaneseClient
            },
        )

        englishEngine.prepare()
        japaneseEngine.prepare()

        // Cancel an English synthesis
        assertThrows(CancellationException::class.java) {
            runBlocking {
                englishEngine.synthesize("Hello", "en").collect {
                    cancel(CancellationException("Cancel English"))
                }
            }
        }

        assertEquals(1, englishClient.stopCount)
        assertEquals("Japanese client must NOT have received stop", 0, japaneseClient.stopCount)
    }

    @Test
    fun `provider enforces up to MAX_SIMULTANEOUS_TRANSLATED_CHANNELS (7) channels`(): Unit = runBlocking {
        val context = FakeContext(tempFolder.newFolder())
        val provider = AndroidOfflineSpeechSynthesisProvider(
            context = context,
            outputSampleRateHz = 16_000,
            synthesisAdmission = Semaphore(1),
            engineFactory = { tag, onDisposed, admission ->
                AndroidOfflineSpeechSynthesisEngine(
                    context = context,
                    languageTag = tag,
                    outputSampleRateHz = 16_000,
                    onDisposed = onDisposed,
                    synthesisAdmission = admission,
                    candidateEngineResolver = { _, _ -> listOf("pkg.dummy") },
                    ttsClientFactory = { _, _, onInit ->
                        onInit(TextToSpeech.SUCCESS)
                        FakeTtsClient("pkg.dummy", setOf(TestVoice("$tag-voice", Locale.forLanguageTag(tag))))
                    },
                )
            },
        )

        val sevenLanguages = listOf("en", "ja", "zh", "es", "ko", "vi", "nl")
        assertEquals(7, MAX_SIMULTANEOUS_TRANSLATED_CHANNELS)
        provider.prepare(sevenLanguages)
        provider.activateBroadcastLanguages(sevenLanguages.toSet())
        provider.retainSettingsLanguages(sevenLanguages.toSet())

        val eightLanguages = sevenLanguages + "de"
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { provider.prepare(eightLanguages) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            provider.activateBroadcastLanguages(eightLanguages.toSet())
        }
        assertThrows(IllegalArgumentException::class.java) {
            provider.retainSettingsLanguages(eightLanguages.toSet())
        }

        provider.close()
    }

    @Test
    fun `seven language provider serializes only execution admission and preserves queued work`(): Unit = runBlocking {
        val context = FakeContext(tempFolder.newFolder())
        val admission = Semaphore(1)
        val allowFirstStart = CountDownLatch(1)
        val submissionsWaitingForStart = AtomicInteger(0)
        val maximumWaitingForStart = AtomicInteger(0)
        val clients = mutableMapOf<String, FakeTtsClient>()
        val languages = listOf("en", "ja", "zh", "es", "ko", "vi", "nl")
        val provider = AndroidOfflineSpeechSynthesisProvider(
            context = context,
            outputSampleRateHz = 16_000,
            synthesisAdmission = admission,
            engineFactory = { tag, onDisposed, sharedAdmission ->
                val voice = TestVoice("$tag-voice", Locale.forLanguageTag(tag))
                AndroidOfflineSpeechSynthesisEngine(
                    context = context,
                    languageTag = tag,
                    outputSampleRateHz = 16_000,
                    onDisposed = onDisposed,
                    synthesisAdmission = sharedAdmission,
                    candidateEngineResolver = { _, _ -> listOf("pkg.shared") },
                    ttsClientFactory = { _, _, onInit ->
                        onInit(TextToSpeech.SUCCESS)
                        FakeTtsClient(
                            defaultEngine = "pkg.shared",
                            availableVoices = setOf(voice),
                            synthesizeImpl = { _, _, utteranceId, listener ->
                                val waiting = submissionsWaitingForStart.incrementAndGet()
                                maximumWaitingForStart.updateAndGet { previous -> maxOf(previous, waiting) }
                                allowFirstStart.await(2, TimeUnit.SECONDS)
                                submissionsWaitingForStart.decrementAndGet()
                                listener?.onStart(utteranceId)
                                listener?.onBeginSynthesis(
                                    utteranceId, 16_000, AudioFormat.ENCODING_PCM_16BIT, 1,
                                )
                                listener?.onAudioAvailable(utteranceId, ByteArray(640) { 3 })
                                listener?.onDone(utteranceId)
                                TextToSpeech.SUCCESS
                            },
                        ).also { clients[tag] = it }
                    },
                )
            },
        )

        try {
            provider.prepare(languages)
            provider.activateBroadcastLanguages(languages.toSet())
            val jobs = languages.map { language ->
                async(Dispatchers.Default) {
                    provider.engineFor(language).synthesize("speech-$language", language).toList()
                }
            }
            while (submissionsWaitingForStart.get() == 0) delay(1L)
            delay(25L)
            assertEquals("Only one request may wait for provider execution start", 1, maximumWaitingForStart.get())
            allowFirstStart.countDown()
            val frames = jobs.awaitAll()

            assertTrue(frames.all { it.isNotEmpty() })
            assertTrue(clients.values.all { it.stopCount == 0 })
        } finally {
            allowFirstStart.countDown()
            provider.close()
        }
    }

    @Test
    fun `cancellation before Android submission neither stops healthy client nor leaks admission`(): Unit = runBlocking {
        val context = FakeContext(tempFolder.newFolder())
        val voice = TestVoice("en-voice", Locale.US)
        val client = FakeTtsClient(
            defaultEngine = "pkg.shared",
            availableVoices = setOf(voice),
            synthesizeImpl = { _, _, utteranceId, listener ->
                listener?.onStart(utteranceId)
                listener?.onBeginSynthesis(
                    utteranceId, 16_000, AudioFormat.ENCODING_PCM_16BIT, 1,
                )
                listener?.onAudioAvailable(utteranceId, ByteArray(640) { 3 })
                listener?.onDone(utteranceId)
                TextToSpeech.SUCCESS
            },
        )
        val admission = Semaphore(1)
        val engine = AndroidOfflineSpeechSynthesisEngine(
            context = context,
            languageTag = "en",
            outputSampleRateHz = 16_000,
            onDisposed = {},
            synthesisAdmission = admission,
            candidateEngineResolver = { _, _ -> listOf("pkg.shared") },
            ttsClientFactory = { _, _, onInit ->
                onInit(TextToSpeech.SUCCESS)
                client
            },
        )

        try {
            engine.prepare()
            admission.acquire()
            val cancelled = launch(Dispatchers.Default) {
                engine.synthesize("queued", "en").toList()
            }
            delay(25L)
            cancelled.cancelAndJoin()
            assertEquals(0, client.stopCount)

            admission.release()
            val frames = withTimeout(1_000L) {
                engine.synthesize("next", "en").toList()
            }
            assertTrue(frames.isNotEmpty())
            assertEquals(0, client.stopCount)
        } finally {
            if (admission.availablePermits == 0) admission.release()
            engine.close()
        }
    }

    @Test
    fun `late callback from cancelled request cannot release a newer request admission`(): Unit = runBlocking {
        val context = FakeContext(tempFolder.newFolder())
        val voice = TestVoice("en-voice", Locale.US)
        val submitted = mutableListOf<Pair<String, UtteranceProgressListener?>>()
        val client = FakeTtsClient(
            defaultEngine = "pkg.shared",
            availableVoices = setOf(voice),
            synthesizeImpl = { _, _, utteranceId, listener ->
                synchronized(submitted) { submitted += utteranceId to listener }
                TextToSpeech.SUCCESS
            },
        )
        val admission = Semaphore(1)
        val engine = AndroidOfflineSpeechSynthesisEngine(
            context = context,
            languageTag = "en",
            outputSampleRateHz = 16_000,
            onDisposed = {},
            synthesisAdmission = admission,
            candidateEngineResolver = { _, _ -> listOf("pkg.shared") },
            ttsClientFactory = { _, _, onInit ->
                onInit(TextToSpeech.SUCCESS)
                client
            },
        )

        try {
            engine.prepare()
            val first = launch(Dispatchers.Default) {
                engine.synthesize("first", "en").toList()
            }
            while (synchronized(submitted) { submitted.size } < 1) delay(1L)
            first.cancelAndJoin()

            val second = launch(Dispatchers.Default) {
                engine.synthesize("second", "en").toList()
            }
            while (synchronized(submitted) { submitted.size } < 2) delay(1L)
            val firstRequest = synchronized(submitted) { submitted.first() }
            firstRequest.second?.onStart(firstRequest.first)

            val third = launch(Dispatchers.Default) {
                engine.synthesize("third", "en").toList()
            }
            delay(50L)
            assertEquals(2, synchronized(submitted) { submitted.size })

            second.cancelAndJoin()
            third.cancelAndJoin()
        } finally {
            engine.close()
        }
    }

    @Test
    fun `idle preference change switches to newly preferred candidate on next synthesis`(): Unit = runBlocking {
        val context = FakeContext(tempFolder.newFolder())
        val koreanVoice = TestVoice("ko-KR-voice", Locale.KOREA)
        var currentPref = "pkg.samsung"

        val samsungClient = FakeTtsClient("pkg.samsung", setOf(koreanVoice), synthesizeImpl = { _, file, id, listener ->
            dummyPcmWaveFile(file, 16_000, 1)
            listener?.onBeginSynthesis(id, 16_000, AudioFormat.ENCODING_PCM_16BIT, 1)
            listener?.onAudioAvailable(id, ByteArray(640) { 1 })
            listener?.onDone(id)
            TextToSpeech.SUCCESS
        })
        val googleClient = FakeTtsClient("pkg.google", setOf(koreanVoice), synthesizeImpl = { _, file, id, listener ->
            dummyPcmWaveFile(file, 16_000, 1)
            listener?.onBeginSynthesis(id, 16_000, AudioFormat.ENCODING_PCM_16BIT, 1)
            listener?.onAudioAvailable(id, ByteArray(640) { 2 })
            listener?.onDone(id)
            TextToSpeech.SUCCESS
        })

        val engine = AndroidOfflineSpeechSynthesisEngine(
            context = context,
            languageTag = "ko",
            outputSampleRateHz = 16_000,
            preferredEnginePackage = { currentPref },
            onDisposed = {},
            candidateEngineResolver = { _, pref ->
                if (pref == "pkg.samsung") listOf("pkg.samsung", "pkg.google")
                else listOf("pkg.google", "pkg.samsung")
            },
            ttsClientFactory = { _, pkg, onInit ->
                onInit(TextToSpeech.SUCCESS)
                if (pkg == "pkg.samsung") samsungClient else googleClient
            },
        )

        engine.prepare()
        assertSame(koreanVoice, samsungClient.selectedVoice)
        assertFalse(samsungClient.isShutdown)

        // Change preference to Google while engine is idle
        currentPref = "pkg.google"
        val invalidated = engine.invalidateIfIdle()
        assertTrue("Idle engine must invalidate on preference change", invalidated)
        assertTrue("Samsung client must be shut down", samsungClient.isShutdown)

        // Next synthesis must use Google
        val frames = engine.synthesize("안녕하세요", "ko").toList()
        assertEquals(1, frames.size)
        assertSame(koreanVoice, googleClient.selectedVoice)
        assertFalse(googleClient.isShutdown)
    }

    @Test
    fun `active synthesis is not interrupted by preference change`(): Unit = runBlocking {
        val context = FakeContext(tempFolder.newFolder())
        val koreanVoice = TestVoice("ko-KR-voice", Locale.KOREA)
        var currentPref = "pkg.samsung"

        val samsungClient = FakeTtsClient("pkg.samsung", setOf(koreanVoice), synthesizeImpl = { _, file, id, listener ->
            dummyPcmWaveFile(file, 16_000, 2)
            listener?.onBeginSynthesis(id, 16_000, AudioFormat.ENCODING_PCM_16BIT, 1)
            // While in middle of synthesis, preference changes!
            currentPref = "pkg.google"
            listener?.onAudioAvailable(id, ByteArray(640) { 1 })
            listener?.onDone(id)
            TextToSpeech.SUCCESS
        })
        val googleClient = FakeTtsClient("pkg.google", setOf(koreanVoice))

        val engine = AndroidOfflineSpeechSynthesisEngine(
            context = context,
            languageTag = "ko",
            outputSampleRateHz = 16_000,
            preferredEnginePackage = { currentPref },
            onDisposed = {},
            candidateEngineResolver = { _, pref ->
                if (pref == "pkg.samsung") listOf("pkg.samsung", "pkg.google")
                else listOf("pkg.google", "pkg.samsung")
            },
            ttsClientFactory = { _, pkg, onInit ->
                onInit(TextToSpeech.SUCCESS)
                if (pkg == "pkg.samsung") samsungClient else googleClient
            },
        )

        // Synthesis completes successfully using Samsung despite mid-synthesis pref change
        val frames = engine.synthesize("안녕하세요", "ko").toList()
        assertEquals(1, frames.size)
        assertFalse("Samsung client should still be active during and immediately after that utterance", samsungClient.isShutdown)

        // Once idle, next ensureReady or synthesize switches to Google
        val invalidated = engine.invalidateIfIdle()
        assertTrue("Should invalidate now that it is idle", invalidated)
        assertTrue("Samsung client must now be shut down", samsungClient.isShutdown)
    }

    @Test
    fun `voice query timeout unblocks caller coroutine via latch and ignores late results`(): Unit = runBlocking {
        val context = FakeContext(tempFolder.newFolder())
        val koreanVoice = TestVoice("ko-KR-voice", Locale.KOREA)
        val hangLatch = CountDownLatch(1)

        val hangingClient = FakeTtsClient(
            defaultEngine = "pkg.hang",
            availableVoices = setOf(koreanVoice),
            voiceQueryDelayMillis = 0L,
        ).apply {
            // Override getVoices behavior to hang on latch
        }

        val hangingClientWrapper = object : AndroidTtsClient by hangingClient {
            override fun getVoices(): Set<android.speech.tts.Voice> {
                hangLatch.await(5, TimeUnit.SECONDS)
                return setOf(koreanVoice)
            }
        }

        val backupClient = FakeTtsClient("pkg.backup", setOf(koreanVoice))

        val engine = AndroidOfflineSpeechSynthesisEngine(
            context = context,
            languageTag = "ko",
            outputSampleRateHz = 16_000,
            onDisposed = {},
            candidateEngineResolver = { _, _ -> listOf("pkg.hang", "pkg.backup") },
            ttsClientFactory = { _, pkg, onInit ->
                onInit(TextToSpeech.SUCCESS)
                if (pkg == "pkg.hang") hangingClientWrapper else backupClient
            },
        )

        // Caller must unblock within bounded timeout without waiting 5 seconds, and fail over to backupClient
        val startTime = System.currentTimeMillis()
        engine.prepare()
        val elapsed = System.currentTimeMillis() - startTime

        assertTrue("Must unblock in approximately voice query timeout (~3s), not 5s", elapsed < 4_500L)
        assertSame(koreanVoice, backupClient.selectedVoice)

        // Release the latch so hanging background thread can exit cleanly
        hangLatch.countDown()
    }

    @Test
    fun `executor queue saturation rejects immediately with fast fail`(): Unit = runBlocking {
        val context = FakeContext(tempFolder.newFolder())
        val koreanVoice = TestVoice("ko-KR-voice", Locale.KOREA)
        val blockLatch = CountDownLatch(1)

        // Tiny executor: 1 thread, queue size 1
        val saturatedExecutor = ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(1),
        )

        // Block the single thread and fill the single queue slot
        saturatedExecutor.execute { blockLatch.await() }
        saturatedExecutor.execute { blockLatch.await() }

        val client = FakeTtsClient("pkg.candidate", setOf(koreanVoice))
        val engine = AndroidOfflineSpeechSynthesisEngine(
            context = context,
            languageTag = "ko",
            outputSampleRateHz = 16_000,
            onDisposed = {},
            candidateEngineResolver = { _, _ -> listOf("pkg.candidate") },
            ttsClientFactory = { _, _, onInit ->
                onInit(TextToSpeech.SUCCESS)
                client
            },
            binderExecutor = saturatedExecutor,
        )

        // Voice query will be rejected immediately by saturatedExecutor (AbortPolicy -> RejectedExecutionException)
        // and fail fast
        assertThrows(IllegalStateException::class.java) {
            runBlocking { engine.prepare() }
        }

        blockLatch.countDown()
        saturatedExecutor.shutdown()
    }

    @Test
    fun `external cancellation during engine initialization aborts immediately without trying next candidate`(): Unit = runBlocking {
        val context = FakeContext(tempFolder.newFolder())
        val candidate2Attempted = AtomicBoolean(false)

        val engine = AndroidOfflineSpeechSynthesisEngine(
            context = context,
            languageTag = "ko",
            outputSampleRateHz = 16_000,
            onDisposed = {},
            candidateEngineResolver = { _, _ -> listOf("pkg.first", "pkg.second") },
            ttsClientFactory = { _, pkg, onInit ->
                if (pkg == "pkg.first") {
                    throw CancellationException("External caller cancelled during first candidate")
                } else {
                    candidate2Attempted.set(true)
                    onInit(TextToSpeech.SUCCESS)
                    FakeTtsClient(pkg)
                }
            },
        )

        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking { engine.prepare() }
        }

        assertEquals("External caller cancelled during first candidate", thrown.message)
        assertFalse("Second candidate must never be attempted on external cancellation", candidate2Attempted.get())
    }
}
