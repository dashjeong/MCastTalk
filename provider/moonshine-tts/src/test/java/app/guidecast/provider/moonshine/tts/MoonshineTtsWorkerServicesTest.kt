package app.guidecast.provider.moonshine.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoonshineTtsWorkerServicesTest {
    @Test
    fun preparationFailurePreservesCauseForOuterFreshTicketRecovery() {
        val death = android.os.RemoteException("worker died")
        val failure = moonshinePreparationFailure("warm-up", listOf("es" to death))
        org.junit.Assert.assertSame(death, failure.cause)
        assertTrue(failure.message.orEmpty().contains("es="))
    }

    @Test
    fun everyMoonshineVoiceHasOneStableDistinctServiceClass() {
        val services = MoonshineTtsWorkerServices.snapshot()

        assertEquals(listOf("en", "ja", "zh", "nl", "es", "ar"), services.keys.toList())
        assertEquals(services.size, services.values.toSet().size)
        assertEquals(
            MoonshineTtsVoiceCatalog.voices.keys,
            services.keys,
        )
        assertNotEquals(services.getValue("en"), services.getValue("ja"))
    }

    @Test
    fun sevenChannelCeilingDoesNotInventMoonshineVoicesOrShareOfficialWorkerProcesses() {
        assertEquals(7, MAX_MOONSHINE_BROADCAST_LANGUAGES)
        val services = MoonshineTtsWorkerServices.snapshot()
        assertEquals(MoonshineTtsVoiceCatalog.voices.keys, services.keys)
        assertEquals(services.size, services.values.toSet().size)
    }

    @Test
    fun spanishAndArabicUseReleaseCatalogPiperVoices() {
        assertEquals(
            MoonshineVoiceSpec("es-mx", "스페인어", "piper_es_MX-ald-medium"),
            MoonshineTtsVoiceCatalog.requireVoice("es"),
        )
        assertEquals(
            MoonshineVoiceSpec("ar-msa", "아랍어", "piper_ar_JO-kareem-medium"),
            MoonshineTtsVoiceCatalog.requireVoice("ar"),
        )
    }

    @Test
    fun manifestKeepsEveryVoiceInItsOwnNonExportedProcess() {
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        MoonshineTtsWorkerServices.snapshot().keys.forEach { languageTag ->
            assertTrue(manifest.contains("android:process=\":tts_$languageTag\""))
        }
        assertEquals(6, Regex("android:exported=\"false\"").findAll(manifest).count())
    }

    @Test
    fun assetPreparationCannotConstructTheNativeTtsEngine() {
        val source = java.io.File(
            "src/main/java/app/guidecast/provider/moonshine/tts/" +
                "MoonshineTtsInferenceRuntime.kt",
        ).readText()
        val assetPhase = source.substringAfter("suspend fun prepareAssets(")
            .substringBefore("suspend fun prepare(")
        val nativeWarmPhase = source.substringAfter("suspend fun prepare(")
            .substringBefore("suspend fun synthesizeToFile(")

        assertTrue(assetPhase.contains("ensureVoiceModel(languageTag, spec, onProgress)"))
        assertFalse(assetPhase.contains("loadEngine("))
        assertFalse(assetPhase.contains("TextToSpeech("))
        assertTrue(nativeWarmPhase.contains("loadEngine(languageTag, spec, onProgress)"))
    }

    @Test
    fun deadRemoteBinderIsNotReportedAsAnActiveWorker() {
        assertTrue(
            moonshineWorkerConnectionIsActive(
                hasConnection = true,
                remoteBinderAlive = null,
            ),
        )
        assertTrue(
            moonshineWorkerConnectionIsActive(
                hasConnection = true,
                remoteBinderAlive = true,
            ),
        )
        assertFalse(
            moonshineWorkerConnectionIsActive(
                hasConnection = true,
                remoteBinderAlive = false,
            ),
        )
        assertFalse(
            moonshineWorkerConnectionIsActive(
                hasConnection = false,
                remoteBinderAlive = true,
            ),
        )
    }

    @Test
    fun closedNativeTicketIsNeverReusedForTheReplacementWorker() {
        assertFalse(
            shouldRetryMoonshineWorkerConnectionFailure(
                connectionFailure = true,
                completedAttempts = 1,
                maximumAttempts = 2,
                nativeColdLoadTicketPresent = true,
            ),
        )
        assertTrue(
            shouldRetryMoonshineWorkerConnectionFailure(
                connectionFailure = true,
                completedAttempts = 1,
                maximumAttempts = 2,
                nativeColdLoadTicketPresent = false,
            ),
        )
        assertFalse(
            shouldRetryMoonshineWorkerConnectionFailure(
                connectionFailure = true,
                completedAttempts = 2,
                maximumAttempts = 2,
                nativeColdLoadTicketPresent = false,
            ),
        )
    }

    @Test
    fun outerGateCanRetryTheFailedColdLoadWithAFreshTicket() {
        val transferredTickets = mutableListOf<String>()

        fun runColdLoadAttempt(ticketId: String, workerSucceeds: Boolean): Boolean {
            transferredTickets += ticketId
            if (workerSucceeds) return true

            val retriesInsideTheWorkerTransport =
                shouldRetryMoonshineWorkerConnectionFailure(
                    connectionFailure = true,
                    completedAttempts = 1,
                    maximumAttempts = 2,
                    nativeColdLoadTicketPresent = true,
                )
            assertFalse(retriesInsideTheWorkerTransport)
            return false
        }

        assertFalse(runColdLoadAttempt(ticketId = "ticket-1", workerSucceeds = false))
        assertEquals(listOf("ticket-1"), transferredTickets)

        // The failed call returns through NativeFirstUseGate. Its next invocation owns a new
        // single-transfer ticket and can bind/warm the replacement worker without reusing the
        // CLOSED ticket from the first Binder death.
        assertTrue(runColdLoadAttempt(ticketId = "ticket-2", workerSucceeds = true))
        assertEquals(listOf("ticket-1", "ticket-2"), transferredTickets)
    }

    @Test
    fun failedVoiceWithVerifiedDiskArtifactsCanBeRewarmedWithoutRedownload() {
        assertTrue(
            isVoiceModelPreparedForWarmup(
                readiness = MoonshineTtsReadiness.READY,
                voiceModelPresentOnDisk = false,
            ),
        )
        assertTrue(
            isVoiceModelPreparedForWarmup(
                readiness = MoonshineTtsReadiness.FAILED,
                voiceModelPresentOnDisk = true,
            ),
        )
        assertFalse(
            isVoiceModelPreparedForWarmup(
                readiness = MoonshineTtsReadiness.FAILED,
                voiceModelPresentOnDisk = false,
            ),
        )
        assertFalse(
            isVoiceModelPreparedForWarmup(
                readiness = MoonshineTtsReadiness.NOT_INSTALLED,
                voiceModelPresentOnDisk = false,
            ),
        )
        assertFalse(
            isVoiceModelPreparedForWarmup(
                readiness = MoonshineTtsReadiness.DOWNLOADING,
                voiceModelPresentOnDisk = false,
            ),
        )
    }
}
