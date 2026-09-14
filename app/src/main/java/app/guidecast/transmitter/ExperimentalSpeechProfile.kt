package app.guidecast.transmitter

import app.guidecast.core.translation.SpeechExpressionProfile

/** Text punctuation and an operator-selected register, not emotion recognition or voice cloning. */
internal fun deriveSpeechExpression(
    text: String,
    register: TranslationRegister = TranslationRegister.FORMAL,
): SpeechExpressionProfile {
    var rate = if (register == TranslationRegister.FORMAL) 0.94f else 1.02f
    var pitch = if (register == TranslationRegister.FORMAL) 0.99f else 1.02f
    val ending = text.trimEnd().lastOrNull()
    if (ending == '?' || ending == '？') pitch += 0.03f
    if (ending == '!' || ending == '！') { pitch += 0.02f; rate += 0.02f }
    if (text.length > 160) rate -= 0.02f
    return SpeechExpressionProfile(rate.coerceIn(0.9f, 1.1f), pitch.coerceIn(0.9f, 1.1f))
}

internal fun developerSpeechPreviewAllowed(developerInfo: Boolean, expressionRequested: Boolean,
    expressionEnabled: Boolean, runtimeIdle: Boolean): Boolean =
    developerInfo && runtimeIdle && (!expressionRequested || expressionEnabled)
