package app.guidecast.transmitter

/** Immediate operator-control transitions only; worker progress belongs to the bounded heartbeat. */
internal fun broadcastControlDiagnostic(state: BroadcastSnapshot): String =
    "mode=${state.runMode} broadcast=${state.phase} input=${state.inputPhase} " +
        "inputError=${state.inputErrorMessage != null} error=${state.errorMessage != null}"
