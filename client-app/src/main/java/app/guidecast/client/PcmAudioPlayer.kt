package app.guidecast.client

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.Closeable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PcmAudioPlayer(
    private val sampleRateHz: Int = 24_000,
) : Closeable {
    private var track: AudioTrack? = null

    suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        val active = ensureTrack()
        if (active.playState != AudioTrack.PLAYSTATE_PLAYING) active.play()
        var offset = 0
        while (offset < bytes.size) {
            val count = active.write(bytes, offset, bytes.size - offset, AudioTrack.WRITE_BLOCKING)
            check(count >= 0) { "통역 음성을 재생하지 못했습니다: AudioTrack $count" }
            offset += count
        }
    }

    fun pauseAndFlush() {
        track?.let { active ->
            runCatching { active.pause() }
            runCatching { active.flush() }
        }
    }

    fun resume() {
        track?.let { active -> runCatching { active.play() } }
    }

    private fun ensureTrack(): AudioTrack = track ?: createTrack().also { track = it }

    private fun createTrack(): AudioTrack {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRateHz)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val minimum = AudioTrack.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(sampleRateHz * Short.SIZE_BYTES / 2)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(format)
            .setBufferSizeInBytes(minimum * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    override fun close() {
        track?.let { active ->
            runCatching { active.stop() }
            runCatching { active.flush() }
            active.release()
        }
        track = null
    }
}
