package app.guidecast.transmitter

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Dedicated emulator, synthetic notes. No operator recordings or network access. */
class VoiceNoteTransferDeviceTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as GuideCastApplication
    private val root = File(app.cacheDir, "note-transfer-test-${java.util.UUID.randomUUID()}").apply { mkdirs() }
    private val source = VoiceNoteRepository(File(root, "source"))
    private val target = VoiceNoteRepository(File(root, "target"))
    @After fun cleanup() { root.deleteRecursively() }

    private fun fixture(repository: VoiceNoteRepository = source): VoiceNote {
        val note = repository.create("합성 이관 시험", "en-US", "ko-KR")
        VoiceNoteWav(repository.audio(note.id)).use { wav -> wav.append(ByteArray(32_000) { (it % 31).toByte() }, 32_000) }
        return note.copy(durationMs = 1_000, interrupted = false, lines = listOf(
            VoiceNoteLine(0, 500, "Confirmed source", "en-US", "확정 번역", "화자 하나", true, false),
            VoiceNoteLine(500, 1_000, "Pending translation", "en", "", "화자 둘", false, true),
        )).also(repository::save)
    }
    private fun archive(rows: List<JSONObject>, audio: List<Pair<String, ByteArray>>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            val records = rows.joinToString("") { "$it\n" }.toByteArray()
            val hash = MessageDigest.getInstance("SHA-256").digest(records).joinToString("") { "%02x".format(it) }
            zip.putNextEntry(ZipEntry("records.jsonl")); zip.write(records); zip.closeEntry()
            audio.forEach { (path, bytes) -> zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry() }
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(DataTransferFormat.manifest(DataTransferKind.SCRIPTS, rows.size.toLong(), hash).toString().toByteArray())
            zip.closeEntry()
        }
    }.toByteArray()
    private fun backup(): Pair<List<JSONObject>, List<Pair<String, ByteArray>>> {
        val rows = mutableListOf<JSONObject>()
        val audio = VoiceNoteTransfer.export(source, { rows += it }) {}.map { (file, _) -> "voice-notes/${file.name}" to file.readBytes() }
        return rows to audio
    }
    private fun stage() = DataTransferStage(File.createTempFile("stage-", ".db", root))
    private fun DataTransferStage.read(bytes: ByteArray) = readZip(ByteArrayInputStream(bytes), DataTransferKind.SCRIPTS, EmptyCoroutineContext) {}
    private fun rejects(block: () -> Unit) { var rejected = false; try { block() } catch (_: Exception) { rejected = true }; assertTrue(rejected) }

    @Test fun notesAudioEditsAndSpeakersRoundTripAndCurrentCorrectionsWin() {
        val note = fixture(); val (rows, audio) = backup()
        stage().use { staged ->
            staged.read(archive(rows, audio))
            assertEquals(1L, target.importMissing(staged.voiceNotes) {})
            assertEquals(note, target.load(note.id))
            assertArrayEquals(source.audio(note.id).readBytes(), target.audio(note.id).readBytes())
            val edited = note.copy(title = "현재 제목", lines = note.lines.map { it.copy(original = "현재 확정 수정") })
            target.save(edited)
            assertEquals(0L, target.importMissing(staged.voiceNotes) {})
            assertEquals(edited, target.load(note.id))
        }
    }

    @Test fun tamperedMissingExtraAndTraversalAudioAreRejectedBeforeAnyMerge() {
        fixture(); val (rows, audio) = backup()
        val changed = audio.single().second.copyOf().apply { this[lastIndex] = (this[lastIndex] + 1).toByte() }
        val cases = listOf(emptyList(), listOf(audio.single().first to changed),
            audio + ("voice-notes/${java.util.UUID.randomUUID()}.wav" to audio.single().second),
            listOf("voice-notes/../../escape.wav" to audio.single().second))
        cases.forEach { files -> stage().use { staged -> rejects { staged.read(archive(rows, files)) }; assertTrue(staged.records(VoiceNoteTransfer.NOTE).none()) } }
        assertTrue(target.portableIds().isEmpty())
        assertFalse(File(root.parentFile, "escape.wav").exists())
    }

    @Test fun invalidNoteBoundsOrMissingSegmentFailBeforeMerge() {
        fixture(); val (rows, audio) = backup()
        val invalidTitle = rows.map { JSONObject(it.toString()) }.also { it.first().put("title", "a".repeat(121)) }
        val invalidTime = rows.map { JSONObject(it.toString()) }.also { it[1].put("end", -1) }
        val gap = rows.filterIndexed { index, _ -> index != 1 }
        listOf(invalidTitle, invalidTime, gap).forEach { bad -> stage().use { staged -> rejects { staged.read(archive(bad, audio)) } } }
        assertTrue(target.portableIds().isEmpty())
    }

    @Test fun interruptedNoteWithoutAudioRemainsRecoverableMetadata() {
        val note = source.create("권한 거부 후 기록", "ko-KR", "en-US")
        val (rows, audio) = backup()
        stage().use { staged ->
            staged.read(archive(rows, audio)); assertEquals(1L, target.importMissing(staged.voiceNotes) {})
            assertEquals(note, target.load(note.id)); assertFalse(target.audio(note.id).exists())
        }
    }

    @Test fun cancellationDuringAudioCopyLeavesNoInstalledPartialNote() {
        val note = fixture(); val (rows, audio) = backup()
        stage().use { staged ->
            staged.read(archive(rows, audio))
            var checks = 0
            rejects { target.importMissing(staged.voiceNotes) { if (++checks == 3) throw CancellationException("synthetic cancellation") } }
            assertTrue(target.portableIds().isEmpty()); assertFalse(target.audio(note.id).exists())
            assertEquals(1L, target.importMissing(staged.voiceNotes) {})
        }
    }

    @Test fun interruptedPreviousImportCannotOverwriteDifferentOrphanAudio() {
        val note = fixture(); val (rows, audio) = backup()
        val original = byteArrayOf(1, 2, 3)
        target.audio(note.id).writeBytes(original)
        stage().use { staged ->
            staged.read(archive(rows, audio)); rejects { target.validateImport(staged.voiceNotes) }
            assertArrayEquals(original, target.audio(note.id).readBytes()); assertTrue(target.portableIds().isEmpty())
        }
    }

    @Test fun legacyScriptBackupWithoutNotesStillImports() {
        stage().use { staged -> staged.read(archive(emptyList(), emptyList())); assertEquals(0L, target.importMissing(staged.voiceNotes) {}) }
    }

    @Test fun settingsScriptBackupIncludesNotesAndActualRepositoryRestoresThem() = runBlocking {
        val repository = VoiceNoteRepository(File(app.filesDir, "voice-notes"))
        val note = fixture(repository)
        try {
            DataTransferRepository(app).prepareBackup(DataTransferKind.SCRIPTS).use { backup ->
                stage().use { staged -> backup.file.inputStream().use { staged.readZip(it, DataTransferKind.SCRIPTS, EmptyCoroutineContext) {} }
                    assertEquals(note, staged.voiceNotes.load(note.id)) }
                repository.delete(note.id)
                DataTransferRepository(app).import(DataTransferKind.SCRIPTS, Uri.fromFile(backup.file))
                assertEquals(note, repository.load(note.id))
                assertEquals(32_044L, repository.audio(note.id).length())
            }
        } finally { repository.delete(note.id) }
    }

    @Test fun comparisonPreferencesRoundTripWithoutGrantingCloudConsent() = runBlocking {
        val settings = app.developerLabSettings
        val previous = settings.state.value
        try {
            settings.importOptions(previous.copy(secondaryModelId = "synthetic-model", comparisonSituation = "박물관 합성 안내"))
            val backup = PortableSettings.capture(app)
            settings.importOptions(previous.copy(secondaryModelId = "other-model", comparisonSituation = ""))
            PortableSettings.apply(app, backup)
            assertEquals("synthetic-model", settings.state.value.secondaryModelId)
            assertEquals("박물관 합성 안내", settings.state.value.comparisonSituation)
            assertFalse(settings.state.value.cloudReviewEnabled)
            assertFalse(settings.state.value.comparisonEnabled)
            assertFalse(settings.state.value.autoLearnEnabled)
            assertFalse(backup.getJSONObject("lab").has("apiKey"))
        } finally { settings.importOptions(previous) }
    }
}
