package app.guidecast.transmitter

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import app.guidecast.core.stream.PcmAudioFrame
import app.guidecast.core.stream.MAX_SIMULTANEOUS_TRANSLATED_CHANNELS
import app.guidecast.core.translation.ExecutionAwareSpeechSynthesisEngine
import app.guidecast.core.translation.SpeechSynthesisEngine
import app.guidecast.core.translation.SpeechSynthesisEngineProvider
import app.guidecast.core.translation.currentNativeColdLoadTicket
import app.guidecast.provider.android.tts.AndroidOfflineSpeechSynthesisProvider
import app.guidecast.provider.moonshine.tts.MoonshineSpeechSynthesisProvider
import app.guidecast.provider.moonshine.tts.MoonshineTtsReadiness
import app.guidecast.provider.moonshine.tts.MoonshineTtsStatus
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

class GalaxySpeechPreparationReport internal constructor(
    val moonshineLanguageTags: Set<String>,
    val androidFallbackLanguageTags: Set<String>,
    val fallbackReasons: Map<String, String>,
    val unavailableLanguageReasons: Map<String, String> = emptyMap(),
    val compatibilityNotice: String? = null,
) {
    val warning: String?
        get() = listOfNotNull(
            compatibilityNotice,
            androidFallbackLanguageTags.toFallbackWarning(fallbackReasons),
            unavailableLanguageReasons.toUnavailableVoiceWarning(),
        ).joinToString(" · ").ifEmpty { null }
}

internal data class SpeechSynthesisRecoveryTiming(
    val primaryFirstFrameTimeoutMillis: Long,
    val continuationPrimaryFirstFrameTimeoutMillis: Long,
    val fallbackFirstFrameTimeoutMillis: Long,
    val pipelineFirstAudioTimeoutMillis: Long,
    val pipelineFrameIdleTimeoutMillis: Long,
    val pipelineTotalTimeoutMillis: Long,
    val compatibilityNotice: String?,
)

@Suppress("UNUSED_PARAMETER")
internal fun speechSynthesisRecoveryTiming(
    sdkInt: Int,
    totalMemoryBytes: Long,
    isLowRamDevice: Boolean,
): SpeechSynthesisRecoveryTiming {
    require(totalMemoryBytes > 0L)
    // S21+ product policy: capacity recommendations may change, audio deadlines do not relax.
    // These are failure-recovery ceilings, NOT proof of the measured 2-second latency gate.
    return SpeechSynthesisRecoveryTiming(
            primaryFirstFrameTimeoutMillis = 2_000L,
            continuationPrimaryFirstFrameTimeoutMillis = 2_000L,
            fallbackFirstFrameTimeoutMillis = 1_500L,
            pipelineFirstAudioTimeoutMillis = 4_500L,
            pipelineFrameIdleTimeoutMillis = 4_500L,
            pipelineTotalTimeoutMillis = 15_000L,
            compatibilityNotice = null,
    )
}

/**
 * Keeps the high-quality Gemma Translator/Moonshine voice as the normal output, while allowing a
 * Galaxy offline system voice to finish the sentence if the private native worker is reclaimed or
 * crashes. A voice failure is therefore isolated from the microphone, UI and web server.
 */
