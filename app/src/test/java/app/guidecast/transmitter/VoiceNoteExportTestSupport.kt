package app.guidecast.transmitter

/** Older regression cases now exercise the production streaming exporter. */
internal fun voiceNoteTranscript(title: String, lines: List<VoiceNoteLine>, srt: Boolean): String =
    java.io.StringWriter().also { writer ->
        writeVoiceNoteExport(writer, VoiceNote("test", title, 0, null, "ko-KR", interrupted = false, lines = lines),
            if (srt) "srt" else "txt", VoiceNoteExportOptions())
    }.toString()
