package app.guidecast.transmitter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiLanguageResourcePlannerTest {
    @Test
    fun recommendationsShrinkUnderPressureWithoutChangingSelection() {
        val selected = listOf("en", "ja", "zh", "nl")
        val roomy = evaluate(memory(totalGiB = 7.2, availableGiB = 6.0), selected = selected)
        val tight = evaluate(memory(totalGiB = 7.2, availableGiB = 0.2), selected = selected)
        assertTrue(requireNotNull(roomy.recommendedChannelCount) in 1..7)
        assertEquals(0, tight.recommendedChannelCount)
        assertEquals(selected, tight.languagePlans.map { it.languageTag })
        val unknown = evaluate(memory(totalGiB = 7.2, availableGiB = 6.0).copy(valuesValid = false),
            selected = selected)
        assertEquals(null, unknown.recommendedChannelCount)
    }
    @Test
    fun oneThroughSevenColdChannelsHaveFiniteLinearAdmissionReservations() {
        val catalog = PRIMARY_TRANSLATION_LANGUAGE_TAGS + listOf("nl", "es")
        val reservation = NativeSupportMemoryBudget.DEFAULT_NEW_WORKER_RESERVATION_BYTES

        (1..MAX_SIMULTANEOUS_TRANSLATION_LANGUAGES).forEach { channelCount ->
            val selected = catalog.take(channelCount)
            val result = evaluate(
                memory = memory(totalGiB = 7.2, availableGiB = 7.0),
                selected = selected,
                speechReady = false,
            )

            // One shared STT reservation plus one translation and one TTS reservation per
            // language. This is an admission envelope, not a measured-RSS promise.
            assertEquals(
                "Unexpected cold-load reservation for $channelCount channels",
                reservation * (1L + 2L * channelCount),
                result.estimatedAdditionalReservationBytes,
            )
            assertEquals(selected, result.languagePlans.map(LanguageResourcePlan::languageTag))
        }
    }

    @Test
    fun lowMemoryTelemetryNeverSilentlyRemovesAnyOfOneThroughSevenReadyChannels() {
        val catalog = PRIMARY_TRANSLATION_LANGUAGE_TAGS + listOf("nl", "es")

        (1..MAX_SIMULTANEOUS_TRANSLATION_LANGUAGES).forEach { channelCount ->
            val selected = catalog.take(channelCount)
            val result = evaluate(
                memory = memory(
                    totalGiB = 7.2,
                    availableGiB = 0.7,
                    systemLowMemory = true,
                ),
                selected = selected,
                speechReady = true,
                translationReady = selected.toSet(),
                synthesisReady = selected.toSet(),
            )

            assertEquals(MultiLanguageResourceState.PRESSURE, result.state)
            assertTrue(result.requiresSequentialPreparation)
            assertEquals(0L, result.estimatedAdditionalReservationBytes)
            assertEquals(selected, result.languagePlans.map(LanguageResourcePlan::languageTag))
            assertTrue(result.recommendation.contains("차단하지 않습니다"))
        }
    }

    @Test
    fun eightGbSingleEnglishReadyAddsNoReservationOrWorkerSideEffect() {
        val result = evaluate(
            memory = memory(totalGiB = 7.2, availableGiB = 4.2),
            selected = listOf("en"),
            speechReady = true,
            translationReady = setOf("en"),
            synthesisReady = setOf("en"),
        )

        assertEquals(MultiLanguageResourceState.SAFE, result.state)
        assertEquals(0L, result.estimatedAdditionalReservationBytes)
        assertEquals(1, result.languagePlans.size)
        assertTrue(result.languagePlans.single().translationPlan.contains("준비됨"))
        assertTrue(result.initialMemoryProfileLabel.startsWith("8GB급 초기 메모리 추정"))
        assertTrue(result.initialMemoryProfileLabel.contains("성능을 보장하지 않습니다"))
    }

    @Test
    fun eightGbFiveColdLanguagesUsesOneSttAndTwoReservationsPerLanguage() {
        val result = evaluate(
            memory = memory(totalGiB = 7.2, availableGiB = 5.5),
            selected = listOf("en", "ja", "zh", "nl", "es"),
            speechReady = false,
        )

        val reservation = NativeSupportMemoryBudget.DEFAULT_NEW_WORKER_RESERVATION_BYTES
        assertEquals(reservation * 11L, result.estimatedAdditionalReservationBytes)
        assertEquals(5, result.languagePlans.size)
        assertTrue(result.requiresSequentialPreparation)
    }

    @Test
    fun sixGbFourLanguagesIsAtLeastCautionAndSequential() {
        val languages = listOf("en", "ja", "zh", "nl")
        val result = evaluate(
            memory = memory(totalGiB = 5.3, availableGiB = 4.0),
            selected = languages,
            speechReady = true,
            translationReady = languages.toSet(),
            synthesisReady = languages.toSet(),
        )

        assertEquals(MultiLanguageResourceState.CAUTION, result.state)
        assertTrue(result.requiresSequentialPreparation)
    }

    @Test
    fun androidLowMemoryAlwaysReportsPressureWithoutChangingSelection() {
        val selected = listOf("en", "ja")
        val result = evaluate(
            memory = memory(
                totalGiB = 7.2,
                availableGiB = 3.5,
                systemLowMemory = true,
            ),
            selected = selected,
            speechReady = true,
            translationReady = selected.toSet(),
            synthesisReady = selected.toSet(),
        )

        assertEquals(MultiLanguageResourceState.PRESSURE, result.state)
        assertEquals(selected, result.languagePlans.map(LanguageResourcePlan::languageTag))
        assertTrue(result.recommendation.contains("차단하지 않습니다"))
    }

    @Test
    fun readyResidentFactsYieldZeroIncrementalReservation() {
        val selected = listOf("en", "ja", "zh", "nl", "es")
        val result = evaluate(
            memory = memory(totalGiB = 7.2, availableGiB = 5.0),
            selected = selected,
            speechReady = true,
            translationReady = selected.toSet(),
            synthesisReady = selected.toSet(),
        )

        assertEquals(0L, result.estimatedAdditionalReservationBytes)
        assertFalse(result.gemmaColdLoadFloorApplies)
    }

    @Test
    fun preparedGalaxyFallbackVoiceNeedsNoAdditionalReservation() {
        val result = evaluate(
            memory = memory(totalGiB = 7.2, availableGiB = 4.0),
            selected = listOf("en"),
            speechReady = true,
            translationReady = setOf("en"),
            fallbackReady = setOf("en"),
        )

        assertEquals(0L, result.estimatedAdditionalReservationBytes)
        assertTrue(result.languagePlans.single().synthesisPlan.contains("Galaxy"))
    }

    @Test
    fun smallAvailableMemoryFluctuationDoesNotInvalidateUiState() {
        val first = memory(totalGiB = 7.2, availableGiB = 4.0)
        val smallDelta = first.copy(availableMemoryBytes = first.availableMemoryBytes + 49L * 1024L * 1024L)
        val materialDelta = first.copy(availableMemoryBytes = first.availableMemoryBytes + 50L * 1024L * 1024L)

        assertFalse(smallDelta.materiallyDiffersFrom(first))
        assertTrue(materialDelta.materiallyDiffersFrom(first))
        assertTrue(first.copy(systemLowMemory = true).materiallyDiffersFrom(first))
    }

    @Test
    fun coldGemmaUsesAvailableMemoryFloorButNotModelFileAsReservation() {
        val result = evaluate(
            memory = memory(totalGiB = 7.2, availableGiB = 2.9),
            selected = listOf("en"),
            useGemma = true,
            gemmaReady = true,
            speechReady = true,
            translationReady = setOf("en"),
            synthesisReady = setOf("en"),
        )

        assertEquals(0L, result.estimatedAdditionalReservationBytes)
        assertTrue(result.gemmaColdLoadFloorApplies)
        assertEquals(MultiLanguageResourceState.PRESSURE, result.state)
    }

    @Test
    fun preparedSharedGemmaWorkerDoesNotReapplyColdLoadFloor() {
        val result = evaluate(
            memory = memory(totalGiB = 7.2, availableGiB = 2.9),
            selected = listOf("en", "ja", "zh", "zh-TW", "es"),
            useGemma = true,
            gemmaReady = true,
            gemmaWorkerPrepared = true,
            speechReady = true,
            translationReady = setOf("en", "ja", "zh", "zh-TW", "es"),
            synthesisReady = setOf("en", "ja", "zh", "zh-TW", "es"),
        )

        assertFalse(result.gemmaColdLoadFloorApplies)
        assertEquals(MultiLanguageResourceState.SAFE, result.state)
        assertTrue(result.languagePlans.all { it.translationPlan.contains("공유 Gemma") })
    }

    @Test
    fun sharedGemmaSupportsEveryCapableTargetWithoutPerTargetEngineReservation() {
        val selected = PRIMARY_TRANSLATION_LANGUAGE_TAGS + listOf("nl", "es")
        val result = evaluate(
            memory = memory(totalGiB = 11.0, availableGiB = 8.0),
            selected = selected,
            useGemma = true,
            gemmaReady = true,
            gemmaWorkerPrepared = true,
            speechReady = true,
            translationReady = selected.toSet(),
            synthesisReady = selected.toSet(),
        )

        assertEquals(7, result.languagePlans.size)
        assertEquals(
            setOf("en", "ja", "zh", "zh-TW", "es"),
            result.languagePlans
                .filter { it.translationPlan.contains("공유 Gemma") }
                .mapTo(linkedSetOf(), LanguageResourcePlan::languageTag),
        )
        assertEquals(0L, result.estimatedAdditionalReservationBytes)
        assertTrue(result.initialMemoryProfileLabel.startsWith("12GB급 초기 메모리 추정"))
        assertTrue(result.initialMemoryProfileLabel.contains("2워커를 자동 사용하지 않으며"))
        assertTrue(result.initialMemoryProfileLabel.contains("성능을 보장하지 않습니다"))
    }

    @Test
    fun measuredResidentPssIsNotSubtractedAgainFromAvailableMemory() {
        val memory = memory(totalGiB = 15.0, availableGiB = 6.0)
        val input = MultiLanguageResourceInput(
            selectedLanguageTags = PRIMARY_TRANSLATION_LANGUAGE_TAGS + listOf("nl", "es"),
            useGemma = true,
            gemmaModelReady = true,
            gemmaWorkerPrepared = true,
            speechRecognitionReady = true,
            translationReadyLanguageTags = (PRIMARY_TRANSLATION_LANGUAGE_TAGS + listOf("nl", "es")).toSet(),
            synthesisReadyLanguageTags = (PRIMARY_TRANSLATION_LANGUAGE_TAGS + listOf("nl", "es")).toSet(),
            synthesisFallbackReadyLanguageTags = emptySet(),
        )
        val measured = AppProcessMemorySnapshot(
            processCount = 8,
            totalPssBytes = 3L * 1024L * 1024L * 1024L,
            totalPrivateDirtyBytes = 2L * 1024L * 1024L * 1024L,
            valuesValid = true,
        )

        val result = MultiLanguageResourcePlanner.evaluate(memory, input, measured)

        assertEquals(memory.availableMemoryBytes, result.projectedAvailableMemoryBytes)
        assertEquals(0L, result.estimatedAdditionalReservationBytes)
        assertEquals(measured, result.appProcessMemory)
        assertTrue(result.initialMemoryProfileLabel.startsWith("16GB급 초기 메모리 추정"))
    }

    @Test
    fun recommendationCatalogUsesPrimaryFiveThenDutchAndSpanish() {
        assertEquals(
            PRIMARY_TRANSLATION_LANGUAGE_TAGS + listOf("nl", "es"),
            MultiLanguageResourcePlanner.recommendationLanguageChoices(emptyList()),
        )
        val result = evaluate(
            memory = memory(totalGiB = 15.0, availableGiB = 12.0),
            selected = emptyList(),
            speechReady = true,
            translationReady = (PRIMARY_TRANSLATION_LANGUAGE_TAGS + listOf("nl", "es")).toSet(),
            synthesisReady = (PRIMARY_TRANSLATION_LANGUAGE_TAGS + listOf("nl", "es")).toSet(),
        )

        assertEquals(7, result.recommendedChannelCount)
        assertTrue(result.recommendation.contains("추정 메모리 권장"))
    }

    @Test
    fun invalidAndOverflowLikeMemoryValuesNeverCrashAndReportPressure() {
        val invalid = DeviceMemorySnapshot.fromRaw(
            totalMemoryBytes = -1L,
            availableMemoryBytes = Long.MAX_VALUE,
            lowMemoryThresholdBytes = -5L,
            systemLowMemory = false,
        )
        val result = evaluate(
            memory = invalid,
            selected = listOf("en", "ja", "zh", "zh-TW", "vi", "nl", "es", "ar"),
        )

        assertFalse(result.memory.valuesValid)
        assertEquals(MultiLanguageResourceState.PRESSURE, result.state)
        assertEquals(MAX_SIMULTANEOUS_TRANSLATION_LANGUAGES, result.languagePlans.size)
        assertTrue(result.estimatedAdditionalReservationBytes >= 0L)
    }

    private fun evaluate(
        memory: DeviceMemorySnapshot,
        selected: List<String>,
        useGemma: Boolean = false,
        gemmaReady: Boolean = false,
        gemmaWorkerPrepared: Boolean = false,
        speechReady: Boolean = false,
        translationReady: Set<String> = emptySet(),
        synthesisReady: Set<String> = emptySet(),
        fallbackReady: Set<String> = emptySet(),
    ) = MultiLanguageResourcePlanner.evaluate(
        memory = memory,
        input = MultiLanguageResourceInput(
            selectedLanguageTags = selected,
            useGemma = useGemma,
            gemmaModelReady = gemmaReady,
            gemmaWorkerPrepared = gemmaWorkerPrepared,
            speechRecognitionReady = speechReady,
            translationReadyLanguageTags = translationReady,
            synthesisReadyLanguageTags = synthesisReady,
            synthesisFallbackReadyLanguageTags = fallbackReady,
        ),
    )

    private fun memory(
        totalGiB: Double,
        availableGiB: Double,
        thresholdGiB: Double = 0.5,
        systemLowMemory: Boolean = false,
    ) = DeviceMemorySnapshot.fromRaw(
        totalMemoryBytes = (totalGiB * GIB).toLong(),
        availableMemoryBytes = (availableGiB * GIB).toLong(),
        lowMemoryThresholdBytes = (thresholdGiB * GIB).toLong(),
        systemLowMemory = systemLowMemory,
    )

    private companion object {
        const val GIB = 1024.0 * 1024.0 * 1024.0
    }
}
