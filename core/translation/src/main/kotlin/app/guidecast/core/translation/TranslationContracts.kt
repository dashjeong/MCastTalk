package app.guidecast.core.translation

import app.guidecast.core.stream.PcmAudioFrame
import java.text.Normalizer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

data class RecognizedUtterance(
    val sequence: Long,
    val text: String,
    val sourceLanguageTag: String,
    val isFinal: Boolean,
    val capturedAtElapsedRealtimeNanos: Long,
    /** Timestamp when this recognition result became available to translation. */
    val recognizedAtElapsedRealtimeNanos: Long = capturedAtElapsedRealtimeNanos,
    /** Prior committed Korean text for disambiguation; it must never be spoken again. */
    val contextBefore: String? = null,
    /** Removes an obsolete non-final preview from operator/listener transcripts. */
    val isRetracted: Boolean = false,
    /** Absolute first-audio safety line; null is allowed for provider/tests without a budget. */
    val firstAudioDeadlineElapsedRealtimeNanos: Long? = null,
) {
    init {
        require(sequence >= 0)
        require(text.isNotBlank() || isRetracted)
        require(text.length <= 2_000)
        require(LANGUAGE_TAG.matches(sourceLanguageTag))
        require(contextBefore == null || contextBefore.length <= 400)
        require(!isRetracted || !isFinal) { "A retraction cannot be a final utterance" }
        require(
            firstAudioDeadlineElapsedRealtimeNanos == null ||
                firstAudioDeadlineElapsedRealtimeNanos >= capturedAtElapsedRealtimeNanos
        )
    }
}

data class TranslationTarget(
    val channelId: String,
    val displayName: String,
    val languageTag: String,
    val speechSampleRateHz: Int,
)

/** Actual PCM observed while consuming one synthesized utterance. */
data class SynthesizedPcmStats(
    val frameCount: Long,
    val byteCount: Long,
    val sampleCount: Long,
    val nonZeroSampleCount: Long,
    val rms: Float,
    val peak: Float,
    /** Signed mean sample, normalized to [-1, 1]. Persistent magnitude suggests DC bias. */
    val dcOffset: Float = 0f,
    /** Samples at or above 99.8% full scale divided by all samples. */
    val clippingRatio: Float = 0f,
    /** Longest contiguous zero-valued run in samples across frame boundaries. */
    val longestZeroRunSamples: Long = 0L,
    /** Largest adjacent-sample jump, normalized to [0, 2], including frame boundaries. */
    val maximumBoundaryJump: Float = 0f,
    /** Sign changes between adjacent non-zero samples divided by comparable pairs. */
    val zeroCrossingRatio: Float = 0f,
) {
    init {
        require(frameCount >= 0 && byteCount >= 0 && sampleCount >= 0)
        require(nonZeroSampleCount in 0..sampleCount)
        require(rms in 0f..1f && peak in 0f..1f)
        require(dcOffset in -1f..1f)
        require(clippingRatio in 0f..1f)
        require(longestZeroRunSamples in 0..sampleCount)
        require(maximumBoundaryJump in 0f..2f)
        require(zeroCrossingRatio in 0f..1f)
    }

    fun isNonSilent(
        minimumPeak: Float = 0f,
        minimumRms: Float = 0f,
    ): Boolean {
        require(minimumPeak in 0f..1f)
        require(minimumRms in 0f..1f)
        val crossesAudibleBoundary = if (minimumRms == 0f && minimumPeak == 0f) {
            rms > 0f && peak > 0f
        } else {
            (minimumRms > 0f && rms > minimumRms) ||
                (minimumPeak > 0f && peak > minimumPeak)
        }
        return frameCount > 0 &&
            byteCount > 0 &&
            sampleCount > 0 &&
            nonZeroSampleCount > 0 &&
            crossesAudibleBoundary
    }

    fun qualityWarnings(sampleRateHz: Int): List<String> {
        require(sampleRateHz > 0)
        return buildList {
            if (clippingRatio >= 0.005f) add("클리핑 후보")
            if (kotlin.math.abs(dcOffset) >= 0.03f) add("DC 편이 후보")
            if (maximumBoundaryJump >= 1.25f) add("클릭·팝 후보")
            if (longestZeroRunSamples >= sampleRateHz.toLong()) add("1초 이상 무음 구간")
            if (sampleCount > 0L && zeroCrossingRatio >= 0.45f) add("고주파 잡음 후보")
        }
    }
}

