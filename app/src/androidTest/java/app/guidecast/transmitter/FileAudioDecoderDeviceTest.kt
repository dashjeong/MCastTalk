package app.guidecast.transmitter

import android.net.Uri
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaExtractor
import android.os.Build
import android.os.ParcelFileDescriptor
import android.speech.RecognizerIntent
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class FileAudioDecoderDeviceTest {
    @Test fun mediaCodecDecodesAacMp4OnExtractedMediaTimeline(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("file-aac-fixture", ".m4a", context.cacheDir)
        try {
            createAacFixture(file)
            // MediaMuxer may normalize the submitted 1.5s origin through the MP4 edit list.
            // Playback uses the resulting container timeline, not the encoder input timestamps.
            val expectedStart = MediaExtractor().let { extractor ->
                try {
                    extractor.setDataSource(file.absolutePath)
                    extractor.selectTrack(0)
                    extractor.sampleTime / 1_000L
                } finally { extractor.release() }
            }
            var first = -1L
            var previous = -1L
            var samples = 0L
            var nonzero = false
            FileAudioDecoder.decode(context, Uri.fromFile(file)).collect { frame ->
                if (first < 0) first = frame.startMs
                assertTrue(frame.startMs >= previous)
                previous = frame.startMs
                samples += frame.bytes.size / 2
                nonzero = nonzero || frame.bytes.any { it != 0.toByte() }
            }
            assertTrue("Decoded start=$first, container start=$expectedStart", first in expectedStart.coerceAtLeast(0)..(expectedStart.coerceAtLeast(0) + 150))
            assertTrue("Decoded end=$previous, container start=$expectedStart", previous in (expectedStart.coerceAtLeast(0) + 1_750)..(expectedStart.coerceAtLeast(0) + 2_300))
            assertTrue("Decoded samples=$samples", samples in 30_000L..36_000L)
            assertTrue(nonzero)
        } finally { file.delete() }
    }

    @Test fun extractorDecodesStereoWaveWithMediaTimelineAndCanBeCancelledAndReopened(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("file-decode-fixture", ".wav", context.cacheDir)
        try {
            val dataBytes = 48_000 * 2 * 2 * 2
            val wav = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
            wav.put("RIFF".toByteArray()).putInt(dataBytes + 36).put("WAVEfmt ".toByteArray())
                .putInt(16).putShort(1).putShort(2).putInt(48_000).putInt(192_000)
                .putShort(4).putShort(16).put("data".toByteArray()).putInt(dataBytes)
            repeat(96_000) { index ->
                val sample = (sin(index * 2 * Math.PI * 440 / 48_000) * 12_000).toInt().toShort()
                wav.putShort(sample).putShort(sample)
            }
            file.writeBytes(wav.array())
            var seen = 0
            FileAudioDecoder.decode(context, Uri.fromFile(file)).take(2).collect { seen++ }
            assertEquals(2, seen)
            var count = 0L
            var previous = -1L
            var peak = 0
            FileAudioDecoder.decode(context, Uri.fromFile(file)).collect { frame ->
                assertTrue(frame.bytes.size <= 640)
                assertEquals(0, frame.bytes.size % 2)
                assertTrue(frame.startMs >= previous)
                previous = frame.startMs
                count += frame.bytes.size / 2
                val samples = ByteBuffer.wrap(frame.bytes).order(ByteOrder.LITTLE_ENDIAN)
                while (samples.hasRemaining()) peak = maxOf(peak, kotlin.math.abs(samples.short.toInt()))
            }
            assertEquals(32_000L, count)
            assertTrue(peak > 5_000)
            assertTrue(previous in 1_950L..2_000L)
        } finally { file.delete() }
    }

    @Test fun fileRecognitionUsesExternalSegmentedPcmAndRequestsRealAutoLanguageAndWordEvidence() {
        assumeTrue(Build.VERSION.SDK_INT >= 34)
        val pipe = ParcelFileDescriptor.createPipe()
        try {
            val auto = fileRecognitionIntent(pipe[0], null)
            assertEquals(RecognizerIntent.EXTRA_AUDIO_SOURCE, auto.getStringExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION))
            assertEquals(16_000, auto.getIntExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 0))
            assertTrue(auto.getBooleanExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, false))
            assertTrue(auto.getBooleanExtra(RecognizerIntent.EXTRA_REQUEST_WORD_TIMING, false))
            assertEquals(RecognizerIntent.LANGUAGE_SWITCH_BALANCED, auto.getStringExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH))
            val manual = fileRecognitionIntent(pipe[0], "ja-JP")
            assertEquals("ja-JP", manual.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
            assertFalse(manual.hasExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH))
        } finally { pipe.forEach { it.close() } }
    }

    private fun createAacFixture(file: File) {
        val encoder = MediaCodec.createEncoderByType("audio/mp4a-latm")
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        var muxerStarted = false
        try {
            val format = MediaFormat.createAudioFormat("audio/mp4a-latm", 16_000, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start(); started = true
            var submittedSamples = 0
            var inputEnded = false
            var outputEnded = false
            var track = -1
            val info = MediaCodec.BufferInfo()
            val deadline = android.os.SystemClock.elapsedRealtime() + 30_000
            while (!outputEnded) {
                check(android.os.SystemClock.elapsedRealtime() < deadline)
                if (!inputEnded) {
                    val index = encoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = requireNotNull(encoder.getInputBuffer(index)).order(ByteOrder.LITTLE_ENDIAN)
                        buffer.clear()
                        val count = minOf(buffer.remaining() / 2, 32_000 - submittedSamples, 1_024)
                        repeat(count) { offset -> buffer.putShort(
                            (sin((submittedSamples + offset) * 2 * Math.PI * 440 / 16_000) * 10_000).toInt().toShort(),
                        ) }
                        val pts = 1_500_000L + submittedSamples * 1_000_000L / 16_000
                        inputEnded = count == 0
                        encoder.queueInputBuffer(index, 0, count * 2, pts,
                            if (inputEnded) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        submittedSamples += count
                    }
                }
                val index = encoder.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(encoder.outputFormat)
                    muxer.start(); muxerStarted = true
                } else if (index >= 0) {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            check(muxerStarted)
                            muxer.writeSampleData(track, requireNotNull(encoder.getOutputBuffer(index)), info)
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally { encoder.releaseOutputBuffer(index, false) }
                }
            }
        } finally {
            if (started) runCatching { encoder.stop() }
            encoder.release()
            if (muxerStarted) runCatching { muxer.stop() }
            muxer.release()
        }
    }
}
