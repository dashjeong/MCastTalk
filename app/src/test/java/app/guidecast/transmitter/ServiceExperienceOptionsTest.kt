package app.guidecast.transmitter

import org.junit.Assert.*
import org.junit.Test

class ServiceExperienceOptionsTest {
    @Test fun correlatedNativeComparisonAndTextComparisonHaveSeparateExplicitControls() {
        val native = TranslationApiOptions(provider = TranslationApiProvider.OPENAI_REALTIME, model = "gpt-realtime-2.1-mini", realtimeAudio = true)
        assertTrue(serviceExperience(native).supportsNativePairComparison)
        assertFalse(serviceExperience(native).supportsLearningComparison)
        assertFalse(native.allowOnline); assertFalse(native.allowLiveAudio)
        assertFalse(serviceExperience(native.copy(realtimeAudio = false)).supportsNativePairComparison)
        assertTrue(serviceExperience(native.copy(realtimeAudio = false)).supportsLearningComparison)
        assertFalse(serviceExperience(TranslationApiOptions(provider = TranslationApiProvider.GEMINI_LIVE,
            model = GEMINI_LIVE_AGENT)).supportsNativePairComparison)
    }
    @Test fun everyPresetFitsImplementedProtocolAndRoundTripsWithoutGrantingConsent() {
        for (provider in listOf(TranslationApiProvider.GEMINI, TranslationApiProvider.GEMINI_LIVE,
            TranslationApiProvider.OPENAI, TranslationApiProvider.OPENAI_REALTIME)) {
            val current = if (provider in setOf(TranslationApiProvider.GEMINI, TranslationApiProvider.GEMINI_LIVE))
                TranslationApiOptions(provider = provider, baseUrl = "https://generativelanguage.googleapis.com/v1beta")
                else TranslationApiOptions(provider = provider)
            assertTrue(serviceModelChoices(current).size >= 2)
            for (choice in serviceModelChoices(current)) {
                val selected = applyServiceModelChoice(current, choice)
                assertTrue("Invalid preset ${choice.id}", validTranslationApiOptions(selected))
                val restored = TranslationApiOptions.fromPortable(selected.portable())
                assertEquals(choice.id, restored.model)
                assertEquals(provider, restored.provider)
                assertFalse(restored.allowOnline)
            }
        }
    }
    @Test fun changingServiceRowWithinSameRouteDoesNotResetChosenModel() {
        val gemini = geminiSharedInputChoice(TranslationApiOptions()).copy(model = "gemini-3.8-flash")
        assertEquals(gemini, geminiSharedInputChoice(gemini))
        val realtime = onlineServiceChoice(TranslationApiOptions(), false).copy(model = "gpt-realtime-2")
        assertEquals(realtime, onlineServiceChoice(realtime, false))
        val text = openAiTextChoice(TranslationApiOptions()).copy(model = "gpt-5.4")
        assertEquals(text, openAiTextChoice(text))
    }
    @Test fun flashPresetsUseGenerateContentRatherThanLiveEndpoint() {
        val options = geminiSharedInputChoice(TranslationApiOptions())
        serviceModelChoices(options).forEach {
            val selected = applyServiceModelChoice(options, it)
            assertTrue(selected.endpoint.endsWith("/models/${it.id}:generateContent"))
            assertFalse(serviceExperience(selected).processing.contains("마이크 음성 → Gemini"))
        }
    }
    private val live = TranslationApiOptions(provider = TranslationApiProvider.GEMINI_LIVE,
        model = GEMINI_LIVE_TRANSLATE, baseUrl = "https://generativelanguage.googleapis.com/v1beta")

    @Test fun liveReferenceCapabilityMatchesSupportedModelAndLearningRemainsSeparate() {
        serviceModelChoices(live).forEach { choice ->
            val experience = serviceExperience(applyServiceModelChoice(live, choice))
            assertFalse(experience.supportsLearningComparison)
            assertEquals(choice.id == GEMINI_LIVE_AGENT, experience.supportsReferences)
            assertTrue(experience.transmitted.contains("음성"))
        }
    }

    @Test fun dedicatedTranslationCannotAdvertiseDomainInstructions() {
        assertFalse(serviceExperience(live).supportsDomainInstructions)
        assertTrue(serviceExperience(live.copy(model = GEMINI_LIVE_AGENT)).supportsDomainInstructions)
        assertFalse(serviceExperience(live.copy(model = "unknown-live-model")).supportsDomainInstructions)
    }

    @Test fun selectingPresetPreservesCredentialDestinationAndDoesNotGrantConsent() {
        val current = live.copy(hasKey = true, allowOnline = true, allowLiveAudio = true,
            allowDomainReferences = true, domainPrompt = "기술 안내", revision = 42)
        val selected = applyServiceModelChoice(current, serviceModelChoices(current).last())
        assertEquals(current.credentialScope, selected.credentialScope)
        assertEquals(current.hasKey, selected.hasKey)
        assertEquals(current.domainPrompt, selected.domainPrompt)
        assertEquals(OnlineInterpretationMode.PROFESSIONAL, selected.interpretationMode)
        assertFalse(selected.allowOnline)
        assertFalse(selected.allowLiveAudio)
        assertFalse(selected.allowDomainReferences)
        assertTrue(validTranslationApiOptions(selected))
    }

    @Test fun switchingBackToDedicatedModelAlsoSwitchesToContinuousMode() {
        val selected = applyServiceModelChoice(live.copy(model = GEMINI_LIVE_AGENT,
            interpretationMode = OnlineInterpretationMode.PROFESSIONAL), serviceModelChoices(live).first())
        assertEquals(GEMINI_LIVE_TRANSLATE, selected.model)
        assertEquals(OnlineInterpretationMode.CONTINUOUS, selected.interpretationMode)
    }

    @Test fun realtimeDescriptionReflectsAppTextRouteRatherThanProviderAudioCapability() {
        val experience = serviceExperience(TranslationApiOptions(provider = TranslationApiProvider.OPENAI_REALTIME,
            model = "gpt-realtime-2.1-mini"))
        assertTrue(experience.processing.startsWith("기기 음성 인식"))
        assertTrue(experience.transmitted.contains("인식된 문장"))
        assertTrue(experience.limitation!!.contains("현재 선택은 문장 연결"))
    }

    @Test fun offlineSummaryDoesNotExposeStaleOnlineModelOrPromisePreparedModels() {
        val experience = serviceExperience(TranslationApiOptions())
        assertTrue(experience.transmitted.contains("일반 통역"))
        assertTrue(experience.limitation!!.contains("언어 모델을 먼저 준비"))
        assertTrue(serviceModelChoices(TranslationApiOptions()).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun unavailablePresetCannotBeAppliedToTextProvider() {
        applyServiceModelChoice(TranslationApiOptions(provider = TranslationApiProvider.OPENAI_REALTIME),
            serviceModelChoices(live).first())
    }
}
