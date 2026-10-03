package app.guidecast.transmitter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

class DomainCorpusFormatTest {

    @Test
    fun parseValidTsvProducesExpectedPairs() {
        val content = "오늘 회의를 시작합니다\tLet's begin today's meeting.\n진행 상황을 공유해 주세요\tPlease share the progress update.\n감사합니다\tThank you.\n"

        val result = DomainCorpusFormat.parseAndValidate(ByteArrayInputStream(content.toByteArray(StandardCharsets.UTF_8)))
        assertTrue("Result should be Success", result is DomainCorpusFormat.ParseResult.Success)
        val success = result as DomainCorpusFormat.ParseResult.Success
        assertEquals(3, success.pairs.size)
        assertEquals("오늘 회의를 시작합니다", success.pairs[0].sourceText)
        assertEquals("Let's begin today's meeting.", success.pairs[0].targetText)
        assertEquals("진행 상황을 공유해 주세요", success.pairs[1].sourceText)
        assertEquals("감사합니다", success.pairs[2].sourceText)
    }

    @Test
    fun intermediateBlankLineFailsWithLineNumber() {
        val content = "회의 시작\tMeeting start\n\n회의 종료\tMeeting end\n"
        val result = DomainCorpusFormat.parseAndValidate(ByteArrayInputStream(content.toByteArray(StandardCharsets.UTF_8)))
        assertTrue(result is DomainCorpusFormat.ParseResult.Error)
        val error = result as DomainCorpusFormat.ParseResult.Error
        assertEquals("Line 2 is blank and must report error at line 2", 2, error.lineNumber)
        assertTrue(error.reason.contains("빈 줄"))
    }

    @Test
    fun duplicateSourceWithIdenticalTranslationIsDeduplicated() {
        val content = "진행 상황 공유\tSharing progress\n진행 상황 공유\tSharing progress\n"

        val result = DomainCorpusFormat.parseAndValidate(ByteArrayInputStream(content.toByteArray(StandardCharsets.UTF_8)))
        assertTrue(result is DomainCorpusFormat.ParseResult.Success)
        val success = result as DomainCorpusFormat.ParseResult.Success
        assertEquals("Identical duplicate should be deduplicated to 1 pair", 1, success.pairs.size)
    }

    @Test
    fun duplicateSourceWithConflictingTranslationReturnsErrorWithLineNumber() {
        val content = "회의 시작\tMeeting start\n일정 확인\tCheck schedule\n회의 시작\tStart the meeting\n"

        val result = DomainCorpusFormat.parseAndValidate(ByteArrayInputStream(content.toByteArray(StandardCharsets.UTF_8)))
        assertTrue(result is DomainCorpusFormat.ParseResult.Error)
        val error = result as DomainCorpusFormat.ParseResult.Error
        assertEquals("Conflicting translation error should report line 3", 3, error.lineNumber)
        assertTrue(error.reason.contains("서로 다른 번역문") || error.reason.contains("동일한 원문"))
    }

    @Test
    fun lineWithoutTabSeparatorFailsWithLineNumber() {
        val content = "유효한 원문\tValid translation\n탭이 없는 잘못된 행입니다\n"

        val result = DomainCorpusFormat.parseAndValidate(ByteArrayInputStream(content.toByteArray(StandardCharsets.UTF_8)))
        assertTrue(result is DomainCorpusFormat.ParseResult.Error)
        val error = result as DomainCorpusFormat.ParseResult.Error
        assertEquals(2, error.lineNumber)
        assertTrue(error.reason.contains("탭") || error.reason.contains("열 형식"))
    }

    @Test
    fun lineWithMoreThanTwoColumnsFailsWithLineNumber() {
        val content = "원문\t번역1\t번역2\n"
        val result = DomainCorpusFormat.parseAndValidate(ByteArrayInputStream(content.toByteArray(StandardCharsets.UTF_8)))
        assertTrue(result is DomainCorpusFormat.ParseResult.Error)
        val error = result as DomainCorpusFormat.ParseResult.Error
        assertEquals(1, error.lineNumber)
    }

