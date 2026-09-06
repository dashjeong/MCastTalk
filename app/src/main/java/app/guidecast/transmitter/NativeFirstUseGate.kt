package app.guidecast.transmitter

import app.guidecast.core.stream.PcmAudioFrame
import app.guidecast.core.translation.ContextualTextTranslationEngine
import app.guidecast.core.translation.ExecutionAwareSpeechSynthesisEngine
import app.guidecast.core.translation.NativeColdLoadTicket
import app.guidecast.core.translation.NativeColdLoadTicketContext
import app.guidecast.core.translation.SpeechSynthesisEngine
import app.guidecast.core.translation.SpeechSynthesisEngineProvider
import app.guidecast.core.translation.TextTranslationEngine
import app.guidecast.core.translation.TranslationEngineProvider
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore

/**
 * Limits either the first successful native use of each worker key, or every operation when the
 * a cached Binder worker generation has been invalidated.
 *
 * A 6 GB-class broadcast must not initialize five translators and five voices at once. Once a
 * worker has proved usable it normally no longer consumes a permit, so independent language
 * inference remains parallel. A cheap liveness probe re-arms admission if Android reclaimed a
 * worker, avoiding both unbounded surprise reloads and needless serialization of healthy work.
 */
internal class NativeFirstUseGate(
    maxParallelInitializations: Int,
    /**
     * A worker that was once ready but was reclaimed by Android is a reload, not first-time
     * preparation. Keep reload fan-out independently bounded without slowing the intentional
     * parallel preparation of a newly selected five-language broadcast.
     */
    maxParallelReloads: Int = maxParallelInitializations,
    /**
     * Reserved for providers that cannot expose a reliable liveness signal. Current Gemma, ML Kit
     * and Moonshine workers use [isInitializationCurrent], so production gates leave this false.
     */
    private val guardEveryOperation: Boolean = false,
    /**
     * Runs under this gate before a possible native operation. The returned reservation stays
     * held until initialization is proved (or the attempt ends). Reloads use the same callback
     * after [isInitializationCurrent] detects a reclaimed worker.
     */
    private val beforeFirstUse: suspend (
        key: String,
        previouslyInitialized: Boolean,
    ) -> NativeColdLoadTicket? = { _, _ -> null },
    /**
     * Optional cheap Binder/process probe. A null probe means that only this gate's own completed
     * initialization set is authoritative; it must never be treated as proof that a cold worker
     * was initialized by a different session.
     */
    private val isInitializationCurrent: ((key: String) -> Boolean)? = null,
) {
    private val initializationPermits = Semaphore(maxParallelInitializations)
    private val reloadPermits = Semaphore(maxParallelReloads)
    /**
     * The global lanes bound different engines, while this lane prevents two first requests for
     * the same Binder worker from both observing an uninitialized key and cold-loading duplicate
     * native state. It is released with the global lane as soon as initialization is proved, so a
     * long streaming sentence does not serialize later healthy inference.
     */
    private val keyInitializationPermits = ConcurrentHashMap<String, Semaphore>()
    private val initializedKeys = ConcurrentHashMap.newKeySet<String>()
    private val previouslyInitializedKeys = ConcurrentHashMap.newKeySet<String>()

    init {
        require(maxParallelInitializations > 0)
        require(maxParallelReloads > 0)
    }

    /** Re-arms admission when an initialized native worker must be recreated after failure. */
    fun invalidate(key: String) {
        require(key.isNotBlank())
        initializedKeys.remove(key)
    }

    suspend fun <T> run(key: String, operation: suspend () -> T): T {
        return runUntilInitialized(key) { confirmInitialized ->
            operation().also { confirmInitialized() }
        }
    }

    /**
     * Runs an explicit warm-up only when the provider is still cold after process-wide admission.
     * A different session may have initialized the same Binder worker while this gate waited.
     */
    suspend fun initialize(key: String, operation: suspend () -> Unit) {
        runUntilInitializedInternal(
            key = key,
            resultWhenAlreadyInitialized = { Unit },
        ) { confirmInitialized ->
            operation()
            confirmInitialized()
        }
    }

    /**
     * Holds one admission permit until [operation] proves its native engine is initialized.
     * Streaming engines can confirm on their first structurally valid PCM frame instead of
     * serializing the whole first sentence. Audibility remains the separate product watchdog's
     * responsibility; even a silent, non-empty PCM frame proves that native initialization and
     * the Binder stream path completed. If no frame arrives, the key stays uninitialized.
     */
    suspend fun <T> runUntilInitialized(
        key: String,
        operation: suspend (confirmInitialized: () -> Unit) -> T,
    ): T = runUntilInitializedInternal(
        key = key,
        resultWhenAlreadyInitialized = null,
        operation = operation,
    )

    private suspend fun <T> runUntilInitializedInternal(
        key: String,
        resultWhenAlreadyInitialized: (() -> T)?,
        operation: suspend (confirmInitialized: () -> Unit) -> T,
    ): T {
        require(key.isNotBlank())
        if (!guardEveryOperation && initializationIsCurrent(key)) {
            return resultWhenAlreadyInitialized?.invoke() ?: operation {}
        }

        val keyPermit = keyInitializationPermits.computeIfAbsent(key) { Semaphore(1) }
        keyPermit.acquire()
        val ownsKeyPermit = java.util.concurrent.atomic.AtomicBoolean(true)
        fun releaseKeyPermit() {
            if (ownsKeyPermit.compareAndSet(true, false)) keyPermit.release()
        }
        if (!guardEveryOperation && initializationIsCurrent(key)) {
            releaseKeyPermit()
            return resultWhenAlreadyInitialized?.invoke() ?: operation {}
        }

        val isReload = !guardEveryOperation && key in previouslyInitializedKeys
        val permits = if (isReload) reloadPermits else initializationPermits
        try {
            permits.acquire()
        } catch (error: Throwable) {
            releaseKeyPermit()
            throw error
        }
        val ownsPermit = java.util.concurrent.atomic.AtomicBoolean(true)
        var externalReservation: NativeColdLoadTicket? = null
        fun releaseAdmission() {
            if (ownsPermit.compareAndSet(true, false)) {
                runCatching { externalReservation?.close() }
                permits.release()
                releaseKeyPermit()
            }
        }
        fun confirmInitialized() {
            initializedKeys += key
            previouslyInitializedKeys += key
            releaseAdmission()
        }
        try {
            if (!guardEveryOperation && initializationIsCurrent(key)) {
                releaseAdmission()
                return resultWhenAlreadyInitialized?.invoke() ?: operation {}
            }
            externalReservation = beforeFirstUse(key, key in previouslyInitializedKeys)
            // Another session may have warmed the same process-wide worker while this gate was
            // waiting for the shared coordinator. Recheck the provider, not this gate's local set.
            if (
                !guardEveryOperation &&
                isInitializationCurrent != null &&
                providerReportsInitializationCurrent(key)
            ) {
                initializedKeys += key
                previouslyInitializedKeys += key
                releaseAdmission()
                return resultWhenAlreadyInitialized?.invoke() ?: operation {}
            }
            val ticket = externalReservation
            return if (ticket == null) {
                operation(::confirmInitialized)
            } else {
                withContext(NativeColdLoadTicketContext(ticket)) {
                    operation(::confirmInitialized)
                }
            }
        } finally {
            releaseAdmission()
            releaseKeyPermit()
        }
    }

    private fun initializationIsCurrent(key: String): Boolean {
        if (key !in initializedKeys) return false
        if (isInitializationCurrent == null) return true
        val current = providerReportsInitializationCurrent(key)
        if (!current) initializedKeys.remove(key)
        return current
    }

    private fun providerReportsInitializationCurrent(key: String): Boolean =
        runCatching { isInitializationCurrent?.invoke(key) ?: false }.getOrDefault(false)
}

