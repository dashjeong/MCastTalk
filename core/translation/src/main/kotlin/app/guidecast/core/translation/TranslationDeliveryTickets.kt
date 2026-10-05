package app.guidecast.core.translation

import app.guidecast.core.stream.StreamPublishResult
import app.guidecast.core.stream.StreamPublishStatus
import java.io.Closeable
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** One existing engine invocation and its committed speech; not a transport retry. */
data class TranslationDeliveryIdentity(
    val inputScope: String,
    val inputSequence: Long,
    val sharedBatchId: String,
    val channelId: String,
    val targetLanguage: String,
    val attempt: Int,
    val streamEpoch: Long,
)

enum class TranslationDeliveryStage {
    TRANSLATING, SPEECH_QUEUED, SYNTHESIZING, SYNTHESIZED,
    TRANSLATION_FAILED, SYNTHESIS_FAILED, DROPPED, CANCELLED,
}

/** Count-only snapshot. Published PCM does not confirm a device playhead or human hearing. */
data class TranslationDeliverySnapshot(
    val identity: TranslationDeliveryIdentity,
    val stage: TranslationDeliveryStage,
    val settingsRevision: Long? = null,
    val corpusRevision: Long? = null,
    val publishedFrames: Long = 0,
    val publishedBytes: Long = 0,
)

/** Bounded metadata beside the existing queues; owns neither API permits nor audio buffers. */
class TranslationDeliveryTickets internal constructor(
    private val inputScope: String,
    private val sharedBatchId: String,
    private val streamEpoch: Long,
    private val isCurrent: () -> Boolean,
    private val terminalCapacity: Int = 128,
) : Closeable {
    internal class Ticket internal constructor(internal var snapshot: TranslationDeliverySnapshot)
    private val active = linkedSetOf<Ticket>()
    private val terminal = ArrayDeque<TranslationDeliverySnapshot>()
    private var closed = false

    init { require(terminalCapacity in 1..256) }

    @Synchronized internal fun begin(sequence: Long, target: TranslationTarget): Ticket? {
        if (closed || !isCurrent()) return null
        return Ticket(TranslationDeliverySnapshot(TranslationDeliveryIdentity(inputScope, sequence,
            sharedBatchId, target.channelId, target.languageTag, 1, streamEpoch),
            TranslationDeliveryStage.TRANSLATING)).also(active::add)
    }

    @Synchronized internal fun accepts(ticket: Ticket): Boolean =
        !closed && isCurrent() && ticket in active

    @Synchronized internal fun bindVersions(ticket: Ticket, settings: Long, corpus: Long?) {
        require(settings >= 0 && (corpus == null || corpus >= 0))
        if (!accepts(ticket)) return
        // A second engine wrapper may observe the same immutable input, never rewrite it.
        val prior = ticket.snapshot
        if (prior.settingsRevision != null) {
            check(prior.settingsRevision == settings && prior.corpusRevision == corpus)
        }
        ticket.snapshot = prior.copy(settingsRevision = settings, corpusRevision = corpus)
    }

    @Synchronized internal fun translated(ticket: Ticket): Boolean =
        advance(ticket, TranslationDeliveryStage.TRANSLATING, TranslationDeliveryStage.SPEECH_QUEUED)

    @Synchronized internal fun beginSpeech(ticket: Ticket): Boolean =
        advance(ticket, TranslationDeliveryStage.SPEECH_QUEUED, TranslationDeliveryStage.SYNTHESIZING)

    private fun advance(ticket: Ticket, expected: TranslationDeliveryStage, next: TranslationDeliveryStage): Boolean {
        if (!accepts(ticket) || ticket.snapshot.stage != expected) return false
        ticket.snapshot = ticket.snapshot.copy(stage = next)
        return true
    }

    @Synchronized internal fun published(ticket: Ticket, bytes: Int) {
        require(bytes > 0)
        if (!accepts(ticket) || ticket.snapshot.stage != TranslationDeliveryStage.SYNTHESIZING) return
        ticket.snapshot = ticket.snapshot.copy(publishedFrames = ticket.snapshot.publishedFrames + 1,
            publishedBytes = ticket.snapshot.publishedBytes + bytes)
    }

    /** Admission, publication and accounting share the same boundary as close. */
    @Synchronized internal fun publishIfCurrent(
        ticket: Ticket,
        bytes: Int,
        publish: () -> StreamPublishResult,
    ): StreamPublishResult? {
        require(bytes > 0)
        if (!accepts(ticket) || ticket.snapshot.stage != TranslationDeliveryStage.SYNTHESIZING) return null
        val result = publish()
        if (result.status == StreamPublishStatus.PUBLISHED) {
            // A publication observer may close reentrantly after accepting the frame.
            // Preserve that accepted frame in the terminal count without reviving work.
            val prior = ticket.snapshot
            ticket.snapshot = prior.copy(publishedFrames = prior.publishedFrames + 1,
                publishedBytes = prior.publishedBytes + bytes)
            val index = terminal.indexOfFirst { it === prior }
            if (index >= 0) terminal[index] = ticket.snapshot
        }
        return result
    }

    @Synchronized internal fun finish(ticket: Ticket, stage: TranslationDeliveryStage) {
        require(stage !in setOf(TranslationDeliveryStage.TRANSLATING, TranslationDeliveryStage.SPEECH_QUEUED,
            TranslationDeliveryStage.SYNTHESIZING))
        if (!active.remove(ticket)) return
        ticket.snapshot = ticket.snapshot.copy(stage = stage)
        terminal.addLast(ticket.snapshot)
        while (terminal.size > terminalCapacity) terminal.removeFirst()
    }

    @Synchronized fun snapshots(): List<TranslationDeliverySnapshot> = terminal.toList() + active.map { it.snapshot }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        active.toList().forEach { finish(it, TranslationDeliveryStage.CANCELLED) }
    }
}

/** Keeps captured settings/corpus versions separate from the audio stream's epoch. */
class TranslationDeliveryContext internal constructor(
    private val tickets: TranslationDeliveryTickets,
    private val ticket: TranslationDeliveryTickets.Ticket,
) : AbstractCoroutineContextElement(Key) {
    fun bindVersions(settingsRevision: Long, corpusRevision: Long?) =
        tickets.bindVersions(ticket, settingsRevision, corpusRevision)
    companion object Key : CoroutineContext.Key<TranslationDeliveryContext>
}