    @Test
    fun emptySourceOrTranslationFailsWithLineNumber() {
        val emptySource = "\t번역만 존재\n"
        val res1 = DomainCorpusFormat.parseAndValidate(ByteArrayInputStream(emptySource.toByteArray(StandardCharsets.UTF_8)))
        assertTrue(res1 is DomainCorpusFormat.ParseResult.Error)
        assertEquals(1, (res1 as DomainCorpusFormat.ParseResult.Error).lineNumber)

        val emptyTarget = "원문만 존재\t\n"
        val res2 = DomainCorpusFormat.parseAndValidate(ByteArrayInputStream(emptyTarget.toByteArray(StandardCharsets.UTF_8)))
        assertTrue(res2 is DomainCorpusFormat.ParseResult.Error)
        assertEquals(1, (res2 as DomainCorpusFormat.ParseResult.Error).lineNumber)
    }

    @Test
    fun textExceedingMaxCharacterLimitFailsWithLineNumber() {
        val longText = "가".repeat(DomainCorpusFormat.MAX_TEXT_LENGTH + 1)
        val content = "$longText\t번역문\n"
        val result = DomainCorpusFormat.parseAndValidate(ByteArrayInputStream(content.toByteArray(StandardCharsets.UTF_8)))
        assertTrue(result is DomainCorpusFormat.ParseResult.Error)
        val error = result as DomainCorpusFormat.ParseResult.Error
        assertEquals(1, error.lineNumber)
        assertTrue(error.reason.contains("길이") || error.reason.contains("500"))
    }

    @Test
    fun controlCharactersInTextAreRejectedWithLineNumber() {
        val content = "안녕\u0007하세요\tHello\n"
        val result = DomainCorpusFormat.parseAndValidate(ByteArrayInputStream(content.toByteArray(StandardCharsets.UTF_8)))
        assertTrue(result is DomainCorpusFormat.ParseResult.Error)
        val error = result as DomainCorpusFormat.ParseResult.Error
        assertEquals(1, error.lineNumber)
        assertTrue(error.reason.contains("제어 문자"))
    }

    @Test
    fun templateTextIsNonEmptyAndParsesSuccessfully() {
        val template = DomainCorpusFormat.generateTemplateTxt()
        assertTrue(template.isNotBlank())
        val result = DomainCorpusFormat.parseAndValidate(ByteArrayInputStream(template.toByteArray(StandardCharsets.UTF_8)))
        assertTrue(result is DomainCorpusFormat.ParseResult.Success)
        val success = result as DomainCorpusFormat.ParseResult.Success
        assertTrue("Template should contain sample pairs", success.pairs.isNotEmpty())
    }

    @Test
    fun exceedsMaxPairLimitFails() {
        val sb = StringBuilder()
        for (i in 1..DomainCorpusFormat.MAX_PAIRS + 1) {
            sb.append("원문 $i\t번역 $i\n")
        }
        val result = DomainCorpusFormat.parseAndValidate(ByteArrayInputStream(sb.toString().toByteArray(StandardCharsets.UTF_8)))
        assertTrue(result is DomainCorpusFormat.ParseResult.Error)
        val error = result as DomainCorpusFormat.ParseResult.Error
        assertTrue(error.reason.contains("10,000") || error.reason.contains("초과"))
    }

    @Test
    fun consecutiveNewlinesDosAttemptFailsEarlyWithoutMemorySpike() {
        // Simulates repeated newlines attack: line 1 blank, line 2 blank
        val newlines = "\n\n\n\n\n"
        val result = DomainCorpusFormat.parseAndValidate(ByteArrayInputStream(newlines.toByteArray(StandardCharsets.UTF_8)))
        assertTrue(result is DomainCorpusFormat.ParseResult.Error)
        val error = result as DomainCorpusFormat.ParseResult.Error
        assertEquals(1, error.lineNumber)
        assertTrue(error.reason.contains("빈 줄"))
    }

    @Test
    fun validateMetadataRejectsControlCharactersAndEnforcesLimits() {
        // Name with control char
        val badName = "회의\u0000도메인"
        assertNotNull(DomainCorpusFormat.validateMetadata(badName, "정상 설명"))

        // Desc with control char
        val badDesc = "설명\u001F제어문자"
        assertNotNull(DomainCorpusFormat.validateMetadata("정상 이름", badDesc))

        // Empty name
        assertNotNull(DomainCorpusFormat.validateMetadata("   ", "설명"))

        // Overlength name (> 80)
        assertNotNull(DomainCorpusFormat.validateMetadata("A".repeat(81), "설명"))

        // Overlength desc (> 400)
        assertNotNull(DomainCorpusFormat.validateMetadata("이름", "B".repeat(401)))

        // Valid name and description
        val validErr = DomainCorpusFormat.validateMetadata("회의·업무 대화", "비즈니스 미팅 번역 코퍼스")
        assertEquals(null, validErr)
    }
}
