package app.guidecast.transmitter

import app.guidecast.core.translation.LanguageModelStatus
import app.guidecast.core.translation.ModelReadiness
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TranslationLanguageSelectionPolicyTest {
    private val options = listOf("en", "ja", "zh", "nl", "es", "ar", "vi", "de").map { tag ->
        TranslationLanguageOption(tag, tag)
    }

    @Test
    fun `selection keeps operator order through five independent output channels`() {
        var selected: Set<String> = linkedSetOf()

        listOf("ja", "en", "ar", "nl", "zh").forEach { tag ->
            selected = toggleTranslationLanguageSelection(
                current = selected,
                languageTag = tag,
                options = options,
            ).selectedLanguageTags
        }

        assertEquals(listOf("ja", "en", "ar", "nl", "zh"), selected.toList())
    }

    @Test
    fun `eighth language is rejected without replacing or reordering seven active channels`() {
        val current = linkedSetOf("ja", "en", "ar", "nl", "zh", "es", "vi")

        val result = toggleTranslationLanguageSelection(
            current = current,
            languageTag = "de",
            options = options,
        )

        assertEquals(current.toList(), result.selectedLanguageTags.toList())
        assertTrue(result.message.orEmpty().contains("최대 7개"))
    }

    @Test
    fun `selected language can be removed at the seven channel limit`() {
        val result = toggleTranslationLanguageSelection(
            current = linkedSetOf("ja", "en", "ar", "nl", "zh", "es", "vi"),
            languageTag = "ar",
            options = options,
        )

        assertEquals(listOf("ja", "en", "nl", "zh", "es", "vi"), result.selectedLanguageTags.toList())
        assertNull(result.message)
    }

    @Test
    fun `selection accepts seven translated channels in operator order`() {
        var selected: Set<String> = linkedSetOf()
        val requested = listOf("ja", "en", "ar", "nl", "zh", "es", "vi")

        requested.forEach { tag ->
            selected = toggleTranslationLanguageSelection(
                current = selected,
                languageTag = tag,
                options = options,
            ).selectedLanguageTags
        }

        assertEquals(requested, selected.toList())
    }

    @Test
    fun `quick selection is capped at five even when the catalog grows`() {
        assertEquals(
            listOf("en", "ja", "zh", "nl", "es"),
            recommendedTranslationLanguageSelection(options).toList(),
        )
    }

    @Test
    fun `official source catalog excludes Dutch and includes six Gemma languages`() {
        assertEquals(
            listOf("ko", "en", "ja", "zh", "es", "ar"),
            SOURCE_LANGUAGE_OPTIONS.map { normalizeSourceLanguage(it.languageTag) },
        )
        assertFalse(SOURCE_LANGUAGE_OPTIONS.any { normalizeSourceLanguage(it.languageTag) == "nl" })
    }

    @Test
    fun `default recommendation selects primary 5 languages in order`() {
        val recommended = recommendedTranslationLanguageSelection()
        assertEquals(listOf("en", "ja", "zh", "zh-TW", "vi"), recommended.toList())
    }

    @Test
    fun `selected source is excluded from output channels and Korean becomes an English target`() {
        assertFalse(translationTargetLanguageOptions("ko-KR").any { it.languageTag == "ko" })
        assertTrue(translationTargetLanguageOptions("en-US").any { it.languageTag == "ko" })
        assertFalse(translationTargetLanguageOptions("en-US").any { it.languageTag == "en" })

        val recommended = recommendedTranslationLanguageSelection(
            sourceLanguageTag = "en-US",
        )
        assertEquals(listOf("ko", "ja", "zh", "zh-TW", "vi"), recommended.toList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `identity output cannot be selected`() {
        toggleTranslationLanguageSelection(
            current = emptySet(),
            languageTag = "ja",
            sourceLanguageTag = "ja-JP",
        )
    }

    @Test
    fun `operator channel presentation follows selection rather than catalog order`() {
        val state = TranslationModelUiState(
            options = options,
            selectedLanguageTags = linkedSetOf("ar", "ja", "en"),
        )

        assertEquals(
            listOf("ar", "ja", "en"),
            selectedTranslationLanguageOptions(state).map { it.languageTag },
        )
    }

    @Test
    fun `model preparation failure is isolated and later languages still prepare`() = runBlocking {
        val attempted = mutableListOf<String>()

        val report = prepareTranslationLanguagesIndependently(
            languageTags = linkedSetOf("en", "ja", "zh", "nl", "es"),
        ) { languageTag ->
            attempted += languageTag
            if (languageTag == "ja") error("download failed")
        }

        assertEquals(setOf("en", "ja", "zh", "nl", "es"), attempted.toSet())
        assertEquals(linkedSetOf("en", "zh", "nl", "es"), report.readyLanguageTags)
        assertEquals(mapOf("ja" to "download failed"), report.failures)
        assertTrue(translationPreparationSummary(report).contains("4/5 준비"))
        assertTrue(translationPreparationSummary(report).contains("ja: download failed"))
    }

    @Test
    fun `model preparation cancellation cancels the whole operator request`() = runBlocking {
        val cancellation = CancellationException("operator stopped")
        val attempted = mutableListOf<String>()

        try {
            prepareTranslationLanguagesIndependently(listOf("en", "ja", "zh")) { languageTag ->
                attempted += languageTag
                if (languageTag == "ja") throw cancellation
            }
            fail("Expected cancellation")
        } catch (error: CancellationException) {
            assertEquals(cancellation.message, error.message)
        }
        assertTrue(attempted.contains("ja"))
    }

    @Test
    fun `hung model preparation times out without blocking four sibling languages`() = runBlocking {
        val completed = mutableSetOf<String>()

        val report = prepareTranslationLanguagesIndependently(
            languageTags = linkedSetOf("en", "ja", "zh", "nl", "es"),
            timeoutMillis = 100L,
        ) { languageTag ->
            if (languageTag == "en") awaitCancellation()
            completed += languageTag
        }

        assertEquals(setOf("ja", "zh", "nl", "es"), completed)
        assertEquals(linkedSetOf("ja", "zh", "nl", "es"), report.readyLanguageTags)
        assertTrue(report.failures.getValue("en").contains("시간 초과"))
    }

    @Test
    fun `batch preparation preserves per language ready and failure evidence`() = runBlocking {
        val selected = linkedSetOf("en", "ja", "zh")
        val report = prepareTranslationLanguageBatch(
            languageTags = selected,
            prepare = { throw IllegalStateException("one or more models failed") },
            statuses = {
                listOf(
                    LanguageModelStatus("en", ModelReadiness.READY),
                    LanguageModelStatus(
                        languageTag = "ja",
                        readiness = ModelReadiness.FAILED,
                        errorMessage = "download failed",
                    ),
                    LanguageModelStatus("zh", ModelReadiness.READY),
                )
            },
        )

        assertEquals(linkedSetOf("en", "zh"), report.readyLanguageTags)
        assertEquals(mapOf("ja" to "download failed"), report.failures)
    }

    @Test
    fun `zh and zh-TW can be selected simultaneously as distinct channels`() {
        var selected = setOf<String>()
        selected = toggleTranslationLanguageSelection(selected, "zh").selectedLanguageTags
        selected = toggleTranslationLanguageSelection(selected, "zh-TW").selectedLanguageTags

        assertEquals(listOf("zh", "zh-TW"), selected.toList())
    }

    @Test
    fun `zh-TW channel ID maps to lowercase zh-tw conforming to AudioChannel ID pattern`() {
        val languageTag = "zh-TW"
        val channelId = languageTag.lowercase(java.util.Locale.ROOT)
        assertEquals("zh-tw", channelId)
        assertTrue(Regex("[a-z0-9][a-z0-9_-]{0,23}").matches(channelId))
    }

    @Test
    fun `translation model card enabled condition respects translationTestActive and broadcastActive`() {
        fun isCardEnabled(broadcastActive: Boolean, translationTestActive: Boolean): Boolean =
            !broadcastActive && !translationTestActive

        assertTrue(isCardEnabled(broadcastActive = false, translationTestActive = false))
        assertFalse(isCardEnabled(broadcastActive = false, translationTestActive = true))
        assertTrue(isCardEnabled(broadcastActive = false, translationTestActive = false))
        assertFalse(isCardEnabled(broadcastActive = true, translationTestActive = false))
    }
}
