package app.guidecast.transmitter

internal fun handleNativeAudioSessionEnd(reason: NativeAudioEndReason,
    terminalize: () -> Unit, releaseRevokedSession: () -> Boolean): Boolean {
    terminalize()
    return reason == NativeAudioEndReason.CONSENT_REVOKED && releaseRevokedSession()
}

/** Invalidate the captured owner before clearing any queued or device-buffered output. */
internal fun releaseNativeAudioConsentOwner(coordinator: TranslationSessionCoordinator, sessionId: Long,
    discardQueuedOutput: () -> Unit, stopOwnedMonitor: () -> Unit, releasePreviewAndProvider: () -> Unit): Boolean =
    coordinator.releaseResources(sessionId) {
        try { discardQueuedOutput() }
        finally { try { stopOwnedMonitor() } finally { releasePreviewAndProvider() } }
    }
