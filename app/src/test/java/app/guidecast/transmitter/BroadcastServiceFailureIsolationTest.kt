package app.guidecast.transmitter

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BroadcastServiceFailureIsolationTest {
    @Test
    fun `stale preview playback failure after new session start does not terminate new session resources`() {
        val coordinator = TranslationSessionCoordinator()
        val session1 = coordinator.beginSession()
        var session1StateUpdated = false
        var session1ResourcesReleased = false

        // New session starts before session 1 failure callback arrives
        val session2 = coordinator.beginSession()
        var session2StateUpdated = false
        var session2ResourcesReleased = false

        // Stale failure callback from session 1 arrives
        val handledStale = coordinator.handleSessionFailure(
            sessionId = session1,
            onStateUpdate = { session1StateUpdated = true },
            onReleaseResources = { session1ResourcesReleased = true },
        )

        assertFalse("오래된 세션의 실패 콜백이 수락되었습니다.", handledStale)
        assertFalse("오래된 세션의 실패로 UI 상태가 변경되었습니다.", session1StateUpdated)
        assertFalse("오래된 세션의 실패로 새 세션의 자원이 해제되었습니다.", session1ResourcesReleased)
        assertTrue("새 세션의 유효성이 오래된 세션 실패로 취소되었습니다.", coordinator.isSessionCurrent(session2))

        // Stale release request with expectedSessionId from session 1 arrives
        var staleExplicitReleased = false
        val releasedStale = coordinator.releaseResources(expectedSessionId = session1) {
            staleExplicitReleased = true
        }
        assertFalse("오래된 세션 ID로 자원 해제가 승인되었습니다.", releasedStale)
        assertFalse("오래된 세션 ID로 자원이 실제로 해제되었습니다.", staleExplicitReleased)
        assertTrue("새 세션의 유효성이 유지되어야 합니다.", coordinator.isSessionCurrent(session2))

        // Legitimate failure for active session 2 arrives
        val handledCurrent = coordinator.handleSessionFailure(
            sessionId = session2,
            onStateUpdate = { session2StateUpdated = true },
            onReleaseResources = { session2ResourcesReleased = true },
        )

        assertTrue("현재 세션의 실패가 정상 처리되어야 합니다.", handledCurrent)
        assertTrue("현재 세션의 UI 상태가 변경되어야 합니다.", session2StateUpdated)
        assertTrue("현재 세션의 자원이 정상 해제되어야 합니다.", session2ResourcesReleased)
        assertFalse("실패 처리 후 이전 세션 ID는 더 이상 유효하지 않아야 합니다.", coordinator.isSessionCurrent(session2))
    }

    @Test
    fun `session failure invalidates generation and active before resource cleanup begins`() {
        val coordinator = TranslationSessionCoordinator()
        val session = coordinator.beginSession()
        var sessionCurrentDuringCleanup: Boolean? = null
        var sessionActiveDuringCleanup: Boolean? = null

        val handled = coordinator.handleSessionFailure(
            sessionId = session,
            onStateUpdate = {
                // State update happens before generation increment
            },
            onReleaseResources = {
                // Async callbacks firing during cleanup must see session as already invalidated
                sessionCurrentDuringCleanup = coordinator.isSessionCurrent(session)
                sessionActiveDuringCleanup = coordinator.isSessionActive()
            },
        )

        assertTrue(handled)
        assertEquals("정리 작업 중 비동기 콜백에 세션이 유효한 것으로 노출되지 않아야 합니다.", false, sessionCurrentDuringCleanup)
        assertEquals("정리 작업 중 세션이 활성 상태로 노출되지 않아야 합니다.", false, sessionActiveDuringCleanup)
        assertFalse(coordinator.isSessionCurrent(session))
        assertFalse(coordinator.isSessionActive())
    }

    @Test
    fun `cleanup exception during session failure does not leak active session state`() {
        val coordinator = TranslationSessionCoordinator()
        val session = coordinator.beginSession()

        val failure = runCatching {
            coordinator.handleSessionFailure(
                sessionId = session,
                onStateUpdate = { /* state updated */ },
                onReleaseResources = { error("simulated cleanup error") },
            )
        }.exceptionOrNull()

        assertNotNull("cleanup 예외가 정상 전달되어야 합니다.", failure)
        assertEquals("simulated cleanup error", failure?.message)
        assertFalse("cleanup 예외 발생 후에도 실패 세션이 유효 상태로 남지 않아야 합니다.", coordinator.isSessionCurrent(session))
        assertFalse("cleanup 예외 발생 후에도 세션이 활성 상태로 남지 않아야 합니다.", coordinator.isSessionActive())
    }

    @Test
    fun `server owner retains exact handle after two stop failures and recovers on retry`() {
        val serverHandle = Any()
        var stopAttempts = 0

        val first = closeWithRetryAndRetain(serverHandle, attempts = 2) {
            stopAttempts += 1
            error("injected Ktor stop failure $stopAttempts")
        }

        assertSame(serverHandle, first.retained)
        assertNotNull(first.failure)
        assertEquals(2, stopAttempts)

        val recovered = closeWithRetryAndRetain(first.retained, attempts = 2) {
            stopAttempts += 1
            // The retryable RunningGuideCastServer succeeds on this third underlying stop call.
        }

        assertNull(recovered.retained)
        assertNull(recovered.failure)
        assertEquals(3, stopAttempts)
    }

    @Test
    fun `uncaught support child failure is reported while sibling and supervisor remain active`() =
        runBlocking {
            val reported = CompletableDeferred<Throwable>()
            val siblingCompleted = AtomicInteger(0)
            val siblingRelease = CompletableDeferred<Unit>()
            val parent = SupervisorJob()
            val scope = CoroutineScope(
                parent + Dispatchers.Default + failureIsolatingServiceExceptionHandler {
                    reported.complete(it)
                },
            )
            try {
                val sibling = scope.launch {
                    siblingRelease.await()
                    siblingCompleted.incrementAndGet()
                }
                val failed = scope.launch { error("isolated model cleanup failed") }

                failed.join()
                val error = withTimeout(1_000L) { reported.await() }
                assertEquals("isolated model cleanup failed", error.message)
                assertTrue("지원 작업 supervisor가 함께 취소됐습니다.", parent.isActive)
                assertTrue("형제 언어 지원 작업이 함께 취소됐습니다.", sibling.isActive)

                siblingRelease.complete(Unit)
                sibling.join()
                assertEquals(1, siblingCompleted.get())
            } finally {
                scope.cancel()
            }
        }
}