class GalaxySpeechSynthesisProvider(
    context: Context,
) : SpeechSynthesisEngineProvider, Closeable {
    private val preferences = context.applicationContext.getSharedPreferences("speech_voice_preferences", Context.MODE_PRIVATE)
    private val mutableVoicePreferences = MutableStateFlow(
        preferences.all.mapNotNull { (language, value) ->
            SpeechVoicePreference.entries.firstOrNull { it.name == value }?.let { language to it }
        }.toMap(),
    )
    val voicePreferences: StateFlow<Map<String, SpeechVoicePreference>> = mutableVoicePreferences.asStateFlow()
    fun voicePreference(languageTag: String): SpeechVoicePreference =
        voicePreferences.value[languageTag] ?: SpeechVoicePreference.AUTO

    /** UI only calls this while settings/broadcast input are idle; never changes a live sentence. */
    fun setVoicePreference(languageTag: String, preference: SpeechVoicePreference) {
        require(Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*").matches(languageTag))
        preferences.edit().putString(languageTag, preference.name).apply()
        mutableVoicePreferences.update { it + (languageTag to preference) }
        engines.remove(languageTag)
        mutableFallbackLanguageTags.update { it - languageTag }
        mutableUnavailableLanguageReasons.update { it + (languageTag to "음성 선택 변경 · 준비 버튼으로 확인하세요.") }
    }
    private val recoveryTiming = context.speechSynthesisRecoveryTiming()
    private val moonshine = MoonshineSpeechSynthesisProvider(context)
    private val android = AndroidOfflineSpeechSynthesisProvider(
        context = context,
        outputSampleRateHz = MoonshineSpeechSynthesisProvider.OUTPUT_SAMPLE_RATE_HZ,
        preferredEnginePackage = { voicePreference(it).enginePackage },
    )
    private val engines = ConcurrentHashMap<String, SpeechSynthesisEngine>()
    private val mutableFallbackLanguageTags = MutableStateFlow<Set<String>>(emptySet())
    private val mutableUnavailableLanguageReasons =
        MutableStateFlow<Map<String, String>>(emptyMap())
    /** Prevent duplicate work per voice without turning five languages into one failure barrier. */
    private val preparationMutexes = ConcurrentHashMap<String, Mutex>()
    private val languageReconciliationMutex = Mutex()
    private val retainedLanguageOwners = RetainedSpeechSynthesisLanguageOwners()
    private val workerFailures = SpeechWorkerFailureLatch { language ->
        recentSpeechWorkerExit(context.applicationContext, language)
    }

    /** Moonshine state only. FAILED must never be rewritten to READY because Android is usable. */
    val statuses: StateFlow<List<MoonshineTtsStatus>> = moonshine.statuses
    val fallbackLanguageTags: StateFlow<Set<String>> =
        mutableFallbackLanguageTags.asStateFlow()
    /** Persistent per-language capability evidence; unlike a one-shot report it survives UI refreshes. */
    val unavailableLanguageReasons: StateFlow<Map<String, String>> =
        mutableUnavailableLanguageReasons.asStateFlow()

    suspend fun prepare(languageTags: Collection<String>): GalaxySpeechPreparationReport =
        prepareInternal(
            languageTags = languageTags,
            warmMoonshineWithNativeAdmission = ::runMoonshineWarmupDirectly,
            ensurePreparationCurrent = {},
        )

    suspend fun prepare(
        languageTags: Collection<String>,
        warmMoonshineWithNativeAdmission: suspend (
            languageTag: String,
            warm: suspend () -> Unit,
        ) -> Unit,
    ): GalaxySpeechPreparationReport = prepareInternal(
        languageTags = languageTags,
        warmMoonshineWithNativeAdmission = warmMoonshineWithNativeAdmission,
        ensurePreparationCurrent = {},
    )

    /** Claims a settings generation so broadcast takeover can invalidate slow preparation. */
    suspend fun prepareForSettings(
        languageTags: Collection<String>,
        warmMoonshineWithNativeAdmission: suspend (
            languageTag: String,
            warm: suspend () -> Unit,
        ) -> Unit,
        isAppPreparationCurrent: () -> Boolean,
    ): GalaxySpeechPreparationReport {
        val selected = languageTags.toSet()
        require(selected.isNotEmpty() && selected.size <= MAX_BROADCAST_LANGUAGES)
        if (!isAppPreparationCurrent()) {
            throw CancellationException("Broadcast activation replaced settings TTS preparation")
        }
        // Only the operator's explicit preparation retries a voice that lost its native worker.
        // Broadcast activation and later sentences keep the healthy fallback instead.
        workerFailures.reset(selected)
        val claim = reconcileRetainedLanguages(
            owner = SpeechSynthesisLanguageOwner.SETTINGS,
            languageTags = selected,
            ensureOwnerCurrent = {
                if (!isAppPreparationCurrent()) {
                    throw CancellationException(
                        "Broadcast activation replaced settings TTS preparation",
                    )
                }
            },
        )
        check(claim.retained.accepted) {
            claim.failures.values.firstOrNull()
                ?: "Settings TTS generation was not accepted"
        }
        val generation = claim.retained.settingsGeneration
        val ensureCurrent = {
            if (!isAppPreparationCurrent() ||
                !retainedLanguageOwners.isSettingsGenerationCurrent(generation)
            ) {
                throw CancellationException(
                    "Settings TTS preparation was replaced by broadcast activation",
                )
            }
        }
        return prepareInternal(
            languageTags = selected,
            warmMoonshineWithNativeAdmission = warmMoonshineWithNativeAdmission,
            ensurePreparationCurrent = ensureCurrent,
        )
    }

    private suspend fun prepareInternal(
        languageTags: Collection<String>,
        warmMoonshineWithNativeAdmission: suspend (
            languageTag: String,
            warm: suspend () -> Unit,
        ) -> Unit,
        ensurePreparationCurrent: () -> Unit,
    ): GalaxySpeechPreparationReport {
        require(languageTags.isNotEmpty() && languageTags.size <= MAX_BROADCAST_LANGUAGES) {
            "Prepare one to $MAX_BROADCAST_LANGUAGES TTS languages"
        }
        check(currentNativeColdLoadTicket() == null) {
            "Galaxy TTS preparation must start outside native cold-load admission"
        }
        val moonshineStatuses = statuses.value.associateBy(MoonshineTtsStatus::languageTag)
        val moonshineLanguages = moonshineStatuses.keys
        val selectedLanguages = languageTags.distinct()
        ensurePreparationCurrent()
        mutableUnavailableLanguageReasons.update { current ->
            current - selectedLanguages.toSet()
        }
        val outcomes = prepareSpeechSynthesisOutcomesIndependently(
            languageTags = selectedLanguages,
            timeoutMillis = PER_LANGUAGE_PREPARATION_TIMEOUT_MILLIS,
            onOutcome = { outcome ->
                languageReconciliationMutex.withLock {
                    ensurePreparationCurrent()
                    recordPreparationOutcome(outcome)
                }
            },
        ) { languageTag ->
            val boundary = preparationMutexes.getOrPut(languageTag, ::Mutex).withLock {
                val preference = voicePreference(languageTag)
                prepareSpeechSynthesisLanguageWithNativeWarmupBoundary(
                    languageTag = languageTag,
                    prepareMoonshineAssets = { target ->
                        workerFailures.check(target)
                        if (target !in moonshineLanguages) {
                            throw MoonshineVoiceUnavailableException(target)
                        }
                        if (preference != SpeechVoicePreference.MOONSHINE && !moonshine.isReady(target)) {
                            throw InstalledOfflineVoiceSelected()
                        }
                        moonshine.prepareAssets(listOf(target))
                    },
                    warmMoonshine = { target -> moonshine.warm(listOf(target)) },
                    warmMoonshineWithNativeAdmission = warmMoonshineWithNativeAdmission,
                    prepareAndroidOffline = { target -> android.prepare(listOf(target)) },
                    moonshinePreparationTimeoutMillis = if (
                        moonshineStatuses[languageTag]?.readiness == MoonshineTtsReadiness.READY
                    ) {
                        CACHED_MOONSHINE_PREPARATION_TIMEOUT_MILLIS
                    } else {
                        DOWNLOAD_MOONSHINE_PREPARATION_TIMEOUT_MILLIS
                    },
                    androidStandbyPreparationTimeoutMillis =
                        ANDROID_STANDBY_PREPARATION_TIMEOUT_MILLIS,
                    ensurePreparationCurrent = ensurePreparationCurrent,
                    preferAndroidOffline = preferInstalledAndroidVoice(
                        preference, moonshine.isReady(languageTag),
                    ),
                )
            }
            boundary.androidStandbyError?.let { standbyError ->
                Log.w(
                    LOG_TAG,
                    "Galaxy offline standby voice is unavailable: $languageTag",
                    standbyError,
                )
            }
            boundary.preparation.moonshineError?.let { workerFailures.record(languageTag, it) }
            boundary.preparation
        }

        return languageReconciliationMutex.withLock {
            ensurePreparationCurrent()
            outcomes.toGalaxySpeechPreparationReport(
                compatibilityNotice = recoveryTiming.compatibilityNotice,
            )
        }
    }

    /** Publish each channel immediately; a stalled sibling cannot hide a healthy fallback. */
    private fun recordPreparationOutcome(outcome: SpeechPreparationOutcome) {
        val languageTag = outcome.languageTag
        val result = outcome.result
        if (result != null) {
            when (result.backend) {
                SpeechSynthesisPreparationBackend.MOONSHINE -> {
                    mutableFallbackLanguageTags.update { it - languageTag }
                    mutableUnavailableLanguageReasons.update { it - languageTag }
                }
                SpeechSynthesisPreparationBackend.ANDROID_OFFLINE -> {
                    val primaryError = requireNotNull(result.moonshineError)
                    if (primaryError !is InstalledOfflineVoiceSelected) {
                        RuntimeDiagnosticLog.failure("tts_prepare_primary_$languageTag", primaryError)
                    }
                    mutableFallbackLanguageTags.update { it + languageTag }
                    mutableUnavailableLanguageReasons.update { it - languageTag }
                    Log.w(LOG_TAG, "Moonshine prepare failed; Galaxy offline voice is ready: $languageTag", primaryError)
                }
            }
        } else {
            val error = requireNotNull(outcome.error)
            RuntimeDiagnosticLog.failure("tts_prepare_$languageTag", error)
            workerFailures.record(languageTag, error)
            mutableFallbackLanguageTags.update { it - languageTag }
            mutableUnavailableLanguageReasons.update { it + (languageTag to error.conciseMessage()) }
            Log.e(LOG_TAG, "Speech preparation failed for isolated channel: $languageTag", error)
        }
    }

    /** Keeps only engines selected by the replacement broadcast, without truncating active PCM. */
    suspend fun reconcileLanguages(
        languageTags: Set<String>,
        broadcastGeneration: Long,
    ): Map<String, String> {
        require(languageTags.isNotEmpty() && languageTags.size <= MAX_BROADCAST_LANGUAGES)
        return reconcileRetainedLanguages(
            owner = SpeechSynthesisLanguageOwner.BROADCAST,
            languageTags = languageTags,
            broadcastGeneration = broadcastGeneration,
        ).failures
    }

    /**
     * Releases only the exact stopped session's logical ownership. Native/Android engines are not
     * force-closed here: the pipeline is closed first and idle cleanup or the next reconciliation
     * performs physical retirement, so late teardown cannot cut a replacement session's PCM.
     */
    fun releaseBroadcastLanguages(broadcastGeneration: Long): Boolean {
        val released = retainedLanguageOwners.releaseBroadcast(broadcastGeneration)
        if (released) {
            android.releaseBroadcastLanguages()
        }
        return released
    }

    private suspend fun reconcileRetainedLanguages(
        owner: SpeechSynthesisLanguageOwner,
        languageTags: Set<String>,
        broadcastGeneration: Long? = null,
        ensureOwnerCurrent: () -> Unit = {},
    ): SpeechSynthesisLanguageReconciliation = languageReconciliationMutex.withLock {
        ensureOwnerCurrent()
        val retained = when (owner) {
            SpeechSynthesisLanguageOwner.BROADCAST ->
                retainedLanguageOwners.activateBroadcast(
                    languageTags = languageTags,
                    generation = requireNotNull(broadcastGeneration),
                )

            SpeechSynthesisLanguageOwner.SETTINGS ->
                retainedLanguageOwners.updateSettings(languageTags)
        }
        if (!retained.accepted) {
            return@withLock SpeechSynthesisLanguageReconciliation(
                retained = retained,
                failures = mapOf(
                    SETTINGS_RETENTION_FAILURE_KEY to
                        "방송 중에는 설정 음성 언어를 교체하지 않습니다.",
                ),
            )
        }
        val moonshineLanguages = statuses.value.mapTo(
            linkedSetOf(),
            MoonshineTtsStatus::languageTag,
        )
        val retainedMoonshineLanguages = retained.all.filterTo(linkedSetOf()) {
            it in moonshineLanguages
        }
        // Empty is meaningful: if neither settings nor the active broadcast owns a Moonshine
        // voice, retire every native worker left by previous preparation.
        val failures = moonshine.reconcileLanguages(retainedMoonshineLanguages)
        ensureOwnerCurrent()
        when (owner) {
            SpeechSynthesisLanguageOwner.BROADCAST -> {
                val applied = retainedLanguageOwners.runIfBroadcastGenerationCurrent(
                    retained.broadcastGeneration,
                ) {
                    android.activateBroadcastLanguages(retained.broadcast)
                }
                if (!applied) {
                    throw CancellationException("Broadcast TTS reconciliation was superseded")
                }
            }

            SpeechSynthesisLanguageOwner.SETTINGS ->
                android.retainSettingsLanguages(retained.settings)
        }
        engines.keys.removeAll { it !in retained.all }
        preparationMutexes.keys.removeAll { it !in retained.all }
        mutableFallbackLanguageTags.update { it intersect retained.all }
        mutableUnavailableLanguageReasons.update { reasons ->
            reasons.filterKeys { it in retained.all }
        }
        SpeechSynthesisLanguageReconciliation(retained, failures)
    }

    val pipelineFirstAudioTimeoutMillis: Long
        get() = recoveryTiming.pipelineFirstAudioTimeoutMillis

    val pipelineFrameIdleTimeoutMillis: Long
        get() = recoveryTiming.pipelineFrameIdleTimeoutMillis

    val pipelineTotalTimeoutMillis: Long
        get() = recoveryTiming.pipelineTotalTimeoutMillis

    /** Preserves the original meaning: true only when the Moonshine voice itself is ready. */
    fun isReady(languageTag: String): Boolean = moonshine.isReady(languageTag)

    fun hasActiveMoonshineWorker(languageTag: String): Boolean =
        moonshine.hasActiveWorker(languageTag)

    fun isFallbackReady(languageTag: String): Boolean =
        languageTag in fallbackLanguageTags.value

    fun unavailableReason(languageTag: String): String? =
        unavailableLanguageReasons.value[languageTag]

    fun fallbackWarning(languageTags: Collection<String>): String? =
        listOfNotNull(
            recoveryTiming.compatibilityNotice,
            languageTags.filterTo(linkedSetOf()) { isFallbackReady(it) }.toFallbackWarning(),
            unavailableLanguageReasons.value
                .filterKeys { it in languageTags }
                .toUnavailableVoiceWarning(),
        ).joinToString(" · ").ifEmpty { null }

    override fun engineFor(targetLanguageTag: String): SpeechSynthesisEngine =
        engines.getOrPut(targetLanguageTag) {
            val fallback = android.engineFor(targetLanguageTag)
            val queuedFallback = checkNotNull(fallback as? ExecutionAwareSpeechSynthesisEngine) {
                "Android offline TTS must expose bounded execution admission"
            }
            if (statuses.value.none { it.languageTag == targetLanguageTag }) {
                // Moonshine does not ship every Gemma Translator language. These channels use an
                // installed offline Galaxy voice without touching the native Moonshine worker;
                // their failure remains isolated by TranslationBroadcastPipeline. Do not label
                // the voice READY merely because an engine object can be constructed: prepare()
                // is the point that proves an installed offline voice exists for this language.
                return@getOrPut RoutedExecutionAwareSpeechSynthesisEngine(queuedFallback) {
                        text, languageTag, onExecutionWaitStarted, onExecutionStarted ->
                    flow {
                        splitTranslatedTextForSpeech(text).forEach { clause ->
                            emitAll(synthesizeSpeechWithStickyFallback(
                                primary = fallback, fallback = fallback, text = clause,
                                languageTag = languageTag, usePreparedFallback = { true },
                                onFallbackAudibleFrame = {
                                    mutableFallbackLanguageTags.update { it + languageTag }
                                    mutableUnavailableLanguageReasons.update { it - languageTag }
                                },
                                onFallbackFailure = { error ->
                                    mutableFallbackLanguageTags.update { it - languageTag }
                                    mutableUnavailableLanguageReasons.update { it + (languageTag to error.conciseMessage()) }
                                },
                                onExecutionWaitStarted = onExecutionWaitStarted,
                                onExecutionStarted = onExecutionStarted,
                                fallbackFirstFrameTimeoutMillis = recoveryTiming.fallbackFirstFrameTimeoutMillis,
                            ))
                        }
                    }
                }
            }
            val nativePrimary = moonshine.engineFor(targetLanguageTag)
            val primary = object : SpeechSynthesisEngine {
                override fun synthesize(text: String, languageTag: String): Flow<PcmAudioFrame> = flow {
                    workerFailures.check(languageTag)
                    emitAll(nativePrimary.synthesize(text, languageTag))
                    // First PCM is not recovery: a worker can crash halfway through the sentence.
                    workerFailures.completed(languageTag)
                }
            }
            RoutedExecutionAwareSpeechSynthesisEngine(queuedFallback) {
                    text, languageTag, onExecutionWaitStarted, onExecutionStarted ->
                flow {
                        splitTranslatedTextForSpeech(text).forEachIndexed { clauseIndex, clause ->
                            emitAll(
                                synthesizeSpeechWithStickyFallback(
                                    primary = primary,
                                    fallback = fallback,
                                    text = clause,
                                    languageTag = languageTag,
                                    usePreparedFallback = {
                                        languageTag in mutableFallbackLanguageTags.value
                                    },
                                    onPrimaryAudibleFrame = {
                                        mutableFallbackLanguageTags.update { languages ->
                                            languages - languageTag
                                        }
                                        mutableUnavailableLanguageReasons.update {
                                            it - languageTag
                                        }
                                    },
                                    onPrimaryFailure = { primaryError ->
                                        RuntimeDiagnosticLog.failure("tts_primary_$languageTag", primaryError)
                                        workerFailures.record(languageTag, primaryError)
                                        Log.e(
                                            LOG_TAG,
                                            "Moonshine voice failed; using installed Galaxy " +
                                                "offline voice: $languageTag",
                                            primaryError,
                                        )
                                    },
                                    onFallbackAudibleFrame = {
                                        mutableFallbackLanguageTags.update { languages ->
                                            languages + languageTag
                                        }
                                        mutableUnavailableLanguageReasons.update {
                                            it - languageTag
                                        }
                                    },
                                    onFallbackFailure = { error ->
                                        RuntimeDiagnosticLog.failure("tts_fallback_$languageTag", error)
                                        mutableFallbackLanguageTags.update { languages ->
                                            languages - languageTag
                                        }
                                        mutableUnavailableLanguageReasons.update {
                                            it + (languageTag to error.conciseMessage())
                                        }
                                    },
                                    onExecutionWaitStarted = onExecutionWaitStarted,
                                    onExecutionStarted = onExecutionStarted,
                                    primaryFirstFrameTimeoutMillis =
                                        recoveryTiming.primaryFirstFrameTimeoutForClause(
                                            clauseIndex,
                                        ),
                                    fallbackFirstFrameTimeoutMillis =
                                        recoveryTiming.fallbackFirstFrameTimeoutMillis,
                                ),
                            )
                        }
                    }
            }
        }

    suspend fun releaseNativeResources() = languageReconciliationMutex.withLock {
        retainedLanguageOwners.clear()
        moonshine.releaseNativeResources()
        android.close()
        engines.clear()
        preparationMutexes.clear()
        mutableFallbackLanguageTags.value = emptySet()
        mutableUnavailableLanguageReasons.value = emptyMap()
    }

    override fun close() {
        workerFailures.clear()
        retainedLanguageOwners.clear()
        engines.clear()
        preparationMutexes.clear()
        moonshine.close()
        android.close()
        mutableFallbackLanguageTags.value = emptySet()
        mutableUnavailableLanguageReasons.value = emptyMap()
    }

    private companion object {
        const val LOG_TAG = "GuideCastSpeech"
        const val SETTINGS_RETENTION_FAILURE_KEY = "settings"
        const val MAX_BROADCAST_LANGUAGES = MAX_SIMULTANEOUS_TRANSLATED_CHANNELS
        const val PER_LANGUAGE_PREPARATION_TIMEOUT_MILLIS = 10L * 60 * 1_000
        // A READY voice only needs Binder/native warm-up. A missing voice may still be performing
        // its explicitly requested first download, so leave it most of the outer ten-minute budget.
        const val CACHED_MOONSHINE_PREPARATION_TIMEOUT_MILLIS = 60_000L
        const val DOWNLOAD_MOONSHINE_PREPARATION_TIMEOUT_MILLIS = 8L * 60 * 1_000
        const val ANDROID_STANDBY_PREPARATION_TIMEOUT_MILLIS = 45_000L
    }
}

private class RoutedExecutionAwareSpeechSynthesisEngine(
    private val fallback: ExecutionAwareSpeechSynthesisEngine,
    private val synthesis: (
        text: String,
        languageTag: String,
        onExecutionWaitStarted: () -> Unit,
        onExecutionStarted: () -> Unit,
    ) -> Flow<PcmAudioFrame>,
) : ExecutionAwareSpeechSynthesisEngine {
    override val maximumExecutionStartWaitMillis: Long
        get() = fallback.maximumExecutionStartWaitMillis

    override fun maximumExecutionStartWaitCount(text: String, languageTag: String): Int =
        splitTranslatedTextForSpeech(text).size

    override fun synthesize(
        text: String,
        languageTag: String,
        onExecutionStarted: () -> Unit,
    ): Flow<PcmAudioFrame> = synthesis(text, languageTag, {}, onExecutionStarted)

    override fun synthesize(
        text: String,
        languageTag: String,
        onExecutionWaitStarted: () -> Unit,
        onExecutionStarted: () -> Unit,
    ): Flow<PcmAudioFrame> = synthesis(
        text,
        languageTag,
        onExecutionWaitStarted,
        onExecutionStarted,
    )
}

private suspend fun runMoonshineWarmupDirectly(
    languageTag: String,
    warm: suspend () -> Unit,
) {
    require(languageTag.isNotBlank())
    warm()
}

internal enum class SpeechSynthesisLanguageOwner {
    BROADCAST,
    SETTINGS,
}

internal data class RetainedSpeechSynthesisLanguages(
    val broadcast: Set<String>,
    val settings: Set<String>,
    val broadcastGeneration: Long,
    val settingsGeneration: Long,
    val accepted: Boolean,
) {
    val all: Set<String> = buildSet {
        addAll(broadcast)
        addAll(settings)
    }
}

internal data class SpeechSynthesisLanguageReconciliation(
    val retained: RetainedSpeechSynthesisLanguages,
    val failures: Map<String, String>,
)

/** Pure owner state; the provider serializes the resulting native reconciliation separately. */
internal class RetainedSpeechSynthesisLanguageOwners {
    private val lock = Any()
    private var broadcast = emptySet<String>()
    private var broadcastGeneration = 0L
    private var settings = emptySet<String>()
    private var settingsGeneration = 0L

    fun activateBroadcast(
        languageTags: Set<String>,
        generation: Long,
    ): RetainedSpeechSynthesisLanguages = synchronized(lock) {
        require(generation > 0L) { "Broadcast TTS generation must be positive" }
        if (generation < broadcastGeneration ||
            (generation == broadcastGeneration && broadcast.isEmpty())
        ) {
            return@synchronized snapshot(accepted = false)
        }
        settingsGeneration = nextGeneration(settingsGeneration)
        broadcast = languageTags.toSet()
        broadcastGeneration = generation
        // Broadcast activation takes ownership of the current selection. Keeping a separate old
        // settings selection could retain five more native workers on an 8 GB phone.
        settings = emptySet()
        snapshot(accepted = true)
    }

    fun updateSettings(
        languageTags: Set<String>,
    ): RetainedSpeechSynthesisLanguages = synchronized(lock) {
        settingsGeneration = nextGeneration(settingsGeneration)
        if (broadcast.isNotEmpty()) {
            // The settings UI normally blocks this path. The provider boundary also rejects a
            // stale async completion so it cannot re-register A5 after broadcast B5 took over.
            return@synchronized snapshot(accepted = false)
        }
        settings = languageTags.toSet()
        snapshot(accepted = true)
    }

    fun isSettingsGenerationCurrent(generation: Long): Boolean = synchronized(lock) {
        broadcast.isEmpty() && generation == settingsGeneration
    }

    fun isBroadcastGenerationCurrent(generation: Long): Boolean = synchronized(lock) {
        broadcast.isNotEmpty() && generation == broadcastGeneration
    }

    fun runIfBroadcastGenerationCurrent(generation: Long, mutation: () -> Unit): Boolean =
        synchronized(lock) {
            if (broadcast.isEmpty() || generation != broadcastGeneration) {
                return@synchronized false
            }
            mutation()
            true
        }

    /** A stale stop can never clear a newer broadcast's ownership. */
    fun releaseBroadcast(generation: Long): Boolean = synchronized(lock) {
        require(generation > 0L) { "Broadcast TTS generation must be positive" }
        if (generation < broadcastGeneration) return@synchronized false
        if (generation > broadcastGeneration) {
            // Teardown may win before slow preparation ever activates this provider. Record an
            // exact tombstone so that late same-generation reconciliation cannot resurrect it.
            broadcastGeneration = generation
            settingsGeneration = nextGeneration(settingsGeneration)
            settings = emptySet()
            broadcast = emptySet()
            return@synchronized true
        }
        if (broadcast.isEmpty()) return@synchronized false
        broadcast = emptySet()
        true
    }

    fun clear() = synchronized(lock) {
        settingsGeneration = nextGeneration(settingsGeneration)
        broadcast = emptySet()
        broadcastGeneration = 0L
        settings = emptySet()
    }

    private fun snapshot(accepted: Boolean) = RetainedSpeechSynthesisLanguages(
        broadcast = broadcast,
        settings = settings,
        broadcastGeneration = broadcastGeneration,
        settingsGeneration = settingsGeneration,
        accepted = accepted,
    )

    private fun nextGeneration(current: Long): Long {
        check(current < Long.MAX_VALUE) { "Settings TTS retention generation overflow" }
        return current + 1L
    }
}

internal data class SpeechSynthesisNativeWarmupBoundaryResult(
    val preparation: SpeechSynthesisLanguagePreparation,
    val androidStandbyError: Throwable?,
)

/**
 * Keeps first-install download/integrity work and Galaxy fallback preparation outside the
 * process-wide native cold-load ticket. Only [warmMoonshine] may execute inside the admission
 * callback, so an eight-minute download or 45-second Android standby cannot block Gemma/ML Kit or
 * another language's native mapping.
 */
internal suspend fun prepareSpeechSynthesisLanguageWithNativeWarmupBoundary(
    languageTag: String,
    prepareMoonshineAssets: suspend (String) -> Unit,
    warmMoonshine: suspend (String) -> Unit,
    warmMoonshineWithNativeAdmission: suspend (
        languageTag: String,
        warm: suspend () -> Unit,
    ) -> Unit,
    prepareAndroidOffline: suspend (String) -> Unit,
    moonshinePreparationTimeoutMillis: Long,
    androidStandbyPreparationTimeoutMillis: Long,
    ensurePreparationCurrent: () -> Unit = {},
    preferAndroidOffline: Boolean = false,
): SpeechSynthesisNativeWarmupBoundaryResult {
    require(languageTag.isNotBlank())
    ensurePreparationCurrent()
    check(currentNativeColdLoadTicket() == null) {
        "Speech asset/fallback preparation cannot inherit a native cold-load ticket"
    }
    if (preferAndroidOffline) {
        val androidError = prepareOptionalSpeechStandby(languageTag, androidStandbyPreparationTimeoutMillis) {
            ensurePreparationCurrent()
            prepareAndroidOffline(it)
            ensurePreparationCurrent()
        }
        if (androidError == null) {
            return SpeechSynthesisNativeWarmupBoundaryResult(
                SpeechSynthesisLanguagePreparation(
                    languageTag, SpeechSynthesisPreparationBackend.ANDROID_OFFLINE,
                    InstalledOfflineVoiceSelected(),
                ),
                androidStandbyError = null,
            )
        }
        // Cached Moonshine may still rescue an unavailable requested vendor. No implicit download.
    }
    val preparation = prepareSpeechSynthesisLanguages(
        languageTags = listOf(languageTag),
        prepareMoonshine = { target ->
            ensurePreparationCurrent()
            prepareMoonshineAssets(target)
            ensurePreparationCurrent()
            warmSpeechWithFreshAdmissionAfterWorkerDeath {
                warmMoonshineWithNativeAdmission(target) {
                    ensurePreparationCurrent()
                    warmMoonshine(target)
                    ensurePreparationCurrent()
                }
            }
            ensurePreparationCurrent()
        },
        prepareAndroidOffline = { target ->
            ensurePreparationCurrent()
            prepareAndroidOffline(target)
            ensurePreparationCurrent()
        },
        moonshinePreparationTimeoutMillis = moonshinePreparationTimeoutMillis,
    ).single()
    ensurePreparationCurrent()
    check(currentNativeColdLoadTicket() == null) {
        "Native cold-load admission leaked beyond Moonshine warm-up"
    }
    val standbyError = if (
        preparation.backend == SpeechSynthesisPreparationBackend.MOONSHINE
    ) {
        prepareOptionalSpeechStandby(
            languageTag = languageTag,
            timeoutMillis = androidStandbyPreparationTimeoutMillis,
            prepare = { target ->
                ensurePreparationCurrent()
                prepareAndroidOffline(target)
                ensurePreparationCurrent()
            },
        )
    } else {
        null
    }
    ensurePreparationCurrent()
    return SpeechSynthesisNativeWarmupBoundaryResult(
        preparation = preparation,
        androidStandbyError = standbyError,
    )
}

/** Retry warm-up only, after the admission scope has released its single-transfer ticket.
 * Never wrap live synthesis with this: replay after first PCM would duplicate speech.
 */
internal suspend fun warmSpeechWithFreshAdmissionAfterWorkerDeath(warmWithAdmission: suspend () -> Unit) {
    check(currentNativeColdLoadTicket() == null)
    try {
        warmWithAdmission()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        var cause: Throwable? = error
        var workerDied = false
        repeat(16) {
            if (cause is android.os.RemoteException) workerDied = true
            cause = cause?.cause
        }
        if (!workerDied) throw error
        check(currentNativeColdLoadTicket() == null)
        // Only one retry per explicit preparation. Native/model errors and timeouts don't loop.
        warmWithAdmission()
    }
}

internal data class SpeechPreparationOutcome(
    val languageTag: String,
    val result: SpeechSynthesisLanguagePreparation? = null,
    val error: Throwable? = null,
) {
    init {
        require((result == null) != (error == null))
    }
}

internal fun List<SpeechPreparationOutcome>.toGalaxySpeechPreparationReport(
    compatibilityNotice: String?,
): GalaxySpeechPreparationReport {
    val successful = mapNotNull(SpeechPreparationOutcome::result)
    return GalaxySpeechPreparationReport(
        moonshineLanguageTags = successful
            .filterTo(linkedSetOf()) {
                it.backend == SpeechSynthesisPreparationBackend.MOONSHINE
            }
            .mapTo(linkedSetOf(), SpeechSynthesisLanguagePreparation::languageTag),
        androidFallbackLanguageTags = successful
            .filterTo(linkedSetOf()) {
                it.backend == SpeechSynthesisPreparationBackend.ANDROID_OFFLINE
            }
            .mapTo(linkedSetOf(), SpeechSynthesisLanguagePreparation::languageTag),
        fallbackReasons = successful
            .filter { it.backend == SpeechSynthesisPreparationBackend.ANDROID_OFFLINE }
            .associateTo(linkedMapOf()) { result ->
                result.languageTag to requireNotNull(result.moonshineError).conciseMessage()
            },
        unavailableLanguageReasons = filter { it.error != null }
            .associateTo(linkedMapOf()) { outcome ->
                outcome.languageTag to requireNotNull(outcome.error).conciseMessage()
            },
        compatibilityNotice = compatibilityNotice,
    )
}

/**
 * Converts an expected dual-provider failure into evidence for only that language. `async` keeps
 * exception delivery deferred until `awaitAll`, so every child must contain its own ordinary
 * failure; `supervisorScope` alone does not stop one failed deferred from aborting the batch.
 * Session cancellation, including a provider-wrapped cancellation, always escapes the batch.
 */
internal suspend fun prepareSpeechSynthesisOutcomesIndependently(
    languageTags: Collection<String>,
    timeoutMillis: Long,
    onOutcome: suspend (SpeechPreparationOutcome) -> Unit = {},
    prepareLanguage: suspend (String) -> SpeechSynthesisLanguagePreparation,
): List<SpeechPreparationOutcome> {
    require(languageTags.isNotEmpty() && languageTags.size <= MAX_SIMULTANEOUS_TRANSLATED_CHANNELS) {
        "Prepare one to $MAX_SIMULTANEOUS_TRANSLATED_CHANNELS TTS languages"
    }
    require(timeoutMillis > 0L)
    return supervisorScope {
        languageTags.distinct().map { languageTag ->
            async {
                val outcome = try {
                    val result = withTimeoutOrNull(timeoutMillis) {
                        prepareLanguage(languageTag)
                    }
                    if (result == null) {
                        SpeechPreparationOutcome(
                            languageTag = languageTag,
                            error = IllegalStateException(
                                "통역 음성 준비 시간이 초과되었습니다: $languageTag",
                            ),
                        )
                    } else {
                        SpeechPreparationOutcome(languageTag = languageTag, result = result)
                    }
                } catch (error: Throwable) {
                    error.findCancellation()?.let { throw it }
                    SpeechPreparationOutcome(languageTag = languageTag, error = error)
                }
                onOutcome(outcome)
                outcome
            }
        }.awaitAll()
    }
}

private class MoonshineVoiceUnavailableException(languageTag: String) :
    IllegalStateException("Moonshine 오프라인 음성이 제공되지 않는 언어입니다: $languageTag")

private fun Context.speechSynthesisRecoveryTiming(): SpeechSynthesisRecoveryTiming {
    val activityManager = getSystemService(ActivityManager::class.java)
    val memoryInfo = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
    return speechSynthesisRecoveryTiming(
        sdkInt = Build.VERSION.SDK_INT,
        totalMemoryBytes = memoryInfo.totalMem,
        isLowRamDevice = activityManager.isLowRamDevice,
    )
}

internal fun SpeechSynthesisRecoveryTiming.primaryFirstFrameTimeoutForClause(
    clauseIndex: Int,
): Long {
    require(clauseIndex >= 0)
    return if (clauseIndex == 0) {
        primaryFirstFrameTimeoutMillis
    } else {
        continuationPrimaryFirstFrameTimeoutMillis
    }
}

private fun Set<String>.toFallbackWarning(
    fallbackReasons: Map<String, String> = emptyMap(),
): String? = takeIf { it.isNotEmpty() }?.let { languages ->
    val reasonSummary = languages.mapNotNull { languageTag ->
        fallbackReasons[languageTag]
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.take(MAX_VISIBLE_FALLBACK_REASON_CHARACTERS)
            ?.let { reason -> "$languageTag: $reason" }
    }.joinToString("; ")
    "설치된 Android 오프라인 음성 사용: " +
        languages.joinToString() +
        reasonSummary.takeIf(String::isNotEmpty)?.let { " · 원인: $it" }.orEmpty()
}

private fun Map<String, String>.toUnavailableVoiceWarning(): String? =
    takeIf { it.isNotEmpty() }?.entries?.joinToString(
        prefix = "통역 음성 준비 확인 필요 · 해당 언어만 자막을 계속합니다: ",
        separator = "; ",
    ) { (languageTag, reason) ->
        "$languageTag: ${reason.replace(Regex("\\s+"), " ").take(MAX_VISIBLE_FALLBACK_REASON_CHARACTERS)}"
    }

private const val MAX_VISIBLE_FALLBACK_REASON_CHARACTERS = 300
