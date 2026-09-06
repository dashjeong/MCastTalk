package app.guidecast.provider.mlkit.translation

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeOperationCoordinatorTest {
    @Test
    fun callerTimeoutDoesNotReleaseNativeGateBeforeUnderlyingTaskCompletes() = runBlocking {
        val coordinator = NativeOperationCoordinator()
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = AtomicBoolean(false)

        val firstCaller = launch {
            runCatching {
                withTimeout(50L) {
                    coordinator.run {
                        firstStarted.complete(Unit)
                        releaseFirst.await()
                    }
                }
            }
        }
        firstStarted.await()
        firstCaller.join()

        val secondCaller = launch {
            coordinator.run { secondEntered.set(true) }
        }
        delay(50L)
        assertFalse(secondEntered.get())

        releaseFirst.complete(Unit)
        withTimeout(1_000L) { secondCaller.join() }
        assertTrue(secondEntered.get())
        coordinator.close()
    }

    @Test
    fun cancelledCallerReceivesNativeFinishedOnlyAfterUnderlyingTaskReturns() = runBlocking {
        val coordinator = NativeOperationCoordinator(maxInFlightOperations = 1)
        val nativeStarted = CompletableDeferred<Unit>()
        val releaseNative = CompletableDeferred<Unit>()
        val nativeFinished = AtomicBoolean(false)

        val caller = launch {
            coordinator.run(onNativeFinished = { nativeFinished.set(true) }) {
                nativeStarted.complete(Unit)
                releaseNative.await()
            }
        }
        nativeStarted.await()
        caller.cancelAndJoin()

        assertFalse(nativeFinished.get())
        releaseNative.complete(Unit)
        withTimeout(1_000L) {
            while (!nativeFinished.get()) yield()
        }
        assertTrue(nativeFinished.get())
        coordinator.close()
    }

    @Test
    fun cancelledRequestWaitingBehindNativeOperationIsSkipped() = runBlocking {
        val coordinator = NativeOperationCoordinator(maxInFlightOperations = 2)
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val queuedOperationEntered = AtomicBoolean(false)

        val firstCaller = launch {
            coordinator.run {
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
        }
        firstStarted.await()

        val queuedCaller = launch {
            coordinator.run { queuedOperationEntered.set(true) }
        }
        awaitInFlightCount(coordinator, expected = 2)
        queuedCaller.cancelAndJoin()

        releaseFirst.complete(Unit)
        withTimeout(1_000L) { firstCaller.join() }
        awaitInFlightCount(coordinator, expected = 0)

        assertFalse(queuedOperationEntered.get())
        coordinator.close()
    }

    @Test
    fun cancelledBacklogStaysBoundedAndDoesNotStarveNewRequest() = runBlocking {
        val maximumInFlight = 4
        val coordinator = NativeOperationCoordinator(maxInFlightOperations = maximumInFlight)
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val staleExecutions = AtomicInteger(0)

        val firstCaller = launch {
            coordinator.run {
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
        }
        firstStarted.await()

        val cancelledWaiters = List(100) {
            launch {
                coordinator.run { staleExecutions.incrementAndGet() }
            }
        }
        awaitInFlightCount(coordinator, expected = maximumInFlight)
        assertTrue(coordinator.inFlightOperationCount <= maximumInFlight)

        cancelledWaiters.forEach { it.cancel() }
        cancelledWaiters.forEach { it.join() }

        val newerOperationEntered = AtomicBoolean(false)
        val newerCaller = launch {
            coordinator.run { newerOperationEntered.set(true) }
        }
        releaseFirst.complete(Unit)

        withTimeout(2_000L) {
            firstCaller.join()
            newerCaller.join()
        }
        awaitInFlightCount(coordinator, expected = 0)

        assertEquals(0, staleExecutions.get())
        assertTrue(newerOperationEntered.get())
        coordinator.close()
    }

    private suspend fun awaitInFlightCount(
        coordinator: NativeOperationCoordinator,
        expected: Int,
    ) {
        withTimeout(1_000L) {
            while (coordinator.inFlightOperationCount != expected) {
                yield()
            }
        }
        // Give a wrongly scheduled operation a chance to surface instead of only sampling the
        // counter during a transient state.
        delay(10L)
    }
}
