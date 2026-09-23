package app.guidecast.transmitter

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognitionPart
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class FileSpeechWord(val text: String, val startMs: Long, val endMs: Long? = null)
data class FileSpeechSegment(
    val id: Long,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val languageTag: String?,
    val words: List<FileSpeechWord> = emptyList(),
    val timingEstimated: Boolean = true,
    val languageConfidence: Int? = null,
)
data class FileTranscriptionResult(
    val segments: List<FileSpeechSegment>,
    val sourceLanguageTag: String?,
    val warnings: List<String>,
    val durationMs: Long,
)
data class FileTranscriptionProgress(
    val processedMs: Long,
    val durationMs: Long?,
    val segmentCount: Int,
    val message: String,
)

/** File jobs claim a backend lease and are excluded from live capture by the workspace owner. */
object FileSpeechTranscriber {
    fun usesAppRecognition(languageTag: String): Boolean =
        normalizeSourceLanguage(languageTag) in GUIDECAST_SOURCE_LANGUAGE_BASE_TAGS

    suspend fun transcribe(
        context: Context,
        uri: Uri,
        sourceLanguageTag: String? = null,
        allowedLanguageTags: List<String>? = null,
        onProgress: (FileTranscriptionProgress) -> Unit = {},
    ): FileTranscriptionResult {
        val manualLanguage = sourceLanguageTag?.let {
            val normalized = Locale.forLanguageTag(it).toLanguageTag()
            if (normalized == "und") throw FileTranscriptionException("원문 언어를 다시 선택하세요.")
            normalized
        }
        var duration: Long? = null
        val frames = FileAudioDecoder.decode(context, uri) { info ->
            duration = info.durationMs
            onProgress(FileTranscriptionProgress(0, duration, 0, "파일 음성을 읽고 있습니다"))
        }
        if (manualLanguage != null && usesAppRecognition(manualLanguage)) {
            val app = context.applicationContext as? GuideCastApplication
                ?: throw FileTranscriptionException("앱 음성 인식 작업 공간을 열지 못했습니다.")
            onProgress(FileTranscriptionProgress(0, null, 0, "선택한 원문 언어의 음성 인식을 준비하고 있습니다"))
            return withPreparedLocalSpeechRecognition(app, manualLanguage) { engine ->
                transcribePreparedFileFrames(frames, engine, manualLanguage, { duration },
                    availability = app.speechRecognitionEngine.status.map { it.isReady }, onProgress = onProgress)
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) throw FileTranscriptionException(
            "이 언어의 파일 음성 인식은 Android 13 이상의 시스템 언어팩이 필요합니다. 지원하는 원문 언어를 직접 선택하세요.",
        )
        if (sourceLanguageTag == null && Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            throw FileTranscriptionException("이 Android 버전에서는 자동 언어 감지를 사용할 수 없습니다. 원문 언어를 직접 선택하세요.")
        }
        val available = try { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) } catch (_: Exception) { false }
        if (!available) throw FileTranscriptionException(
            "시스템의 자동 언어 감지·음성 인식을 사용할 수 없습니다. 한국어 등 앱이 지원하는 원문 언어를 직접 선택하세요.",
        )
        return transcribeFileFrames(frames, manualLanguage, { duration }, onProgress) { bytes, start, end, language ->
            recognizeFileChunk(context.applicationContext, bytes, start, end, language, allowedLanguageTags)
        }
    }
}

