package app.guidecast.transmitter

import java.io.Closeable

/** A segmentation hint. It does not recognize words or acknowledge provider processing. */
internal interface GeminiSpeechActivityClassifier : Closeable {
    val packetBytes: Int
    /** Uncertain evidence for only the most recently classified packet; never a speech guarantee. */
    val lastPacketUncertain: Boolean get() = false
    /** The final short packet may be padded for classification, never for transmission. */
    fun isSpeech(pcm: ByteArray): Boolean
}
