package app.guidecast.transmitter

import app.guidecast.core.translation.TranslationStyle
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RelayVoiceProfileTest {
    private val gemini = TranslationApiOptions(provider = TranslationApiProvider.GEMINI_LIVE,
        model = GEMINI_LIVE_AGENT, baseUrl = "https://generativelanguage.googleapis.com/v1beta")
    private val openAi = TranslationApiOptions(provider = TranslationApiProvider.OPENAI_REALTIME,
        model = "gpt-realtime-2", realtimeAudio = true)

    private class Wire : GeminiLiveWire, OpenAiAudioWire {
        val sent = mutableListOf<String>()
        val events = Channel<String>(40)
        var closed = false
        private suspend fun socket(block: suspend (RealtimeSocket) -> Unit) {
            try {
                block(object : RealtimeSocket {
                    override suspend fun send(text: String) { sent += text }
                    override suspend fun receive(): String = events.receive()
                })
            } finally { closed = true }
        }
        override suspend fun connect(key: String, authorized: () -> Boolean, block: suspend (RealtimeSocket) -> Unit) = socket(block)
        override suspend fun connect(model: String, key: String, authorized: () -> Boolean, block: suspend (RealtimeSocket) -> Unit) = socket(block)
    }

    private fun geminiGeneration(model: String = GEMINI_LIVE_AGENT, voice: RelayVoiceGender = RelayVoiceGender.AUTO) =
        JSONObject(geminiLiveSetup(model, "en", liveVoice = voice)).getJSONObject("setup").getJSONObject("generationConfig")
    private fun geminiVoice(generation: JSONObject): String = generation.getJSONObject("speechConfig")
        .getJSONObject("voiceConfig").getJSONObject("prebuiltVoiceConfig").getString("voiceName")
    private fun openAiOutput(setup: String): JSONObject = JSONObject(setup).getJSONObject("session")
        .getJSONObject("audio").getJSONObject("output")

    @Test fun oldPortableProfilesKeepTheExistingDefaultAndNewVoicesRoundTripWithoutPermission() {
        val old = gemini.portable().apply { remove("liveVoice") }
        assertEquals(RelayVoiceGender.AUTO, TranslationApiOptions.fromPortable(old).liveVoice)
        for (profile in listOf(gemini, openAi)) for (voice in RelayVoiceGender.entries) {
            val selected = profile.copy(liveVoice = voice, allowOnline = true, allowLiveAudio = true,
                allowDomainReferences = true, hasKey = true)
            val restored = TranslationApiOptions.fromPortable(selected.portable())
            assertEquals(voice, restored.liveVoice)
            assertEquals(profile.credentialScope, restored.credentialScope)
            assertFalse(restored.allowOnline)
            assertFalse(restored.allowLiveAudio)
            assertFalse(restored.allowDomainReferences)
            assertFalse(restored.hasKey)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun arbitraryVoiceNamesCannotEnterThroughPortableSettings() {
        TranslationApiOptions.fromPortable(gemini.portable().put("liveVoice", "arbitrary-voice"))
    }

    @Test fun capabilityIsLimitedToAgentAndImplementedNativeRealtimeModels() {
        assertTrue(relayVoiceSupported(gemini))
        assertTrue(relayVoiceSupported(openAi))
        for (profile in listOf(gemini.copy(model = GEMINI_LIVE_TRANSLATE), openAi.copy(realtimeAudio = false),
            TranslationApiOptions(), geminiSharedInputChoice(gemini), openAiTextChoice(openAi))) {
            assertFalse(relayVoiceSupported(profile))
            assertEquals(listOf(RelayVoiceGender.AUTO), relayVoiceChoices(profile))
        }
        assertFalse(relayVoiceSupported(openAi.copy(model = "unverified-model")))
        assertEquals(RelayVoiceGender.entries.toList(), relayVoiceChoices(gemini))
    }

    @Test fun labelsIdentifyVoiceFamiliesAndUnsupportedReplicationWithoutAGenderGuarantee() {
        assertEquals("여성 계열 · Kore", relayVoiceLabel(gemini.copy(liveVoice = RelayVoiceGender.FEMALE)))
        assertEquals("남성 계열 · Puck", relayVoiceLabel(gemini.copy(liveVoice = RelayVoiceGender.MALE)))
        assertEquals("여성 계열 · marin", relayVoiceLabel(openAi.copy(liveVoice = RelayVoiceGender.FEMALE)))
        assertEquals("남성 계열 · cedar", relayVoiceLabel(openAi.copy(liveVoice = RelayVoiceGender.MALE)))
        assertEquals("기본 · marin", relayVoiceLabel(openAi))
        val dedicated = relayVoiceLabel(gemini.copy(model = GEMINI_LIVE_TRANSLATE, liveVoice = RelayVoiceGender.MALE))
        assertTrue(dedicated.contains("선택 미지원"))
        assertTrue(dedicated.contains("모델이 결정"))
        assertFalse(dedicated.contains("Puck"))
    }

    @Test fun agentDefaultOmitsVoiceAndExplicitChoicesUseTheDocumentedSpeechConfig() {
        assertFalse(geminiGeneration().has("speechConfig"))
        assertEquals("Kore", geminiVoice(geminiGeneration(voice = RelayVoiceGender.FEMALE)))
        assertEquals("Puck", geminiVoice(geminiGeneration(voice = RelayVoiceGender.MALE)))
        for (voice in RelayVoiceGender.entries) {
            val setup = JSONObject(geminiLiveSetup(GEMINI_LIVE_AGENT, "en", "Semiconductor", liveVoice = voice))
                .getJSONObject("setup")
            assertEquals("[\"AUDIO\"]", setup.getJSONObject("generationConfig").getJSONArray("responseModalities").toString())
            assertTrue(setup.has("systemInstruction"))
            assertEquals(2_048, setup.getJSONObject("generationConfig").getInt("maxOutputTokens"))
        }
    }

    @Test fun dedicatedLiveTranslateNeverReceivesAnUnsupportedVoiceField() {
        val original = geminiLiveSetup(GEMINI_LIVE_TRANSLATE, "en")
        for (voice in RelayVoiceGender.entries) {
            assertEquals(original, geminiLiveSetup(GEMINI_LIVE_TRANSLATE, "en", liveVoice = voice))
            val generation = geminiGeneration(GEMINI_LIVE_TRANSLATE, voice)
            assertFalse(generation.has("speechConfig"))
            assertEquals("en", generation.getJSONObject("translationConfig").getString("targetLanguageCode"))
        }
    }

    @Test fun realtimeDefaultAndFemaleKeepMarinWhileMaleUsesCedarForEveryAdmittedModel() {
        for (model in OPENAI_REALTIME_MODELS) for (voice in RelayVoiceGender.entries) {
            val output = openAiOutput(openAiAudioSetup(model, "ko", "en", liveVoice = voice))
            assertEquals(if (voice == RelayVoiceGender.MALE) "cedar" else "marin", output.getString("voice"))
            assertEquals(24_000, output.getJSONObject("format").getInt("rate"))
        }
    }

    @Test fun storedLiveVoiceIsNotSentBySentenceTranslationRoutes() {
        val textProfiles = listOf(geminiSharedInputChoice(gemini), openAi.copy(realtimeAudio = false), openAiTextChoice(openAi))
        for (profile in textProfiles) for (voice in RelayVoiceGender.entries) {
            val selected = profile.copy(liveVoice = voice)
            assertTrue(validTranslationApiOptions(selected))
            val request = JSONObject(TranslationApiJson.request(selected, TranslationStyle.CONVERSATIONAL.name,
                "안녕하세요.", null, "ko", "en"))
            assertFalse(request.has("liveVoice"))
            assertFalse(request.has("voice"))
            assertFalse(request.has("audio"))
            for (name in listOf("Kore", "Puck", "marin", "cedar")) assertFalse(request.toString().contains(name))
        }
    }

    @Test fun streamingProfilePreservesStoredVoiceWithoutEnablingItsAudioRoute() {
        val saved = TranslationApiOptions.fromPortable(openAi.copy(liveVoice = RelayVoiceGender.MALE).portable())
        val restored = restoreStreamingTextApiProfile(TranslationApiOptions(), null, saved)
        assertEquals(RelayVoiceGender.MALE, restored.liveVoice)
        assertFalse(restored.usesNativeLiveAudio)
        assertFalse(relayVoiceSupported(restored))
        assertFalse(restored.allowOnline)
        assertFalse(restored.allowLiveAudio)
    }

    @Test fun selectedGeminiVoiceReachesTheActualTransportSetup() = runTest {
        for (voice in listOf(RelayVoiceGender.FEMALE, RelayVoiceGender.MALE)) {
            val wire = Wire(); wire.events.send("{\"setupComplete\":{}}")
            var ready = false
            val task = launch { GeminiLiveTransport(wire).run("synthetic", GEMINI_LIVE_AGENT, "en",
                flow { awaitCancellation() }, { true }, { ready = true }, {}, liveVoice = voice) }
            runCurrent()
            assertTrue(ready)
            assertEquals(1, wire.sent.size)
            val generation = JSONObject(wire.sent.single()).getJSONObject("setup").getJSONObject("generationConfig")
            assertEquals(if (voice == RelayVoiceGender.MALE) "Puck" else "Kore", geminiVoice(generation))
            task.cancelAndJoin(); assertTrue(wire.closed)
        }
    }

    @Test fun selectedRealtimeVoiceReachesTheActualTransportSetup() = runTest {
        for (voice in RelayVoiceGender.entries) {
            val wire = Wire(); wire.events.send("{\"type\":\"session.updated\"}")
            var ready = false
            val task = launch { OpenAiAudioTransport(wire).run("synthetic", "gpt-realtime-2", "ko", "en",
                flow { awaitCancellation() }, { true }, { ready = true }, {}, liveVoice = voice) }
            runCurrent()
            assertTrue(ready)
            assertEquals(1, wire.sent.size)
            assertEquals(if (voice == RelayVoiceGender.MALE) "cedar" else "marin", openAiOutput(wire.sent.single()).getString("voice"))
            task.cancelAndJoin(); assertTrue(wire.closed)
        }
    }

    @Test fun changingVoiceRevokesTheSnapshotInsteadOfUpdatingAnExistingSocket() = runTest {
        for (native in listOf(gemini, openAi)) {
            val wire = Wire(); var current = native
            var ready = false
            wire.events.send(if (native.provider == TranslationApiProvider.GEMINI_LIVE)
                "{\"setupComplete\":{}}" else "{\"type\":\"session.updated\"}")
            val task = launch { runCatching {
                if (native.provider == TranslationApiProvider.GEMINI_LIVE) GeminiLiveTransport(wire).run("synthetic", native.model,
                    "en", flow { awaitCancellation() }, { current == native }, { ready = true }, {}, liveVoice = native.liveVoice)
                else OpenAiAudioTransport(wire).run("synthetic", native.model, "ko", "en", flow { awaitCancellation() },
                    { current == native }, { ready = true }, {}, liveVoice = native.liveVoice)
            } }
            runCurrent(); assertTrue(ready)
            current = native.copy(liveVoice = RelayVoiceGender.MALE)
            advanceTimeBy(30); runCurrent()
            assertTrue(task.isCompleted)
            task.join()
            assertTrue(wire.closed)
            assertEquals(1, wire.sent.size)
        }
    }
}