internal suspend fun transcribeFileFrames(
    frames: Flow<FilePcmFrame>,
    manualLanguage: String?,
    durationMs: () -> Long?,
    onProgress: (FileTranscriptionProgress) -> Unit = {},
    recognizeChunk: suspend (ByteArray, Long, Long, String?) -> List<FileSpeechSegment>,
): FileTranscriptionResult {
        val segments = mutableListOf<FileSpeechSegment>()
        val warnings = linkedSetOf<String>()
        val pending = ByteArrayOutputStream(320_000)
        var chunkStart = 0L
        var chunkEnd = 0L
        var observedEnd = 0L
        var quietMillis = 0L
        var characters = 0L

        suspend fun flush() {
            if (pending.size() == 0) return
            val bytes = pending.toByteArray()
            pending.reset()
            if (bytes.any { it != 0.toByte() }) {
                val recognized = recognizeChunk(bytes, chunkStart,
                    chunkEnd, manualLanguage)
                recognized.forEach { segment ->
                    characters += segment.text.length.toLong() + segment.words.sumOf { it.text.length.toLong() }
                    if (segments.size >= 20_000 || characters > 20_000_000) throw FileTranscriptionException(
                        "전사 결과가 저장 한도를 넘었습니다. 파일을 나누어 변환하세요.",
                    )
                    segments += segment.copy(id = segments.size.toLong())
                }
                if (recognized.any { it.words.isEmpty() }) warnings +=
                    "이 기기에서 유효한 단어 시각을 확인하지 못했습니다. 구간 시각은 음원 처리 범위의 추정값입니다."
            }
            onProgress(FileTranscriptionProgress(chunkEnd, durationMs(), segments.size, "음성을 텍스트로 변환 중"))
        }

        try {
            frames.collect { frame ->
                currentCoroutineContext().ensureActive()
                if (pending.size() > 0 && frame.startMs > chunkEnd + 100) flush()
                if (pending.size() == 0) { chunkStart = frame.startMs; quietMillis = 0 }
                pending.write(frame.bytes)
                chunkEnd = frame.endMs
                observedEnd = maxOf(observedEnd, chunkEnd)
                quietMillis = if (filePcmIsQuiet(frame.bytes)) quietMillis + frame.bytes.size / 32 else 0
                // Prefer an existing pause after ten seconds; keep every sample even during a
                // long continuous passage. Each recognizer session has at most 1.92 MB of PCM.
                if (pending.size() >= 320_000 && quietMillis >= 400 || pending.size() >= 1_920_000) {
                    if (pending.size() >= 1_920_000 && quietMillis < 400) warnings +=
                        "쉼이 없는 긴 발화는 구간 경계에서 나누어 인식했습니다. 경계 문장을 확인하세요."
                    flush()
                }
            }
            flush()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: FileTranscriptionException) {
            throw error
        } catch (error: IllegalStateException) {
            // Decoder and codec exceptions can include a URI/vendor internals; retain only our
            // own fixed Korean instructions, never copy platform exception messages to exports.
            throw FileTranscriptionException("파일 음성을 처리하지 못했습니다. 오프라인 언어팩과 파일 형식을 확인하세요.")
        } catch (_: Exception) {
            throw FileTranscriptionException("파일을 읽지 못했습니다. 파일 선택 권한과 MP3·MP4·M4A·WAV 형식을 확인하세요.")
        }
        if (segments.isEmpty()) warnings += "인식된 음성이 없습니다. 음원 내용과 원문 언어를 확인하세요."
        if (segments.any { it.words.isNotEmpty() }) warnings +=
            "단어 시작 시각은 인식기가 제공했습니다. 단어 종료 및 문장 끝 경계는 확정 시각이 아닙니다."
        val languages = segments.mapNotNull { it.languageTag }.distinct()
        return FileTranscriptionResult(segments, manualLanguage ?: languages.singleOrNull(), warnings.toList(),
            maxOf(durationMs() ?: 0L, observedEnd))
}

class FileTranscriptionException(message: String) : IllegalStateException(message)

private data class FileRawResult(
    val text: String,
    val words: List<FileSpeechWord>,
    val languageTag: String?,
    val confidence: Int?,
)

