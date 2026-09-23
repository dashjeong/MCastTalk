package app.mcasttalk.windows.host

import java.nio.file.Path

/** Opt-in local model integration probe; never run against a user's workspace. */
object InferenceProbeMain {
    @JvmStatic fun main(args: Array<String>) {
        ProcessMeetingInference(Path.of(args.single())).use { engine ->
            val result = engine.execute(mapOf("op" to "text", "text" to "The meeting starts at three in the afternoon.",
                "sourceLanguage" to "en", "publishLanguage" to "en", "targets" to "en,ko", "audioTargets" to ""))
            check(result.requiredString("status") == "complete")
            check(result.requiredString("text_ko").any { it in '\uAC00'..'\uD7A3' })
            println("PASS Java stdio → real translation → parsed Korean response (${result.requiredInt("elapsedMs")} ms)")
        }
    }
}
