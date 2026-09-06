package app.guidecast.transmitter

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperatorVisualLanguagePolicyTest {
    private val source by lazy(::mainActivitySource)

    @Test
    fun instructorPinIsOptInWithoutChangingItsValidationOrTransport() {
        assertTrue(source.contains("var micPinEnabled by rememberSaveable { mutableStateOf(false) }"))
        assertTrue(source.contains("(!micPinEnabled || validMicPin)"))
        assertTrue(source.contains("micPin.takeIf { micPinEnabled }?.toCharArray()"))
        assertTrue(source.contains("브라우저 마이크 권한 단계는 HTTPS가 필요"))
    }

    @Test
    fun operatorUiDoesNotRegressToPrototypeLabelsOrEmojiControls() {
        listOf(
            "LIVE CONSOLE",
            "PREFLIGHT",
            "KOREAN INPUT",
            "PROCESSING",
            "FINAL OUTPUT",
            "OPEN SOURCE & TERMS",
            "OFFLINE NOTICE",
            "🎙",
            "🔊",
        ).forEach { banned ->
            assertFalse("운영 화면에 프로토타입 표현이 다시 들어왔습니다: $banned", source.contains(banned))
        }
    }

    @Test
    fun themeDefinesACompleteProductVisualSystem() {
        listOf(
            "typography = GuideCastTypography",
            "shapes = GuideCastShapes",
            "secondaryContainer =",
            "tertiaryContainer =",
            "outlineVariant =",
            "surfaceContainerLow =",
            "surfaceContainerHighest =",
        ).forEach { required ->
            assertTrue("제품 테마 토큰이 빠졌습니다: $required", source.contains(required))
        }
        assertFalse(
            "기본 Material 보라색으로 보였던 이전 primary container가 다시 들어왔습니다.",
            source.contains("0xFFD2EEE7"),
        )
    }

    @Test
    fun destructiveInputAndBroadcastActionsUseTheErrorHierarchy() {
        assertTrue(
            "중지·취소 조작에 공통 error-outline 스타일이 필요합니다.",
            source.windowed("destructiveOutlinedButtonColors".length)
                .count { it == "destructiveOutlinedButtonColors" } >= 5,
        )
    }

    @Test
    fun multilingualUiShowsCapacityIsolationAndPerChannelOperations() {
        listOf(
            "MAX_SIMULTANEOUS_TRANSLATION_LANGUAGES",
            "방송 시 원음 별도 제공",
            "한 채널의 오류는 다른 채널의 방송을 멈추지 않습니다.",
            "확정→첫 음성",
            "채널 QR",
            "translationProvider",
            "synthesisProvider",
            "번역 오류",
            "/복구",
        ).forEach { required ->
            assertTrue("다국어 운영 가시성 표현이 빠졌습니다: $required", source.contains(required))
        }
    }

    private fun mainActivitySource(): String {
        val candidates = listOf(
            File("src/main/java/app/guidecast/transmitter/MainActivity.kt"),
            File("app/src/main/java/app/guidecast/transmitter/MainActivity.kt"),
        )
        val file = candidates.firstOrNull(File::isFile)
            ?: error("MainActivity.kt를 찾을 수 없습니다: ${candidates.joinToString()}")
        return file.readText() + "\n" + File(file.parentFile, "GuideCastDesignSystem.kt").readText() +
            "\n" + File(file.parentFile, "OperatorWorkspace.kt").readText()
    }
}