@androidx.annotation.RequiresApi(33)
private suspend fun recognizeFileChunk(
    context: Context,
    bytes: ByteArray,
    startMs: Long,
    endMs: Long,
    manualLanguage: String?,
    allowedLanguageTags: List<String>? = null,
): List<FileSpeechSegment> = coroutineScope {
    val pipe = ParcelFileDescriptor.createPipe()
    val input = pipe[0]
    val output = pipe[1]
    val completion = CompletableDeferred<Unit>()
    val active = AtomicBoolean(true)
    val lock = Any()
    var submitted = 0
    var ended = false
    var detectedLanguage: String? = null
    var confidence: Int? = null
    val results = mutableListOf<FileRawResult>()
    var recognizer: SpeechRecognizer? = null
    fun complete(error: Throwable? = null) {
        if (error != null) completion.completeExceptionally(error) else completion.complete(Unit)
    }
    fun earlyEnd() = FileTranscriptionException(
        "기기 음성 인식기가 파일 입력을 끝까지 처리하기 전에 종료했습니다. 원문 언어를 직접 선택하거나 음성 서비스를 업데이트한 뒤 다시 시도하세요.",
    )
    fun receive(bundle: Bundle, sessionFinal: Boolean) {
        val text = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
            ?.takeIf { it.isNotEmpty() } ?: return
        if (sessionFinal && (text == results.lastOrNull()?.text ||
                text == results.joinToString(" ") { it.text })) return
        if (results.size >= 1_000 || text.length >= 30_000 ||
            results.sumOf { it.text.length } + text.length > 60_000) {
            throw FileTranscriptionException("음성 인식 결과가 구간 한도를 넘었습니다. 파일을 나누어 변환하세요.")
        }
        val words = if (Build.VERSION.SDK_INT >= 34) {
            bundle.getParcelableArrayList(SpeechRecognizer.RECOGNITION_PARTS, RecognitionPart::class.java)
                .orEmpty().takeIf { it.size <= 10_000 }.orEmpty()
                .map { FileSpeechWord(it.formattedText ?: it.rawText, it.timestampMillis) }
                .takeIf { words -> words.sumOf { it.text.length.toLong() } <= 60_000 }
                .orEmpty().let { validatedFileWordTimes(it, startMs, endMs) }
        } else emptyList()
        results += FileRawResult(text, words, manualLanguage ?: detectedLanguage, confidence)
    }
    try {
        Os.fcntlInt(output.fileDescriptor, OsConstants.F_SETFL,
            Os.fcntlInt(output.fileDescriptor, OsConstants.F_GETFL, 0) or OsConstants.O_NONBLOCK)
        withContext(Dispatchers.Main.immediate) {
            val client = SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also { recognizer = it }
            client.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onError(error: Int) = synchronized(lock) {
                    if (!active.get()) return@synchronized
                    if (error in setOf(SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT) && ended) {
                        complete()
                    } else complete(FileTranscriptionException(
                        "기기 음성 인식이 완료되지 않았습니다 (오류 $error). 원문 언어를 직접 선택하고 해당 오프라인 언어팩을 준비하세요.",
                    ))
                }
                override fun onSegmentResults(segmentResults: Bundle) = synchronized(lock) {
                    if (active.get()) runCatching { receive(segmentResults, false) }.onFailure(::complete)
                    Unit
                }
                override fun onResults(bundle: Bundle) = synchronized(lock) {
                    if (!active.get()) return@synchronized
                    if (!ended) complete(earlyEnd())
                    else runCatching { receive(bundle, true); complete() }.onFailure(::complete)
                    Unit
                }
                override fun onEndOfSegmentedSession() = synchronized(lock) {
                    if (active.get()) complete(if (ended) null else earlyEnd())
                }
                override fun onLanguageDetection(bundle: Bundle) = synchronized(lock) {
                    if (!active.get() || Build.VERSION.SDK_INT < 34) return@synchronized
                    detectedLanguage = bundle.getString(SpeechRecognizer.DETECTED_LANGUAGE)
                        ?.takeIf { Locale.forLanguageTag(it).language.isNotBlank() }
                    confidence = if (bundle.containsKey(SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL)) {
                        bundle.getInt(SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL)
                    } else null
                    val switching = bundle.getInt(SpeechRecognizer.LANGUAGE_SWITCH_RESULT, 0)
                    if (manualLanguage == null && switching in setOf(
                            SpeechRecognizer.LANGUAGE_SWITCH_RESULT_FAILED,
                            SpeechRecognizer.LANGUAGE_SWITCH_RESULT_SKIPPED_NO_MODEL,
                        )) complete(FileTranscriptionException("감지한 언어의 오프라인 모델로 전환하지 못했습니다. 원문 언어를 직접 선택하고 언어팩을 준비하세요."))
                }
            })
            client.startListening(fileRecognitionIntent(input, manualLanguage, allowedLanguageTags))
        }
        val writer = launch(Dispatchers.IO) {
            try {
                var lastProgress = SystemClock.elapsedRealtime()
                while (submitted < bytes.size) {
                    currentCoroutineContext().ensureActive()
                    val written = synchronized(lock) {
                        val count = try {
                            Os.write(output.fileDescriptor, bytes, submitted, minOf(640, bytes.size - submitted))
                        } catch (error: ErrnoException) {
                            if (error.errno == OsConstants.EAGAIN || error.errno == OsConstants.EINTR) 0 else throw error
                        }
                        submitted += count
                        if (submitted == bytes.size) ended = true
                        count
                    }
                    if (written > 0) lastProgress = SystemClock.elapsedRealtime() else {
                        check(SystemClock.elapsedRealtime() - lastProgress < 10_000) {
                            "음성 인식기가 파일 PCM을 읽지 않습니다. 원문 언어와 오프라인 언어팩을 확인하세요."
                        }
                        delay(10)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { complete(error) }
            finally { runCatching { output.close() } }
        }
        try {
            try {
                withTimeout(bytes.size / 32L * 2 + 30_000L) { completion.await() }
            } catch (timeout: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                throw FileTranscriptionException("기기 음성 인식 응답 시간이 초과됐습니다. 원문 언어와 오프라인 언어팩을 확인한 뒤 다시 시도하세요.")
            }
            writer.join()
        } finally { writer.cancel() }
        val recognized = synchronized(lock) { results.toList() }
        if (manualLanguage == null && recognized.any { it.languageTag == null }) {
            throw FileTranscriptionException("기기 음성 인식기가 자동 감지 언어를 제공하지 않았습니다. 원문 언어를 직접 선택해 다시 변환하세요.")
        }
        // When an OEM omits timestamps, callback wall time or pipe write progress is not a media
        // boundary. Merge same-language untimed lines and retain the actual chunk's whole range.
        val grouped = mutableListOf<FileRawResult>()
        recognized.forEach { result ->
            val last = grouped.lastOrNull()
            if (last != null && last.words.isEmpty() && result.words.isEmpty() &&
                last.languageTag == result.languageTag) grouped[grouped.lastIndex] =
                last.copy(text = last.text + " " + result.text)
            else grouped += result
        }
        grouped.mapIndexed { index, result ->
            val begin = result.words.firstOrNull()?.startMs ?: startMs
            val nextStart = grouped.getOrNull(index + 1)?.words?.firstOrNull()?.startMs
            val end = maxOf(nextStart ?: endMs, result.words.lastOrNull()?.startMs ?: begin).coerceIn(begin, endMs)
            FileSpeechSegment(index.toLong(), begin, end, result.text, result.languageTag,
                result.words, true, result.confidence)
        }
    } finally {
        active.set(false)
        runCatching { output.close() }; runCatching { input.close() }
        withContext(NonCancellable + Dispatchers.Main.immediate) {
            recognizer?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }
        }
    }
}

