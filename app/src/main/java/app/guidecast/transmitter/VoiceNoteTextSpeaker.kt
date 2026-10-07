package app.guidecast.transmitter

import android.content.Context
import android.speech.tts.TextToSpeech
import java.io.Closeable
import java.util.Locale

/** Reading saved text never opts an offline note into a network TTS voice. */
internal class VoiceNoteTextSpeaker(context: Context) : Closeable {
    @Volatile private var ready = false
    private val tts = TextToSpeech(context.applicationContext) { ready = it == TextToSpeech.SUCCESS }
    fun speak(text: String, language: String): Boolean {
        if (!ready || text.isBlank() || text.length > 65_536) return false
        val locale = Locale.forLanguageTag(language)
        val voices = tts.voices.orEmpty().filter { !it.isNetworkConnectionRequired && it.locale.language == locale.language }
        val voice = voices.firstOrNull { it.locale.country == locale.country } ?: voices.firstOrNull() ?: return false
        if (tts.setVoice(voice) != TextToSpeech.SUCCESS) return false
        tts.stop()
        fileTranslationChunks(text).forEachIndexed { index, chunk ->
            if (tts.speak(chunk, TextToSpeech.QUEUE_ADD, null, "voice-note-$index") != TextToSpeech.SUCCESS) { tts.stop(); return false }
        }
        return true
    }
    fun stop() { tts.stop() }
    override fun close() { ready = false; tts.stop(); tts.shutdown() }
}
