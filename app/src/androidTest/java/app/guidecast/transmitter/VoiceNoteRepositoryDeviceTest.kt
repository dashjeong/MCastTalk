package app.guidecast.transmitter

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class VoiceNoteRepositoryDeviceTest {
    @Test fun smallSavedExportRequestSurvivesParcelRoundTripAndRejectsChangedOrDeletedNotes() {
        val note = VoiceNote("test", "큰 합성 노트", 123, null, "ko-KR", interrupted = false,
            lines = List(1_000) { VoiceNoteLine(it * 1000L, (it + 1) * 1000L, "한".repeat(1000), null) })
        val request = VoiceNoteExportRequest(note, "json", VoiceNoteExportOptions(translations = false, search = "한"))
        val parcel = android.os.Parcel.obtain()
        try {
            parcel.writeBundle(request.savedRequest())
            assertTrue("Saved state must exclude the transcript", parcel.dataSize() < 4096)
            parcel.setDataPosition(0)
            val saved = requireNotNull(parcel.readBundle(javaClass.classLoader))
            assertEquals(request, restoreVoiceNoteExport(saved) { assertEquals(note.id, it); note })
            assertThrows(FileTranscriptionException::class.java) { restoreVoiceNoteExport(saved) { note.copy(title = "changed") } }
            assertThrows(FileTranscriptionException::class.java) { restoreVoiceNoteExport(saved) { throw java.io.FileNotFoundException() } }
        } finally { parcel.recycle() }
    }
    @Test fun librarySummariesSkipLargeTranscriptsAndRecoverAtomicBackupMetadata() {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "note-summary-${UUID.randomUUID()}")
        try {
            val repository = VoiceNoteRepository(root)
            val notes = List(4) { index -> repository.create("summary $index", null, "ko-KR").copy(
                durationMs = (index + 1) * 1000L, interrupted = false,
                lines = List(500) { VoiceNoteLine(it * 1000L, (it + 1) * 1000L, "字".repeat(1000), null) }).also(repository::save) }
            val first = File(root, "${notes.first().id}.json")
            assertTrue(first.renameTo(File(root, first.name + ".bak")))
            val summaries = repository.list()
            assertEquals(notes.map { it.id }.toSet(), summaries.map { it.id }.toSet())
            for (summary in summaries) {
                assertTrue(summary.lines.isEmpty())
                assertEquals(notes.single { it.id == summary.id }.durationMs, summary.durationMs)
            }
            assertEquals(500, repository.load(notes.first().id).lines.size)
        } finally { root.deleteRecursively() }
    }
    @Test fun structuredExportRoundTripsUnicodeAndDoesNotLeakExcludedFieldsOrLocalPaths() {
        val note = VoiceNote("synthetic", "회의 \"검토\"", 123, null, "ko-KR", interrupted = false,
            lines = listOf(VoiceNoteLine(0, 500, "Hello", "en", "비공개 번역", "비공개 이름"),
                VoiceNoteLine(1_000, 2_000, "검색 한글 日本語 中文 😀\n다음 줄", null, timingEstimated = false)))
        val output = java.io.StringWriter()
        writeVoiceNoteExport(output, note, "json", VoiceNoteExportOptions(translations = false, speakers = false, search = "검색"))
        val json = org.json.JSONObject(output.toString())
        assertEquals("mcasttalk.voice-note/1", json.getString("schema"))
        assertEquals(1, json.getInt("exportedSegments")); assertEquals(2, json.getInt("totalSegments"))
        val row = json.getJSONArray("segments").getJSONObject(0)
        assertEquals(2, row.getInt("sourceOrdinal")); assertEquals(1_000L, row.getLong("startMs"))
        assertEquals(note.lines[1].original, row.getString("original"))
        assertTrue(row.isNull("language")); assertFalse(row.getBoolean("timingEstimated"))
        assertFalse(row.has("speaker")); assertFalse(row.has("translation"))
        assertFalse(output.toString().contains("비공개")); assertFalse(output.toString().contains("/data/"))
    }
    @Test fun oversizedEditsAreRejectedWithActionableMessageAndKeepSavedContent() {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "note-size-${UUID.randomUUID()}")
        try {
            val repository = VoiceNoteRepository(root)
            val original = repository.create("기존 제목", "ko-KR", "en-US")
            for ((count, text) in listOf(70 to "x".repeat(65_536), 24 to "가".repeat(65_536))) {
                val error = assertThrows(FileTranscriptionException::class.java) {
                    repository.save(original.copy(title = "저장되면 안 되는 제목", lines = List(count) { VoiceNoteLine(0, 1, text, "ko-KR") }))
                }
                assertTrue(error.message.orEmpty().contains("4MB"))
                assertEquals(original, repository.load(original.id))
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun personalStorageDomainsAreExcludedFromBothBackupAndDeviceTransfer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(0, context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP)
        val expected = setOf("root", "file", "database", "sharedpref", "external", "device_root", "device_file", "device_database", "device_sharedpref")
        val domains = mutableMapOf<String, MutableSet<String>>()
        context.resources.getXml(R.xml.data_extraction_rules).use { xml ->
            var section = ""
            while (xml.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (xml.eventType != org.xmlpull.v1.XmlPullParser.START_TAG) continue
                when (xml.name) {
                    "cloud-backup", "device-transfer" -> { section = xml.name; domains[section] = mutableSetOf() }
                    "include" -> fail("Private content must never be automatically included")
                    "exclude" -> {
                        assertEquals(".", xml.getAttributeValue(null, "path"))
                        requireNotNull(domains[section]).add(xml.getAttributeValue(null, "domain"))
                    }
                }
            }
        }
        assertEquals(expected, domains["cloud-backup"])
        assertEquals(expected, domains["device-transfer"])
    }
    @Test fun noteSurvivesReopenAndDeleteRemovesOnlyItsAudio() {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "note-test-${UUID.randomUUID()}")
        try {
            val repository = VoiceNoteRepository(root)
            val note = repository.create("합성 시험", null, "ko-KR")
            val neighbor = repository.create("유지할 노트", "en-US", "ko-KR")
            VoiceNoteWav(repository.audio(note.id)).use { it.append(ByteArray(32_000) { 1 }, 32_000) }
            val recovered = repository.recover(note.id)
            assertEquals(1_000L, recovered.durationMs)
            assertFalse(recovered.interrupted)
            repository.save(recovered.copy(lines = listOf(VoiceNoteLine(0, 1_000, "Hello", "en-US", "안녕", "화자 1", edited = true, timingEstimated = false))))
            val reopened = VoiceNoteRepository(root)
            assertEquals("화자 1", reopened.load(note.id).lines.single().speaker)
            assertTrue(reopened.load(note.id).lines.single().edited)
            assertFalse(reopened.load(note.id).lines.single().timingEstimated)
            assertTrue(reopened.list().all { it.lines.isEmpty() })
            reopened.delete(note.id)
            assertFalse(reopened.audio(note.id).exists())
            assertEquals(neighbor.id, reopened.list().single().id)
            assertThrows(IllegalArgumentException::class.java) { reopened.audio("../outside") }
        } finally { root.deleteRecursively() }
    }
}
