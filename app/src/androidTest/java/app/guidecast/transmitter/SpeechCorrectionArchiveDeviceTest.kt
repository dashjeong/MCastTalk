package app.guidecast.transmitter

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream

@RunWith(AndroidJUnit4::class)
class SpeechCorrectionArchiveDeviceTest {
    private val entry = SpeechCorrectionEntry(1, "현장", "ko", "디엠지 안내", "디엠지 안내입니다",
        "디엠지", true, "private-session", 1)
    private fun decode(json: String) = SpeechCorrectionArchive.decode(json.byteInputStream(), "가져온 현장")
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Invalid archive was accepted") } catch (expected: Exception) { /* expected */ }
    }

    @Test fun roundTripPreservesUnicodeButDoesNotExportSourceIdentity() {
        val encoded = SpeechCorrectionArchive.encode(listOf(entry))
        assertFalse(encoded.contains("private-session"))
        val imported = decode(encoded).single()
        assertEquals("가져온 현장", imported.profile)
        assertEquals(entry.recognizedText, imported.recognizedText)
        assertEquals(entry.correctedText, imported.correctedText)
        assertEquals(entry.hint, imported.hint)
        assertNull(imported.sourceKey)
    }

    @Test fun duplicateSchemaUnknownNestedPayloadAndTrailingDataAreRejected() {
        val valid = SpeechCorrectionArchive.encode(listOf(entry))
        rejected { decode(valid.replace("\"schema\":1", "\"schema\":1,\"schema\":1")) }
        rejected { decode(valid.replace("\"schema\":1", "\"nested\":[[[[[1]]]]],\"schema\":1")) }
        rejected { decode(valid + "{}") }
        rejected { decode(valid.replace("\"enabled\":true", "\"enabled\":\"true\"")) }
    }

    @Test fun invalidUtf8OversizedAndInvalidSemanticRecordsAreRejected() {
        rejected { SpeechCorrectionArchive.decode(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28)), "기본") }
        rejected { decode(" ".repeat(SpeechCorrectionArchive.MAX_BYTES + 1)) }
        rejected { decode(SpeechCorrectionArchive.encode(listOf(entry)).replace("디엠지 안내입니다", "")) }
        rejected { decode(SpeechCorrectionArchive.encode(listOf(entry)).replace("\"language\":\"ko\"", "\"language\":\"ko_XX\"")) }
    }
}
