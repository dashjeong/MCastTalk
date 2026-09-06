package app.guidecast.transmitter

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class SpeechCorrectionRepositoryDeviceTest {
    private lateinit var context: Context
    private val databaseName = "speech-corrections-device-test.db"

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(databaseName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun scopedCrudProfileSelectionDisableDeleteRestoreClearAndReopen(): Unit = runBlocking {
        var now = 100L
        val repository = SpeechCorrectionRepository(context, databaseName) { now++ }
        repository.refresh()
        repository.selectProfile("강의실")
        val saved = repository.save(
            draft(
                recognizedText = "가이드 케스트",
                correctedText = "가이드캐스트",
                hint = "가이드캐스트",
                sourceKey = "session-1:final-7",
            ),
        )
        repository.save(
            draft(
                languageTag = "en-US",
                recognizedText = "guide cast",
                correctedText = "GuideCast",
                hint = "GuideCast",
            ),
        )
        repository.save(draft(profile = "야외", recognizedText = "다른 프로필", correctedText = "다른 프로필"))

        assertEquals(listOf("가이드캐스트"), repository.recognitionHints("KO-kr"))
        assertEquals(saved, repository.correctionCandidates("가이드 케스트", "ko-KR").single())
        assertTrue(repository.correctionCandidates("가이드", "ko-KR").isEmpty())
        assertTrue(repository.correctionCandidates("다른 프로필", "ko-KR").isEmpty())
        assertEquals(1, repository.list("강의실", "ko-kr", "케스트").size)
        assertEquals(1, repository.list("강의실", "ko", "케스트").size)
        assertEquals(listOf("강의실", "기본", "야외"), repository.profiles())

        assertFalse(checkNotNull(repository.setEnabled(saved.id, false)).enabled)
        assertTrue(repository.recognitionHints("ko-KR").isEmpty())
        assertTrue(checkNotNull(repository.setEnabled(saved.id, true)).enabled)
        val deleted = checkNotNull(repository.delete(saved.id))
        assertTrue(repository.recognitionHints("ko-KR").isEmpty())
        assertNull(repository.delete(saved.id))
        assertEquals(saved.id, repository.restore(deleted).id)
        assertEquals(listOf("가이드캐스트"), repository.recognitionHints("ko-KR"))

        val cleared = repository.clear("강의실")
        assertEquals(2, cleared.size)
        assertTrue(repository.list("강의실").isEmpty())
        cleared.forEach { repository.restore(it) }
        assertEquals(2, repository.list("강의실").size)

        val reopened = SpeechCorrectionRepository(context, databaseName)
        reopened.refresh()
        assertEquals("강의실", reopened.activeProfile.value)
        assertEquals(listOf("가이드캐스트"), reopened.recognitionHints("ko-KR"))
        assertEquals("가이드 케스트", reopened.list("강의실", "ko-KR").single().recognizedText)

        assertValidation(SpeechCorrectionValidationCode.ORIGINAL_TEXT_IMMUTABLE) {
            runBlocking { reopened.save(deleted.toDraftForTest().copy(recognizedText = "다른 원문"), deleted.id) }
        }
    }

    @Test
    fun profileReplacementIsAtomicAndLeavesOtherProfilesUntouched(): Unit = runBlocking {
        val repository = SpeechCorrectionRepository(context, databaseName) { 500L }
        repository.refresh()
        repository.save(draft(recognizedText = "기존", correctedText = "기존 교정"))
        repository.save(draft(profile = "야외", recognizedText = "보존", correctedText = "보존 교정"))

        val tooManyHints = List(33) { index ->
            draft(recognizedText = "가져오기 $index", correctedText = "교정 $index", hint = "힌트 $index")
        }
        assertValidation(SpeechCorrectionValidationCode.MAX_HINTS) {
            runBlocking { repository.replaceProfile("강의실", tooManyHints) }
        }
        assertEquals("기존 교정", repository.list("강의실").single().correctedText)

        assertValidation(SpeechCorrectionValidationCode.DUPLICATE_ENTRY) {
            runBlocking { repository.save(draft(recognizedText = "기존", correctedText = "중복")) }
        }
        assertEquals("기존 교정", repository.list("강의실").single().correctedText)

        val replacements = listOf(
            draft(recognizedText = "새 항목 1", correctedText = "새 교정 1", hint = "새 힌트"),
            draft(recognizedText = "새 항목 2", correctedText = "새 교정 2", enabled = false),
        )
        val replaced = repository.replaceProfile("강의실", replacements)
        assertEquals(2, replaced.size)
        assertEquals(setOf("새 항목 1", "새 항목 2"), repository.list("강의실").map { it.recognizedText }.toSet())
        assertEquals("보존 교정", repository.list("야외").single().correctedText)

        val conflicting = repository.delete(replaced.first().id)!!
        repository.restore(conflicting)
        assertValidation(SpeechCorrectionValidationCode.DUPLICATE_ENTRY) {
            runBlocking { repository.restore(conflicting) }
        }
        assertEquals(conflicting, repository.list("강의실").first { it.id == conflicting.id })
    }

    @Test
    fun databaseIsAppPrivateAndSchemaContainsNoAudioOrDiagnosticTextColumns(): Unit = runBlocking {
        val repository = SpeechCorrectionRepository(context, databaseName)
        repository.refresh()
        val privateText = "민감한 교정 문장"
        repository.save(draft(recognizedText = privateText, correctedText = "교정 결과"))

        val databaseFile = context.getDatabasePath(databaseName)
        assertTrue(databaseFile.canonicalPath.startsWith(java.io.File(context.applicationInfo.dataDir).canonicalPath + "/"))
        val columns = SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { database ->
            database.rawQuery("PRAGMA table_info(corrections)", null).use { cursor ->
                buildList {
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                }
            }
        }
        assertFalse(columns.any { it.contains("audio", ignoreCase = true) || it.contains("pcm", ignoreCase = true) })
        assertFalse(columns.any { it.contains("diagnostic", ignoreCase = true) || it.contains("log", ignoreCase = true) })

        val failure = try {
            SpeechCorrectionValidation.draft(draft(recognizedText = "$privateText\n", correctedText = "교정 결과"))
            fail("Control character was accepted")
            error("unreachable")
        } catch (error: SpeechCorrectionValidationException) {
            error
        }
        assertFalse(failure.message.orEmpty().contains(privateText))
    }

    private fun draft(
        profile: String = "강의실",
        languageTag: String = "ko-KR",
        recognizedText: String,
        correctedText: String,
        hint: String? = null,
        enabled: Boolean = true,
        sourceKey: String? = null,
    ) = SpeechCorrectionDraft(
        profile = profile,
        languageTag = languageTag,
        recognizedText = recognizedText,
        correctedText = correctedText,
        hint = hint,
        enabled = enabled,
        sourceKey = sourceKey,
    )

    private fun assertValidation(code: SpeechCorrectionValidationCode, block: () -> Unit) {
        try {
            block()
            fail("Expected SpeechCorrectionValidationException")
        } catch (error: SpeechCorrectionValidationException) {
            assertEquals(code, error.code)
        }
    }

    private fun SpeechCorrectionEntry.toDraftForTest() = SpeechCorrectionDraft(
        profile, languageTag, recognizedText, correctedText, hint, enabled, sourceKey,
    )
}
