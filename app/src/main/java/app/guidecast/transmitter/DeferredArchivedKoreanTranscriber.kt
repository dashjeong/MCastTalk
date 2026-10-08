package app.guidecast.transmitter

import android.content.Context
import android.os.SystemClock
import app.guidecast.core.stream.PcmAudioFrame
import app.guidecast.core.stream.RecordedBroadcast
import app.guidecast.core.translation.RecognizedUtterance
import app.guidecast.core.translation.SpeechRecognitionConfig
import app.guidecast.core.translation.SpeechRecognitionEngine
import app.guidecast.provider.android.stt.AndroidOnDeviceSpeechRecognitionEngine
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

internal data class DeferredSourceArchiveAdmission(
    val recordingId: String,
    val part: Long,
    val inputEpoch: Long,
    val grantGeneration: Long,
    val sourceIndexOffsetBytes: Long,
    val consentAndInputOnNanos: Long,
    val inputStoppedNanos: Long,
)

internal data class DeferredArchivedKoreanResult(val sourcePcmSha256: String,
    val sourcePcmBytes: Int, val finalized: List<RecognizedUtterance>)

/** Only this invocation's raw ASR final events enter the result; pending previews are never promoted. */
internal class DeferredFinalKoreanTranscript {
    private val finalized = linkedMapOf<Long, RecognizedUtterance>()
    private val pending = linkedMapOf<Long, RecognizedUtterance>()
    fun accept(value: RecognizedUtterance) {
        require(value.sourceLanguageTag in setOf("ko", "ko-KR"))
        if (value.sequence in finalized) {
            check(value.isFinal && !value.isRetracted && value.text == finalized.getValue(value.sequence).text)
            return
        }
        if (value.isRetracted) pending.remove(value.sequence)
        else if (value.isFinal) {
            check(value.text.trim().length in 1..500 && validContextUnicode(value.text) &&
                !containsNativeContextCredentialLikeText(value.text) &&
                value.text.none { it.code < 32 || it.code == 127 } &&
                value.text.codePoints().anyMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.HANGUL })
            check(finalized.size < 3)
            pending.remove(value.sequence)
            finalized[value.sequence] = value
        } else {
            check(pending.size < 8 || value.sequence in pending)
            pending[value.sequence] = value
        }
    }
    fun finish(): List<RecognizedUtterance> {
        check(pending.isEmpty() && finalized.isNotEmpty())
        return finalized.values.toList()
    }
}

/**
 * LOCAL source PCM -> installed on-device Korean ASR. No prepare/download/Live caption is used.
 * Caller owns an optional idle backend lease and preserves it until this suspending call unwinds.
 */
internal suspend fun deferredArchivedKoreanTranscript(
    context: Context,
    recording: RecordedBroadcast,
    admission: DeferredSourceArchiveAdmission,
    currentAndIdle: () -> Boolean,
): DeferredArchivedKoreanResult? {
    if (!currentAndIdle()) return null
    val engine = AndroidOnDeviceSpeechRecognitionEngine(context.applicationContext)
    if (!engine.capability().available || !engine.languageStatus("ko-KR").isReady || !currentAndIdle()) return null
    val pcm = withContext(Dispatchers.IO) { readDeferredSourcePcm(recording, admission, currentAndIdle) }
    if (!currentAndIdle()) return null
    val result = strictDeferredKoreanRecognition(engine, pcm, currentAndIdle)
    if (!currentAndIdle()) return null
    return DeferredArchivedKoreanResult(MessageDigest.getInstance("SHA-256").digest(pcm)
        .joinToString("") { "%02x".format(it) }, pcm.size, result)
}

