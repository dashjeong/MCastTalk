package app.guidecast.transmitter

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OperatorOptionsDeviceTest {
    @Test fun portableOptionsRoundTripPreservesPriorityOrderAndStandaloneSelection() {
        val source = DEFAULT_SOURCE_LANGUAGE_TAG
        val targets = translationTargetLanguageOptions(source).map { it.languageTag }.take(3).reversed()
        val expected = OperatorOptions(source, targets, translationEnabled = false, useGemma = false,
            runMode = BroadcastRunMode.STANDALONE)
        assertEquals(expected, OperatorOptions.fromJson(JSONObject(expected.toJson().toString())))
    }

    @Test fun oldSettingsAndUnknownFutureOptionsUseCompatibleDefaults() {
        assertEquals(OperatorOptions(), OperatorOptions.fromJson(JSONObject()))
        val row = JSONObject().put("source", "future-source").put("runMode", "FUTURE_MODE")
            .put("targets", JSONArray(listOf("future-target", "en", "en")))
            .put("gemma", false).put("refinement", true).put("futureField", true)
        val parsed = OperatorOptions.fromJson(row)
        assertEquals(DEFAULT_SOURCE_LANGUAGE_TAG, parsed.sourceLanguageTag)
        assertEquals(listOf("en"), parsed.targetLanguageTags)
        assertFalse(parsed.selectiveRefinement)
        assertEquals(BroadcastRunMode.NETWORK, parsed.runMode)
        assertFalse(parsed.toJson().has("futureField"))
    }

    @Test fun malformedOversizedLanguageListIsRejectedBeforeRestore() {
        try {
            OperatorOptions.fromJson(JSONObject().put("targets", JSONArray(List(101) { "en" })))
            fail("Oversized preference input must not be restored")
        } catch (_: IllegalArgumentException) { }
    }
}
