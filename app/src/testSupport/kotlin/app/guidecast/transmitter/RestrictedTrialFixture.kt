package app.guidecast.transmitter

import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.json.JSONObject

/** In-memory ONLY. Synthetic approval strings here are not live authorization. */
internal object RestrictedTrialFixture {
    val pcm = ByteArray(3200) { 2 }
    fun setup(transcriptions: Boolean = false): String {
        val root = JSONObject(geminiLiveSetup(GEMINI_LIVE_AGENT, "en"))
        if (!transcriptions) { root.getJSONObject("setup").remove("inputAudioTranscription"); root.getJSONObject("setup").remove("outputAudioTranscription") }
        return root.toString()
    }
    fun approval(setup: String, pcm: ByteArray = this.pcm, reference: String = "API0_SYNTHETIC_ONLY", now: Long = 0): Map<String, String> = mapOf(
        "paidTrialApproval" to "APPROVED_GEMINI_3_8_LIVE_LIMITED_TRIAL_V1", "approvedBudgetUsd" to "0.50",
        "approvedModel" to GEMINI_LIVE_AGENT, "approvedConnections" to "2", "approvedResponses" to "1,2",
        "approvedSessionSeconds" to "60", "approvedInputMillis" to "38400", "approvedOutputSecondsPerResponse" to "20",
        "approvedOutputSecondsTotal" to "60", "approvalReference" to reference,
        "approvalExpiresAtMillis" to (now + 600_000).toString(), "reviewedSetupSha256" to trialDigest(setup),
        "reviewedSetupTextTokens" to "300", "approvedPcmSha256" to MessageDigest.getInstance("SHA-256").digest(pcm).joinToString("") { "%02x".format(it) })
    fun usage(promptAudio: Long = 100, outputAudio: Long = 100): JSONObject = JSONObject()
        .put("promptTokenCount", promptAudio).put("responseTokenCount", outputAudio).put("totalTokenCount", promptAudio + outputAudio)
        .put("cachedContentTokenCount", 0).put("thoughtsTokenCount", 0).put("toolUsePromptTokenCount", 0)
        .put("promptTokensDetails", modalities(promptAudio)).put("responseTokensDetails", modalities(outputAudio))
    private fun modalities(audio: Long) = JSONArray().put(JSONObject().put("modality", "AUDIO").put("tokenCount", audio))
        .put(JSONObject().put("modality", "TEXT").put("tokenCount", 0))
    fun output(size: Int = 4800, complete: Boolean = true, usage: JSONObject? = usage()): String {
        val content = JSONObject().put("modelTurn", JSONObject().put("parts", JSONArray().put(JSONObject().put("inlineData", JSONObject()
            .put("mimeType", "audio/pcm;rate=24000").put("data", Base64.getEncoder().encodeToString(ByteArray(size)))))))
            .put("turnComplete", complete)
        return JSONObject().put("serverContent", content).apply { if (usage != null) put("usageMetadata", usage) }.toString()
    }
    fun input(bytes: ByteArray = pcm) = JSONObject().put("realtimeInput", JSONObject().put("audio", JSONObject()
        .put("mimeType", "audio/pcm;rate=16000").put("data", Base64.getEncoder().encodeToString(bytes)))).toString()
    class Wire : GeminiLiveWire {
        var opens = 0; var closes = 0
        val sent = mutableListOf<String>()
        val incoming = Channel<String>(Channel.UNLIMITED)
        override suspend fun connect(key: String, authorized: () -> Boolean, block: suspend (RealtimeSocket) -> Unit) {
            check(key == "API0_OPAQUE_FIXTURE" && authorized()); opens++
            try { block(object : RealtimeSocket {
                override suspend fun send(text: String) { sent += text }
                override suspend fun receive(): String = incoming.receive()
            }) } finally { closes++ }
        }
    }
    suspend fun drive(trial: RestrictedGeminiTrial, wire: Wire, setup: String, repetitions: Int,
        pcm: ByteArray = this.pcm) {
        trial.wrap(wire).connect("API0_OPAQUE_FIXTURE", { true }) { socket ->
            socket.send(setup); socket.receive()
            repeat(repetitions) { for (offset in pcm.indices step 3200) socket.send(input(pcm.copyOfRange(offset, offset + 3200))) }
            while (true) socket.receive()
        }
    }
    suspend fun happyTwoSessionRun(journal: File, nowMillis: () -> Long): JSONObject {
        val setup = setup(); val permit = RestrictedTrialApproval.read(approval(setup, now = nowMillis()), nowMillis())
        val trial = RestrictedGeminiTrial(permit, TrialPermitJournal(journal), setup, pcm, nowMillis)
        val wire = Wire()
        for (n in 1..2) {
            wire.incoming.send("{\"setupComplete\":{}}")
            repeat(n) { wire.incoming.send(output()) }
            drive(trial, wire, setup, n)
        }
        check(wire.opens == 2 && wire.closes == 2)
        check(trial.countsOnly().getInt("completed_sessions") == 2)
        val before = wire.opens
        val rejected = runCatching { drive(trial, wire, setup, 1) }.exceptionOrNull() as? TrialBlocked
        check(rejected?.reasonCode == "CONNECTION_LIMIT" && wire.opens == before)
        check(wire.sent.count { JSONObject(it).optJSONObject("realtimeInput")?.optBoolean("audioStreamEnd") == true } == 3)
        return trial.countsOnly().put("apiCalls", 0).put("mockConnections", wire.opens)
            .put("thirdConnectionRejectedBeforeDelegate", true).put("syntheticApprovalOnly", true)
    }
}