/** Same audible boundary used by the local browser's live/quiet diagnostic. */
const val MIN_AUDIBLE_PCM_RMS = 0.002f
const val MIN_AUDIBLE_PCM_PEAK = 0.01f

data class SpeechRecognitionConfig(
    val sourceLanguageTag: String,
    val sampleRateHz: Int = 16_000,
    val channelCount: Int = 1,
    /** Request-local, user-confirmed recognition hints; providers may ignore unsupported hints. */
    val biasingPhrases: List<String> = emptyList(),
) {
    init {
        require(LANGUAGE_TAG.matches(sourceLanguageTag))
        require(sampleRateHz in 8_000..48_000)
        require(channelCount == 1)
        normalizeSpeechRecognitionBiasingPhrases(biasingPhrases)
    }
}

const val MAX_SPEECH_RECOGNITION_BIASING_PHRASES = 32
const val MAX_SPEECH_RECOGNITION_BIASING_PHRASE_CODE_POINTS = 80
const val MAX_SPEECH_RECOGNITION_BIASING_TOTAL_CODE_POINTS = 1_000

/**
 * Validates request-local recognition hints and returns an NFC-normalized snapshot.
 *
 * The contract rejects invalid or oversized input instead of silently truncating it. Callers may
 * retain the returned list as the immutable request snapshot passed to the next recognizer attempt.
 */
fun normalizeSpeechRecognitionBiasingPhrases(phrases: List<String>): List<String> {
    require(phrases.size <= MAX_SPEECH_RECOGNITION_BIASING_PHRASES) {
        "Too many speech recognition biasing phrases"
    }
    var totalCodePoints = 0
    return phrases.map { phrase ->
        require(!phrase.hasDisallowedBiasingCodePoint()) {
            "A speech recognition biasing phrase contains invalid or control text"
        }
        val normalized = Normalizer.normalize(phrase, Normalizer.Form.NFC)
        require(normalized.isNotBlank()) { "A speech recognition biasing phrase cannot be blank" }
        require(!normalized.hasDisallowedBiasingCodePoint()) {
            "A speech recognition biasing phrase contains invalid or control text"
        }
        val codePointCount = normalized.codePointCount(0, normalized.length)
        require(codePointCount <= MAX_SPEECH_RECOGNITION_BIASING_PHRASE_CODE_POINTS) {
            "A speech recognition biasing phrase is too long"
        }
        totalCodePoints += codePointCount
        require(totalCodePoints <= MAX_SPEECH_RECOGNITION_BIASING_TOTAL_CODE_POINTS) {
            "Speech recognition biasing phrases are too long in aggregate"
        }
        normalized
    }
}

private fun String.hasDisallowedBiasingCodePoint(): Boolean {
    var index = 0
    while (index < length) {
        val first = this[index]
        if (
            Character.isSurrogate(first) &&
            (!Character.isHighSurrogate(first) ||
                index + 1 >= length ||
                !Character.isLowSurrogate(this[index + 1]))
        ) {
            return true
        }
        val codePoint = codePointAt(index)
        if (
            Character.isISOControl(codePoint) ||
            codePoint in 0x202A..0x202E ||
            codePoint in 0x2066..0x2069
        ) {
            return true
        }
        index += Character.charCount(codePoint)
    }
    return false
}

interface SpeechRecognitionEngine {
    fun recognize(
        frames: Flow<PcmAudioFrame>,
        config: SpeechRecognitionConfig,
    ): Flow<RecognizedUtterance>
}

fun interface TextTranslationEngine {
    suspend fun translate(text: String, sourceLanguageTag: String, targetLanguageTag: String): String
}

/** Optional translator capability that consumes context without translating or returning it. */
interface ContextualTextTranslationEngine : TextTranslationEngine {
    suspend fun translateWithContext(
        text: String,
        contextBefore: String?,
        sourceLanguageTag: String,
        targetLanguageTag: String,
    ): String

