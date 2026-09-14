package app.guidecast.transmitter

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import app.guidecast.core.translation.SpeechExpressionContext
import app.guidecast.core.translation.SpeechExpressionProfile
import app.guidecast.provider.android.tts.AndroidOfflineSpeechSynthesisProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** One bounded local test, its own client and AudioTrack. No microphone capture or cloud request. */
internal suspend fun playDeveloperSpeechPreview(
    context: Context,
    text: String,
    languageTag: String,
    expression: SpeechExpressionProfile,
) = withContext(Dispatchers.IO + SpeechExpressionContext(expression)) {
    require(text.isNotBlank() && text.length <= 500)
    val provider = AndroidOfflineSpeechSynthesisProvider(context, outputSampleRateHz = 16_000)
    var track: AudioTrack? = null
    try {
        withTimeout(90_000L) {
            provider.prepare(listOf(languageTag))
            val minimum = AudioTrack.getMinBufferSize(16_000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0)
            val output = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(16_000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minimum, 6_400)).setTransferMode(AudioTrack.MODE_STREAM).build()
                .also { track = it }
            check(output.state == AudioTrack.STATE_INITIALIZED)
            output.play()
            var submittedSamples = 0L
            provider.engineFor(languageTag).synthesize(text, languageTag).collect { frame ->
                writeLocalMonitorPcm(frame.bytes, isCurrent = { true }, writeNonBlocking = { bytes, offset, size ->
                    output.write(bytes, offset, size, AudioTrack.WRITE_NON_BLOCKING)
                })
                submittedSamples += frame.bytes.size / 2
            }
            check(submittedSamples > 0)
            // Let the final local buffer play; stopping immediately would truncate the ending.
            withTimeout(2_000L) {
                while ((output.playbackHeadPosition.toLong() and 0xffffffffL) < submittedSamples) {
                    currentCoroutineContext().ensureActive(); delay(10)
                }
            }
        }
    } finally {
        track?.let { runCatching { it.pause() }; runCatching { it.flush() }; runCatching { it.release() } }
        provider.close()
    }
}
