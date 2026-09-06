package app.guidecast.provider.mlkit.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MlKitSourceSessionCoordinatorTest {
    @Test
    fun switchingSourceInvalidatesEveryEngineCapturedForPriorSource() {
        val coordinator = MlKitSourceSessionCoordinator("ko")
        val koreanSession = coordinator.capture()

        val englishSession = coordinator.switchTo("en")

        assertFalse(coordinator.isCurrent(koreanSession))
        assertTrue(coordinator.isCurrent(englishSession))
        assertEquals("en", englishSession.languageTag)
        assertTrue(englishSession.generation > koreanSession.generation)
    }

    @Test
    fun selectingSameSourceDoesNotInvalidatePreparedWorkers() {
        val coordinator = MlKitSourceSessionCoordinator("ja")
        val preparedSession = coordinator.capture()

        val unchanged = coordinator.switchTo("ja")

        assertEquals(preparedSession, unchanged)
        assertTrue(coordinator.isCurrent(preparedSession))
    }
}
