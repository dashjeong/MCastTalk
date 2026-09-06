package app.guidecast.transmitter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SevenChannelAdmissionTest {
    @Test fun expandedChannelsKeepBoundedColdLoadAdmission() {
        for (count in 1..7) {
            assertEquals(count, translationSupportPreparationParallelism(false, count))
            assertEquals(1, translationSupportPreparationParallelism(true, count))
            assertTrue(translationSupportReloadParallelism(false, count) in 1..count)
            assertEquals(1, translationSupportReloadParallelism(true, count))
            assertTrue(shouldSerializeNativeColdLoads(true, count, true))
            if (count > 1) assertTrue(shouldSerializeNativeColdLoads(false, count, true))
        }
    }
}
