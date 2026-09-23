package app.guidecast.provider.moonshine.stt

/**
 * The SDK's synchronous stopStream performs a final native transcription and invokes listeners.
 * Keep those listeners alive until it returns, then retire the stream before acknowledging EOF.
 * A failed flush/release is never acknowledged as a successful complete transcript.
 */
internal fun finishMoonshineSttInput(
    flushNative: () -> Unit,
    releaseNative: () -> Unit,
    acknowledge: () -> Unit,
) {
    try {
        flushNative()
    } catch (failure: Throwable) {
        try { releaseNative() } catch (cleanupFailure: Throwable) { failure.addSuppressed(cleanupFailure) }
        throw failure
    }
    releaseNative()
    acknowledge()
}
