package app.guidecast.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class GuideCastRemoteClientTest {
    @Test
    fun parsesQrAddressWithoutLeakingFragmentIntoBaseUrl() {
        val endpoint = parseGuideCastEndpoint(
            "http://192.168.43.1:8787/#token=0123456789abcdef0123456789abcdef",
        )

        assertEquals("http://192.168.43.1:8787/", endpoint.displayUrl)
        assertEquals("0123456789abcdef0123456789abcdef", endpoint.token)
    }

    @Test
    fun addsHttpSchemeAndDefaultPort() {
        val endpoint = parseGuideCastEndpoint("192.168.232.1")

        assertEquals("http://192.168.232.1:80/", endpoint.displayUrl)
        assertNull(endpoint.token)
    }

    @Test
    fun rejectsCredentialsAndUnsupportedSchemes() {
        assertThrows(IllegalArgumentException::class.java) {
            parseGuideCastEndpoint("ftp://192.168.43.1/file")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseGuideCastEndpoint("http://user:password@192.168.43.1:8787/")
        }
    }

    @Test
    fun resolvesLanguageByStableNameOrTag() {
        assertEquals(ClientTargetLanguage.DUTCH, ClientTargetLanguage.fromId("DUTCH"))
        assertEquals(ClientTargetLanguage.HONG_KONG, ClientTargetLanguage.fromId("zh-Hant-HK"))
        assertEquals(ClientTargetLanguage.JAPANESE, ClientTargetLanguage.fromId("missing"))
    }
}
