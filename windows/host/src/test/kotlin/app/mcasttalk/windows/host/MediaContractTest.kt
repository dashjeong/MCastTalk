package app.mcasttalk.windows.host

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class MediaContractTest {
    private fun frame(samples: Int = 4000): ByteArray = ByteBuffer.allocate(16+samples*2).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(0x3154434d).putInt(1).putInt(16000).putInt(samples).array()
    @Test fun acceptsMinimumAndMaximumVoiceFrames() {
        assertEquals(8000, parseVoiceUtterance(frame()).pcm.size)
        assertEquals(192000, parseVoiceUtterance(frame(96000)).pcm.size)
    }
    @Test fun rejectsTruncatedFrame() { assertThrows(IllegalArgumentException::class.java) { parseVoiceUtterance(frame().copyOf(8015)) } }
    @Test fun rejectsOversizedFrame() { assertThrows(IllegalArgumentException::class.java) { parseVoiceUtterance(frame(96001)) } }
    @Test fun rejectsForgedLength() { val f=frame(); f[12]=0; assertThrows(IllegalArgumentException::class.java) { parseVoiceUtterance(f) } }
    @Test fun rejectsWrongMagic() { val f=frame(); f[0]=0; assertThrows(IllegalArgumentException::class.java) { parseVoiceUtterance(f) } }
    @Test fun rejectsWrongRate() { val f=frame(); f[8]=0; assertThrows(IllegalArgumentException::class.java) { parseVoiceUtterance(f) } }
    @Test fun rejectsNegativeSequence() { val f=frame(); ByteBuffer.wrap(f).order(ByteOrder.LITTLE_ENDIAN).putInt(4,-1); assertThrows(IllegalArgumentException::class.java) { parseVoiceUtterance(f) } }
    @Test fun privateAddressesAreExplicit() {
        listOf("10.0.0.1","172.16.1.1","172.31.255.254","192.168.0.1").forEach { assertTrue(isPrivateIpv4(it)) }
        listOf("8.8.8.8","127.0.0.1","169.254.1.1","172.32.1.1","192.168.001.1","192.168.1.999","example.com").forEach { assertFalse(isPrivateIpv4(it)) }
    }
    @Test fun lanRequiresExplicitTlsConfiguration() {
        assertThrows(IllegalArgumentException::class.java) { HostConfig.parse(arrayOf("--bind=0.0.0.0")) }
        val config=HostConfig.parse(arrayOf("--bind=0.0.0.0","--lan-hosts=192.168.1.9"))
        assertEquals(setOf("192.168.1.9"),config.lanHosts)
    }
    @Test fun onlyAllowlistedHttpsOriginsPass() {
        val host=listOf("192.168.1.9:8787")
        assertTrue(isTrustedWebSocketOrigin(host,listOf("https://192.168.1.9:8787"),"https",8787,setOf("192.168.1.9")))
        assertFalse(isTrustedWebSocketOrigin(host,listOf("http://192.168.1.9:8787"),"http",8787,setOf("192.168.1.9")))
        assertFalse(isTrustedWebSocketOrigin(host,listOf("https://192.168.1.8:8787"),"https",8787,setOf("192.168.1.9")))
        assertFalse(isTrustedWebSocketOrigin(host,listOf("https://192.168.1.9:8787"),"https",8787,emptySet()))
    }
    @Test fun certificateIsStableAndNewIpDoesNotSilentlyRotateIt() {
        val root=Files.createTempDirectory("mcasttalk-tls-test")
        try {
            val first=LanTls.openOrCreate(root,setOf("192.168.1.9"))
            val again=LanTls.openOrCreate(root,setOf("192.168.1.9"))
            assertArrayEquals(first.keyStore.getCertificate("mcasttalk").encoded,again.keyStore.getCertificate("mcasttalk").encoded)
            assertThrows(IllegalArgumentException::class.java) { LanTls.openOrCreate(root,setOf("192.168.1.10")) }
        } finally { root.toFile().deleteRecursively() }
    }
}
