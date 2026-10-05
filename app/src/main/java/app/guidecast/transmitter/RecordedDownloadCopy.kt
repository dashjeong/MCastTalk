package app.guidecast.transmitter

import java.io.File
import java.io.OutputStream

/** Validate the staged source before creating or truncating a selected destination. */
internal fun copyPreparedRecordedDownload(resolve: () -> File?, openOutput: () -> OutputStream,
    checkRunning: () -> Unit): String {
    val source = requireNotNull(resolve()) { "Prepared download unavailable" }
    checkRunning()
    source.inputStream().use { input -> openOutput().use { output ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            checkRunning()
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        output.flush()
        checkRunning()
    } }
    return if (source.extension == "mp3") "audio/mpeg" else "text/plain"
}
