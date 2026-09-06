package app.guidecast.transmitter

import app.guidecast.core.translation.GlossaryTerm
import org.junit.Assert.*
import org.junit.Test

class GlossaryCsvTest {
    private val term = GlossaryTerm("ko", "en", "평화의 길", "DMZ Peace Trail", "peace road", "관광", "사용자")
    @Test fun signLayoutCandidatesBecomeSavableWithoutChangingWords() {
        val raw = "  Namdaemun District Tax \nOffice\r\n"
        val spoken = GlossaryCsv.spokenCandidate(raw)
        assertEquals("Namdaemun District Tax Office", spoken)
        assertEquals(spoken, GlossaryCsv.validate(listOf(term.copy(preferredTerm = spoken))).single().preferredTerm)
        assertEquals("九龙浦生活文化中心 （阿拉艺术村）", GlossaryCsv.spokenCandidate("九龙浦生活文化中心\n（阿拉艺术村）"))
        assertTrue(raw.contains('\n')) // the supplied string/provenance is never modified
    }
    @Test fun utf8BomCsvRoundTripKeepsQuotesCommasAndFormulaLikeData() {
        val rows = listOf(term, term.copy(sourceTerm = "수식", preferredTerm = "=1+1", origin = "'원본"),
            term.copy(sourceTerm = "이름", preferredTerm = "The \"Park\", Seoul", enabled = false))
        val csv = "\uFEFF" + GlossaryCsv.header + "\r\n" + rows.joinToString("\r\n", transform = GlossaryCsv::line)
        assertTrue(csv.contains("'=1+1"))
        assertEquals(rows, GlossaryCsv.read(csv.reader()))
    }
    @Test fun duplicateSameTermWithDifferentTranslationRejectsWholeFile() {
        assertThrows(IllegalArgumentException::class.java) { GlossaryCsv.validate(listOf(term, term.copy(preferredTerm = "different"))) }
        assertEquals(listOf(term), GlossaryCsv.validate(listOf(term, term)))
    }
    @Test fun invalidOrOversizeOrWrongHeaderRejected() {
        listOf("wrong\n", GlossaryCsv.header + "\n\"unfinished", GlossaryCsv.header + "\n" + "a".repeat(5000),
            GlossaryCsv.header + "\n\"ko\"bad,en,name,value,,,,1").forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { GlossaryCsv.read(bad.reader()) }
        }
        listOf(term.copy(sourceLanguage = "../../secret"), term.copy(preferredTerm = "bad\ncommand"),
            term.copy(sourceTerm = ""), term.copy(targetLanguage = "ko")).forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { GlossaryCsv.validate(listOf(bad)) }
        }
    }
    @Test fun emptyTemplateAndNoFinalNewlineAndDisabledRowsWork() {
        assertTrue(GlossaryCsv.read((GlossaryCsv.header + "\r\n").reader()).isEmpty())
        val disabled = term.copy(enabled = false)
        assertEquals(listOf(disabled), GlossaryCsv.read((GlossaryCsv.header + "\n" + GlossaryCsv.line(disabled)).reader()))
    }
}