    override suspend fun translate(
        text: String,
        sourceLanguageTag: String,
        targetLanguageTag: String,
    ): String = translateWithContext(
        text = text,
        contextBefore = null,
        sourceLanguageTag = sourceLanguageTag,
        targetLanguageTag = targetLanguageTag,
    )
}

/**
 * A translator that owns its queue and execution deadlines.
 *
 * Callers may use [maximumCallDurationMillis] as their outer watchdog budget without counting a
 * normal queue wait as an inference failure.
 */
interface BoundedQueuedTranslationEngine : ContextualTextTranslationEngine {
    val maximumCallDurationMillis: Long
}

fun interface TranslationEngineProvider {
    fun engineFor(targetLanguageTag: String): TextTranslationEngine
}

interface SpeechSynthesisEngine {
    fun synthesize(text: String, languageTag: String): Flow<PcmAudioFrame>
}

/**
 * A speech engine whose provider can distinguish bounded admission/queue wait from actual
 * synthesis start. First-audio watchdogs use [maximumExecutionStartWaitMillis] for the former and
 * begin their normal audible-PCM deadline only after [onExecutionStarted].
 */
interface ExecutionAwareSpeechSynthesisEngine : SpeechSynthesisEngine {
    val maximumExecutionStartWaitMillis: Long

    /** Maximum number of bounded provider admissions expected for this synthesis call. */
    fun maximumExecutionStartWaitCount(text: String, languageTag: String): Int = 1

    fun synthesize(
        text: String,
        languageTag: String,
        onExecutionStarted: () -> Unit,
    ): Flow<PcmAudioFrame>

    /**
     * Reports the exact provider-admission intervals so outer stream watchdogs can pause only
     * while useful work is waiting for its bounded shared lane.
     */
    fun synthesize(
        text: String,
        languageTag: String,
        onExecutionWaitStarted: () -> Unit,
        onExecutionStarted: () -> Unit,
    ): Flow<PcmAudioFrame> = flow {
        onExecutionWaitStarted()
        emitAll(synthesize(text, languageTag, onExecutionStarted))
    }

    override fun synthesize(text: String, languageTag: String): Flow<PcmAudioFrame> =
        synthesize(text, languageTag) {}
}

interface TranslationPipelineObserver {
    fun onSourceRecognized(utterance: RecognizedUtterance) = Unit

    fun onTranslationCompleted(
        utterance: RecognizedUtterance,
        target: TranslationTarget,
        translatedText: String,
        elapsedMillis: Long,
    ) = Unit

    fun onSynthesisCompleted(
        utterance: RecognizedUtterance,
        target: TranslationTarget,
        elapsedMillis: Long,
    ) = Unit

    /** Called once at the first audible synthesized PCM frame, before optional publication. */
    fun onSynthesisAudioStarted(
        utterance: RecognizedUtterance,
        target: TranslationTarget,
        elapsedMillis: Long,
    ) = Unit

    /**
     * New observers receive measured PCM. The default bridge preserves existing observers while
     * ensuring an empty Flow is distinguishable from a successful audible synthesis.
     */
    fun onSynthesisAudioCompleted(
        utterance: RecognizedUtterance,
        target: TranslationTarget,
        elapsedMillis: Long,
        pcm: SynthesizedPcmStats,
    ) = onSynthesisCompleted(utterance, target, elapsedMillis)

    companion object {
        val NONE = object : TranslationPipelineObserver {}
    }
}

fun interface SpeechSynthesisEngineProvider {
    fun engineFor(targetLanguageTag: String): SpeechSynthesisEngine
}

enum class ModelReadiness {
    NOT_INSTALLED,
    DOWNLOADING,
    VERIFYING,
    READY,
    FAILED,
}

data class LanguageModelStatus(
    val languageTag: String,
    val readiness: ModelReadiness,
    val downloadedBytes: Long = 0,
    val totalBytes: Long? = null,
    val errorMessage: String? = null,
)

interface LanguageModelManager {
    val statuses: StateFlow<List<LanguageModelStatus>>

    suspend fun refresh(languageTags: Set<String>)

    suspend fun prepare(languageTags: Set<String>)

    suspend fun remove(languageTag: String)
}

internal val LANGUAGE_TAG = Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*")
