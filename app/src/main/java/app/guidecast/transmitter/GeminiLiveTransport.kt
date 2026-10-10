package app.guidecast.transmitter

import app.guidecast.core.translation.TranslationStyle
import app.guidecast.core.audio.pcmS16LeSignalStats
import app.guidecast.core.translation.interpretationInstructions

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

internal const val GEMINI_LIVE_TRANSLATE = "gemini-3.5-live-translate-preview"
internal const val GEMINI_LIVE_AGENT = "gemini-3.8-live"
internal fun geminiLiveTarget(tag: String): String = when (tag.lowercase()) {
    "zh-tw", "zh-hant" -> "zh-Hant"
    "zh-cn", "zh-hans", "zh" -> "zh-Hans"
    "pt-br" -> "pt-BR"
    "pt-pt" -> "pt-PT"
    else -> tag.substringBefore('-').lowercase().also { require(it.matches(Regex("[a-z]{2,3}"))) }
}
internal fun geminiLiveSetup(model: String, target: String, domainPrompt: String = "", tone: TranslationStyle = TranslationStyle.CONVERSATIONAL,
    interpreterInstructions: String = "", references: String = "", liveVoice: RelayVoiceGender = RelayVoiceGender.AUTO,
    diagnostics: GeminiWireDiagnostics? = null, resumptionHandle: String? = null, sourceLanguageTag: String? = null, explicitActivity: Boolean = false): String {
    require(model in setOf(GEMINI_LIVE_TRANSLATE, GEMINI_LIVE_AGENT))
    require(model == GEMINI_LIVE_AGENT || resumptionHandle == null)
    require(validInterpreterDomain(domainPrompt))
    require(model != GEMINI_LIVE_TRANSLATE || domainPrompt.isEmpty()) { "Live Translate does not support domain instructions" }
    require(model != GEMINI_LIVE_TRANSLATE || (interpreterInstructions.isEmpty() && references.isEmpty())) {
        "Live Translate does not support reference text or instructions"
    }
    val generation = JSONObject().put("responseModalities", JSONArray().put("AUDIO"))
    val setup = JSONObject().put("model", "models/$model").put("generationConfig", generation)
    if (model == GEMINI_LIVE_TRANSLATE) {
        setup.put("inputAudioTranscription", JSONObject()).put("outputAudioTranscription", JSONObject())
        generation.put("translationConfig", JSONObject().put("targetLanguageCode", geminiLiveTarget(target)).put("echoTargetLanguage", false))
    } else {
        generation.put("maxOutputTokens", 2_048)
        setup.put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
        setup.put("sessionResumption", JSONObject().apply { resumptionHandle?.let { handle ->
            require(handle.isNotBlank() && handle.length <= 8_192 && handle.none(Char::isISOControl))
            put("handle", handle)
        } })
        setup.put("realtimeInputConfig", JSONObject().put("activityHandling", "NO_INTERRUPTION").apply {
            if (explicitActivity) put("automaticActivityDetection", JSONObject().put("disabled", true))
        })
        geminiRelayVoiceName(model, liveVoice)?.let { voice ->
            generation.put("speechConfig", JSONObject().put("voiceConfig", JSONObject()
                .put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice))))
        }
        setup.put("inputAudioTranscription", JSONObject().apply {
            sourceLanguageTag?.let { selectedSource ->
                put("languageCodes", JSONArray().put(nativeInterpreterSourceLanguage(selectedSource).languageTag))
            }
        }).put("outputAudioTranscription", JSONObject())
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text",
                nativeInterpreterInstructions(geminiLiveTarget(target), tone, domainPrompt, interpreterInstructions, references, sourceLanguageTag)))))
    }
    diagnostics?.observeSetup(model, setup.optJSONObject("inputAudioTranscription") != null,
        setup.optJSONObject("outputAudioTranscription") != null)
    return JSONObject().put("setup", setup).toString()
}

internal interface GeminiLiveWire {
    suspend fun connect(key: String, authorized: () -> Boolean, block: suspend (RealtimeSocket) -> Unit)
}
/** No URL credentials, redirects, SDK telemetry or logging. Socket lifetime belongs to the caller. */
internal class KtorGeminiLiveWire : GeminiLiveWire {
    override suspend fun connect(key: String, authorized: () -> Boolean, block: suspend (RealtimeSocket) -> Unit) {
        check(authorized())
        val client = HttpClient(CIO) { install(WebSockets) { maxFrameSize = 262_144 }; followRedirects = false; expectSuccess = true }
        try {
            val socket = try { withTimeout(6_000) {
                check(authorized())
                client.webSocketSession(urlString = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent") {
                    header("x-goog-api-key", key)
                }
            } } catch (failure: ResponseException) {
                // Provider bodies may echo private data. Discard the body and original cause here.
                throw OnlineProviderFailure(onlineHttpFailure(failure.response.status.value))
            }
            try {
                block(object : RealtimeSocket {
                    override fun abort() { socket.cancel() }
                    override suspend fun send(text: String) { currentCoroutineContext().ensureActive(); check(authorized()); socket.send(Frame.Text(text)) }
                    override suspend fun receive(): String {
                        currentCoroutineContext().ensureActive(); check(authorized())
                        val frame = socket.incoming.receive(); check(authorized())
                        return when (frame) {
                            is Frame.Text -> frame.readText()
                            is Frame.Binary -> frame.data.toString(Charsets.UTF_8)
                            else -> error("Live connection closed")
                        }.also { require(it.length <= 262_144) }
                    }
                })
            } finally { socket.cancel() }
        } finally { client.close() }
    }
}
internal data class GeminiLiveEvent(val source: String?, val translation: String?, val finished: Boolean,
    val interrupted: Boolean, val audio: List<ByteArray>, val usage: JSONObject?, val timingTurn: Long? = null,
    val serverContent: Boolean = false, val goAwayTimeLeftMillis: Long? = null,
    val resumptionUpdate: GeminiLiveResumptionUpdate? = null)
