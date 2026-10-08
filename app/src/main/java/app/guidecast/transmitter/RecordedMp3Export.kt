package app.guidecast.transmitter

import app.guidecast.core.stream.RecordedPcmSegment
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** Genuine encoding, not a renamed WAV. Each export owns one encoder; no cloud work. */
internal object LameMp3Native {
    init { System.loadLibrary("mp3lame"); System.loadLibrary("guidecast_mp3") }
    external fun create(rate: Int): Long
    external fun encode(handle: Long, samples: ShortArray, count: Int): ByteArray
    external fun flush(handle: Long): ByteArray
    external fun tag(handle: Long): ByteArray
    external fun close(handle: Long)
    external fun version(): String
}

internal interface RecordedMp3Encoder : Closeable {
    fun encode(samples: ShortArray, count: Int): ByteArray
    fun flush(): ByteArray
    /** Final Info/Xing duration and encoder delay metadata replacing the reserved first frame. */
    fun tag(): ByteArray
}

private class NativeRecordedMp3Encoder(rate: Int) : RecordedMp3Encoder {
    private var handle = runCatching { LameMp3Native.create(rate) }.getOrElse {
        throw IllegalStateException("MP3 인코더를 준비하지 못했습니다", it)
    }
    override fun encode(samples: ShortArray, count: Int) = LameMp3Native.encode(handle, samples, count)
    override fun flush() = LameMp3Native.flush(handle)
    override fun tag() = LameMp3Native.tag(handle)
    override fun close() { if (handle != 0L) { LameMp3Native.close(handle); handle = 0L } }
}

internal data class RecordedAudioChoice(val channel: String, val rate: Int) {
    val key: String get() = "$channel-$rate"
    val label: String get() = if (channel == "source") "원음" else
        "${recordedChannelDisplayName(channel, channel)} 통역"
    val fileLabel: String get() = "$key.mp3"
}
internal fun recordedAudioChoices(segments: List<RecordedPcmSegment>): List<RecordedAudioChoice> =
    segments.filter { it.committedBytes > 0 }.map { RecordedAudioChoice(it.channel.id, it.channel.sampleRateHz) }.distinct()
internal fun RecordedAudioChoice.segments(segments: List<RecordedPcmSegment>) =
    segments.filter { it.channel.id == channel && it.channel.sampleRateHz == rate && it.committedBytes > 0 }

private val mp3ExportAdmission = Semaphore(1, true)

/** Seekable private staging allows duration-tag finalization before SAF copy or URI sharing. */
internal fun encodeRecordedMp3(segments: List<RecordedPcmSegment>, file: File,
    checkRunning: () -> Unit = { check(!Thread.currentThread().isInterrupted) },
    progress: (Long, Long) -> Unit = { _, _ -> },
    encoderFactory: (Int) -> RecordedMp3Encoder = ::NativeRecordedMp3Encoder,
) {
    require(segments.isNotEmpty()) { "저장된 음성이 없습니다" }
    val channel = segments.first().channel
    require(segments.all { it.channel.id == channel.id && it.channel.sampleRateHz == channel.sampleRateHz }) {
        "서로 다른 언어·음질의 음원은 각각 MP3로 저장하세요"
    }
    require(segments.all { it.fileOffsetBytes >= 0 && it.fileOffsetBytes % 2 == 0L && it.committedBytes > 0 && it.committedBytes % 2 == 0L &&
        it.fileOffsetBytes <= Long.MAX_VALUE - it.committedBytes })
    val total = segments.fold(0L) { sum, s -> Math.addExact(sum, s.committedBytes) }
    // Check the caller's cancellation while another export owns the encoder budget.
    while (true) {
        checkRunning()
        if (mp3ExportAdmission.tryAcquire(50, TimeUnit.MILLISECONDS)) break
    }
    try {
        checkRunning()
        encoderFactory(channel.sampleRateHz).use { encoder ->
            RandomAccessFile(file, "rw").use { output ->
                output.setLength(0)
                val bytes = ByteArray(8192); val samples = ShortArray(4096)
                var completed = 0L
                segments.forEach { segment ->
                    RandomAccessFile(segment.file, "r").use { input ->
                        check(input.length() >= segment.fileOffsetBytes + segment.committedBytes) { "저장 구간이 불완전합니다" }
                        input.seek(segment.fileOffsetBytes)
                        var remaining = segment.committedBytes
                        while (remaining > 0) {
                            checkRunning()
                            val count = minOf(bytes.size.toLong(), remaining).toInt()
                            input.readFully(bytes, 0, count)
                            for (i in 0 until count / 2) samples[i] =
                                ((bytes[i * 2].toInt() and 255) or (bytes[i * 2 + 1].toInt() shl 8)).toShort()
                            output.write(encoder.encode(samples, count / 2))
                            remaining -= count; completed += count; progress(completed, total)
                        }
                    }
                }
                checkRunning(); output.write(encoder.flush())
                val tag = encoder.tag()
                check(tag.isNotEmpty() && tag.size <= output.length()) { "MP3 길이 정보를 저장하지 못했습니다" }
                output.seek(0); output.write(tag); output.fd.sync()
            }
        }
    } catch (failure: Throwable) {
        file.delete(); throw failure
    } finally { mp3ExportAdmission.release() }
}
