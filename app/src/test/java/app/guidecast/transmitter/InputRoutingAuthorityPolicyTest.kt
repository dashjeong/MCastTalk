package app.guidecast.transmitter

import app.guidecast.core.audio.AudioInputDevice
import app.guidecast.core.audio.AudioInputKind
import app.guidecast.core.audio.PcmFrame
import app.guidecast.core.audio.WebAudioInputBridge
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InputRoutingAuthorityPolicyTest {

    private val physicalMicSample = PcmFrame(
        bytes = ByteArray(640) { 0x11 },
        sampleRateHz = 16_000,
        capturedAtElapsedRealtimeNanos = 1000L,
    )
    private val playbackSample = PcmFrame(
        bytes = ByteArray(640) { 0x22 },
        sampleRateHz = 16_000,
        capturedAtElapsedRealtimeNanos = 2000L,
    )
    private val webSpeakerSample = PcmFrame(
        bytes = ByteArray(640) { 0x33 },
        sampleRateHz = 16_000,
        capturedAtElapsedRealtimeNanos = 3000L,
    )

    @Before
    @After
    fun cleanup() {
        WebAudioInputBridge.reset()
    }

    @Test
    fun physicalMicrophoneNeverYieldsToActiveWebSpeaker() = runBlocking {
        val builtInDevice = AudioInputDevice(
            platformId = 1,
            kind = AudioInputKind.BUILT_IN,
            label = "내장 마이크",
        )

        // Web speaker connects and streams in background
        val sessionGen = WebAudioInputBridge.openSession()
        WebAudioInputBridge.emitFrame(webSpeakerSample, sessionGen)
        assertTrue(WebAudioInputBridge.isStreamingActive())

        val resolvedFlow = resolveInputFrames(
            input = builtInDevice,
            physicalMicFrames = { flowOf(physicalMicSample) },
            playbackFrames = { flowOf(playbackSample) },
            webSpeakerFrames = { flowOf(webSpeakerSample) },
        )

        val received = resolvedFlow.first()
        assertEquals("Physical microphone frames must be routed", 0x11.toByte(), received.bytes[0])
        assertNotEquals("Web speaker must never override physical microphone", 0x33.toByte(), received.bytes[0])
    }

    @Test
    fun bluetoothMicrophoneNeverYieldsToActiveWebSpeaker() = runBlocking {
        val bluetoothDevice = AudioInputDevice(
            platformId = 2,
            kind = AudioInputKind.BLUETOOTH,
            label = "블루투스 헤드셋",
        )

        val sessionGen = WebAudioInputBridge.openSession()
        WebAudioInputBridge.emitFrame(webSpeakerSample, sessionGen)
        assertTrue(WebAudioInputBridge.isStreamingActive())

        val resolvedFlow = resolveInputFrames(
            input = bluetoothDevice,
            physicalMicFrames = { flowOf(physicalMicSample) },
            playbackFrames = { flowOf(playbackSample) },
            webSpeakerFrames = { flowOf(webSpeakerSample) },
        )

        val received = resolvedFlow.first()
        assertEquals(0x11.toByte(), received.bytes[0])
    }

    @Test
    fun devicePlaybackNeverYieldsToActiveWebSpeaker() = runBlocking {
        val playbackDevice = AudioInputDevice(
            platformId = AudioInputKind.DEVICE_PLAYBACK_ID,
            kind = AudioInputKind.DEVICE_PLAYBACK,
            label = "기기 내부 소리",
        )

        val sessionGen = WebAudioInputBridge.openSession()
        WebAudioInputBridge.emitFrame(webSpeakerSample, sessionGen)
        assertTrue(WebAudioInputBridge.isStreamingActive())

        val resolvedFlow = resolveInputFrames(
            input = playbackDevice,
            physicalMicFrames = { flowOf(physicalMicSample) },
            playbackFrames = { flowOf(playbackSample) },
            webSpeakerFrames = { flowOf(webSpeakerSample) },
        )

        val received = resolvedFlow.first()
        assertEquals("Device playback frames must be routed", 0x22.toByte(), received.bytes[0])
        assertNotEquals("Web speaker must never override device playback", 0x33.toByte(), received.bytes[0])
    }

    @Test
    fun webSpeakerIsRoutedOnlyWhenExplicitlySelected() = runBlocking {
        val webSpeakerDevice = AudioInputDevice(
            platformId = AudioInputKind.WEB_SPEAKER_ID,
            kind = AudioInputKind.WEB_SPEAKER,
            label = "강사 웹 마이크 (원격 입력)",
        )

        val resolvedFlow = resolveInputFrames(
            input = webSpeakerDevice,
            physicalMicFrames = { flowOf(physicalMicSample) },
            playbackFrames = { flowOf(playbackSample) },
            webSpeakerFrames = { flowOf(webSpeakerSample) },
        )

        val received = resolvedFlow.first()
        assertEquals("Web speaker frames routed when explicitly selected", 0x33.toByte(), received.bytes[0])
    }
}
