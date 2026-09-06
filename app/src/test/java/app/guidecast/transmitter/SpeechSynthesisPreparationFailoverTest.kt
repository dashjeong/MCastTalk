package app.guidecast.transmitter

import app.guidecast.core.stream.PcmAudioFrame
import app.guidecast.core.translation.NativeColdLoadTicket
import app.guidecast.core.translation.NativeColdLoadTicketContext
import app.guidecast.core.translation.ExecutionAwareSpeechSynthesisEngine
import app.guidecast.core.translation.SpeechSynthesisEngine
import app.guidecast.core.translation.currentNativeColdLoadTicket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.fail
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SpeechSynthesisPreparationFailoverTest {
    @Test
    fun deadWarmupRetriesOnceWithADifferentNativeTicket() = runBlocking {
        val tickets = mutableListOf<RecordingNativeColdLoadTicket>()
        warmSpeechWithFreshAdmissionAfterWorkerDeath {
            assertNull(currentNativeColdLoadTicket())
            val ticket = RecordingNativeColdLoadTicket()
            tickets += ticket
            withContext(NativeColdLoadTicketContext(ticket)) {
                if (tickets.size == 1) throw IllegalStateException("worker lost", android.os.RemoteException())
                assertSame(tickets.last(), currentNativeColdLoadTicket())
            }
        }
        assertEquals(2, tickets.size)
        assertNotSame(tickets.first(), tickets.last())
    }

    @Test
    fun repeatedWorkerDeathStopsAfterSecondAttempt() = runBlocking {
        var attempts = 0
        try {
            warmSpeechWithFreshAdmissionAfterWorkerDeath {
                attempts++
                throw android.os.RemoteException()
            }
            fail("Must propagate repeated worker death")
        } catch (expected: android.os.RemoteException) { }
        assertEquals(2, attempts)
    }

    @Test
    fun warmupModelFailureDoesNotRetry() = runBlocking {
        var attempts = 0
        try {
            warmSpeechWithFreshAdmissionAfterWorkerDeath {
                attempts++
                error("invalid model")
            }
            fail("Must propagate model failure")
        } catch (expected: IllegalStateException) { }
        assertEquals(1, attempts)
    }

    @Test
    fun broadcastTakesOverFiveSettingsVoicesAndRejectsStaleSettingsGeneration() {
        val owners = RetainedSpeechSynthesisLanguageOwners()
        val settingsLanguages = setOf("en", "ja", "zh", "nl", "es")
        val broadcastLanguages = setOf("de", "fr", "it", "pt", "id")

        val settings = owners.updateSettings(settingsLanguages)
        val broadcast = owners.activateBroadcast(broadcastLanguages, generation = 2L)
        val staleSettings = owners.updateSettings(settingsLanguages)

        assertTrue(settings.accepted)
        assertEquals(settingsLanguages, settings.all)
        assertFalse(owners.isSettingsGenerationCurrent(settings.settingsGeneration))
        assertTrue(broadcast.accepted)
        assertEquals(broadcastLanguages, broadcast.broadcast)
        assertTrue(broadcast.settings.isEmpty())
        assertEquals(broadcastLanguages, broadcast.all)
        assertTrue(broadcast.all.size <= 5)
        assertFalse(staleSettings.accepted)
        assertEquals(broadcastLanguages, staleSettings.all)
    }

    @Test
    fun stoppedBroadcastReleasesOnlyItsGenerationAndImmediateSettingsCanTakeOver() {
        val owners = RetainedSpeechSynthesisLanguageOwners()
        val firstBroadcast = owners.activateBroadcast(setOf("en"), generation = 41L)

        assertFalse(owners.updateSettings(setOf("ja")).accepted)
        assertFalse(owners.releaseBroadcast(40L))
        assertTrue(owners.isBroadcastGenerationCurrent(firstBroadcast.broadcastGeneration))
        assertTrue(owners.releaseBroadcast(41L))

        val settings = owners.updateSettings(setOf("ja"))
        assertTrue(settings.accepted)
        assertTrue(owners.isSettingsGenerationCurrent(settings.settingsGeneration))

        val lateStoppedGeneration = owners.activateBroadcast(setOf("en"), generation = 41L)
        assertFalse(lateStoppedGeneration.accepted)
        assertTrue(lateStoppedGeneration.broadcast.isEmpty())

        val replacement = owners.activateBroadcast(setOf("zh"), generation = 42L)
        assertFalse(owners.releaseBroadcast(41L))
        assertTrue(owners.isBroadcastGenerationCurrent(replacement.broadcastGeneration))
        assertEquals(setOf("zh"), replacement.broadcast)
    }

    @Test
    fun teardownBeforeVoiceActivationTombstonesLateSameGenerationReconciliation() {
        val owners = RetainedSpeechSynthesisLanguageOwners()

        assertTrue(owners.releaseBroadcast(73L))
        val lateActivation = owners.activateBroadcast(setOf("en"), generation = 73L)

        assertFalse(lateActivation.accepted)
        assertTrue(lateActivation.broadcast.isEmpty())
        val next = owners.activateBroadcast(setOf("ja"), generation = 74L)
        assertTrue(next.accepted)
        assertEquals(setOf("ja"), next.broadcast)
    }

    @Test
    fun broadcastTakeoverDuringSlowSettingsAssetPreparationPreventsStaleNativeWarm() = runBlocking {
        val owners = RetainedSpeechSynthesisLanguageOwners()
        val settings = owners.updateSettings(setOf("en"))
        val assetsStarted = CompletableDeferred<Unit>()
        val finishAssets = CompletableDeferred<Unit>()
        var nativeWarmCalled = false
        var androidFallbackCalled = false

        supervisorScope {
            val preparation = async {
                prepareSpeechSynthesisLanguageWithNativeWarmupBoundary(
                    languageTag = "en",
                    prepareMoonshineAssets = {
                        assetsStarted.complete(Unit)
                        finishAssets.await()
                    },
                    warmMoonshine = { nativeWarmCalled = true },
                    warmMoonshineWithNativeAdmission = { _, warm -> warm() },
                    prepareAndroidOffline = { androidFallbackCalled = true },
                    moonshinePreparationTimeoutMillis = 1_000L,
                    androidStandbyPreparationTimeoutMillis = 1_000L,
                    ensurePreparationCurrent = {
                        if (!owners.isSettingsGenerationCurrent(settings.settingsGeneration)) {
                            throw CancellationException("stale settings generation")
                        }
                    },
                )
            }

            assetsStarted.await()
            val broadcast = owners.activateBroadcast(setOf("ja"), generation = 2L)
            finishAssets.complete(Unit)

            try {
                preparation.await()
                fail("Expected stale settings preparation cancellation")
            } catch (error: CancellationException) {
                assertEquals("stale settings generation", error.message)
            }
            assertEquals(setOf("ja"), broadcast.all)
        }

        assertFalse(nativeWarmCalled)
        assertFalse(androidFallbackCalled)
    }

    @Test
    fun appBroadcastOwnerInvalidatesSettingsTtsBeforePermitWarmAndStatusCommit() = runBlocking {
        val appOwners = TranslationPreparationOwnerState()
        val settingsOwner = appOwners.beginSettings("ko-KR", setOf("en"))
        val voiceOwners = RetainedSpeechSynthesisLanguageOwners()
        val voiceSettings = voiceOwners.updateSettings(setOf("en"))
        val assetsStarted = CompletableDeferred<Unit>()
        val finishAssets = CompletableDeferred<Unit>()
        var nativeWarmCalls = 0
        var fallbackCalls = 0
        var statusCommits = 0
        val ensureCurrent = {
            if (!appOwners.isCurrent(settingsOwner) ||
                !voiceOwners.isSettingsGenerationCurrent(voiceSettings.settingsGeneration)
            ) {
                throw CancellationException("stale cross-layer settings owner")
            }
        }

        supervisorScope {
            val preparation = async {
                prepareSpeechSynthesisLanguageWithNativeWarmupBoundary(
                    languageTag = "en",
                    prepareMoonshineAssets = {
                        assetsStarted.complete(Unit)
                        finishAssets.await()
                    },
                    warmMoonshine = { nativeWarmCalls += 1 },
                    warmMoonshineWithNativeAdmission = { _, warm -> warm() },
                    prepareAndroidOffline = { fallbackCalls += 1 },
                    moonshinePreparationTimeoutMillis = 1_000L,
                    androidStandbyPreparationTimeoutMillis = 1_000L,
                    ensurePreparationCurrent = ensureCurrent,
                ).also { statusCommits += 1 }
            }

            assetsStarted.await()
            appOwners.beginBroadcast("ko-KR", setOf("ja"))
            finishAssets.complete(Unit)

            try {
                preparation.await()
                fail("Expected app-level broadcast takeover cancellation")
            } catch (error: CancellationException) {
                assertEquals("stale cross-layer settings owner", error.message)
            }
        }

        assertEquals(0, nativeWarmCalls)
        assertEquals(0, fallbackCalls)
        assertEquals(0, statusCommits)
    }

    @Test
    fun assetsAndGalaxyStandbyStayOutsideNativeWarmupAdmission() = runBlocking {
        val ticket = RecordingNativeColdLoadTicket()
        val events = mutableListOf<String>()

        val result = prepareSpeechSynthesisLanguageWithNativeWarmupBoundary(
            languageTag = "en",
            prepareMoonshineAssets = {
                assertNull(currentNativeColdLoadTicket())
                events += "assets"
            },
            warmMoonshine = {
                assertSame(ticket, currentNativeColdLoadTicket())
                events += "native-warm"
            },
            warmMoonshineWithNativeAdmission = { _, warm ->
                events += "admission-start"
                withContext(NativeColdLoadTicketContext(ticket)) { warm() }
                events += "admission-end"
            },
            prepareAndroidOffline = {
                assertNull(currentNativeColdLoadTicket())
                events += "android-standby"
            },
            moonshinePreparationTimeoutMillis = 1_000L,
            androidStandbyPreparationTimeoutMillis = 1_000L,
        )

        assertEquals(SpeechSynthesisPreparationBackend.MOONSHINE, result.preparation.backend)
        assertNull(result.androidStandbyError)
        assertEquals(
            listOf(
                "assets",
                "admission-start",
                "native-warm",
                "admission-end",
                "android-standby",
            ),
            events,
        )
    }

    @Test
    fun failedNativeWarmReleasesAdmissionBeforePreparingGalaxyFallback() = runBlocking {
        val ticket = RecordingNativeColdLoadTicket()
        val events = mutableListOf<String>()

        val result = prepareSpeechSynthesisLanguageWithNativeWarmupBoundary(
            languageTag = "ja",
            prepareMoonshineAssets = { events += "assets" },
            warmMoonshine = {
                assertSame(ticket, currentNativeColdLoadTicket())
                events += "native-warm-failed"
                error("native load failed")
            },
            warmMoonshineWithNativeAdmission = { _, warm ->
                withContext(NativeColdLoadTicketContext(ticket)) { warm() }
            },
            prepareAndroidOffline = {
                assertNull(currentNativeColdLoadTicket())
                events += "android-fallback"
            },
            moonshinePreparationTimeoutMillis = 1_000L,
            androidStandbyPreparationTimeoutMillis = 1_000L,
        )

        assertEquals(
            SpeechSynthesisPreparationBackend.ANDROID_OFFLINE,
            result.preparation.backend,
        )
        assertTrue(result.preparation.moonshineError?.message.orEmpty().contains("native load failed"))
        assertNull(result.androidStandbyError)
        assertEquals(listOf("assets", "native-warm-failed", "android-fallback"), events)
    }

    @Test
    fun optionalStandbyVoiceTimeoutReturnsFailureWithoutHangingSiblingPreparation() = runBlocking {
        val failure = prepareOptionalSpeechStandby(
            languageTag = "en",
            timeoutMillis = 50L,
            prepare = { awaitCancellation() },
        )

        assertTrue(failure?.message.orEmpty().contains("시간 초과"))
    }

    @Test
    fun moonshineFallbackWarningShowsTheExactPerLanguagePreparationCause() {
        val report = GalaxySpeechPreparationReport(
            moonshineLanguageTags = setOf("ja"),
            androidFallbackLanguageTags = linkedSetOf("en", "nl"),
            fallbackReasons = mapOf(
                "en" to "QLinearMatMul\n operator unavailable",
                "nl" to "voice file SHA-256 mismatch",
            ),
        )

        val warning = requireNotNull(report.warning)
        assertTrue(warning.contains("en: QLinearMatMul operator unavailable"))
        assertTrue(warning.contains("nl: voice file SHA-256 mismatch"))
        assertTrue(warning.contains("Android 오프라인 음성"))
    }

    @Test
    fun translatedLongSentenceIsSplitForTtsAtMeaningfulBoundariesWithoutLoss() {
        val firstClause = "This is the first complete sentence."
        val secondClause = " Please keep walking slowly, and look to your left."
        val text = (firstClause + secondClause).repeat(8)

        val chunks = splitTranslatedTextForSpeech(text, maximumCharacters = 120)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 120 })
        assertEquals(
            text.filterNot(Char::isWhitespace),
            chunks.joinToString(" ").filterNot(Char::isWhitespace),
        )
        assertTrue(chunks.dropLast(1).all { chunk ->
            chunk.last() in setOf('.', '!', '?', ',', ';', ':')
        })
    }

    @Test
    fun cjkTranslationWithoutSpacesRemainsLosslessAcrossTtsClauses() {
        val text = "请沿着这条路继续前行，左边可以看到和平步道。".repeat(20)

        val chunks = splitTranslatedTextForSpeech(text, maximumCharacters = 80)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 80 })
        assertEquals(text, chunks.joinToString(""))
    }

    @Test
    fun moonshineSuccessDoesNotInitializeAndroidFallback() = runBlocking {
        val moonshineCalls = mutableListOf<String>()
        val fallbackCalls = mutableListOf<String>()

        val result = prepareSpeechSynthesisLanguages(
            languageTags = listOf("en"),
            prepareMoonshine = { moonshineCalls += it },
            prepareAndroidOffline = { fallbackCalls += it },
        ).single()

        assertEquals(listOf("en"), moonshineCalls)
        assertTrue(fallbackCalls.isEmpty())
        assertEquals(SpeechSynthesisPreparationBackend.MOONSHINE, result.backend)
        assertEquals(null, result.moonshineError)
    }

    @Test
    fun moonshinePreparationTimeoutUsesSameLanguageAndroidFallback() = runBlocking {
        val fallbackCalls = mutableListOf<String>()

        val result = prepareSpeechSynthesisLanguages(
            languageTags = listOf("en"),
            prepareMoonshine = { awaitCancellation() },
            prepareAndroidOffline = { fallbackCalls += it },
            moonshinePreparationTimeoutMillis = 50L,
        ).single()

        assertEquals(listOf("en"), fallbackCalls)
        assertEquals(SpeechSynthesisPreparationBackend.ANDROID_OFFLINE, result.backend)
        assertTrue(result.moonshineError?.message.orEmpty().contains("준비 시간 초과"))
    }

    @Test
    fun callerCancellationDuringMoonshinePreparationNeverStartsFallback() = runBlocking {
        var fallbackCalled = false

        try {
            withTimeout(50L) {
                prepareSpeechSynthesisLanguages(
                    languageTags = listOf("en"),
                    prepareMoonshine = { awaitCancellation() },
                    prepareAndroidOffline = { fallbackCalled = true },
                    moonshinePreparationTimeoutMillis = 10_000L,
                )
            }
            fail("Expected caller cancellation")
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            assertFalse(fallbackCalled)
        }
    }

    @Test
    fun fiveLanguagesContinueWhenIndividualMoonshineVoicesFail() = runBlocking {
        val primaryFailures = setOf("ja", "nl", "es")
        val fallbackCalls = mutableListOf<String>()
        val observed = mutableListOf<SpeechSynthesisLanguagePreparation>()

        val results = prepareSpeechSynthesisLanguages(
            languageTags = listOf("en", "ja", "zh", "nl", "es"),
            prepareMoonshine = { languageTag ->
                if (languageTag in primaryFailures) error("worker died: $languageTag")
            },
            prepareAndroidOffline = { fallbackCalls += it },
            onPrepared = observed::add,
        )

        assertEquals(listOf("ja", "nl", "es"), fallbackCalls)
        assertEquals(listOf("en", "ja", "zh", "nl", "es"), results.map { it.languageTag })
        assertEquals(results, observed)
        assertEquals(
            primaryFailures,
            results.filter {
                it.backend == SpeechSynthesisPreparationBackend.ANDROID_OFFLINE
            }.mapTo(linkedSetOf()) { it.languageTag },
        )
        assertTrue(results.filter { it.languageTag in primaryFailures }.all {
            it.moonshineError?.message?.startsWith("worker died") == true
        })
    }

    @Test
    fun isolatedPreparationKeepsTwoSuccessfulLanguagesWhenOneHasNoVoice() = runBlocking {
        val outcomes = prepareSpeechSynthesisOutcomesIndependently(
            languageTags = listOf("en", "ja", "es"),
            timeoutMillis = 1_000L,
        ) { languageTag ->
            prepareSpeechSynthesisLanguages(
                languageTags = listOf(languageTag),
                prepareMoonshine = { target ->
                    if (target == "ja") error("ja Moonshine worker unavailable")
                },
                prepareAndroidOffline = { target ->
                    if (target == "ja") error("ja Galaxy offline voice missing")
                },
            ).single()
        }

        assertEquals(listOf("en", "ja", "es"), outcomes.map { it.languageTag })
        assertEquals(setOf("en", "es"), outcomes.mapNotNull { it.result?.languageTag }.toSet())
        val report = outcomes.toGalaxySpeechPreparationReport(compatibilityNotice = null)
        assertEquals(linkedSetOf("en", "es"), report.moonshineLanguageTags)
        assertTrue(report.androidFallbackLanguageTags.isEmpty())
        assertEquals(setOf("ja"), report.unavailableLanguageReasons.keys)
        assertTrue(
            outcomes.single { it.languageTag == "ja" }.error is
                SpeechSynthesisPreparationException,
        )
    }

    @Test
    fun isolatedPreparationPropagatesProviderWrappedCancellation() = runBlocking {
        val cancellation = CancellationException("broadcast stopped")

        try {
            prepareSpeechSynthesisOutcomesIndependently(
                languageTags = listOf("en", "ja", "es"),
                timeoutMillis = 1_000L,
            ) { languageTag ->
                if (languageTag == "ja") throw IllegalStateException("wrapped", cancellation)
                SpeechSynthesisLanguagePreparation(
                    languageTag = languageTag,
                    backend = SpeechSynthesisPreparationBackend.MOONSHINE,
                )
            }
            fail("Expected cancellation")
        } catch (error: CancellationException) {
            // Coroutine stack-trace recovery may copy CancellationException, so identity is not a
            // stable contract. The nested provider cancellation must still escape as cancellation.
            assertEquals(cancellation.message, error.message)
        }
    }

    @Test
    fun bothBackendsFailWithLanguageAndBothCauses() = runBlocking {
        val moonshineError = IllegalStateException("native worker unavailable")
        val androidError = IllegalArgumentException("offline voice missing")

        try {
            prepareSpeechSynthesisLanguages(
                languageTags = listOf("nl"),
                prepareMoonshine = { throw moonshineError },
                prepareAndroidOffline = { throw androidError },
            )
            fail("Expected both-backend preparation failure")
        } catch (error: SpeechSynthesisPreparationException) {
            assertEquals("nl", error.languageTag)
            assertSame(moonshineError, error.moonshineError)
            assertSame(androidError, error.androidOfflineError)
            assertSame(androidError, error.cause)
            assertTrue(error.suppressed.contains(moonshineError))
            assertTrue(error.message.orEmpty().contains("Moonshine"))
            assertTrue(error.message.orEmpty().contains("Galaxy 오프라인 음성"))
        }
    }

    @Test
    fun wrappedMoonshineCancellationNeverStartsFallback() = runBlocking {
        val cancellation = CancellationException("session replaced")
        var fallbackCalled = false

        try {
            prepareSpeechSynthesisLanguages(
                languageTags = listOf("en"),
                prepareMoonshine = { throw IllegalStateException("wrapped", cancellation) },
                prepareAndroidOffline = { fallbackCalled = true },
            )
            fail("Expected cancellation")
        } catch (error: CancellationException) {
            // withTimeoutOrNull introduces a coroutine boundary where stack-trace recovery may
            // copy the exception. Its cancellation type/message and no-fallback behavior matter.
            assertEquals(cancellation.message, error.message)
            assertFalse(fallbackCalled)
        }
    }

    @Test
    fun fallbackCancellationIsNotReportedAsDualVoiceFailure() = runBlocking {
        val cancellation = CancellationException("user stopped")

        try {
            prepareSpeechSynthesisLanguages(
                languageTags = listOf("zh"),
                prepareMoonshine = { error("worker died") },
                prepareAndroidOffline = { throw cancellation },
            )
            fail("Expected cancellation")
        } catch (error: CancellationException) {
            // The bounded preparation scope may recover a copied cancellation stack trace.
            assertEquals(cancellation.message, error.message)
        }
    }

    @Test
    fun missingAndroidVoicesTimeOutIndependentlyAndKeepHealthySiblingReady() = runBlocking {
        val fallbackStarted = mutableSetOf<String>()
        val fallbackStopped = mutableSetOf<String>()
        val outcomes = withTimeout(2_000L) {
            prepareSpeechSynthesisOutcomesIndependently(
                languageTags = listOf("en", "zh-TW", "vi"),
                timeoutMillis = 1_000L,
            ) { languageTag ->
                prepareSpeechSynthesisLanguages(
                    languageTags = listOf(languageTag),
                    prepareMoonshine = { target ->
                        if (target != "en") error("Moonshine voice unavailable: $target")
                    },
                    prepareAndroidOffline = { target ->
                        fallbackStarted += target
                        try {
                            awaitCancellation()
                        } finally {
                            fallbackStopped += target
                        }
                    },
                    androidFallbackPreparationTimeoutMillis = 40L,
                ).single()
            }
        }
        assertEquals(setOf("zh-TW", "vi"), fallbackStarted)
        assertEquals(fallbackStarted, fallbackStopped)
        assertEquals(
            SpeechSynthesisPreparationBackend.MOONSHINE,
            outcomes.single { it.languageTag == "en" }.result?.backend,
        )
        listOf("zh-TW", "vi").forEach { languageTag ->
            val error = outcomes.single { it.languageTag == languageTag }.error
            assertTrue(error is SpeechSynthesisPreparationException)
            assertTrue(error?.message.orEmpty().contains("$languageTag Galaxy 오프라인 음성 준비 시간 초과"))
            assertTrue(error?.message.orEmpty().contains("오프라인 음성을 설치한 뒤 다시 준비"))
        }
    }

    @Test
    fun operatorCancellationDuringAndroidFallbackNeverBecomesVoiceFailure() = runBlocking {
        val fallbackStarted = CompletableDeferred<Unit>()
        var fallbackStopped = false
        var voiceFailureReported = false
        supervisorScope {
            val preparation = async {
                try {
                    prepareSpeechSynthesisLanguages(
                        languageTags = listOf("vi"),
                        prepareMoonshine = { error("Moonshine voice unavailable") },
                        prepareAndroidOffline = {
                            fallbackStarted.complete(Unit)
                            try {
                                awaitCancellation()
                            } finally {
                                fallbackStopped = true
                            }
                        },
                        androidFallbackPreparationTimeoutMillis = 5_000L,
                    )
                } catch (error: SpeechSynthesisPreparationException) {
                    voiceFailureReported = true
                    throw error
                }
            }
            withTimeout(1_000L) { fallbackStarted.await() }
            withTimeout(1_000L) { preparation.cancelAndJoin() }
            assertTrue(preparation.isCancelled)
        }
        assertTrue(fallbackStopped)
        assertFalse(voiceFailureReported)
    }

    @Test
    fun invalidLanguageCountFailsBeforeAnyBackendCall() = runBlocking {
        var called = false

        try {
            prepareSpeechSynthesisLanguages(
                languageTags = emptyList(),
                prepareMoonshine = { called = true },
                prepareAndroidOffline = { called = true },
            )
            fail("Expected input validation failure")
        } catch (_: IllegalArgumentException) {
            assertFalse(called)
        }
    }

    @Test
    fun eightLanguagesFailBeforeAnyBackendCall() = runBlocking {
        var called = false

        try {
            prepareSpeechSynthesisLanguages(
                languageTags = listOf("en", "ja", "zh", "nl", "es", "ar", "vi", "de"),
                prepareMoonshine = { called = true },
                prepareAndroidOffline = { called = true },
            )
            fail("Expected input validation failure")
        } catch (_: IllegalArgumentException) {
            assertFalse(called)
        }
    }

    @Test
    fun runtimeMoonshineFailureEmitsGalaxyFallbackPcm() = runBlocking {
        val primaryError = IllegalStateException("binder died")
        val expected = audibleFrame(123L)
        var fallbackCalled = false
        var reportedPrimary: Throwable? = null

        val frames = synthesizeSpeechWithFallback(
            primary = engine { _, _ -> flow { throw primaryError } },
            fallback = engine { _, _ ->
                fallbackCalled = true
                flowOf(expected)
            },
            text = "hello",
            languageTag = "en",
            onPrimaryFailure = { reportedPrimary = it },
        ).toList()

        assertTrue(fallbackCalled)
        assertEquals(primaryError::class, reportedPrimary?.let { it::class })
        assertEquals(primaryError.message, reportedPrimary?.message)
        assertEquals(listOf(expected), frames)
    }

    @Test
    fun preparedGalaxyFallbackSkipsKnownFailedMoonshineWorkerOnLaterSentences() = runBlocking {
        val expected = audibleFrame(124L)
        var primaryCalls = 0
        var fallbackCalls = 0
        var fallbackReadySignals = 0

        val frames = synthesizeSpeechWithStickyFallback(
            primary = engine { _, _ ->
                primaryCalls += 1
                flow { error("known broken native voice must not be retried") }
            },
            fallback = engine { _, _ ->
                fallbackCalls += 1
                flowOf(expected)
            },
            text = "translated script remains available",
            languageTag = "en",
            usePreparedFallback = { true },
            onFallbackAudibleFrame = { fallbackReadySignals += 1 },
        ).toList()

        assertEquals(0, primaryCalls)
        assertEquals(1, fallbackCalls)
        assertEquals(1, fallbackReadySignals)
        assertEquals(listOf(expected), frames)
    }

    @Test
    fun explicitMoonshinePreparationRestoresPrimaryRouteAfterStickyFallback() = runBlocking {
        val expected = audibleFrame(125L)
        var primaryCalls = 0
        var fallbackCalls = 0

        val frames = synthesizeSpeechWithStickyFallback(
            primary = engine { _, _ ->
                primaryCalls += 1
                flowOf(expected)
            },
            fallback = engine { _, _ ->
                fallbackCalls += 1
                flowOf(audibleFrame(126L))
            },
            text = "primary voice was prepared again",
            languageTag = "en",
            usePreparedFallback = { false },
        ).toList()

        assertEquals(1, primaryCalls)
        assertEquals(0, fallbackCalls)
        assertEquals(listOf(expected), frames)
    }

    @Test
    fun note9TimingKeepsSlowButHealthyMoonshineSpeechInsteadOfCancellingIt() = runBlocking {
        val expected = audibleFrame(456L)
        var fallbackCalled = false

        val frames = synthesizeSpeechWithFallback(
            primary = engine { _, _ ->
                flow {
                    // Longer than the normal 2.5 s S23 failover threshold, but healthy on the
                    // slower Note9 compatibility path.
                    delay(2_600L)
                    emit(expected)
                }
            },
            fallback = engine { _, _ ->
                fallbackCalled = true
                flowOf(audibleFrame(1L))
            },
            text = "published translation",
            languageTag = "en",
            primaryFirstFrameTimeoutMillis = 6_500L,
        ).toList()

        assertEquals(listOf(expected), frames)
        assertFalse(fallbackCalled)
    }

    @Test
    fun emptyMoonshineOutputKeepsTranslatedTextAndUsesFallbackPcm() = runBlocking {
        val expected = audibleFrame(456L)
        var reportedPrimary: Throwable? = null

        val frames = synthesizeSpeechWithFallback(
            primary = engine { _, _ -> flowOf() },
            fallback = engine { _, _ -> flowOf(expected) },
            text = "translated script already visible",
            languageTag = "en",
            onPrimaryFailure = { reportedPrimary = it },
        ).toList()

        assertTrue(requireNotNull(reportedPrimary).message.orEmpty().contains("PCM 출력 없음"))
        assertEquals(listOf(expected), frames)
    }

    @Test
    fun silentMoonshineOutputIsNotAcceptedAsSuccessfulSpeech() = runBlocking {
        val silent = PcmAudioFrame(byteArrayOf(0, 0, 0, 0), 1L)
        val audible = audibleFrame(2L)

        val frames = synthesizeSpeechWithFallback(
            primary = engine { _, _ -> flowOf(silent) },
            fallback = engine { _, _ -> flowOf(audible) },
            text = "hello",
            languageTag = "en",
        ).toList()

        assertEquals(listOf(silent, audible), frames)
    }

    @Test
    fun audiblePrimaryPcmThenFailureNeverReplaysWholeSentenceThroughFallback() = runBlocking {
        val audible = audibleFrame(1L)
        val primaryError = IllegalStateException("native worker stopped mid sentence")
        val collected = mutableListOf<PcmAudioFrame>()
        var fallbackCalled = false
        var reportedPrimary: Throwable? = null

        try {
            synthesizeSpeechWithFallback(
                primary = engine { _, _ ->
                    flow {
                        emit(audible)
                        throw primaryError
                    }
                },
                fallback = engine { _, _ ->
                    fallbackCalled = true
                    flowOf(audibleFrame(2L))
                },
                text = "do not speak this sentence twice",
                languageTag = "en",
                onPrimaryFailure = { reportedPrimary = it },
            ).collect { collected += it }
            fail("Expected partial synthesis failure")
        } catch (error: PartialSpeechSynthesisException) {
            // Coroutine stack-trace recovery may create an equivalent exception instance at a
            // channel boundary. Preserve the provider type/message contract, not JVM identity.
            assertEquals(primaryError.javaClass, error.cause?.javaClass)
            assertEquals(primaryError.message, error.cause?.message)
        }

        assertEquals(listOf(audible), collected)
        assertFalse(fallbackCalled)
        assertEquals(primaryError.javaClass, reportedPrimary?.javaClass)
        assertEquals(primaryError.message, reportedPrimary?.message)
    }

    @Test
    fun silenceOnlyThenPrimaryFailureUsesFallbackBecauseNothingWasAudible() = runBlocking {
        val silent = PcmAudioFrame(byteArrayOf(0, 0, 0, 0), 1L)
        val audibleFallback = audibleFrame(2L)
        var fallbackCalled = false

        val frames = synthesizeSpeechWithFallback(
            primary = engine { _, _ ->
                flow {
                    emit(silent)
                    error("native worker stopped before speech")
                }
            },
            fallback = engine { _, _ ->
                fallbackCalled = true
                flowOf(audibleFallback)
            },
            text = "translated script remains queued",
            languageTag = "en",
        ).toList()

        assertTrue(fallbackCalled)
        assertEquals(listOf(silent, audibleFallback), frames)
    }

    @Test
    fun silentAndOneLsbFramesCannotKeepAStalledPrimaryFromAudibleFallback() = runBlocking {
        val silent = PcmAudioFrame(byteArrayOf(0, 0, 0, 0), 1L)
        val oneLsbNoise = PcmAudioFrame(byteArrayOf(1, 0, -1, -1), 2L)
        val audibleFallback = audibleFrame(3L)
        var fallbackCalled = false

        val frames = withTimeout(1_000L) {
            synthesizeSpeechWithFallback(
                primary = engine { _, _ ->
                    flow {
                        emit(silent)
                        emit(oneLsbNoise)
                        awaitCancellation()
                    }
                },
                fallback = engine { _, _ ->
                    fallbackCalled = true
                    flowOf(audibleFallback)
                },
                text = "translated script remains committed",
                languageTag = "en",
                primaryFirstFrameTimeoutMillis = 50L,
                fallbackFirstFrameTimeoutMillis = 200L,
            ).toList()
        }

        assertTrue(fallbackCalled)
        assertEquals(listOf(silent, oneLsbNoise, audibleFallback), frames)
    }

    @Test
    fun queuedFallbackStartsAudibleDeadlineOnlyAfterProviderExecutionStarts() = runBlocking {
        val expected = audibleFrame(3L)
        val queuedFallback = object : ExecutionAwareSpeechSynthesisEngine {
            override val maximumExecutionStartWaitMillis = 500L

            override fun synthesize(
                text: String,
                languageTag: String,
                onExecutionStarted: () -> Unit,
            ): Flow<PcmAudioFrame> = flow {
                delay(100L) // Valid provider admission wait; longer than the audible deadline.
                onExecutionStarted()
                delay(10L)
                emit(expected)
            }
        }

        val frames = withTimeout(1_000L) {
            synthesizeSpeechWithStickyFallback(
                primary = queuedFallback,
                fallback = queuedFallback,
                text = "queued translated speech",
                languageTag = "ja",
                usePreparedFallback = { true },
                fallbackFirstFrameTimeoutMillis = 50L,
            ).toList()
        }

        assertEquals(listOf(expected), frames)
    }

    @Test
    fun queuedFallbackStillTimesOutWhenAudiblePcmStallsAfterExecutionStarts() = runBlocking {
        val stalledFallback = object : ExecutionAwareSpeechSynthesisEngine {
            override val maximumExecutionStartWaitMillis = 500L

            override fun synthesize(
                text: String,
                languageTag: String,
                onExecutionStarted: () -> Unit,
            ): Flow<PcmAudioFrame> = flow {
                delay(100L)
                onExecutionStarted()
                awaitCancellation()
            }
        }

        try {
            withTimeout(1_000L) {
                synthesizeSpeechWithStickyFallback(
                    primary = stalledFallback,
                    fallback = stalledFallback,
                    text = "stalled translated speech",
                    languageTag = "zh",
                    usePreparedFallback = { true },
                    fallbackFirstFrameTimeoutMillis = 50L,
                ).toList()
            }
            fail("Expected post-start first-audible timeout")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains("첫 가청 PCM"))
        }
    }

    @Test
    fun backendReadinessCallbacksRequireAudiblePcm() = runBlocking {
        val silent = PcmAudioFrame(byteArrayOf(0, 0, 0, 0), 1L)
        val oneLsbNoise = PcmAudioFrame(byteArrayOf(1, 0, -1, -1), 2L)
        val audibleFallback = audibleFrame(3L)
        var primaryReadySignals = 0
        var fallbackReadySignals = 0

        synthesizeSpeechWithFallback(
            primary = engine { _, _ -> flowOf(silent, oneLsbNoise) },
            fallback = engine { _, _ -> flowOf(silent, oneLsbNoise, audibleFallback) },
            text = "status follows audible output",
            languageTag = "en",
            onPrimaryAudibleFrame = { primaryReadySignals += 1 },
            onFallbackAudibleFrame = { fallbackReadySignals += 1 },
        ).toList()

        assertEquals(0, primaryReadySignals)
        assertEquals(1, fallbackReadySignals)
    }

    @Test
    fun runtimeCancellationNeverSynthesizesFallback() = runBlocking {
        val cancellation = CancellationException("broadcast stopped")
        var fallbackCalled = false

        try {
            synthesizeSpeechWithFallback(
                primary = engine { _, _ -> flow { throw cancellation } },
                fallback = engine { _, _ ->
                    fallbackCalled = true
                    flowOf(PcmAudioFrame(byteArrayOf(0, 0), 1L))
                },
                text = "hello",
                languageTag = "en",
            ).toList()
            fail("Expected cancellation")
        } catch (error: CancellationException) {
            assertEquals(cancellation.message, error.message)
            assertFalse(fallbackCalled)
        }
    }

    @Test
    fun outerSessionTimeoutNeverStartsFallbackVoice() = runBlocking {
        var fallbackCalled = false

        try {
            withTimeout(50L) {
                synthesizeSpeechWithFallback(
                    primary = engine { _, _ -> flow { awaitCancellation() } },
                    fallback = engine { _, _ ->
                        fallbackCalled = true
                        flowOf(PcmAudioFrame(byteArrayOf(1, 0), 1L))
                    },
                    text = "published translation",
                    languageTag = "en",
                ).toList()
            }
            fail("Expected outer timeout")
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            assertFalse(fallbackCalled)
        }
    }

    @Test
    fun independentOutcomesIsolatesSingleLanguageFailureWithoutAbortingSibling() = runBlocking {
        val outcomes = prepareSpeechSynthesisOutcomesIndependently(
            languageTags = listOf("en", "ja"),
            timeoutMillis = 5_000L,
            prepareLanguage = { tag ->
                if (tag == "en") {
                    throw IllegalStateException("Moonshine TTS worker died during PCM streaming")
                } else {
                    SpeechSynthesisLanguagePreparation(
                        languageTag = "ja",
                        backend = SpeechSynthesisPreparationBackend.MOONSHINE,
                        moonshineError = null,
                    )
                }
            },
        )

        assertEquals(2, outcomes.size)
        val enOutcome = outcomes.first { it.languageTag == "en" }
        val jaOutcome = outcomes.first { it.languageTag == "ja" }

        assertNotNull(enOutcome.error)
        assertTrue(enOutcome.error is IllegalStateException)
        assertEquals("Moonshine TTS worker died during PCM streaming", enOutcome.error?.message)

        assertNotNull(jaOutcome.result)
        assertEquals(SpeechSynthesisPreparationBackend.MOONSHINE, jaOutcome.result?.backend)

        val report = outcomes.toGalaxySpeechPreparationReport(compatibilityNotice = null)
        assertTrue("ja must be in moonshineLanguageTags", "ja" in report.moonshineLanguageTags)
        assertFalse("en must not be in moonshineLanguageTags", "en" in report.moonshineLanguageTags)
        assertTrue("en must be in unavailableLanguageReasons", "en" in report.unavailableLanguageReasons)
    }

    private fun engine(
        synthesis: (String, String) -> Flow<PcmAudioFrame>,
    ): SpeechSynthesisEngine = object : SpeechSynthesisEngine {
        override fun synthesize(text: String, languageTag: String): Flow<PcmAudioFrame> =
            synthesis(text, languageTag)
    }

    private fun audibleFrame(capturedAtElapsedRealtimeNanos: Long): PcmAudioFrame =
        PcmAudioFrame(
            // PCM16-LE +4096/-4096: well above the shared RMS/peak audibility threshold.
            byteArrayOf(0, 16, 0, -16),
            capturedAtElapsedRealtimeNanos,
        )

    private class RecordingNativeColdLoadTicket : NativeColdLoadTicket {
        override fun transferToNative() = Unit

        override fun completeNative() = Unit

        override fun close() = Unit
    }
}
