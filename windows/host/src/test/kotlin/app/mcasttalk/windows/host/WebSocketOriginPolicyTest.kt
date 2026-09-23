package app.mcasttalk.windows.host

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSocketOriginPolicyTest {
    @Test
    fun acceptsOnlyMatchingLoopbackHostSchemeAndActualLocalPort() {
        for (host in listOf("127.0.0.1", "localhost", "[::1]")) {
            assertTrue(isTrustedWebSocketOrigin(listOf("$host:8787"), listOf("http://$host:8787"), "http", 8787))
            assertTrue(isTrustedWebSocketOrigin(listOf(host), listOf("https://$host"), "https", 443))
        }
        assertTrue(isTrustedWebSocketOrigin(listOf("LOCALHOST:8787"), listOf("http://localhost:8787"), "http", 8787))
    }

    @Test
    fun rejectsMissingNullDuplicateMalformedAndCrossOriginValues() {
        val host = listOf("localhost:8787")
        for (origin in listOf(
            null, emptyList(), listOf("null"), listOf("http://localhost:8787", "http://localhost:8787"),
            listOf("http://evil.invalid:8787"), listOf("http://localhost:8788"), listOf("https://localhost:8787"),
            listOf("http://localhost:8787/"), listOf("http://localhost:8787?x=1"),
            listOf("http://user@localhost:8787"), listOf("http://localhost:8787#x"),
            listOf(" http://localhost:8787"), listOf("http://127.0.0.1:8787"),
        )) assertFalse("Origin=$origin", isTrustedWebSocketOrigin(host, origin, "http", 8787))
        for (badHost in listOf(null, emptyList(), listOf("evil.invalid:8787"), listOf("localhost:8788"),
            listOf("localhost:8787", "localhost:8787"), listOf("user@localhost:8787"), listOf("localhost:8787/path"))) {
            assertFalse("Host=$badHost", isTrustedWebSocketOrigin(badHost, listOf("http://localhost:8787"), "http", 8787))
        }
    }
}
