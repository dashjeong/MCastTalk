package app.guidecast.transmitter

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import app.guidecast.core.translation.TranslationStyle
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real SQLite checks on disposable databases; never reads or changes operator corpora. */
class DomainCorpusRepositoryDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun withRepository(block: suspend (DomainCorpusRepository, String) -> Unit) = runBlocking {
        val name = "domain-regression-${System.nanoTime()}.db"
        val repository = DomainCorpusRepository(context, name)
        try { block(repository, name) } finally {
            repository.close()
            context.deleteDatabase(name)
        }
    }

    private suspend fun import(
        repository: DomainCorpusRepository,
        text: String,
        target: String = "en-US",
        style: TranslationStyle = TranslationStyle.FORMAL,
    ): DomainCorpusProfile {
        val result = repository.importTxt(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)),
            "회의 시험", "사용자가 확인한 표현", "ko-KR", target, style)
        assertTrue(result.toString(), result is DomainImportResult.Success)
        return (result as DomainImportResult.Success).profile
    }

    @Test fun importExportActivationPersistenceAndScriptIsolation() = withRepository { repository, name ->
        val text = "회의를 시작합니다.\tLet us begin the meeting.\n예산을 검토합니다.\tWe will review the budget.\n"
        val profile = import(repository, text)
        assertEquals("ko", profile.sourceLanguageTag)
        assertEquals("en", profile.targetLanguageTag)
        assertNull(repository.match("회의를 시작합니다.", "ko-KR", "en", TranslationStyle.FORMAL).exactTranslation)
        repository.activate(profile.id)
        assertEquals("Let us begin the meeting.", repository.match("  회의를 시작합니다. ", "ko-KR", "en-GB", TranslationStyle.FORMAL).exactTranslation)
        assertNull(repository.match("회의를 시작합니다.", "ko", "en", TranslationStyle.CONVERSATIONAL).exactTranslation)
        val output = ByteArrayOutputStream()
        repository.exportTxt(profile.id, output)
        assertEquals(text, output.toString("UTF-8"))
        val reopened = DomainCorpusRepository(context, name)
        try {
            assertEquals("Let us begin the meeting.", reopened.match("회의를 시작합니다.", "ko", "en", TranslationStyle.FORMAL).exactTranslation)
        } finally { reopened.close() }
        val chinese = import(repository, "회의를 시작합니다.\t现在开始开会。\n", "zh-CN")
        repository.activate(chinese.id)
        assertEquals("现在开始开会。", repository.match("회의를 시작합니다.", "ko-KR", "zh-Hans", TranslationStyle.FORMAL).exactTranslation)
        assertNull(repository.match("회의를 시작합니다.", "ko", "zh-TW", TranslationStyle.FORMAL).exactTranslation)
        repository.deactivate(profile.id)
        assertNull(repository.match("회의를 시작합니다.", "ko", "en", TranslationStyle.FORMAL).exactTranslation)
        repository.remove(chinese.id)
        assertEquals(1, repository.profiles().size)
        val missingOutput = ByteArrayOutputStream()
        try { repository.exportTxt(chinese.id, missingOutput); fail("Missing profile must not export successfully") }
        catch (_: IllegalArgumentException) { assertEquals(0, missingOutput.size()) }
    }

    @Test fun malformedImportIsAtomicAndConcurrentActivationInvalidatesCache() = withRepository { repository, _ ->
        val first = import(repository, "회의를 시작합니다.\tFirst wording.\n")
        val second = import(repository, "회의를 시작합니다.\tSecond wording.\n")
        repository.activate(first.id)
        repository.match("회의를 시작합니다.", "ko", "en", TranslationStyle.FORMAL)
        val revision = repository.revision.value
        val failure = repository.importTxt(ByteArrayInputStream("정상\tValid\n잘못된 행\n".toByteArray()),
            "오류 시험", "", "ko", "en", TranslationStyle.FORMAL)
        assertTrue(failure is DomainImportResult.Failure)
        assertEquals(revision, repository.revision.value)
        assertEquals(2, repository.profiles().size)
        coroutineScope {
            val readers = (1..20).map { async { repository.match("회의를 시작합니다.", "ko", "en", TranslationStyle.FORMAL) } }
            repository.activate(second.id)
            readers.awaitAll().forEach { assertTrue(it.exactTranslation in listOf("First wording.", "Second wording.")) }
        }
        repeat(20) { assertEquals("Second wording.", repository.match("회의를 시작합니다.", "ko", "en", TranslationStyle.FORMAL).exactTranslation) }
        assertEquals(second.id, repository.profiles().single { it.active }.id)
    }

    @Test fun tenThousandPairsRealDatabaseColdWarmAndHintBudget() = withRepository { repository, _ ->
        val text = buildString { repeat(10_000) { append("회의 예산 항목 $it\tMeeting budget item $it\n") } }
        val profile = import(repository, text)
        assertEquals(10_000, profile.pairCount)
        repository.activate(profile.id)
        val before = SystemClock.elapsedRealtimeNanos()
        assertEquals("Meeting budget item 9999", repository.match("회의 예산 항목 9999", "ko-KR", "en", TranslationStyle.FORMAL).exactTranslation)
        val coldMs = (SystemClock.elapsedRealtimeNanos() - before) / 1_000_000.0
        val warm = (1..30).map {
            val start = SystemClock.elapsedRealtimeNanos()
            val result = repository.match("회의 예산 항목을 재검토합니다.", "ko", "en", TranslationStyle.FORMAL)
            assertNull(result.exactTranslation)
            assertTrue(result.hints.length <= DomainCorpusFormat.MAX_HINTS_LENGTH)
            assertTrue(JSONObject(result.hints).getJSONArray("examples").length() in 1..3)
            (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
        }.sorted()
        val report = JSONObject().put("deviceModel", android.os.Build.MODEL).put("api", android.os.Build.VERSION.SDK_INT)
            .put("pairs", 10_000).put("coldExactMs", coldMs).put("warmHintsP95Ms", warm[28]).put("samples", warm.size)
            .put("syntheticFixture", true).put("modelInferenceMeasured", false)
        File(context.getExternalFilesDir(null), "benchmark").apply { mkdirs() }
            .resolve("domain-sqlite-${System.currentTimeMillis()}.json").writeText(report.toString(2))
    }

    @Test fun stalledDocumentExportDoesNotBlockLiveLookupOrOperatorControl() = withRepository { repository, _ ->
        val text = "회의를 시작합니다.\tLet us begin.\n"
        val profile = import(repository, text)
        repository.activate(profile.id)
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val bytes = ByteArrayOutputStream()
        val slow = object : OutputStream() {
            override fun write(value: Int) { error("Expected a complete UTF-8 row") }
            override fun write(value: ByteArray, offset: Int, length: Int) {
                entered.complete(Unit)
                check(release.await(10, TimeUnit.SECONDS)) { "Test export was not released" }
                bytes.write(value, offset, length)
            }
        }
        coroutineScope {
            val export = async(Dispatchers.IO) { repository.exportTxt(profile.id, slow) }
            try {
                withTimeout(3_000) { entered.await() }
                withTimeout(3_000) {
                    assertEquals("Let us begin.", repository.match("회의를 시작합니다.", "ko", "en", TranslationStyle.FORMAL).exactTranslation)
                    repository.deactivate(profile.id)
                    repository.activate(profile.id)
                    repository.remove(profile.id)
                    assertTrue(repository.profiles().isEmpty())
                }
            } finally { release.countDown() }
            export.await()
        }
        assertEquals("Export must retain its consistent pre-deletion snapshot", text, bytes.toString("UTF-8"))
    }
}
