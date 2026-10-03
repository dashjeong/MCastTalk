package app.guidecast.transmitter

import io.ktor.client.plugins.ResponseException
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.TimeoutCancellationException
import org.json.JSONObject

/** Static categories only: provider bodies/messages, credentials and URLs never reach the UI. */
internal class OnlineProviderFailure(val result: OnlineConnectionResult) : Exception(result.name)

internal fun onlineHttpFailure(status: Int): OnlineConnectionResult = when (status) {
    401 -> OnlineConnectionResult.KEY_REJECTED
    403 -> OnlineConnectionResult.ACCESS_DENIED
    404 -> OnlineConnectionResult.MODEL_UNAVAILABLE
    408, 504 -> OnlineConnectionResult.TIMED_OUT
    429 -> OnlineConnectionResult.LIMIT_REACHED
    in 500..599 -> OnlineConnectionResult.SERVICE_UNAVAILABLE
    else -> OnlineConnectionResult.FAILED
}

internal fun onlineProviderFailure(root: JSONObject): OnlineProviderFailure {
    val error = root.optJSONObject("error") ?: return OnlineProviderFailure(OnlineConnectionResult.FAILED)
    val code = error.optString("code")
    val status = error.optString("status")
    val details = error.optJSONArray("details")
    val reasons = if (details != null && details.length() <= 16) (0 until details.length()).mapNotNull { index ->
        details.optJSONObject(index)?.takeIf {
            it.optString("@type") == "type.googleapis.com/google.rpc.ErrorInfo"
        }?.optString("reason")
    } else emptyList()
    val result = when {
        reasons.any { it in setOf("API_KEY_INVALID", "API_KEY_EXPIRED") } -> OnlineConnectionResult.KEY_REJECTED
        reasons.any { it in setOf("API_KEY_SERVICE_BLOCKED", "API_KEY_HTTP_REFERRER_BLOCKED", "API_KEY_IP_ADDRESS_BLOCKED") } -> OnlineConnectionResult.ACCESS_DENIED
        code in setOf("invalid_api_key", "authentication_error") || status == "UNAUTHENTICATED" -> OnlineConnectionResult.KEY_REJECTED
        code in setOf("permission_denied", "permission_error") || status == "PERMISSION_DENIED" -> OnlineConnectionResult.ACCESS_DENIED
        code == "model_not_found" || status == "NOT_FOUND" -> OnlineConnectionResult.MODEL_UNAVAILABLE
        code in setOf("rate_limit_exceeded", "insufficient_quota", "rate_limit_error") || status == "RESOURCE_EXHAUSTED" -> OnlineConnectionResult.LIMIT_REACHED
        status == "DEADLINE_EXCEEDED" -> OnlineConnectionResult.TIMED_OUT
        status in setOf("UNAVAILABLE", "INTERNAL") -> OnlineConnectionResult.SERVICE_UNAVAILABLE
        else -> onlineHttpFailure(error.optInt("code", 0))
    }
    return OnlineProviderFailure(result)
}

internal fun onlineConnectionFailureResult(error: Throwable): OnlineConnectionResult {
    var current: Throwable? = error
    repeat(6) {
        val failure = current ?: return OnlineConnectionResult.FAILED
        when (failure) {
            is OnlineProviderFailure -> return failure.result
            is ResponseException -> return onlineHttpFailure(failure.response.status.value)
            is TimeoutCancellationException, is SocketTimeoutException -> return OnlineConnectionResult.TIMED_OUT
            is IOException -> return OnlineConnectionResult.NETWORK_UNAVAILABLE
        }
        current = failure.cause
    }
    return OnlineConnectionResult.FAILED
}
