package app.guidecast.core.stream

import java.io.Closeable
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class RecordedPcmSegment(
    val sessionId: String, val partId: Long, val channel: AudioChannelDescriptor,
    val segment: Int, val committedBytes: Long, val file: File,
    val fileOffsetBytes: Long = 0,
)

data class RecordedBroadcast(
    val id: String, val startedAtMillis: Long, val endedAtMillis: Long?, val state: String,
    val droppedRecordingFrames: Long, val failure: String?, val segments: List<RecordedPcmSegment>,
)
data class RecordingHealth(val state: String, val droppedFrames: Long, val failure: String?)

/** Disk-backed recording copies, independent of capture, listeners, and cloud requests.
 * Admission only copies into a finite queue. All disk/index writes run on one IO worker.
 * PCM files split before RIFF limits; committed lengths are published only after flushing.
 */
class PcmBroadcastArchive(
    private val directory: File,
    private val capacity: Int = 256,
    private val segmentByteLimit: Int = 4 * 1024 * 1024,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val beforeWrite: () -> Unit = {},
) : Closeable {
    private val writes = ArrayBlockingQueue<Write>(capacity)
    private val worker = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "broadcast-recording-io").apply { isDaemon = true } }
    private val sessions = ConcurrentHashMap<String, Session>()
    private val closed = AtomicBoolean(false)
    init {
        require(capacity > 0 && segmentByteLimit >= 2 && segmentByteLimit % 2 == 0)
        worker.scheduleWithFixedDelay({ drain() }, 20, 100, TimeUnit.MILLISECONDS)
    }

    fun begin(): String {
        check(!closed.get())
        val id = UUID.randomUUID().toString()
        val state = Session(id, clockMillis())
        sessions[id] = state
        worker.execute { persist(state) }
        return id
    }
    fun health(id: String): RecordingHealth? = sessions[id]?.let { RecordingHealth(it.status, it.dropped.get(), it.failure) }

    /** A paused broadcast retains this logical ID; each resumed stream uses a fresh part. */
    fun attach(sessionId: String, stream: StreamSession): Closeable {
        val session = requireNotNull(sessions[sessionId])
        check(session.ended == null && !closed.get())
        val part = Part(session, stream.generation, stream.channels)
        synchronized(session) {
            check(session.parts.putIfAbsent(part.id, part) == null) { "Recording part already attached" }
            session.status = "RECORDING"
        }
        worker.execute { persist(session) }
        val subscription = stream.observePublishedPcm { descriptor, frame ->
            synchronized(part.admission) {
                if (!part.open.get() || closed.get()) return@observePublishedPcm
                // Size is bounded before copying, even if a faulty producer submits a huge frame.
                if (frame.bytes.size > 128 * 1024 || !writes.offer(Write(part, descriptor, frame.copy(bytes = frame.bytes.copyOf())))) {
                    session.dropped.incrementAndGet()
                }
            }
        }
        return Closeable {
            synchronized(part.admission) { part.open.set(false); subscription.close() }
            worker.execute {
                drain()
                part.tracks.values.forEach { it.close() }
                synchronized(session) {
                    if (session.ended == null && session.parts.values.none { it.open.get() }) session.status = "PAUSED"
                }
                persist(session)
            }
        }
    }

    fun finish(sessionId: String, endState: String = "COMPLETED") {
        require(endState in setOf("COMPLETED", "FAILED"))
        val session = sessions[sessionId] ?: return
        session.parts.values.forEach { part -> synchronized(part.admission) { part.open.set(false) } }
        session.ended = clockMillis()
        session.status = endState
        worker.execute { drain(); session.parts.values.forEach { part -> part.tracks.values.forEach { it.close() } }; persist(session) }
    }

    fun markPaused(sessionId: String, paused: Boolean) {
        val session = sessions[sessionId] ?: return
        if (session.ended == null) { session.status = if (paused) "PAUSED" else "RECORDING"; worker.execute { drain(); persist(session) } }
    }

    /** Exact target sequence only. Source PCM has no sequence: never invent source alignment. */
    fun sequenceSlice(id: String, part: Long, channel: String, sequence: Long): List<RecordedPcmSegment> {
        val available = snapshot(id)?.segments?.filter { it.partId == part && it.channel.id == channel } ?: return emptyList()
        if (available.isEmpty()) return emptyList()
        val index = File(available.first().file.parentFile, "$part-$channel.index")
        if (!index.isFile) return emptyList()
        val result = mutableListOf<RecordedPcmSegment>()
        java.io.DataInputStream(index.inputStream().buffered()).use { input ->
            var remaining = index.length() / 32
            while (remaining-- > 0) {
                input.readLong(); val seq = input.readLong(); val number = input.readInt()
                val offset = input.readLong() * 2; val count = input.readInt()
                if (seq != sequence || count <= 0) continue
                val segment = available.firstOrNull { it.segment == number && offset >= 0 && offset <= it.committedBytes - count } ?: continue
                val previous = result.lastOrNull()
                if (previous?.file == segment.file && previous.fileOffsetBytes + previous.committedBytes == offset)
                    result[result.lastIndex] = previous.copy(committedBytes = previous.committedBytes + count)
                else result.add(segment.copy(fileOffsetBytes = offset, committedBytes = count.toLong()))
            }
        }
        return result
    }

    /** Test/export barrier. Never call from a capture callback or the UI thread. */
    fun flush() { worker.submit { drain(); sessions.values.forEach(::persist) }.get(10, TimeUnit.SECONDS) }

    fun snapshots(): List<RecordedBroadcast> = (directory.listFiles().orEmpty()
        .filter { it.isDirectory && ID.matches(it.name) }.map { it.name } + sessions.keys)
        .distinct().mapNotNull(::snapshot)
        .sortedByDescending { it.startedAtMillis }

    fun snapshot(id: String): RecordedBroadcast? {
        if (!ID.matches(id)) return null
        val stored = load(File(directory, id))
        val current = sessions[id] ?: return stored
        // Even when the disk is full and its metadata cannot be updated, the running operator
        // must see the failed/incomplete recording rather than an old success snapshot.
        return (stored ?: RecordedBroadcast(id, current.started, current.ended, current.status,
            current.dropped.get(), current.failure, emptyList())).copy(
            endedAtMillis = current.ended, state = current.status,
            droppedRecordingFrames = current.dropped.get(), failure = current.failure)
    }

    /** User-controlled removal, never silently deletes the beginning of an active recording. */
    fun delete(id: String): Boolean {
        require(ID.matches(id))
        check(sessions[id]?.let { it.ended == null } != true) { "Stop broadcast before deleting" }
        return worker.submit<Boolean> { drain(); sessions.remove(id); File(directory, id).deleteRecursively() }.get(10, TimeUnit.SECONDS)
    }

    private fun drain() {
        val touched = linkedSetOf<Session>()
        for (attempt in 0 until capacity) {
            val write = writes.poll() ?: break
            val session = write.part.session
            touched.add(session)
            if (session.failure != null) { session.dropped.incrementAndGet(); continue }
            try {
                beforeWrite()
                val track = write.part.tracks.getOrPut(write.channel.id) { Track(write.part, write.channel) }
                track.append(write.frame)
            } catch (_: Exception) {
                session.failure = "STORAGE_WRITE_FAILED"
                session.dropped.incrementAndGet()
            }
        }
        touched.forEach { session ->
            try {
                session.parts.values.forEach { part -> part.tracks.values.forEach { it.flush() } }
                persist(session)
            } catch (_: Exception) { session.failure = "STORAGE_WRITE_FAILED" }
        }
    }

    private fun persist(session: Session) {
        try {
            val folder = File(directory, session.id).apply { check(mkdirs() || isDirectory) }
            val p = Properties().apply {
                setProperty("started", session.started.toString())
                setProperty("ended", session.ended?.toString().orEmpty())
                setProperty("state", session.status)
                setProperty("dropped", session.dropped.get().toString())
                setProperty("failure", session.failure.orEmpty())
                session.parts.values.forEach { part -> part.tracks.values.forEach { track ->
                    val prefix = "track.${part.id}.${track.channel.id}"
                    setProperty("$prefix.language", track.channel.languageTag)
                    setProperty("$prefix.rate", track.channel.sampleRateHz.toString())
                    track.committed.forEach { (n, size) -> setProperty("$prefix.$n.bytes", size.toString()) }
                } }
            }
            val staging = File(folder, "session.properties.tmp")
            staging.outputStream().use { p.store(it, "MCastTalk local broadcast recording") }
            check(staging.renameTo(File(folder, "session.properties")))
        } catch (_: Exception) { session.failure = "STORAGE_METADATA_FAILED" }
    }

    private fun load(folder: File): RecordedBroadcast? = runCatching {
        val p = Properties().apply { File(folder, "session.properties").inputStream().use(::load) }
        val segments = p.stringPropertyNames().mapNotNull { key ->
            val match = SEGMENT.matchEntire(key) ?: return@mapNotNull null
            val (part, channel, number) = match.destructured
            val prefix = "track.$part.$channel"
            val descriptor = AudioChannelDescriptor(channel, channel, p.getProperty("$prefix.language"), p.getProperty("$prefix.rate").toInt())
            val file = File(folder, "$part-$channel-$number.pcm")
            val size = p.getProperty(key).toLong().coerceAtMost(file.length())
            RecordedPcmSegment(folder.name, part.toLong(), descriptor, number.toInt(), size, file)
        }.sortedWith(compareBy<RecordedPcmSegment> { it.partId }.thenBy { it.channel.id }.thenBy { it.segment })
        val ended = p.getProperty("ended").takeIf(String::isNotBlank)?.toLong()
        RecordedBroadcast(folder.name, p.getProperty("started").toLong(), ended,
            if (ended == null && !sessions.containsKey(folder.name)) "INTERRUPTED" else p.getProperty("state"),
            p.getProperty("dropped").toLong(), p.getProperty("failure").takeIf(String::isNotBlank), segments)
    }.getOrNull()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        sessions.values.forEach { it.parts.values.forEach { part -> synchronized(part.admission) { part.open.set(false) } } }
        worker.submit { drain(); sessions.values.forEach { session -> session.parts.values.forEach { part -> part.tracks.values.forEach { it.close() } }; persist(session) } }.get(10, TimeUnit.SECONDS)
        worker.shutdownNow()
    }

    private class Session(val id: String, val started: Long) {
        @Volatile var ended: Long? = null
        @Volatile var status = "PREPARING"
        @Volatile var failure: String? = null
        val dropped = AtomicLong()
        val parts = ConcurrentHashMap<Long, Part>()
    }
    private class Part(val session: Session, val id: Long, val channels: List<AudioChannelDescriptor>) {
        val admission = Any()
        val open = AtomicBoolean(true)
        val tracks = linkedMapOf<String, Track>() // IO-worker ownership only
    }
    private data class Write(val part: Part, val channel: AudioChannelDescriptor, val frame: PcmAudioFrame)

    private inner class Track(val part: Part, val channel: AudioChannelDescriptor) {
        private var number = 0
        private var bytes = 0L
        private var output: java.io.BufferedOutputStream? = null
        private var index: DataOutputStream? = null
        private var isClosed = false
        val committed = linkedMapOf<Int, Long>()
        private fun folder() = File(directory, part.session.id).apply { check(mkdirs() || isDirectory) }
        fun append(frame: PcmAudioFrame) {
            check(!isClosed)
            var offset = 0
            while (offset < frame.bytes.size) {
                if (output == null) output = FileOutputStream(File(folder(), "${part.id}-${channel.id}-$number.pcm"), true).buffered(64 * 1024)
                if (index == null) index = DataOutputStream(FileOutputStream(File(folder(), "${part.id}-${channel.id}.index"), true).buffered(64 * 1024))
                val count = minOf(frame.bytes.size - offset, segmentByteLimit - bytes.toInt())
                // Fixed-width index records: original monotonic stamp, utterance or -1, segment,
                // sample offset and byte count. No invented source/translation correspondence.
                index!!.writeLong(frame.capturedAtElapsedRealtimeNanos)
                index!!.writeLong(frame.utteranceSequence ?: -1L)
                index!!.writeInt(number); index!!.writeLong(bytes / 2); index!!.writeInt(count)
                output!!.write(frame.bytes, offset, count)
                bytes += count; offset += count
                if (bytes == segmentByteLimit.toLong()) { flush(); output!!.close(); output = null; number++; bytes = 0 }
            }
        }
        fun flush() {
            if (isClosed) return
            output?.flush(); index?.flush()
            if (bytes > 0) committed[number] = bytes
        }
        fun close() { if (!isClosed) { flush(); output?.close(); index?.close(); isClosed = true } }
    }

    companion object {
        private val ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val SEGMENT = Regex("track\\.(\\d+)\\.([a-z0-9][a-z0-9_-]{0,23})\\.(\\d+)\\.bytes")
    }
}
