package app.guidecast.transmitter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class FileAudioPlaybackState(
    val prepared: Boolean = false,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val repeat: Boolean = false,
    val error: String? = null,
)

/** Activity-scoped local playback. Pause on background/focus loss; no HTTP server or capture. */
internal class FileAudioPlayback(
    context: Context,
    private val scope: CoroutineScope,
    private val onState: (FileAudioPlaybackState) -> Unit,
) : Closeable {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(AudioManager::class.java)
    private var player: MediaPlayer? = null
    private var polling: Job? = null
    private var state = FileAudioPlaybackState()
    private var speed = 1f
    private var repeat = false
    private var receiverRegistered = false
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes).setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener({ change -> if (change <= 0) pause() }, Handler(Looper.getMainLooper())).build()
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { pause() }
    }

    /** Caller supplies an already hash-verified descriptor and retains ownership until return. */
    fun prepare(descriptor: ParcelFileDescriptor) {
        closePlayer()
        speed = 1f
        repeat = false
        publish(FileAudioPlaybackState())
        try {
            val candidate = MediaPlayer()
            player = candidate
            candidate.setAudioAttributes(attributes)
            candidate.setDataSource(descriptor.fileDescriptor)
            candidate.setOnPreparedListener {
                if (player !== it) return@setOnPreparedListener
                it.isLooping = repeat
                publish(FileAudioPlaybackState(prepared = true, durationMs = it.duration.toLong(), speed = speed, repeat = repeat))
            }
            candidate.setOnCompletionListener {
                if (player !== it) return@setOnCompletionListener
                polling?.cancel()
                abandonFocus()
                publish(state.copy(isPlaying = false, positionMs = state.durationMs))
            }
            candidate.setOnSeekCompleteListener {
                if (player === it) publish(state.copy(positionMs = it.currentPosition.toLong()))
            }
            candidate.setOnErrorListener { failed, _, _ ->
                if (player === failed) fail("오디오를 재생할 수 없습니다. 파일 찾기로 원본을 다시 선택하세요.")
                true
            }
            candidate.prepareAsync()
        } catch (_: Exception) { fail("오디오를 열 수 없습니다. 파일 형식과 접근 권한을 확인하세요.") }
    }

    fun playPause() {
        if (state.isPlaying) pause() else play()
    }

    fun play() {
        val current = player ?: return
        if (!state.prepared) return
        try {
            if (manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                publish(state.copy(error = "다른 앱의 음성 사용이 끝나면 다시 재생하세요.")); return
            }
            if (!receiverRegistered) {
                if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), Context.RECEIVER_NOT_EXPORTED)
                else context.registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
                receiverRegistered = true
            }
            if (state.positionMs >= state.durationMs) current.seekTo(0, MediaPlayer.SEEK_CLOSEST)
            current.playbackParams = PlaybackParams().setSpeed(speed).setPitch(1f)
            current.start()
            publish(state.copy(isPlaying = true, error = null))
            polling?.cancel()
            polling = scope.launch {
                while (player === current && state.isPlaying) {
                    try { publish(state.copy(positionMs = current.currentPosition.toLong())) }
                    catch (_: IllegalStateException) { fail("재생 상태가 변경됐습니다. 파일을 다시 선택하세요."); break }
                    delay(80)
                }
            }
        } catch (_: Exception) { fail("재생을 시작할 수 없습니다. 파일을 다시 선택하세요.") }
    }

    fun pause() {
        if (state.isPlaying) runCatching { player?.pause() }
        polling?.cancel(); polling = null
        abandonFocus()
        publish(state.copy(isPlaying = false))
    }

    fun stop() { pause(); seek(0) }
    fun seek(positionMs: Long) {
        if (!state.prepared) return
        val position = positionMs.coerceIn(0, state.durationMs)
        try { player?.seekTo(position, MediaPlayer.SEEK_CLOSEST); publish(state.copy(positionMs = position)) }
        catch (_: IllegalStateException) { fail("재생 위치를 변경할 수 없습니다. 파일을 다시 선택하세요.") }
    }
    fun setRepeat(enabled: Boolean) { repeat = enabled; if (state.prepared) runCatching { player?.isLooping = enabled }; publish(state.copy(repeat = enabled)) }
    fun setSpeed(value: Float) {
        speed = value.coerceIn(0.5f, 2f)
        if (state.isPlaying) try { player?.playbackParams = PlaybackParams().setSpeed(speed).setPitch(1f) }
        catch (_: Exception) { speed = 1f; pause(); publish(state.copy(error = "이 파일은 배속 재생을 지원하지 않습니다. 기본 속도로 다시 재생하세요.")) }
        publish(state.copy(speed = speed))
    }

    private fun publish(next: FileAudioPlaybackState) { state = next; onState(next) }
    private fun abandonFocus() {
        runCatching { manager.abandonAudioFocusRequest(focus) }
        if (receiverRegistered) { runCatching { context.unregisterReceiver(noisy) }; receiverRegistered = false }
    }
    private fun fail(message: String) { closePlayer(); publish(FileAudioPlaybackState(error = message)) }
    private fun closePlayer() {
        polling?.cancel(); polling = null
        val old = player; player = null
        old?.setOnPreparedListener(null); old?.setOnCompletionListener(null); old?.setOnErrorListener(null)
        runCatching { old?.release() }
        abandonFocus()
    }
    override fun close() { closePlayer(); publish(FileAudioPlaybackState()) }
}
