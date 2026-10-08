package app.guidecast.transmitter

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.ContextCompat
import app.guidecast.core.server.GuideCastLocalServer
import app.guidecast.core.server.GuideCastBroadcastStatus
import app.guidecast.core.server.GuideCastBroadcastPhase
import app.guidecast.core.server.GuideCastChannelStatus
import app.guidecast.core.server.GuideCastChannelReadiness
import app.guidecast.core.server.GuideCastListenerNextAction
import app.guidecast.core.server.GuideCastServerConfig
import app.guidecast.core.server.GuideCastTranscriptLine
import app.guidecast.core.server.GuideCastTranscriptSnapshot
import app.guidecast.core.server.LocalNetworkAddressResolver
import app.guidecast.core.server.RunningGuideCastServer
import app.guidecast.core.server.ReplayCaptionSnapshot
import app.guidecast.core.stream.AudioChannelDescriptor
import app.guidecast.core.stream.PcmAudioFrame
import app.guidecast.core.stream.RecordedBroadcast
import app.guidecast.core.stream.RecordedPcmSegment
import app.guidecast.core.stream.StreamSession
import app.guidecast.provider.moonshine.tts.MoonshineSpeechSynthesisProvider
import app.guidecast.provider.gemma.translation.GemmaTranslationProvider
import app.guidecast.core.translation.ContextualTextTranslationEngine
import app.guidecast.core.translation.SharedTranslationBatchContext
import app.guidecast.core.translation.TextTranslationEngine
import app.guidecast.core.translation.TranslationRequestIdentity
import app.guidecast.core.translation.TranslationStyleContext
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal enum class MenuBroadcastOrigin { NOTES, FILES, HISTORY }
internal enum class MenuBroadcastPhase { IDLE, STARTING, LIVE, PAUSED, COMPLETED, FAILED }
internal data class MenuBroadcastCaption(val startMs: Long, val endMs: Long,
    val original: String, val translations: Map<String, String>, val isFinal: Boolean = true) {
    init {
        require(startMs >= 0 && endMs >= startMs)
        require(original.length <= 65_536 && translations.size <= 7)
        require(translations.all { (tag, text) -> validMenuLanguage(tag) && text.length <= 65_536 })
    }
}

internal data class MenuBroadcastState(
    val generation: Long = 0,
    val phase: MenuBroadcastPhase = MenuBroadcastPhase.IDLE,
    val origin: MenuBroadcastOrigin? = null,
    val title: String = "",
    val listenerUrl: String? = null,
    val errorMessage: String? = null,
    val warningMessage: String? = null,
    val sourcePublishedBytes: Long = 0,
    val translatedPublishedBytes: Long = 0,
    val listenerCount: Int = 0,
    val webSocketDeliveredFrames: Long = 0,
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val channels: List<AudioChannelDescriptor> = emptyList(),
    val captions: List<MenuBroadcastCaption> = emptyList(),
    val droppedInputFrames: Long = 0,
    val droppedCaptionLines: Long = 0,
    val isStopping: Boolean = false,
    val subtitleOnlyChannelIds: Set<String> = emptySet(),
    val unavailableAudioChannelIds: Set<String> = emptySet(),
    val contentCompleted: Boolean = false,
    val serverCloseRetryAvailable: Boolean = false,
) {
    val isActive: Boolean get() = isStopping || phase in setOf(MenuBroadcastPhase.STARTING,
        MenuBroadcastPhase.LIVE, MenuBroadcastPhase.PAUSED, MenuBroadcastPhase.COMPLETED)

    val canRequestStop: Boolean get() = isActive && (!isStopping ||
        (phase == MenuBroadcastPhase.FAILED && serverCloseRetryAvailable))

    /** The server remains owned until its close operation has finished. */
    fun withStopRequested(): MenuBroadcastState = copy(isStopping = true, serverCloseRetryAvailable = false)

    fun withClosedServer(failed: Boolean = false): MenuBroadcastState = copy(
        phase = if (failed) MenuBroadcastPhase.FAILED else MenuBroadcastPhase.IDLE,
        isStopping = false,
        serverCloseRetryAvailable = false,
        listenerUrl = null,
        channels = emptyList(),
        listenerCount = 0,
        subtitleOnlyChannelIds = emptySet(),
        unavailableAudioChannelIds = emptySet(),
        errorMessage = if (failed) errorMessage else null,
    )

    fun withServerCloseFailure(): MenuBroadcastState = copy(
        phase = MenuBroadcastPhase.FAILED,
        isStopping = true,
        serverCloseRetryAvailable = true,
        errorMessage = "웹 서버를 종료하지 못했습니다. 방송 종료를 다시 누르세요. 다른 방송은 시작할 수 없습니다.",
    )
}

