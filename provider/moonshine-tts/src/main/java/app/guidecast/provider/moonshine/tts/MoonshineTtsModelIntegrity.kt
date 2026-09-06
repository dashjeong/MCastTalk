package app.guidecast.provider.moonshine.tts

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

internal data class MoonshineTtsExpectedFile(
    val relativePath: String,
    val expectedSize: Long,
    val sha256: String? = null,
)

/**
 * Validates a downloaded voice without consulting Moonshine's native dependency resolver.
 *
 * The worker process writes one atomic marker after comparing every release-pinned manifest entry
 * with the downloaded file. The main process may use [hasReadySnapshot] for an advisory startup
 * status without hashing hundreds of megabytes on the UI thread. Before native loading, the worker
 * always calls [isReady] or [writeReadyMarkerIfFilesMatch], which verifies every pinned SHA-256.
 */
internal object MoonshineTtsModelIntegrity {
    private val markerWriteLock = Any()

    fun filesMatch(
        modelDirectory: File,
        expectedFiles: Collection<MoonshineTtsExpectedFile>,
    ): Boolean = runCatching {
        val normalized = normalizeExpectedFiles(expectedFiles)
        normalized.isNotEmpty() && normalized.all { expected ->
            downloadedFileMatches(modelDirectory.toPath(), expected)
        }
    }.getOrDefault(false)

    fun isReady(
        modelDirectory: File,
        currentManifest: Collection<MoonshineTtsExpectedFile>? = null,
    ): Boolean = runCatching {
        val markerFiles = readMarker(modelDirectory.toPath()) ?: return false
        if (currentManifest != null && markerFiles != normalizeExpectedFiles(currentManifest)) {
            return false
        }
        markerFiles.all { expected ->
            downloadedFileMatches(modelDirectory.toPath(), expected)
        }
    }.getOrDefault(false)

    /**
     * Fast, advisory readiness for UI startup. The marker must describe the exact pinned release
     * inventory and every path must still be a regular file of the expected size. This deliberately
     * does not hash model bytes; [isReady] performs that validation in the isolated worker before
     * native loading.
     */
    fun hasReadySnapshot(
        modelDirectory: File,
        currentManifest: Collection<MoonshineTtsExpectedFile>,
    ): Boolean = runCatching {
        val markerFiles = readMarker(modelDirectory.toPath()) ?: return false
        val normalizedManifest = normalizeExpectedFiles(currentManifest)
        markerFiles == normalizedManifest && markerFiles.all { expected ->
            downloadedFileHasExpectedSize(modelDirectory.toPath(), expected)
        }
    }.getOrDefault(false)

    fun writeReadyMarker(
        modelDirectory: File,
        expectedFiles: Collection<MoonshineTtsExpectedFile>,
    ) {
        check(writeReadyMarkerIfFilesMatch(modelDirectory, expectedFiles)) {
            "Moonshine TTS files failed integrity validation"
        }
    }

