package app.guidecast.transmitter

import androidx.test.platform.app.InstrumentationRegistry
import app.guidecast.core.translation.ProviderTranscriptSemanticAssembler
import app.guidecast.core.translation.RecognizedUtterance
import app.guidecast.provider.mlkit.translation.MlKitTranslationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Authored corpus through real semantic staging and local ML Kit, not a human adequacy score. */
class SemanticTranslationCorpusDeviceTest {
    private data class Case(val head: String, val tail: String, val meanings: List<Regex>)

    @Test fun incompleteMeaningsReachRealTranslatorTogetherAndRetainCriticalFacts(): Unit = runBlocking {
        val cases = listOf(
            Case("이곳에 들어가면 안", "됩니다", listOf(
                Regex("not|n't|prohibit|forbid"), Regex("enter|entrance|go|access"))),
            Case("비가 오면", "실내에서 기다려 주세요", listOf(
                Regex("if|when"), Regex("rain"), Regex("wait"), Regex("indoors|inside"))),
            Case("입장 요금은 15", "달러입니다", listOf(Regex("15"), Regex("dollar|\\$"))),
            Case("출발 시각은 오후 3", "시입니다", listOf(Regex("3|three"), Regex("p\\.?m|afternoon"))),
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = MlKitTranslationProvider(context, "ko", requireWifiForModels = false)
        val results = JSONArray()
        val failures = mutableListOf<String>()
        try {
            withTimeout(180_000) { provider.modelManager.prepare(setOf("en")) }
            val translator = provider.engineFor("en")
            repeat(2) { round ->
                cases.forEachIndexed { index, case ->
                    val assembler = ProviderTranscriptSemanticAssembler()
                    fun line(id: Long, text: String, ms: Long) = RecognizedUtterance(
                        id, text, "ko", true, ms * 1_000_000, ms * 1_000_000,
                    )
                    assembler.observeSpeechActivity(true, 0)
                    val events = assembler.accept(line(0, case.head, 100)).toMutableList()
                    for (ms in 200L..6_000L step 200) {
                        assembler.observeSpeechActivity(false, ms * 1_000_000)
                        events += assembler.tick(ms * 1_000_000)
                    }
                    assertTrue("Corpus $index: incomplete meaning published during hesitation",
                        events.none { it.isFinal })
                    assembler.observeSpeechActivity(true, 6_100_000_000)
                    events += assembler.accept(line(1, case.tail, 6_200))
                    for (ms in 6_400L..8_400L step 200) {
                        assembler.observeSpeechActivity(false, ms * 1_000_000)
                        events += assembler.tick(ms * 1_000_000)
                    }
                    val final = events.filter { it.isFinal }.single()
                    assertEquals("${case.head} ${case.tail}", final.text)
                    val translated = withTimeout(30_000) {
                        translator.translate(final.text, "ko", "en")
                    }.lowercase(Locale.ROOT)
                    // Only authored synthetic corpus is included in assertion output, never user audio.
                    val missing = case.meanings.filterNot { it.containsMatchIn(translated) }
                    results.put(JSONObject().put("round", round).put("case", index)
                        .put("source", final.text).put("translation", translated)
                        .put("engine", "ML_KIT_LOCAL").put("referenceKind", "authored-regression-corpus")
                        .put("missingCriticalAnchors", JSONArray(missing.map { it.pattern })))
                    missing.forEach { meaning ->
                        failures += "Round $round corpus $index lost $meaning: $translated"
                    }
                    assertTrue(assembler.finish(9_000_000_000).none { it.isFinal })
                }
            }
            assertTrue(failures.joinToString("\n"), failures.isEmpty())
        } finally {
            provider.close()
            val output = File(context.getExternalFilesDir(null), "benchmark/semantic-translation-corpus.json")
            output.parentFile?.mkdirs()
            output.writeText(JSONObject().put("schemaVersion", 1).put("results", results)
                .put("qualityGatePassed", failures.isEmpty() && results.length() == 8).toString(2))
        }
    }
}
