package app.guidecast.transmitter

import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RecognitionSequenceMapperTest {
    @Test
    fun `partials and final preserve one provider utterance identity`() {
        val mapper = RecognitionSequenceMapper(AtomicLong(10))
        val partial = mapper.map(7)
        val revisedPartial = mapper.map(7)
        val final = mapper.map(7)

        assertEquals(10L, partial)
        assertEquals(partial, revisedPartial)
        assertEquals(partial, final)
    }

    @Test
    fun `interleaved provider lines remain independent and reuse gets a new id after final`() {
        val mapper = RecognitionSequenceMapper(AtomicLong(0))
        val first = mapper.map(1)
        val second = mapper.map(2)
        assertNotEquals(first, second)

        mapper.complete(1)
        val reusedProviderId = mapper.map(1)

        assertNotEquals(first, reusedProviderId)
        assertEquals(second, mapper.map(2))
    }
}
