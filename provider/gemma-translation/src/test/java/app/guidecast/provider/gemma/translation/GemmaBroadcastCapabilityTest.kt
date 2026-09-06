package app.guidecast.provider.gemma.translation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaBroadcastCapabilityTest {
    @Test
    fun twoGiBDeviceIsRejectedBeforeNativeModelLoad() {
        val capability = GemmaBroadcastCapability.forTotalMemory(2L * 1024 * 1024 * 1024)
        assertFalse(capability.supported)
        assertFalse(capability.constrainedMemoryMode)
        assertFalse(capability.loadPermittedNow)
        assertTrue(capability.message.contains("자동 전환"))
    }

    @Test
    fun nominalFourGiBDeviceIsRejected() {
        val capability = GemmaBroadcastCapability.forTotalMemory(4L * 1024 * 1024 * 1024)
        assertFalse(capability.supported)
        assertFalse(capability.constrainedMemoryMode)
        assertFalse(capability.loadPermittedNow)
        assertTrue(capability.message.contains("5.0 GiB 이상"))
    }

    @Test
    fun nominalSixGiBDeviceUsesConservativeGemmaLane() {
        val capability = GemmaBroadcastCapability.forTotalMemory(6L * 1024 * 1024 * 1024)
        assertTrue(capability.supported)
        assertTrue(capability.constrainedMemoryMode)
        assertTrue(capability.loadPermittedNow)
        assertTrue(capability.message.contains("6GB급 메모리 절약 모드"))
    }

    @Test
    fun nominalEightGiBDeviceIsAccepted() {
        val capability = GemmaBroadcastCapability.forTotalMemory(7L * 1024 * 1024 * 1024)
        assertTrue(capability.supported)
        assertFalse(capability.constrainedMemoryMode)
        assertTrue(capability.loadPermittedNow)
    }

    @Test
    fun androidReportedFiveGiBBoundaryIsAcceptedForNominalSixGiBHardware() {
        val capability = GemmaBroadcastCapability.forTotalMemory(5L * 1024 * 1024 * 1024)
        assertTrue(capability.supported)
        assertTrue(capability.constrainedMemoryMode)
        assertTrue(capability.loadPermittedNow)
    }

    @Test
    fun supportedHardwareSerializesLoadWhenOnlyTwoGiBIsCurrentlyAvailable() {
        val capability = GemmaBroadcastCapability.forMemory(
            totalMemoryBytes = 6_700L * 1024 * 1024,
            availableMemoryBytes = 2L * 1024 * 1024 * 1024,
            systemLowMemory = false,
        )
        assertTrue(capability.supported)
        assertTrue(capability.constrainedMemoryMode)
        assertFalse(capability.loadPermittedNow)
        assertTrue(capability.message.contains("콜드 로드 순차 실행"))
        assertTrue(capability.message.contains("선택한 Gemma를 실제 시도"))
    }

    @Test
    fun temporaryLowMemoryKeepsSelectionAndReportsQualityPreservingSerialization() {
        val capability = GemmaBroadcastCapability.forMemory(
            totalMemoryBytes = 6L * 1024 * 1024 * 1024,
            availableMemoryBytes = 512L * 1024 * 1024,
            systemLowMemory = true,
        )
        assertTrue(capability.supported)
        assertTrue(capability.systemLowMemory)
        assertFalse(capability.loadPermittedNow)
        assertTrue(capability.message.contains("콜드 로드 순차 실행"))
        assertTrue(capability.message.contains("실제 초기화 실패 시 해당 문장만 대체 번역"))
    }

    @Test
    fun normalSixGiBDeviceWithFourGiBAvailableCanLoadGemma() {
        val capability = GemmaBroadcastCapability.forMemory(
            totalMemoryBytes = 6L * 1024 * 1024 * 1024,
            availableMemoryBytes = 4L * 1024 * 1024 * 1024,
            systemLowMemory = false,
        )

        assertTrue(capability.supported)
        assertTrue(capability.constrainedMemoryMode)
        assertTrue(capability.loadPermittedNow)
    }

    @Test
    fun threeGiBAvailableIsTheExactSessionLoadBoundary() {
        val boundary = GemmaBroadcastCapability.forMemory(
            totalMemoryBytes = 6L * 1024 * 1024 * 1024,
            availableMemoryBytes = GemmaBroadcastCapability.MIN_AVAILABLE_MEMORY_BYTES,
            systemLowMemory = false,
        )
        val oneByteBelow = GemmaBroadcastCapability.forMemory(
            totalMemoryBytes = 6L * 1024 * 1024 * 1024,
            availableMemoryBytes = GemmaBroadcastCapability.MIN_AVAILABLE_MEMORY_BYTES - 1L,
            systemLowMemory = false,
        )

        assertTrue(boundary.loadPermittedNow)
        assertFalse(oneByteBelow.loadPermittedNow)
    }

    @Test
    fun androidLowMemorySignalOverridesOtherwiseSufficientAvailableMemory() {
        val capability = GemmaBroadcastCapability.forMemory(
            totalMemoryBytes = 6L * 1024 * 1024 * 1024,
            availableMemoryBytes = 4L * 1024 * 1024 * 1024,
            systemLowMemory = true,
        )

        assertTrue(capability.supported)
        assertFalse(capability.loadPermittedNow)
    }
}
