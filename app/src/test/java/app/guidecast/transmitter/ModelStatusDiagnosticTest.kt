package app.guidecast.transmitter

import app.guidecast.provider.gemma.translation.GemmaModelReadiness
import app.guidecast.provider.gemma.translation.GemmaModelStatus
import app.guidecast.provider.gemma.translation.GemmaModelVariant
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelStatusDiagnosticTest {
    @Test fun `imported catalog metadata and error content are absent from export summary`() {
        val custom = GemmaModelVariant.STANDARD.copy(
            id = "PRIVATE_USER_MODEL", label = "PRIVATE_LABEL", fileName = "PRIVATE_FILE",
            repositoryUrl = "https://PRIVATE_REPOSITORY", revision = "PRIVATE_REVISION",
        )
        val status = GemmaModelStatus(variant = custom, readiness = GemmaModelReadiness.FAILED,
            downloadedBytes = 3_145_728L, errorMessage = "PRIVATE_ERROR_TRANSCRIPT")
        assertEquals("custom:FAILED:3MiB:error=true", gemmaModelDiagnostic(status))
    }

    @Test fun `only exact bundled models retain their allowlisted identifier`() {
        assertEquals("standard:NOT_INSTALLED:0MiB:error=false", gemmaModelDiagnostic(GemmaModelStatus()))
        val customUsingBuiltinId = GemmaModelVariant.STANDARD.copy(label = "PRIVATE_CUSTOM_LABEL")
        assertEquals("custom:NOT_INSTALLED:0MiB:error=false",
            gemmaModelDiagnostic(GemmaModelStatus(variant = customUsingBuiltinId)))
    }
}
