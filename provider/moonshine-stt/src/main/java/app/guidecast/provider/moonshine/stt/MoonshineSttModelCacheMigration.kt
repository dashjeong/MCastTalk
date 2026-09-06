package app.guidecast.provider.moonshine.stt

import ai.moonshine.voice.AssetDownloader
import ai.moonshine.voice.JNI
import ai.moonshine.voice.ModelCache
import ai.moonshine.voice.ModelSpec
import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * One-time repair for caches created before GuideCast validated Moonshine model files.
 *
 * Only the official Korean TINY STT directory is migrated. A schema marker containing the fresh
 * files' sizes and SHA-256 digests is written after download validation; all TTS and other model
 * directories under Moonshine's root are left untouched.
 */
internal class MoonshineSttModelCacheMigration(
    private val context: Context,
) {
    fun prepare(onProgress: (progress: Float, currentFile: String?) -> Unit) {
        val spec = ModelSpec.stt(
            MOONSHINE_KOREAN_LANGUAGE,
            JNI.MOONSHINE_MODEL_ARCH_TINY,
            false,
        )
        val root = ModelCache.defaultRoot(context)
        val targetKey = ModelCache.key(spec)
        val targetDirectory = ModelCache.directoryFor(context, spec, null)
        val expectedFiles = officialFiles()
        val migration = ScopedModelCacheMigration(
            rootDirectory = root,
            targetDirectory = targetDirectory,
            targetKey = targetKey,
            schemaVersion = SCHEMA_VERSION,
        )
        migration.prepare(expectedFiles) {
            val downloader = AssetDownloader()
            downloader.ensureModelPresent(targetDirectory, spec) {
                    file,
                    fileIndex,
                    fileCount,
                    bytesRead,
                    totalBytes,
                ->
                val fileProgress = if (totalBytes > 0L) {
                    bytesRead.toDouble() / totalBytes.toDouble()
                } else {
                    0.0
                }
                val overallProgress = if (fileCount > 0) {
                    (fileIndex.toDouble() + fileProgress) / fileCount.toDouble()
                } else {
                    fileProgress
                }
                onProgress(overallProgress.toFloat().coerceIn(0f, 1f), file)
            }
            check(downloader.isModelPresent(targetDirectory, spec)) {
                "Moonshine 한국어 TINY 모델 다운로드가 완료되지 않았습니다."
            }
        }
        onProgress(1f, null)
    }

    /**
     * Fast app-private hand-off check used immediately before native mapping.
     *
     * Full SHA-256 verification is intentionally performed by [prepare] before native admission.
     * This check trusts only that freshly written marker and exact file sizes, avoiding a second
     * 70+ MiB hash pass while the process-wide cold-load ticket is held.
     */
    fun requirePreparedForNativeLoad() {
        val spec = ModelSpec.stt(
            MOONSHINE_KOREAN_LANGUAGE,
            JNI.MOONSHINE_MODEL_ARCH_TINY,
            false,
        )
        val targetDirectory = ModelCache.directoryFor(context, spec, null)
        val marker = File(targetDirectory, MARKER_FILE_NAME)
        val lines = runCatching { marker.readLines(StandardCharsets.UTF_8) }
            .getOrElse { throw IllegalStateException("Moonshine STT 검증 표식이 없습니다.", it) }
        check(
            lines.size >= 2 &&
                lines[0] == "schema=$SCHEMA_VERSION" &&
                lines[1] == "key=${ModelCache.key(spec)}"
        ) {
            "Moonshine STT 검증 표식이 올바르지 않습니다."
        }
        val records = lines.drop(2).associate { line ->
            val parts = line.split('\t')
            check(parts.size == 3) { "Moonshine STT 검증 기록이 올바르지 않습니다." }
            String(URL_DECODER.decode(parts[0]), StandardCharsets.UTF_8) to parts
        }
        check(records.keys == PINNED_OFFICIAL_FILES.mapTo(mutableSetOf()) { it.relativePath }) {
            "Moonshine STT 검증 파일 목록이 올바르지 않습니다."
        }
        PINNED_OFFICIAL_FILES.forEach { expected ->
            val record = records.getValue(expected.relativePath)
            val file = File(targetDirectory, expected.relativePath)
            check(
                file.isFile &&
                    !Files.isSymbolicLink(file.toPath()) &&
                    file.length() == expected.expectedSize &&
                    record[1].toLongOrNull() == expected.expectedSize &&
                    record[2] == expected.expectedSha256
            ) {
                "Moonshine STT 모델 준비 상태가 변경되었습니다: ${expected.relativePath}"
            }
        }
    }

    // The SDK version is pinned by the resolved dependency lock and these exact files are verified
    // by SHA-256. Querying Transcriber.getSttDependencies() here loads JNI before the process-wide
    // native admission gate, so cache preparation deliberately uses this checked-in manifest.
    private fun officialFiles(): List<ExpectedModelFile> = PINNED_OFFICIAL_FILES

    companion object {
        private const val MOONSHINE_KOREAN_LANGUAGE = "ko"
        private const val SCHEMA_VERSION = "guidecast-moonshine-stt-ko-tiny-v3"
        private const val MARKER_FILE_NAME = ".guidecast-cache-schema"
        private val URL_DECODER = Base64.getUrlDecoder()
        private val PINNED_OFFICIAL_FILES = listOf(
            ExpectedModelFile(
                relativePath = "encoder_model.ort",
                expectedSize = 13_238_176L,
                expectedSha256 = "947260d46252f48eada86a34986b3f70c01d68a343959949a77375b94debd055",
            ),
            ExpectedModelFile(
                relativePath = "tokenizer.bin",
                expectedSize = 249_974L,
                expectedSha256 = "6884b35fd6377d4c4d32336a0bc152f36b64d1e45b6503683cdc238250a8472d",
            ),
            ExpectedModelFile(
                relativePath = "decoder_model_merged.ort",
                expectedSize = 58_327_336L,
                expectedSha256 = "95aa9f2e764b80625d2889d6ec9f05c965808e540ac50c16abd10c7ea33fe44b",
            ),
        )
    }
}

