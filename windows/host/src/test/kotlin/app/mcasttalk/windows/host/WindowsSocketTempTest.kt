package app.mcasttalk.windows.host

import java.nio.file.Files
import java.nio.file.Paths
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assume.assumeTrue

class WindowsSocketTempTest {
    @Test(timeout = 20_000)
    fun freshJvmOpensSelectorWithLongTempEnvironmentAndNoStartupPropertyOverride() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        // Model a normal user home, not a home nested inside the OS Temp tree.
        // TEMP itself remains deliberately long for the startup regression.
        val testRoot = Paths.get(System.getProperty("mcasttalk.test.socketRoot"))
        Files.createDirectories(testRoot)
        val userRoot = Files.createDirectory(
            testRoot.resolve(UUID.randomUUID().toString().take(8))
        )
        try {
            val longTemp = userRoot.resolve("long-temp-" + "x".repeat(110))
            Files.createDirectories(longTemp)
            val classpath = listOf(
                WindowsSocketTemp::class.java,
                SocketStartupProbe::class.java,
                kotlin.Unit::class.java,
            ).map { Paths.get(it.protectionDomain.codeSource.location.toURI()).toString() }
                .distinct().joinToString(File.pathSeparator)
            val builder = ProcessBuilder(
                Paths.get(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-Duser.home=$userRoot",
                "-cp",
                classpath,
                SocketStartupProbe::class.java.name,
            ).redirectErrorStream(true)
            // This probe verifies application startup without JVM property overrides.
            // Build-runner settings must not leak into the fresh child JVM.
            listOf("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS").forEach {
                builder.environment().remove(it)
            }
            builder.environment()["TEMP"] = longTemp.toString()
            builder.environment()["TMP"] = longTemp.toString()
            val process = builder.start()
            try {
                assertTrue("Isolated selector startup timed out", process.waitFor(10, TimeUnit.SECONDS))
                val output = process.inputStream.bufferedReader().use { it.readText() }
                assertEquals(output, 0, process.exitValue())
                assertTrue(output, output.contains("SELECTOR_OPEN=true"))
                assertTrue(output, output.contains("SOCKET_TEMP=" + userRoot.resolve(".mcasttalk/tmp")))
            } finally {
                if (process.isAlive) {
                    process.destroyForcibly()
                    process.waitFor(5, TimeUnit.SECONDS)
                }
            }
        } finally {
            userRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun preparesWritableApplicationDirectoryWithoutChangingGlobalProperty() {
        val parent = Files.createTempDirectory("mct-socket-")
        try {
            val propertyBefore = System.getProperty("jdk.net.unixdomain.tmpdir")
            val requested = parent.resolve("app")
            val prepared = WindowsSocketTemp.prepare(requested)
            assertEquals(requested.toAbsolutePath().normalize(), prepared)
            assertTrue(Files.isDirectory(prepared))
            assertEquals(propertyBefore, System.getProperty("jdk.net.unixdomain.tmpdir"))
            Files.list(prepared).use { assertEquals(0L, it.count()) }
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsTooLongSocketPathsBeforeWritingAnything() {
        val parent = Files.createTempDirectory("mct-socket-")
        try {
            val requested = parent.resolve("long".repeat(40))
            val error = assertThrows(IllegalArgumentException::class.java) {
                WindowsSocketTemp.prepare(requested)
            }
            assertTrue(error.message.orEmpty().contains("jdk.net.unixdomain.tmpdir"))
            assertTrue(!Files.exists(requested))
        } finally {
            parent.toFile().deleteRecursively()
        }
    }
}
