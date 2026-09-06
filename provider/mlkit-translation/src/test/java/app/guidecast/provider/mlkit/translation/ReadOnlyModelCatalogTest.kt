package app.guidecast.provider.mlkit.translation

import com.google.android.gms.tasks.TaskCompletionSource
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ReadOnlyModelCatalogTest {
    @Test fun neverCompletingCatalogTimesOutAndDoesNotBlockNativeLane() = runBlocking {
        val task = TaskCompletionSource<Set<String>>()
        val lane = NativeOperationCoordinator()
        try {
            assertTrue(runCatching { awaitReadOnlyModelCatalog(task.task, 30) }
                .exceptionOrNull() is TimeoutCancellationException)
            assertEquals(0, lane.inFlightOperationCount)
            assertEquals("ready", withTimeout(500) { lane.run { "ready" } })
            task.setResult(setOf("ko", "en")) // Late callback must not resume cancelled continuation.
            assertEquals(setOf("ko", "en"), awaitReadOnlyModelCatalog(task.task, 500))
        } finally { lane.close() }
    }

    @Test fun failureAndEmptyFreshInstallAreDifferent() = runBlocking {
        val empty = TaskCompletionSource<Set<String>>()
        empty.setResult(emptySet())
        assertTrue(awaitReadOnlyModelCatalog(empty.task, 500).isEmpty())
        val failed = TaskCompletionSource<Set<String>>()
        failed.setException(IllegalStateException("catalog offline"))
        assertEquals("catalog offline", runCatching {
            awaitReadOnlyModelCatalog(failed.task, 500)
        }.exceptionOrNull()?.message)
    }
}
