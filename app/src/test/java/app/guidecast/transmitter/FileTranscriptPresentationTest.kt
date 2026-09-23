package app.guidecast.transmitter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileTranscriptPresentationTest {
    @Test fun android13RequiresManualLanguageBeforeConversionAndDoesNotLabelAutoAsSelected() {
        val android13 = FileTranslationUiState(automaticLanguageSupported = false, fileTranscriptionSupported = true)
        assertEquals("원문 언어를 선택하세요", fileSourceLanguageChoiceLabel(android13))
        org.junit.Assert.assertTrue(fileConversionPreflight(android13).orEmpty().contains("원문 언어를 직접 선택"))
        assertNull(fileConversionPreflight(android13.copy(sourceLanguageTag = "en-US")))
        assertEquals("영어", fileSourceLanguageChoiceLabel(android13.copy(sourceLanguageTag = "en-US")))
        assertNull(fileConversionPreflight(FileTranslationUiState(automaticLanguageSupported = true)))
    }

    @Test fun unavailableRecognizerAndActiveBroadcastExplanationsAreBothPreserved() {
        val state = FileTranslationUiState(automaticLanguageSupported = false, fileTranscriptionSupported = false,
            unavailableReason = "방송을 중지한 뒤 변환하세요.")
        val reason = fileConversionPreflight(state).orEmpty()
        org.junit.Assert.assertTrue(reason.contains("인식기가 없습니다"))
        org.junit.Assert.assertTrue(reason.contains("방송을 중지"))
        org.junit.Assert.assertTrue(reason.contains("저장된 스크립트 재생"))
    }

    @Test fun manualAppRecognitionDoesNotRequirePlatformAutoLanguageOrAndroid13Gate() {
        val noPlatform = FileTranslationUiState(automaticLanguageSupported = false, fileTranscriptionSupported = true,
            automaticLanguageUnavailableReason = "기기의 자동 파일 음성 인식을 사용할 수 없습니다.")
        org.junit.Assert.assertTrue(fileConversionPreflight(noPlatform).orEmpty().contains("원문 언어를 직접 선택"))
        assertNull(fileConversionPreflight(noPlatform.copy(sourceLanguageTag = "ko-KR")))
        assertEquals("선택한 언어는 지원되지 않습니다.", fileConversionPreflight(noPlatform.copy(sourceLanguageTag = "fr-FR",
            sourceLanguageUnavailableReason = "선택한 언어는 지원되지 않습니다.")))
    }

    @Test fun wordHighlightMatchesCaseDifferencesWithoutLosingRepeatedWordPosition() {
        val segment = FileSpeechSegment(1, 0, 1_000, "Hello, HELLO!", "en", words = listOf(
            FileSpeechWord("hello", 0, null), FileSpeechWord("hello", 500, 900),
        ))
        assertEquals(0..4, currentFileWordRange(segment, 100))
        assertEquals(7..11, currentFileWordRange(segment, 500))
        assertNull(currentFileWordRange(segment, 900))
        assertEquals("Hello, HELLO!", segment.text)
    }

    @Test
    fun wordHighlightPreservesRepeatedWordsPunctuationAndEngineTiming() {
        val segment = FileSpeechSegment(1L, 100L, 1_000L, "go, go!", "en-US", words = listOf(
            FileSpeechWord("go", 100L, null), FileSpeechWord("go", 500L, 900L),
        ), timingEstimated = false)
        assertEquals(0..1, currentFileWordRange(segment, 499L))
        assertEquals(4..5, currentFileWordRange(segment, 500L))
        assertNull(currentFileWordRange(segment, 99L))
        assertNull(currentFileWordRange(segment, 950L))
    }

    @Test
    fun absentOrUnmatchedWordTimingNeverFabricatesAHighlight() {
        val segment = FileSpeechSegment(1L, 0L, 1_000L, "actual source", "en-US")
        assertNull(currentFileWordRange(segment, 500L))
        assertNull(currentFileWordRange(segment.copy(words = listOf(FileSpeechWord("different", 0L))), 500L))
    }

    @Test
    fun segmentBoundaryAndSilenceGapDoNotKeepPreviousSentenceHighlighted() {
        val segments = listOf(FileSpeechSegment(1L, 0L, 500L, "first", "en-US"),
            FileSpeechSegment(2L, 700L, 1_000L, "second", "en-US"))
        assertEquals(0, activeFileSpeechSegment(segments, 499L))
        assertEquals(-1, activeFileSpeechSegment(segments, 500L))
        assertEquals(1, activeFileSpeechSegment(segments, 700L))
        assertEquals(-1, activeFileSpeechSegment(segments, 1_000L))
    }

    @Test
    fun fileDurationSupportsEightHoursWithoutMinuteOverflow() {
        assertEquals("8:00:01", formatFilePosition(28_801_000L))
        assertEquals("0:00", formatFilePosition(-1L))
        assertEquals("영어", fileLanguageLabel("en-GB"))
    }

    @Test
    fun retryOffersIncompleteItemsAndDoesNotTreatSavedOrRunningFilesAsFailures() {
        assertEquals(true, fileConversionCanRetry(FileConversionStatus.PARTIAL))
        assertEquals(true, fileConversionCanRetry(FileConversionStatus.FAILED))
        assertEquals(true, fileConversionCanRetry(FileConversionStatus.CANCELLED))
        assertEquals(false, fileConversionCanRetry(FileConversionStatus.SAVED))
        assertEquals(false, fileConversionCanRetry(FileConversionStatus.CONVERTING))
    }
}
