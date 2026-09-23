package app.mcasttalk.windows.host

import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HostConfigTest {
    @Test
    fun parsesExplicitLocalConfiguration() {
        val config = HostConfig.parse(
            arrayOf(
                "--data-dir=C:\\MCastTalkData",
                "--bind=localhost",
                "--port=9090",
            )
        )

        assertEquals(Paths.get("C:\\MCastTalkData").toAbsolutePath().normalize(), config.dataRoot)
        assertEquals("localhost", config.bindAddress)
        assertEquals(9090, config.port)
    }

    @Test
    fun blocksPublicBindingUntilTlsAndAuthenticationExist() {
        assertThrows(IllegalArgumentException::class.java) {
            HostConfig.parse(arrayOf("--bind=0.0.0.0"))
        }
    }
}
