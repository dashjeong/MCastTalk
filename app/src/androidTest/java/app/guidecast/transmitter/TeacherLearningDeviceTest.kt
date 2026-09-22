package app.guidecast.transmitter

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TeacherLearningDeviceTest {
    @Test fun versionTwoDatabaseMigratesWithoutLosingHumanMemoryOrReviewHistory(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "teacher-v2-${System.nanoTime()}.db"
        val old = report()
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("CREATE TABLE phrases(id INTEGER PRIMARY KEY AUTOINCREMENT,source_language TEXT NOT NULL," +
                "target_language TEXT NOT NULL,register_name TEXT NOT NULL,normalized_original TEXT NOT NULL," +
                "original TEXT NOT NULL,corrected TEXT NOT NULL,origin TEXT NOT NULL,updated_at INTEGER NOT NULL," +
                "UNIQUE(source_language,target_language,register_name,normalized_original))")
            db.execSQL("CREATE TABLE teacher_reports(fingerprint TEXT PRIMARY KEY,created_at INTEGER NOT NULL,report_json TEXT NOT NULL)")
            db.execSQL("INSERT INTO phrases VALUES(1,'ko','en','AUTO',?,?,?,'USER',100)",
                arrayOf(old.original, old.original, "We meet at 11."))
            db.execSQL("INSERT INTO teacher_reports VALUES(?,?,?)", arrayOf<Any>(old.key, old.createdAtEpochMillis, old.toJson().toString()))
            db.version = 2
        }
        SentenceTranslationMemory(context, name).use { memory ->
            assertEquals("We meet at 11.", memory.lookup("ko", "en", TranslationRegister.AUTO, old.original)?.corrected)
            assertEquals(old, memory.teacherReports().single())
            assertNull(memory.comparativeLesson(comparisonReport().comparativeKey()))
            assertTrue(memory.recordTeacherReport(comparisonReport()) { true })
            assertEquals(2, memory.teacherReports().size)
        }
        context.deleteDatabase(name)
    }
    private fun comparisonReport(key: Char = 'c', previous: TeacherLearningReport? = null) = report().copy(
        key = key.toString().repeat(64), comparison = ComparativeTeacherEvidence("일정을 확인하는 중", "관람 안내",
            CloudReviewProvider.GOOGLE, "synthetic-gemini", "We meet at 11.", "We meet at 11.", "We meet at 11.",
            true, comparativeVersionHash(previous)))

    @Test fun comparisonApprovalIsContextScopedStaleSafeAndRestoresPreviousVersion(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "comparison-version-${System.nanoTime()}.db"
        SentenceTranslationMemory(context, name).use { memory ->
            val first = comparisonReport()
            memory.recordTeacherReport(first) { true }
            assertNull(memory.comparativeLesson(first.comparativeKey()))
            assertTrue(memory.decideTeacherLearning(first, true))
            val approved = requireNotNull(memory.comparativeLesson(first.comparativeKey()))
            assertNull(memory.lookup("ko", "en", TranslationRegister.AUTO, first.original))
            assertNull(memory.comparativeLesson(comparativeLessonKey("ko", "en", TranslationRegister.AUTO, first.original, "다른 문맥", "관람 안내")))
            val stale = comparisonReport('d')
            memory.recordTeacherReport(stale) { true }
            assertFalse(memory.decideTeacherLearning(stale, true))
            val refreshed = comparisonReport('e', approved)
            memory.recordTeacherReport(refreshed) { true }
            assertTrue(memory.decideTeacherLearning(refreshed, true))
            val newest = requireNotNull(memory.comparativeLesson(first.comparativeKey()))
            assertFalse(memory.undoTeacherLearning(approved))
            assertTrue(memory.undoTeacherLearning(newest))
            assertEquals(approved, memory.comparativeLesson(first.comparativeKey()))
        }
        context.deleteDatabase(name)
    }

    @Test fun importedComparisonEvidenceCannotAuthorizeApplicationAndLaterHumanEditsWin(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "comparison-import-${System.nanoTime()}.db"
        SentenceTranslationMemory(context, name).use { memory ->
            val candidate = comparisonReport()
            assertTrue(memory.importTeacherReport(candidate.copy(outcome = TeacherReviewOutcome.APPROVED)))
            val imported = memory.teacherReports().single()
            assertEquals(TeacherReviewOutcome.IMPORTED, imported.outcome)
            assertFalse(memory.decideTeacherLearning(imported, true))
            assertNull(memory.comparativeLesson(candidate.comparativeKey()))
            val local = comparisonReport('f')
            memory.recordTeacherReport(local) { true }
            memory.upsert(SentenceMemoryEntry(sourceLanguageTag = "ko", targetLanguageTag = "en",
                translationRegister = TranslationRegister.AUTO, original = local.original,
                corrected = "We meet at 11 o'clock.", origin = SentenceMemoryOrigin.USER))
            assertFalse(memory.decideTeacherLearning(local, true))
        }
        context.deleteDatabase(name)
    }
    private fun report() = TeacherLearningReport("a".repeat(64), "ko", "en", TranslationRegister.AUTO,
        "11시에 만나요", "We meet at 12.", "We meet at 11.", setOf(TeacherLearningSignal.NUMBERS), setOf(TeacherLesson.NUMBERS),
        TeacherReviewOutcome.PROPOSED, CloudReviewProvider.OPENAI, "synthetic-model")

    @Test fun restoredCorrectionCanBeDeactivatedWithoutTouchingANewerVersionOrHumanMemory(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "comparison-deactivate-${System.nanoTime()}.db"
        SentenceTranslationMemory(context, name).use { memory ->
            suspend fun approve(key: Char, previous: TeacherLearningReport?): TeacherLearningReport {
                val candidate = comparisonReport(key, previous)
                memory.recordTeacherReport(candidate) { true }
                assertTrue(memory.decideTeacherLearning(candidate, true))
                return requireNotNull(memory.comparativeLesson(candidate.comparativeKey()))
            }
            val first = approve('c', null)
            val second = approve('d', first)
            val third = approve('e', second)
            assertFalse(memory.deactivateComparativeLesson(second))
            assertTrue(memory.undoTeacherLearning(third))
            assertEquals(second, memory.comparativeLesson(second.comparativeKey()))
            assertFalse(memory.undoTeacherLearning(second)) // No invented older rollback snapshot.
            val human = SentenceMemoryEntry(sourceLanguageTag = "ko", targetLanguageTag = "en", translationRegister = TranslationRegister.AUTO,
                original = first.original, corrected = "Human confirmation.", origin = SentenceMemoryOrigin.USER)
            memory.upsert(human)
            assertTrue(memory.deactivateComparativeLesson(second))
            assertNull(memory.comparativeLesson(second.comparativeKey()))
            assertEquals(human.corrected, memory.lookup("ko", "en", TranslationRegister.AUTO, first.original)?.corrected)
            memory.clearTeacherReports()
            assertTrue(memory.teacherReports().isEmpty())
        }
        context.deleteDatabase(name)
    }

    @Test fun proposedCorrectionIsInactiveUntilApprovalAndCanBeHeldApprovedAndUndone(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "teacher-regression-${System.nanoTime()}.db"
        SentenceTranslationMemory(context, name).use { memory ->
            val proposed = report()
            assertTrue(memory.recordTeacherReport(proposed) { true })
            assertNull(memory.lookup("ko", "en", TranslationRegister.AUTO, proposed.original))
            assertTrue(memory.decideTeacherLearning(proposed, false))
            val held = memory.teacherReports().single()
            assertEquals(TeacherReviewOutcome.HELD, held.outcome)
            assertTrue(memory.decideTeacherLearning(held, true))
            assertEquals(proposed.after, memory.lookup("ko", "en", TranslationRegister.AUTO, proposed.original)?.corrected)
            val approved = memory.teacherReports().single()
            assertEquals(TeacherReviewOutcome.APPROVED, approved.outcome)
            assertFalse(memory.decideTeacherLearning(held, true))
            assertTrue(memory.undoTeacherLearning(approved))
            assertNull(memory.lookup("ko", "en", TranslationRegister.AUTO, proposed.original))
            assertEquals(TeacherReviewOutcome.UNDONE, memory.teacherReports().single().outcome)
            assertFalse(memory.undoTeacherLearning(approved))
        }
        context.deleteDatabase(name)
    }

    @Test fun approvalAndRollbackPreserveLaterHumanEdits(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "teacher-confirmed-${System.nanoTime()}.db"
        SentenceTranslationMemory(context, name).use { memory ->
            val proposed = report()
            memory.recordTeacherReport(proposed) { true }; assertTrue(memory.decideTeacherLearning(proposed, true))
            val approved = memory.teacherReports().single()
            val human = requireNotNull(memory.lookup("ko", "en", TranslationRegister.AUTO, proposed.original))
            memory.upsert(human.copy(corrected = "We will meet at 11."))
            assertFalse(memory.undoTeacherLearning(approved))
            assertEquals("We will meet at 11.", memory.lookup("ko", "en", TranslationRegister.AUTO, proposed.original)?.corrected)
            val another = proposed.copy(key = "b".repeat(64))
            memory.recordTeacherReport(another) { true }
            assertFalse(memory.decideTeacherLearning(another, true))
        }
        context.deleteDatabase(name)
    }

    @Test fun reportMigrationRoundTripIsEvidenceOnlyAndPreservesBeforeAfter(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "teacher-import-${System.nanoTime()}.db"
        val original = report()
        val row = original.toJson().put("type", "teacherReport")
        assertEquals(original.key, DataTransferFormat.validate(row, DataTransferKind.DICTIONARY).key)
        SentenceTranslationMemory(context, name).use { memory ->
            assertTrue(memory.importTeacherReport(TeacherLearningReport.fromJson(row)))
            assertFalse(memory.importTeacherReport(original))
            assertNull(memory.lookup("ko", "en", TranslationRegister.AUTO, original.original))
            val export = JSONObject(teacherReportExport(memory.teacherReports()))
            val report = export.getJSONArray("reports").getJSONObject(0)
            assertEquals(original.before, report.getString("before")); assertEquals(original.after, report.getString("after"))
            assertEquals(false, report.getJSONArray("checks").getJSONObject(0).getBoolean("before"))
            assertEquals(true, report.getJSONArray("checks").getJSONObject(0).getBoolean("after"))
        }
        context.deleteDatabase(name)
    }

    @Test fun automaticStyleReusesAnExistingHumanConfirmedFormalSentence(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "teacher-register-${System.nanoTime()}.db"
        SentenceTranslationMemory(context, name).use { memory ->
            memory.upsert(SentenceMemoryEntry(sourceLanguageTag = "ko", targetLanguageTag = "en", translationRegister = TranslationRegister.FORMAL,
                original = "함께 갑니다", corrected = "We go together.", origin = SentenceMemoryOrigin.USER))
            assertEquals("We go together.", memory.lookup("ko", "en", TranslationRegister.AUTO, "함께 갑니다")?.corrected)
            val pending = report().copy(original = "함께 갑니다", before = "Go together.", after = "Let's go together.", lessons = setOf(TeacherLesson.NUMBERS))
            memory.recordTeacherReport(pending) { true }
            assertFalse(memory.decideTeacherLearning(pending, true))
        }
        context.deleteDatabase(name)
    }

    @Test fun importedApprovalCannotEraseAnExistingConfirmedSentence(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "teacher-forged-${System.nanoTime()}.db"
        SentenceTranslationMemory(context, name).use { memory ->
            val proposed = report()
            memory.upsert(SentenceMemoryEntry(sourceLanguageTag = "ko", targetLanguageTag = "en", translationRegister = TranslationRegister.AUTO,
                original = proposed.original, corrected = requireNotNull(proposed.after), origin = SentenceMemoryOrigin.USER))
            val human = requireNotNull(memory.lookup("ko", "en", TranslationRegister.AUTO, proposed.original))
            val forged = proposed.copy(outcome = TeacherReviewOutcome.APPROVED, appliedAtEpochMillis = human.updatedAtEpochMillis)
            assertTrue(memory.importTeacherReport(forged))
            val imported = memory.teacherReports().single()
            assertEquals(TeacherReviewOutcome.IMPORTED, imported.outcome)
            assertNull(imported.appliedAtEpochMillis)
            assertFalse(memory.undoTeacherLearning(imported))
            assertFalse(memory.decideTeacherLearning(imported, true))
            assertEquals(proposed.after, memory.lookup("ko", "en", TranslationRegister.AUTO, proposed.original)?.corrected)
        }
        context.deleteDatabase(name)
    }

    @Test fun apiCredentialsAreEncryptedScopedAndNeverReauthorizedByImport() {
        val original = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "api-test-${System.nanoTime()}-"
        val context = object : ContextWrapper(original) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = original.getSharedPreferences(prefix + name, mode)
        }
        val settings = TranslationApiSettings(context)
        val first = TranslationApiOptions(provider = TranslationApiProvider.COMPATIBLE, baseUrl = "https://first.example/v1")
        assertTrue(settings.configure(first)); assertTrue(settings.saveKey("synthetic-device-key-not-real")); settings.setAllowOnline(true)
        assertTrue(settings.state.value.allowOnline)
        assertFalse(context.getSharedPreferences("translation_api_credentials", 0).all.values.any { it.toString().contains("synthetic-device-key-not-real") })
        settings.configure(first.copy(baseUrl = "https://second.example/v1")); assertFalse(settings.state.value.hasKey)
        settings.configure(first); assertTrue(settings.state.value.hasKey); assertFalse(settings.state.value.allowOnline)
        val safe = settings.state.value.portable().put("allowOnline", true).put("hasKey", true).put("apiKey", "injected")
        settings.configure(TranslationApiOptions.fromPortable(safe)); assertFalse(settings.state.value.allowOnline)
        assertFalse(settings.state.value.portable().has("apiKey"))
        original.deleteSharedPreferences(prefix + "translation_api"); original.deleteSharedPreferences(prefix + "translation_api_credentials")
    }
}
