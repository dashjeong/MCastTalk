package app.guidecast.transmitter

import android.content.Context
import android.content.ClipData
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.core.content.FileProvider
import app.guidecast.core.stream.RecordedBroadcast
import app.guidecast.core.stream.RecordedPcmSegment
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class RecordedAudioPlaybackControl {
    @Volatile var paused = false; private set
    @Volatile var pauseTrack: (() -> Unit)? = null
    @Volatile var resumeTrack: (() -> Unit)? = null
    fun pause() { paused = true; pauseTrack?.invoke() }
    fun resume() { paused = false; resumeTrack?.invoke() }
}

internal suspend fun playRecordedPcm(segments: List<RecordedPcmSegment>, ownership: RecordedPlaybackOwnership,
    control: RecordedAudioPlaybackControl? = null, onProgress: (Long, Long) -> Unit = { _, _ -> },
    onStarted: suspend () -> Unit = {}) = withContext(Dispatchers.IO) {
    var track: AudioTrack? = null
    val playbackJob = requireNotNull(currentCoroutineContext()[kotlinx.coroutines.Job])
    val trackRef = java.util.concurrent.atomic.AtomicReference<AudioTrack?>()
    val lease = ownership.beginPlayback {
        playbackJob.cancel()
        runCatching { trackRef.get()?.let { it.pause(); it.flush() } }
    }
    var rate = 0
    var admittedSamples = 0L
    var started = false
    val totalMillis = segments.sumOf { it.committedBytes * 1000 / (2L * it.channel.sampleRateHz) }
    var completedMillis = 0L
    fun reportPosition() { onProgress((completedMillis + ((track?.playbackHeadPosition?.toLong() ?: 0) and 0xffffffffL) * 1000 / maxOf(1, rate)).coerceAtMost(totalMillis), totalMillis) }
    control?.pauseTrack = { runCatching { trackRef.get()?.pause() } }
    control?.resumeTrack = { runCatching { lease.whileActive { trackRef.get()?.play() } } }
    val buffer = ByteArray(32 * 1024)
    suspend fun drainTrack() {
        var deadline = android.os.SystemClock.elapsedRealtime() + 4000
        while (track != null && (track!!.playbackHeadPosition.toLong() and 0xffffffffL) < admittedSamples && android.os.SystemClock.elapsedRealtime() < deadline) {
            currentCoroutineContext().ensureActive()
            if (control?.paused == true) { deadline = android.os.SystemClock.elapsedRealtime() + 4000; kotlinx.coroutines.delay(100) }
            else kotlinx.coroutines.delay(10)
            reportPosition()
        }
    }
    try {
        for (segment in segments) {
            currentCoroutineContext().ensureActive()
            if (rate != segment.channel.sampleRateHz) {
                drainTrack()
                if (rate > 0) completedMillis += admittedSamples * 1000 / rate
                track?.stop(); track?.release()
                rate = segment.channel.sampleRateHz
                admittedSamples = 0
                track = lease.whileActive { AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(maxOf(32 * 1024, AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)))
                    .setTransferMode(AudioTrack.MODE_STREAM).build().also { check(it.state == AudioTrack.STATE_INITIALIZED); trackRef.set(it); it.play() } }
                if (!started) { withContext(Dispatchers.Main) { onStarted() }; started = true }
            }
            segment.file.inputStream().use { input ->
                var skip = segment.fileOffsetBytes
                while (skip > 0) { val count = input.skip(skip); check(count > 0); skip -= count }
                var remaining = segment.committedBytes
                while (remaining > 0) {
                    currentCoroutineContext().ensureActive()
                    while (control?.paused == true) { currentCoroutineContext().ensureActive(); reportPosition(); kotlinx.coroutines.delay(100) }
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    check(read > 0) { "녹음 파일이 불완전합니다" }
                    var offset = 0
                    while (offset < read) {
                        currentCoroutineContext().ensureActive()
                        if (control?.paused == true) { reportPosition(); kotlinx.coroutines.delay(50); continue }
                        // A full/paused platform buffer is backpressure, not playback failure.
                        // Non-blocking writes keep pause, input takeover and cancellation responsive.
                        val written = requireNotNull(track).write(buffer, offset, read - offset, AudioTrack.WRITE_NON_BLOCKING)
                        check(written >= 0) { "기기 재생 오류" }
                        if (written == 0) { kotlinx.coroutines.delay(10); continue }
                        offset += written
                        admittedSamples += written / 2
                        reportPosition()
                    }
                    remaining -= read
                }
            }
        }
        // Wait for the final admitted PCM to leave the platform buffer before releasing it.
        drainTrack()
        onProgress(totalMillis, totalMillis)
    } finally {
        control?.pauseTrack = null; control?.resumeTrack = null
        trackRef.set(null)
        runCatching { track?.pause(); track?.flush(); track?.release() }
        lease.release()
    }
}

