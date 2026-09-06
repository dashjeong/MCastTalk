package app.guidecast.transmitter


/**
 * Stable process-wide identities for native workers shared by settings, tests and broadcasts.
 *
 * Session-local gates may keep target-specific keys for their own liveness bookkeeping. Gemma,
 * however, owns one shared LiteRT model process, so every target must contend on this single
 * process key. Moonshine and ML Kit own one worker per language and retain language-specific keys.
 */
internal object ProcessNativeColdLoadKeys {
    const val GEMMA_MODEL = "gemma-translation"
    const val MLKIT_RECONCILIATION = "mlkit-reconciliation"

    fun mlKitTranslation(languageTag: String): String =
        "mlkit-translation:${languageTag.requireNativeWorkerLanguageTag()}"

    fun speech(languageTag: String): String =
        "speech:${languageTag.requireNativeWorkerLanguageTag()}"

    fun speechRecognition(languageTag: String): String =
        "speech-recognition:${languageTag.requireNativeWorkerLanguageTag()}"

    private fun String.requireNativeWorkerLanguageTag(): String =
        trim().also { require(it.isNotEmpty()) }
}
