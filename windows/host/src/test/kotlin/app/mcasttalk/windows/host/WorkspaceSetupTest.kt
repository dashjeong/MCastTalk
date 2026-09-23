package app.mcasttalk.windows.host

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceSetupTest {
    @Test
    fun initializesCompleteDataRootIdempotently() {
        val parent = Files.createTempDirectory("mcasttalk-workspace-test")
        try {
            val root = parent.resolve("MCastTalkData")
            val first = WorkspaceSetup.initialize(root)
            val second = WorkspaceSetup.initialize(root)

            assertEquals(first.instanceId, second.instanceId)
            assertTrue(root.resolve("models/asr").exists())
            assertTrue(root.resolve("models/nmt").exists())
            assertTrue(root.resolve("models/tts").exists())
            assertTrue(root.resolve("licenses").exists())
            assertTrue(root.resolve("config/data-root.json").exists())
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun remembersWorkspaceWithJsonEscaping() {
        val parent = Files.createTempDirectory("mcasttalk-settings-test")
        try {
            val settings = parent.resolve("config/launcher.json")
            val root = parent.resolve("data with spaces")

            WorkspaceSetup.rememberRoot(settings, root)

            assertEquals(
                root.toAbsolutePath().normalize(),
                WorkspaceSetup.readRememberedRoot(settings),
            )
            assertTrue(settings.readText().contains("schemaVersion"))
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun failedInitializationPreservesThePreviousWorkspacePointer() {
        val parent = Files.createTempDirectory("mcasttalk-invalid-workspace-test")
        try {
            val settings = parent.resolve("settings/launcher.json")
            val previous = parent.resolve("previous")
            val invalid = parent.resolve("not-a-directory")
            invalid.writeText("existing file")
            WorkspaceSetup.rememberRoot(settings, previous)
            val previousSettings = settings.readText()

            assertThrows(IllegalArgumentException::class.java) {
                WorkspaceSetup.initializeAndRemember(settings, invalid)
            }

            assertEquals(previousSettings, settings.readText())
            assertEquals(previous.toAbsolutePath().normalize(), WorkspaceSetup.readRememberedRoot(settings))
            assertEquals("existing file", invalid.readText())
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun retriesFailedSelectionAndPersistsOnlyAnInitializedWorkspace() {
        val parent = Files.createTempDirectory("mcasttalk-reselect-test")
        try {
            val settings = parent.resolve("settings/launcher.json")
            val previous = parent.resolve("previous")
            val invalid = parent.resolve("not-a-directory")
            val selected = parent.resolve("selected")
            invalid.writeText("existing file")
            WorkspaceSetup.rememberRoot(settings, previous)
            val choices = ArrayDeque(listOf(invalid, selected))
            val failures = mutableListOf<Path>()

            val resolved = WorkspaceSetup.resolveWorkspace(
                selected,
                settings,
                selectDirectory = { choices.removeFirst() },
                onInitializationFailure = { failedRoot, _ ->
                    failures.add(failedRoot)
                    assertEquals(previous.toAbsolutePath().normalize(), WorkspaceSetup.readRememberedRoot(settings))
                },
            )

            assertEquals(listOf(invalid), failures)
            assertEquals(selected.toAbsolutePath().normalize(), resolved)
            assertEquals(resolved, WorkspaceSetup.readRememberedRoot(settings))
            assertTrue(resolved.resolve("config/data-root.json").exists())
            DataRootGate.requireInitialized(resolved)
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun damagedRememberedWorkspaceAllowsReselection() {
        val parent = Files.createTempDirectory("mcasttalk-damaged-workspace-test")
        try {
            val settings = parent.resolve("settings/launcher.json")
            val previous = parent.resolve("previous")
            val selected = parent.resolve("selected")
            WorkspaceSetup.initializeAndRemember(settings, previous)
            previous.resolve("config/data-root.json").writeText("invalid marker")
            var failures = 0

            val resolved = WorkspaceSetup.resolveWorkspace(
                selected,
                settings,
                selectDirectory = { selected },
                onInitializationFailure = { failedRoot, _ ->
                    assertEquals(previous, failedRoot)
                    assertEquals(previous, WorkspaceSetup.readRememberedRoot(settings))
                    failures++
                },
            )

            assertEquals(1, failures)
            assertEquals(selected, resolved)
            assertEquals(selected, WorkspaceSetup.readRememberedRoot(settings))
        } finally {
            parent.toFile().deleteRecursively()
        }
    }
}
