package app.guidecast.transmitter

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceNotePresentationTest {
    @Test fun defaultViewKeepsUnknownFailuresEvenWhenTheyFollowSuccessfulRecording() {
        val success = "녹음과 2개 문장을 저장했습니다. 원음 대조·문장 수정·번역을 할 수 있습니다."
        val failure = "받아쓰기 연결이 종료됐습니다. 원음은 보존됩니다."
        assertEquals(listOf(failure), voiceNoteVisibleMessages(listOf(success, "$success\n$failure", failure), false))
        assertEquals(listOf(success, failure), voiceNoteVisibleMessages(listOf(success, "$success\n$failure"), true))
    }

    @Test fun permissionPreparationAndUnrecognizedMessagesRemainActionable() {
        val messages = listOf("마이크 권한을 허용한 뒤 다시 시작하세요.", "녹음 준비 중", "새로운 기기 오류")
        assertEquals(messages, voiceNoteVisibleMessages(messages, false))
    }
}