internal data class ExpectedModelFile(
    val relativePath: String,
    val expectedSize: Long,
    val expectedSha256: String,
)

/** Filesystem-only implementation so scope, corruption repair, and concurrency are unit-testable. */
internal class ScopedModelCacheMigration(
    private val rootDirectory: File,
    private val targetDirectory: File,
    private val targetKey: String,
    private val schemaVersion: String,
) {
    fun prepare(
        expectedFiles: List<ExpectedModelFile>,
        downloadFreshFiles: () -> Unit,
    ): Boolean {
        validateScopeAndManifest(expectedFiles)
        check(rootDirectory.mkdirs() || rootDirectory.isDirectory) {
            "Moonshine 모델 캐시 루트를 만들지 못했습니다."
        }
        val lockFile = File(rootDirectory, ".$schemaVersion.lock")
        val processLock = PROCESS_LOCKS.computeIfAbsent(lockFile.canonicalPath) { ReentrantLock() }
        return processLock.withLock {
            RandomAccessFile(lockFile, "rw").channel.use { channel ->
                channel.lock().use {
                    prepareWhileLocked(expectedFiles, downloadFreshFiles)
                }
            }
        }
    }

    private fun prepareWhileLocked(
        expectedFiles: List<ExpectedModelFile>,
        downloadFreshFiles: () -> Unit,
    ): Boolean {
        check(targetDirectory.mkdirs() || targetDirectory.isDirectory) {
            "Moonshine 한국어 TINY 모델 디렉터리를 만들지 못했습니다."
        }
        if (hasCurrentValidMarker(expectedFiles)) return false

        // A legacy or prior-schema cache is preserved only when every byte matches the pinned
        // official 0.1.5 artifact. Size alone is not an authenticity or corruption check.
        if (hasExactLegacyFiles(expectedFiles)) {
            writeMarker(validateAndHash(expectedFiles))
            check(hasCurrentValidMarker(expectedFiles)) {
                "Moonshine 기존 모델 캐시 검증 표식을 확인하지 못했습니다."
            }
            return true
        }

        // An invalid marker or a non-exact legacy dependency means this exact cache is untrusted.
        // Do not let Moonshine's existence-only check pass partial files to native load().
        targetDirectory.listFiles().orEmpty().forEach(::deleteWithoutFollowingSymlinks)
        check(targetDirectory.mkdirs() || targetDirectory.isDirectory) {
            "Moonshine 한국어 TINY 모델 디렉터리를 다시 만들지 못했습니다."
        }
        downloadFreshFiles()
        val records = validateAndHash(expectedFiles)
        writeMarker(records)
        check(hasCurrentValidMarker(expectedFiles)) {
            "Moonshine 한국어 TINY 모델 캐시 검증 표식을 확인하지 못했습니다."
        }
        return true
    }

    private fun hasExactLegacyFiles(expectedFiles: List<ExpectedModelFile>): Boolean {
        return expectedFiles.all { expected ->
            val file = resolveExpectedFile(expected.relativePath)
            file.isFile &&
                !Files.isSymbolicLink(file.toPath()) &&
                file.length() == expected.expectedSize &&
                file.sha256() == expected.expectedSha256
        }
    }

    private fun validateScopeAndManifest(expectedFiles: List<ExpectedModelFile>) {
        require(schemaVersion.matches(SAFE_SCHEMA)) { "Invalid Moonshine cache schema" }
        require(targetKey.matches(SAFE_TARGET_KEY)) { "Invalid Moonshine cache target" }
        val root = rootDirectory.canonicalFile
        val target = targetDirectory.canonicalFile
        require(target.parentFile == root && target.name == targetKey) {
            "Moonshine cache migration escaped its exact model directory"
        }
        require(expectedFiles.isNotEmpty()) { "Moonshine model manifest is empty" }
        require(expectedFiles.map { it.relativePath }.toSet().size == expectedFiles.size) {
            "Moonshine model manifest contains duplicate files"
        }
        expectedFiles.forEach { expected ->
            require(expected.expectedSize > 0L) {
                "Moonshine model manifest contains an invalid size"
            }
            require(expected.expectedSha256.matches(SHA_256)) {
                "Moonshine model manifest contains an invalid SHA-256"
            }
            resolveExpectedFile(expected.relativePath)
        }
    }

    private fun hasCurrentValidMarker(expectedFiles: List<ExpectedModelFile>): Boolean = runCatching {
        val marker = markerFile()
        if (!marker.isFile) return false
        val lines = marker.readLines(StandardCharsets.UTF_8)
        if (lines.size < 2 || lines[0] != "schema=$schemaVersion" ||
            lines[1] != "key=$targetKey") {
            return false
        }
        val records = lines.drop(2).associate { line ->
            val parts = line.split('\t')
            check(parts.size == 3)
            val path = String(URL_DECODER.decode(parts[0]), StandardCharsets.UTF_8)
            path to ModelFileRecord(
                relativePath = path,
                size = parts[1].toLong(),
                sha256 = parts[2],
            )
        }
        if (records.keys != expectedFiles.mapTo(mutableSetOf()) { it.relativePath }) return false
        expectedFiles.all { expected ->
            val record = records.getValue(expected.relativePath)
            val file = resolveExpectedFile(expected.relativePath)
            file.isFile &&
                !Files.isSymbolicLink(file.toPath()) &&
                record.size == expected.expectedSize &&
                record.sha256 == expected.expectedSha256 &&
                file.length() == expected.expectedSize &&
                file.sha256() == expected.expectedSha256
        }
    }.getOrDefault(false)

    private fun validateAndHash(expectedFiles: List<ExpectedModelFile>): List<ModelFileRecord> =
        expectedFiles.map { expected ->
            val file = resolveExpectedFile(expected.relativePath)
            check(file.isFile && !Files.isSymbolicLink(file.toPath()) && file.length() > 0L) {
                "Moonshine 모델 파일이 없습니다: ${expected.relativePath}"
            }
            check(file.length() == expected.expectedSize) {
                "Moonshine 모델 파일 크기가 올바르지 않습니다: ${expected.relativePath}"
            }
            val sha256 = file.sha256()
            check(sha256 == expected.expectedSha256) {
                "Moonshine 모델 파일 무결성이 올바르지 않습니다: ${expected.relativePath}"
            }
            ModelFileRecord(
                relativePath = expected.relativePath,
                size = file.length(),
                sha256 = sha256,
            )
        }

    private fun writeMarker(records: List<ModelFileRecord>) {
        val marker = markerFile()
        val temporary = File(targetDirectory, "$MARKER_FILE_NAME.part")
        val contents = buildString {
            appendLine("schema=$schemaVersion")
            appendLine("key=$targetKey")
            records.sortedBy(ModelFileRecord::relativePath).forEach { record ->
                append(URL_ENCODER.encodeToString(record.relativePath.toByteArray(StandardCharsets.UTF_8)))
                append('\t')
                append(record.size)
                append('\t')
                appendLine(record.sha256)
            }
        }
        temporary.writeText(contents, StandardCharsets.UTF_8)
        if (marker.exists()) {
            check(marker.delete()) {
                temporary.delete()
                "Moonshine 이전 모델 캐시 검증 표식을 교체하지 못했습니다."
            }
        }
        check(temporary.renameTo(marker)) {
            temporary.delete()
            "Moonshine 모델 캐시 검증 표식을 저장하지 못했습니다."
        }
    }

    private fun markerFile() = File(targetDirectory, MARKER_FILE_NAME)

    private fun resolveExpectedFile(relativePath: String): File {
        require(relativePath.isNotBlank() && '\u0000' !in relativePath) {
            "Moonshine model path is invalid"
        }
        val candidate = File(targetDirectory, relativePath)
        require(!candidate.isAbsolute || relativePath.firstOrNull() != File.separatorChar) {
            "Moonshine model path must be relative"
        }
        val targetPath = targetDirectory.canonicalFile.toPath()
        val candidatePath = candidate.canonicalFile.toPath()
        require(candidatePath.startsWith(targetPath) && candidatePath != targetPath) {
            "Moonshine model path escaped its cache directory"
        }
        return candidate
    }

    private fun deleteWithoutFollowingSymlinks(file: File) {
        if (file.isDirectory && !Files.isSymbolicLink(file.toPath())) {
            file.listFiles().orEmpty().forEach(::deleteWithoutFollowingSymlinks)
        }
        check(file.delete() || !file.exists()) {
            "Moonshine legacy cache file could not be removed: ${file.name}"
        }
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().buffered().use { input ->
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private data class ModelFileRecord(
        val relativePath: String,
        val size: Long,
        val sha256: String,
    )

    companion object {
        private const val MARKER_FILE_NAME = ".guidecast-cache-schema"
        private const val HASH_BUFFER_BYTES = 64 * 1_024
        private val SAFE_SCHEMA = Regex("[A-Za-z0-9._-]{1,80}")
        private val SAFE_TARGET_KEY = Regex("[A-Za-z0-9._-]{1,120}")
        private val SHA_256 = Regex("[0-9a-f]{64}")
        private val URL_ENCODER = Base64.getUrlEncoder().withoutPadding()
        private val URL_DECODER = Base64.getUrlDecoder()
        private val PROCESS_LOCKS = ConcurrentHashMap<String, ReentrantLock>()
    }
}
