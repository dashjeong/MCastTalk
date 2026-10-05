package app.guidecast.transmitter

import app.guidecast.core.stream.AudioChannelDescriptor
import app.guidecast.core.stream.RecordedPcmSegment
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class RecordedMp3ExportTest {
    private fun fixture(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("mp3-flow-test").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }
    private fun segment(file: File, offset: Long = 0, length: Long = file.length() - offset, rate: Int = 16000) =
        RecordedPcmSegment("synthetic", 1, AudioChannelDescriptor("source", "Source", "ko", rate), 0, length, file, offset)
    @Test fun committedSliceUsesSignedLittleEndianAndFinalTagBeforePublishing() = fixture { dir ->
        val source = File(dir,"pcm").apply { writeBytes(byteArrayOf(9,9,0,0,-1,127,0,-128,-1,-1,8,8)) }
        val samples = mutableListOf<Short>(); var closed = false
        val encoder = object : RecordedMp3Encoder {
            override fun encode(values: ShortArray, count: Int): ByteArray { samples += values.take(count); return byteArrayOf(0,0,7,8,9,10) }
            override fun flush() = byteArrayOf(11,12)
            override fun tag() = byteArrayOf(1,2)
            override fun close() { closed = true }
        }
        val target = File(dir,"encoded")
        encodeRecordedMp3(listOf(segment(source,2,8)), target, encoderFactory = { encoder })
        assertEquals(listOf<Short>(0,32767,-32768,-1), samples)
        assertArrayEquals(byteArrayOf(1,2,7,8,9,10,11,12), target.readBytes())
        assertTrue(closed); assertEquals(12L, source.length())
    }
    @Test fun cancellationClosesEncoderAndRemovesPartialOutputWithoutTouchingSource() = fixture { dir ->
        val source = File(dir,"pcm").apply { writeBytes(ByteArray(20000)) }; val target = File(dir,"out")
        var checks = 0; var closed = false
        val encoder = object : RecordedMp3Encoder {
            override fun encode(samples: ShortArray, count: Int) = byteArrayOf(1,2)
            override fun flush(): ByteArray = error("Must not finalize after cancellation")
            override fun tag(): ByteArray = error("Must not tag after cancellation")
            override fun close() { closed = true }
        }
        try { encodeRecordedMp3(listOf(segment(source)), target,
            checkRunning = { if (++checks == 3) throw CancellationException() }, encoderFactory = { encoder }); fail() }
        catch (_: CancellationException) { }
        assertTrue(closed); assertFalse(target.exists()); assertEquals(20000L,source.length())
    }
    @Test fun differentSampleRatesCannotSilentlyBecomeOneMp3() = fixture { dir ->
        val source=File(dir,"pcm").apply { writeBytes(byteArrayOf(1,2)) }
        val segments=listOf(segment(source),segment(source,rate=24000))
        assertEquals(2, recordedAudioChoices(segments).size)
        try { encodeRecordedMp3(segments, File(dir,"out"), encoderFactory={ error("Must reject before creating encoder") }); fail() }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun truncatedCommittedPcmFailsAndClosesEncoder() = fixture { dir ->
        val source=File(dir,"pcm").apply { writeBytes(byteArrayOf(1,2)) }; var closed=false; val target=File(dir,"out")
        val encoder=object : RecordedMp3Encoder {
            override fun encode(samples: ShortArray,count:Int):ByteArray=error("Cannot read incomplete data")
            override fun flush():ByteArray=error("Cannot finalize incomplete data")
            override fun tag():ByteArray=error("Cannot finalize incomplete data")
            override fun close(){ closed=true }
        }
        try { encodeRecordedMp3(listOf(segment(source,length=4)),target,encoderFactory={encoder});fail() }
        catch (_: IllegalStateException) { }
        assertTrue(closed);assertFalse(target.exists())
    }

    @Test fun oddPcmOffsetIsRejectedBeforeCreatingEncoder() = fixture { dir ->
        val source = File(dir, "pcm").apply { writeBytes(ByteArray(4)) }
        try { encodeRecordedMp3(listOf(segment(source, 1, 2)), File(dir, "out"),
            encoderFactory = { error("Unaligned PCM must not reach encoder") }); fail() }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun queuedExportObservesCancellationWithoutWaitingForActiveExportToFinish() = fixture { dir ->
        val source = File(dir, "pcm").apply { writeBytes(ByteArray(4)) }
        val owned = CountDownLatch(1); val release = CountDownLatch(1)
        val waiting = CountDownLatch(1); val cancelled = AtomicBoolean(false)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val active = executor.submit {
                encodeRecordedMp3(listOf(segment(source)), File(dir, "active"), encoderFactory = {
                    object : RecordedMp3Encoder {
                        override fun encode(samples: ShortArray, count: Int): ByteArray {
                            owned.countDown(); check(release.await(5, TimeUnit.SECONDS)); return byteArrayOf(0,0,3,4)
                        }
                        override fun flush() = byteArrayOf(5,6)
                        override fun tag() = byteArrayOf(1,2)
                        override fun close() { }
                    }
                })
            }
            assertTrue(owned.await(2, TimeUnit.SECONDS))
            val queued = executor.submit {
                try {
                    encodeRecordedMp3(listOf(segment(source)), File(dir, "queued"),
                        checkRunning = { waiting.countDown(); if (cancelled.get()) throw CancellationException() },
                        encoderFactory = { error("Cancelled waiter must not open an encoder") })
                    fail("Queued export must cancel")
                } catch (_: CancellationException) { }
            }
            assertTrue(waiting.await(2, TimeUnit.SECONDS)); cancelled.set(true)
            queued.get(2, TimeUnit.SECONDS)
            assertFalse(active.isDone); assertFalse(File(dir, "queued").exists())
            release.countDown(); active.get(2, TimeUnit.SECONDS)
        } finally { release.countDown(); executor.shutdownNow() }
    }
}
