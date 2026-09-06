package app.guidecast.provider.moonshine.stt

import ai.moonshine.voice.TranscriberOption

/**
 * Product latency policy for the Korean Moonshine fallback.
 *
 * Moonshine's VAD remains responsible for acoustic pause detection. The app-level interpretation
 * segmenter separately commits stable linguistic prefixes during uninterrupted speech, so this
 * maximum is only a final safety boundary and not a periodic PCM cutter.
 */
internal object MoonshineRealtimeSttOptions {
    const val TRANSCRIPTION_INTERVAL_SECONDS = "0.35"
    const val NOTE9_TRANSCRIPTION_INTERVAL_SECONDS = "0.50"
    const val VAD_THRESHOLD = "0.58"
    const val VAD_WINDOW_SECONDS = "0.30"
    const val VAD_MAX_SEGMENT_SECONDS = "12.0"
    const val KOREAN_MAX_TOKENS_PER_SECOND = "13.0"

    fun values(note9Compatibility: Boolean = false): List<TranscriberOption> = listOf(
        TranscriberOption(
            "transcription_interval",
            if (note9Compatibility) {
                NOTE9_TRANSCRIPTION_INTERVAL_SECONDS
            } else {
                TRANSCRIPTION_INTERVAL_SECONDS
            },
        ),
        TranscriberOption("vad_threshold", VAD_THRESHOLD),
        TranscriberOption("vad_window_duration", VAD_WINDOW_SECONDS),
        TranscriberOption("vad_max_segment_duration", VAD_MAX_SEGMENT_SECONDS),
        TranscriberOption("max_tokens_per_second", KOREAN_MAX_TOKENS_PER_SECOND),
    )
}
