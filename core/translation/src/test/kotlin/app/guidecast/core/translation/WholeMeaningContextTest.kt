package app.guidecast.core.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WholeMeaningContextTest {
    @Test
    fun `context includes whole recent units in their original order`() {
        assertEquals("촬영하면 안 됩니다 안전을 위해 기다리세요",
            wholeMeaningContext(listOf("촬영하면 안 됩니다", "안전을 위해 기다리세요")))
    }

    @Test
    fun `limit drops an older unit instead of cutting its qualification`() {
        assertEquals("가도 됩니다", wholeMeaningContext(listOf("촬영하면 안 됩니다", "가도 됩니다"), 10))
        assertNull(wholeMeaningContext(listOf("오래된 문맥", "사진을 촬영하면 절대로 안 됩니다"), 10))
    }

    @Test
    fun `exact bound allows whole unit but never takes a partial tail`() {
        assertEquals("가".repeat(400), wholeMeaningContext(listOf("가".repeat(400))))
        assertNull(wholeMeaningContext(listOf("가".repeat(401))))
        assertNull(wholeMeaningContext(emptyList()))
    }
}
