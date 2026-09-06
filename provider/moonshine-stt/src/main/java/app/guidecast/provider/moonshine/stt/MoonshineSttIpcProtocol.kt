package app.guidecast.provider.moonshine.stt

/** Small, version-local Binder protocol shared by the client and private worker process. */
internal object MoonshineSttIpcProtocol {
    const val READINESS_DOWNLOAD_REQUIRED = 0
    const val READINESS_DOWNLOADING = 1
    const val READINESS_READY = 2
    const val READINESS_FAILED = 3
    const val READINESS_UNSUPPORTED = 4
    /** Model assets remain verified, but the isolated native worker must be admitted and warmed. */
    const val READINESS_NATIVE_RESTART_REQUIRED = 5

    const val ERROR_INVALID_ARGUMENT = 1
    const val ERROR_MODEL_PREPARATION = 2
    const val ERROR_RECOGNITION = 3
    const val ERROR_WORKER_STOPPED = 4
    const val ERROR_NATIVE_PREPARATION_REQUIRED = 5

    // Binder has a process-wide transaction buffer. One acknowledged 32 KiB PCM transaction at
    // a time stays far below that limit and provides backpressure to the capture Flow.
    const val MAX_PCM_TRANSACTION_BYTES = 32 * 1_024
}

internal fun ByteArray.asBinderSafePcmChunks(
    maximumBytes: Int = MoonshineSttIpcProtocol.MAX_PCM_TRANSACTION_BYTES,
): Sequence<ByteArray> {
    require(isNotEmpty()) { "PCM frame must not be empty" }
    require(size % Short.SIZE_BYTES == 0) { "PCM frame must contain complete 16-bit samples" }
    require(maximumBytes >= Short.SIZE_BYTES) { "PCM Binder chunk size is too small" }
    val alignedMaximum = maximumBytes - (maximumBytes % Short.SIZE_BYTES)
    if (size <= alignedMaximum) return sequenceOf(this)
    return sequence {
        var offset = 0
        while (offset < size) {
            val end = minOf(offset + alignedMaximum, size)
            yield(copyOfRange(offset, end))
            offset = end
        }
    }
}