/** A bookmark is captured BEFORE this input-on; timestamps independently exclude older audio. */
internal fun readDeferredSourcePcm(recording: RecordedBroadcast, admission: DeferredSourceArchiveAdmission,
    currentAndIdle: () -> Boolean): ByteArray {
    require(recording.id == admission.recordingId && recording.endedAtMillis != null &&
        recording.state == "COMPLETED" && recording.failure == null && recording.droppedRecordingFrames == 0L)
    require(admission.sourceIndexOffsetBytes >= 0 && admission.sourceIndexOffsetBytes % 32 == 0L &&
        admission.consentAndInputOnNanos > 0 && admission.inputStoppedNanos > admission.consentAndInputOnNanos)
    val segments = recording.segments.filter { it.partId == admission.part && it.channel.id == "source" }
    require(segments.isNotEmpty() && segments.all { it.channel.sampleRateHz == 16_000 && it.fileOffsetBytes == 0L })
    val rawDirectory = segments.first().file.parentFile.absoluteFile
    val directory = rawDirectory.canonicalFile
    require(segments.all { it.file.parentFile.canonicalFile == directory && it.sessionId == admission.recordingId &&
        it.file.canonicalFile == File(directory, it.file.name) })
    // Normalize Android's /data/user/0 alias once; a child-file symlink must still fail containment.
    val index = File(directory, "${admission.part}-source.index")
    require(index.isFile && index.canonicalFile == index.absoluteFile && index.length() % 32 == 0L)
    val indexLength = index.length()
    require(admission.sourceIndexOffsetBytes <= indexLength)
    val out = ByteArrayOutputStream(384_000)
    var quietBytes = 0
    var lastQuietEnd = 0
    var scanned = 0
    var clipped = false
    var previousNanos = admission.consentAndInputOnNanos
    RandomAccessFile(index, "r").use { input ->
        input.seek(admission.sourceIndexOffsetBytes)
        while (input.filePointer < indexLength) {
            check(currentAndIdle())
            if (++scanned > 4_096) { clipped = true; break }
            val captured = input.readLong()
            input.readLong() // source has no utterance alignment; never invent it.
            val number = input.readInt()
            val sampleOffset = input.readLong()
            val count = input.readInt()
            require(sampleOffset >= 0 && sampleOffset <= Long.MAX_VALUE / 2 && count > 0 && count % 2 == 0 && count <= 128 * 1024)
            if (captured < admission.consentAndInputOnNanos) continue
            require(captured >= previousNanos); previousNanos = captured
            if (captured >= admission.inputStoppedNanos) break
            if (out.size() + count > 384_000) { clipped = true; break }
            val segment = requireNotNull(segments.singleOrNull { it.segment == number })
            val offset = sampleOffset * 2
            require(segment.committedBytes >= count && offset <= segment.committedBytes - count)
            require(segment.file.isFile && segment.file.canonicalFile == File(directory, segment.file.name) &&
                segment.file.length() == segment.committedBytes)
            val bytes = ByteArray(count)
            RandomAccessFile(segment.file, "r").use { file -> file.seek(offset); file.readFully(bytes) }
            out.write(bytes)
            quietBytes = if (deferredPcmQuiet(bytes)) quietBytes + bytes.size else 0
            if (quietBytes >= 12_800 && out.size() >= 16_000) lastQuietEnd = out.size()
            check(segment.file.length() == segment.committedBytes && currentAndIdle())
        }
    }
    check(index.length() == indexLength && currentAndIdle())
    if (clipped) check(lastQuietEnd > 0) // An arbitrary long-speech cut is not a completed input.
    val result = out.toByteArray().let { if (clipped) it.copyOf(lastQuietEnd) else it }
    require(result.size in 16_000..384_000 && result.size % 2 == 0)
    return result
}

private fun deferredPcmQuiet(bytes: ByteArray): Boolean {
    var square = 0.0
    for (i in bytes.indices step 2) {
        val sample = (((bytes[i + 1].toInt() and 255) shl 8) or (bytes[i].toInt() and 255)).toShort().toInt()
        square += sample.toDouble() * sample
    }
    return square / (bytes.size / 2) < 200.0 * 200.0
}

internal suspend fun strictDeferredKoreanRecognition(engine: SpeechRecognitionEngine, pcm: ByteArray,
    currentAndIdle: () -> Boolean): List<RecognizedUtterance> = withTimeout(25_000) {
    coroutineScope {
        val inputFinished = CompletableDeferred<Unit>()
        val transcript = DeferredFinalKoreanTranscript()
        val frames = flow {
            for (offset in pcm.indices step 640) {
                currentCoroutineContext().ensureActive(); check(currentAndIdle())
                val bytes = pcm.copyOfRange(offset, minOf(offset + 640, pcm.size))
                emit(PcmAudioFrame(bytes, SystemClock.elapsedRealtimeNanos()))
                delay(bytes.size / 32L)
            }
            repeat(100) {
                currentCoroutineContext().ensureActive(); check(currentAndIdle())
                emit(PcmAudioFrame(ByteArray(640), SystemClock.elapsedRealtimeNanos()))
                delay(20)
            }
            inputFinished.complete(Unit)
        }
        val collector = launch {
            engine.recognize(frames, SpeechRecognitionConfig("ko-KR")).collect { value ->
                currentCoroutineContext().ensureActive(); check(currentAndIdle())
                transcript.accept(value)
            }
        }
        val revocation = launch {
            while (collector.isActive) { if (!currentAndIdle()) { collector.cancel(); break }; delay(25) }
        }
        try {
            select<Unit> {
                inputFinished.onAwait { }
                collector.onJoin { check(inputFinished.isCompleted) }
            }
            check(withTimeoutOrNull(8_000) { collector.join(); true } == true)
            currentCoroutineContext().ensureActive(); check(currentAndIdle())
            transcript.finish()
        } finally { withContext(NonCancellable) {
            collector.cancelAndJoin(); revocation.cancelAndJoin()
        } }
    }
}
