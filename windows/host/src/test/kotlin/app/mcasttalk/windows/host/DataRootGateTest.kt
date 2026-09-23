package app.mcasttalk.windows.host

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DataRootGateTest {
    @Test
    fun acceptsBootstrapMarker() {
        val root = Files.createTempDirectory("mcasttalk-data-test")
        try {
            val config = root.resolve("config").createDirectories()
            config.resolve("data-root.json").writeText(
                """{"schemaVersion":1,"instanceId":"test-instance"}"""
            )

            val identity = DataRootGate.requireInitialized(root)
            assertEquals(1, identity.schemaVersion)
            assertEquals("test-instance", identity.instanceId)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsMissingMarker() {
        val root = Files.createTempDirectory("mcasttalk-data-test")
        try {
            assertThrows(IllegalArgumentException::class.java) {
                DataRootGate.requireInitialized(root)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
