package app.guidecast.provider.moonshine.tts

import android.content.Context
import ai.moonshine.voice.ModelCache
import ai.moonshine.voice.ModelSpec
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

internal data class MoonshineTtsCacheSchemaState(
    val ready: Boolean,
    val errorMessage: String? = null,
    val clearedDirectoryCount: Int = 0,
)

/**
 * One-time boundary between the 0.2.3 existence-only cache and integrity-marked voice downloads.
 *
 * Schema upgrades intentionally do not delete legacy downloads. The isolated worker validates
 * each requested voice against Moonshine's current official manifest and promotes valid files by
 * writing a per-voice marker. Only an actually mismatched artifact is removed there, so valid
 * voices survive both the integrity-marker migration and later catalog expansion.
 */
internal object MoonshineTtsCacheSchema {
    private val inProcessLock = Any()

    fun ensureCurrent(context: Context): MoonshineTtsCacheSchemaState = runCatching {
        val applicationContext = context.applicationContext
        val modelRoot = ModelCache.defaultRoot(applicationContext)
        val voiceDirectories = MoonshineTtsVoiceCatalog.voices.values.map { voice ->
            val modelSpec = ModelSpec.tts(voice.moonshineLanguageTag, voice.voiceId)
            // ModelCache.directoryFor() creates the directory. Building the exact path from its
            // public key avoids creating six empty directories on a fresh installation.
            File(modelRoot, ModelCache.key(modelSpec))
        }
        val stateRoot = File(
            applicationContext.noBackupFilesDir ?: applicationContext.filesDir,
            STATE_DIRECTORY_NAME,
        )
        migrate(
            modelRoot = modelRoot,
            officialVoiceDirectories = voiceDirectories,
            markerFile = File(stateRoot, MARKER_FILE_NAME),
            lockFile = File(stateRoot, LOCK_FILE_NAME),
        )
    }.getOrElse { error ->
        MoonshineTtsCacheSchemaState(
            ready = false,
            errorMessage = error.message ?: error.javaClass.simpleName,
        )
    }

    internal fun migrate(
        modelRoot: File,
        officialVoiceDirectories: Collection<File>,
        markerFile: File,
        lockFile: File,
    ): MoonshineTtsCacheSchemaState = synchronized(inProcessLock) {
        val markerParent = requireNotNull(markerFile.parentFile) {
            "Moonshine TTS cache schema marker has no parent directory"
        }
        val lockParent = requireNotNull(lockFile.parentFile) {
            "Moonshine TTS cache schema lock has no parent directory"
        }
        check(markerParent.absoluteFile.normalize() == lockParent.absoluteFile.normalize()) {
            "Moonshine TTS cache schema files must share one directory"
        }
        Files.createDirectories(markerParent.toPath())

        FileChannel.open(
            lockFile.toPath(),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        ).use { channel ->
            channel.lock().use {
                val installedVersion = readMarkerVersion(markerFile.toPath())
                if (installedVersion >= CURRENT_SCHEMA_VERSION) {
                    return@synchronized MoonshineTtsCacheSchemaState(ready = true)
                }

                val modelRootPath = modelRoot.toPath().toAbsolutePath().normalize()
                val targets = officialVoiceDirectories
                    .map { it.toPath().toAbsolutePath().normalize() }
                    .distinct()
                require(targets.size == OFFICIAL_VOICE_DIRECTORY_COUNT) {
                    "Moonshine TTS migration requires exactly six official voice directories"
                }
                targets.forEach { target ->
                    require(target.parent == modelRootPath) {
                        "Moonshine TTS migration target escaped the model cache root"
                    }
                }

                writeMarkerAtomically(markerFile.toPath(), CURRENT_SCHEMA_VERSION)
                MoonshineTtsCacheSchemaState(ready = true)
            }
        }
    }

    private fun readMarkerVersion(marker: Path): Int {
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) return 0
        return runCatching {
            String(Files.readAllBytes(marker), Charsets.UTF_8).trim().toInt()
        }.getOrDefault(0)
    }

    private fun writeMarkerAtomically(marker: Path, version: Int) {
        val parent = requireNotNull(marker.parent)
        val temporary = Files.createTempFile(parent, marker.fileName.toString(), ".tmp")
        try {
            FileChannel.open(
                temporary,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            ).use { channel ->
                channel.write(
                    java.nio.ByteBuffer.wrap("$version\n".toByteArray(Charsets.UTF_8)),
                )
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
    }

    private const val CURRENT_SCHEMA_VERSION = 3
    private const val OFFICIAL_VOICE_DIRECTORY_COUNT = 6
    private const val STATE_DIRECTORY_NAME = "guidecast-cache-schema"
    private const val MARKER_FILE_NAME = "moonshine-tts.version"
    private const val LOCK_FILE_NAME = "moonshine-tts.lock"
}
