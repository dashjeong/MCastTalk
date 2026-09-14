package app.guidecast.transmitter

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Synthetic app-private fixtures only. Never reads or exports the operator's actual history. */
class DataTransferDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val names = mutableSetOf<String>()

    @After fun cleanup() {
        names.forEach { name ->
            context.deleteDatabase(name)
            File(context.noBackupFilesDir, "transcript-backups/$name").deleteRecursively()
        }
    }

    @Test fun versionOneAndUnknownFieldsRemainPortableWithoutRestoringSecrets() {
        val settings = JSONObject().put("type", "settings").put("developerInfo", true)
            .put("futureSetting", JSONObject().put("enabled", true)).put("apiKey", "synthetic-not-a-key")
            .put("lab", JSONObject().put("provider", "GOOGLE").put("cloudReviewEnabled", true).put("hasApiKey", true))
        val bytes = archive(DataTransferKind.SETTINGS, sequenceOf(settings)) { manifest ->
            manifest.remove("major"); manifest.put("version", 1); manifest.put("futureMetadata", "ignored")
        }
        stage().use { stage ->
            stage.readZip(ByteArrayInputStream(bytes), DataTransferKind.SETTINGS, EmptyCoroutineContext) { }
            assertEquals(1L, stage.count)
            assertTrue(stage.records("settings").single().getBoolean("developerInfo"))
            // Secret/consent fields are deliberately absent from the import whitelist; validation
            // tolerates unknown fields but does not apply them to a preference store.
            PortableSettings.validate(stage.records("settings").single())
            val safe = PortableSettings.sanitized(stage.records("settings").single())
            assertFalse(safe.has("apiKey"))
            assertFalse(safe.has("futureSetting"))
            assertFalse(safe.getJSONObject("lab").has("cloudReviewEnabled"))
            assertFalse(safe.getJSONObject("lab").has("hasApiKey"))
        }
    }

    @Test fun pathTraversalUnknownMajorAndTamperedDigestAreRejectedBeforeMerge() {
        val row = JSONObject().put("type", "settings").put("developerInfo", false)
        val traversal = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("../escape.json")); zip.write("{}".toByteArray()); zip.closeEntry()
        } }.toByteArray()
        listOf(traversal,
            archive(DataTransferKind.SETTINGS, sequenceOf(row)) { it.put("major", 99) },
            archive(DataTransferKind.SETTINGS, sequenceOf(row)) { it.put("sha256", "0".repeat(64)) },
        ).forEach { bytes -> stage().use { staged ->
            expectFailure { staged.readZip(ByteArrayInputStream(bytes), DataTransferKind.SETTINGS, EmptyCoroutineContext) { } }
            assertTrue(staged.records("settings").none())
        } }
        assertFalse(File(context.cacheDir.parentFile, "escape.json").exists())
    }

    @Test fun oversizedAndDeeplyNestedRecordsAreRejectedWithoutUnboundedParsing() {
        expectFailure { DataTransferFormat.parse("{".repeat(17) + "}".repeat(17)) }
        expectFailure { DataTransferFormat.parse("{\"x\":\"" + "a".repeat(DataTransferFormat.MAX_RECORD_CHARS) + "\"}") }
    }

    @Test fun missingSegmentAndMismatchedSessionFailCompleteValidation() {
        val missing = sequenceOf(fileRow("a"), translationRow("a", 0, "hello"))
        val mismatch = sequenceOf(
            JSONObject().put("type", "session").put("id", 100).put("started", 10).put("language", "ko-KR"),
            broadcastRow(100, 0, 20),
        )
        listOf(missing, mismatch).forEach { rows -> stage().use { staged ->
            expectFailure { staged.readZip(ByteArrayInputStream(archive(DataTransferKind.SCRIPTS, rows)), DataTransferKind.SCRIPTS, EmptyCoroutineContext) { } }
            assertTrue(staged.records("file").none())
            assertTrue(staged.records("broadcast").none())
        } }
    }

    @Test fun duplicateSegmentIdsAndAggregateTextOrWordBudgetFailBeforeImport() {
        val duplicate = segmentRow("a", 1).apply { getJSONObject("value").put("id", 0) }
        stage().use { staged ->
            expectFailure { staged.readZip(ByteArrayInputStream(archive(DataTransferKind.SCRIPTS,
                sequenceOf(fileRow("a"), segmentRow("a", 0), duplicate))), DataTransferKind.SCRIPTS, EmptyCoroutineContext) { } }
            assertTrue(staged.records("file").none())
        }
        val normalRows = listOf(fileRow("a"), segmentRow("a", 0), segmentRow("a", 1),
            translationRow("a", 0, "translated zero"), translationRow("a", 1, "translated one"))
        listOf(10L to 100L, 10_000L to 1L).forEach { (chars, words) ->
            DataTransferStage(File.createTempFile("portable-budget-", ".db", context.cacheDir), chars, words).use { staged ->
                expectFailure { staged.readZip(ByteArrayInputStream(archive(DataTransferKind.SCRIPTS, normalRows.asSequence())),
                    DataTransferKind.SCRIPTS, EmptyCoroutineContext) { } }
                assertTrue(staged.records("file").none())
            }
        }
    }

    @Test fun sourceAndTranslationLanguageBudgetsMatchImportAndPersistence() {
        val languages = listOf("en", "ja", "zh", "es", "fr", "de", "vi", "ar", "it")
        stage().use { staged ->
            val rows = sequence {
                yield(fileRow("a")); yield(segmentRow("a", 0))
                languages.forEach { yield(translationRow("a", 0, "text").put("language", it)) }
            }
            expectFailure { staged.readZip(ByteArrayInputStream(archive(DataTransferKind.SCRIPTS, rows)), DataTransferKind.SCRIPTS, EmptyCoroutineContext) { } }
            assertTrue(staged.records("file").none())
        }
        expectFailure { DataTransferFormat.validate(broadcastRow(1, 0, 0).put("source", "a".repeat(32_001)), DataTransferKind.SCRIPTS) }
        val segment = FileSpeechSegment(0, 0, 1_000, "synthetic", "ko-KR")
        expectFailure { FileTranscriptBudget.validate(listOf(segment, segment), emptyMap()) }
        expectFailure { FileTranscriptBudget.validate(listOf(segment), languages.associateWith { listOf("text") }) }
    }

    @Test fun explicitNullLanguageAndEmptySessionRemainRestorable() = runBlocking {
        val file = fileRow("a").put("language", JSONObject.NULL)
        val segment = segmentRow("a", 0).apply { getJSONObject("value").put("language", JSONObject.NULL) }
        stage().use { staged ->
            staged.readZip(ByteArrayInputStream(archive(DataTransferKind.SCRIPTS, sequenceOf(file, segment))), DataTransferKind.SCRIPTS, EmptyCoroutineContext) { }
            FileTranscriptLibrary(context, database("portable-null.db")).use { library ->
                library.importPortable(staged::records)
                assertNull(library.load("a".repeat(64))?.sourceLanguageTag)
                assertNull(library.load("a".repeat(64))?.segments?.single()?.languageTag)
            }
        }
        BroadcastTranscriptArchive(context, database("portable-empty-session.db")).use { archive ->
            archive.importPortableSessions(listOf(ArchivedBroadcastSession(4, 100, "ko-KR", 0)))
            assertEquals(4L, archive.snapshot.value.sessions.single().sessionId)
            val id = archive.beginSession("ko-KR")
            archive.enqueue(id, TranslationTranscriptLine(1, "synthetic pending", 0, true))
            assertTrue(archive.exportPortablePage(null).any { it.key.sessionId == id })
        }
    }

    @Test fun fileScriptOverTwoMegabytesRoundTripsWithWordTimingAndRelinkRequirement() {
        val count = 2_000
        val phrase = "합성 보관 검증 ".repeat(80)
        assertTrue(phrase.toByteArray(Charsets.UTF_8).size.toLong() * count > 2L * 1024 * 1024)
        val rows = sequence {
            yield(fileRow("a"))
            repeat(count) { index -> yield(segmentRow("a", index)); yield(translationRow("a", index, "$index $phrase")) }
        }
        val bytes = archive(DataTransferKind.SCRIPTS, rows)
        stage().use { staged ->
            staged.readZip(ByteArrayInputStream(bytes), DataTransferKind.SCRIPTS, EmptyCoroutineContext) { }
            val name = database("portable-large.db")
            FileTranscriptLibrary(context, name).use { library ->
                assertEquals(1, library.importPortable(staged::records))
                assertEquals(0, library.importPortable(staged::records))
                val loaded = requireNotNull(library.load("a".repeat(64)))
                assertTrue(loaded.requiresRelink)
                assertEquals("content://synthetic/a", loaded.uri)
                assertEquals(count, loaded.segments.size)
                assertEquals("${count - 1} $phrase", loaded.translations["en"]?.last())
                assertNull(loaded.segments.first().words.single().endMs)
                expectFailure { library.relink(loaded.id, "b".repeat(64), "content://synthetic/wrong", "wrong.wav") }
                assertTrue(requireNotNull(library.load(loaded.id)).requiresRelink)
                library.relink(loaded.id, loaded.id, "content://synthetic/relinked", "relinked.wav")
                assertFalse(requireNotNull(library.load(loaded.id)).requiresRelink)
            }
        }
    }

    @Test fun fileCollisionPreservesCurrentConfirmedWordingAndImportTransactionRollsBackFailure() {
        val name = database("portable-preserve.db")
        FileTranscriptLibrary(context, name).use { library ->
            val existing = FileLibraryEntry("a".repeat(64), "existing.wav", "content://synthetic/existing", "a".repeat(64), 1_000,
                "ko-KR", 1, listOf(FileSpeechSegment(0, 0, 1_000, "현재 확정 문장", "ko-KR")))
            library.save(existing)
            stage().use { staged ->
                staged.readZip(ByteArrayInputStream(archive(DataTransferKind.SCRIPTS,
                    sequenceOf(fileRow("a"), segmentRow("a", 0)))), DataTransferKind.SCRIPTS, EmptyCoroutineContext) { }
                assertEquals(0, library.importPortable(staged::records))
                assertEquals(existing, library.load(existing.id))
            }
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
                db.execSQL("CREATE TRIGGER reject_portable BEFORE INSERT ON segments WHEN NEW.file_id='${"b".repeat(64)}' BEGIN SELECT RAISE(ABORT,'synthetic'); END")
            }
            stage().use { staged ->
                staged.readZip(ByteArrayInputStream(archive(DataTransferKind.SCRIPTS,
                    sequenceOf(fileRow("b"), segmentRow("b", 0)))), DataTransferKind.SCRIPTS, EmptyCoroutineContext) { }
                expectFailure { library.importPortable(staged::records) }
                assertNull(library.load("b".repeat(64)))
                assertEquals(existing, library.load(existing.id))
            }
        }
    }

    @Test fun versionOneFileDatabaseMigratesWithoutChangingExistingMetadata() {
        val name = database("portable-v1.db")
        val path = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL("CREATE TABLE files(id TEXT PRIMARY KEY,name TEXT NOT NULL,uri TEXT NOT NULL,duration INTEGER NOT NULL,language TEXT,created INTEGER NOT NULL,notes TEXT NOT NULL)")
            db.execSQL("CREATE TABLE segments(file_id TEXT NOT NULL,ordinal INTEGER NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(file_id,ordinal))")
            db.execSQL("CREATE TABLE translations(file_id TEXT NOT NULL,language TEXT NOT NULL,ordinal INTEGER NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(file_id,language,ordinal))")
            db.execSQL("INSERT INTO files VALUES(?,?,?,10,'ko-KR',11,'[]')", arrayOf("a".repeat(64), "legacy.wav", "content://synthetic/legacy"))
            db.version = 1
        }
        FileTranscriptLibrary(context, name).use { library ->
            val metadata = library.list().single()
            assertEquals("legacy.wav", metadata.displayName)
            assertFalse(metadata.requiresRelink)
        }
    }

    @Test fun libraryCapacityRejectsOnlyNewFilesAndChecksImportBeforeAnyInsert() {
        val name = database("portable-capacity.db")
        val entry = FileLibraryEntry("a".repeat(64), "existing.wav", "content://synthetic/a", "a".repeat(64), 1_000,
            "ko-KR", 1, listOf(FileSpeechSegment(0, 0, 1_000, "synthetic", "ko-KR")))
        FileTranscriptLibrary(context, name, maximumFiles = 1).use { library ->
            library.save(entry)
            val edited = entry.copy(segments = listOf(entry.segments.single().copy(text = "confirmed")))
            library.save(edited)
            expectFailure { library.save(entry.copy(id = "b".repeat(64), sha256 = "b".repeat(64))) }
            stage().use { staged ->
                staged.readZip(ByteArrayInputStream(archive(DataTransferKind.SCRIPTS,
                    sequenceOf(fileRow("b"), segmentRow("b", 0)))), DataTransferKind.SCRIPTS, EmptyCoroutineContext) { }
                expectFailure { library.validatePortableCapacity(staged.records("file")) }
                expectFailure { library.importPortable(staged::records) }
            }
            assertEquals(edited, library.load(entry.id))
            assertNull(library.load("b".repeat(64)))
        }
    }

    @Test fun boundedListKeepsDirectIdLookupAndSqlPreflightRejectsOversizedLegacyBody() {
        val name = database("portable-bounded-read.db")
        fun entry(char: String, created: Long) = FileLibraryEntry(char.repeat(64), "$char.wav", "content://synthetic/$char", char.repeat(64), 1_000,
            "ko-KR", created, listOf(FileSpeechSegment(0, 0, 1_000, "synthetic ".repeat(100), "ko-KR")))
        FileTranscriptLibrary(context, name).use { it.save(entry("a", 1)); it.save(entry("b", 2)) }
        FileTranscriptLibrary(context, name, maximumFiles = 1).use { library ->
            assertEquals(1, library.list().size)
            assertEquals("b".repeat(64), library.list().single().id)
            assertNotNull(library.load("a".repeat(64)))
        }
        FileTranscriptLibrary(context, name, maximumEncodedChars = 256).use { limited ->
            expectFailure { limited.load("a".repeat(64)) }
            assertEquals(2, limited.list().size)
        }
    }

    @Test fun metadataBudgetRejectsUnexpectedlyLongNotesWithoutChangingStoredFiles() {
        val invalid = fileRow("a").put("notes", JSONArray().put("x".repeat(FileTranscriptBudget.MAX_NOTE_CHARS + 1)))
        stage().use { staged ->
            expectFailure { staged.readZip(ByteArrayInputStream(archive(DataTransferKind.SCRIPTS,
                sequenceOf(invalid, segmentRow("a", 0)))), DataTransferKind.SCRIPTS, EmptyCoroutineContext) { } }
            assertTrue(staged.records("file").none())
        }
    }

    @Test fun archiveImportPreservesAllRowsOverActiveCapAndStrictExportRejectsMissingShard() = runBlocking {
        val name = database("portable-archive.db")
        BroadcastTranscriptArchive(context, name, maximumStoredLines = 2).use { archive ->
            val rows = (0..4).map { DataTransferFormat.broadcast(broadcastRow(100, it.toLong(), 1_000)) }
            assertEquals(5, archive.importPortableLines(rows))
            assertEquals(0, archive.importPortableLines(rows))
            assertEquals(5, archive.loadPage(TranscriptArchiveFilter()).totalMatchingLines)
            assertTrue(archive.loadPage(TranscriptArchiveFilter()).lines.all { it.isDailyBackup })
            assertEquals(5, archive.exportPortablePage(null).size)
            // Add a healthy second day; corruption must not hide this unrelated record.
            archive.importPortableLines(listOf(DataTransferFormat.broadcast(broadcastRow(200, 0, 172_800_000))))
            val directory = File(context.noBackupFilesDir, "transcript-backups/$name")
            val first = requireNotNull(directory.listFiles()?.sortedBy { it.name }?.firstOrNull())
            first.delete()
            val page = archive.loadPage(TranscriptArchiveFilter())
            assertEquals(6, page.totalMatchingLines)
            assertEquals(1, page.lines.size)
            assertNotNull(archive.snapshot.value.warning)
            var failed = false
            try { archive.exportPortablePage(null) } catch (_: Exception) { failed = true }
            assertTrue(failed)
        }
    }

    private fun stage() = DataTransferStage(File.createTempFile("portable-test-", ".db", context.cacheDir))
    private fun database(name: String) = name.also { names += it; context.deleteDatabase(it) }
    private fun fileRow(char: String) = JSONObject().put("type", "file").put("id", char.repeat(64)).put("name", "synthetic-$char.wav")
        .put("uri", "content://synthetic/$char").put("duration", 10_000_000).put("language", "ko-KR").put("created", 100).put("notes", JSONArray())
    private fun segmentRow(char: String, ordinal: Int) = JSONObject().put("type", "segment").put("file", char.repeat(64)).put("ordinal", ordinal)
        .put("value", JSONObject().put("id", ordinal).put("start", ordinal * 1_000L).put("end", (ordinal + 1) * 1_000L)
            .put("text", "합성 $ordinal").put("language", "ko-KR").put("estimated", true)
            .put("words", JSONArray().put(JSONObject().put("text", "합성").put("start", ordinal * 1_000L))))
    private fun translationRow(char: String, ordinal: Int, text: String) = JSONObject().put("type", "translation").put("file", char.repeat(64))
        .put("ordinal", ordinal).put("language", "en").put("text", text)
    private fun broadcastRow(session: Long, sequence: Long, started: Long) = JSONObject().put("type", "broadcast")
        .put("session", session).put("sequence", sequence).put("started", started).put("language", "ko-KR")
        .put("source", "합성 원문 $sequence").put("translations", JSONObject().put("en", "synthetic $sequence"))

    private fun archive(kind: DataTransferKind, rows: Sequence<JSONObject>, changeManifest: (JSONObject) -> Unit = {}): ByteArray {
        val out = ByteArrayOutputStream()
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("records.jsonl"))
            rows.forEach { row -> val bytes = (row.toString() + "\n").toByteArray(Charsets.UTF_8); digest.update(bytes); zip.write(bytes); count++ }
            zip.closeEntry()
            val manifest = DataTransferFormat.manifest(kind, count, digest.digest().joinToString("") { "%02x".format(it) }).also(changeManifest)
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest.toString().toByteArray(Charsets.UTF_8)); zip.closeEntry()
        }
        return out.toByteArray()
    }
    private inline fun expectFailure(block: () -> Unit) {
        var failed = false
        try { block() } catch (_: Exception) { failed = true }
        assertTrue("malformed or conflicting operation must fail", failed)
    }
}
