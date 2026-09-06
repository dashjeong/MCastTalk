package app.guidecast.core.audio

import android.media.AudioDeviceInfo
import android.media.AudioManager
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BluetoothAudioRouteControllerTest {

    private class FakeAudioRoutePlatformAdapter(
        override var sdkInt: Int = 33,
        override var mode: Int = AudioManager.MODE_NORMAL,
    ) : AudioRoutePlatformAdapter {
        override var isBluetoothScoOn: Boolean = false

        val availableCommunicationDevicesList = mutableListOf<PlatformAudioDeviceInfo>()
        var currentCommunicationDevice: PlatformAudioDeviceInfo? = null
        var setCommunicationDeviceReturnValue: Boolean = true
        val setCommunicationDeviceCalls = mutableListOf<PlatformAudioDeviceInfo>()
        val clearCommunicationDeviceCalls = AtomicInteger(0)

        val activeListeners = mutableListOf<CommunicationDeviceChangeListener>()

        val inputDevicesList = mutableListOf<PlatformAudioDeviceInfo>()
        val outputDevicesList = mutableListOf<PlatformAudioDeviceInfo>()

        val startBluetoothScoCalls = AtomicInteger(0)
        val stopBluetoothScoCalls = AtomicInteger(0)
        var awaitLegacyScoBehavior: (suspend (Long) -> Unit)? = null

        override fun startBluetoothSco() {
            startBluetoothScoCalls.incrementAndGet()
            isBluetoothScoOn = true
        }

        override fun stopBluetoothSco() {
            stopBluetoothScoCalls.incrementAndGet()
            isBluetoothScoOn = false
        }

        override fun getAvailableCommunicationDevices(): List<PlatformAudioDeviceInfo> =
            availableCommunicationDevicesList.toList()

        override fun getCommunicationDevice(): PlatformAudioDeviceInfo? =
            currentCommunicationDevice

        override fun setCommunicationDevice(device: PlatformAudioDeviceInfo): Boolean {
            setCommunicationDeviceCalls.add(device)
            return if (setCommunicationDeviceReturnValue) {
                // By default, simulate platform setting device after listener fires or immediately
                true
            } else {
                false
            }
        }

        override fun clearCommunicationDevice() {
            clearCommunicationDeviceCalls.incrementAndGet()
            currentCommunicationDevice = null
        }

        override fun addCommunicationDeviceChangeListener(
            executor: Executor,
            listener: CommunicationDeviceChangeListener,
        ) {
            activeListeners.add(listener)
        }

        override fun removeCommunicationDeviceChangeListener(
            listener: CommunicationDeviceChangeListener,
        ) {
            activeListeners.remove(listener)
        }

        var throwOnGetInputDevices: SecurityException? = null
        var throwOnGetOutputDevices: SecurityException? = null

        override fun getInputDevices(): List<PlatformAudioDeviceInfo> {
            throwOnGetInputDevices?.let { throw it }
            return inputDevicesList.toList()
        }

        override fun getOutputDevices(): List<PlatformAudioDeviceInfo> {
            throwOnGetOutputDevices?.let { throw it }
            return outputDevicesList.toList()
        }

        override suspend fun awaitLegacyScoConnection(timeoutMillis: Long) {
            val behavior = awaitLegacyScoBehavior
            if (behavior != null) {
                behavior.invoke(timeoutMillis)
            } else {
                // Default success
                startBluetoothSco()
            }
        }

        fun fireDeviceChanged(device: PlatformAudioDeviceInfo?) {
            currentCommunicationDevice = device
            activeListeners.toList().forEach { it.onCommunicationDeviceChanged(device) }
        }
    }

    private val directExecutor = Executor { command -> command.run() }

    // Sample Devices
    private val scoMicInput = TestPlatformAudioDeviceInfo(
        id = 101,
        type = AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        productName = "Galaxy Buds Pro (Mic)",
        address = "AA:BB:CC:DD:EE:FF",
        isSource = true,
        isSink = false,
    )

    private val scoSpeakerSink = TestPlatformAudioDeviceInfo(
        id = 102,
        type = AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        productName = "Galaxy Buds Pro (Speaker)",
        address = "AA:BB:CC:DD:EE:FF",
        isSource = false,
        isSink = true,
    )

    private val bleMicInput = TestPlatformAudioDeviceInfo(
        id = 201,
        type = AudioDeviceInfo.TYPE_BLE_HEADSET,
        productName = "Galaxy Buds2 Pro (LE Mic)",
        address = "11:22:33:44:55:66",
        isSource = true,
        isSink = false,
    )

    private val bleSpeakerSink = TestPlatformAudioDeviceInfo(
        id = 202,
        type = AudioDeviceInfo.TYPE_BLE_HEADSET,
        productName = "Galaxy Buds2 Pro (LE Speaker)",
        address = "11:22:33:44:55:66",
        isSource = false,
        isSink = true,
    )

    private val builtInMic = TestPlatformAudioDeviceInfo(
        id = 1,
        type = AudioDeviceInfo.TYPE_BUILTIN_MIC,
        productName = "Phone Microphone",
        address = "",
        isSource = true,
        isSink = false,
    )

    private val builtInSpeaker = TestPlatformAudioDeviceInfo(
        id = 2,
        type = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        productName = "Phone Speaker",
        address = "",
        isSource = false,
        isSink = true,
    )

    @Test
    fun `resolveCompatibleSink strictly selects output sink only`() {
        val adapter = FakeAudioRoutePlatformAdapter()
        // Available communication devices contains a sink and an accidental source
        adapter.availableCommunicationDevicesList.add(scoMicInput) // isSource=true, isSink=false
        adapter.availableCommunicationDevicesList.add(scoSpeakerSink) // isSource=false, isSink=true

        val controller = BluetoothAudioRouteController(adapter, directExecutor)
        val resolvedSink = controller.resolveCompatibleSink(scoMicInput)

        assertTrue("Resolved device must be a sink", resolvedSink.isSink)
        assertFalse("Resolved device must not be a source", resolvedSink.isSource)
        assertEquals(scoSpeakerSink.id, resolvedSink.id)
    }

    @Test
    fun `resolveCompatibleSink distinguishes classic SCO and BLE Headset profiles`() {
        val adapter = FakeAudioRoutePlatformAdapter()
        // Add both classic SCO sink and BLE headset sink
        adapter.availableCommunicationDevicesList.add(scoSpeakerSink)
        adapter.availableCommunicationDevicesList.add(bleSpeakerSink)

        val controller = BluetoothAudioRouteController(adapter, directExecutor)

        // Requesting classic SCO mic MUST select classic SCO sink
        val resolvedSco = controller.resolveCompatibleSink(scoMicInput)
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, resolvedSco.type)
        assertEquals(scoSpeakerSink.id, resolvedSco.id)

        // Requesting BLE mic MUST select BLE sink
        val resolvedBle = controller.resolveCompatibleSink(bleMicInput)
        assertEquals(AudioDeviceInfo.TYPE_BLE_HEADSET, resolvedBle.type)
        assertEquals(bleSpeakerSink.id, resolvedBle.id)
    }

    @Test
    fun `resolveCompatibleSink prioritizes exact address match`() {
        val adapter = FakeAudioRoutePlatformAdapter()
        val otherScoSink = TestPlatformAudioDeviceInfo(
            id = 103,
            type = AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            productName = "Another Headset",
            address = "99:88:77:66:55:44",
            isSource = false,
            isSink = true,
        )
        // Add other headset first, matching headset second
        adapter.availableCommunicationDevicesList.add(otherScoSink)
        adapter.availableCommunicationDevicesList.add(scoSpeakerSink)

        val controller = BluetoothAudioRouteController(adapter, directExecutor)
        val resolved = controller.resolveCompatibleSink(scoMicInput)

        assertEquals("Should match exact Bluetooth MAC address", scoSpeakerSink.id, resolved.id)
        assertEquals(scoMicInput.address, resolved.address)
    }

    @Test
    fun `resolveCompatibleSink throws SinkNotFound when no compatible sink exists`() {
        val adapter = FakeAudioRoutePlatformAdapter()
        // Only built-in speaker available
        adapter.availableCommunicationDevicesList.add(builtInSpeaker)

        val controller = BluetoothAudioRouteController(adapter, directExecutor)
        try {
            controller.resolveCompatibleSink(scoMicInput)
            fail("Expected SinkNotFound exception")
        } catch (e: BluetoothRouteException.SinkNotFound) {
            assertTrue(e.message?.contains("호환되는 Bluetooth 통신 출력 장치") == true)
        }
    }

    @Test
    fun `acquireRouteApi31 sets mode in communication and passes sink to setCommunicationDevice`() = runTest {
        val adapter = FakeAudioRoutePlatformAdapter(sdkInt = 33, mode = AudioManager.MODE_NORMAL)
        adapter.inputDevicesList.add(scoMicInput)
        adapter.availableCommunicationDevicesList.add(scoSpeakerSink)
        // Simulate immediate route switch
        adapter.currentCommunicationDevice = scoSpeakerSink

        val controller = BluetoothAudioRouteController(adapter, directExecutor)
        val lease = controller.acquireRoute(scoMicInput.id)

        assertTrue(lease.isBluetooth)
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, adapter.mode)
        assertEquals(1, adapter.setCommunicationDeviceCalls.size)
        assertEquals(scoSpeakerSink.id, adapter.setCommunicationDeviceCalls.first().id)
        assertTrue(adapter.setCommunicationDeviceCalls.first().isSink)

        // Close lease and check cleanup
        lease.close()
        assertEquals(1, adapter.clearCommunicationDeviceCalls.get())
        assertEquals(AudioManager.MODE_NORMAL, adapter.mode)
    }

    @Test
    fun `acquireRouteApi31 rejection restores mode and throws SinkRejected`() = runTest {
        val adapter = FakeAudioRoutePlatformAdapter(sdkInt = 33, mode = AudioManager.MODE_NORMAL)
        adapter.inputDevicesList.add(scoMicInput)
        adapter.availableCommunicationDevicesList.add(scoSpeakerSink)
        // Simulate Android rejecting the communication device
        adapter.setCommunicationDeviceReturnValue = false

        val controller = BluetoothAudioRouteController(adapter, directExecutor)
        try {
            controller.acquireRoute(scoMicInput.id)
            fail("Expected SinkRejected exception")
        } catch (e: BluetoothRouteException.SinkRejected) {
            assertTrue(e.message?.contains("거부했습니다") == true)
        }

        // Mode must be immediately restored to previous mode
        assertEquals(AudioManager.MODE_NORMAL, adapter.mode)
    }

    @Test
    fun `acquireRouteApi31 awaits listener and succeeds when device changes`() = runTest {
        val adapter = FakeAudioRoutePlatformAdapter(sdkInt = 33, mode = AudioManager.MODE_NORMAL)
        adapter.inputDevicesList.add(scoMicInput)
        adapter.availableCommunicationDevicesList.add(scoSpeakerSink)
        // Current device initially null (switching asynchronously)
        adapter.currentCommunicationDevice = null

        val controller = BluetoothAudioRouteController(adapter, directExecutor)

        val deferredLease = async {
            controller.acquireRoute(scoMicInput.id)
        }

        runCurrent()
        // Listener registered
        assertEquals(1, adapter.activeListeners.size)
        assertFalse(deferredLease.isCompleted)

        // Simulate platform device changed event
        adapter.fireDeviceChanged(scoSpeakerSink)
        runCurrent()

        assertTrue(deferredLease.isCompleted)
        val lease = deferredLease.await()
        assertTrue(lease.isBluetooth)
        assertEquals(scoSpeakerSink.id, lease.selectedSink?.id)

        lease.close()
        assertEquals(AudioManager.MODE_NORMAL, adapter.mode)
    }

    @Test
    fun `acquireRouteApi31 timeout restores mode clears device and throws RouteTimeout`() = runTest {
        val adapter = FakeAudioRoutePlatformAdapter(sdkInt = 33, mode = AudioManager.MODE_NORMAL)
        adapter.inputDevicesList.add(scoMicInput)
        adapter.availableCommunicationDevicesList.add(scoSpeakerSink)
        adapter.currentCommunicationDevice = null // Never changes

        val controller = BluetoothAudioRouteController(adapter, directExecutor)

        supervisorScope {
            val deferredLease = async {
                controller.acquireRoute(scoMicInput.id)
            }

            runCurrent()
            assertEquals(1, adapter.activeListeners.size)

            // Advance time past COMMUNICATION_ROUTE_TIMEOUT_MILLIS (3,000ms)
            advanceTimeBy(BluetoothAudioRouteController.COMMUNICATION_ROUTE_TIMEOUT_MILLIS + 100)
            runCurrent()

            assertTrue(deferredLease.isCompleted)
            try {
                deferredLease.await()
                fail("Expected RouteTimeout exception")
            } catch (e: BluetoothRouteException.RouteTimeout) {
                assertTrue(e.message?.contains("대기 시간 초과") == true)
            }
        }

        assertEquals(0, adapter.activeListeners.size)
        assertEquals(1, adapter.clearCommunicationDeviceCalls.get())
        assertEquals(AudioManager.MODE_NORMAL, adapter.mode)
    }

    @Test
    fun `acquireRouteApi31 cancellation cleans up listener device and mode`() = runTest {
        val adapter = FakeAudioRoutePlatformAdapter(sdkInt = 33, mode = AudioManager.MODE_NORMAL)
        adapter.inputDevicesList.add(scoMicInput)
        adapter.availableCommunicationDevicesList.add(scoSpeakerSink)
        adapter.currentCommunicationDevice = null

        val controller = BluetoothAudioRouteController(adapter, directExecutor)

        val job = async {
            controller.acquireRoute(scoMicInput.id)
        }

        runCurrent()
        assertEquals(1, adapter.activeListeners.size)

        // Cancel while waiting
        job.cancelAndJoin()

        assertEquals(0, adapter.activeListeners.size)
        assertEquals(1, adapter.clearCommunicationDeviceCalls.get())
        assertEquals(AudioManager.MODE_NORMAL, adapter.mode)
    }

    @Test
    fun `lease close is strictly idempotent`() = runTest {
        val adapter = FakeAudioRoutePlatformAdapter(sdkInt = 33, mode = AudioManager.MODE_NORMAL)
        adapter.inputDevicesList.add(scoMicInput)
        adapter.availableCommunicationDevicesList.add(scoSpeakerSink)
        adapter.currentCommunicationDevice = scoSpeakerSink

        val controller = BluetoothAudioRouteController(adapter, directExecutor)
        val lease = controller.acquireRoute(scoMicInput.id)

        assertEquals(0, adapter.clearCommunicationDeviceCalls.get())
        lease.close()
        assertEquals(1, adapter.clearCommunicationDeviceCalls.get())
        assertEquals(AudioManager.MODE_NORMAL, adapter.mode)

        // Second and third close calls should have zero side effects
        lease.close()
        lease.close()
        assertEquals(1, adapter.clearCommunicationDeviceCalls.get())
        assertEquals(AudioManager.MODE_NORMAL, adapter.mode)
    }

    @Test
    fun `legacy SCO acquires and releases correctly on API 29-30`() = runTest {
        val adapter = FakeAudioRoutePlatformAdapter(sdkInt = 30, mode = AudioManager.MODE_NORMAL)
        adapter.inputDevicesList.add(scoMicInput)

        val controller = BluetoothAudioRouteController(adapter, directExecutor)
        val lease = controller.acquireRoute(scoMicInput.id)

        assertTrue(lease.isBluetooth)
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, adapter.mode)
        assertEquals(1, adapter.startBluetoothScoCalls.get())

        lease.close()
        assertEquals(1, adapter.stopBluetoothScoCalls.get())
        assertEquals(AudioManager.MODE_NORMAL, adapter.mode)

        // Idempotency
        lease.close()
        assertEquals(1, adapter.stopBluetoothScoCalls.get())
    }

    @Test
    fun `legacy SCO failure stops SCO restores mode and throws exception`() = runTest {
        val adapter = FakeAudioRoutePlatformAdapter(sdkInt = 30, mode = AudioManager.MODE_NORMAL)
        adapter.inputDevicesList.add(scoMicInput)
        adapter.awaitLegacyScoBehavior = {
            throw BluetoothRouteException.LegacyScoFailed(AudioManager.SCO_AUDIO_STATE_ERROR)
        }

        val controller = BluetoothAudioRouteController(adapter, directExecutor)
        try {
            controller.acquireRoute(scoMicInput.id)
            fail("Expected LegacyScoFailed exception")
        } catch (e: BluetoothRouteException.LegacyScoFailed) {
            assertEquals(AudioManager.SCO_AUDIO_STATE_ERROR, e.state)
        }

        assertEquals(1, adapter.stopBluetoothScoCalls.get())
        assertEquals(AudioManager.MODE_NORMAL, adapter.mode)
    }

    @Test
    fun `verifyRoutedDevice rejects silent fallback to built-in mic`() {
        val controller = BluetoothAudioRouteController(FakeAudioRoutePlatformAdapter())

        // 1. Bluetooth mic requested, but platform routed to built-in microphone
        try {
            controller.verifyRoutedDevice(
                requestedInput = scoMicInput,
                routedDevice = builtInMic,
            )
            fail("Expected DeviceMismatch exception on silent fallback to built-in mic")
        } catch (e: BluetoothRouteException.DeviceMismatch) {
            assertTrue(e.message?.contains("실제 라우팅된 장치 유형은") == true || e.message?.contains("내장/다른 장치") == true)
        }

        // 2. Bluetooth mic requested, correctly routed to Bluetooth
        controller.verifyRoutedDevice(
            requestedInput = scoMicInput,
            routedDevice = scoMicInput,
        )

        // 3. Non-Bluetooth input requested (e.g. built-in mic), routed to built-in mic -> allowed
        controller.verifyRoutedDevice(
            requestedInput = builtInMic,
            routedDevice = builtInMic,
        )

        // 4. Null requested input -> allowed
        controller.verifyRoutedDevice(
            requestedInput = null,
            routedDevice = builtInMic,
        )
    }

    @Test
    fun `resolveCompatibleSink throws AmbiguousRoute when multiple candidate sinks have no identity match`() {
        val adapter = FakeAudioRoutePlatformAdapter()
        val sinkA = TestPlatformAudioDeviceInfo(
            id = 111,
            type = AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            productName = "Tourist Headset A",
            address = "",
            isSource = false,
            isSink = true,
        )
        val sinkB = TestPlatformAudioDeviceInfo(
            id = 112,
            type = AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            productName = "Tourist Headset B",
            address = "",
            isSource = false,
            isSink = true,
        )
        adapter.availableCommunicationDevicesList.add(sinkA)
        adapter.availableCommunicationDevicesList.add(sinkB)

        val controller = BluetoothAudioRouteController(adapter, directExecutor)
        val requestWithoutIdentity = TestPlatformAudioDeviceInfo(
            id = 100,
            type = AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            productName = "Generic Headset",
            address = "",
            isSource = true,
            isSink = false,
        )

        try {
            controller.resolveCompatibleSink(requestWithoutIdentity)
            fail("Expected AmbiguousRoute when multiple sinks have no identity match")
        } catch (e: BluetoothRouteException.AmbiguousRoute) {
            assertTrue(e.message?.isNotBlank() == true)
            assertTrue(e.message?.contains("여러 개") == true)
        }
    }

    @Test
    fun `resolveActiveInput allows sole candidate of matching profile`() {
        val adapter = FakeAudioRoutePlatformAdapter()
        val soleInput = TestPlatformAudioDeviceInfo(
            id = 501,
            type = AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            productName = "Anonymous Mic",
            address = "",
            isSource = true,
            isSink = false,
        )
        adapter.inputDevicesList.add(soleInput)

        val controller = BluetoothAudioRouteController(adapter, directExecutor)
        val resolved = controller.resolveActiveInput(
            requestedInput = TestPlatformAudioDeviceInfo(id = 1, type = AudioDeviceInfo.TYPE_BLUETOOTH_SCO, productName = "", address = "", isSource = true),
            selectedSink = TestPlatformAudioDeviceInfo(id = 2, type = AudioDeviceInfo.TYPE_BLUETOOTH_SCO, productName = "", address = "", isSink = true),
        )

        assertEquals(soleInput.id, resolved.id)
    }

    @Test
    fun `resolveActiveInput throws AmbiguousRoute on multiple candidates without identity`() {
        val adapter = FakeAudioRoutePlatformAdapter()
        val inputA = TestPlatformAudioDeviceInfo(
            id = 501,
            type = AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            productName = "Tourist Mic A",
            address = "",
            isSource = true,
            isSink = false,
        )
        val inputB = TestPlatformAudioDeviceInfo(
            id = 502,
            type = AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            productName = "Tourist Mic B",
            address = "",
            isSource = true,
            isSink = false,
        )
        adapter.inputDevicesList.add(inputA)
        adapter.inputDevicesList.add(inputB)

        val controller = BluetoothAudioRouteController(adapter, directExecutor)
        try {
            controller.resolveActiveInput(
                requestedInput = TestPlatformAudioDeviceInfo(id = 999, type = AudioDeviceInfo.TYPE_BLUETOOTH_SCO, productName = "Unknown", address = "", isSource = true),
                selectedSink = TestPlatformAudioDeviceInfo(id = 888, type = AudioDeviceInfo.TYPE_BLUETOOTH_SCO, productName = "Unknown Sink", address = "", isSink = true),
            )
            fail("Expected AmbiguousRoute when multiple inputs have no identity match")
        } catch (e: BluetoothRouteException.AmbiguousRoute) {
            assertTrue(e.message?.isNotBlank() == true)
            assertTrue(e.message?.contains("여러 개") == true)
        }
    }

    @Test
    fun `getInputDevices throwing SecurityException surfaces PermissionDenied`() = runTest {
        val adapter = FakeAudioRoutePlatformAdapter()
        adapter.throwOnGetInputDevices = SecurityException("Need BLUETOOTH_CONNECT")

        val controller = BluetoothAudioRouteController(adapter, directExecutor)
        try {
            controller.acquireRoute(scoMicInput.id)
            fail("Expected PermissionDenied when adapter throws SecurityException")
        } catch (e: BluetoothRouteException.PermissionDenied) {
            assertTrue(e.cause is SecurityException)
        }
    }

    @Test
    fun `legacy SCO returns immediately without start or await if isBluetoothScoOn is already true`() = runTest {
        val adapter = FakeAudioRoutePlatformAdapter(sdkInt = 30, mode = AudioManager.MODE_NORMAL)
        adapter.isBluetoothScoOn = true
        adapter.inputDevicesList.add(scoMicInput)

        val controller = BluetoothAudioRouteController(adapter, directExecutor)
        val lease = controller.acquireRoute(scoMicInput.id)

        assertTrue(lease.isBluetooth)
        assertEquals(0, adapter.startBluetoothScoCalls.get())

        // Because SCO was already on, this lease did not start SCO and must not stop it on close
        lease.close()
        assertEquals(0, adapter.stopBluetoothScoCalls.get())
        assertEquals(AudioManager.MODE_NORMAL, adapter.mode)
    }

    @Test
    fun `pre-existing legacy SCO lease does not stop SCO or alter mode on close because it does not own route`() = runTest {
        val adapter = FakeAudioRoutePlatformAdapter(sdkInt = 30, mode = AudioManager.MODE_IN_COMMUNICATION)
        adapter.isBluetoothScoOn = true
        adapter.inputDevicesList.add(scoMicInput)

        val controller = BluetoothAudioRouteController(adapter, directExecutor)
        val lease = controller.acquireRoute(scoMicInput.id)

        assertTrue(lease.isBluetooth)
        assertEquals(0, adapter.startBluetoothScoCalls.get())

        lease.close()
        assertEquals(0, adapter.stopBluetoothScoCalls.get())
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, adapter.mode)
    }

    @Test
    fun `verifyRoutedDevice rejects null routed device when Bluetooth was requested`() {
        val controller = BluetoothAudioRouteController(FakeAudioRoutePlatformAdapter())
        try {
            controller.verifyRoutedDevice(
                requestedInput = scoMicInput,
                routedDevice = null as PlatformAudioDeviceInfo?,
            )
            fail("Expected DeviceMismatch when routedDevice is null for Bluetooth")
        } catch (e: BluetoothRouteException.DeviceMismatch) {
            assertTrue(e.message?.contains("routedDevice=null") == true)
        }
    }

    @Test
    fun `verifyRoutedDevice rejects cross profile mismatch SCO vs BLE`() {
        val controller = BluetoothAudioRouteController(FakeAudioRoutePlatformAdapter())

        // SCO requested, BLE routed
        try {
            controller.verifyRoutedDevice(
                requestedInput = scoMicInput,
                routedDevice = bleMicInput,
            )
            fail("Expected DeviceMismatch when SCO routes to BLE")
        } catch (e: BluetoothRouteException.DeviceMismatch) {
            assertTrue(e.message?.contains("Classic Bluetooth SCO 마이크") == true)
        }

        // BLE requested, SCO routed
        try {
            controller.verifyRoutedDevice(
                requestedInput = bleMicInput,
                routedDevice = scoMicInput,
            )
            fail("Expected DeviceMismatch when BLE routes to SCO")
        } catch (e: BluetoothRouteException.DeviceMismatch) {
            assertTrue(e.message?.contains("BLE Audio 마이크") == true)
        }
    }

    @Test
    fun `verifyRoutedDevice rejects identity mismatch when address or name is exposed`() {
        val controller = BluetoothAudioRouteController(FakeAudioRoutePlatformAdapter())

        // Address mismatch
        val wrongAddressMic = scoMicInput.copy(address = "00:11:22:33:44:55")
        try {
            controller.verifyRoutedDevice(
                requestedInput = scoMicInput,
                routedDevice = wrongAddressMic,
            )
            fail("Expected DeviceMismatch on address mismatch")
        } catch (e: BluetoothRouteException.DeviceMismatch) {
            assertTrue(e.message?.contains("주소") == true)
        }

        // Name mismatch
        val touristHeadsetMic = TestPlatformAudioDeviceInfo(
            id = 999,
            type = AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            productName = "Tourist Headset",
            address = "",
            isSource = true,
        )
        val guideHeadsetMic = TestPlatformAudioDeviceInfo(
            id = 888,
            type = AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            productName = "Guide Headset",
            address = "",
            isSource = true,
        )
        try {
            controller.verifyRoutedDevice(
                requestedInput = guideHeadsetMic,
                routedDevice = touristHeadsetMic,
            )
            fail("Expected DeviceMismatch on name mismatch")
        } catch (e: BluetoothRouteException.DeviceMismatch) {
            assertTrue(e.message?.contains("이름") == true)
        }
    }

    @Test
    fun `resolveCompatibleSink with visible addresses rejects address mismatch even if product name matches or is sole candidate`() {
        val adapter = FakeAudioRoutePlatformAdapter()
        val candidateSink = TestPlatformAudioDeviceInfo(
            id = 201,
            type = AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            productName = "Galaxy Buds Pro",
            address = "99:88:77:66:55:44",
            isSource = false,
            isSink = true,
        )
        adapter.availableCommunicationDevicesList.add(candidateSink)

        val controller = BluetoothAudioRouteController(adapter, directExecutor)
        val requestedInputWithDifferentAddress = TestPlatformAudioDeviceInfo(
            id = 101,
            type = AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            productName = "Galaxy Buds Pro",
            address = "11:22:33:44:55:66",
            isSource = true,
            isSink = false,
        )

        try {
            controller.resolveCompatibleSink(requestedInputWithDifferentAddress)
            fail("Expected SinkNotFound when visible address mismatches")
        } catch (e: BluetoothRouteException.SinkNotFound) {
            assertTrue(e.message?.contains("주소") == true)
        }
    }

    @Test
    fun `resolveActiveInput with visible addresses rejects address mismatch even if product name matches or is sole candidate`() {
        val adapter = FakeAudioRoutePlatformAdapter()
        val soleCandidateInput = TestPlatformAudioDeviceInfo(
            id = 301,
            type = AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            productName = "Galaxy Buds Pro",
            address = "99:88:77:66:55:44",
            isSource = true,
            isSink = false,
        )
        adapter.inputDevicesList.add(soleCandidateInput)

        val controller = BluetoothAudioRouteController(adapter, directExecutor)
        val requestedInput = TestPlatformAudioDeviceInfo(
            id = 101,
            type = AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            productName = "Galaxy Buds Pro",
            address = "11:22:33:44:55:66",
            isSource = true,
        )
        val selectedSink = TestPlatformAudioDeviceInfo(
            id = 201,
            type = AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            productName = "Galaxy Buds Pro",
            address = "11:22:33:44:55:66",
            isSink = true,
        )

        try {
            controller.resolveActiveInput(requestedInput, selectedSink)
            fail("Expected DeviceMismatch when visible address mismatches")
        } catch (e: BluetoothRouteException.DeviceMismatch) {
            assertTrue(e.message?.contains("주소") == true)
        }
    }

    @Test
    fun `verifyRoutedDevice succeeds when addresses match despite different display names`() {
        val controller = BluetoothAudioRouteController(FakeAudioRoutePlatformAdapter())
        val requestedInput = TestPlatformAudioDeviceInfo(
            id = 101,
            type = AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            productName = "Galaxy Buds2 Pro (Mic)",
            address = "AA:BB:CC:DD:EE:FF",
            isSource = true,
        )
        val routedDevice = TestPlatformAudioDeviceInfo(
            id = 102,
            type = AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            productName = "Buds2 Pro Audio (Speaker)",
            address = "AA:BB:CC:DD:EE:FF",
            isSink = false,
            isSource = true,
        )

        // Equal addresses prove identity; display name difference must not reject
        controller.verifyRoutedDevice(requestedInput, routedDevice)
    }

    @Test
    fun `verifyRoutedDevice rejects when addresses mismatch despite identical display names`() {
        val controller = BluetoothAudioRouteController(FakeAudioRoutePlatformAdapter())
        val requestedInput = TestPlatformAudioDeviceInfo(
            id = 101,
            type = AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            productName = "Galaxy Buds2 Pro",
            address = "AA:BB:CC:DD:EE:11",
            isSource = true,
        )
        val routedDevice = TestPlatformAudioDeviceInfo(
            id = 102,
            type = AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            productName = "Galaxy Buds2 Pro",
            address = "AA:BB:CC:DD:EE:22",
            isSource = true,
        )

        try {
            controller.verifyRoutedDevice(requestedInput, routedDevice)
            fail("Expected DeviceMismatch when addresses mismatch despite identical names")
        } catch (e: BluetoothRouteException.DeviceMismatch) {
            assertTrue(e.message?.contains("주소") == true)
        }
    }

    @Test
    fun `verifyRoutedDevice does not falsely reject generic platform labels when address is blank`() {
        val controller = BluetoothAudioRouteController(FakeAudioRoutePlatformAdapter())
        val requestedInput = TestPlatformAudioDeviceInfo(
            id = 101,
            type = AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            productName = "Galaxy Buds2 Pro",
            address = "",
            isSource = true,
        )
        val genericRouted = TestPlatformAudioDeviceInfo(
            id = 102,
            type = AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            productName = "Bluetooth Headset",
            address = "",
            isSource = true,
        )

        // When addresses cannot be compared, generic labels must not trigger false rejection
        controller.verifyRoutedDevice(requestedInput, genericRouted)
    }

    @Test
    fun `awaitAndVerifyRoutedDevice waits boundedly and verifies device`() = runTest {
        val controller = BluetoothAudioRouteController(FakeAudioRoutePlatformAdapter())

        var pollCount = 0
        val routed = controller.awaitAndVerifyRoutedDevice(
            requestedInput = scoMicInput,
            timeoutMillis = 1000L,
            pollIntervalMillis = 10L,
            getRoutedDevice = {
                pollCount++
                if (pollCount < 3) null else scoMicInput
            },
        )

        assertNotNull(routed)
        assertEquals(scoMicInput.id, routed?.id)
        assertTrue(pollCount >= 3)
    }

    @Test
    fun `awaitAndVerifyRoutedDevice times out if device remains null`() = runTest {
        val controller = BluetoothAudioRouteController(FakeAudioRoutePlatformAdapter())

        try {
            controller.awaitAndVerifyRoutedDevice(
                requestedInput = scoMicInput,
                timeoutMillis = 200L,
                pollIntervalMillis = 50L,
                getRoutedDevice = { null },
            )
            fail("Expected DeviceMismatch or RouteTimeout when routed device stays null")
        } catch (e: BluetoothRouteException) {
            assertTrue(e is BluetoothRouteException.DeviceMismatch || e is BluetoothRouteException.RouteTimeout)
        }
    }

    @Test
    fun `awaitAndVerifyRoutedDevice rejects if device remains built-in mic`() = runTest {
        val controller = BluetoothAudioRouteController(FakeAudioRoutePlatformAdapter())

        try {
            controller.awaitAndVerifyRoutedDevice(
                requestedInput = scoMicInput,
                timeoutMillis = 200L,
                pollIntervalMillis = 50L,
                getRoutedDevice = { builtInMic },
            )
            fail("Expected DeviceMismatch when routed device stays built-in mic")
        } catch (e: BluetoothRouteException.DeviceMismatch) {
            assertTrue(e.message?.contains("Classic Bluetooth SCO 마이크") == true)
        }
    }

    @Test
    fun `non bluetooth or null input returns Lease NONE with no mode modification`() = runTest {
        val adapter = FakeAudioRoutePlatformAdapter(sdkInt = 33, mode = AudioManager.MODE_NORMAL)
        adapter.inputDevicesList.add(builtInMic)

        val controller = BluetoothAudioRouteController(adapter, directExecutor)

        // Null device ID
        val nullLease = controller.acquireRoute(null)
        assertSame(AudioRouteLease.NONE, nullLease)
        assertEquals(AudioManager.MODE_NORMAL, adapter.mode)

        // Built-in mic ID
        val micLease = controller.acquireRoute(builtInMic.id)
        assertSame(AudioRouteLease.NONE, micLease)
        assertEquals(AudioManager.MODE_NORMAL, adapter.mode)
    }
}
