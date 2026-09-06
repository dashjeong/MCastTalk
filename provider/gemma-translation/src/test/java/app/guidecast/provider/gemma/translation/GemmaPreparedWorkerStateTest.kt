package app.guidecast.provider.gemma.translation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaPreparedWorkerStateTest {
    @Test
    fun liveBinderIsReusableOnlyAfterSuccessfulInferenceOnCurrentConnection() {
        val state = GemmaPreparedWorkerState()

        assertFalse(state.isReusable(binderAlive = true))
        state.markSuccessfulInference()
        assertTrue(state.isReusable(binderAlive = true))
        assertFalse(state.isReusable(binderAlive = false))
    }

    @Test
    fun disconnectOrFailedRequestInvalidatesSuccessfulInferenceProof() {
        val state = GemmaPreparedWorkerState()
        state.markSuccessfulInference()

        state.invalidate()

        assertFalse(state.isReusable(binderAlive = true))
    }
}
