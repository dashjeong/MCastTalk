package app.guidecast.transmitter

/** Preflight untrusted API JSON before a recursive platform parser can consume its stack. */
internal fun requireBoundedJson(text: String, maximumChars: Int = 65_536) {
    require(text.length <= maximumChars)
    var depth = 0
    var quoted = false
    var escaped = false
    for (c in text) {
        if (quoted) {
            if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
        } else when (c) {
            '"' -> quoted = true
            '{', '[' -> { depth++; require(depth <= 16) }
            '}', ']' -> { depth--; require(depth >= 0) }
        }
    }
    require(!quoted && depth == 0)
}