internal fun TranslationEngineProvider.withNativeFirstUseGate(
    gate: NativeFirstUseGate,
    keyPrefix: String = "translation",
): TranslationEngineProvider = TranslationEngineProvider { targetLanguageTag ->
    require(keyPrefix.isNotBlank())
    val delegate = engineFor(targetLanguageTag)
    object : ContextualTextTranslationEngine {
        override suspend fun translateWithContext(
            text: String,
            contextBefore: String?,
            sourceLanguageTag: String,
            targetLanguageTag: String,
        ): String = gate.run("$keyPrefix:$targetLanguageTag") {
            if (delegate is ContextualTextTranslationEngine) {
                delegate.translateWithContext(
                    text = text,
                    contextBefore = contextBefore,
                    sourceLanguageTag = sourceLanguageTag,
                    targetLanguageTag = targetLanguageTag,
                )
            } else {
                delegate.translate(text, sourceLanguageTag, targetLanguageTag)
            }
        }
    }
}

internal fun SpeechSynthesisEngineProvider.withNativeFirstUseGate(
    gate: NativeFirstUseGate,
    keyPrefix: String = "speech",
): SpeechSynthesisEngineProvider = SpeechSynthesisEngineProvider { targetLanguageTag ->
    require(keyPrefix.isNotBlank())
    val delegate: SpeechSynthesisEngine = engineFor(targetLanguageTag)
    if (delegate is ExecutionAwareSpeechSynthesisEngine) {
        object : ExecutionAwareSpeechSynthesisEngine {
            override val maximumExecutionStartWaitMillis: Long
                get() = delegate.maximumExecutionStartWaitMillis

            override fun maximumExecutionStartWaitCount(text: String, languageTag: String): Int =
                delegate.maximumExecutionStartWaitCount(text, languageTag) + 1

            override fun synthesize(
                text: String,
                languageTag: String,
                onExecutionStarted: () -> Unit,
            ): Flow<PcmAudioFrame> = gatedSpeechSynthesis(gate, "$keyPrefix:$languageTag") {
                delegate.synthesize(text, languageTag, onExecutionStarted)
            }

            override fun synthesize(
                text: String,
                languageTag: String,
                onExecutionWaitStarted: () -> Unit,
                onExecutionStarted: () -> Unit,
            ): Flow<PcmAudioFrame> = gatedSpeechSynthesis(
                gate = gate,
                key = "$keyPrefix:$languageTag",
                onGateWaitStarted = onExecutionWaitStarted,
                onGateStarted = onExecutionStarted,
            ) {
                delegate.synthesize(text, languageTag, onExecutionWaitStarted, onExecutionStarted)
            }
        }
    } else {
        object : SpeechSynthesisEngine {
            override fun synthesize(text: String, languageTag: String): Flow<PcmAudioFrame> =
                gatedSpeechSynthesis(gate, "$keyPrefix:$languageTag") {
                    delegate.synthesize(text, languageTag)
                }
        }
    }
}

private fun gatedSpeechSynthesis(
    gate: NativeFirstUseGate,
    key: String,
    onGateWaitStarted: () -> Unit = {},
    onGateStarted: () -> Unit = {},
    synthesize: () -> Flow<PcmAudioFrame>,
): Flow<PcmAudioFrame> = channelFlow {
    onGateWaitStarted()
    gate.runUntilInitialized(key) { confirmInitialized ->
        onGateStarted()
        var receivedPcm = false
        synthesize().collect { frame ->
            if (!receivedPcm) {
                receivedPcm = true
                confirmInitialized()
            }
            send(frame)
        }
    }
}.buffer(Channel.RENDEZVOUS)
