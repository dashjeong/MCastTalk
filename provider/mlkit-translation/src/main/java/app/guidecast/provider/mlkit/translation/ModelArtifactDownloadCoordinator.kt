package app.guidecast.provider.mlkit.translation

import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Network downloads do not enter TranslateJni and must not occupy its global mutation lane.
 * The actual Google Task remains owned here after a settings waiter times out or is cancelled.
 * A retry joins that task; only deletion of the same normalized artifact waits for its completion.
 */
internal class ModelArtifactDownloadCoordinator : Closeable {
    private class Artifact {
        val boundary = Mutex()
        var download: Deferred<Unit>? = null
    }

    private val owner = SupervisorJob()
    private val scope = CoroutineScope(owner + Dispatchers.Default)
    private val artifacts = ConcurrentHashMap<String, Artifact>()
    private val closed = AtomicBoolean(false)

    suspend fun download(
        artifactKey: String,
        timeoutMillis: Long = MODEL_DOWNLOAD_WAIT_TIMEOUT_MILLIS,
        isArtifactReady: suspend () -> Boolean = { false },
        readinessPollMillis: Long = 5_000L,
        operation: suspend () -> Unit,
    ) {
        require(readinessPollMillis > 0)
        try {
            withTimeout(timeoutMillis) {
                val artifact = artifacts.getOrPut(artifactKey, ::Artifact)
                val task = artifact.boundary.withLock {
                    check(!closed.get()) { "ML Kit model download coordinator is closed" }
                    // Installed state and Task callback delivery are separate facts. A retry must
                    // not wait forever on an old callback after Google's catalog confirms the file.
                    if (isArtifactReady()) return@withTimeout
                    artifact.download?.takeUnless { it.isCompleted }
                        ?: scope.async { operation() }.also { artifact.download = it }
                }
                while (true) {
                    val completed = try {
                        withTimeoutOrNull(readinessPollMillis) { task.await(); true } == true
                    } catch (error: Exception) {
                        currentCoroutineContext().ensureActive()
                        if (isArtifactReady()) break
                        throw error
                    }
                    if (completed || isArtifactReady()) break
                }
            }
        } catch (error: CancellationException) {
            // Caller cancellation ends preparation immediately. A remote task cancellation or our
            // own deadline is a model-stage failure, so healthy language stages can still finish.
            currentCoroutineContext().ensureActive()
            val reason = if (error is TimeoutCancellationException) {
                "다운로드 응답 시간이 초과됐습니다. 모델 서비스 응답과 연결 상태를 확인하고 다시 준비하세요."
            } else {
                "다운로드가 중단됐습니다. 인터넷 연결을 확인하고 다시 준비하세요."
            }
            throw IllegalStateException("$artifactKey 번역 모델 $reason", error)
        }
    }

    /**
     * Finish waiting before the non-cancellable deletion transaction begins. Keep the boundary
     * locked through that transaction so a new download cannot race a real file deletion.
     */
    suspend fun <T> withDownloadFinishedForRemoval(
        artifactKey: String,
        timeoutMillis: Long = MODEL_DOWNLOAD_WAIT_TIMEOUT_MILLIS,
        operation: suspend () -> T,
    ): T {
        val artifact = artifacts.getOrPut(artifactKey, ::Artifact)
        var locked = false
        try {
            try {
                withTimeout(timeoutMillis) {
                    artifact.boundary.lock()
                    locked = true
                    try {
                        artifact.download?.await()
                    } catch (error: Exception) {
                        currentCoroutineContext().ensureActive()
                        // A terminal failed/cancelled download no longer modifies its artifact.
                    }
                }
            } catch (error: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                throw IllegalStateException(
                    "$artifactKey 번역 모델 다운로드가 아직 끝나지 않아 삭제하지 않았습니다. " +
                        "연결 상태를 확인하고 다시 시도하세요.",
                    error,
                )
            }
            currentCoroutineContext().ensureActive()
            return operation()
        } finally {
            if (locked) artifact.boundary.unlock()
        }
    }

    override fun close() {
        // Completing the owner waits for already submitted work without cancelling the Google
        // Task observer. No new download can start after the owner enters completion.
        if (closed.compareAndSet(false, true)) owner.complete()
    }
}

private const val MODEL_DOWNLOAD_WAIT_TIMEOUT_MILLIS = 120_000L

/** Preparation reuses installed artifacts; requesting download again may trigger a network update. */
internal suspend fun ensureModelArtifactDownloaded(
    isDownloaded: suspend () -> Boolean,
    download: suspend () -> Unit,
) {
    if (!isDownloaded()) download()
}
