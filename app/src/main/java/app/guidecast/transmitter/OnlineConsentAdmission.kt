package app.guidecast.transmitter

/** A room may keep its address while an idle microphone obtains explicit text consent. */
internal fun streamingTextConsentRoomCurrent(current: BroadcastSnapshot, owner: String?, menuActive: Boolean,
    inputRequestPending: Boolean, expectedRecordingId: String?, expectedInputEpoch: Long, currentInputEpoch: Long): Boolean =
    !expectedRecordingId.isNullOrBlank() && current.recordingId == expectedRecordingId &&
        currentInputEpoch == expectedInputEpoch && owner == "streaming" && !menuActive &&
        current.phase == BroadcastPhase.LIVE && !current.isInterpreterRelay &&
        current.inputPhase == InputPhase.IDLE && !current.inputStopping && !current.inputDraining &&
        !inputRequestPending && !current.translationTestActive && !current.testToneActive

internal fun onlineConsentCanApply(selected: TranslationApiOptions?, current: TranslationApiOptions,
    confirmed: Boolean, settingsEnabled: Boolean, roomOnlyAllowed: Boolean): Boolean =
    selected == current && confirmed && current.hasKey && current.provider != TranslationApiProvider.LOCAL &&
        (settingsEnabled || (roomOnlyAllowed && isOnlineTextApiProfile(current)))

/** Only transmission consent changes; the snapshot prevents approval of a different destination. */
internal fun selectedOnlineConsentUpdate(selected: TranslationApiOptions, current: TranslationApiOptions): TranslationApiOptions? =
    if (selected != current || !current.hasKey || current.provider == TranslationApiProvider.LOCAL) null
    else current.copy(allowOnline = true, allowLiveAudio = current.usesNativeLiveAudio, revision = current.revision + 1)
