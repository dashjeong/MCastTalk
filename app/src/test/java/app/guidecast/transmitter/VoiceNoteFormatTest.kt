package app.guidecast.transmitter

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class VoiceNoteFormatTest {
    @Test fun originalLanguageAndParenthesizedTranslationStayAligned() {
        val lines = listOf(
            VoiceNoteLine(0, 1_500, "Welcome", "en-US", "환영합니다", "안내자"),
            VoiceNoteLine(1_500, 3_000, "こんにちは", "ja-JP", "안녕하세요"),
            VoiceNoteLine(3_000, 4_000, "你好", "zh-CN", "안녕"),
        )
        val text = voiceNoteTranscript("산책 메모", lines, false)
        assertTrue(text.startsWith("산책 메모\n\n"))
        assertTrue(text.contains("[en-US]\n[안내자] Welcome\n(환영합니다)"))
        assertTrue(text.contains("[ja-JP]\nこんにちは\n(안녕하세요)"))
        assertTrue(text.contains("[zh-CN]\n你好\n(안녕)"))
    }
    @Test fun untranslatedTextAndSameLanguageNeverInventParentheses() {
        assertEquals("Hello", voiceNoteLineText(VoiceNoteLine(0, 1, "Hello", null)))
        assertEquals("안녕하세요", voiceNoteLineText(VoiceNoteLine(0, 1, "안녕하세요", "ko", "안녕하세요")))
    }
    @Test fun srtUsesMediaTimesAndPreservesAllLanguages() {
        val text = voiceNoteTranscript("title", listOf(VoiceNoteLine(3_661_001, 3_662_080, "你好", "zh", "안녕")), true)
        assertEquals("1\n01:01:01,001 --> 01:01:02,080\n你好\n(안녕)\n\n", text)
    }
    @Test fun zeroLengthEstimatedCueHasPositiveDuration() {
        assertTrue(voiceNoteTranscript("", listOf(VoiceNoteLine(0, 0, "a", "en")), true).contains("00:00:00,000 --> 00:00:00,001"))
    }
    @Test fun negativeEstimatedTimesStillProduceAPlayableSrtCue() {
        val text = voiceNoteTranscript("", listOf(VoiceNoteLine(-1_000, -500, "Hello", "en")), true)
        assertEquals("1\n00:00:00,000 --> 00:00:00,001\nHello\n\n", text)
    }
    @Test fun extremeEstimatedTimeCannotOverflowTheEndOfAnSrtCue() {
        val text = voiceNoteTranscript("", listOf(VoiceNoteLine(Long.MAX_VALUE, 0, "Hello", "en")), true)
        assertTrue(text.contains(voiceNoteTime(Long.MAX_VALUE - 1, true) + " --> " + voiceNoteTime(Long.MAX_VALUE, true)))
    }
    @Test fun multilineRecognitionDoesNotSplitAnSrtCue() {
        val srt = voiceNoteTranscript("", listOf(VoiceNoteLine(0, 1000, "Hello\n\nworld", "en", "안녕\r\n\r\n세상")), true)
        assertTrue(srt.contains("Hello\nworld\n(안녕\n세상)"))
        assertEquals(1, srt.split("\n\n").count { it.isNotBlank() })
    }
    @Test fun wavContainsActualPcmAndFinalizedHeader() {
        val path = Files.createTempFile("voice-note", ".wav")
        try {
            val data = ByteArray(32_000) { (it % 127).toByte() }
            VoiceNoteWav(path.toFile()).use { it.append(data, data.size) }
            val bytes = Files.readAllBytes(path)
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals("RIFF", String(bytes.copyOfRange(0, 4), Charsets.US_ASCII))
            assertEquals(32_036, header.getInt(4))
            assertEquals(16_000, header.getInt(24))
            assertEquals(32_000, header.getInt(40))
            assertArrayEquals(data, bytes.copyOfRange(44, bytes.size))
        } finally { Files.deleteIfExists(path) }
    }
    @Test fun interruptedRecordingRecoversWithoutChangingCompleteSamples() {
        val path = Files.createTempFile("voice-note", ".wav")
        try {
            val data = ByteArray(32_001) { 9 }
            RandomAccessFile(path.toFile(), "rw").use { it.write(voiceNoteWavHeader(0)); it.write(data) }
            assertEquals(1_000L, recoverVoiceNoteWav(path.toFile()))
            val bytes = Files.readAllBytes(path)
            assertEquals(32_044, bytes.size)
            assertEquals(32_000, ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(40))
            assertArrayEquals(data.copyOf(32_000), bytes.copyOfRange(44, bytes.size))
        } finally { Files.deleteIfExists(path) }
    }
    @Test fun invalidPcmAndOversizedHeadersAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { voiceNoteWavHeader(1) }
        assertThrows(IllegalArgumentException::class.java) { voiceNoteWavHeader(VOICE_NOTE_MAX_BYTES + 2) }
        val path = Files.createTempFile("voice-note", ".wav")
        try { VoiceNoteWav(path.toFile()).use { wav -> assertThrows(IllegalArgumentException::class.java) { wav.append(byteArrayOf(1), 1) } } }
        finally { Files.deleteIfExists(path) }
    }
    @Test fun recoveryRejectsCorruptHeaderWithoutRewritingTheOriginal() {
        val path = Files.createTempFile("voice-note-corrupt", ".wav")
        try {
            val original = voiceNoteWavHeader(0) + ByteArray(321) { 7 }
            original[0] = 'X'.code.toByte()
            Files.write(path, original)
            assertThrows(IllegalArgumentException::class.java) { recoverVoiceNoteWav(path.toFile()) }
            assertArrayEquals(original, Files.readAllBytes(path))
        } finally { Files.deleteIfExists(path) }
    }
    @Test fun recoveryRejectsDifferentSampleFormatWithoutRewritingAudio() {
        val path = Files.createTempFile("voice-note-format", ".wav")
        try {
            val original = voiceNoteWavHeader(0) + ByteArray(320) { 5 }
            ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN).putInt(24, 48_000)
            Files.write(path, original)
            assertThrows(IllegalArgumentException::class.java) { recoverVoiceNoteWav(path.toFile()) }
            assertArrayEquals(original, Files.readAllBytes(path))
        } finally { Files.deleteIfExists(path) }
    }
}
