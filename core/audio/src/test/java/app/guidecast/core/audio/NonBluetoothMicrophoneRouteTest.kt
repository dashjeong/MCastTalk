package app.guidecast.core.audio

import android.media.AudioDeviceInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NonBluetoothMicrophoneRouteTest {
    private fun device(id: Int, type: Int, source: Boolean = true) = TestPlatformAudioDeviceInfo(id, type, "microphone", isSource = source)
    private val builtIn = device(10, AudioDeviceInfo.TYPE_BUILTIN_MIC)
    private val usb = device(20, AudioDeviceInfo.TYPE_USB_DEVICE)
    private val wired = device(30, AudioDeviceInfo.TYPE_WIRED_HEADSET)

    private fun assertReason(reason: NonBluetoothMicrophoneRouteFailure, expected: PlatformAudioDeviceInfo?, actual: PlatformAudioDeviceInfo?) {
        try { verifyNonBluetoothMicrophoneRoute(expected, actual); fail("Incorrect route must be rejected") }
        catch (error: NonBluetoothMicrophoneRouteException) { assertEquals(reason, error.reason); assertTrue(requireNotNull(error.message).contains("다시 선택")) }
    }

    @Test fun matchingBuiltInWiredAndUsbDevicesAreAccepted() {
        listOf(builtIn, usb, wired).forEach { expected -> verifyNonBluetoothMicrophoneRoute(expected, expected.copy(productName = "alternate Android label")) }
    }

    @Test fun fallbackToBuiltInAndAnotherSameTypeUsbDeviceAreRejected() {
        assertReason(NonBluetoothMicrophoneRouteFailure.DIFFERENT_TYPE, usb, builtIn)
        assertReason(NonBluetoothMicrophoneRouteFailure.DIFFERENT_TYPE, wired, builtIn)
        assertReason(NonBluetoothMicrophoneRouteFailure.DIFFERENT_DEVICE, usb, usb.copy(id = 21))
        assertReason(NonBluetoothMicrophoneRouteFailure.DIFFERENT_DEVICE, builtIn, builtIn.copy(id = 11))
    }

    @Test fun missingRoutesAndOutputOnlyDevicesAreRejected() {
        assertReason(NonBluetoothMicrophoneRouteFailure.ROUTE_UNCONFIRMED, usb, null)
        assertReason(NonBluetoothMicrophoneRouteFailure.NOT_AN_INPUT, usb, usb.copy(isSource = false, isSink = true))
        assertReason(NonBluetoothMicrophoneRouteFailure.NOT_AN_INPUT, usb.copy(isSource = false), usb)
    }

    @Test fun changedRouteIsRejectedBeforePublishingEvenWhenTheRouteCallbackHasNotRun() {
        var routed: PlatformAudioDeviceInfo? = usb
        val delivered = mutableListOf<Int>()
        withVerifiedNonBluetoothMicrophoneRoute(usb, { routed }) { delivered += 1 }
        routed = builtIn
        try {
            withVerifiedNonBluetoothMicrophoneRoute(usb, { routed }) { delivered += 2 }
            fail("Unselected microphone PCM must not be processed or published")
        } catch (error: NonBluetoothMicrophoneRouteException) {
            assertEquals(NonBluetoothMicrophoneRouteFailure.DIFFERENT_TYPE, error.reason)
        }
        routed = null
        try {
            withVerifiedNonBluetoothMicrophoneRoute(usb, { routed }) { delivered += 3 }
            fail("Unconfirmed microphone PCM must not be processed or published")
        } catch (error: NonBluetoothMicrophoneRouteException) {
            assertEquals(NonBluetoothMicrophoneRouteFailure.ROUTE_UNCONFIRMED, error.reason)
        }
        assertEquals(listOf(1), delivered)
    }

    @Test fun frameAdmissionDoesNotAddDefaultOrBluetoothRouteQueriesOrDropQuietData() {
        val silentFrame = ByteArray(320)
        assertSame(silentFrame, withVerifiedNonBluetoothMicrophoneRoute(usb, { usb }) { silentFrame })
        val noQuery: () -> PlatformAudioDeviceInfo? = { error("This route has a separate policy") }
        assertSame(silentFrame, withVerifiedNonBluetoothMicrophoneRoute(null, noQuery) { silentFrame })
        assertSame(silentFrame, withVerifiedNonBluetoothMicrophoneRoute(device(40, AudioDeviceInfo.TYPE_BLUETOOTH_SCO), noQuery) { silentFrame })
    }

    @Test fun anonymousDefaultInputAndBluetoothKeepTheirSeparateRoutingPolicies() {
        verifyNonBluetoothMicrophoneRoute(null, null)
        verifyNonBluetoothMicrophoneRoute(null, builtIn)
        listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET).forEach { type ->
            val expected = device(40, type)
            verifyNonBluetoothMicrophoneRoute(expected, expected.copy(id = 41))
            verifyNonBluetoothMicrophoneRoute(expected, null)
        }
    }

    @Test fun aDisconnectedExplicitSelectionCannotBecomeAnAnonymousDefaultMicrophone() {
        requireSelectedMicrophoneAvailable(null, null)
        requireSelectedMicrophoneAvailable(usb.id, usb)
        try { requireSelectedMicrophoneAvailable(usb.id, null); fail("Missing selected microphone must not fall back") }
        catch (error: NonBluetoothMicrophoneRouteException) { assertEquals(NonBluetoothMicrophoneRouteFailure.SELECTED_DEVICE_UNAVAILABLE, error.reason) }
        // A resolved Bluetooth route can legitimately have a changed Android device ID.
        requireSelectedMicrophoneAvailable(40, device(41, AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
    }

    @Test fun startupWaitsForAConfirmedUsbRouteInsteadOfAcceptingInitialFallback() = runTest {
        var routed: PlatformAudioDeviceInfo? = builtIn
        val waiting = async { awaitNonBluetoothMicrophoneRoute(usb, timeoutMillis = 500, pollIntervalMillis = 25) { routed } }
        runCurrent(); assertFalse(waiting.isCompleted)
        advanceTimeBy(100); runCurrent(); assertFalse(waiting.isCompleted)
        routed = usb
        advanceTimeBy(25); runCurrent()
        assertSame(usb, waiting.await())
    }

    @Test fun missingOrWrongInputHasABoundedTimeoutAndSpecificReason() = runTest {
        listOf(null to NonBluetoothMicrophoneRouteFailure.ROUTE_UNCONFIRMED,
            builtIn to NonBluetoothMicrophoneRouteFailure.DIFFERENT_TYPE,
            usb.copy(id = 21) to NonBluetoothMicrophoneRouteFailure.DIFFERENT_DEVICE).forEach { (routed, reason) ->
            val result = async { runCatching { awaitNonBluetoothMicrophoneRoute(usb, 100, 20) { routed } } }
            advanceTimeBy(100); runCurrent()
            val error = result.await().exceptionOrNull()
            assertTrue(error is NonBluetoothMicrophoneRouteException)
            assertEquals(reason, (error as NonBluetoothMicrophoneRouteException).reason)
        }
    }

    @Test fun cancellationStopsPollingRatherThanBeingReportedAsRouteFailure() = runTest {
        var reads = 0
        val canceled = CompletableDeferred<Unit>()
        val waiting = async {
            try { awaitNonBluetoothMicrophoneRoute(usb, 500, 25) { reads++; null } }
            finally { canceled.complete(Unit) }
        }
        runCurrent(); advanceTimeBy(25); runCurrent()
        waiting.cancelAndJoin(); canceled.await()
        val afterCancel = reads
        advanceTimeBy(1_000); runCurrent()
        assertTrue(waiting.isCancelled)
        assertEquals(afterCancel, reads)
    }

    @Test fun theCallersTimeoutIsNotConvertedIntoAnInputMismatch() = runTest {
        try {
            withTimeout(50) { awaitNonBluetoothMicrophoneRoute(usb, 500, 25) { null } }
            fail("The caller timeout must cancel startup")
        } catch (_: TimeoutCancellationException) { }
    }
}