@androidx.annotation.RequiresApi(33)
internal fun fileRecognitionIntent(input: ParcelFileDescriptor, sourceLanguageTag: String?, allowedLanguageTags: List<String>? = null): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, input)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
        putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        if (sourceLanguageTag != null) putExtra(RecognizerIntent.EXTRA_LANGUAGE, sourceLanguageTag)
        if (Build.VERSION.SDK_INT >= 34) {
            putExtra(RecognizerIntent.EXTRA_REQUEST_WORD_TIMING, true)
            if (sourceLanguageTag == null) {
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
                if (!allowedLanguageTags.isNullOrEmpty()) {
                    putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, ArrayList(allowedLanguageTags))
                    putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, ArrayList(allowedLanguageTags))
                }
            }
        }
    }

/** Reject absent/default-zero or invalid timing rather than assigning fabricated word intervals. */
internal fun validatedFileWordTimes(words: List<FileSpeechWord>, startMs: Long, endMs: Long): List<FileSpeechWord> {
    if (startMs < 0 || endMs < startMs || words.isEmpty() || words.all { it.startMs == 0L } || words.any { it.startMs < 0 } ||
        words.zipWithNext().any { (a, b) -> b.startMs < a.startMs } ||
        words.any { it.startMs > endMs - startMs }) return emptyList()
    return words.map { it.copy(startMs = startMs + it.startMs, endMs = null) }
}

private fun filePcmIsQuiet(bytes: ByteArray): Boolean {
    if (bytes.isEmpty()) return true
    var squares = 0.0
    for (offset in bytes.indices step 2) {
        val sample = ((bytes[offset].toInt() and 255) or (bytes[offset + 1].toInt() shl 8)).toShort()
        squares += sample.toDouble() * sample
    }
    return squares / (bytes.size / 2) < 200.0 * 200.0
}
