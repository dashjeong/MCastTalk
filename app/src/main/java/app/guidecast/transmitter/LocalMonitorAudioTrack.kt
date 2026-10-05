package app.guidecast.transmitter

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build

internal fun createLocalMonitorAudioTrack(sampleRateHz: Int, isolateNativeTurns: Boolean = false): AudioTrack {
    val minBuffer = AudioTrack.getMinBufferSize(
        sampleRateHz,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
    )
    check(minBuffer > 0) { "통역 음성 스피커를 열지 못했습니다." }
    val targetBufferBytes = (sampleRateHz * 2 * 120 / 1_000)
    return AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
                .build(),
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setSampleRate(sampleRateHz)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
        )
        .setBufferSizeInBytes(maxOf(minBuffer, targetBufferBytes))
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()
        .also { track ->
            check(track.state == AudioTrack.STATE_INITIALIZED) {
                "통역 음성 스피커 초기화에 실패했습니다."
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val thresholdFrames = (if (isolateNativeTurns) 1 else sampleRateHz * 40 / 1_000)
                    .coerceIn(1, track.bufferCapacityInFrames)
                runCatching { track.setStartThresholdInFrames(thresholdFrames) }
                    .getOrElse { error ->
                        track.release()
                        throw IllegalStateException("로컬 출력 시작 버퍼를 설정하지 못했습니다.", error)
                    }
            }
        }
}
