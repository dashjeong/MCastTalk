package app.guidecast.transmitter

import android.content.Context
import app.guidecast.core.stream.PcmBroadcastArchive
import app.guidecast.core.stream.RecordedBroadcast
import app.guidecast.core.stream.StreamSession
import java.io.Closeable
import java.io.File
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.json.JSONObject

internal data class RecordedCaption(val part: Long, val sequence: Long, val original: String,
    val translations: Map<String, String>, val final: Boolean, val monotonicNanos: Long,
    val alignment: String, val outputState: String?, val endReason: String?)

/** Operator-owned local media; never uses a model or uploads a recording. */
internal class BroadcastRecordingRepository(context: Context,
    private val shares: RecordedBroadcastShareStore = recordedShareStore(context)) : Closeable {
    private val directory = File(context.noBackupFilesDir, "broadcast-recordings")
    val audio = PcmBroadcastArchive(directory)
    private val worker = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "recorded-captions-io").apply { isDaemon = true } }
    private val shareCleanup = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "recorded-share-cleanup").apply { isDaemon = true } }
    private val pending = linkedMapOf<Triple<String, Long, Long>, String>()
    private val seen = linkedMapOf<Triple<String, Long, Long>, String>()
    private val captionGaps = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val captionLengths = java.util.concurrent.ConcurrentHashMap<String, Long>()
    @Volatile var activeId: String? = null; private set
    @Volatile var activePart: Long? = null; private set
    private var subscription: Closeable? = null
    init {
        worker.scheduleWithFixedDelay({ drainCaptions() }, 100, 200, TimeUnit.MILLISECONDS)
        shareCleanup.scheduleWithFixedDelay({ runCatching { shares.cleanup() } }, 0, 1, TimeUnit.HOURS)
    }

    @Synchronized fun startPart(stream: StreamSession, title: String? = null): String {
        closePart()
        val id = activeId ?: audio.begin(title.orEmpty()).also { activeId = it }
        subscription = audio.attach(id, stream)
        activePart = stream.generation
        return id
    }

    @Synchronized fun closePart() {
        activePart = null
        subscription?.close(); subscription = null
        synchronized(pending) { seen.clear() }
    }

    /** Native close callbacks synchronously finalize their own rows before recording detaches. */
    @Synchronized fun closePartAfterTerminalization(terminalize: () -> List<TranslationTranscriptLine>) {
        val part = activePart
        try {
            val rows = terminalize()
            if (part != null && part == activePart) capture(part, rows)
        } finally { if (part == activePart) closePart() }
    }

    @Synchronized fun finish(failed: Boolean = false) {
        closePart()
        activeId?.let { audio.finish(it, if (failed) "FAILED" else "COMPLETED") }
        activeId = null
    }

    fun capture(part: Long, lines: List<TranslationTranscriptLine>) {
        val id = activeId ?: return
        if (activePart != part) return
        synchronized(pending) {
            lines.forEach { line ->
                val key = Triple(id, part, line.sequence)
                val body = JSONObject().put("part", part).put("sequence", line.sequence)
                    .put("original", line.sourceText).put("translations", JSONObject(line.translations))
                    .put("final", line.isFinal).put("monotonicNanos", line.capturedAtElapsedRealtimeNanos)
                    .put("alignment", line.recordingAlignment.name)
                    .put("outputState", line.liveOutputState?.name ?: JSONObject.NULL)
                    .put("endReason", line.liveEndReason?.name ?: JSONObject.NULL).toString()
                if (seen[key] == body) return@forEach
                if (pending.size >= 1024 && key !in pending) {
                    // The recording continues. Its caption copy has an explicit incomplete marker.
                    captionGaps.add(id)
                    return@forEach
                }
                pending[key] = body
                seen[key] = body
            }
            // Dedup memory belongs to the current bounded runtime window only.
            while (seen.size > 4096) seen.remove(seen.keys.first())
        }
    }

    private fun drainCaptions() {
        val batch = synchronized(pending) { pending.toMap().also { pending.clear() } }
        batch.entries.groupBy { it.key.first }.forEach { (id, rows) ->
            try {
                val folder = File(directory, id).apply { check(mkdirs() || isDirectory) }
                val file = File(folder, "captions.ndjson")
                val committed = committedCaptionLength(id)
                if (file.exists() && file.length() != committed) {
                    captionGaps.add(id)
                    java.io.RandomAccessFile(file, "rw").use { it.setLength(committed) }
                }
                file.appendText(rows.joinToString("\n", postfix = "\n") { it.value })
                // Readers/export never snapshot a partially appended JSON line.
                captionLengths[id] = file.length()
            } catch (_: Exception) {
                captionGaps.add(id)
            }
        }
        captionGaps.forEach { id -> runCatching { File(directory, "$id/caption-gap").writeText("CAPTION_COPY_INCOMPLETE") } }
    }

    fun captions(id: String, after: Pair<Long, Long>? = null, limit: Int = 100, committedLength: Long? = null): List<RecordedCaption> {
        require(limit in 1..100)
        if (audio.snapshot(id) == null) return emptyList()
        val chosen = java.util.TreeMap<Pair<Long, Long>, RecordedCaption>(compareBy<Pair<Long, Long>> { it.first }.thenBy { it.second })
        val input = File(directory, "$id/captions.ndjson")
        if (!input.isFile) return emptyList()
        val bounded = object : java.io.FilterInputStream(input.inputStream()) {
            var remaining = minOf(input.length(), committedLength ?: committedCaptionLength(id))
            override fun read(): Int { if (remaining <= 0) return -1; val value = super.read(); if (value >= 0) remaining--; return value }
            override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
                if (remaining <= 0) return -1
                val read = super.read(bytes, offset, minOf(count.toLong(), remaining).toInt())
                if (read > 0) remaining -= read
                return read
            }
        }
        bounded.bufferedReader().useLines { lines -> lines.forEach { raw -> runCatching {
            val row = JSONObject(raw); val key = row.getLong("part") to row.getLong("sequence")
            if (after != null && (key.first < after.first || (key.first == after.first && key.second <= after.second))) return@runCatching
            val targets = row.getJSONObject("translations")
            chosen[key] = RecordedCaption(key.first, key.second, row.getString("original"),
                targets.keys().asSequence().associateWith { targets.getString(it) }, row.getBoolean("final"), row.getLong("monotonicNanos"),
                // Older Gemini rows already have a native output state but lacked its session ID.
                if (!row.isNull("outputState") && row.has("outputState")) "NATIVE_PAIR_UNCONFIRMED" else row.getString("alignment"),
                row.optString("outputState").takeUnless { it == "null" || it.isBlank() },
                row.optString("endReason").takeUnless { it == "null" || it.isBlank() })
            if (chosen.size > limit) chosen.pollLastEntry()
        } } }
        return chosen.values.toList()
    }

    fun captionIncomplete(id: String): Boolean = audio.snapshot(id) != null && (id in captionGaps || File(directory, "$id/caption-gap").exists())
    fun history(): List<RecordedBroadcast> = audio.snapshots()
    fun rename(id: String, title: String): Boolean = audio.rename(id, title)
    fun flush() { worker.submit { drainCaptions() }.get(10, TimeUnit.SECONDS); audio.flush() }
    fun captionFile(id: String): File? = if (audio.snapshot(id) != null) File(directory, "$id/captions.ndjson").takeIf { it.isFile } else null
    fun committedCaptionLength(id: String): Long = captionLengths.getOrPut(id) {
        val file = captionFile(id) ?: return@getOrPut 0L
        val committed = completeCaptionPrefix(file)
        if (committed != file.length()) captionGaps.add(id)
        committed
    }
    fun stageShare(snapshot: RecordedBroadcast): File = shares.create(snapshot.id) { file -> file.outputStream().use { exportRecordedBroadcast(this, snapshot, it) } }
    fun restoreDownload(name: String?): File? = shares.resolveDownload(name)
    fun stageMp3(snapshot: RecordedBroadcast, choice: RecordedAudioChoice,
        checkRunning: () -> Unit = {}, progress: (Long, Long) -> Unit = { _, _ -> }): File =
        shares.create(snapshot.id, "mp3") { file ->
            flush()
            val frozen = requireNotNull(audio.snapshot(snapshot.id))
            encodeRecordedMp3(choice.segments(frozen.segments), file, checkRunning, progress)
        }
    fun stageScript(snapshot: RecordedBroadcast, checkRunning: () -> Unit = {}): File =
        shares.create(snapshot.id, "txt") { file ->
            flush()
            val length = committedCaptionLength(snapshot.id)
            file.outputStream().buffered().use { out ->
                var after: Pair<Long, Long>? = null
                while (true) {
                    checkRunning()
                    val rows = captions(snapshot.id, after, committedLength = length)
                    if (rows.isEmpty()) break
                    rows.forEach { row ->
                        out.write(buildString {
                            append("[${row.part}:${row.sequence}] ${if (row.final) "확정" else "미완료"} · ${row.alignment}\n")
                            row.outputState?.let { append("통역 상태: $it · 종료 원인: ${row.endReason ?: "미확인"}\n") }
                            append("원문: ${row.original.ifBlank { "전사 없음" }}\n")
                            row.translations.forEach { (tag, text) ->
                                append("${java.util.Locale.forLanguageTag(tag).getDisplayLanguage(java.util.Locale.KOREAN)}: $text\n")
                            }; append('\n')
                        }.toByteArray())
                    }
                    after = rows.last().let { it.part to it.sequence }
                    if (rows.size < 100) break
                }
            }
        }
    @Synchronized fun delete(id: String) {
        check(id != activeId) { "Stop broadcast before deleting" }
        flush()
        val recording = audio.snapshot(id)
        check(recording == null || recording.endedAtMillis != null || recording.state == "INTERRUPTED")
        // Keep the history entry retryable if an app-owned sharing copy cannot be removed.
        val uniqueLegacyStart = recording?.startedAtMillis?.takeIf { start -> audio.snapshots().count { it.startedAtMillis == start } == 1 }
        shares.deleteRecording(id, uniqueLegacyStart)
        check(audio.delete(id)); captionLengths.remove(id); captionGaps.remove(id)
    }
    override fun close() { finish(); flush(); worker.shutdownNow(); shareCleanup.shutdownNow(); audio.close() }
}

/** Backwards scan uses fixed memory and accepts only complete newline-terminated records. */
internal fun completeCaptionPrefix(file: File): Long = java.io.RandomAccessFile(file, "r").use { input ->
    var end = input.length(); val buffer = ByteArray(16 * 1024)
    while (end > 0) {
        val start = maxOf(0, end - buffer.size); val count = (end - start).toInt()
        input.seek(start); input.readFully(buffer, 0, count)
        for (i in count - 1 downTo 0) if (buffer[i] == '\n'.code.toByte()) return@use start + i + 1
        end = start
    }
    0L
}
