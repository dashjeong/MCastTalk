package app.guidecast.transmitter

import app.guidecast.core.translation.LanguageModelStatus
import app.guidecast.core.translation.ModelReadiness
import app.guidecast.provider.moonshine.tts.MoonshineTtsReadiness
import app.guidecast.provider.moonshine.tts.MoonshineTtsStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class OperatorDashboardUiPolicyTest {
    @Test
    fun silenceAndQuietPcmAreNormalWaitingNotAnInputFailure() {
        for (peak in listOf(0f, 0.0001f)) {
            val status = inputListeningStatus(BroadcastSnapshot(
                inputPhase = InputPhase.ACTIVE, inputFrameCount = 1200, inputPeak = peak,
            ))
            assertEquals(OperatorStatusTone.NEUTRAL, status.tone)
            assertEquals("발화 대기 · PCM 수신 중 (현재 조용함)", status.state)
        }
        assertEquals(OperatorStatusTone.WORKING, inputListeningStatus(
            BroadcastSnapshot(inputPhase = InputPhase.ACTIVE),
        ).tone)
    }

    @Test
    fun waitingDoesNotHideActualInputOrLanguageFailure() {
        assertEquals(OperatorStatusTone.ERROR, inputListeningStatus(BroadcastSnapshot(
            inputPhase = InputPhase.ACTIVE, inputErrorMessage = "permission denied",
        )).tone)
        val failed = BroadcastChannelSnapshot("en", "en", "English",
            synthesisState = BroadcastChannelWorkerState.DEGRADED,
            lastSynthesisError = "first PCM timeout")
        assertEquals(OperatorStatusTone.ERROR, synthesisWorkerStatus(failed, true).tone)
        assertEquals(OperatorStatusTone.ERROR, interpretationProgressStatus(BroadcastSnapshot(
            inputPhase = InputPhase.FAILED, translationChannels = listOf(failed),
        )).tone)
    }

    @Test
    fun preparedWorkersWaitForWorkRatherThanImplyingMissingAudioResponse() {
        assertEquals("문장 대기", translationWorkerStatus(null, true).state)
        assertEquals("번역문 대기", synthesisWorkerStatus(null, true).state)
        val state = BroadcastSnapshot(inputPhase = InputPhase.ACTIVE, translationTestActive = true)
        assertEquals(OperatorStatusTone.NEUTRAL, interpretationProgressStatus(state).tone)
        assertEquals(OperatorStatusTone.WORKING, interpretationProgressStatus(state.copy(
            transcripts = listOf(TranslationTranscriptLine(0, "이번 전시회는", 0)),
        )).tone)
        assertEquals(OperatorStatusTone.NEUTRAL, interpretationProgressStatus(state.copy(
            inputPhase = InputPhase.PAUSED,
            transcripts = listOf(TranslationTranscriptLine(0, "이번 전시회는", 0)),
        )).tone)
    }

    @Test
    fun partialReadinessKeepsHealthyLanguageVisible() {
        val models = TranslationModelUiState(
            selectedLanguageTags = linkedSetOf("en", "ja"),
            speechRecognitionReady = true, broadcastTranslationEnabled = true,
            statuses = listOf(LanguageModelStatus("en", ModelReadiness.READY), LanguageModelStatus("ja", ModelReadiness.FAILED)),
            ttsStatuses = listOf(MoonshineTtsStatus("en", "English", "kokoro_af_heart", MoonshineTtsReadiness.READY)),
        )
        assertEquals("일부 준비 1/2", operatorStageStatuses(BroadcastSnapshot(), models)[1].state)
    }

    @Test
    fun idleDashboardNamesEveryIndependentStage() {
        val statuses = operatorStageStatuses(BroadcastSnapshot(), TranslationModelUiState())

        assertEquals(listOf("입력", "통역", "웹 방송"), statuses.map { it.label })
        assertEquals(listOf("대기", "준비 확인", "대기"), statuses.map { it.state })
        val original = operatorStageStatuses(BroadcastSnapshot(),
            TranslationModelUiState(broadcastTranslationEnabled = false))
        assertEquals("원음 모드", original[1].state)
    }

    @Test
    fun preparedTranslationIsVisibleWithoutConflatingBroadcastState() {
        val models = TranslationModelUiState(
            selectedLanguageTags = setOf("en"),
            statuses = listOf(LanguageModelStatus("en", ModelReadiness.READY)),
            speechRecognitionReady = true,
            broadcastTranslationEnabled = true,
            ttsStatuses = listOf(
                MoonshineTtsStatus(
                    languageTag = "en",
                    displayName = "영어",
                    voiceId = "kokoro_af_heart",
                    readiness = MoonshineTtsReadiness.READY,
                ),
            ),
        )

        val statuses = operatorStageStatuses(
            BroadcastSnapshot(inputPhase = InputPhase.ACTIVE, phase = BroadcastPhase.IDLE),
            models,
        )

        assertEquals("동작 중", statuses[0].state)
        assertEquals("1개 언어 준비", statuses[1].state)
        assertEquals("대기", statuses[2].state)
    }

    @Test
    fun pausedBroadcastDoesNotMakeActiveInputLookPaused() {
        val statuses = operatorStageStatuses(
            BroadcastSnapshot(inputPhase = InputPhase.ACTIVE, phase = BroadcastPhase.PAUSED),
            TranslationModelUiState(),
        )

        assertEquals(OperatorStatusTone.READY, statuses[0].tone)
        assertEquals("동작 중", statuses[0].state)
        assertEquals(OperatorStatusTone.WARNING, statuses[2].tone)
        assertEquals("일시정지", statuses[2].state)
    }

    @Test
    fun oneDegradedLanguageDoesNotChangeAnotherChannelsVisibleHealth() {
        val english = BroadcastChannelSnapshot(
            channelId = "en",
            languageTag = "en",
            displayName = "English",
            translationState = BroadcastChannelWorkerState.DEGRADED,
            lastTranslationError = "translator reconnecting",
        )
        val japanese = BroadcastChannelSnapshot(
            channelId = "ja",
            languageTag = "ja",
            displayName = "日本語",
            lastTranslatedSequence = 7,
            lastCompletedSequence = 7,
        )

        assertEquals(
            OperatorStatusTone.ERROR,
            translationWorkerStatus(english, modelReady = true).tone,
        )
        assertEquals(
            OperatorStatusTone.READY,
            translationWorkerStatus(japanese, modelReady = true).tone,
        )
        assertEquals(
            "채널 확인 필요",
            broadcastChannelStatus(BroadcastPhase.LIVE, english).state,
        )
        assertEquals(
            "송출 중",
            broadcastChannelStatus(BroadcastPhase.LIVE, japanese).state,
        )
    }

    @Test
    fun configuredFallbackKeepsOnlyItsOwnPriorityChannelReady() {
        val models = TranslationModelUiState(
            selectedLanguageTags = linkedSetOf("es", "ar"),
            statuses = listOf(
                LanguageModelStatus("es", ModelReadiness.READY),
                LanguageModelStatus("ar", ModelReadiness.NOT_INSTALLED),
            ),
            useGemma = true,
            gemmaReady = false,
        )

        assertEquals(true, translationModelReadyForChannel(models, "es"))
        assertEquals(false, translationModelReadyForChannel(models, "ar"))
    }

    @Test
    fun publishedAudioWithZeroListenersDoesNotClaimWebDelivery() {
        val channel = BroadcastChannelSnapshot(
            channelId = "en",
            languageTag = "en",
            displayName = "English",
            lastSynthesizedSequence = 4L,
            lastPublishedSequence = 4L,
            publishedFrameCount = 18L,
            listenerCount = 0,
        )

        val status = synthesisWorkerStatus(channel, ttsReady = true)

        assertEquals("서버 게시·청취자 대기", status.state)
        assertEquals(OperatorStatusTone.READY, status.tone)
        assertEquals(
            "최근 합성 문장 #4 · 서버 게시 18프레임 · 웹 전송 0회",
            channelAudioDeliverySummary(channel),
        )
    }

    @Test
    fun matchingPublishedAndWebsocketSequenceShowsHealthyDelivery() {
        val channel = BroadcastChannelSnapshot(
            channelId = "ja",
            languageTag = "ja",
            displayName = "日本語",
            listenerCount = 1,
            lastSynthesizedSequence = 8L,
            lastPublishedSequence = 8L,
            publishedFrameCount = 25L,
            webSocketDeliveredFrameCount = 25L,
            lastWebSocketDeliveredSequence = 8L,
        )

        val status = synthesisWorkerStatus(channel, ttsReady = true)

        assertEquals("웹 전송 확인", status.state)
        assertEquals(OperatorStatusTone.READY, status.tone)
        assertEquals(
            "최근 합성 문장 #8 · 서버 게시 25프레임 · 웹 전송 25회",
            channelAudioDeliverySummary(channel),
        )
    }

    @Test
    fun saturatedListenerRemainsVisibleEvenAfterAWebsocketSend() {
        val channel = BroadcastChannelSnapshot(
            channelId = "zh",
            languageTag = "zh",
            displayName = "中文",
            listenerCount = 1,
            listenerDroppedFrames = 7L,
            lastSynthesizedSequence = 12L,
            lastPublishedSequence = 12L,
            publishedFrameCount = 40L,
            webSocketDeliveredFrameCount = 9L,
            lastWebSocketDeliveredSequence = 12L,
        )

        val status = synthesisWorkerStatus(channel, ttsReady = true)

        assertEquals("일부 웹 전송 누락", status.state)
        assertEquals(OperatorStatusTone.WARNING, status.tone)
    }
}
