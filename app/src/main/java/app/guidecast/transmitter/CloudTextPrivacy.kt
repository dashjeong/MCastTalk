package app.guidecast.transmitter

/** Conservative local stop for recognizable credentials; not a general PII classifier. */
internal fun containsCredentialLikeText(text: String): Boolean = text.contains("PRIVATE KEY-----") ||
    Regex("\\bsk-[A-Za-z0-9_-]{20,}").containsMatchIn(text) ||
    Regex("\\bAIza[A-Za-z0-9_-]{30,}").containsMatchIn(text) ||
    Regex("(?i)\\b(?:authorization\\s*:\\s*bearer|api[_ -]?key\\s*[:=]|password\\s*[:=])\\s*\\S{8,}").containsMatchIn(text)

private val nativeContextKeyShapes = listOf(Regex("sk-[A-Za-z0-9_-]{20,}"), Regex("AIza[A-Za-z0-9_-]{30,}"))

internal fun containsNativeContextCredentialLikeText(text: String): Boolean =
    containsCredentialLikeText(text) || nativeContextKeyShapes.any { it.containsMatchIn(text) }
