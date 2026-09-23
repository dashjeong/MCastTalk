package app.guidecast.transmitter

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TeacherLearningDeviceTest {
    private fun report() = TeacherLearningReport("a".repeat(64), "ko", "en", TranslationRegister.AUTO,
        "11시에 만나요", "We meet at 12.", "We meet at 11.", setOf(TeacherLearningSignal.NUMBERS), setOf(TeacherLesson.NUMBERS),
        TeacherReviewOutcome.PROPOSED, CloudReviewProvider.OPENAI, "synthetic-model")

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
