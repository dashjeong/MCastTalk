package app.guidecast.transmitter

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import org.json.JSONObject

internal enum class OnlineConnectionResult(val message: String) {
    READY("연결 확인됨 · 음성이나 대본은 보내지 않았습니다"),
    KEY_REQUIRED("API 키를 입력한 뒤 연결을 확인해 주세요."),
    KEY_REJECTED("서비스에서 키를 인증하지 못했습니다. API 키를 확인하거나 새 키로 변경해 주세요."),
    ACCESS_DENIED("이 계정에서 서비스를 이용할 권한이 없습니다. 서비스 콘솔의 프로젝트·API 권한을 확인해 주세요."),
    MODEL_UNAVAILABLE("선택한 모델을 이용할 수 없습니다. 모델을 변경하거나 해당 모델의 이용 권한을 확인해 주세요."),
    LIMIT_REACHED("서비스 요청 한도에 도달했습니다. 잠시 후 다시 시도하거나 서비스 콘솔의 사용량·한도를 확인해 주세요."),
    NETWORK_UNAVAILABLE("서버에 연결할 수 없습니다. Wi-Fi·모바일 데이터 연결을 확인한 뒤 다시 시도해 주세요."),
    TIMED_OUT("서버 응답이 늦어 연결 확인을 종료했습니다. 키는 유지됩니다. 잠시 후 다시 시도해 주세요."),
    SERVICE_UNAVAILABLE("서비스가 일시적으로 응답하지 않습니다. 잠시 후 다시 시도해 주세요."),
    CONSENT_REQUIRED("선택한 서비스의 온라인 이용 동의가 필요합니다. 동의 후 연결을 확인해 주세요."),
    FAILED("연결을 확인하지 못했습니다. 서비스 상태를 확인하고 다시 시도해 주세요. 기존 키는 유지됩니다."),
    UNSUPPORTED("이 고급 서비스는 연결 확인을 지원하지 않습니다. 통역을 시작할 때 연결합니다."),
}

/** Only session setup; no microphone, source text, domain prompt, or inference request. */
internal class OnlineConnectionCheck(
    private val gemini: GeminiLiveTransport = GeminiLiveTransport(),
    private val openAi: RealtimeWire = KtorRealtimeWire(),
    private val openAiAudio: OpenAiAudioTransport = OpenAiAudioTransport(),
) {
    private class SetupComplete : CancellationException()
    suspend fun run(options: TranslationApiOptions, key: String, allowed: () -> Boolean): OnlineConnectionResult {
        if (options.provider !in setOf(TranslationApiProvider.GEMINI_LIVE, TranslationApiProvider.OPENAI_REALTIME)) return OnlineConnectionResult.UNSUPPORTED
        if (!allowed()) return OnlineConnectionResult.CONSENT_REQUIRED
        if (key.isBlank()) return OnlineConnectionResult.KEY_REQUIRED
        return try {
            withTimeout(8_000) {
                check(allowed())
                val connectionScope = this
                val revocation = launch {
                    while (isActive) {
                        delay(25)
                        if (!allowed()) connectionScope.cancel(CancellationException("Consent revoked"))
                    }
                }
                try {
                when (options.provider) {
                    TranslationApiProvider.GEMINI_LIVE -> gemini.run(key, options.model, "en", flow { awaitCancellation() }, allowed,
                        onReady = { check(allowed()); throw SetupComplete() }, onEvent = {}, tone = options.tone)
                    else -> if (options.realtimeAudio) openAiAudio.run(key, options.model, "ko", "en",
                        flow { awaitCancellation() }, allowed, onReady = { check(allowed()); throw SetupComplete() }, onEvent = {})
                    else openAi.exchangeModel(options.model, key, allowed) { socket ->
                        socket.send(JSONObject().put("type", "session.update").put("session", JSONObject()
                            .put("type", "realtime").put("model", options.model)
                            .put("output_modalities", org.json.JSONArray().put("text"))).toString())
                        repeat(32) {
                            check(allowed())
                            val raw = socket.receive(); requireBoundedJson(raw)
                            val event = JSONObject(raw)
                            if (event.optString("type") == "error") throw onlineProviderFailure(event)
                            if (event.optString("type") == "session.updated") { check(allowed()); throw SetupComplete() }
                        }
                        error("No session acknowledgement")
                    }
                }
                } finally { revocation.cancel() }
            }
            OnlineConnectionResult.FAILED
        } catch (_: SetupComplete) {
            if (allowed()) OnlineConnectionResult.READY else OnlineConnectionResult.CONSENT_REQUIRED
        } catch (_: TimeoutCancellationException) {
            OnlineConnectionResult.TIMED_OUT
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { onlineConnectionFailureResult(error) }
    }
}