/** Local media, live notes and saved recordings share one LAN broadcast. */
internal class MenuBroadcastController(private val app: GuideCastApplication) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Any()
    private val generations = AtomicLong()
    @Volatile private var playbackClock = MenuPlaybackClock()
    private val mutableState = MutableStateFlow(MenuBroadcastState())
    val state: StateFlow<MenuBroadcastState> = mutableState.asStateFlow()
    private var pending: Plan? = null
    private var work: Job? = null
    private var lease: WebBroadcastLease? = null
    private var startupWatchdog: Job? = null
    private var serverAwaitingClose: RunningGuideCastServer? = null
    @Volatile private var notePcm: Channel<ByteArray>? = null
    @Volatile private var noteLines: Channel<MenuBroadcastCaption>? = null
    @Volatile private var transcript = GuideCastTranscriptSnapshot(0, emptyList())
    private val transcriptGate = Any()
    private val captionSequences = linkedMapOf<Pair<Long, String>, Long>()
    private val acceptedNoteFinals = linkedSetOf<Pair<Long, String>>()
    private var noteFinalWatermark = -1L

    private sealed interface Plan {
        val origin: MenuBroadcastOrigin
        val title: String
        data class Note(val source: String, val targets: Set<String>, override val title: String,
            val localTranslationAllowed: Boolean, val useGemma: Boolean,
            val onlineOptions: TranslationApiOptions? = null) : Plan {
            override val origin = MenuBroadcastOrigin.NOTES
        }
        data class Media(override val origin: MenuBroadcastOrigin, val uri: Uri,
            override val title: String, val source: String, val captions: List<MenuBroadcastCaption>) : Plan
        data class History(val id: String, val channelIds: Set<String>, override val title: String) : Plan {
            override val origin = MenuBroadcastOrigin.HISTORY
        }
    }

    fun owns(origin: MenuBroadcastOrigin): Boolean = state.value.isActive && state.value.origin == origin
    private fun listenerStatus(): GuideCastBroadcastStatus {
        val snapshot = state.value
        val phase = when (snapshot.phase) {
            MenuBroadcastPhase.IDLE -> GuideCastBroadcastPhase.IDLE
            MenuBroadcastPhase.STARTING -> GuideCastBroadcastPhase.PREPARING
            MenuBroadcastPhase.LIVE -> GuideCastBroadcastPhase.LIVE
            MenuBroadcastPhase.PAUSED -> GuideCastBroadcastPhase.PAUSED
            MenuBroadcastPhase.COMPLETED -> GuideCastBroadcastPhase.COMPLETED
            MenuBroadcastPhase.FAILED -> GuideCastBroadcastPhase.FAILED
        }
        val next = when (phase) {
            GuideCastBroadcastPhase.FAILED -> GuideCastListenerNextAction.ASK_BROADCASTER_TO_RETRY
            GuideCastBroadcastPhase.COMPLETED -> GuideCastListenerNextAction.USE_REPLAY
            GuideCastBroadcastPhase.IDLE, GuideCastBroadcastPhase.PREPARING,
            GuideCastBroadcastPhase.PAUSED -> GuideCastListenerNextAction.WAIT_FOR_BROADCASTER
            GuideCastBroadcastPhase.LIVE -> GuideCastListenerNextAction.NONE
        }
        return GuideCastBroadcastStatus(phase, snapshot.channels.associate { channel ->
            val ready = when {
                phase == GuideCastBroadcastPhase.FAILED || channel.id in snapshot.unavailableAudioChannelIds -> GuideCastChannelReadiness.UNAVAILABLE
                phase == GuideCastBroadcastPhase.PREPARING -> GuideCastChannelReadiness.PREPARING
                channel.id in snapshot.subtitleOnlyChannelIds -> GuideCastChannelReadiness.SUBTITLES_ONLY
                else -> GuideCastChannelReadiness.READY
            }
            val hasCaption = snapshot.captions.any { caption -> if (channel.id == "source")
                caption.original.isNotBlank() else !caption.translations[channel.languageTag].isNullOrBlank() }
            val transcriptReadiness = when {
                hasCaption -> GuideCastChannelReadiness.READY
                phase in setOf(GuideCastBroadcastPhase.COMPLETED, GuideCastBroadcastPhase.FAILED) -> GuideCastChannelReadiness.UNAVAILABLE
                else -> GuideCastChannelReadiness.PREPARING
            }
            channel.id to GuideCastChannelStatus(ready, transcriptReadiness = transcriptReadiness, nextAction =
                if (ready in setOf(GuideCastChannelReadiness.UNAVAILABLE, GuideCastChannelReadiness.SUBTITLES_ONLY))
                    GuideCastListenerNextAction.SELECT_ANOTHER_CHANNEL else next)
        }, next)
    }
    fun startLiveNote(source: String, target: Set<String>, title: String,
        translateFinalCaptions: Boolean = true): Boolean {
        require(validMenuLanguage(source) && target.size <= 7 && target.all(::validMenuLanguage))
        val settings = app.serviceMenuProfiles.settings(ServiceMenuProfile.NOTES)
        val selected = settings.state.value
        val needsOnline = translateFinalCaptions && selected.provider != TranslationApiProvider.LOCAL
        if (needsOnline && (!isOnlineTextApiProfile(selected) || !settings.authorized(selected))) {
            synchronized(gate) {
                if (!state.value.isActive) mutableState.value = MenuBroadcastState(phase = MenuBroadcastPhase.FAILED,
                    origin = MenuBroadcastOrigin.NOTES,
                    errorMessage = "이 메뉴 설정에서 문장 번역 서비스·API 키·온라인 문장 전송 동의를 확인한 뒤 웹 방송을 시작하세요.")
            }
            return false
        }
        return request(Plan.Note(source, target.filterNot { it.equals(source, true) }.toSet(), title,
            translateFinalCaptions && selected.provider == TranslationApiProvider.LOCAL,
            app.serviceMenuProfiles.state.value.getValue(ServiceMenuProfile.NOTES).localEngine == FileTranslationEngine.GEMMA,
            selected.takeIf { needsOnline }))
    }
    fun startLiveNote(source: String, target: String, title: String): Boolean =
        startLiveNote(source, setOf(target), title)
    fun startMedia(origin: MenuBroadcastOrigin, uri: Uri, title: String, source: String,
        captions: List<MenuBroadcastCaption>): Boolean {
        require(origin != MenuBroadcastOrigin.HISTORY && validMenuLanguage(source))
        require(uri.scheme in setOf("content", "file")) { "기기에 저장된 미디어 파일을 선택하세요." }
        return request(Plan.Media(origin, uri, title, source, captions.sortedBy { it.startMs }))
    }
    fun startRecording(id: String, channelIds: Set<String>, title: String): Boolean {
        require(id.matches(Regex("[A-Za-z0-9-]{1,80}")) && channelIds.size <= 8)
        return request(Plan.History(id, channelIds, title))
    }

    private fun request(plan: Plan): Boolean = synchronized(gate) {
        if (state.value.isActive) return@synchronized false
        val live = app.broadcastRuntime.state.value
        if (live.phase in setOf(BroadcastPhase.STARTING, BroadcastPhase.LIVE, BroadcastPhase.PAUSED) ||
            live.inputPhase in setOf(InputPhase.STARTING, InputPhase.ACTIVE, InputPhase.PAUSED) ||
            (app.audioStreams.currentSession().isActive() && app.audioStreams.currentSession().channels.isNotEmpty())) {
            mutableState.value = MenuBroadcastState(phase = MenuBroadcastPhase.FAILED, origin = plan.origin,
                errorMessage = "사용 중인 통번역 방송을 종료한 뒤 이 콘텐츠를 방송하세요.")
            return@synchronized false
        }
        val generation = generations.incrementAndGet()
        val acquired = app.webBroadcastOwnership.tryAcquire("menu:$generation")
        if (acquired == null) {
            mutableState.value = MenuBroadcastState(phase = MenuBroadcastPhase.FAILED, origin = plan.origin,
                errorMessage = "다른 방송을 종료한 뒤 다시 시작하세요.")
            return@synchronized false
        }
        lease = acquired
        pending = plan
        playbackClock = MenuPlaybackClock()
        mutableState.value = MenuBroadcastState(generation, MenuBroadcastPhase.STARTING, plan.origin,
            plan.title.take(120), warningMessage = "같은 Wi-Fi 또는 핫스팟에 연결한 청취자가 들을 수 있습니다.")
        try {
            ContextCompat.startForegroundService(app, Intent(app, MenuMediaBroadcastService::class.java)
                .setAction(MenuMediaBroadcastService.ACTION_START)
                .putExtra(MenuMediaBroadcastService.EXTRA_GENERATION, generation))
            startupWatchdog = scope.launch {
                delay(15_000)
                synchronized(gate) {
                    if (state.value.generation == generation && pending != null) {
                        pending = null; lease?.close(); lease = null
                        mutableState.value = state.value.copy(phase = MenuBroadcastPhase.FAILED,
                            errorMessage = "방송 서비스를 시작하지 못했습니다. 앱을 열어 다시 시작하세요.")
                        app.stopService(Intent(app, MenuMediaBroadcastService::class.java))
                    }
                }
            }
            true
        } catch (_: Exception) {
            pending = null; acquired.close(); lease = null
            mutableState.value = state.value.copy(phase = MenuBroadcastPhase.FAILED,
                errorMessage = "방송 서비스를 시작하지 못했습니다. 앱을 열어 다시 시작하세요.")
            false
        }
    }

    internal fun serviceStarted(generation: Long): Boolean = synchronized(gate) {
        if (generation != state.value.generation || pending == null || work != null) return@synchronized false
        val plan = requireNotNull(pending); pending = null
        startupWatchdog?.cancel(); startupWatchdog = null
        work = scope.launch { run(plan, generation) }
        true
    }
    internal fun serviceDestroyed(generation: Long) {
        if (state.value.generation == generation && state.value.isActive) stop(generation)
    }

    fun offerNotePcm(bytes: ByteArray, rate: Int, generation: Long = state.value.generation): Boolean {
        require(rate == 16_000 && bytes.isNotEmpty() && bytes.size % 2 == 0 && bytes.size <= 32_000)
        val accepted = synchronized(gate) {
            if (!current(generation) || state.value.origin != MenuBroadcastOrigin.NOTES || state.value.phase != MenuBroadcastPhase.LIVE) return false
            notePcm?.trySend(bytes.copyOf())?.isSuccess == true
        }
        if (!accepted) update(generation) { it.copy(droppedInputFrames = it.droppedInputFrames + 1,
            warningMessage = "원음 송출 대기량이 많아 일부 음성이 누락됐습니다.") }
        return accepted
    }
    fun offerNoteLine(line: MenuBroadcastCaption, generation: Long = state.value.generation): Boolean {
        val accepted = synchronized(gate) {
            if (!current(generation) || state.value.origin != MenuBroadcastOrigin.NOTES || state.value.phase != MenuBroadcastPhase.LIVE) return false
            val key = line.startMs to line.original
            if (line.isFinal && key in acceptedNoteFinals) return true
            if (line.isFinal && line.startMs <= noteFinalWatermark) return false
            val added = noteLines?.trySend(line)?.isSuccess == true
            if (added && line.isFinal) {
                acceptedNoteFinals.add(key)
                while (acceptedNoteFinals.size > 1_024) {
                    val oldest = acceptedNoteFinals.first()
                    acceptedNoteFinals.remove(oldest)
                    noteFinalWatermark = maxOf(noteFinalWatermark, oldest.first)
                }
            }
            added
        }
        if (!accepted) update(generation) { it.copy(droppedCaptionLines = it.droppedCaptionLines + 1,
            warningMessage = "자막 대기량이 많아 일부 문장을 송출하지 못했습니다.") }
        return accepted
    }
    /** Normal note completion drains accepted final captions/TTS; explicit stop still cancels. */
    fun finishLiveNoteInput(generation: Long = state.value.generation): Unit = synchronized(gate) {
        if (!current(generation) || state.value.origin != MenuBroadcastOrigin.NOTES) return@synchronized
        notePcm?.close(); noteLines?.close()
        Unit
    }
    fun pause(expectedGeneration: Long = state.value.generation): Unit = synchronized(gate) {
        if (!current(expectedGeneration)) return@synchronized
        val generation = expectedGeneration
        if (state.value.phase != MenuBroadcastPhase.LIVE) return@synchronized
        playbackClock.setPaused(true)
        update(generation) { it.copy(phase = MenuBroadcastPhase.PAUSED) }
        while (notePcm?.tryReceive()?.isSuccess == true) Unit
        while (noteLines?.tryReceive()?.isSuccess == true) Unit
    }
    fun resume(expectedGeneration: Long = state.value.generation): Unit = synchronized(gate) {
        if (!current(expectedGeneration)) return@synchronized
        val generation = expectedGeneration
        if (state.value.phase != MenuBroadcastPhase.PAUSED) return@synchronized
        playbackClock.setPaused(false)
        update(generation) { it.withResumedContent() }
    }
    fun stop(expectedGeneration: Long = state.value.generation): Unit = synchronized(gate) {
        if (state.value.generation != expectedGeneration) return@synchronized
        if (state.value.isStopping && work != null) return@synchronized
        val retained = serverAwaitingClose
        if (retained != null) {
            if (work != null) return@synchronized
            val generation = state.value.generation
            mutableState.value = state.value.withStopRequested()
            work = scope.launch {
                val released = runCatching { retained.close() }.isSuccess
                synchronized(gate) {
                    work = null
                    if (released && state.value.generation == generation) {
                        serverAwaitingClose = null
                        lease?.close(); lease = null
                        update(generation) { it.withClosedServer() }
                        app.stopService(Intent(app, MenuMediaBroadcastService::class.java))
                    } else update(generation) { it.withServerCloseFailure() }
                }
            }
            return@synchronized
        }
        startupWatchdog?.cancel(); startupWatchdog = null
        notePcm?.close(); noteLines?.close()
        pending = null
        val job = work
        if (job != null) {
            mutableState.value = state.value.withStopRequested()
            job.cancel()
        } else {
            lease?.close(); lease = null
            mutableState.value = state.value.withClosedServer()
            app.stopService(Intent(app, MenuMediaBroadcastService::class.java))
        }
        Unit
    }

    private fun current(generation: Long): Boolean = state.value.generation == generation && state.value.isActive && !state.value.isStopping
    private inline fun update(generation: Long, transform: (MenuBroadcastState) -> MenuBroadcastState) {
        mutableState.update { if (it.generation == generation) transform(it) else it }
    }
    private suspend fun awaitPlaying(generation: Long, workClock: MenuPlaybackClock? = null) {
        try {
            while (current(generation) && state.value.phase == MenuBroadcastPhase.PAUSED) {
                workClock?.setPaused(true)
                delay(30)
            }
            currentCoroutineContext().ensureActive()
            check(current(generation)) { "방송이 종료됐습니다." }
        } finally { workClock?.setPaused(false) }
    }

    private suspend fun run(plan: Plan, generation: Long) {
        var stream: StreamSession? = null
        var server: RunningGuideCastServer? = null
        var recordedId: String? = null
        var observer: Job? = null
        var recordOutput = false
        var failed = false
        try {
            val stored = if (plan is Plan.History) requireNotNull(app.recordings.audio.snapshot(plan.id)) {
                "저장된 방송을 찾을 수 없습니다." } else null
            val channels = descriptors(plan, stored)
            val address = LocalNetworkAddressResolver.resolve()
                ?: error("Wi-Fi 또는 핫스팟에 연결한 뒤 방송을 시작하세요.")
            currentCoroutineContext().ensureActive()
            synchronized(transcriptGate) {
                transcript = GuideCastTranscriptSnapshot(0, emptyList())
                captionSequences.clear()
            }
            val session = app.audioStreams.configure(channels)
            stream = session
            if (plan !is Plan.History) {
                recordedId = app.recordings.startPart(session, plan.title)
                recordOutput = true
            }
            val recording = recordedId
            val captionRecording = stored?.id ?: recording
            val localServer = GuideCastLocalServer(app, app.audioStreams,
                transcriptSnapshotProvider = { transcript },
                replayProvider = { if (stored != null) stored.copy(segments = stored.segments.filter { segment ->
                    channels.any { it.id == segment.channel.id } }) else recording?.let { app.recordings.audio.snapshot(it) } },
                replayCaptionSnapshotProvider = captionRecording?.let { id -> {
                    val committed = app.recordings.committedCaptionLength(id)
                    ReplayCaptionSnapshot(committed) { part, sequence ->
                        val after = if (part != null && sequence != null) part to sequence else null
                        recordedReplayCaptionJson(app.recordings.captions(id, after, committedLength = committed),
                            channels.map { it.languageTag }.toSet(), allowNativeGroups = after == null)
                    }
                } },
                isSpeakerInputReady = { false },
                broadcastStatusProvider = ::listenerStatus,
                isLiveAudioBroadcastEnabled = { current(generation) && state.value.permitsLiveAudio() })
            val runningServer = localServer.start(address.address, GuideCastServerConfig(), session)
            server = runningServer
            currentCoroutineContext().ensureActive()
            if (plan is Plan.Note) {
                synchronized(gate) {
                    acceptedNoteFinals.clear(); noteFinalWatermark = -1
                    notePcm = Channel(128)
                    noteLines = Channel(16)
                }
            }
            update(generation) { it.copy(phase = MenuBroadcastPhase.LIVE, listenerUrl = runningServer.listenerUrl,
                channels = channels, errorMessage = null,
                warningMessage = if (plan is Plan.History) null else unavailableVoices(channels.drop(1)),
                subtitleOnlyChannelIds = channels.drop(1).filter { descriptor -> plan !is Plan.History &&
                    (!readyVoice(descriptor.languageTag) || (plan is Plan.Note && !noteTranslationReady(plan, descriptor.languageTag)))
                }.map { it.id }.toSet()) }
            observer = scope.launch {
                while (current(generation)) {
                    val snapshot = session.observabilitySnapshot()
                    update(generation) { it.copy(listenerCount = snapshot.totalListeners,
                        webSocketDeliveredFrames = snapshot.webSocketDeliveredFrames) }
                    delay(200)
                }
            }
            when (plan) {
                is Plan.Note -> runNote(plan, generation, session)
                is Plan.Media -> runMedia(plan, generation, session)
                is Plan.History -> runHistory(requireNotNull(stored), channels, generation, session)
            }
            if (recordOutput) withTimeout(15_000) { withContext(Dispatchers.IO) { app.recordings.flush() } }
            synchronized(gate) {
                if (current(generation)) update(generation) { it.withCompletedContent() }
            }
            // Stored audio remains available without decoder, capture, synthesis or provider work.
            while (current(generation)) delay(200)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            failed = true
            update(generation) { it.copy(phase = MenuBroadcastPhase.FAILED, isStopping = true,
                errorMessage = "음성 방송을 준비하거나 송출하지 못했습니다. 저장 파일·Wi-Fi를 확인하고 다시 시작하세요.") }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                observer?.cancelAndJoin()
                notePcm?.close(); notePcm = null
                noteLines?.close(); noteLines = null
                stream?.close()
                val serverCloseFailure = runCatching { server?.close() }.exceptionOrNull()
                if (recordOutput) runCatching { app.recordings.finish(failed) }
                synchronized(gate) {
                    if (state.value.generation == generation) {
                        work = null
                        if (serverCloseFailure != null) {
                            serverAwaitingClose = server
                            update(generation) { it.withServerCloseFailure() }
                        } else {
                            lease?.close(); lease = null
                            update(generation) { it.withClosedServer(failed) }
                            app.stopService(Intent(app, MenuMediaBroadcastService::class.java))
                        }
                    }
                }
            }
        }
    }

    private fun descriptors(plan: Plan, stored: RecordedBroadcast?): List<AudioChannelDescriptor> {
        if (plan is Plan.History) {
            val segments = requireNotNull(stored).segments.filter { plan.channelIds.isEmpty() || it.channel.id in plan.channelIds }
            require(segments.isNotEmpty()) { "선택한 방송에 저장된 음성이 없습니다." }
            val groups = segments.groupBy { it.channel.id }
            require(groups.values.all { parts -> parts.map { it.channel.sampleRateHz }.distinct().size == 1 }) {
                "샘플레이트가 다른 방송 부분은 따로 선택하세요." }
            return groups.values.map { recordedChannelForPresentation(it.first().channel) }.also { require(it.size <= 8) }
        }
        val source = when (plan) { is Plan.Note -> plan.source; is Plan.Media -> plan.source; else -> error("Unsupported source") }
        val targets = when (plan) {
            is Plan.Note -> plan.targets
            is Plan.Media -> plan.captions.flatMap { it.translations.keys }.distinct().toSet()
            else -> emptySet()
        }.filterNot { it.equals(source, true) }
        require(targets.size <= 7)
        return listOf(AudioChannelDescriptor("source", "원음", source, 16_000)) + targets.map { tag ->
            AudioChannelDescriptor(tag.lowercase(Locale.ROOT),
                Locale.forLanguageTag(tag).getDisplayLanguage(Locale.KOREAN).ifBlank { tag }.take(40), tag,
                MoonshineSpeechSynthesisProvider.OUTPUT_SAMPLE_RATE_HZ)
        }
    }

    private fun unavailableVoices(channels: List<AudioChannelDescriptor>): String? {
        val missing = channels.filterNot { readyVoice(it.languageTag) }
        return if (missing.isEmpty()) null else "${missing.joinToString { it.displayName }} 음성이 준비되지 않았습니다. 원음·자막을 방송하며, 음성 설정에서 먼저 준비하세요."
    }
    private fun readyVoice(language: String): Boolean = app.speechSynthesisProvider.hasActiveMoonshineWorker(language) ||
        app.speechSynthesisProvider.isFallbackReady(language)
    private fun noteTranslationReady(plan: Plan.Note, target: String): Boolean =
        if (plan.onlineOptions != null) app.serviceMenuProfiles.settings(ServiceMenuProfile.NOTES).authorized(plan.onlineOptions)
        else plan.localTranslationAllowed && if (plan.useGemma) app.gemmaTranslationProvider.hasActivePreparedWorker() &&
            GemmaTranslationProvider.supportsTranslation(plan.source, target)
        else app.translationProvider.hasActivePreparedWorker(target)

    private suspend fun runNote(plan: Plan.Note, generation: Long, session: StreamSession) = supervisorScope {
        val audio = requireNotNull(notePcm)
        val lines = requireNotNull(noteLines)
        val captions = launch {
            try { for (line in lines) {
                    if (!current(generation)) break
                    if (state.value.phase != MenuBroadcastPhase.LIVE) continue
                    try {
                        publishCaption(line, generation, session)
                        withMenuPlaybackTimeout(30_000, playbackClock) {
                            val translated = translatePreparedNote(line, plan, generation)
                            val outputAllowed = { current(generation) && (plan.onlineOptions == null ||
                                app.serviceMenuProfiles.settings(ServiceMenuProfile.NOTES).authorized(plan.onlineOptions)) }
                            if (!outputAllowed()) throw CancellationException("Note translation settings changed")
                            if (translated != line) publishCaption(translated, generation, session, outputAllowed = outputAllowed)
                            synthesizeCaption(translated, generation, session, outputAllowed)
                        }
                    } catch (_: MenuPlaybackDeadlineExceeded) {
                        update(generation) { it.copy(warningMessage = "일부 노트 번역 음성의 처리 시간이 초과됐습니다. 원음 방송은 계속됩니다.") }
                    } catch (cancelled: CancellationException) {
                        currentCoroutineContext().ensureActive()
                        if (!current(generation)) throw cancelled
                        update(generation) { it.copy(warningMessage = "일부 노트 번역 음성 처리가 중단됐습니다. 원음 방송은 계속됩니다.") }
                    } catch (_: Exception) {
                        update(generation) { it.copy(warningMessage = "일부 노트 번역 음성을 만들지 못했습니다. 원음 방송은 계속됩니다.") }
                    }
                }
            } finally { lines.close() }
        }
        try {
            for (bytes in audio) {
                if (!current(generation)) break
                if (state.value.phase != MenuBroadcastPhase.LIVE) continue
                publishSplit(session, "source", bytes, 16_000, generation, paced = false)
            }
            lines.close()
            try { withMenuPlaybackTimeout(30_000, playbackClock) { captions.join() } }
            catch (_: MenuPlaybackDeadlineExceeded) {
                update(generation) { it.copy(warningMessage = "남은 통역 음성을 모두 만들지 못했습니다. 원음과 저장된 자막은 다시 들을 수 있습니다.") }
            }
        } finally { captions.cancelAndJoin(); audio.close(); lines.close() }
    }

    private suspend fun runMedia(plan: Plan.Media, generation: Long, session: StreamSession) = supervisorScope {
        val captions = MenuCaptionWorkQueue(this, timeoutMillis = Long.MAX_VALUE, onFailure = {
            update(generation) { it.copy(warningMessage = "일부 통역 음성을 만들지 못했습니다. 원음과 자막은 계속 방송합니다.") }
        }) { line ->
            try { withMenuPlaybackTimeout(30_000, playbackClock) { synthesizeCaption(line, generation, session) } }
            catch (_: MenuPlaybackDeadlineExceeded) {
                update(generation) { it.copy(warningMessage = "일부 통역 음성의 처리 시간이 초과됐습니다. 원음과 자막은 계속 방송합니다.") }
            }
        }
        var next = 0
        try {
            FileAudioDecoder.decode(app, plan.uri, onInfo = { info ->
                update(generation) { it.copy(durationMs = info.durationMs) }
            }).collect { frame ->
                awaitPlaying(generation)
                while (next < plan.captions.size && plan.captions[next].startMs <= frame.startMs) {
                    val line = plan.captions[next++]
                    publishCaption(line, generation, session)
                    if (!captions.offer(line)) {
                        update(generation) { it.copy(droppedCaptionLines = it.droppedCaptionLines + 1,
                            warningMessage = "번역 음성의 대기량이 많습니다. 원음·자막은 유지하고 일부 통역 음성을 건너뜁니다.") }
                    }
                }
                publishSplit(session, "source", frame.bytes, 16_000, generation, paced = true)
                update(generation) { it.copy(positionMs = frame.endMs) }
            }
            while (next < plan.captions.size) {
                val line = plan.captions[next++]
                publishCaption(line, generation, session)
                if (!captions.offer(line)) update(generation) { it.copy(
                    droppedCaptionLines = it.droppedCaptionLines + 1,
                    warningMessage = "번역 음성의 대기량이 많습니다. 원음·자막은 유지하고 일부 통역 음성을 건너뜁니다.") }
            }
            val drained = try { withMenuPlaybackTimeout(30_000, playbackClock) { captions.drain(Long.MAX_VALUE) } }
                catch (_: MenuPlaybackDeadlineExceeded) { false }
            if (!drained) update(generation) { it.copy(
                warningMessage = "남은 통역 음성을 모두 송출하지 못했습니다. 원음과 자막은 저장했습니다.") }
        } finally { captions.cancel() }
    }

    private suspend fun runHistory(stored: RecordedBroadcast, channels: List<AudioChannelDescriptor>,
        generation: Long, session: StreamSession) = supervisorScope {
        val timelines = channels.associate { descriptor -> descriptor.id to stored.segments
            .filter { it.channel.id == descriptor.id }.sortedWith(compareBy<RecordedPcmSegment> { it.partId }.thenBy { it.segment }) }
        val duration = timelines.values.maxOf { parts -> parts.sumOf { it.committedBytes * 1000 / (2L * it.channel.sampleRateHz) } }
        update(generation) { it.copy(durationMs = duration,
            warningMessage = "저장된 채널별 음성을 재사용합니다. 원문과 통역 음원의 정확한 시간 정렬은 보장하지 않습니다.") }
        // The first page is immediately useful. Remaining rows are independently disk-paged
        // through the replay caption endpoint instead of blocking audio startup.
        publishHistoryCaptions(app.recordings.captions(stored.id, limit = 100), channels, generation)
        timelines.map { (id, parts) -> async {
            var position = 0L
            for (segment in parts) {
                RandomAccessFile(segment.file, "r").use { input ->
                    input.seek(segment.fileOffsetBytes)
                    var remaining = segment.committedBytes
                    require(remaining % 2 == 0L)
                    val bytes = ByteArray(segment.channel.sampleRateHz / 50 * 2)
                    while (remaining > 0) {
                        awaitPlaying(generation)
                        val count = minOf(bytes.size.toLong(), remaining).toInt()
                        input.readFully(bytes, 0, count)
                        publishSplit(session, id, bytes.copyOf(count), segment.channel.sampleRateHz, generation, true)
                        remaining -= count
                        position += count * 1000L / (2 * segment.channel.sampleRateHz)
                        update(generation) { it.copy(positionMs = maxOf(it.positionMs, position)) }
                    }
                }
            }
        } }.awaitAll()
    }

    private fun publishHistoryCaptions(rows: List<RecordedCaption>, channels: List<AudioChannelDescriptor>, generation: Long) {
        val projected = recordedHistoryCaptionProjection(rows, channels.map { it.languageTag }.toSet(), allowNativeGroups = true)
        synchronized(transcriptGate) {
            if (!current(generation)) return
            transcript = GuideCastTranscriptSnapshot(transcript.revision + 1, projected.map { item ->
                GuideCastTranscriptLine(item.publicationSequence, item.stored.original, item.stored.final,
                    item.stored.monotonicNanos, item.translations, emptyMap(), emptyMap(), emptyMap(),
                    liveSegmentLanguage = item.liveSegmentLanguage, liveOutputState = item.liveOutputState,
                    displayGroupSequence = item.displayGroupSequence)
            })
        }
        update(generation) { it.copy(captions = projected.map { item ->
            MenuBroadcastCaption(0, 0, item.stored.original, item.translations, item.stored.final)
        }) }
    }

    private fun publishCaption(line: MenuBroadcastCaption, generation: Long, session: StreamSession, record: Boolean = true,
        outputAllowed: () -> Boolean = { true }) {
        if (!current(generation) || !outputAllowed()) return
        val sequence = synchronized(transcriptGate) {
            if (!current(generation) || !outputAllowed()) return
            val revision = transcript.revision + 1
            val key = line.startMs to line.original
            val next = captionSequences.getOrPut(key) { revision }
            while (captionSequences.size > 1_000) captionSequences.remove(captionSequences.keys.first())
            val row = GuideCastTranscriptLine(next, line.original, line.isFinal,
                SystemClock.elapsedRealtimeNanos(), line.translations, emptyMap(), emptyMap(), emptyMap())
            transcript = GuideCastTranscriptSnapshot(revision,
                (transcript.lines.filterNot { it.sequence == next } + row).sortedBy { it.sequence }.takeLast(1_000))
            next
        }
        update(generation) { it.copy(captions = (it.captions.filterNot { row -> row.startMs == line.startMs && row.original == line.original } + line).takeLast(1_000)) }
        if (record) app.recordings.capture(session.generation, listOf(TranslationTranscriptLine(sequence,
            line.original, SystemClock.elapsedRealtimeNanos(), line.isFinal, translations = line.translations)))
    }

    private suspend fun translatePreparedNote(line: MenuBroadcastCaption, plan: Plan.Note,
        generation: Long): MenuBroadcastCaption = supervisorScope {
        if (!line.isFinal || line.original.isBlank() || !current(generation)) return@supervisorScope line
        if (plan.onlineOptions != null) return@supervisorScope translateOnlineNote(line, plan, generation)
        if (!plan.localTranslationAllowed) {
            if (plan.targets.any { line.translations[it].isNullOrBlank() }) update(generation) { it.copy(
                warningMessage = "원음과 현재 자막을 방송합니다. 노트 방송은 추가 온라인 번역을 자동 호출하지 않습니다.") }
            return@supervisorScope line
        }
        val results = plan.targets.filter { line.translations[it].isNullOrBlank() }.map { target -> async {
            try {
                val engine = if (plan.useGemma) {
                    if (!app.gemmaTranslationProvider.hasActivePreparedWorker() ||
                        !GemmaTranslationProvider.supportsTranslation(plan.source, target)) {
                        update(generation) { it.copy(warningMessage = "선택한 오프라인 번역 모델이 준비되지 않았습니다. 원음 방송은 계속됩니다.") }
                        return@async null
                    }
                    app.gemmaTranslationProvider.preparedEngineFor(target)
                } else {
                    if (!app.translationProvider.hasActivePreparedWorker(target)) {
                        update(generation) { it.copy(warningMessage = "번역 모델이 준비되지 않았습니다. 공통 설정에서 먼저 준비하세요. 원음 방송은 계속됩니다.") }
                        return@async null
                    }
                    app.translationProvider.engineFor(target)
                }
                val translated = withTimeout(8_000) { app.withTranslationBackendUse {
                    if (!current(generation)) throw CancellationException("Broadcast ended")
                    engine.translate(line.original, plan.source, target)
                } }
                if (current(generation) && translated.isNotBlank()) target to translated else null
            } catch (_: TimeoutCancellationException) {
                update(generation) { it.copy(warningMessage = "로컬 번역 시간이 초과됐습니다. 원음과 자막은 계속 방송합니다.") }; null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                update(generation) { it.copy(warningMessage = "준비된 로컬 번역을 사용할 수 없습니다. 원음과 자막은 계속 방송합니다.") }; null
            }
        } }.awaitAll().filterNotNull().toMap()
        line.copy(translations = line.translations + results)
    }

    private suspend fun translateOnlineNote(line: MenuBroadcastCaption, plan: Plan.Note,
        generation: Long): MenuBroadcastCaption = supervisorScope {
        val selected = requireNotNull(plan.onlineOptions)
        val settings = app.serviceMenuProfiles.settings(ServiceMenuProfile.NOTES)
        fun allowed() = current(generation) && settings.authorized(selected)
        check(allowed()) { "노트 방송의 온라인 문장 전송 동의 또는 설정이 변경됐습니다." }
        val missing = plan.targets.filter { line.translations[it].isNullOrBlank() }
        if (missing.isEmpty()) return@supervisorScope line
        val sequence = requireNotNull(synchronized(transcriptGate) { captionSequences[line.startMs to line.original] })
        val requestScope = "menu-note:$generation"
        val identity = TranslationRequestIdentity(requestScope, sequence)
        val preparedLocal = TextTranslationEngine { text, source, target ->
            check(allowed())
            val local = if (plan.useGemma) {
                check(app.gemmaTranslationProvider.hasActivePreparedWorker() && GemmaTranslationProvider.supportsTranslation(source, target))
                app.gemmaTranslationProvider.preparedEngineFor(target)
            } else {
                check(app.translationProvider.hasActivePreparedWorker(target))
                app.translationProvider.engineFor(target)
            }
            app.withTranslationBackendUse { local.translate(text, source, target) }
        }
        val engine = app.serviceMenuProfiles.service(ServiceMenuProfile.NOTES).engine(
            DomainCorpusTranslationEngine(preparedLocal, app.domainCorpus) { 600 }, expectedOptions = selected)
        val batch = if (selected.provider in setOf(TranslationApiProvider.GEMINI, TranslationApiProvider.OPENAI))
            SharedTranslationBatchContext(requestScope, missing, this, ::allowed) else null
        val context = TranslationStyleContext(selected.tone) + identity +
            (batch ?: kotlin.coroutines.EmptyCoroutineContext)
        val translated = withContext(context) {
            missing.map { target -> async {
                check(allowed())
                val value = if (engine is ContextualTextTranslationEngine)
                    engine.translateWithContext(line.original, null, plan.source, target)
                else engine.translate(line.original, plan.source, target)
                check(allowed() && value.isNotBlank())
                target to value
            } }.awaitAll().toMap()
        }
        check(allowed())
        // The shared service validates source meaning and every batch language before release.
        line.copy(translations = line.translations + translated)
    }

    private suspend fun synthesizeCaption(line: MenuBroadcastCaption, generation: Long, session: StreamSession,
        outputAllowed: () -> Boolean = { true }) = supervisorScope {
        if (!line.isFinal) return@supervisorScope
        if (!outputAllowed()) throw CancellationException("Translation output is no longer authorized")
        val sequence = synchronized(transcriptGate) { captionSequences[line.startMs to line.original] }
        line.translations.mapNotNull { (tag, text) -> session.channels.firstOrNull { it.id != "source" && it.languageTag == tag }
            ?.takeIf { text.isNotBlank() }?.let { channel -> async {
                if (!readyVoice(tag)) return@async
                val synthesisClock = MenuPlaybackClock()
                try {
                    withMenuPlaybackTimeout(30_000, synthesisClock) {
                        app.withTranslationBackendUse {
                            check(current(generation) && readyVoice(tag))
                            if (!outputAllowed()) throw CancellationException("Translation output is no longer authorized")
                            app.speechSynthesisProvider.engineFor(tag).synthesize(text, tag).collect { frame ->
                                publishSplit(session, channel.id, frame.bytes, channel.sampleRateHz, generation, true, sequence, synthesisClock, outputAllowed)
                            }
                        }
                    }
                } catch (_: MenuPlaybackDeadlineExceeded) {
                    update(generation) { it.copy(warningMessage = "${channel.displayName} 음성 합성 시간이 초과됐습니다. 원음과 자막은 계속 방송합니다.",
                        unavailableAudioChannelIds = it.unavailableAudioChannelIds + channel.id) }
                } catch (cancelled: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    if (!current(generation)) throw cancelled
                    update(generation) { it.copy(warningMessage = "${channel.displayName} 음성 처리가 중단됐습니다. 다음 문장과 원음 방송은 계속합니다.",
                        unavailableAudioChannelIds = it.unavailableAudioChannelIds + channel.id) }
                }
                catch (_: Exception) { update(generation) { it.copy(warningMessage = "${channel.displayName} 음성 합성에 실패했습니다. 원음과 자막은 계속 방송합니다.",
                    unavailableAudioChannelIds = it.unavailableAudioChannelIds + channel.id) } }
            } } }.awaitAll()
    }

    private suspend fun publishSplit(session: StreamSession, channel: String, bytes: ByteArray, rate: Int,
        generation: Long, paced: Boolean, utteranceSequence: Long? = null, workClock: MenuPlaybackClock? = null,
        outputAllowed: () -> Boolean = { true }) {
        require(bytes.size % 2 == 0)
        val chunk = rate / 50 * 2
        var offset = 0
        while (offset < bytes.size) {
            awaitPlaying(generation, workClock)
            if (!outputAllowed()) throw CancellationException("Translation output is no longer authorized")
            val end = minOf(offset + chunk, bytes.size)
            val part = bytes.copyOfRange(offset, end)
            check(session.tryPublish(channel, PcmAudioFrame(part, SystemClock.elapsedRealtimeNanos(), utteranceSequence)).accepted)
            update(generation) { if (channel == "source") it.copy(sourcePublishedBytes = it.sourcePublishedBytes + part.size,
                positionMs = if (it.origin == MenuBroadcastOrigin.NOTES && notePcm != null)
                    (it.sourcePublishedBytes + part.size) * 1000L / (2 * rate) else it.positionMs)
                else it.copy(translatedPublishedBytes = it.translatedPublishedBytes + part.size,
                    subtitleOnlyChannelIds = it.subtitleOnlyChannelIds - channel,
                    unavailableAudioChannelIds = it.unavailableAudioChannelIds - channel) }
            if (paced) delay(maxOf(1, part.size * 1000L / (rate * 2)))
            offset = end
        }
    }
}

private fun validMenuLanguage(tag: String): Boolean = Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*").matches(tag)