/** Frozen committed prefixes, so an active recording can continue while export is read. */
internal fun exportRecordedBroadcast(repository: BroadcastRecordingRepository, snapshot: RecordedBroadcast, output: OutputStream) {
    // This IO-only barrier includes terminal caption updates and final admitted PCM before freezing.
    repository.flush()
    val frozen = requireNotNull(repository.audio.snapshot(snapshot.id)) { "Recording unavailable" }
    ZipOutputStream(output.buffered()).use { zip ->
        fun entry(name: String, body: (OutputStream) -> Unit) { zip.putNextEntry(ZipEntry(name)); body(zip); zip.closeEntry() }
        entry("broadcast.txt") { it.write(("MCastTalk 방송 ${frozen.startedAtMillis}\n종료 ${frozen.endedAtMillis ?: "진행 중"}\n상태 ${frozen.state}\n녹음 공백 ${frozen.droppedRecordingFrames}\n저장 오류 ${frozen.failure ?: "없음"}\n스크립트 공백 ${repository.captionIncomplete(frozen.id)}\n음성: mono PCM16 WAV; 원문·통역 대응 미확인 구간이 있을 수 있습니다.\n").toByteArray()) }
        for (segment in frozen.segments) {
            entry("audio/${segment.partId}-${segment.channel.id}-${segment.segment}.wav") { out ->
                out.write(voiceNoteWavHeader(segment.committedBytes, segment.channel.sampleRateHz))
                segment.file.inputStream().use { copyCommittedPrefix(it, out, segment.committedBytes) }
            }
        }
        repository.captionFile(snapshot.id)?.let { file ->
            val length = repository.committedCaptionLength(snapshot.id)
            entry("scripts.ndjson") { out ->
                var after: Pair<Long, Long>? = null
                while (true) {
                    val rows = repository.captions(snapshot.id, after, committedLength = length)
                    if (rows.isEmpty()) break
                    rows.forEach { row -> out.write((org.json.JSONObject().put("part", row.part).put("sequence", row.sequence)
                        .put("original", row.original).put("translations", org.json.JSONObject(row.translations))
                        .put("final", row.final).put("monotonicNanos", row.monotonicNanos).put("alignment", row.alignment)
                        .put("outputState", row.outputState ?: org.json.JSONObject.NULL).put("endReason", row.endReason ?: org.json.JSONObject.NULL)
                        .toString() + "\n").toByteArray()) }
                    after = rows.last().let { it.part to it.sequence }
                    if (rows.size < 100) break
                }
            }
            entry("scripts.txt") { out ->
                var after: Pair<Long, Long>? = null
                while (true) {
                    val rows = repository.captions(snapshot.id, after, committedLength = length)
                    if (rows.isEmpty()) break
                    rows.forEach { row -> out.write(buildString {
                        append("[${row.part}:${row.sequence}] ${if (row.final) "확정" else "미완료"} · ${row.alignment}\n")
                        row.outputState?.let { append("통역 상태: $it · 종료 원인: ${row.endReason ?: "미확인"}\n") }
                        append("원문: ${row.original.ifBlank { "전사 없음" }}\n")
                        row.translations.forEach { (language, text) -> append("$language: $text\n") }; append('\n')
                    }.toByteArray()) }
                    after = rows.last().let { it.part to it.sequence }
                    if (rows.size < 100) break
                }
            }
        }
    }
}

internal fun copyCommittedPrefix(input: InputStream, output: OutputStream, length: Long) {
    require(length >= 0)
    var remaining = length; val buffer = ByteArray(64 * 1024)
    while (remaining > 0) {
        val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
        check(count > 0) { "저장 구간이 불완전합니다" }
        output.write(buffer, 0, count); remaining -= count
    }
}

internal fun stagedRecordedBroadcast(context: Context, repository: BroadcastRecordingRepository, snapshot: RecordedBroadcast): File {
    return repository.stageShare(snapshot)
}

internal fun shareRecordedBroadcast(context: Context, file: File) {
    val uri = recordedBroadcastUri(context, file)
    val intent = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newUri(context.contentResolver, "방송 이력", uri) }
    context.startActivity(Intent.createChooser(intent, "방송 파일 공유"))
}
internal fun shareRecordedArtifacts(context: Context, files: List<File>) {
    require(files.isNotEmpty() && files.size <= 32 && files.all { it.isFile && it.extension in setOf("mp3", "txt") })
    val uris = ArrayList(files.map { recordedBroadcastUri(context, it) })
    val mime = if (files.all { it.extension == "mp3" }) "audio/mpeg"
        else if (files.all { it.extension == "txt" }) "text/plain" else "*/*"
    val intent = Intent(if (files.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).setType(mime)
    if (files.size == 1) intent.putExtra(Intent.EXTRA_STREAM, uris.single()) else intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    intent.clipData = ClipData.newUri(context.contentResolver, "저장한 방송", uris.first()).apply {
        uris.drop(1).forEach { addItem(ClipData.Item(it)) }
    }
    context.startActivity(Intent.createChooser(intent, "방송 음원·스크립트 공유"))
}
internal fun recordedBroadcastUri(context: Context, file: File): android.net.Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.broadcastfiles", file)
