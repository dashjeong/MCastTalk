package app.mcasttalk.windows.host

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

internal object WindowsSocketTemp {
    private const val PROPERTY = "jdk.net.unixdomain.tmpdir"

    /** Must run before the first Java NIO selector initializes UnixDomainSockets. */
    fun configure() {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
        val configured = System.getProperty(PROPERTY)?.takeIf { it.isNotBlank() }
        val root = if (configured != null) {
            Paths.get(configured)
        } else {
            // Windows AF_UNIX can fail to connect in deeper AppData paths even when
            // ordinary files are writable there. Keep this per-user socket path short.
            Paths.get(System.getProperty("user.home"), ".mcasttalk", "tmp")
        }
        System.setProperty(PROPERTY, prepare(root).toString())
    }

    internal fun prepare(root: Path): Path {
        val normalized = root.toAbsolutePath().normalize()
        // JDK 17 Windows uses UTF-8 AF_UNIX paths and appends socket_<positive int>.
        // Leave room in the native 108-byte address for the terminating zero.
        require(normalized.resolve("socket_2147483647").toString().toByteArray(Charsets.UTF_8).size < 108) {
            "The local socket folder is too long: $normalized. Set -D$PROPERTY to a shorter writable folder."
        }
        Files.createDirectories(normalized)
        val probe = Files.createTempFile(normalized, ".write-", ".tmp")
        Files.delete(probe)
        return normalized
    }
}