    /** Hashes the full pinned inventory once and atomically promotes it when every file matches. */
    fun writeReadyMarkerIfFilesMatch(
        modelDirectory: File,
        expectedFiles: Collection<MoonshineTtsExpectedFile>,
    ): Boolean = synchronized(markerWriteLock) {
        val normalized = normalizeExpectedFiles(expectedFiles)
        require(normalized.isNotEmpty()) { "Moonshine TTS manifest contains no files" }
        if (!normalized.all { downloadedFileMatches(modelDirectory.toPath(), it) }) {
            return@synchronized false
        }

        val directory = modelDirectory.toPath().toAbsolutePath().normalize()
        Files.createDirectories(directory)
        val marker = directory.resolve(MARKER_FILE_NAME)
        val temporary = Files.createTempFile(directory, MARKER_FILE_NAME, ".tmp")
        val content = buildMarker(normalized).toByteArray(Charsets.UTF_8)
        try {
            FileChannel.open(
                temporary,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            ).use { channel ->
                channel.write(ByteBuffer.wrap(content))
                channel.force(true)
            }
            try {
                Files.move(
                    temporary,
                    marker,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, marker, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
        true
    }

    fun invalidateMarker(modelDirectory: File) {
        Files.deleteIfExists(
            modelDirectory.toPath().toAbsolutePath().normalize().resolve(MARKER_FILE_NAME),
        )
    }

    /**
     * Deletes only files that disagree with this voice's official manifest. Valid files from a
     * partially complete voice are retained, and sibling voice/STT/application data is untouched.
     */
    fun removeInvalidArtifacts(
        modelRoot: File,
        modelDirectory: File,
        expectedFiles: Collection<MoonshineTtsExpectedFile>,
    ): Int {
        val root = modelRoot.toPath().toAbsolutePath().normalize()
        val directory = modelDirectory.toPath().toAbsolutePath().normalize()
        require(directory.parent == root) { "Moonshine TTS voice escaped the model cache root" }
        val normalized = normalizeExpectedFiles(expectedFiles)
        invalidateMarker(modelDirectory)

        var removed = 0
        normalized.forEach { expected ->
            val destination = resolveExpectedPath(directory, expected.relativePath)
            if (!downloadedFileMatches(directory, expected)) {
                val partial = Paths.get(destination.toString() + PART_SUFFIX)
                val finalArtifactExists = Files.exists(destination, LinkOption.NOFOLLOW_LINKS)
                if (finalArtifactExists) {
                    // Never combine a partial response with a final artifact that already failed
                    // the release-pinned size/hash check.
                    if (deleteArtifact(destination)) removed += 1
                    if (deleteArtifact(partial)) removed += 1
                } else if (!isSafeResumablePartial(partial, expected.expectedSize)) {
                    if (deleteArtifact(partial)) removed += 1
                }
            }
        }
        return removed
    }

    /** AssetDownloader resumes only a non-empty, strictly incomplete regular `.part` file. */
    private fun isSafeResumablePartial(partial: Path, expectedSize: Long): Boolean = runCatching {
        if (!Files.isRegularFile(partial, LinkOption.NOFOLLOW_LINKS)) return@runCatching false
        val actualSize = Files.size(partial)
        actualSize > 0L && actualSize < expectedSize
    }.getOrDefault(false)

    private fun readMarker(modelDirectory: Path): List<MoonshineTtsExpectedFile>? {
        val directory = modelDirectory.toAbsolutePath().normalize()
        val marker = directory.resolve(MARKER_FILE_NAME)
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) return null
        if (Files.size(marker) !in 1..MAX_MARKER_BYTES) return null
        val lines = String(Files.readAllBytes(marker), Charsets.UTF_8)
            .split('\n')
            .dropLastWhile(String::isEmpty)
        if (lines.size !in 3..MAX_MARKER_LINES || lines.first() != MARKER_HEADER) return null
        val expectedDigest = lines[1].takeIf(SHA256_PATTERN::matches) ?: return null
        val entries = lines.drop(2).map { line ->
            val fields = line.split('\t', limit = 3)
            if (fields.size != 3) return null
            val expectedSize = fields[0].toLongOrNull()?.takeIf { it >= 0L } ?: return null
            val sha256 = fields[1]
                .takeUnless { it == NO_HASH }
                ?.lowercase()
                ?.takeIf(SHA256_PATTERN::matches)
                ?: if (fields[1] == NO_HASH) null else return null
            runCatching {
                MoonshineTtsExpectedFile(
                    relativePath = normalizeRelativePath(fields[2]),
                    expectedSize = expectedSize,
                    sha256 = sha256,
                )
            }.getOrElse { return null }
        }
        val normalized = runCatching { normalizeExpectedFiles(entries) }.getOrElse { return null }
        if (normalized.size != entries.size || manifestDigest(normalized) != expectedDigest) return null
        return normalized
    }

    private fun buildMarker(expectedFiles: List<MoonshineTtsExpectedFile>): String = buildString {
        append(MARKER_HEADER).append('\n')
        append(manifestDigest(expectedFiles)).append('\n')
        expectedFiles.forEach { expected ->
            append(expected.expectedSize)
                .append('\t')
                .append(expected.sha256 ?: NO_HASH)
                .append('\t')
                .append(expected.relativePath)
                .append('\n')
        }
    }

    private fun normalizeExpectedFiles(
        expectedFiles: Collection<MoonshineTtsExpectedFile>,
    ): List<MoonshineTtsExpectedFile> {
        require(expectedFiles.size <= MAX_MANIFEST_FILES) {
            "Moonshine TTS manifest contains too many files"
        }
        val normalized = expectedFiles.map { expected ->
            require(expected.expectedSize >= 0L) {
                "Moonshine TTS manifest is missing an exact file size"
            }
            val sha256 = expected.sha256?.lowercase()?.also { hash ->
                require(SHA256_PATTERN.matches(hash)) {
                    "Moonshine TTS manifest contains an invalid SHA-256"
                }
            }
            MoonshineTtsExpectedFile(
                relativePath = normalizeRelativePath(expected.relativePath),
                expectedSize = expected.expectedSize,
                sha256 = sha256,
            )
        }.sortedBy(MoonshineTtsExpectedFile::relativePath)
        require(normalized.map(MoonshineTtsExpectedFile::relativePath).distinct().size == normalized.size) {
            "Moonshine TTS manifest contains duplicate paths"
        }
        return normalized
    }

    private fun normalizeRelativePath(value: String): String {
        require(value.isNotBlank() && value.none { it == '\t' || it == '\r' || it == '\n' }) {
            "Moonshine TTS manifest contains an invalid path"
        }
        require(!value.contains('\\')) { "Moonshine TTS manifest path must use '/'" }
        val original = Paths.get(value)
        require(!original.isAbsolute && original.nameCount > 0) {
            "Moonshine TTS manifest contains an absolute or empty path"
        }
        val normalized = original.normalize()
        require(
            normalized.nameCount > 0 &&
                normalized.none { part -> part.toString() == ".." } &&
                normalized.toString() == value,
        ) { "Moonshine TTS manifest contains an unsafe path" }
        return value
    }

    private fun downloadedFileMatches(
        directory: Path,
        expected: MoonshineTtsExpectedFile,
    ): Boolean {
        val target = resolveExpectedPath(directory.toAbsolutePath().normalize(), expected.relativePath)
        if (!downloadedFileHasExpectedSize(directory, expected)) return false
        return expected.sha256 == null || sha256(target) == expected.sha256
    }

    private fun downloadedFileHasExpectedSize(
        directory: Path,
        expected: MoonshineTtsExpectedFile,
    ): Boolean {
        val target = resolveExpectedPath(directory.toAbsolutePath().normalize(), expected.relativePath)
        return Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) &&
            Files.size(target) == expected.expectedSize
    }

    private fun resolveExpectedPath(directory: Path, relativePath: String): Path {
        val relative = Paths.get(normalizeRelativePath(relativePath))
        val resolved = directory.resolve(relative).normalize()
        require(resolved.startsWith(directory) && resolved != directory) {
            "Moonshine TTS manifest path escaped its voice directory"
        }
        return resolved
    }

    private fun manifestDigest(expectedFiles: List<MoonshineTtsExpectedFile>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        expectedFiles.forEach { expected ->
            digest.update(expected.relativePath.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(expected.expectedSize.toString().toByteArray(Charsets.US_ASCII))
            digest.update(0.toByte())
            digest.update((expected.sha256 ?: NO_HASH).toByteArray(Charsets.US_ASCII))
            digest.update('\n'.code.toByte())
        }
        return digest.digest().toHex()
    }

    private fun sha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).buffered(HASH_BUFFER_BYTES).use { input ->
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun deleteArtifact(target: Path): Boolean {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return false
        Files.walkFileTree(
            target,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(
                    directory: Path,
                    error: java.io.IOException?,
                ): FileVisitResult {
                    if (error != null) throw error
                    Files.delete(directory)
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return true
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }

    private const val MARKER_FILE_NAME = ".guidecast-integrity-v1"
    private const val MARKER_HEADER = "guidecast-moonshine-tts-integrity-v1"
    private const val NO_HASH = "-"
    private const val PART_SUFFIX = ".part"
    private const val HASH_BUFFER_BYTES = 128 * 1_024
    private const val MAX_MARKER_BYTES = 256L * 1_024
    private const val MAX_MARKER_LINES = 258
    private const val MAX_MANIFEST_FILES = 256
    private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
}
