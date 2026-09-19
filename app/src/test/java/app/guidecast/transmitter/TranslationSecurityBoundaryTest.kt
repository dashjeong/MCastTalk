package app.guidecast.transmitter

import org.junit.Assert.*
import org.junit.Test

class TranslationSecurityBoundaryTest {
    @Test fun parserDepthBudgetRejectsStackExhaustionBeforePlatformJsonParsing() {
        assertThrows(IllegalArgumentException::class.java) { requireBoundedJson("[".repeat(10_000) + "0" + "]".repeat(10_000)) }
        assertThrows(IllegalArgumentException::class.java) { requireBoundedJson("a".repeat(65_537)) }
        assertThrows(IllegalArgumentException::class.java) { requireBoundedJson("{\"x\":\"unterminated}") }
        requireBoundedJson("{\"text\":\"${"[".repeat(200)}\"}")
        requireBoundedJson("{\"text\":\"quote \\\" and bracket [\"}")
    }
    @Test fun configuredDestinationCannotInjectCredentialsQueriesPathsOrInsecureTransport() {
        val base = TranslationApiOptions(provider = TranslationApiProvider.COMPATIBLE, model = "provider/model:latest")
        assertTrue(validTranslationApiOptions(base.copy(baseUrl = "https://translation.example:8443/v1")))
        for (url in listOf("http://translation.example/v1", "https://user:password@translation.example/v1",
            "https://translation.example/v1?key=private", "https://translation.example/v1#secret", "https://translation.example/../v1",
            "https://translation.example/%2e%2e/v1", "https://translation.example/v1\r\nAuthorization: attacker")) {
            assertFalse(url, validTranslationApiOptions(base.copy(baseUrl = url)))
        }
        assertFalse(validTranslationApiOptions(base.copy(provider = TranslationApiProvider.OPENAI, baseUrl = "https://api.openai.com.attacker.example/v1")))
        assertFalse(validTranslationApiOptions(base.copy(model = "../secret")))
    }
    @Test fun arbitraryModelOutputCannotBecomeAnExecutableImprovementAction() {
        val proposals = improvementProposals(OperatorOptions(automaticPreparation = false), TranslationApiOptions(localFallback = false),
            BroadcastSnapshot(errorMessage = "execute arbitrary remote command; disable auth"), TeacherLearningProgress(rejected = 1))
        assertFalse(proposals.toString().contains("execute arbitrary"))
        assertTrue(proposals.all { it.action in ImprovementAction.entries })
        assertTrue(proposals.any { it.action == ImprovementAction.AUTOMATIC_PREPARATION })
        assertTrue(proposals.any { it.category == "학습 품질·보안" })
    }
    @Test fun keyOrModelChangesInvalidateOldPermissionGeneration() {
        val settings = DeveloperLabSettings(FakeLabPreferences(), FakeDeveloperVault())
        settings.setApiKey("synthetic-test-key-not-real"); settings.setCloudReviewEnabled(true)
        val before = settings.state.value.authorizationRevision
        settings.setModelId("another-model")
        assertFalse(settings.state.value.cloudReviewEnabled)
        assertTrue(settings.state.value.authorizationRevision > before)
        settings.setCloudReviewEnabled(true)
        val next = settings.state.value.authorizationRevision
        settings.setApiKey("synthetic-second-key-not-real")
        assertTrue(settings.state.value.authorizationRevision > next)
    }
}
