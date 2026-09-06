package app.guidecast.transmitter

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.guidecast.core.server.*
import app.guidecast.core.stream.*
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ListenerLocalizationDeviceTest {
    @Test fun installedApkServesBundledLocalizationOnEveryPrimaryChannel() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val languages = listOf("source", "en", "ja", "zh", "zh-tw", "vi")
        val streams = AudioStreamRegistry(maxChannels = 6, maxListeners = 14)
        streams.configure(languages.map {
            AudioChannelDescriptor(it, it, when (it) {
                "source" -> "ko"
                "zh-tw" -> "zh-TW"
                else -> it
            }, 24_000)
        })
        val server = GuideCastLocalServer(context, streams).start(
            bindAddress = InetAddress.getByName("127.0.0.1") as Inet4Address,
            config = GuideCastServerConfig(port = 18787, access = BroadcastAccess.Open),
        )
        try {
            withContext(Dispatchers.IO) {
                for (language in languages) {
                    val html = httpGet("/$language")
                    assertTrue("Missing player for $language", html.contains("/player.js"))
                }
                val js = httpGet("/player.js")
                assertTrue(js.contains("GuideCastI18n"))
                assertTrue(js.contains("Tiếng Việt"))
                assertTrue(js.contains("繁體中文"))
                assertTrue(js.indexOf("globalThis.GuideCastI18n =") < js.indexOf("const listenerUi"))
                assertFalse(js.contains(".innerHTML ="))
            }
            // Explicit optional window for desktop-browser checks against this actual APK server.
            if (InstrumentationRegistry.getArguments().getString("holdWeb") == "true") delay(120_000)
        } finally { server.close() }
    }

    // Raw test client, like existing server device tests. Do not relax app network security.
    private fun httpGet(path: String): String = Socket("127.0.0.1", 18787).use { socket ->
        socket.soTimeout = 5_000
        socket.getOutputStream().write(
            "GET $path HTTP/1.1\r\nHost: 127.0.0.1:18787\r\nConnection: close\r\n\r\n".toByteArray(),
        )
        socket.getOutputStream().flush()
        socket.getInputStream().bufferedReader().readText().also {
            assertTrue("$path must return HTTP 200", it.startsWith("HTTP/1.1 200"))
        }
    }
}
