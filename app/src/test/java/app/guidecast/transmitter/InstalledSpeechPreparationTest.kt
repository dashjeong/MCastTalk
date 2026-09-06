package app.guidecast.transmitter

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class InstalledSpeechPreparationTest {
    @Test fun autoUsesInstalledVoiceWithoutStartingMoonshineDownload() = runTest {
        var downloads = 0
        var android = 0
        val result = prepareSpeechSynthesisLanguageWithNativeWarmupBoundary(
            languageTag = "en",
            prepareMoonshineAssets = { downloads++ },
            warmMoonshine = { error("Native must not load") },
            warmMoonshineWithNativeAdmission = { _, warm -> warm() },
            prepareAndroidOffline = { android++ },
            moonshinePreparationTimeoutMillis = 100,
            androidStandbyPreparationTimeoutMillis = 100,
            preferAndroidOffline = true,
        )
        assertEquals(0, downloads)
        assertEquals(1, android)
        assertEquals(SpeechSynthesisPreparationBackend.ANDROID_OFFLINE, result.preparation.backend)
        assertTrue(result.preparation.moonshineError is InstalledOfflineVoiceSelected)
    }

    @Test fun missingPreferredVendorCanUseCachedMoonshine() = runTest {
        var warmed = 0
        val result = prepareSpeechSynthesisLanguageWithNativeWarmupBoundary(
            languageTag = "ja", prepareMoonshineAssets = {},
            warmMoonshine = { warmed++ },
            warmMoonshineWithNativeAdmission = { _, warm -> warm() },
            prepareAndroidOffline = { error("Offline voice not installed") },
            moonshinePreparationTimeoutMillis = 100,
            androidStandbyPreparationTimeoutMillis = 100,
            preferAndroidOffline = true,
        )
        assertEquals(1, warmed)
        assertEquals(SpeechSynthesisPreparationBackend.MOONSHINE, result.preparation.backend)
        assertNotNull(result.androidStandbyError)
    }

    @Test fun cancellationDoesNotStartDownloadOrFallbackWork() = runTest {
        try {
            prepareSpeechSynthesisLanguageWithNativeWarmupBoundary(
                languageTag = "vi", prepareMoonshineAssets = { error("Unexpected download") },
                warmMoonshine = { error("Unexpected warmup") },
                warmMoonshineWithNativeAdmission = { _, warm -> warm() },
                prepareAndroidOffline = { throw CancellationException("Operator stopped") },
                moonshinePreparationTimeoutMillis = 100,
                androidStandbyPreparationTimeoutMillis = 100,
                preferAndroidOffline = true,
            )
            fail("Cancellation must escape")
        } catch (_: CancellationException) { }
    }

    @Test fun preferencePolicyDoesNotPretendMissingAssetsAreInstalled() {
        assertTrue(preferInstalledAndroidVoice(SpeechVoicePreference.AUTO, false))
        assertFalse(preferInstalledAndroidVoice(SpeechVoicePreference.AUTO, true))
        assertFalse(preferInstalledAndroidVoice(SpeechVoicePreference.MOONSHINE, false))
        assertTrue(preferInstalledAndroidVoice(SpeechVoicePreference.SAMSUNG, true))
        assertTrue(preferInstalledAndroidVoice(SpeechVoicePreference.GOOGLE, true))
    }
}
