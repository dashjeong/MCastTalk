package app.guidecast.transmitter

import android.app.Activity
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.sin

/** A separate test-APK process that explicitly permits playback capture. */
class PlaybackToneActivity : Activity() {
    private var audioTrack: AudioTrack? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "GuideCast capturable MEDIA test tone"
            textSize = 22f
            setPadding(48, 96, 48, 48)
        })

        val samples = ShortArray(SAMPLE_RATE_HZ) { index ->
            (sin(2.0 * PI * FREQUENCY_HZ * index / SAMPLE_RATE_HZ) * Short.MAX_VALUE * AMPLITUDE)
                .toInt()
                .toShort()
        }
        val pcm = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, sample ->
            pcm[index * 2] = (sample.toInt() and 0xff).toByte()
            pcm[index * 2 + 1] = (sample.toInt() shr 8).toByte()
        }
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setAllowedCapturePolicy(
                        if (intent.getBooleanExtra(EXTRA_BLOCK_CAPTURE, false)) {
                            AudioAttributes.ALLOW_CAPTURE_BY_NONE
                        } else {
                            AudioAttributes.ALLOW_CAPTURE_BY_ALL
                        },
                    )
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE_HZ)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size)
            .build()
            .also { track ->
                check(track.write(pcm, 0, pcm.size) == pcm.size)
                track.setLoopPoints(0, samples.size, -1)
                track.play()
            }
    }

    override fun onDestroy() {
        audioTrack?.let { track ->
            try {
                track.stop()
            } catch (_: IllegalStateException) {
                // The test may finish after AudioFlinger already stopped the static track.
            }
            track.release()
        }
        audioTrack = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_BLOCK_CAPTURE = "block_capture"
        private const val SAMPLE_RATE_HZ = 16_000
        private const val FREQUENCY_HZ = 733.0
        private const val AMPLITUDE = 0.6
    }
}
