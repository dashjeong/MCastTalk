package app.guidecast.provider.android.tts

import java.io.Closeable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RetiringLanguageEngineRegistryTest {
    @Test
    fun deselectedEngineDrainsActiveSynthesisBeforeDisposal() {
        val registry = registry()
        val english = registry.getOrCreate("en")
        val japanese = registry.getOrCreate("ja")
        val activeSynthesis = english.acquire()

        registry.reconcileLanguages("broadcast", setOf("ja"))

        assertEquals(setOf("ja"), registry.currentLanguageTags())
        assertSame(japanese, registry.getOrCreate("ja"))
        assertEquals(0, english.disposeCalls.get())
        assertThrows(IllegalStateException::class.java) { english.acquire() }

        activeSynthesis.close()

        assertEquals(1, english.disposeCalls.get())
        registry.reconcileLanguages("broadcast", setOf("en", "ja"))
        val replacementEnglish = registry.getOrCreate("en")
        assertNotSame(english, replacementEnglish)
    }

    @Test
    fun providerCloseForcesBothSelectedAndAlreadyDrainingEnginesExactlyOnce() {
        val registry = registry()
        val english = registry.getOrCreate("en")
        val japanese = registry.getOrCreate("ja")
        val activeSynthesis = english.acquire()
        registry.reconcileLanguages("broadcast", setOf("ja"))

        registry.closeAll()

        assertTrue(registry.currentLanguageTags().isEmpty())
        assertEquals(1, english.disposeCalls.get())
        assertEquals(1, japanese.disposeCalls.get())
        activeSynthesis.close()
        assertEquals(1, english.disposeCalls.get())
    }

    @Test
    fun lateDisposalFromOldGenerationCannotRemoveReplacementEngine() {
        val registry = registry()
        val oldEnglish = registry.getOrCreate("en")
        val activeSynthesis = oldEnglish.acquire()
        registry.reconcileLanguages("broadcast", setOf("ja"))
        registry.reconcileLanguages("broadcast", setOf("en"))
        val replacementEnglish = registry.getOrCreate("en")

        activeSynthesis.close()

        assertEquals(1, oldEnglish.disposeCalls.get())
        assertSame(replacementEnglish, registry.getOrCreate("en"))
        assertEquals(setOf("en"), registry.currentLanguageTags())
    }

    @Test
    fun settingsLanguageSwitchRetiresOnlyLanguagesUnownedByLiveBroadcast() {
        val registry = registry()
        val broadcastEnglish = registry.getOrCreate("en")
        registry.reconcileLanguages("broadcast", setOf("en"))
        registry.reconcileLanguages("settings", setOf("ja"))
        val firstSettingsVoice = registry.getOrCreate("ja")

        registry.reconcileLanguages("settings", setOf("nl"))
        val nextSettingsVoice = registry.getOrCreate("nl")

        assertEquals(setOf("en", "nl"), registry.retainedLanguageTags())
        assertEquals(setOf("en", "nl"), registry.currentLanguageTags())
        assertEquals(0, broadcastEnglish.disposeCalls.get())
        assertEquals(1, firstSettingsVoice.disposeCalls.get())
        assertEquals(0, nextSettingsVoice.disposeCalls.get())

        registry.reconcileLanguages("broadcast", emptySet())

        assertEquals(1, broadcastEnglish.disposeCalls.get())
        assertEquals(setOf("nl"), registry.currentLanguageTags())
    }

    @Test
    fun settingsOnlyLanguageChurnDoesNotAccumulateOldEngines() {
        val registry = registry()
        registry.reconcileLanguages("settings", setOf("en"))
        val english = registry.getOrCreate("en")

        registry.reconcileLanguages("settings", setOf("ja"))
        val japanese = registry.getOrCreate("ja")
        registry.reconcileLanguages("settings", setOf("nl"))
        val dutch = registry.getOrCreate("nl")

        assertEquals(1, english.disposeCalls.get())
        assertEquals(1, japanese.disposeCalls.get())
        assertEquals(0, dutch.disposeCalls.get())
        assertEquals(setOf("nl"), registry.retainedLanguageTags())
        assertEquals(setOf("nl"), registry.currentLanguageTags())
    }

    @Test
    fun broadcastTakeoverAtomicallyDropsFiveSettingsVoicesAndRejectsStaleCreation() {
        val registry = registry()
        val settingsLanguages = setOf("en", "ja", "zh", "nl", "es")
        val broadcastLanguages = setOf("de", "fr", "it", "pt", "id")
        registry.reconcileLanguages("settings", settingsLanguages)
        val settingsEngines = settingsLanguages.associateWith(registry::getOrCreate)

        registry.reconcileLanguageOwners(
            mapOf(
                "broadcast" to broadcastLanguages,
                "settings" to emptySet(),
            ),
        )
        val broadcastEngines = broadcastLanguages.associateWith(registry::getOrCreate)
        val liveBroadcastLease = requireNotNull(broadcastEngines["de"]).acquire()

        assertEquals(broadcastLanguages, registry.retainedLanguageTags())
        assertEquals(broadcastLanguages, registry.currentLanguageTags())
        assertTrue(settingsEngines.values.all { it.disposeCalls.get() == 1 })
        assertTrue(broadcastEngines.values.all { it.disposeCalls.get() == 0 })
        assertThrows(IllegalStateException::class.java) {
            registry.getOrCreate("en")
        }

        liveBroadcastLease.close()
        assertEquals(0, requireNotNull(broadcastEngines["de"]).disposeCalls.get())
    }

    @Test
    fun retirementAndFinalLeaseReleaseRaceDisposesExactlyOnce() {
        repeat(200) {
            val disposeCalls = AtomicInteger()
            val lifecycle = RetirableResourceLifecycle { disposeCalls.incrementAndGet() }
            val lease = lifecycle.acquire()
            val barrier = CyclicBarrier(2)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val retire = executor.submit {
                    barrier.await()
                    lifecycle.retireWhenIdle()
                }
                val release = executor.submit {
                    barrier.await()
                    lease.close()
                }
                retire.get(2, TimeUnit.SECONDS)
                release.get(2, TimeUnit.SECONDS)
            } finally {
                executor.shutdownNow()
            }
            assertEquals(1, disposeCalls.get())
        }
    }

    private fun registry(): RetiringLanguageEngineRegistry<FakeEngine> =
        RetiringLanguageEngineRegistry(
            create = { _, onDisposed -> FakeEngine(onDisposed) },
            retireWhenIdle = FakeEngine::retireWhenIdle,
            forceClose = FakeEngine::close,
        )

    private class FakeEngine(onDisposed: () -> Unit) : Closeable {
        val disposeCalls = AtomicInteger()
        private val lifecycle = RetirableResourceLifecycle {
            disposeCalls.incrementAndGet()
            onDisposed()
        }

        fun acquire(): Closeable = lifecycle.acquire()

        fun retireWhenIdle() = lifecycle.retireWhenIdle()

        override fun close() = lifecycle.closeNow()
    }
}
