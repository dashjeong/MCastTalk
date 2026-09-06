package app.guidecast.provider.android.stt

import android.speech.SpeechRecognizer
import app.guidecast.core.translation.SpeechRecognitionConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AndroidSpeechBiasingPolicyTest {
    @Test
    fun `empty hints never produce an intent extra`() {
        val policy = AndroidSpeechBiasingPolicy()

        assertNull(policy.intentExtra(SpeechRecognitionConfig("ko-KR"), 33))
    }

    @Test
    fun `API 33 produces normalized biasing strings but older APIs do not`() {
        val config = SpeechRecognitionConfig("ko-KR", biasingPhrases = listOf("Cafe\u0301"))
        val policy = AndroidSpeechBiasingPolicy()

        assertNull(policy.intentExtra(config, 32))
        assertEquals(arrayListOf("Caf\u00e9"), policy.intentExtra(config, 33))
    }

    @Test
    fun `intent extra is a request snapshot`() {
        val phrases = mutableListOf("도라산")
        val config = SpeechRecognitionConfig("ko-KR", biasingPhrases = phrases)

        val extra = AndroidSpeechBiasingPolicy().intentExtra(config, 33)
        phrases[0] = "changed later"

        assertEquals(arrayListOf("도라산"), extra)
    }

    @Test
    fun `invalid cached hints fail open and remain disabled`() {
        val phrases = mutableListOf("도라산")
        val config = SpeechRecognitionConfig("ko-KR", biasingPhrases = phrases)
        val policy = AndroidSpeechBiasingPolicy()
        phrases[0] = "line\nbreak"

        assertNull(policy.intentExtra(config, 33))
        phrases[0] = "valid again"
        assertNull(policy.intentExtra(config, 33))
    }

    @Test
    fun `synchronous hinted failure retries once without hints on a fresh recognizer`() {
        var nextRecognizer = 0
        val attempts = mutableListOf<Pair<Int, List<String>?>>()
        val destroyed = mutableListOf<Int>()
        var rejectionCount = 0

        val active = startRecognitionWithBiasFallback(
            biasingStrings = arrayListOf("판문점"),
            createRecognizer = { ++nextRecognizer },
            startListening = { recognizer, hints ->
                attempts += recognizer to hints?.toList()
                if (hints != null) throw IllegalArgumentException("OEM rejected optional extra")
            },
            destroyRecognizer = { destroyed += it },
            onHintedStartFailure = { rejectionCount++ },
        )

        assertEquals(2, active)
        assertEquals(listOf(1 to listOf("판문점"), 2 to null), attempts)
        assertEquals(listOf(1), destroyed)
        assertEquals(1, rejectionCount)
    }

    @Test
    fun `repeated fallback failure is propagated after both recognizers are destroyed`() {
        var nextRecognizer = 0
        val destroyed = mutableListOf<Int>()

        val error = assertThrows(IllegalStateException::class.java) {
            startRecognitionWithBiasFallback<Int>(
                biasingStrings = arrayListOf("판문점"),
                createRecognizer = { ++nextRecognizer },
                startListening = { _, hints ->
                    if (hints != null) throw IllegalArgumentException("hint failure")
                    throw IllegalStateException("base failure")
                },
                destroyRecognizer = { destroyed += it },
                onHintedStartFailure = {},
            )
        }

        assertEquals("base failure", error.message)
        assertEquals(1, error.suppressed.size)
        assertEquals(listOf(1, 2), destroyed)
    }

    @Test
    fun `base request synchronous failure is not retried`() {
        var createCount = 0
        var rejectionCount = 0

        assertThrows(IllegalStateException::class.java) {
            startRecognitionWithBiasFallback(
                biasingStrings = null,
                createRecognizer = { ++createCount },
                startListening = { _, _ -> throw IllegalStateException("base failure") },
                destroyRecognizer = {},
                onHintedStartFailure = { rejectionCount++ },
            )
        }

        assertEquals(1, createCount)
        assertEquals(0, rejectionCount)
    }

    @Test
    fun `hint rejection keeps later recognizer restarts on the base request`() {
        val config = SpeechRecognitionConfig("ko-KR", biasingPhrases = listOf("도라산"))
        val policy = AndroidSpeechBiasingPolicy()
        val firstExtra = policy.intentExtra(config, 33)

        policy.onSynchronousHintedStartFailure()

        assertEquals(arrayListOf("도라산"), firstExtra)
        assertNull(policy.intentExtra(config, 33))
    }

    @Test
    fun `async client error from hinted request disables hints on later attempts`() {
        val config = SpeechRecognitionConfig("ko-KR", biasingPhrases = listOf("도라산"))
        val policy = AndroidSpeechBiasingPolicy()
        assertEquals(arrayListOf("도라산"), policy.intentExtra(config, 33))

        policy.onRecognitionError(
            errorCode = SpeechRecognizer.ERROR_CLIENT,
            requestHadHints = true,
        )

        assertNull(policy.intentExtra(config, 33))
    }

    @Test
    fun `ordinary recognition errors do not disable future hints`() {
        val config = SpeechRecognitionConfig("ko-KR", biasingPhrases = listOf("도라산"))
        listOf(
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            SpeechRecognizer.ERROR_NETWORK,
        ).forEach { errorCode ->
            val policy = AndroidSpeechBiasingPolicy()

            policy.onRecognitionError(errorCode = errorCode, requestHadHints = true)

            assertEquals(arrayListOf("도라산"), policy.intentExtra(config, 33))
        }
    }

    @Test
    fun `client error from unhinted request does not blacklist hints`() {
        val config = SpeechRecognitionConfig("ko-KR", biasingPhrases = listOf("도라산"))
        val policy = AndroidSpeechBiasingPolicy()

        policy.onRecognitionError(
            errorCode = SpeechRecognizer.ERROR_CLIENT,
            requestHadHints = false,
        )

        assertEquals(arrayListOf("도라산"), policy.intentExtra(config, 33))
    }

    @Test
    fun `activating replacement suppresses every stale generation callback`() {
        val generations = RecognitionCallbackGeneration()
        val first = generations.activate()
        var firstCallbacks = 0
        var secondCallbacks = 0
        generations.dispatch(first) { firstCallbacks++ }

        val second = generations.activate()
        generations.dispatch(first) { firstCallbacks++ }
        generations.dispatch(second) { secondCallbacks++ }

        assertEquals(1, firstCallbacks)
        assertEquals(1, secondCallbacks)
    }

    @Test
    fun `generation is invalidated before synchronous destroy callback`() {
        val generations = RecognitionCallbackGeneration()
        val active = generations.activate()
        var leakedCallbacks = 0

        generations.invalidateBeforeDestroy(active) {
            generations.dispatch(active) { leakedCallbacks++ }
        }

        assertEquals(0, leakedCallbacks)
    }
}
