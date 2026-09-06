package app.guidecast.provider.android.tts

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.CoroutineContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidTtsInitializationAttemptTest {
    @Test
    fun `timeout before constructor handoff disposes late engine exactly once`() = runBlocking {
        val discarded = mutableListOf<Any>()
        val attempt = attempt(discarded)
        val engine = Any()

        attempt.abort(IllegalStateException("timeout"))
        attempt.reportStatus(SUCCESS)
        attempt.attach(engine)
        attempt.reportStatus(SUCCESS)

        assertTrue(runCatching { attempt.await() }.isFailure)
        assertEquals(listOf(engine), discarded)
    }

    @Test
    fun `close after attach but before callback disposes pending engine`() = runBlocking {
        val discarded = mutableListOf<Any>()
        val attempt = attempt(discarded)
        val engine = Any()

        attempt.attach(engine)
        attempt.abort(IllegalStateException("closed"))
        attempt.reportStatus(SUCCESS)

        assertTrue(runCatching { attempt.await() }.isFailure)
        assertEquals(listOf(engine), discarded)
    }

    @Test
    fun `failed callback disposes engine and preserves failure`() = runBlocking {
        val discarded = mutableListOf<Any>()
        val attempt = attempt(discarded)
        val engine = Any()

        attempt.reportStatus(FAILURE)
        attempt.attach(engine)

        val error = runCatching { attempt.await() }.exceptionOrNull()
        assertEquals("init failed: $FAILURE", error?.message)
        assertEquals(listOf(engine), discarded)
    }

    @Test
    fun `successful waiter takes ownership and late abort cannot dispose it`() = runBlocking {
        val discarded = mutableListOf<Any>()
        val attempt = attempt(discarded)
        val engine = Any()

        attempt.reportStatus(SUCCESS)
        attempt.attach(engine)
        assertSame(engine, attempt.await())

        attempt.abort(IllegalStateException("late close"))
        assertTrue(discarded.isEmpty())
    }

    @Test
    fun `cancellation after constructor handoff still disposes candidate`() = runBlocking {
        val discarded = mutableListOf<Any>()
        val attempt = attempt(discarded)
        val engine = Any()

        val error = runCatching {
            constructAndAwaitAndroidTtsInitialization(attempt) {
                attempt.attach(engine)
                throw CancellationException("dispatcher cancelled before returning")
            }
        }.exceptionOrNull()

        assertTrue(error is CancellationException)
        assertEquals(listOf(engine), discarded)
    }

    @Test
    fun `prompt cancellation after successful callback disposes unclaimed engine`() {
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val discarded = mutableListOf<Any>()
        val attempt = attempt(discarded)
        val engine = Any()
        val result = scope.async { attempt.await() }

        dispatcher.runNext()
        attempt.attach(engine)
        attempt.reportStatus(SUCCESS)
        result.cancel(CancellationException("timeout won before resumed waiter ran"))
        dispatcher.runAll()

        assertTrue(result.isCancelled)
        assertEquals(listOf(engine), discarded)
        scope.cancel()
    }

    private fun attempt(discarded: MutableList<Any>) = AndroidTtsInitializationAttempt(
        successfulStatus = SUCCESS,
        failure = { status -> IllegalStateException("init failed: $status") },
        discard = discarded::add,
    )

    private companion object {
        const val SUCCESS = 0
        const val FAILURE = -1
    }
}

private class QueuedDispatcher : CoroutineDispatcher() {
    private val tasks = ArrayDeque<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        tasks.addLast(block)
    }

    fun runNext() {
        tasks.removeFirst().run()
    }

    fun runAll() {
        while (tasks.isNotEmpty()) runNext()
    }
}
