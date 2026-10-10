package app.guidecast.transmitter

internal enum class MenuSpeechFailurePhase { PREPARATION, NOT_READY, TIMEOUT, CANCELLED, SYNTHESIS }

/** Content-free capability evidence; never includes utterances, paths, or exception messages. */
internal fun menuSpeechFailureDiagnostic(
    phase: MenuSpeechFailurePhase,
    languageTag: String,
    error: Throwable? = null,
    nativeReady: Boolean,
    offlineReady: Boolean,
): String {
    val language = languageTag.takeIf { Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*").matches(it) }
        ?: "invalid"
    val errorClass = error?.javaClass?.name ?: "none"
    val causeClass = error?.cause?.javaClass?.name ?: "none"
    return "phase=${phase.name} language=$language errorClass=$errorClass causeClass=$causeClass " +
        "nativeReady=$nativeReady offlineReady=$offlineReady"
}
