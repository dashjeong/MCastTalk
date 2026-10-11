package app.guidecast.transmitter

import android.content.Context
import app.guidecast.core.audio.pcmS16LeSignalStats
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.security.MessageDigest

/** One recurrent state per uninterrupted microphone stream; no audio leaves the device here. */
internal class SileroSpeechActivityClassifier(context: Context) : GeminiSpeechActivityClassifier {
    override val packetBytes = 1_024
    private val environment = OrtEnvironment.getEnvironment()
    private val recurrent = FloatArray(256)
    private val previous = FloatArray(64)
    private var closed = false
    private val gate = SpeechProbabilityGate()
    private var frames = 0L
    private var admittedFrames = 0L
    private var uncertainFrames = 0L
    private var maximumProbability = 0f
    private var inferenceNanos = 0L
    private var maximumInferenceNanos = 0L
    override var lastPacketUncertain: Boolean = false
        private set
    private val session: OrtSession

    init {
        val model = context.assets.open("models/silero-vad-v6.2.3.onnx").use { it.readBytes() }
        val digest = MessageDigest.getInstance("SHA-256").digest(model).joinToString("") { "%02x".format(it) }
        check(digest == "1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3") {
            "Speech activity model integrity check failed"
        }
        session = OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(1)
            options.setInterOpNumThreads(1)
            environment.createSession(model, options)
        }
        try {
            check(session.inputNames == setOf("input", "state", "sr"))
            check(session.outputNames == setOf("output", "stateN"))
        } catch (failure: Throwable) { session.close(); throw failure }
    }

    override fun isSpeech(pcm: ByteArray): Boolean {
        val started = System.nanoTime()
        check(!closed)
        require(pcm.isNotEmpty() && pcm.size <= packetBytes && pcm.size % 2 == 0)
        val samples = FloatArray(576)
        previous.copyInto(samples)
        for (index in 0 until pcm.size / 2) {
            val sample = ((pcm[index * 2].toInt() and 255) or (pcm[index * 2 + 1].toInt() shl 8)).toShort()
            samples[index + 64] = sample / 32_768f
        }
        // Only the classifier sees padding for an EOF or pause tail. The transport sends valid PCM bytes.
        val probability = OnnxTensor.createTensor(environment, FloatBuffer.wrap(samples), longArrayOf(1, 576)).use { audio ->
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(recurrent), longArrayOf(2, 1, 128)).use { state ->
                OnnxTensor.createTensor(environment, LongBuffer.wrap(longArrayOf(16_000)), longArrayOf()).use { rate ->
                    session.run(mapOf("input" to audio, "state" to state, "sr" to rate)).use { result ->
                        @Suppress("UNCHECKED_CAST")
                        val probability = (result.get("output").get().value as Array<FloatArray>).single().single()
                        @Suppress("UNCHECKED_CAST")
                        val next = result.get("stateN").get().value as Array<Array<FloatArray>>
                        check(probability.isFinite() && probability in 0f..1f && next.size == 2)
                        for (index in 0..1) {
                            val values = next[index].single()
                            check(values.size == 128 && values.all(Float::isFinite))
                            values.copyInto(recurrent, index * 128)
                        }
                        probability
                    }
                }
            }
        }
        samples.copyInto(previous, 0, 512, 576)
        frames++
        maximumProbability = maxOf(maximumProbability, probability)
        val rms = pcm.pcmS16LeSignalStats().rms
        lastPacketUncertain = probability >= 0.10f || rms >= 0.025f
        if (lastPacketUncertain) uncertainFrames++
        val admitted = gate.accept(probability, rms)
        if (admitted) admittedFrames++
        val elapsed = System.nanoTime() - started
        inferenceNanos += elapsed
        maximumInferenceNanos = maxOf(maximumInferenceNanos, elapsed)
        return admitted
    }

    override fun close() {
        if (!closed) {
            closed = true; recurrent.fill(0f); previous.fill(0f); session.close()
            RuntimeDiagnosticLog.durableRecord("speech_activity", "classifier=SILERO_V6_2_3 frames=$frames admitted=$admittedFrames uncertain=$uncertainFrames max_probability=$maximumProbability total_ns=$inferenceNanos max_ns=$maximumInferenceNanos")
        }
    }
}
