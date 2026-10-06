package app.guidecast.transmitter

import app.guidecast.core.translation.TranslationStyle
import org.junit.Assert.*
import org.junit.Test

class StreamingTextApiProfilesTest {
    private val geminiLive = TranslationApiOptions(provider = TranslationApiProvider.GEMINI_LIVE,
        model = GEMINI_LIVE_AGENT, baseUrl = "https://generativelanguage.googleapis.com/v1beta",
        interpretationMode = OnlineInterpretationMode.PROFESSIONAL, domainPrompt = "반도체 장비 안내",
        interpreterInstructions = "숫자와 조건을 빠뜨리지 마세요.", tone = TranslationStyle.FORMAL,
        hasKey = true, allowOnline = true, allowLiveAudio = true, allowDomainReferences = true)
    private val openAiAudio = TranslationApiOptions(provider = TranslationApiProvider.OPENAI_REALTIME,
        model = "gpt-realtime-2", realtimeAudio = true, hasKey = true, allowOnline = true,
        allowLiveAudio = true, allowDomainReferences = true)

    private fun assertTextWithFreshConsent(options: TranslationApiOptions) {
        assertTrue(validTranslationApiOptions(options))
        assertTrue(isOnlineTextApiProfile(options))
        assertFalse(options.usesNativeLiveAudio)
        assertFalse(options.allowOnline)
        assertFalse(options.allowLiveAudio)
        assertFalse(options.allowDomainReferences)
        assertFalse(options.localFallback)
        // Settings resolves the destination vault itself; profiles never establish key availability.
        assertFalse(options.hasKey)
    }

    @Test fun firstStreamingOnlineSelectionUsesGeminiTextAndDeviceSpeechRoute() {
        val selected = restoreStreamingTextApiProfile(TranslationApiOptions(), null, null)
        assertTextWithFreshConsent(selected)
        assertEquals(TranslationApiProvider.GEMINI, selected.provider)
        assertTrue(selected.endpoint.endsWith(":generateContent"))
        assertTrue(serviceExperience(selected).processing.startsWith("기기 음성 인식"))
        assertTrue(serviceExperience(selected).processing.contains("기기 음성 재생"))
    }

    @Test fun legacyGeminiLiveBecomesTextInTheSameCredentialDestination() {
        val selected = restoreStreamingTextApiProfile(TranslationApiOptions(), null, geminiLive)
        assertTextWithFreshConsent(selected)
        assertEquals(TranslationApiProvider.GEMINI, selected.provider)
        assertEquals(geminiLive.credentialScope, selected.credentialScope)
        assertEquals(geminiLive.baseUrl, selected.baseUrl)
        assertEquals(geminiLive.domainPrompt, selected.domainPrompt)
        assertEquals(geminiLive.interpreterInstructions, selected.interpreterInstructions)
        assertEquals(geminiLive.tone, selected.tone)
        assertEquals(geminiLive.interpretationMode, selected.interpretationMode)
        assertFalse(selected.model in setOf(GEMINI_LIVE_AGENT, GEMINI_LIVE_TRANSLATE))
    }

    @Test fun legacyOpenAiNativeKeepsModelAndVendorButUsesTheTextBridge() {
        val selected = restoreStreamingTextApiProfile(TranslationApiOptions(), null, openAiAudio)
        assertTextWithFreshConsent(selected)
        assertEquals(TranslationApiProvider.OPENAI_REALTIME, selected.provider)
        assertEquals(openAiAudio.model, selected.model)
        assertEquals(openAiAudio.credentialScope, selected.credentialScope)
        assertTrue(serviceExperience(selected).processing.startsWith("기기 음성 인식"))
        assertTrue(serviceExperience(selected).limitation!!.contains("문장 연결"))
    }

    @Test fun currentNativeProfileCanBeConvertedWithoutSavedChoices() {
        for (current in listOf(geminiLive, openAiAudio)) {
            val selected = restoreStreamingTextApiProfile(current, null, null)
            assertTextWithFreshConsent(selected)
            assertEquals(current.credentialScope, selected.credentialScope)
        }
    }

    @Test fun separateTextProfileWinsOverARecentNativeLearningProvider() {
        val saved = openAiTextChoice(TranslationApiOptions()).copy(model = "gpt-5.4")
        val selected = restoreStreamingTextApiProfile(TranslationApiOptions(), saved, geminiLive)
        assertTextWithFreshConsent(selected)
        assertEquals(saved.provider, selected.provider)
        assertEquals(saved.model, selected.model)
        assertEquals(saved.credentialScope, selected.credentialScope)
        // Restoration does not modify the immutable profile used by the learning lookup.
        assertEquals(TranslationApiProvider.GEMINI_LIVE, geminiLive.provider)
        assertTrue(geminiLive.allowLiveAudio)
    }

