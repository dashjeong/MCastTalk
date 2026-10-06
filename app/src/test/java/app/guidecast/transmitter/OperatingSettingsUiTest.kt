package app.guidecast.transmitter

import app.guidecast.core.audio.AudioInputKind
import org.junit.Assert.*
import org.junit.Test

class OperatingSettingsUiTest {
    private val native = TranslationApiOptions(provider = TranslationApiProvider.OPENAI_REALTIME,
        model = "gpt-realtime-2", realtimeAudio = true, hasKey = true)
    private val relay = InterpreterRelayOptions().withTargets(listOf("en", "ja", "zh", "ru", "vi"))

    @Test fun missingNativeServiceModelAndKeyHaveDifferentRepairDestinations() {
        assertEquals(RelaySetupItem.SERVICE, relayRequiredSetting(TranslationApiOptions(), relay, AudioInputKind.BUILT_IN))
        assertEquals(RelaySetupItem.MODEL, relayRequiredSetting(native.copy(model = "unsupported"), relay, AudioInputKind.BUILT_IN))
        assertEquals(RelaySetupItem.KEY, relayRequiredSetting(native.copy(hasKey = false), relay, AudioInputKind.BUILT_IN))
    }

    @Test fun missingOutputAndWrongInputAreRepairedWithoutChangingFiveTargets() {
        assertEquals(RelaySetupItem.OUTPUT, relayRequiredSetting(native, relay.copy(localPlayback = false), AudioInputKind.BUILT_IN))
        for (kind in listOf(null, AudioInputKind.DEVICE_PLAYBACK, AudioInputKind.WEB_SPEAKER))
            assertEquals(RelaySetupItem.INPUT, relayRequiredSetting(native, relay, kind))
        assertNull(relayRequiredSetting(native, relay, AudioInputKind.USB))
        assertEquals(listOf("en", "ja", "zh", "ru", "vi"), relay.targetLanguageTags)
    }

    @Test fun sourceAndMonitorMismatchRouteToLanguageSelection() {
        assertEquals(RelaySetupItem.LANGUAGES, relayRequiredSetting(native, relay.copy(source = "en-US"), AudioInputKind.BUILT_IN))
        assertEquals(RelaySetupItem.LANGUAGES, relayRequiredSetting(native, relay.copy(target = "ko"), AudioInputKind.BUILT_IN))
    }

    @Test fun streamingRepairsKeyAndConsentButRawAndOfflineModesDoNotRequireThem() {
        val online = native.copy(realtimeAudio = false)
        assertEquals("service", streamingRequiredSetting(native, true, setOf("en"), true))
        assertEquals("languages", streamingRequiredSetting(online, true, emptySet(), true))
        assertEquals("key", streamingRequiredSetting(online.copy(hasKey = false), true, setOf("en"), false))
        assertEquals("consent", streamingRequiredSetting(online, true, setOf("en"), false))
        assertNull(streamingRequiredSetting(online, true, setOf("en"), true))
        assertNull(streamingRequiredSetting(online.copy(hasKey = false), false, emptySet(), false))
        assertNull(streamingRequiredSetting(TranslationApiOptions(), true, setOf("en"), false))
    }

    @Test fun onlinePreparationAdviceKeepsDeviceRecognitionAndVoiceButSkipsLocalTranslationAssets() {
        val cold = TranslationModelUiState(selectedLanguageTags = setOf("en"))
        val online = native.copy(realtimeAudio = false)
        val advice = requireNotNull(streamingPreparationAdvice(online, cold))
        assertTrue(advice.recognition)
        assertEquals(listOf("en"), advice.voices)
        assertTrue(advice.translators.isEmpty())
        assertEquals(listOf("en"), requireNotNull(streamingPreparationAdvice(TranslationApiOptions(), cold)).translators)
    }

    @Test fun readyAndroidFallbackVoiceAvoidsAnUnnecessaryPreparationWarning() {
        val ready = TranslationModelUiState(selectedLanguageTags = setOf("en"), speechRecognitionReady = true,
            ttsFallbackLanguageTags = setOf("en"))
        assertNull(streamingPreparationAdvice(native.copy(realtimeAudio = false), ready))
        assertNull(streamingPreparationAdvice(TranslationApiOptions(), ready.copy(broadcastTranslationEnabled = false)))
    }
}
