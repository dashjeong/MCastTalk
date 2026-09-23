package app.guidecast.transmitter

import org.junit.Assert.*
import org.junit.Test

class FileConversionDiagnosticsTest {
    @Test fun diagnosticsKeepStageAndAppCodeLocationWithoutContentOrExternalFrames() {
        val error = IllegalStateException("PRIVATE transcript /storage/private.wav https://secret.example token",
            IllegalArgumentException("PRIVATE cause"))
        error.stackTrace = arrayOf(
            StackTraceElement("vendor.Private", "PRIVATE", "PRIVATE.wav", 1),
            StackTraceElement("app.guidecast.transmitter.FileTranscriptLibrary", "save", "PRIVATE.kt", 120),
        )
        val detail = fileConversionFailureDetail(FileConversionStage.SAVE_SOURCE, error)
        assertTrue(detail.contains("stage=SAVE_SOURCE"))
        assertTrue(detail.contains("type=java.lang.IllegalStateException"))
        assertTrue(detail.contains("FileTranscriptLibrary.save:120"))
        assertFalse(detail.contains("PRIVATE"))
        assertFalse(detail.contains("storage"))
        assertFalse(detail.contains("https"))
        assertFalse(detail.contains("vendor"))
    }
}