    @Test fun nativeAndOfflineChoicesCannotOverwriteTheRememberedTextProfile() {
        assertFalse(isOnlineTextApiProfile(geminiLive))
        assertFalse(isOnlineTextApiProfile(openAiAudio))
        assertFalse(isOnlineTextApiProfile(TranslationApiOptions()))
        assertTrue(isOnlineTextApiProfile(openAiAudio.copy(realtimeAudio = false)))
        assertTrue(isOnlineTextApiProfile(geminiSharedInputChoice(TranslationApiOptions())))
    }

    @Test fun textModelsAndCompatibleEndpointRoundTripWithoutPermissions() {
        val compatible = TranslationApiOptions(provider = TranslationApiProvider.COMPATIBLE,
            model = "translator-v2", baseUrl = "https://translator.example/v1",
            protocol = TranslationApiProtocol.CHAT_COMPLETIONS)
        for (saved in listOf(geminiSharedInputChoice(TranslationApiOptions()).copy(model = "gemini-3.8-flash"),
            openAiTextChoice(TranslationApiOptions()).copy(model = "gpt-5.4"),
            openAiAudio.copy(realtimeAudio = false), compatible)) {
            val restored = TranslationApiOptions.fromPortable(saved.portable())
            val selected = restoreStreamingTextApiProfile(TranslationApiOptions(), restored, geminiLive)
            assertTextWithFreshConsent(selected)
            assertEquals(saved.provider, selected.provider)
            assertEquals(saved.model, selected.model)
            assertEquals(saved.baseUrl, selected.baseUrl)
            assertEquals(saved.protocol, selected.protocol)
            assertEquals(saved.credentialScope, selected.credentialScope)
        }
    }

    @Test fun malformedOrNativeTextSlotDoesNotBypassDestinationValidation() {
        val invalid = openAiTextChoice(TranslationApiOptions()).copy(baseUrl = "http://translator.example/v1")
        for (saved in listOf(invalid, geminiLive, openAiAudio)) {
            val selected = restoreStreamingTextApiProfile(TranslationApiOptions(), saved, openAiAudio)
            assertTextWithFreshConsent(selected)
            assertEquals(openAiAudio.model, selected.model)
            assertEquals(openAiAudio.credentialScope, selected.credentialScope)
        }
        assertTextWithFreshConsent(restoreStreamingTextApiProfile(TranslationApiOptions(), invalid, invalid))
    }

    @Test fun portableRestorationPreservesTheCurrentBudgetPreference() {
        val current = TranslationApiOptions(budgetLimitUsd = "7.50")
        val saved = openAiTextChoice(TranslationApiOptions()).copy(budgetLimitUsd = "1.00")
        assertEquals("7.50", restoreStreamingTextApiProfile(current, saved, geminiLive).budgetLimitUsd)
    }

    @Test fun explicitStreamingPickerOffersOnlyImplementedTextRoutes() {
        val choices = translationApiServiceChoices(textOnly = true)
        assertEquals(setOf("gemini-batch", "openai", "openai-text"), choices.map { it.id }.toSet())
        for (choice in choices) {
            val selected = translationApiServiceChoice(openAiAudio, choice.id, textOnly = true)
            assertTrue(isOnlineTextApiProfile(selected))
            assertFalse(selected.usesNativeLiveAudio)
        }
    }

    @Test fun existingGeneralAndRelayPickerDefaultsRemainAvailable() {
        assertEquals(5, translationApiServiceChoices().size)
        val choices = translationApiServiceChoices(nativeOnly = true)
        assertEquals(setOf("gemini", "openai-audio"), choices.map { it.id }.toSet())
        for (choice in choices) assertTrue(translationApiServiceChoice(TranslationApiOptions(),
            choice.id, nativeOnly = true).usesNativeLiveAudio)
    }

    @Test(expected = IllegalArgumentException::class)
    fun hiddenNativeChoiceCannotBeAppliedToStreaming() {
        translationApiServiceChoice(TranslationApiOptions(), "openai-audio", textOnly = true)
    }

    @Test(expected = IllegalArgumentException::class)
    fun conflictingPanelRoutesCannotOfferAnyService() {
        translationApiServiceChoices(nativeOnly = true, textOnly = true)
    }
}
