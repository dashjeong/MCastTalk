package app.guidecast.provider.mlkit.translation

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelArtifactDownloadCoordinatorTest {
    @Test fun installedArtifactSurvivesLateTaskFailureOrRemoteCancellation() = runBlocking {
        for (failure in listOf(IllegalStateException("late callback"), CancellationException("remote cancelled"))) {
            val downloads = ModelArtifactDownloadCoordinator()
            val installed = AtomicBoolean(false)
            try {
                downloads.download("ko", isArtifactReady = installed::get) {
                    installed.set(true)
                    throw failure
                }
                assertTrue(installed.get())
            } finally { downloads.close() }
        }
    }

    @Test fun installedArtifactUnblocksPreparationWithoutReleasingUnfinishedDownloadOwnership() = runBlocking {
        val downloads = ModelArtifactDownloadCoordinator()
        val installed = AtomicBoolean(false)
        val started = CompletableDeferred<Unit>()
        val terminal = CompletableDeferred<Unit>()
        val submissions = AtomicInteger()
        try {
            val preparation = async {
                downloads.download("ko", timeoutMillis = 1_000,
                    isArtifactReady = installed::get, readinessPollMillis = 20) {
                    submissions.incrementAndGet()
                    started.complete(Unit)
                    terminal.await()
                }
            }
            withTimeout(1_000) { started.await() }
            installed.set(true)
            withTimeout(500) { preparation.await() }
            downloads.download("ko", timeoutMillis = 100, isArtifactReady = installed::get) {
                submissions.incrementAndGet()
            }
            assertEquals(1, submissions.get())
            assertFalse(terminal.isCompleted)
            var removed = false
            val removal = runCatching {
                downloads.withDownloadFinishedForRemoval("ko", 50) { removed = true }
            }
            assertTrue(removal.isFailure)
            assertFalse(removed)
        } finally {
            terminal.complete(Unit)
            downloads.close()
        }
    }

    @Test
    fun hungDownloadTimesOutWithoutBlockingSiblingAndRetryJoinsRealTask() = runBlocking {
        val downloads = ModelArtifactDownloadCoordinator()
        val started = CompletableDeferred<Unit>()
        val terminal = CompletableDeferred<Unit>()
        val submissions = AtomicInteger()
        try {
            val first = async {
                runCatching {
                    downloads.download("ko", timeoutMillis = 100) {
                        submissions.incrementAndGet()
                        started.complete(Unit)
                        terminal.await()
                    }
                }.exceptionOrNull()
            }
            withTimeout(1_000) { started.await() }
            val error = withTimeout(1_000) { first.await() }
            assertTrue(error is IllegalStateException)
            assertTrue(error?.message.orEmpty().contains("ko 번역 모델 다운로드 응답 시간"))
            assertFalse(terminal.isCompleted)

            var siblingReady = false
            downloads.download("en", timeoutMillis = 1_000) { siblingReady = true }
            assertTrue(siblingReady)

            val retry = launch {
                downloads.download("ko", timeoutMillis = 1_000) { submissions.incrementAndGet() }
            }
            delay(30)
            assertFalse(retry.isCompleted)
            assertEquals(1, submissions.get())
            terminal.complete(Unit)
            withTimeout(1_000) { retry.join() }
            assertEquals(1, submissions.get())
        } finally {
            terminal.complete(Unit)
            downloads.close()
        }
    }

    @Test
    fun callerCancellationPreservesDownloadButDoesNotBecomeAStageFailure() = runBlocking {
        val downloads = ModelArtifactDownloadCoordinator()
        val started = CompletableDeferred<Unit>()
        val terminal = CompletableDeferred<Unit>()
        val caller = launch {
            downloads.download("ja", timeoutMillis = 1_000) {
                started.complete(Unit)
                terminal.await()
            }
        }
        try {
            withTimeout(1_000) { started.await() }
            caller.cancelAndJoin()
            assertTrue(caller.isCancelled)
            assertFalse(terminal.isCompleted)
        } finally {
            terminal.complete(Unit)
            downloads.close()
        }
    }

    @Test
    fun terminalDownloadFailureAndRemoteCancellationAllowFreshRetry() = runBlocking {
        val downloads = ModelArtifactDownloadCoordinator()
        try {
            val failed = runCatching {
                downloads.download("vi") { error("network failed") }
            }.exceptionOrNull()
            assertEquals("network failed", failed?.message)
            val cancelled = runCatching {
                downloads.download("vi") { throw CancellationException("remote task cancelled") }
            }.exceptionOrNull()
            assertTrue(cancelled is IllegalStateException)
            var retried = false
            downloads.download("vi") { retried = true }
            assertTrue(retried)
        } finally {
            downloads.close()
        }
    }

    @Test
    fun deletionTimeoutAndCancellationDoNotStartMutationOrStrandBoundary() = runBlocking {
        val downloads = ModelArtifactDownloadCoordinator()
        val started = CompletableDeferred<Unit>()
        val terminal = CompletableDeferred<Unit>()
        val mutations = AtomicInteger()
        val downloading = launch {
            downloads.download("zh", timeoutMillis = 2_000) {
                started.complete(Unit)
                terminal.await()
            }
        }
        try {
            withTimeout(1_000) { started.await() }
            val error = runCatching {
                downloads.withDownloadFinishedForRemoval("zh", timeoutMillis = 30) {
                    mutations.incrementAndGet()
                }
            }.exceptionOrNull()
            assertTrue(error is IllegalStateException)
            assertTrue(error?.message.orEmpty().contains("삭제하지 않았습니다"))
            assertEquals(0, mutations.get())

            val deletion = launch {
                downloads.withDownloadFinishedForRemoval("zh", timeoutMillis = 2_000) {
                    mutations.incrementAndGet()
                }
            }
            delay(30)
            deletion.cancelAndJoin()
            assertEquals(0, mutations.get())
            terminal.complete(Unit)
            withTimeout(1_000) { downloading.join() }
            downloads.withDownloadFinishedForRemoval("zh", timeoutMillis = 1_000) {
                mutations.incrementAndGet()
            }
            assertEquals(1, mutations.get())
        } finally {
            terminal.complete(Unit)
            downloads.close()
        }
    }

    @Test
    fun cancelledDeletionKeepsDownloadsExcludedUntilNativeTransactionCompletes() = runBlocking {
        val downloads = ModelArtifactDownloadCoordinator()
        val deletionStarted = CompletableDeferred<Unit>()
        val deletionTerminal = CompletableDeferred<Unit>()
        val replacementStarted = AtomicBoolean(false)
        val committed = AtomicBoolean(false)
        val deletion = launch {
            downloads.withDownloadFinishedForRemoval("en") {
                runReservedModelRemoval(
                    reserve = { Unit },
                    drain = {},
                    delete = {
                        deletionStarted.complete(Unit)
                        deletionTerminal.await()
                    },
                    complete = { committed.set(true) },
                    recover = { error("Deletion must commit after its real terminal result") },
                )
            }
        }
        try {
            withTimeout(1_000) { deletionStarted.await() }
            deletion.cancel()
            val replacement = launch {
                downloads.download("en", timeoutMillis = 1_000) { replacementStarted.set(true) }
            }
            delay(30)
            assertFalse(replacementStarted.get())
            assertFalse(committed.get())
            deletionTerminal.complete(Unit)
            withTimeout(1_000) {
                deletion.join()
                replacement.join()
            }
            assertTrue(committed.get())
            assertTrue(replacementStarted.get())
        } finally {
            deletionTerminal.complete(Unit)
            downloads.close()
        }
    }

    @Test
    fun traditionalAndSimplifiedChineseUseOneArtifactKey() {
        assertEquals("zh", "zh".toMlKitLanguage())
        assertEquals("zh", "zh-TW".toMlKitLanguage())
    }

    @Test
    fun installedArtifactPreparesOfflineWithoutRequestingNetworkUpdate() = runBlocking {
        ensureModelArtifactDownloaded(
            isDownloaded = { true },
            download = { error("Installed models must not request network updates during preparation") },
        )
        var downloads = 0
        ensureModelArtifactDownloaded(isDownloaded = { false }, download = { downloads++ })
        assertEquals(1, downloads)
    }

    @Test
    fun failedOrStalledInstalledCheckNeverDownloadsOrPretendsReady() = runBlocking {
        val check = com.google.android.gms.tasks.TaskCompletionSource<Boolean>()
        var downloaded = false
        val timeout = runCatching {
            ensureModelArtifactDownloaded(
                isDownloaded = { awaitReadOnlyModelCatalog(check.task, 30) },
                download = { downloaded = true },
            )
        }.exceptionOrNull()
        assertTrue(timeout is kotlinx.coroutines.TimeoutCancellationException)
        assertFalse(downloaded)
        check.setException(IllegalStateException("model check failed"))
        val failure = runCatching {
            ensureModelArtifactDownloaded(
                isDownloaded = { awaitReadOnlyModelCatalog(check.task, 1_000) },
                download = { downloaded = true },
            )
        }.exceptionOrNull()
        assertEquals("model check failed", failure?.message)
        assertFalse(downloaded)
    }

    @Test
    fun englishIsBuiltInButTranslationStillRequiresTheOtherLanguageArtifact() {
        assertTrue(isMlKitTranslationPairReady("ko-KR", "en", setOf("ko")))
        assertTrue(isMlKitTranslationPairReady("en", "ja", setOf("ja")))
        assertFalse(isMlKitTranslationPairReady("ko-KR", "en", emptySet()))
        assertFalse(isMlKitTranslationPairReady("en", "ja", emptySet()))
        assertTrue(isMlKitTranslationPairReady("ko-KR", "zh-TW", setOf("ko", "zh")))
        assertFalse(isMlKitTranslationPairReady("ko-KR", "zh-TW", setOf("ko")))
    }
}
