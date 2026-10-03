package app.guidecast.transmitter

import org.junit.Assert.*
import org.junit.Test

class ServiceExperienceOptionsTest {
    private val live = TranslationApiOptions(provider = TranslationApiProvider.GEMINI_LIVE,
        model = GEMINI_LIVE_TRANSLATE, baseUrl = "https://generativelanguage.googleapis.com/v1beta")

    @Test fun liveAudioNeverAdvertisesUnimplementedLearningOrRag() {
        serviceModelChoices(live).forEach { choice ->
            val experience = serviceExperience(applyServiceModelChoice(live, choice))
            assertFalse(experience.supportsLearningComparison)
            assertFalse(experience.supportsReferences)
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
        assertTrue(experience.limitation!!.contains("직접 음성 통역은 아직 지원하지 않습니다"))
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
