package app.guidecast.transmitter

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.guidecast.core.translation.KoreanNumericQuantities
import app.guidecast.provider.mlkit.translation.MlKitTranslationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Real ML Kit text inference, not a new ASR/audio quality test. No cloud calls or phrase memory. */
@RunWith(AndroidJUnit4::class)
class MlKitSpokenQuantityDeviceTest {
    @Test fun actualLocalModelPreservesSpokenQuantitiesInReportedAndIndependentSentences() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = MlKitTranslationProvider(context, sourceLanguageTag = "ko", requireWifiForModels = false)
        val rows = JSONArray()
        val output = File(requireNotNull(context.getExternalFilesDir(null)), "synthetic-mlkit-spoken-quantities.json")
        val evidence = JSONObject().put("scope", "REAL_MLKIT_TEXT_ONLY_NOT_ASR_OR_OVERALL_MEANING")
            .put("rows", rows).put("state", "RUNNING")
        // First two are the unchanged observed transcripts. Others are independent synthetic
        // numeric controls. No expected translation is inserted into production input or output.
        val cases = listOf(
            Triple("reported-years-73", "한국전쟁의 총성이 멈춘 지 칠십 삼 년이 흘렀습니다", "73"),
            Triple("reported-years-56", "방송을 제가 한 지가 지금 오십육 년째 하고 있거든요 하루도 안 쉬고 지금도 하고 있어요.", "56"),
            Triple("independent-months", "계약 기간은 십팔 개월입니다.", "18"),
            Triple("independent-years", "이 건물은 이십칠 년 동안 사용했습니다.", "27"),
            Triple("independent-count", "참가자는 삼십오 명입니다.", "35"),
            Triple("independent-repetitions", "검사를 사십이 회 반복했습니다.", "42"),
        )
        try {
            withTimeout(180_000L) { provider.modelManager.prepare(setOf("en")) }
            val engine = provider.engineFor("en")
            val mismatches = mutableListOf<String>()
            for ((id, original, expectedNumber) in cases) {
                val row = JSONObject().put("id", id).put("originalTranscript", original)
                    .put("computedTranslationInput", KoreanNumericQuantities.normalizeForTranslation(original, "ko"))
                    .put("expectedNumber", expectedNumber)
                rows.put(row)
                val started = SystemClock.elapsedRealtime()
                try {
                    val actual = withTimeout(30_000L) { engine.translate(original, "ko", "en") }
                    val numbers = Regex("[0-9]+(?:[.,][0-9]+)*").findAll(actual).map { it.value }.toList()
                    row.put("actualTranslation", actual).put("observedDigitQuantities", JSONArray(numbers))
                    row.put("numericCheckPassed", numbers == listOf(expectedNumber))
                    if (numbers != listOf(expectedNumber)) mismatches += id
                } catch (failure: Exception) {
                    row.put("failureType", failure.javaClass.simpleName)
                    throw failure
                } finally {
                    row.put("elapsedMs", SystemClock.elapsedRealtime() - started)
                    output.writeText(evidence.toString(2))
                }
            }
            evidence.put("state", if (mismatches.isEmpty()) "NUMERIC_CHECKS_PASSED" else "NUMERIC_CHECKS_FAILED")
            output.writeText(evidence.toString(2))
            // Every case is recorded before a quality failure is reported; no retry until passing.
            assertEquals("Numeric information was lost or changed; see ${output.name}", emptyList<String>(), mismatches)
        } finally {
            output.writeText(evidence.toString(2))
            provider.close()
        }
    }
}
