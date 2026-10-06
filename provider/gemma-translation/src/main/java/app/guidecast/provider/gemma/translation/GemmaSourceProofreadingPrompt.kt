package app.guidecast.provider.gemma.translation

import app.guidecast.core.translation.SourceProofreadingContext

internal object GemmaSourceProofreadingPrompt {
    fun build(text: String, context: String): String {
        require(text.isNotBlank() && text.length <= 600 && context.length <= 400)
        return SourceProofreadingContext.INSTRUCTIONS + "\ncurrent_text: ${quoted(text)}" +
            "\nprevious_context: ${quoted(context)}\nReturn JSON only: {\"translation\":\"complete corrected current_text in its original language\"}"
    }
    private fun quoted(text: String): String = buildString {
        append('"')
        text.forEach { when (it) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> if (it.code < 32) append("\\u" + it.code.toString(16).padStart(4, '0')) else append(it)
        } }
        append('"')
    }
}
