package app.guidecast.transmitter

import java.io.StringWriter
import java.io.Writer
import org.junit.Assert.*
import org.junit.Test

class VoiceNoteExportTest {
    @Test fun restoredExportFingerprintDetectsContentAndMetadataChangesWithUnambiguousFields() {
        val baseline = voiceNoteExportRevision(note)
        assertEquals(baseline, voiceNoteExportRevision(note.copy()))
        for (changed in listOf(note.copy(title = "changed"), note.copy(targetLanguage = "en-US"),
            note.copy(lines = note.lines.map { it.copy(original = it.original + "!") }),
            note.copy(lines = note.lines.map { it.copy(translation = "changed") }),
            note.copy(lines = note.lines.map { it.copy(timingEstimated = false) }))) {
            assertNotEquals(baseline, voiceNoteExportRevision(changed))
        }
        assertNotEquals(voiceNoteExportRevision(note.copy(title = "ab", notice = "c")),
            voiceNoteExportRevision(note.copy(title = "a", notice = "bc")))
    }
    private val note = VoiceNote("test", "회의 <제목>", 123, null, "ko-KR", durationMs = 20_000, interrupted = false,
        lines = listOf(VoiceNoteLine(0, 1_000, "Hello", "en", "안녕", "안내자"),
            VoiceNoteLine(10_000, 11_000, "Search\n\nresult", null, speaker = "발표자")))
    private fun export(kind: String, options: VoiceNoteExportOptions = VoiceNoteExportOptions()) = StringWriter().also {
        writeVoiceNoteExport(it, note, kind, options)
    }.toString()

    @Test fun filteredSrtRenumbersCuesButRetainsOriginalAudioPosition() {
        assertEquals("1\n00:00:10,000 --> 00:00:11,000\n[발표자] Search\nresult\n\n",
            export("srt", VoiceNoteExportOptions(search = "search")))
    }
    @Test fun originalOnlyExportOmitsTranslationSpeakerAndOptionalTimeWithoutMutatingTheNote() {
        val text = export("txt", VoiceNoteExportOptions(translations = false, speakers = false, timestamps = false))
        assertFalse(text.contains("안녕")); assertFalse(text.contains("안내자")); assertFalse(text.contains("00:00"))
        assertTrue(text.contains("번역 대기 1개")); assertTrue(text.contains("언어 미확인"))
        assertEquals("안녕", note.lines.first().translation)
    }
    @Test fun windowsTextUsesUtf8BomAndOnlyCrLfWhileOtherFormatsNeverGetBom() {
        val text = export("txt", VoiceNoteExportOptions(windowsText = true))
        assertTrue(text.startsWith("\uFEFF")); assertFalse(text.replace("\r\n", "").contains('\n'))
        assertFalse(export("srt", VoiceNoteExportOptions(windowsText = true)).startsWith("\uFEFF"))
    }
    @Test fun markdownKeepsUntrustedHtmlAndLinksAsVisibleText() {
        val output = StringWriter()
        writeVoiceNoteExport(output, note.copy(lines = listOf(VoiceNoteLine(0, 1, "<script>\n![image](https://host)\n# title", null))), "md", VoiceNoteExportOptions())
        assertTrue(output.toString().contains("&lt;script&gt;"))
        assertTrue(output.toString().contains("\\!\\[image\\]\\(https://host\\)"))
        assertTrue(output.toString().contains("\\# title"))
    }
    @Test fun zeroSearchResultsFailBeforeWritingRatherThanCreatingAnApparentlyCompleteEmptyReport() {
        val output = StringWriter()
        assertThrows(IllegalArgumentException::class.java) { writeVoiceNoteExport(output, note, "txt", VoiceNoteExportOptions(search = "missing")) }
        assertEquals("", output.toString())
    }
    @Test fun cancellationAndDestinationFailureArePropagatedWithoutReportingSuccess() {
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            writeVoiceNoteExport(StringWriter(), note, "md", VoiceNoteExportOptions()) { throw java.util.concurrent.CancellationException() }
        }
        val broken = object : Writer() {
            override fun write(buffer: CharArray, offset: Int, length: Int) { throw java.io.IOException("disk full") }
            override fun flush() = Unit
            override fun close() = Unit
        }
        assertThrows(java.io.IOException::class.java) { writeVoiceNoteExport(broken, note, "txt", VoiceNoteExportOptions()) }
    }
    @Test fun maximumSegmentCountIsWrittenIncrementally() {
        var largestWrite = 0
        var total = 0
        val counting = object : Writer() {
            override fun write(buffer: CharArray, offset: Int, length: Int) { largestWrite = maxOf(largestWrite, length); total += length }
            override fun flush() = Unit
            override fun close() = Unit
        }
        writeVoiceNoteExport(counting, note.copy(lines = List(2_000) { VoiceNoteLine(it * 1000L, (it + 1) * 1000L, "a".repeat(1000), "en") }), "txt", VoiceNoteExportOptions())
        assertTrue(total > 2_000_000); assertTrue(largestWrite < 2_000)
    }
}
