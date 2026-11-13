package app.guidecast.core.translation

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Single successful translation pair stored in session memory.
 */
data class SessionMemoryPair(
    val sourceText: String,
    val translatedText: String,
    val sequence: Long? = null,
)

/**
 * Request-local context providing bounded recent translation memory for this broadcast session.
 * 온라인과 기기 내 번역이 같은 언어 채널의 확정 번역만 참고한다.
 */
class TranslationSessionMemoryContext(
    /** Formatted JSON array string representing recent pairs, bounded to [MAX_IPC_CHARS]. */
    val memory: String,
    /** Structured pairs represented by [memory], at most [MAX_PAIRS] entries. */
    val pairs: List<SessionMemoryPair> = emptyList(),
    val previousByTarget: Map<String, List<SessionMemoryPair>> = emptyMap(),
) : AbstractCoroutineContextElement(Key) {

    companion object Key : CoroutineContext.Key<TranslationSessionMemoryContext> {
        /** Maximum number of recent pairs retained per language channel. */
        const val MAX_PAIRS = 4
        /** Maximum aggregate raw character length of source and translation text. */
        const val MAX_TOTAL_RAW_CHARS = 800
        /** Maximum raw character length for a single sentence; overlong sentences are omitted whole. */
        const val MAX_SINGLE_RAW_CHARS = 400
        /** Hard ceiling for formatted JSON string passed across IPC. */
        const val MAX_IPC_CHARS = 1_200
    }
}

/**
 * In-memory, session-scoped bilingual memory for broadcast translation.
 *
 * Maintains up to [maxPairs] recent successful (source, translation) pairs per language channel.
 * Stored references are cleared when the owning broadcast or pipeline completes or is cancelled.
 *
 * This memory provides reference context only; it never returns cached translations or
 * bypasses normal translation inference.
 */
class BroadcastSessionBilingualMemory(
    private val maxPairs: Int = TranslationSessionMemoryContext.MAX_PAIRS,
    private val maxTotalRawChars: Int = TranslationSessionMemoryContext.MAX_TOTAL_RAW_CHARS,
    private val maxSingleRawChars: Int = TranslationSessionMemoryContext.MAX_SINGLE_RAW_CHARS,
    private val maxIpcChars: Int = TranslationSessionMemoryContext.MAX_IPC_CHARS,
) {
    private val lock = Any()
    private val channelPairs = mutableMapOf<String, ArrayDeque<SessionMemoryPair>>()
    @Volatile
    private var isClosed = false

    /**
     * Records a confirmed, successful translation pair immediately upon translation completion.
     * Overlong sentences or requests made after session close are rejected.
     */
    fun record(
        sourceLanguageTag: String,
        targetLanguageTag: String,
        sourceText: String,
        translatedText: String,
        sequence: Long? = null,
    ) {
        if (isClosed) return
        val trimmedSource = sourceText.trim()
        val trimmedTarget = translatedText.trim()
        if (trimmedSource.isEmpty() || trimmedTarget.isEmpty()) return
        if (trimmedSource.length > maxSingleRawChars || trimmedTarget.length > maxSingleRawChars) return
        if (trimmedSource.length + trimmedTarget.length > maxTotalRawChars) return

        val key = channelKey(sourceLanguageTag, targetLanguageTag)
        synchronized(lock) {
            if (isClosed) return
            val deque = channelPairs.getOrPut(key) { ArrayDeque(maxPairs) }
            deque.addLast(SessionMemoryPair(trimmedSource, trimmedTarget, sequence))
            while (deque.size > maxPairs) {
                deque.removeFirst()
            }
            while (deque.isNotEmpty() && deque.sumOf { it.sourceText.length + it.translatedText.length } > maxTotalRawChars) {
                deque.removeFirst()
            }
        }
    }

    /**
     * Builds request-local [TranslationSessionMemoryContext] for the specified channel.
     * If formatted JSON exceeds [maxIpcChars], oldest pairs are dropped by whole sentence unit.
     */
    fun buildContext(
        sourceLanguageTag: String,
        targetLanguageTag: String,
        beforeSequence: Long? = null,
    ): TranslationSessionMemoryContext {
        if (isClosed) return TranslationSessionMemoryContext(memory = "", pairs = emptyList())
        val key = channelKey(sourceLanguageTag, targetLanguageTag)
        val snapshot = synchronized(lock) {
            if (isClosed) emptyList() else channelPairs[key]?.filter {
                beforeSequence == null || it.sequence == null || it.sequence < beforeSequence
            }.orEmpty()
        }
        if (snapshot.isEmpty()) {
            return TranslationSessionMemoryContext(memory = "", pairs = emptyList())
        }

        val working = ArrayDeque(snapshot)
        while (working.isNotEmpty()) {
            val json = buildJsonArrayString(working)
            val rawSum = working.sumOf { it.sourceText.length + it.translatedText.length }
            if (json.length <= maxIpcChars && rawSum <= maxTotalRawChars) {
                return TranslationSessionMemoryContext(memory = json, pairs = working.toList())
            }
            working.removeFirst()
        }
        return TranslationSessionMemoryContext(memory = "", pairs = emptyList())
    }

    /**
     * Clears all stored references when the session or pipeline ends.
     */
    fun clear() {
        synchronized(lock) {
            isClosed = true
            channelPairs.values.forEach { it.clear() }
            channelPairs.clear()
        }
    }

    private fun channelKey(sourceLanguageTag: String, targetLanguageTag: String): String =
        "${sourceLanguageTag.trim().lowercase()}->${targetLanguageTag.trim().lowercase()}"

    private fun buildJsonArrayString(pairs: Collection<SessionMemoryPair>): String = buildString {
        append('[')
        pairs.forEachIndexed { index, pair ->
            if (index > 0) append(',')
            append("{\"source\":")
            append(escapeJsonString(pair.sourceText))
            append(",\"translation\":")
            append(escapeJsonString(pair.translatedText))
            append('}')
        }
        append(']')
    }

    private fun escapeJsonString(value: String): String = buildString {
        append('"')
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        append(ch)
                    }
                }
            }
        }
        append('"')
    }
}
