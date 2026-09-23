package app.mcasttalk.windows.host

import java.nio.file.Files
import java.nio.file.Path
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

internal interface MeetingInference : AutoCloseable {
    fun execute(request: Map<String, Any?>): FlatJsonObject
    fun executeStreaming(request: Map<String, Any?>, onEvent: (FlatJsonObject) -> Unit): FlatJsonObject = execute(request)
    val needsWarmup: Boolean get() = false
    fun warmup(): FlatJsonObject? = null
}

/** One serialized local worker. Deadline termination also kills its owned inference children. */
internal class ProcessMeetingInference(private val root: Path) : MeetingInference {
    private val properties = Properties().apply {
        Files.newBufferedReader(root.resolve("config/inference.properties")).use { load(it) }
    }
    private val deadlines = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "inference-deadline").apply { isDaemon = true } }
    @Volatile private var process: Process? = null
    private var reader: java.io.BufferedReader? = null
    private var writer: java.io.BufferedWriter? = null
    @Volatile private var closed = false

    private fun stopProcess(ownedProcess: Process? = process) {
        ownedProcess?.let { p ->
            p.descendants().forEach { it.destroyForcibly() }
            p.destroyForcibly()
        }
    }

    override val needsWarmup: Boolean get() = true
    override fun warmup(): FlatJsonObject = execute(mapOf("op" to "warmup"))
    override fun execute(request: Map<String, Any?>): FlatJsonObject = executeStreaming(request) {}

    @Synchronized override fun executeStreaming(request: Map<String, Any?>, onEvent: (FlatJsonObject) -> Unit): FlatJsonObject {
        check(!closed) { "Inference is closed" }
        val ownedProcess = java.util.concurrent.atomic.AtomicReference(process)
        val timeout = deadlines.schedule({ stopProcess(ownedProcess.get()) }, 180, TimeUnit.SECONDS)
        try {
            if (process?.isAlive != true) {
                val python = Path.of(properties.getProperty("python")).toAbsolutePath()
                val home = BundledInference.workerHome(root,Path.of(properties.getProperty("workerHome")).toAbsolutePath())
                val config = Path.of(properties.getProperty("config")).toAbsolutePath()
                require(listOf(python, home, config).all { Files.exists(it) }) { "Inference assets missing" }
                val builder = ProcessBuilder(python.toString(), "-B", "-u", "-E", "-s", "-X", "utf8", "-m", "mcasttalk_worker.meeting_engine", "--config", config.toString())
                builder.directory(home.toFile())
                builder.environment()["MCASTTALK_HOST_PID"] = ProcessHandle.current().pid().toString()
                // Native model diagnostics are discarded here; never write private utterances to host logs.
                builder.redirectError(ProcessBuilder.Redirect.DISCARD)
                val p = builder.start(); process = p; ownedProcess.set(p)
                reader = p.inputStream.bufferedReader(Charsets.UTF_8)
                writer = p.outputStream.bufferedWriter(Charsets.UTF_8)
                check(readResponse().requiredString("status") == "ready") { "Inference worker failed to initialize" }
            }
            writer!!.apply { write(encodeJson(request)); newLine(); flush() }
            var events = 0
            val maxEvents = ((request["targets"] as? String)?.split(',')?.toSet()?.size ?: 0).coerceAtMost(32) * 2
            while (true) {
                val result = readResponse()
                check(result.optionalString("status") != "error") { result.optionalString("error") ?: "Inference failed" }
                if (result.optionalString("status") != "partial") return result
                check(request["stream"] == true && ++events <= maxEvents) { "Unexpected or excessive inference event" }
                onEvent(result)
            }
        } catch (error: Exception) {
            stopProcess()
            throw error
        } finally { timeout.cancel(false) }
    }

    private fun readResponse(): FlatJsonObject {
        val text = StringBuilder()
        while (text.length <= 8 * 1024 * 1024) {
            val char = reader!!.read()
            check(char >= 0) { "Inference process exited or timed out" }
            if (char == 10) return parseFlatJsonObject(text.toString(), 8 * 1024 * 1024)
            text.append(char.toChar())
        }
        error("Inference output exceeded limit")
    }

    override fun close() {
        closed = true
        runCatching { process?.outputStream?.close() }
        if (process?.waitFor(5,TimeUnit.SECONDS) == false) stopProcess()
        deadlines.shutdownNow()
    }
}

