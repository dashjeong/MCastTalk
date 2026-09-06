package app.guidecast.provider.gemma.translation

import android.content.Context
import android.net.Uri
import android.os.StatFs
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

enum class GemmaModelReadiness {
    NOT_INSTALLED,
    DOWNLOADING,
    VERIFYING,
    /** File size and SHA-256 passed, but Android inference has not passed yet. */
    VERIFIED,
    ENGINE_TESTING,
    /** File verification and actual Gemma translation inference passed. */
    READY,
    FAILED,
}

data class GemmaModelStatus(
    val readiness: GemmaModelReadiness = GemmaModelReadiness.NOT_INSTALLED,
    val downloadedBytes: Long = 0,
    val variant: GemmaModelVariant = GemmaModelVariant.STANDARD,
    val totalBytes: Long = variant.sizeBytes,
    val errorMessage: String? = null,
)

/**
 * Resumable installer for the exact gemma4-e2b artifact used by google-gemma/gemma-translator.
 *
 * The upstream repository and model are public and require no account or token. A partial
 * download stays in the app's private data area and is resumed with an HTTP Range request.
 * The runtime cannot see the model until its exact byte count and SHA-256 both pass.
 */
class GemmaModelManager(context: Context, private val stagingVariant: GemmaModelVariant? = null) {
    private val appContext = context.applicationContext
    private val selectionPreferences = appContext.getSharedPreferences(
        "guidecast_gemma_selection", Context.MODE_PRIVATE,
    )
    private var selectionLoadError: String? = null
    @Volatile
    var selectedVariant: GemmaModelVariant = stagingVariant ?: runCatching {
        val restore = selectionPreferences.getString("application_previous", null)
        if (restore != null) {
            check(selectionPreferences.edit().putString("variant", restore)
                .remove("application_previous").commit())
        }
        GemmaCatalogStore(appContext).resolve(selectionPreferences.getString("variant", "standard")!!)
    }.getOrElse {
        selectionLoadError = "저장된 모델 선택을 복원하지 못했습니다. 기본 목록을 표시합니다: ${it.message}"
        GemmaModelVariant.STANDARD
    }
        private set
    private val selectionGate = GemmaModelSelectionGate()
    private val preferences
        get() = appContext.getSharedPreferences(
            if (selectedVariant == GemmaModelVariant.STANDARD) PREFERENCES_NAME
            else "${PREFERENCES_NAME}_${selectedVariant.id}",
            Context.MODE_PRIVATE,
        )
    private val operationActive = AtomicBoolean(false)
    private val mutableStatus = MutableStateFlow(newStatus())
    val status: StateFlow<GemmaModelStatus> = mutableStatus.asStateFlow()

    val appliedVariant: GemmaModelVariant
        get() = selectionPreferences.getString("application_previous", null)?.let {
            GemmaCatalogStore(appContext).resolve(it)
        } ?: selectedVariant

    val modelFile: File
        get() = File(appContext.filesDir, "models/${selectedVariant.fileName}")

    private val temporaryFile: File
        get() = File(requireNotNull(modelFile.parentFile), "${selectedVariant.fileName}.download")

    suspend fun refresh() = withSelectedModel { refreshSelected() }

    private suspend fun refreshSelected() = withContext(Dispatchers.IO) {
        if (operationActive.get() || mutableStatus.value.readiness == GemmaModelReadiness.ENGINE_TESTING) {
            return@withContext
        }
        selectedVariant.compatibilityIssue(android.os.Build.VERSION.SDK_INT)?.let { issue ->
            mutableStatus.value = newStatus(readiness = GemmaModelReadiness.FAILED, errorMessage = issue)
            return@withContext
        }
        val file = modelFile
        if (!file.isFile) {
            val partialBytes = temporaryFile.takeIf(File::isFile)?.length() ?: 0L
            mutableStatus.value = newStatus(downloadedBytes = partialBytes)
            return@withContext
        }
        if (isPreviouslyVerified(file)) {
            mutableStatus.value = verifiedStatus(file)
            return@withContext
        }
        verifyAndPublish(file)
    }

