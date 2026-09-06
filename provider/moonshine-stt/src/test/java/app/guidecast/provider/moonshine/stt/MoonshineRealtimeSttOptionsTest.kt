package app.guidecast.provider.moonshine.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoonshineRealtimeSttOptionsTest {
    @Test
    fun `keeps partial updates frequent and fallback endpoint bounded at twelve seconds`() {
        val options = MoonshineRealtimeSttOptions.values().associate { it.name() to it.value() }

        assertTrue(options.getValue("transcription_interval").toDouble() <= 0.5)
        assertTrue(options.getValue("vad_window_duration").toDouble() <= 0.5)
        assertEquals("12.0", options["vad_max_segment_duration"])
        assertEquals("13.0", options["max_tokens_per_second"])
    }

    @Test
    fun `note9 profile reduces partial inference pressure without losing realtime updates`() {
        val options = MoonshineRealtimeSttOptions.values(note9Compatibility = true)
            .associate { it.name() to it.value() }

        assertEquals("0.50", options["transcription_interval"])
        assertEquals("12.0", options["vad_max_segment_duration"])
        assertEquals("13.0", options["max_tokens_per_second"])
    }
}
