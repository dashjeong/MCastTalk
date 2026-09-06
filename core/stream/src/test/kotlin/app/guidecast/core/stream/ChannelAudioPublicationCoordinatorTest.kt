package app.guidecast.core.stream

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelAudioPublicationCoordinatorTest {
    @Test
    fun `all-channel tone waits for active speech and then excludes every channel`() = runTest {
        val coordinator = ChannelAudioPublicationCoordinator(listOf("en", "ja"))
        val englishSpeech = coordinator.acquireChannel("en")
        val toneAcquired = CompletableDeferred<Unit>()
        val tone = async {
            requireNotNull(coordinator.acquireAllChannels(timeoutMillis = 1_000L)).also {
                toneAcquired.complete(Unit)
            }
        }
        runCurrent()

        assertFalse(toneAcquired.isCompleted)
        englishSpeech.close()
        advanceTimeBy(15L)
        runCurrent()
        assertTrue(toneAcquired.isCompleted)

        val japaneseSpeechAcquired = CompletableDeferred<Unit>()
        val japaneseSpeech = async {
            coordinator.acquireChannel("ja").also { japaneseSpeechAcquired.complete(Unit) }
        }
        runCurrent()
        assertFalse(japaneseSpeechAcquired.isCompleted)

        tone.await().close()
        runCurrent()
        assertTrue(japaneseSpeechAcquired.isCompleted)
        japaneseSpeech.await().close()
    }

    @Test
    fun `all-channel wait never retains a healthy partial lease and times out`() = runTest {
        val coordinator = ChannelAudioPublicationCoordinator(listOf("en", "ja"))
        val blockedJapaneseSpeech = coordinator.acquireChannel("ja")
        val tone = async { coordinator.acquireAllChannels(timeoutMillis = 100L) }
        runCurrent()

        val healthyEnglishSpeech = async { coordinator.acquireChannel("en") }
        runCurrent()
        assertTrue(
            "A waiting tone retained the earlier sorted English lock",
            healthyEnglishSpeech.isCompleted,
        )
        healthyEnglishSpeech.await().close()

        advanceTimeBy(100L)
        runCurrent()
        assertNull(tone.await())
        blockedJapaneseSpeech.close()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unknown channel cannot bypass publication ordering`() = runTest {
        ChannelAudioPublicationCoordinator(listOf("en")).acquireChannel("ja")
    }
}