internal fun parseGeminiLiveEvent(raw: String, diagnostics: GeminiWireDiagnostics? = null, allowRenewal: Boolean = false): GeminiLiveEvent {
    requireBoundedJson(raw, maximumChars = 262_144)
    val root = JSONObject(raw)
    diagnostics?.observeMessageShape(root)
    if (root.has("error")) throw onlineProviderFailure(root)
    check(!root.has("toolCall") && (allowRenewal || !root.has("goAway"))) { "Live session requires restart" }
    val content = root.optJSONObject("serverContent")
    val interrupted = content?.optBoolean("interrupted", false) == true
    val parts = content?.optJSONObject("modelTurn")?.optJSONArray("parts")
    val audio = mutableListOf<ByteArray>()
    if (!interrupted && parts != null) for (index in 0 until parts.length()) {
        parts.getJSONObject(index).optJSONObject("inlineData")?.let { blob ->
            require(blob.getString("mimeType") in setOf("audio/pcm;rate=24000", "audio/pcm;rate=24000;channels=1"))
            val bytes = Base64.getDecoder().decode(blob.getString("data"))
            require(bytes.isNotEmpty() && bytes.size <= 96_000 && bytes.size % 2 == 0)
            audio += bytes
        }
    }
    fun transcript(field: String) = geminiTranscriptionFragment(
        content?.optJSONObject(field)?.optString("text")?.also { require(it.length <= 8_000) })
    return GeminiLiveEvent(transcript("inputTranscription"), transcript("outputTranscription"),
        content?.optBoolean("turnComplete", false) == true, interrupted, audio, root.optJSONObject("usageMetadata"),
        serverContent = content != null,
        goAwayTimeLeftMillis = if (allowRenewal && root.has("goAway")) geminiGoAwayMillis(root.getJSONObject("goAway")) else null,
        resumptionUpdate = if (allowRenewal && root.has("sessionResumptionUpdate")) geminiResumptionUpdate(root.getJSONObject("sessionResumptionUpdate")) else null)
}

private fun recordGeminiManualTrace(snapshot: GeminiManualTraceSnapshot) {
    RuntimeDiagnosticLog.durableRecord("native_manual_trace", snapshot.detail())
    snapshot.epochs.forEach { RuntimeDiagnosticLog.durableRecord("native_manual_epoch", "drain=${snapshot.drainOrdinal} ${it.detail()}") }
}

