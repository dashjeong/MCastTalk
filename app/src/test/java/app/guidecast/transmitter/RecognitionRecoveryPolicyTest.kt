package app.guidecast.transmitter

import android.speech.SpeechRecognizer
import app.guidecast.provider.android.stt.AndroidSpeechRecognitionException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionRecoveryPolicyTest {
    @Test
    fun samsungClientCancellationSwitchesToPreparedPcmRecognizer() {
        assertEquals(
            RecognitionRecoveryAction.SWITCH_BACKEND,
            recognitionRecoveryAction(
                error = AndroidSpeechRecognitionException(
                    SpeechRecognizer.ERROR_CLIENT,
                    "음성인식 요청이 취소되었습니다.",
                ),
                alternateBackendReady = true,
            ),
        )
    }

    @Test
    fun noMatchAndSpeechTimeoutAreNormalSessionRestarts() {
        listOf(
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        ).forEach { code ->
            assertEquals(
                RecognitionRecoveryAction.RESTART_SESSION,
                recognitionRecoveryAction(
                    error = AndroidSpeechRecognitionException(code, "session ended"),
                    alternateBackendReady = true,
                ),
            )
        }
    }

    @Test
    fun noMatchAndSpeechTimeoutUseNeutralListeningStatus() {
        listOf(
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        ).forEach { code ->
            assertEquals(
                "발화를 기다리는 중 · 문장 인식을 계속합니다.",
                recognitionRecoveryStatusMessage(
                    AndroidSpeechRecognitionException(code, "음성 입력 시간이 초과되었습니다."),
                ),
            )
        }
    }

    @Test
    fun actualRecognizerErrorsKeepTheirRecoveryDetail() {
        assertEquals(
            "음성인식 경로를 자동 복구했습니다: 마이크 PCM 전달 실패",
            recognitionRecoveryStatusMessage(
                AndroidSpeechRecognitionException(
                    SpeechRecognizer.ERROR_CLIENT,
                    "마이크 PCM 전달 실패",
                ),
            ),
        )
        assertEquals(
            "음성인식 경로를 자동 복구했습니다: worker disconnected",
            recognitionRecoveryStatusMessage(IllegalStateException("worker disconnected")),
        )
    }

    @Test
    fun missingLanguageFailsOnlyWhenNoIndependentRecognizerIsReady() {
        val error = AndroidSpeechRecognitionException(
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
            "language unavailable",
        )
        assertEquals(
            RecognitionRecoveryAction.FAIL,
            recognitionRecoveryAction(error, alternateBackendReady = false),
        )
        assertEquals(
            RecognitionRecoveryAction.SWITCH_BACKEND,
            recognitionRecoveryAction(error, alternateBackendReady = true),
        )
    }

    @Test
    fun nonResponsivePrimarySwitchesToPreparedIndependentRecognizer() {
        val error = IllegalStateException("Moonshine STT session-ready callback timed out")

        assertEquals(
            RecognitionRecoveryAction.SWITCH_BACKEND,
            recognitionRecoveryAction(error, alternateBackendReady = true),
        )
        assertEquals(
            RecognitionRecoveryAction.RESTART_SESSION,
            recognitionRecoveryAction(error, alternateBackendReady = false),
        )
    }

    @Test
    fun modernDeviceWithBothBackendsReadyPrefersAndroid() {
        assertEquals(
            RecognitionBackend.ANDROID,
            preferredKoreanRecognitionBackend(
                sdkInt = 34,
                androidReady = true,
                moonshineReady = true,
            ),
        )

        val decision = koreanRecognitionBackendPolicy(
            sdkInt = 34,
            androidReady = true,
            moonshineReady = true,
        )
        assertEquals(RecognitionBackend.ANDROID, decision.backend)
        assertTrue(decision.status.isReady)
        assertEquals(
            "Galaxy 한국어 오프라인 음성인식 준비됨 · 독립 PCM 대체 준비됨",
            decision.status.message,
        )
    }

    @Test
    fun modernDeviceWithAndroidUnavailableOrUnreadyFallsBackToMoonshine() {
        assertEquals(
            RecognitionBackend.MOONSHINE,
            preferredKoreanRecognitionBackend(
                sdkInt = 33,
                androidReady = false,
                moonshineReady = true,
            ),
        )

        val decision = koreanRecognitionBackendPolicy(
            sdkInt = 33,
            androidReady = false,
            moonshineReady = true,
            androidMessage = "한국어 오프라인 음성 모델 다운로드가 필요합니다.",
        )
        assertEquals(RecognitionBackend.MOONSHINE, decision.backend)
        assertTrue(decision.status.isReady)
        assertEquals(
            "독립 PCM 한국어 오프라인 음성인식 준비됨",
            decision.status.message,
        )
    }

    @Test
    fun api29DeviceAlwaysPrefersMoonshineRegardlessOfAndroidReadiness() {
        listOf(true, false).forEach { androidReady ->
            assertEquals(
                RecognitionBackend.MOONSHINE,
                preferredKoreanRecognitionBackend(
                    sdkInt = 29,
                    androidReady = androidReady,
                    moonshineReady = true,
                ),
            )

            val decision = koreanRecognitionBackendPolicy(
                sdkInt = 29,
                androidReady = androidReady,
                moonshineReady = true,
            )
            assertEquals(RecognitionBackend.MOONSHINE, decision.backend)
            assertTrue(decision.status.isReady)
            assertEquals(
                "독립 PCM 한국어 오프라인 음성인식 준비됨",
                decision.status.message,
            )
        }
    }

    @Test
    fun androidRuntimeErrorClientWithMoonshineReadySwitchesBackend() {
        assertEquals(
            RecognitionRecoveryAction.SWITCH_BACKEND,
            recognitionRecoveryAction(
                error = AndroidSpeechRecognitionException(
                    SpeechRecognizer.ERROR_CLIENT,
                    "SpeechRecognizer client error",
                ),
                alternateBackendReady = true,
            ),
        )
    }

    @Test
    fun noBackendReadyFailsWithAccurateStatus() {
        assertNull(
            preferredKoreanRecognitionBackend(
                sdkInt = 34,
                androidReady = false,
                moonshineReady = false,
            ),
        )
        assertNull(
            preferredKoreanRecognitionBackend(
                sdkInt = 29,
                androidReady = false,
                moonshineReady = false,
            ),
        )

        // Modern: accurate status reports primary (Android) failure reason
        val modernDecision = koreanRecognitionBackendPolicy(
            sdkInt = 34,
            androidReady = false,
            moonshineReady = false,
            androidMessage = "한국어 온디바이스 음성 모델이 설치되지 않았습니다.",
            moonshineMessage = "Moonshine 엔진을 초기화할 수 없습니다.",
        )
        assertNull(modernDecision.backend)
        assertFalse(modernDecision.status.isReady)
        assertEquals(
            "한국어 온디바이스 음성 모델이 설치되지 않았습니다.",
            modernDecision.status.message,
        )

        // API 29: accurate status reports Moonshine failure reason
        val api29Decision = koreanRecognitionBackendPolicy(
            sdkInt = 29,
            androidReady = false,
            moonshineReady = false,
            androidMessage = "온디바이스 음성인식을 지원하지 않는 API 버전입니다.",
            moonshineMessage = "Moonshine 한국어 모델 가중치 파일이 누락되었습니다.",
        )
        assertNull(api29Decision.backend)
        assertFalse(api29Decision.status.isReady)
        assertEquals(
            "Moonshine 한국어 모델 가중치 파일이 누락되었습니다.",
            api29Decision.status.message,
        )

        // Fallback default message when no specific reason was supplied
        val defaultDecision = koreanRecognitionBackendPolicy(
            sdkInt = 33,
            androidReady = false,
            moonshineReady = false,
        )
        assertNull(defaultDecision.backend)
        assertFalse(defaultDecision.status.isReady)
        assertEquals(
            "한국어 오프라인 음성 모델 준비가 필요합니다.",
            defaultDecision.status.message,
        )

        // Recovery action fails when no alternate backend is ready on fatal error
        val fatalError = AndroidSpeechRecognitionException(
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
            "언어팩 미지원",
        )
        assertEquals(
            RecognitionRecoveryAction.FAIL,
            recognitionRecoveryAction(fatalError, alternateBackendReady = false),
        )
    }
}
