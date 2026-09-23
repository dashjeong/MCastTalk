package app.mcasttalk.windows.host

import java.awt.GraphicsEnvironment
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

private val dataDirectories = listOf(
    "config",
    "db",
    "models/asr",
    "models/nmt",
    "models/tts",
    "models/vad",
    "models/llm",
    "voices",
    "dictionaries",
    "packages",
    "manifests",
    "licenses",
    "transcripts",
    "recordings",
    "cache/audio",
    "cache/translation",
    "certs",
    "logs",
    "diagnostics",
    "temp",
)

object WorkspaceSetup {
    fun resolveOrPrompt(defaultRoot: Path): Path = resolveWorkspace(
        defaultRoot,
        launcherSettingsPath(),
        selectDirectory = { suggested ->
            require(!GraphicsEnvironment.isHeadless()) {
                "No usable workspace is configured. Start with --data-dir=<path> in headless mode."
            }
            chooseDirectory(suggested)
        },
        onInitializationFailure = { root, error ->
            check(!GraphicsEnvironment.isHeadless()) {
                "Cannot initialize workspace $root: ${error.message}. Start with --data-dir=<path>."
            }
            SwingUtilities.invokeAndWait {
                JOptionPane.showMessageDialog(
                    null,
                    "선택한 작업 공간을 준비하지 못했습니다. 다른 폴더를 선택해 주세요.\n$root\n${error.message}",
                    "MCastTalk 작업 공간",
                    JOptionPane.ERROR_MESSAGE,
                )
            }
        },
    )

    internal fun resolveWorkspace(
        defaultRoot: Path,
        settings: Path,
        selectDirectory: (Path) -> Path,
        onInitializationFailure: (Path, Exception) -> Unit,
    ): Path {
        var suggested = defaultRoot
        readRememberedRoot(settings)?.let { remembered ->
            if (remembered.exists() && remembered.isDirectory()) {
                try {
                    initialize(remembered)
                    return remembered
                } catch (error: Exception) {
                    onInitializationFailure(remembered, error)
                    suggested = remembered
                }
            }
        }
        while (true) {
            // Selection cancellation exits without changing the saved workspace.
            val selected = selectDirectory(suggested).toAbsolutePath().normalize()
            try {
                initializeAndRemember(settings, selected)
                return selected
            } catch (error: Exception) {
                onInitializationFailure(selected, error)
                suggested = selected
            }
        }
    }

    internal fun initializeAndRemember(settings: Path, selected: Path): DataRootIdentity {
        val identity = initialize(selected)
        rememberRoot(settings, selected)
        return identity
    }

    fun initialize(root: Path): DataRootIdentity {
        val normalized = root.toAbsolutePath().normalize()
        require(!normalized.exists() || normalized.isDirectory()) {
            "Data root points to a file: $normalized"
        }
        normalized.createDirectories()
        assertWritable(normalized)
        dataDirectories.forEach { relative ->
            val destination = normalized.resolve(relative).normalize()
            require(destination.startsWith(normalized)) {
                "Unsafe data directory: $relative"
            }
            require(!destination.exists() || destination.isDirectory()) {
                "Expected a directory but found a file: $destination"
            }
            destination.createDirectories()
        }
        val marker = normalized.resolve("config/data-root.json")
        if (!marker.exists()) {
            atomicWrite(
                marker,
                encodeJson(
                    linkedMapOf(
                        "schemaVersion" to 1,
                        "instanceId" to UUID.randomUUID().toString(),
                        "createdAt" to Instant.now().toString(),
                        "root" to normalized.toString(),
                    )
                ) + "\n",
            )
        }
        return DataRootGate.requireInitialized(normalized)
    }

    fun defaultRoot(): Path {
        val packagedPath = System.getProperty("jpackage.app-path")
            ?.takeIf { it.isNotBlank() }
            ?.let(Paths::get)
        val base = packagedPath?.parent ?: Paths.get(System.getProperty("user.dir"))
        return base.resolve("MCastTalkData").toAbsolutePath().normalize()
    }

    internal fun readRememberedRoot(settings: Path): Path? {
        if (!settings.isRegularFile()) {
            return null
        }
        return runCatching {
            parseFlatJsonObject(settings.readText(), maxChars = 16 * 1024)
                .optionalString("dataRoot")
                ?.takeIf { it.isNotBlank() }
                ?.let(Paths::get)
                ?.toAbsolutePath()
                ?.normalize()
        }.getOrNull()
    }

    internal fun rememberRoot(settings: Path, root: Path) {
        settings.parent.createDirectories()
        atomicWrite(
            settings,
            encodeJson(
                linkedMapOf(
                    "schemaVersion" to 1,
                    "dataRoot" to root.toAbsolutePath().normalize().toString(),
                )
            ) + "\n",
        )
    }

    internal fun launcherSettingsPath(): Path {
        val localAppData = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
        val base = if (localAppData != null) {
            Paths.get(localAppData)
        } else {
            Paths.get(System.getProperty("user.home"), "AppData", "Local")
        }
        return base.resolve("MCastTalk/launcher.json").toAbsolutePath().normalize()
    }

    private fun chooseDirectory(defaultRoot: Path): Path {
        var result: Path? = null
        var approved = false
        SwingUtilities.invokeAndWait {
            val chooser = JFileChooser().apply {
                dialogTitle = "MCastTalk 작업 공간 선택"
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                isAcceptAllFileFilterUsed = false
                selectedFile = defaultRoot.toFile()
                approveButtonText = "이 폴더 사용"
            }
            approved = chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION
            result = chooser.selectedFile?.toPath()?.toAbsolutePath()?.normalize()
        }
        require(approved && result != null) { "Workspace selection was cancelled" }
        return result!!
    }

    private fun assertWritable(root: Path) {
        val probe = root.resolve(".mcasttalk-write-probe-${UUID.randomUUID()}")
        try {
            probe.writeText("ok")
        } finally {
            Files.deleteIfExists(probe)
        }
    }

    private fun atomicWrite(path: Path, text: String) {
        val temporary = path.resolveSibling(".${path.fileName}.${UUID.randomUUID()}.tmp")
        try {
            temporary.writeText(text)
            try {
                Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