    suspend fun download() {
        runExclusive {
            checkCompatibility()
            val partial = prepareTemporaryFile(clearExisting = false)
            downloadTo(partial)
            installVerified(partial)
        }
    }

    /** Imports a file selected with Android's Storage Access Framework. */
    suspend fun importModel(source: Uri) {
        runExclusive {
            checkCompatibility()
            val destination = prepareTemporaryFile(clearExisting = true)
            try {
                appContext.contentResolver.openInputStream(source)?.buffered()?.use { input ->
                    FileOutputStream(destination, false).buffered().use { output ->
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        var copied = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            copied += count
                            publishProgress(copied)
                            check(copied <= selectedVariant.sizeBytes) {
                                "선택한 파일이 공식 Gemma Translator 모델 크기보다 큽니다."
                            }
                        }
                    }
                } ?: error("선택한 모델 파일을 열 수 없습니다.")
                installVerified(destination)
            } catch (error: Throwable) {
                destination.delete()
                throw error
            }
        }
    }

    suspend fun markEngineTesting() = withContext(Dispatchers.IO) {
        check(modelFile.isFile && isPreviouslyVerified(modelFile)) {
            "검증된 Gemma Translator 모델 파일이 없습니다."
        }
        mutableStatus.value = newStatus(
            readiness = GemmaModelReadiness.ENGINE_TESTING,
            downloadedBytes = modelFile.length(),
        )
    }

    suspend fun markRuntimeReady() = withContext(Dispatchers.IO) {
        check(modelFile.isFile && isPreviouslyVerified(modelFile)) {
            "검증된 Gemma Translator 모델 파일이 없습니다."
        }
        preferences.edit()
            .putBoolean(KEY_RUNTIME_VERIFIED, true)
            .remove(KEY_RUNTIME_ERROR)
            .commit()
        mutableStatus.value = newStatus(
            readiness = GemmaModelReadiness.READY,
            downloadedBytes = modelFile.length(),
        )
    }

    suspend fun markRuntimeFailure(message: String) = withContext(Dispatchers.IO) {
        preferences.edit()
            .putBoolean(KEY_RUNTIME_VERIFIED, false)
            .putString(KEY_RUNTIME_ERROR, message.take(2_000))
            .commit()
        mutableStatus.value = newStatus(
            readiness = GemmaModelReadiness.VERIFIED,
            downloadedBytes = modelFile.takeIf(File::isFile)?.length() ?: 0L,
            errorMessage = message,
        )
    }

    /** Records an Android foreground-service timeout without deleting the resumable partial file. */
    fun markOperationInterrupted(message: String) {
        val installedBytes = modelFile.takeIf(File::isFile)?.length() ?: 0L
        val partialBytes = temporaryFile.takeIf(File::isFile)?.length() ?: 0L
        mutableStatus.value = newStatus(
            readiness = if (modelFile.isFile && isPreviouslyVerified(modelFile)) {
                GemmaModelReadiness.VERIFIED
            } else {
                GemmaModelReadiness.NOT_INSTALLED
            },
            downloadedBytes = maxOf(installedBytes, partialBytes),
            errorMessage = message.take(2_000),
        )
    }

    suspend fun remove() = withSelectedModel { withContext(Dispatchers.IO) {
        check(operationActive.compareAndSet(false, true)) { "다른 Gemma 모델 작업이 진행 중입니다." }
        try {
            preferences.edit().clear().commit()
            check(!modelFile.exists() || modelFile.delete()) { "Gemma 모델을 삭제하지 못했습니다." }
            if (temporaryFile.exists()) temporaryFile.delete()
            modelFile.parentFile?.resolve(selectedVariant.cacheDirectoryName)?.deleteRecursively()
            mutableStatus.value = newStatus()
        } finally {
            operationActive.set(false)
        }
    }

    }

    private suspend fun runExclusive(operation: suspend () -> Unit) = withSelectedModel {
        check(operationActive.compareAndSet(false, true)) { "다른 Gemma 모델 작업이 진행 중입니다." }
        try {
            withContext(Dispatchers.IO) { operation() }
        } catch (cancelled: CancellationException) {
            val interruptionMessage = mutableStatus.value.errorMessage
            val installedBytes = modelFile.takeIf(File::isFile)?.length() ?: 0L
            val partialBytes = temporaryFile.takeIf(File::isFile)?.length() ?: 0L
            mutableStatus.value = newStatus(
                readiness = if (modelFile.isFile && isPreviouslyVerified(modelFile)) {
                    GemmaModelReadiness.VERIFIED
                } else {
                    GemmaModelReadiness.NOT_INSTALLED
                },
                downloadedBytes = maxOf(installedBytes, partialBytes),
                errorMessage = interruptionMessage,
            )
            throw cancelled
        } catch (error: Throwable) {
            mutableStatus.value = newStatus(
                readiness = GemmaModelReadiness.FAILED,
                downloadedBytes = temporaryFile.takeIf(File::isFile)?.length()
                    ?: mutableStatus.value.downloadedBytes,
                errorMessage = error.message ?: "Gemma Translator 모델을 준비하지 못했습니다.",
            )
            throw error
        } finally {
            operationActive.set(false)
        }
    }

    private fun prepareTemporaryFile(clearExisting: Boolean): File {
        val directory = requireNotNull(modelFile.parentFile)
        check(directory.exists() || directory.mkdirs()) { "모델 저장 폴더를 만들지 못했습니다." }
        if (clearExisting && temporaryFile.exists()) {
            check(temporaryFile.delete()) { "이전 임시 모델 파일을 정리하지 못했습니다." }
        }
        if (temporaryFile.length() > selectedVariant.sizeBytes) {
            check(temporaryFile.delete()) { "손상된 임시 모델 파일을 정리하지 못했습니다." }
        }
        ensureStorageAvailable(directory, selectedVariant.sizeBytes - temporaryFile.length())
        mutableStatus.value = newStatus(
            readiness = GemmaModelReadiness.DOWNLOADING,
            downloadedBytes = temporaryFile.length(),
        )
        return temporaryFile
    }

    private fun ensureStorageAvailable(directory: File, remainingBytes: Long) {
        val available = StatFs(directory.absolutePath).availableBytes
        val required = remainingBytes + STORAGE_SAFETY_BYTES
        check(available >= required) {
            "저장 공간이 부족합니다. 최소 ${required.toGiBText()}가 필요하지만 " +
                "현재 ${available.toGiBText()}만 사용할 수 있습니다."
        }
    }

    private fun downloadTo(destination: File) {
        var current = URI(selectedVariant.downloadUrl)
        var redirectCount = 0
        var requestedOffset = destination.length()
        while (true) {
            requireTrustedGemmaDownloadUrl(current)
            val connection = (URL(current.toASCIIString()).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                requestMethod = "GET"
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", "GuideCast-Android/$CLIENT_VERSION")
                if (requestedOffset > 0L) setRequestProperty("Range", "bytes=$requestedOffset-")
            }
            try {
                val statusCode = connection.responseCode
                if (statusCode in REDIRECT_CODES) {
                    check(redirectCount++ < MAX_REDIRECTS) { "모델 다운로드 리다이렉트가 너무 많습니다." }
                    val location = connection.getHeaderField("Location")
                        ?: error("모델 서버가 이동 주소를 보내지 않았습니다.")
                    current = current.resolve(location)
                    continue
                }
                if (statusCode == HTTP_RANGE_NOT_SATISFIABLE && destination.length() == selectedVariant.sizeBytes) {
                    return
                }
                val append = when (statusCode) {
                    HttpURLConnection.HTTP_OK -> false
                    HttpURLConnection.HTTP_PARTIAL -> {
                        val contentRange = connection.getHeaderField("Content-Range").orEmpty()
                        check(contentRange.startsWith("bytes $requestedOffset-")) {
                            "모델 서버의 이어받기 범위가 올바르지 않습니다."
                        }
                        true
                    }
                    HttpURLConnection.HTTP_UNAUTHORIZED,
                    HttpURLConnection.HTTP_FORBIDDEN,
                    -> error("공개 모델 서버가 접근을 거부했습니다(HTTP $statusCode). 잠시 후 다시 시도하세요.")
                    HttpURLConnection.HTTP_NOT_FOUND -> error("공식 Gemma Translator 파일을 찾지 못했습니다(HTTP 404).")
                    else -> error("모델 서버 응답 오류(HTTP $statusCode)")
                }
                if (!append) {
                    requestedOffset = 0L
                    publishProgress(0L)
                }
                connection.inputStream.buffered().use { input ->
                    FileOutputStream(destination, append).buffered().use { output ->
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        var copied = requestedOffset
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            copied += count
                            publishProgress(copied)
                            check(copied <= selectedVariant.sizeBytes) {
                                "다운로드 파일이 공식 Gemma Translator 모델 크기보다 큽니다."
                            }
                        }
                    }
                }
                check(destination.length() == selectedVariant.sizeBytes) {
                    "모델 다운로드가 완료되지 않았습니다 " +
                        "(${destination.length()} / ${selectedVariant.sizeBytes} bytes). 다시 누르면 이어받습니다."
                }
                return
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun publishProgress(downloaded: Long) {
        mutableStatus.value = newStatus(
            readiness = GemmaModelReadiness.DOWNLOADING,
            downloadedBytes = downloaded,
        )
    }

    private fun installVerified(download: File) {
        mutableStatus.value = newStatus(
            readiness = GemmaModelReadiness.VERIFYING,
            downloadedBytes = download.length(),
        )
        try {
            verifyFile(download)
        } catch (error: Throwable) {
            download.delete()
            throw error
        }
        if (modelFile.exists()) check(modelFile.delete()) { "기존 모델을 교체하지 못했습니다." }
        check(download.renameTo(modelFile)) { "검증된 Gemma Translator 파일을 설치하지 못했습니다." }
        rememberVerified(modelFile, runtimeVerified = false)
        mutableStatus.value = newStatus(
            readiness = GemmaModelReadiness.VERIFIED,
            downloadedBytes = modelFile.length(),
        )
    }

    private fun verifyAndPublish(file: File) {
        mutableStatus.value = newStatus(
            readiness = GemmaModelReadiness.VERIFYING,
            downloadedBytes = file.length(),
        )
        runCatching {
            verifyFile(file)
            rememberVerified(file, runtimeVerified = false)
        }.onSuccess {
            mutableStatus.value = verifiedStatus(file)
        }.onFailure { error ->
            file.delete()
            mutableStatus.value = newStatus(
                readiness = GemmaModelReadiness.FAILED,
                errorMessage = error.message,
            )
        }
    }

    private fun verifyFile(file: File) {
        check(file.length() == selectedVariant.sizeBytes) {
            "Gemma Translator 모델 크기가 올바르지 않습니다 (${file.length()} / ${selectedVariant.sizeBytes} bytes)."
        }
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual == selectedVariant.sha256) { "Gemma Translator 모델 SHA-256 검증에 실패했습니다." }
    }

    private fun verifiedStatus(file: File): GemmaModelStatus = newStatus(
        readiness = if (preferences.getBoolean(KEY_RUNTIME_VERIFIED, false)) {
            GemmaModelReadiness.READY
        } else {
            GemmaModelReadiness.VERIFIED
        },
        downloadedBytes = file.length(),
        errorMessage = preferences.getString(KEY_RUNTIME_ERROR, null),
    )

    private fun isPreviouslyVerified(file: File): Boolean =
        file.length() == selectedVariant.sizeBytes &&
            preferences.getString(KEY_VERIFIED_SHA, null) == selectedVariant.sha256 &&
            preferences.getLong(KEY_VERIFIED_LENGTH, -1L) == file.length() &&
            preferences.getLong(KEY_VERIFIED_MODIFIED, -1L) == file.lastModified()

    private fun rememberVerified(file: File, runtimeVerified: Boolean) {
        preferences.edit()
            .putString(KEY_VERIFIED_SHA, selectedVariant.sha256)
            .putLong(KEY_VERIFIED_LENGTH, file.length())
            .putLong(KEY_VERIFIED_MODIFIED, file.lastModified())
            .putBoolean(KEY_RUNTIME_VERIFIED, runtimeVerified)
            .remove(KEY_RUNTIME_ERROR)
            .commit()
    }

    private fun Long.toGiBText(): String = "%.2f GiB".format(this / 1_073_741_824.0)


    suspend fun <T> withSelectedModel(block: suspend () -> T): T =
        selectionGate.withSelectedModel(block)

    private fun checkCompatibility() {
        selectedVariant.compatibilityIssue(android.os.Build.VERSION.SDK_INT)?.let { error(it) }
    }

    internal suspend fun selectVariant(variant: GemmaModelVariant) = selectionGate.change {
        withContext(Dispatchers.IO) {
            check(stagingVariant == null) { "준비 전용 모델의 선택은 변경할 수 없습니다." }
            check(selectionPreferences.edit().putString("variant", variant.id).commit()) {
                "모델 선택을 저장하지 못했습니다."
            }
            selectedVariant = variant
            selectionLoadError = null
            mutableStatus.value = newStatus()
            refreshSelected()
        }
    }

    internal fun beginApplication() {
        check(selectionPreferences.edit().putString("application_previous", selectedVariant.id).commit()) {
            "모델 복구 정보를 저장하지 못했습니다."
        }
    }

    internal fun finishApplication() {
        check(selectionPreferences.edit().remove("application_previous").commit()) {
            "모델 적용 완료를 저장하지 못했습니다."
        }
    }

    private fun newStatus(
        readiness: GemmaModelReadiness = GemmaModelReadiness.NOT_INSTALLED,
        downloadedBytes: Long = 0,
        errorMessage: String? = null,
    ) = GemmaModelStatus(
        readiness = readiness,
        downloadedBytes = downloadedBytes,
        variant = selectedVariant,
        errorMessage = errorMessage ?: selectionLoadError,
    )

    companion object {
        const val RUNTIME_VERSION = "LiteRT-LM Android 0.16.1"
        const val UPSTREAM_REPOSITORY_URL = "https://github.com/google-gemma/gemma-translator"
        const val TERMS_URL = "https://ai.google.dev/gemma/apache_2"
        const val MODEL_REPOSITORY_URL = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm"
        const val MODEL_FILE_NAME = "gemma-4-E2B-it.litertlm"
        const val MODEL_REVISION = "6e5c4f1e395deb959c494953478fa5cec4b8008f"
        const val MODEL_SIZE_BYTES = 2_588_147_712L
        const val MODEL_SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
        const val MODEL_DOWNLOAD_URL =
            "$MODEL_REPOSITORY_URL/resolve/$MODEL_REVISION/$MODEL_FILE_NAME?download=true"
        private const val PREFERENCES_NAME = "guidecast_gemma_translator_model"
        private const val KEY_VERIFIED_SHA = "verified_sha"
        private const val KEY_VERIFIED_LENGTH = "verified_length"
        private const val KEY_VERIFIED_MODIFIED = "verified_modified"
        private const val KEY_RUNTIME_VERIFIED = "runtime_verified"
        private const val KEY_RUNTIME_ERROR = "runtime_error"
        private const val CLIENT_VERSION = "0.2.1"
        private const val CONNECT_TIMEOUT_MILLIS = 20_000
        private const val READ_TIMEOUT_MILLIS = 60_000
        private const val COPY_BUFFER_BYTES = 1024 * 1024
        private const val MAX_REDIRECTS = 8
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        private const val STORAGE_SAFETY_BYTES = 512L * 1024 * 1024
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