internal data class VoiceUtterance(val sequence: Int, val pcm: ByteArray)
internal fun parseVoiceUtterance(bytes: ByteArray): VoiceUtterance {
    require(bytes.size in 8016..192016) { "Voice utterance must contain 0.25–6 seconds PCM" }
    val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    require(header.int == 0x3154434d) { "Invalid voice protocol magic" } // MCT1
    val sequence = header.int
    require(sequence >= 0 && header.int == 16000) { "Invalid voice sequence or sample rate" }
    val samples = header.int
    require(samples in 4000..96000 && bytes.size == 16 + samples * 2) { "PCM frame length mismatch" }
    return VoiceUtterance(sequence, bytes.copyOfRange(16, bytes.size))
}

/** Bounded work admission, per-presence jobs, unique-language inference and generation-safe delivery. */
internal class MeetingInterpreter(
    private val engine: MeetingInference?, private val membership: RoomMembershipCoordinator, scope: CoroutineScope,
    val capacity: LanguageCapacity? = null,
) : AutoCloseable {
    private data class Work(val room: String, val sender: Participant, val connection: RoomSocketHub.Connection,
        val recipients: List<Participant>, val text: String?, val voice: VoiceUtterance?, val privateRecipient: Participant?,
        val queuedAt: Long = System.nanoTime())
    private val queue = Channel<Work>(4)
    private val pending = ConcurrentHashMap.newKeySet<String>()
    val enabled: Boolean get() = engine != null
    @Volatile var status: String = if (engine == null) "not-configured" else if (engine.needsWarmup) "warming-up" else "ready"
        private set
    @Volatile var backend: String? = null
        private set
    @Volatile var lastLatencyMs: Int? = null
        private set
    @Volatile var fallbackReason: String? = null
        private set
    @Volatile var lastAsrMs: Int? = null
        private set
    @Volatile var firstAudioMs: Int? = null
        private set
    @Volatile var firstCaptionMs: Int? = null
        private set
    init {
        scope.launch {
            if (engine?.needsWarmup == true) {
                try {
                    val ready = withContext(Dispatchers.IO) { engine.warmup() }
                    backend = ready?.optionalString("backend")
                    fallbackReason = ready?.optionalString("fallbackReason")?.takeIf(String::isNotBlank)
                    if (capacity != null) capacity.calibrate(checkNotNull(ready))
                    status = "ready"
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { status = "warmup-failed" }
            }
            for (work in queue) {
              var terminalStatus: String? = null
              var failureMessage: String? = null
              try {
                check(System.nanoTime() - work.queuedAt <= TimeUnit.SECONDS.toNanos(30)) { "Interpretation queue deadline exceeded" }
                membership.recipients(work.room, work.sender) // Never infer a departed sender's queued utterance.
                fun displayTarget(recipient: Participant): String = if (work.voice != null && recipient.preferences.understands(work.sender.preferences.inputLanguage))
                    work.sender.preferences.inputLanguage else recipient.preferences.displayLanguage
                val targets = work.recipients.flatMap { recipient ->
                    listOf(displayTarget(recipient)) + if (work.voice != null && recipient.id != work.sender.id && recipient.preferences.wantsTranslatedAudio(work.sender.preferences.inputLanguage))
                        listOf(recipient.preferences.listenLanguage) else emptyList()
                }.toSet()
                require((targets + work.sender.preferences.inputLanguage + work.sender.preferences.publishLanguage).all { it in supportedLanguages }) { "Unsupported interpretation language" }
                val audioTargets = if (work.voice == null) emptySet() else work.recipients.filter {
                    it.id != work.sender.id && it.preferences.wantsTranslatedAudio(work.sender.preferences.inputLanguage)
                }.map { it.preferences.listenLanguage }.toSet()
                val request = mapOf("op" to if (work.voice == null) "text" else "voice", "text" to work.text,
                    "pcm" to work.voice?.pcm?.let { Base64.getEncoder().encodeToString(it) },
                    "sourceLanguage" to work.sender.preferences.inputLanguage, "publishLanguage" to work.sender.preferences.publishLanguage,
                    "targets" to targets.joinToString(","), "audioTargets" to audioTargets.joinToString(","), "stream" to (work.voice != null))
                status = "processing"
                val messageId = UUID.randomUUID().toString()
                val captions = mutableSetOf<String>(); val audio = mutableSetOf<String>()
                firstCaptionMs = null; firstAudioMs = null
                val result = withContext(Dispatchers.IO) { checkNotNull(engine).executeStreaming(request) { event ->
                    check(work.voice != null) { "Text requests cannot stream voice events" }
                    val target = event.requiredString("targetLanguage")
                    require(target in targets) { "Unexpected interpretation target" }
                    val kind = event.requiredString("kind")
                    val elapsed = event.requiredInt("elapsedMs")
                    require(elapsed >= 0) { "Invalid event latency" }
                    when (kind) {
                        "caption" -> {
                            require(target !in captions) { "Duplicate caption" }
                            event.requiredString("originalText"); event.requiredString("publishedText"); event.requiredString("translatedText")
                            captions.add(target)
                            if (firstCaptionMs == null && target != work.sender.preferences.inputLanguage) firstCaptionMs = elapsed
                            lastAsrMs = event.requiredInt("asrMs")
                            work.recipients.filter { displayTarget(it) == target }.forEach { recipient ->
                                val fields = voiceFields(work, messageId)
                                fields.putAll(mapOf("type" to "INTERPRETATION", "targetLanguage" to target,
                                    "originalText" to event.requiredString("originalText"), "publishedText" to event.requiredString("publishedText"),
                                    "translatedText" to event.requiredString("translatedText"), "elapsedMs" to elapsed,
                                    "translationStatus" to if (recipient.preferences.understands(work.sender.preferences.inputLanguage)) "original" else "translated"))
                                membership.deliver(work.room,work.sender,work.connection,recipient,encodeJson(fields))
                            }
                        }
                        "audio" -> {
                            require(target in audioTargets && target in captions && audio.add(target)) { "Unexpected or duplicate speech" }
                            val wav = event.requiredString("audioWav")
                            if (firstAudioMs == null) firstAudioMs = elapsed
                            work.recipients.filter { it.id != work.sender.id && it.preferences.listenLanguage == target && it.preferences.wantsTranslatedAudio(work.sender.preferences.inputLanguage) }.forEach { recipient ->
                                val fields = voiceFields(work,messageId)
                                fields.putAll(mapOf("type" to "INTERPRETATION_AUDIO", "audioLanguage" to target, "audioWav" to wav, "elapsedMs" to elapsed))
                                membership.deliver(work.room,work.sender,work.connection,recipient,encodeJson(fields))
                            }
                        }
                        else -> error("Unsupported inference event")
                    }
                } }
                status = "ready"
                backend = result.optionalString("backend")
                fallbackReason = result.optionalString("fallbackReason")?.takeIf(String::isNotBlank)
                lastLatencyMs = result.requiredInt("elapsedMs")
                if (work.voice != null && result.optionalString("status") != "no_speech") capacity?.observe(result)
                if (result.optionalString("status") == "no_speech") {
                    terminalStatus = "no_speech"
                    continue
                }
                // Validate the complete worker response before delivering to any participant.
                result.requiredString("originalText");result.requiredString("publishedText")
                targets.forEach { result.requiredString("text_$it") }
                if (work.voice != null && captions.isNotEmpty()) {
                    check(captions == targets && audio == audioTargets) { "Incomplete progressive interpretation" }
                    terminalStatus = "complete"
                    continue
                }
                for (recipient in work.recipients) {
                    val target = displayTarget(recipient)
                    val fields = linkedMapOf<String, Any?>(
                        "type" to if (work.voice == null) "CHAT_MESSAGE" else "INTERPRETATION",
                        "roomId" to work.room, "messageId" to messageId, "senderId" to work.sender.id,
                        "speakerId" to work.sender.id, "senderPresenceId" to work.sender.presenceId,
                        "senderAccountId" to work.sender.accountId, "senderUsername" to work.sender.username,
                        "senderDisplayName" to work.sender.displayName, "sentAt" to java.time.Instant.now().toString(),
                        "scope" to if (work.privateRecipient == null) "room" else "private",
                        "recipientId" to work.privateRecipient?.id, "recipientPresenceId" to work.privateRecipient?.presenceId,
                        "recipientDisplayName" to work.privateRecipient?.displayName, "recipientUsername" to work.privateRecipient?.username,
                        "originalText" to result.requiredString("originalText"), "publishedText" to result.requiredString("publishedText"),
                        "sourceLanguage" to work.sender.preferences.inputLanguage, "targetLanguage" to target,
                        "translatedText" to result.requiredString("text_$target"), "translationStatus" to "translated",
                        "machineGenerated" to true, "elapsedMs" to result.requiredInt("elapsedMs"),
                    )
                    if (work.voice != null && recipient.id != work.sender.id && recipient.preferences.wantsTranslatedAudio(work.sender.preferences.inputLanguage)) {
                        fields["audioWav"] = result.optionalString("audio_${recipient.preferences.listenLanguage}")
                        fields["audioLanguage"] = recipient.preferences.listenLanguage
                    }
                    membership.deliver(work.room, work.sender, work.connection, recipient, encodeJson(fields))
                }
                if (work.voice != null) {
                    terminalStatus = "complete"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Only a class name: no utterance, credentials, model response or local path in logs.
                System.err.println("Local interpretation failed: ${error.javaClass.simpleName}")
                status = "last-request-failed"
                if(work.text!=null) {
                    // The original text remains useful when translation fails. Keep the exact
                    // submission recipient snapshot; do not leak historical text to newcomers.
                    val message=chatMessage(work.room,work.sender,work.privateRecipient,work.text,"failed")
                    work.recipients.forEach { recipient -> membership.deliver(work.room,work.sender,work.connection,recipient,message) }
                    failureMessage = errorMessage("TRANSLATION_FAILED", "번역에 실패해 연결이 유지된 수신자에게 원문만 전달했습니다.")
                } else {
                    failureMessage = errorMessage("INFERENCE_FAILED", "로컬 통역 실패 또는 시간 초과입니다. 이미 전달된 일부 자막/음성을 제외한 나머지는 제공하지 못했습니다.")
                    work.recipients.forEach { membership.deliver(work.room,work.sender,work.connection,it,encodeJson(mapOf("type" to "INTERPRETATION_STATUS", "status" to "failed", "roomId" to work.room))) }
                }
            } finally {
                pending.remove(work.sender.presenceId)
                terminalStatus?.let { work.connection.send(encodeJson(mapOf("type" to "VOICE_STATUS", "status" to it))) }
                failureMessage?.let { work.connection.send(it) }
            }
            }
        }
    }

    @Synchronized fun submit(room: String, sender: Participant, connection: RoomSocketHub.Connection, text: ChatSendCommand? = null, voice: VoiceUtterance? = null) {
        check(enabled) { "Inference is not configured" }
        require(status != "warming-up" && status != "warmup-failed") { "통역 모델 준비/성능 측정이 완료되지 않았습니다. 원음·영상 연결은 유지됩니다. 호스트 상태를 확인하세요." }
        require(sender.role != ParticipantRole.LISTENER) { "Listeners cannot publish" }
        val recipients = membership.recipients(room, sender, text?.recipientId, text?.recipientPresenceId)
        if (voice != null) {
            val source=sender.preferences.inputLanguage
            val audio=recipients.filter { it.id!=sender.id && it.preferences.wantsTranslatedAudio(source) }.map { it.preferences.listenLanguage }.toSet()
            val translations=(recipients.filterNot { it.preferences.understands(source) }.map { it.preferences.displayLanguage }+audio+sender.preferences.publishLanguage).toSet()-source
            capacity?.admit(translations,audio,voice.pcm.size/32,pending.isNotEmpty())
        }
        require(pending.add(sender.presenceId)) { "Your previous interpretation is still running" }
        if (!queue.trySend(Work(room, sender, connection, recipients, text?.text, voice,
                if (text?.recipientId != null) recipients.last() else null)).isSuccess) {
            pending.remove(sender.presenceId)
            throw IllegalArgumentException("Interpretation queue is full; try again shortly")
        }
        connection.send(encodeJson(mapOf("type" to "VOICE_STATUS", "status" to "processing")))
    }
    private fun voiceFields(work: Work, messageId: String): LinkedHashMap<String,Any?> = linkedMapOf(
        "roomId" to work.room, "messageId" to messageId, "senderId" to work.sender.id, "speakerId" to work.sender.id,
        "senderPresenceId" to work.sender.presenceId, "senderAccountId" to work.sender.accountId,
        "senderUsername" to work.sender.username, "senderDisplayName" to work.sender.displayName,
        "sourceLanguage" to work.sender.preferences.inputLanguage, "sentAt" to java.time.Instant.now().toString(),
        "machineGenerated" to true, "scope" to "room",
    )
    override fun close() { queue.cancel(); engine?.close() }
    companion object { val supportedLanguages = setOf("ko", "en", "ja", "zh-CN") }
}