/** General-agent renewal only transfers packets never attempted on the previous socket. */
internal class GeminiLiveTransport(private val wire: GeminiLiveWire = KtorGeminiLiveWire(),
    private val nowNanos: () -> Long = System::nanoTime) {
    private val renewalMaximumBytes = 640_000
    suspend fun run(key: String, model: String, target: String, input: Flow<ByteArray>, authorized: () -> Boolean,
        onReady: () -> Unit, onEvent: suspend (GeminiLiveEvent) -> Unit, domainPrompt: String = "", tone: TranslationStyle = TranslationStyle.CONVERSATIONAL,
        durationLimitMillis: Long? = null, onAudioSent: (Int) -> Unit = {},
        timing: NativeLiveTiming? = null, diagnostics: GeminiWireDiagnostics? = null,
        interpreterInstructions: String = "", references: String = "", liveVoice: RelayVoiceGender = RelayVoiceGender.AUTO,
        onAudioSendUnconfirmed: (Int) -> Unit = {}, onPreparedAudioDiscarded: (Int) -> Unit = {},
        onConnectionAttempt: (Int, Boolean, GeminiWireDiagnostics) -> Unit = { _, _, _ -> },
        onConnectionClosed: (Int, GeminiWireDiagnostics) -> Unit = { _, _ -> },
        onRenewalNotice: (GeminiLiveRenewalNotice) -> Unit = {},
        onSourceDelayNotice: suspend (GeminiLiveSourceDelayNotice) -> Unit = {}, sourceLanguageTag: String? = null,
        inputDrain: NativeInputDrainState? = null, explicitActivity: Boolean = false, drainTraceOrdinal: Long? = null,
        onManualTrace: (GeminiManualTraceSnapshot) -> Unit = ::recordGeminiManualTrace) {
        val source = if (model == GEMINI_LIVE_AGENT) sourceLanguageTag?.let { nativeInterpreterSourceLanguage(it).languageTag } else null
        if (model == GEMINI_LIVE_AGENT) runWithRenewal(key, model, target, input, authorized, onReady, onEvent,
            domainPrompt, tone, durationLimitMillis, onAudioSent, timing, diagnostics, interpreterInstructions, references,
            liveVoice, onAudioSendUnconfirmed, onPreparedAudioDiscarded, onConnectionAttempt, onConnectionClosed, onRenewalNotice, onSourceDelayNotice, source, inputDrain, explicitActivity, drainTraceOrdinal, onManualTrace)
        else {
            check(authorized())
            val observed = diagnostics ?: GeminiWireDiagnostics(nowNanos)
            onConnectionAttempt(1, false, observed)
            try { runWithoutRenewal(key, model, target, input, authorized, onReady, onEvent, domainPrompt, tone,
                durationLimitMillis, onAudioSent, timing, observed, interpreterInstructions, references, liveVoice,
                onAudioSendUnconfirmed, onPreparedAudioDiscarded, onSourceDelayNotice, inputDrain) }
            finally { onConnectionClosed(1, observed) }
        }
    }

    private suspend fun runWithRenewal(key: String, model: String, target: String, input: Flow<ByteArray>, authorized: () -> Boolean,
        onReady: () -> Unit, onEvent: suspend (GeminiLiveEvent) -> Unit, domainPrompt: String, tone: TranslationStyle,
        durationLimitMillis: Long?, onAudioSent: (Int) -> Unit, timing: NativeLiveTiming?, diagnostics: GeminiWireDiagnostics?,
        interpreterInstructions: String, references: String, liveVoice: RelayVoiceGender,
        onAudioSendUnconfirmed: (Int) -> Unit, onPreparedAudioDiscarded: (Int) -> Unit,
        onConnectionAttempt: (Int, Boolean, GeminiWireDiagnostics) -> Unit,
        onConnectionClosed: (Int, GeminiWireDiagnostics) -> Unit, onRenewalNotice: (GeminiLiveRenewalNotice) -> Unit,
        onSourceDelayNotice: suspend (GeminiLiveSourceDelayNotice) -> Unit, sourceLanguageTag: String?, inputDrain: NativeInputDrainState?, explicitActivity: Boolean, drainTraceOrdinal: Long?,
        onManualTrace: (GeminiManualTraceSnapshot) -> Unit): Unit =
        nativeLiveSessionWindow(durationLimitMillis) {
        var currentDiagnostics = diagnostics ?: GeminiWireDiagnostics(nowNanos)
        val operationFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        try { coroutineScope {
            check(authorized())
            val publication = Mutex()
            val abortSocket = java.util.concurrent.atomic.AtomicReference<RealtimeSocket?>(null)
            var publicationSocket: RealtimeSocket? = null
            val renewalDeadline = java.util.concurrent.atomic.AtomicLong(0)
            val providerFailed = java.util.concurrent.atomic.AtomicBoolean(false)
            val sourceProgress = GeminiLiveSourceProgress()
            var packetizer = GeminiPcmPacketizer()
            val prepared = GeminiLiveRenewalBuffer(renewalMaximumBytes)
            val manual = if (explicitActivity) GeminiManualActivityPump(nowNanos = nowNanos) else null
            val manualOrdinal = if (manual != null) drainTraceOrdinal ?: NativeDrainTraceOrdinals.next() else -1
            var packetizerDiscardedBytes = 0L
            var pendingIngressFirstNanos: Long? = null
            var pendingIngressLastNanos: Long? = null
            var manualHasOutput = false
            var manualEosMarked = false
            var manualStartsSent = 0L
            var manualEndsSent = 0L
            var sender: Job? = null
            var outputTurn = 1L
            val rotations = java.util.ArrayDeque<Long>()
            fun abortCurrent() { runCatching { abortSocket.get()?.abort() } }
            fun claim(failure: Throwable): Throwable {
                if (operationFailure.compareAndSet(null, failure)) { providerFailed.set(true); abortCurrent() }
                return requireNotNull(operationFailure.get())
            }
            fun checkRenewalDeadline() {
                val deadline = renewalDeadline.get()
                if (deadline != 0L && nowNanos() >= deadline)
                    throw claim(GeminiLiveRenewalFailure(GeminiLiveRenewalFailureCode.DEADLINE))
                operationFailure.get()?.let { throw it }
            }
            suspend fun <T> timedOperation(timeoutMillis: Long, stage: NativeAudioResponseTimeoutStage,
                failureFactory: () -> Throwable = { NativeAudioResponseTimeout(stage) }, operation: suspend () -> T): T = coroutineScope {
                val expired = java.util.concurrent.atomic.AtomicBoolean(false)
                val deadline = launch { delay(timeoutMillis); expired.set(true)
                    if (authorized()) claim(failureFactory()) }
                try {
                    val result = withTimeout(timeoutMillis) { operation() }
                    if (expired.get()) throw operationFailure.get() ?: claim(failureFactory())
                    result
                } catch (timeout: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    throw operationFailure.get() ?: claim(failureFactory())
                } catch (failure: Throwable) {
                    if (expired.get()) { currentCoroutineContext().ensureActive(); throw operationFailure.get() ?: failure }
                    throw if (failure is CancellationException) failure else operationFailure.get() ?: failure
                } finally { deadline.cancel() }
            }
            suspend fun sendPacket(socket: RealtimeSocket, packet: ByteArray) {
                try { timedOperation(3_000, NativeAudioResponseTimeoutStage.REQUEST_SEND) {
                    check(authorized()); checkRenewalDeadline()
                    socket.send(JSONObject().put("realtimeInput", JSONObject().put("audio", JSONObject()
                        .put("mimeType", "audio/pcm;rate=16000").put("data", Base64.getEncoder().encodeToString(packet)))).toString())
                } } catch (failure: Throwable) { onAudioSendUnconfirmed(packet.size); throw failure }
                val active = packet.pcmS16LeSignalStats().let { it.rms >= 0.002f || it.peak >= 0.01f }
                sourceProgress.sent(nowNanos(), packet.size, active); timing?.sent(packet.size, active)
                currentDiagnostics.sent(packet); onAudioSent(packet.size); inputDrain?.audioSent(active)
            }
            fun markManualEofIfPublished() {
                val activity = manual ?: return
                if (!manualEosMarked && inputDrain?.requested == true && activity.inputEnded &&
                    activity.queuedBytes == 0 && (activity.activeEndSent || !activity.hasPending)) {
                    manualEosMarked = true
                    inputDrain.eosSent()
                    if (inputDrain.completeQuietInput()) abortCurrent()
                }
            }
            // Publication mutex serializes wire sends, End acknowledgement and response boundaries.
            suspend fun sendManualCommand(socket: RealtimeSocket): Boolean {
                val activity = manual ?: return false
                if (inputDrain?.requested == true) activity.observeStopRequest()
                val command = activity.nextCommand() ?: run { markManualEofIfPublished(); return false }
                when (command) {
                    is GeminiManualCommand.Audio -> sendPacket(socket, command.bytes)
                    is GeminiManualCommand.Start, is GeminiManualCommand.End -> {
                        timedOperation(3_000, NativeAudioResponseTimeoutStage.REQUEST_SEND) {
                            check(authorized()); checkRenewalDeadline()
                            socket.send(JSONObject().put("realtimeInput", JSONObject().put(
                                if (command is GeminiManualCommand.Start) "activityStart" else "activityEnd", JSONObject())).toString())
                        }
                    }
                }
                activity.ack(command)
                if (command is GeminiManualCommand.Start) { manualHasOutput = false; manualStartsSent++ }
                if (command is GeminiManualCommand.End) { manualEndsSent++; inputDrain?.activityEndSent() }
                markManualEofIfPublished()
                return true
            }
            suspend fun drainPrepared() {
                while (true) {
                    val sent = publication.withLock {
                        currentCoroutineContext().ensureActive(); check(authorized()); checkRenewalDeadline()
                        val destination = publicationSocket ?: return@withLock false
                        if (manual != null) return@withLock sendManualCommand(destination)
                        val packet = prepared.take() ?: return@withLock false
                        sendPacket(destination, packet); true
                    }
                    if (!sent) return
                    yield()
                }
            }
            val revocation = launch {
                try { while (isActive) {
                    if (!authorized()) throw claim(IllegalStateException("Live consent revoked"))
                    checkRenewalDeadline(); delay(25)
                } } finally { abortCurrent() }
            }
            val sourceWatchdog = launch { while (isActive) {
                if (authorized()) sourceProgress.delayNotice(nowNanos())?.let { notice ->
                    timedOperation(5_000, NativeAudioResponseTimeoutStage.EVENT_CALLBACK) {
                        check(authorized()); checkRenewalDeadline(); onSourceDelayNotice(notice)
                    }
                }
                delay(250)
            } }
            var connectionIndex = 0
            var handle: String? = null
            try {
                while (isActive) {
                    check(authorized()); checkRenewalDeadline()
                    connectionIndex++
                    currentDiagnostics = if (connectionIndex == 1) currentDiagnostics else GeminiWireDiagnostics(nowNanos)
                    val observed = currentDiagnostics
                    onConnectionAttempt(connectionIndex, handle != null, observed)
                    var nextHandle: String? = null
                    try { wire.connect(key, authorized) { socket ->
                        abortSocket.set(socket)
                        try {
                            timedOperation(6_000, NativeAudioResponseTimeoutStage.REQUEST_SEND,
                                failureFactory = { OnlineProviderFailure(OnlineConnectionResult.TIMED_OUT) }) {
                                check(authorized()); checkRenewalDeadline()
                                socket.send(geminiLiveSetup(model, target, domainPrompt, tone, interpreterInstructions, references,
                                    liveVoice, observed, handle, sourceLanguageTag, explicitActivity))
                                val rawAck = socket.receive(); requireBoundedJson(rawAck, maximumChars = 262_144)
                                val ack = JSONObject(rawAck)
                                if (ack.has("error")) throw onlineProviderFailure(ack)
                                check(authorized() && ack.has("setupComplete")) { "Live setup incomplete" }
                                checkRenewalDeadline()
                            }
                            observed.mark(GeminiWireMark.SETUP_COMPLETE)
                            publication.withLock { check(authorized()); checkRenewalDeadline(); publicationSocket = socket; renewalDeadline.set(0) }
                            timing?.ready(); onReady()
                            if (sender == null) sender = launch {
                                var end = GeminiInputEnd.FAILURE
                                try {
                                    input.collect { frame ->
                                        val ingressAt = nowNanos()
                                        check(authorized()); checkRenewalDeadline()
                                        var packets: List<ByteArray> = emptyList(); var offered = 0
                                        try { publication.withLock {
                                            check(authorized()); checkRenewalDeadline()
                                            if (inputDrain?.requested == true) manual?.observeStopRequest()
                                            manual?.observeIngress(frame.size)
                                            val firstIngress = pendingIngressFirstNanos ?: ingressAt
                                            packets = packetizer.accept(frame)
                                            for ((index, packet) in packets.withIndex()) {
                                                timing?.stages?.packetReady(packet.size)
                                                if (manual != null) manual.accept(packet, hasManualAudioActivity(packet),
                                                    if (index == 0) firstIngress else ingressAt, ingressAt)
                                                else prepared.offer(packet)
                                                offered++
                                            }
                                            if (frame.isNotEmpty()) {
                                                pendingIngressFirstNanos = if (packetizer.pendingBytes == 0) null
                                                    else if (packets.isEmpty()) firstIngress else ingressAt
                                                pendingIngressLastNanos = if (packetizer.pendingBytes == 0) null else ingressAt
                                            }
                                        } } catch (failure: Throwable) {
                                            packets.drop(offered).forEach { onPreparedAudioDiscarded(it.size) }
                                            throw failure
                                        }
                                        drainPrepared()
                                    }
                                    end = GeminiInputEnd.NORMAL_EOS
                                    if (inputDrain?.requested != true) error("Live capture ended; restart required")
                                    publication.withLock {
                                        check(authorized()); checkRenewalDeadline()
                                        if (manual != null) {
                                            manual.observeStopRequest()
                                            val tail = packetizer.finishAndFlush()
                                            if (tail.isNotEmpty()) {
                                                timing?.stages?.packetReady(tail.size)
                                                try { manual.accept(tail, hasManualAudioActivity(tail),
                                                    pendingIngressFirstNanos, pendingIngressLastNanos) }
                                                catch (failure: Throwable) { onPreparedAudioDiscarded(tail.size); throw failure }
                                            }
                                            manual.endInput()
                                            publicationSocket?.let { destination ->
                                                while (sendManualCommand(destination)) { currentCoroutineContext().ensureActive() }
                                            }
                                            markManualEofIfPublished()
                                            return@withLock
                                        }
                                        val destination = publicationSocket ?: error("Live input ended during connection handoff")
                                        while (true) {
                                            val packet = prepared.take() ?: break
                                            sendPacket(destination, packet)
                                        }
                                        val tail = packetizer.finishAndFlush()
                                        if (tail.isNotEmpty()) {
                                            timing?.stages?.packetReady(tail.size)
                                            sendPacket(destination, tail)
                                        }
                                        inputDrain.eosSendStarted()
                                        try {
                                            timedOperation(3_000, NativeAudioResponseTimeoutStage.REQUEST_SEND) {
                                                check(authorized())
                                                destination.send(JSONObject().put("realtimeInput", JSONObject().put("audioStreamEnd", true)).toString())
                                            }
                                        } catch (failure: Throwable) {
                                            inputDrain.eosSendFailed()
                                            throw failure
                                        }
                                        inputDrain.eosSent()
                                        if (inputDrain.completeQuietInput()) abortCurrent()
                                    }
                                } catch (cancelled: CancellationException) {
                                    end = if (providerFailed.get()) GeminiInputEnd.FAILURE else GeminiInputEnd.STOP
                                    throw cancelled
                                } finally {
                                    sourceProgress.endInput()
                                    if (!authorized()) end = GeminiInputEnd.CONSENT_REVOKED
                                    withContext(NonCancellable) { publication.withLock {
                                        prepared.discard { bytes -> timing?.stages?.inputLoss(); onPreparedAudioDiscarded(bytes) }
                                        val tail = packetizer.finish()
                                        packetizerDiscardedBytes += tail
                                        if (tail > 0) timing?.stages?.inputLoss()
                                        currentDiagnostics.endInput(tail, end)
                                    } }
                                }
                            }
                            drainPrepared()
                            var checkpoint: GeminiLiveRenewalCheckpoint? = null
                            val deliveredOnConnection = GeminiLiveRenewalCheckpoint()
                            while (isActive) {
                                check(authorized()); checkRenewalDeadline()
                                val event = try {
                                    parseGeminiLiveEvent(socket.receive(), observed, allowRenewal = true).copy(timingTurn = outputTurn)
                                } catch (failure: Throwable) {
                                    currentCoroutineContext().ensureActive()
                                    if (operationFailure.get() == null && inputDrain?.completed == true && authorized()) return@connect
                                    throw operationFailure.get() ?: failure
                                }
                                var openedBoundaryForEvent = false
                                event.goAwayTimeLeftMillis?.takeIf { inputDrain?.requested != true }?.let { left ->
                                    val now = nowNanos()
                                    val deadline = now + minOf(20_000L, left - 1_000L) * 1_000_000L
                                    if (checkpoint == null) {
                                        while (rotations.isNotEmpty() && now - rotations.first() >= 600_000_000_000L) rotations.removeFirst()
                                        if (rotations.size >= 3) throw claim(GeminiLiveRenewalFailure(GeminiLiveRenewalFailureCode.ROTATION_LIMIT))
                                        renewalDeadline.set(deadline); checkpoint = GeminiLiveRenewalCheckpoint()
                                        publication.withLock {
                                            check(authorized()); checkRenewalDeadline()
                                            if (manual?.hasOpenActivity == true) {
                                                manual.sealCurrentActivity()
                                                while (!manual.activeEndSent && manual.hasPending) {
                                                    if (!sendManualCommand(socket)) break
                                                }
                                            }
                                            publicationSocket = null
                                            val tail = if (manual == null) packetizer.finish() else 0
                                            if (manual == null) packetizer = GeminiPcmPacketizer()
                                            if (tail > 0) { timing?.stages?.inputLoss(); onPreparedAudioDiscarded(tail) }
                                            observed.endInput(tail, GeminiInputEnd.NORMAL_EOS)
                                            if (manual == null) timedOperation(3_000, NativeAudioResponseTimeoutStage.REQUEST_SEND) {
                                                check(authorized()); socket.send(JSONObject().put("realtimeInput", JSONObject().put("audioStreamEnd", true)).toString())
                                            }
                                            checkpoint = if (manual?.activeEndSent == true)
                                                GeminiLiveRenewalCheckpoint(GeminiLiveRenewalDeliveryState.ACTIVE_UNCONFIRMED)
                                                else deliveredOnConnection.afterInputEnd()
                                            openedBoundaryForEvent = true
                                        }
                                        onRenewalNotice(GeminiLiveRenewalNotice(connectionIndex, left))
                                    } else renewalDeadline.set(minOf(renewalDeadline.get(), deadline))
                                }
                                val receivedAt = nowNanos()
                                sourceProgress.source(receivedAt, event.source)
                                sourceProgress.providerResponse(receivedAt, event); observed.received(event)
                                if (event.interrupted) timing?.interrupted()
                                timing?.providerAudio(event.audio.sumOf { it.size })
                                if (event.audio.isNotEmpty() || event.finished || event.interrupted)
                                    timing?.stages?.provider(outputTurn, event.audio.sumOf { it.size }, event.finished, event.interrupted)
                                check(authorized()); checkRenewalDeadline()
                                if (manual != null) publication.withLock {
                                    if (inputDrain?.requested == true) manual.observeStopRequest()
                                    if (!event.source.isNullOrBlank()) manual.observeSource(receivedAt)
                                    if (!event.translation.isNullOrBlank() || event.audio.isNotEmpty()) {
                                        check(manual.activeEndSent) { "Activity output preceded its input end" }
                                        manualHasOutput = true
                                    }
                                    if (event.finished) check(manual.activeEndSent) { "Unowned activity terminal" }
                                }
                                // Input sent during publication belongs to a later response boundary.
                                if (!event.translation.isNullOrBlank() || event.audio.isNotEmpty())
                                    inputDrain?.outputStarted()
                                if (event.serverContent || event.usage != null) {
                                    val callbackStarted = nowNanos()
                                    try { timedOperation(5_000, NativeAudioResponseTimeoutStage.EVENT_CALLBACK) { onEvent(event) } }
                                    finally { timing?.callbackFinished(nowNanos() - callbackStarted) }
                                }
                                if (event.finished || event.interrupted) outputTurn++
                                val drainComplete = if (manual != null) publication.withLock {
                                    if (event.finished || event.interrupted)
                                        check(manual.turnComplete(event.interrupted, manualHasOutput)) { "Unowned activity terminal" }
                                    inputDrain?.observeResponse(!event.translation.isNullOrBlank() || event.audio.isNotEmpty(), event.finished, event.interrupted, !event.source.isNullOrBlank()) == true
                                } else inputDrain?.observeResponse(!event.translation.isNullOrBlank() || event.audio.isNotEmpty(), event.finished, event.interrupted, !event.source.isNullOrBlank()) == true
                                if (drainComplete) break
                                deliveredOnConnection.delivered(event)
                                checkpoint?.delivered(if (openedBoundaryForEvent && event.resumptionUpdate?.resumable == true)
                                    event.copy(resumptionUpdate = null) else event)
                                checkpoint?.takeHandle()?.let { fresh ->
                                    check(authorized()); checkRenewalDeadline(); nextHandle = fresh
                                }
                                if (nextHandle != null) break
                                if (manual != null && event.finished) {
                                    drainPrepared()
                                    if (inputDrain?.completed == true) break
                                }
                            }
                        } finally {
                            runCatching { socket.abort() }
                            withContext(NonCancellable) { publication.withLock { if (publicationSocket === socket) publicationSocket = null } }
                            abortSocket.compareAndSet(socket, null)
                        }
                    } } finally { observed.mark(GeminiWireMark.CLOSED); onConnectionClosed(connectionIndex, observed) }
                    check(authorized()); checkRenewalDeadline()
                    if (inputDrain?.completed == true) break
                    check(manual != null || inputDrain?.requested != true) { "Live input ended during connection handoff" }
                    handle = nextHandle ?: throw claim(GeminiLiveRenewalFailure(GeminiLiveRenewalFailureCode.UNSAFE_CHECKPOINT))
                    rotations.addLast(nowNanos())
                }
            } finally {
                revocation.cancel(); sourceWatchdog.cancel(); sender?.cancel()
                abortCurrent()
                withContext(NonCancellable) {
                    sender?.join()
                    var manualSnapshot: GeminiManualTraceSnapshot? = null
                    publication.withLock {
                        publicationSocket = null
                        prepared.discard { bytes -> timing?.stages?.inputLoss(); onPreparedAudioDiscarded(bytes) }
                        manual?.let { activity ->
                            val lost = activity.discard()
                            if (lost > 0) { timing?.stages?.inputLoss(); onPreparedAudioDiscarded(lost) }
                            RuntimeDiagnosticLog.durableRecord("native_manual_activity", "starts_sent=$manualStartsSent ends_sent=$manualEndsSent activities=${activity.completedActivities} sent=${activity.deliveredAudioBytes} idle_suppressed=${activity.suppressedIdleBytes} abandoned=$lost eof_published=$manualEosMarked eos_scope=LOCAL_EOF_AND_MANUAL_ACTIVITY_END")
                            manualSnapshot = activity.traceSnapshot().copy(
                                drainOrdinal = manualOrdinal, packetizerDiscardedBytes = packetizerDiscardedBytes)
                        }
                    }
                    // No diagnostic file I/O while holding the wire publication owner.
                    manualSnapshot?.let { runCatching { onManualTrace(it) } }
                }
            }
        } } catch (failure: Throwable) {
            val terminal = operationFailure.get() ?: run { currentCoroutineContext().ensureActive(); failure }
            if (terminal !is CancellationException || terminal is TimeoutCancellationException) currentDiagnostics.failed(terminal)
            throw terminal
        }
    }

    private suspend fun runWithoutRenewal(key: String, model: String, target: String, input: Flow<ByteArray>, authorized: () -> Boolean,
        onReady: () -> Unit, onEvent: suspend (GeminiLiveEvent) -> Unit, domainPrompt: String = "", tone: TranslationStyle = TranslationStyle.CONVERSATIONAL,
        durationLimitMillis: Long? = null, onAudioSent: (Int) -> Unit = {},
        timing: NativeLiveTiming? = null, diagnostics: GeminiWireDiagnostics? = null,
        interpreterInstructions: String = "", references: String = "", liveVoice: RelayVoiceGender = RelayVoiceGender.AUTO,
        onAudioSendUnconfirmed: (Int) -> Unit = {}, onPreparedAudioDiscarded: (Int) -> Unit = {},
        onSourceDelayNotice: suspend (GeminiLiveSourceDelayNotice) -> Unit = {},
        inputDrain: NativeInputDrainState? = null): Unit = nativeLiveSessionWindow(durationLimitMillis) {
        try {
        check(authorized())
        wire.connect(key, authorized) { socket ->
            try {
            withTimeout(6_000) {
                socket.send(geminiLiveSetup(model, target, domainPrompt, tone, interpreterInstructions, references, liveVoice, diagnostics))
                val rawAck = socket.receive()
                requireBoundedJson(rawAck, maximumChars = 262_144)
                val ack = JSONObject(rawAck)
                if (ack.has("error")) throw onlineProviderFailure(ack)
                check(authorized() && ack.has("setupComplete")) { "Live setup incomplete" }
            }
            diagnostics?.mark(GeminiWireMark.SETUP_COMPLETE)
            timing?.ready(); onReady()
            coroutineScope {
                val providerFailed = java.util.concurrent.atomic.AtomicBoolean(false)
                val sourceProgress = GeminiLiveSourceProgress()
                val operationFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
                fun claimOperationTimeout(stage: NativeAudioResponseTimeoutStage): Throwable {
                    val failure = NativeAudioResponseTimeout(stage)
                    if (operationFailure.compareAndSet(null, failure)) runCatching { socket.abort() }
                    return requireNotNull(operationFailure.get())
                }
                suspend fun <T> timedOperation(timeoutMillis: Long, stage: NativeAudioResponseTimeoutStage, operation: suspend () -> T): T = coroutineScope {
                    val expired = java.util.concurrent.atomic.AtomicBoolean(false)
                    val deadline = launch {
                        delay(timeoutMillis); expired.set(true)
                        if (authorized()) claimOperationTimeout(stage)
                    }
                    try {
                        val result = withTimeout(timeoutMillis) { operation() }
                        if (expired.get()) throw operationFailure.get() ?: claimOperationTimeout(stage)
                        result
                    } catch (timeout: TimeoutCancellationException) {
                        currentCoroutineContext().ensureActive()
                        throw operationFailure.get() ?: claimOperationTimeout(stage)
                    } catch (failure: Throwable) {
                        if (expired.get()) {
                            currentCoroutineContext().ensureActive()
                            throw operationFailure.get() ?: failure
                        }
                        throw if (failure is CancellationException) failure else operationFailure.get() ?: failure
                    } finally { deadline.cancel() }
                }
                val sender = launch {
                    val packetizer = GeminiPcmPacketizer()
                    val prepared = java.util.ArrayDeque<ByteArray>()
                    var end = GeminiInputEnd.FAILURE
                    try {
                    input.collect { frame ->
                        check(authorized())
                        packetizer.accept(frame).forEach { packet ->
                            timing?.stages?.packetReady(packet.size); prepared.addLast(packet)
                        }
                        while (prepared.isNotEmpty()) {
                                currentCoroutineContext().ensureActive(); check(authorized())
                                val packet = prepared.removeFirst()
                                try {
                                    timedOperation(3_000, NativeAudioResponseTimeoutStage.REQUEST_SEND) {
                                        socket.send(JSONObject().put("realtimeInput", JSONObject().put("audio", JSONObject()
                                            .put("mimeType", "audio/pcm;rate=16000")
                                            .put("data", Base64.getEncoder().encodeToString(packet)))).toString())
                                    }
                                } catch (failure: Throwable) {
                                    onAudioSendUnconfirmed(packet.size)
                                    throw failure
                                }
                                val active = packet.pcmS16LeSignalStats().let { it.rms >= 0.002f || it.peak >= 0.01f }
                                sourceProgress.sent(nowNanos(), packet.size, active)
                                timing?.sent(packet.size, active)
                                diagnostics?.sent(packet)
                                onAudioSent(packet.size); inputDrain?.audioSent(active)
                        }
                    }
                    end = GeminiInputEnd.NORMAL_EOS
                    if (inputDrain?.requested != true) error("Live capture ended; restart required")
                    val tail = packetizer.finishAndFlush()
                    if (tail.isNotEmpty()) {
                        timing?.stages?.packetReady(tail.size)
                        try {
                            timedOperation(3_000, NativeAudioResponseTimeoutStage.REQUEST_SEND) {
                                check(authorized())
                                socket.send(JSONObject().put("realtimeInput", JSONObject().put("audio", JSONObject()
                                    .put("mimeType", "audio/pcm;rate=16000")
                                    .put("data", Base64.getEncoder().encodeToString(tail)))).toString())
                            }
                        } catch (failure: Throwable) { onAudioSendUnconfirmed(tail.size); throw failure }
                        val active = tail.pcmS16LeSignalStats().let { it.rms >= 0.002f || it.peak >= 0.01f }
                        sourceProgress.sent(nowNanos(), tail.size, active); timing?.sent(tail.size, active)
                        diagnostics?.sent(tail); onAudioSent(tail.size); inputDrain.audioSent(active)
                    }
                    inputDrain.eosSendStarted()
                    try {
                        timedOperation(3_000, NativeAudioResponseTimeoutStage.REQUEST_SEND) {
                            check(authorized())
                            socket.send(JSONObject().put("realtimeInput", JSONObject().put("audioStreamEnd", true)).toString())
                        }
                    } catch (failure: Throwable) {
                        inputDrain.eosSendFailed()
                        throw failure
                    }
                    inputDrain.eosSent()
                    if (inputDrain.completeQuietInput()) runCatching { socket.abort() }
                    } catch (cancelled: CancellationException) {
                        end = if (providerFailed.get()) GeminiInputEnd.FAILURE else GeminiInputEnd.STOP
                        throw cancelled
                    } finally {
                        sourceProgress.endInput()
                        if (!authorized()) end = GeminiInputEnd.CONSENT_REVOKED
                        while (prepared.isNotEmpty()) {
                            timing?.stages?.inputLoss()
                            onPreparedAudioDiscarded(prepared.removeFirst().size)
                        }
                        val tail = packetizer.finish()
                        if (tail > 0) timing?.stages?.inputLoss()
                        diagnostics?.endInput(tail, end)
                    }
                }
                val revocation = launch {
                    try { while (isActive) { if (!authorized()) error("Live consent revoked"); delay(25) } }
                    finally { runCatching { socket.abort() } }
                }
                val sourceWatchdog = launch { while (isActive) {
                    if (authorized()) sourceProgress.delayNotice(nowNanos())?.let { notice ->
                        timedOperation(5_000, NativeAudioResponseTimeoutStage.EVENT_CALLBACK) {
                            check(authorized()); onSourceDelayNotice(notice)
                        }
                    }
                    delay(250)
                } }
                try {
                    var outputTurn = 1L
                    while (isActive) {
                        check(authorized())
                        val event = parseGeminiLiveEvent(socket.receive(), diagnostics).copy(timingTurn = outputTurn)
                        val receivedAt = nowNanos()
                        sourceProgress.source(receivedAt, event.source)
                        sourceProgress.providerResponse(receivedAt, event)
                        diagnostics?.received(event)
                        if (event.interrupted) timing?.interrupted()
                        timing?.providerAudio(event.audio.sumOf { it.size })
                        if (event.audio.isNotEmpty() || event.finished || event.interrupted)
                            timing?.stages?.provider(outputTurn, event.audio.sumOf { it.size }, event.finished, event.interrupted)
                        check(authorized())
                        // Input sent during publication belongs to a later response boundary.
                        if (!event.translation.isNullOrBlank() || event.audio.isNotEmpty())
                            inputDrain?.outputStarted()
                        val callbackStarted = nowNanos()
                        try { timedOperation(5_000, NativeAudioResponseTimeoutStage.EVENT_CALLBACK) { onEvent(event) } }
                        finally { timing?.callbackFinished(nowNanos() - callbackStarted) }
                        if (event.finished || event.interrupted) outputTurn++
                        if (inputDrain?.observeResponse(!event.translation.isNullOrBlank() || event.audio.isNotEmpty(), event.finished, event.interrupted, !event.source.isNullOrBlank()) == true) break
                    }
                } catch (failure: Throwable) {
                    val terminal = operationFailure.get() ?: run { currentCoroutineContext().ensureActive(); failure }
                    if (operationFailure.get() == null && inputDrain?.completed == true) {
                        currentCoroutineContext().ensureActive(); check(authorized())
                    } else {
                        if (terminal !is CancellationException) providerFailed.set(true)
                        throw terminal
                    }
                } finally { sender.cancel(); revocation.cancel(); sourceWatchdog.cancel() }
            }
            } finally { runCatching { socket.abort() } }
        }
        } catch (failure: Throwable) {
            if (failure !is CancellationException || failure is TimeoutCancellationException) diagnostics?.failed(failure)
            throw failure
        } finally { diagnostics?.mark(GeminiWireMark.CLOSED) }
    }
}

/** Normal operation lasts until explicit stop/revocation/provider close; fixtures own their cap. */
internal suspend fun <T> nativeLiveSessionWindow(durationLimitMillis: Long?, block: suspend CoroutineScope.() -> T): T {
    require(durationLimitMillis == null || durationLimitMillis > 0)
    return if (durationLimitMillis == null) coroutineScope(block) else withTimeout(durationLimitMillis, block)
}
