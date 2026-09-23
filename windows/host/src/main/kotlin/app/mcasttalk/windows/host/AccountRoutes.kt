package app.mcasttalk.windows.host

import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

internal const val SESSION_COOKIE_NAME = "mcasttalk_session"
private const val MAX_ACCOUNT_BODY_BYTES = 16 * 1024

internal class ApiFailure(val status: HttpStatusCode, val code: String, message: String) :
    IllegalArgumentException(message)

internal fun ApplicationCall.sessionToken(): String? {
    val candidates = request.headers.getAll(HttpHeaders.Cookie).orEmpty()
        .flatMap { it.split(';') }.map { it.trim() }
        .filter { it.substringBefore('=') == SESSION_COOKIE_NAME }
    if (candidates.size != 1) return null
    return candidates.single().substringAfter('=', "").takeIf { it.length in 32..128 }
}

internal suspend fun ApplicationCall.apiError(status: HttpStatusCode, code: String, message: String) {
    respondText(encodeJson(mapOf("error" to mapOf("code" to code, "message" to message))),
        ContentType.Application.Json, status)
}

internal fun accountJson(account: LocalAccount): Map<String, Any?> = linkedMapOf(
    "id" to account.id,
    "username" to account.username,
    "displayName" to account.displayName,
    "role" to account.role.name,
    "enabled" to account.enabled,
    "expiresAt" to account.expiresAt?.toString(),
    "guestRoomId" to account.guestRoomId,
    "createdAt" to account.createdAt.toString(),
)

internal class AccountRequestLimiter {
    private val requests = mutableMapOf<String, ArrayDeque<Long>>()

    @Synchronized
    fun allow(key: String, maximum: Int, periodMillis: Long = 60_000): Boolean {
        val now = System.nanoTime() / 1_000_000
        requests.entries.removeIf { it.value.lastOrNull()?.let { last -> now - last >= periodMillis } != false }
        if (requests.size >= 256 && key !in requests) return false
        val times = requests.getOrPut(key) { ArrayDeque() }
        while (times.firstOrNull()?.let { now - it >= periodMillis } == true) times.removeFirst()
        if (times.size >= maximum) return false
        times.addLast(now)
        return true
    }
}

internal fun Route.accountRoutes(services: AccountServices) {
    val limiter = AccountRequestLimiter()

    get("/api/v1/auth/session") {
        call.accountApi(services) {
            val actor = call.sessionToken()?.let { services.sessions.resolve(it, touch = false) }
            call.respondJson(mapOf("authenticated" to (actor != null), "user" to actor?.let(::accountJson)))
        }
    }
    post("/api/v1/auth/login") {
        call.accountApi(services, mutation = true) {
            if (!limiter.allow("login", 20)) throw ApiFailure(HttpStatusCode.TooManyRequests, "RATE_LIMITED", "Please try again later")
            val body = call.accountBody()
            val password = body.passwordChars("password")
            val actor = try { services.accounts.authenticate(body.requiredString("username"), password) }
                finally { password.fill('\u0000') }
            val token = actor?.let(services.sessions::issue)
                ?: throw ApiFailure(HttpStatusCode.Unauthorized, "INVALID_CREDENTIALS", "Unable to sign in with these credentials")
            // A cookie replacement also ends live sockets authenticated with the old
            // browser session; a failed sign-in must leave that session untouched.
            call.sessionToken()?.let(services.sessions::revoke)
            call.setSessionCookie(token)
            call.respondJson(mapOf("authenticated" to true, "user" to accountJson(checkNotNull(actor))))
        }
    }
    post("/api/v1/auth/logout") {
        call.accountApi(services, mutation = true) {
            call.accountBody()
            call.sessionToken()?.let(services.sessions::revoke)
            call.setSessionCookie("", clear = true)
            call.respondJson(mapOf("authenticated" to false, "user" to null))
        }
    }
    post("/api/v1/auth/password") {
        call.accountApi(services, mutation = true, requireActor = true) { actor ->
            if (!limiter.allow("password:${actor!!.id}", 10)) throw ApiFailure(HttpStatusCode.TooManyRequests, "RATE_LIMITED", "Please try again later")
            val body = call.accountBody()
            val current = body.passwordChars("currentPassword")
            val replacement = body.passwordChars("newPassword")
            try { services.accounts.changePassword(actor, current, replacement) }
            finally { current.fill('\u0000'); replacement.fill('\u0000') }
            call.setSessionCookie("", clear = true)
            call.respondJson(mapOf("ok" to true))
        }
    }
    get("/api/v1/admin/accounts") {
        call.accountApi(services, requireActor = true, requireAdmin = true) { actor ->
            call.respondJson(mapOf("accounts" to services.accounts.listAccounts(actor!!).map(::accountJson)))
        }
    }
    post("/api/v1/admin/accounts") {
        call.accountApi(services, mutation = true, requireActor = true, requireAdmin = true) { actor ->
            if (!limiter.allow("admin:${actor!!.id}", 60)) throw ApiFailure(HttpStatusCode.TooManyRequests, "RATE_LIMITED", "Please try again later")
            val body = call.accountBody()
            val password = body.passwordChars("password")
            val created = try {
                services.accounts.createAccount(
                    actor, body.requiredString("username"), body.requiredString("displayName"), password,
                    AccountRole.valueOf(body.requiredString("role")),
                    enabled = body.optionalBoolean("enabled") ?: true,
                    expiresAt = body.expiry(),
                    guestRoomId = body.optionalString("guestRoomId"),
                )
            } finally { password.fill('\u0000') }
            call.respondJson(mapOf("account" to accountJson(created)), HttpStatusCode.Created)
        }
    }
    post("/api/v1/admin/accounts/{accountId}/update") {
        call.accountApi(services, mutation = true, requireActor = true, requireAdmin = true) { actor ->
            if (!limiter.allow("admin:${actor!!.id}", 60)) throw ApiFailure(HttpStatusCode.TooManyRequests, "RATE_LIMITED", "Please try again later")
            val body = call.accountBody()
            val id = call.parameters["accountId"] ?: throw IllegalArgumentException("accountId is required")
            val current = services.accounts.listAccounts(actor).firstOrNull { it.id == id }
                ?: throw ApiFailure(HttpStatusCode.NotFound, "NOT_FOUND", "Account not found")
            val updated = services.accounts.updateAccount(
                actor, id, body.optionalString("displayName") ?: current.displayName,
                body.optionalString("role")?.let(AccountRole::valueOf) ?: current.role,
                body.optionalBoolean("enabled") ?: current.enabled,
                if (body.contains("expiresAt")) body.expiry() else current.expiresAt,
                if (body.contains("guestRoomId")) body.optionalString("guestRoomId") else current.guestRoomId,
            )
            call.respondJson(mapOf("account" to accountJson(updated)))
        }
    }
    post("/api/v1/admin/accounts/{accountId}/password") {
        call.accountApi(services, mutation = true, requireActor = true, requireAdmin = true) { actor ->
            if (!limiter.allow("admin:${actor!!.id}", 60)) throw ApiFailure(HttpStatusCode.TooManyRequests, "RATE_LIMITED", "Please try again later")
            val body = call.accountBody()
            val password = body.passwordChars("password")
            try {
                services.accounts.resetPassword(actor, call.parameters["accountId"] ?: "", password)
            } finally { password.fill('\u0000') }
            call.respondJson(mapOf("ok" to true))
        }
    }
}

internal suspend fun ApplicationCall.accountApi(
    services: AccountServices,
    mutation: Boolean = false,
    requireActor: Boolean = false,
    requireAdmin: Boolean = false,
    touchSession: Boolean = true,
    block: suspend (LocalAccount?) -> Unit,
) {
    try {
        if (!services.accounts.isInitialized()) throw ApiFailure(HttpStatusCode.ServiceUnavailable, "SETUP_REQUIRED", "Complete administrator setup in the Windows launcher")
        val hosts = request.headers.getAll(HttpHeaders.Host)
        val origins = request.headers.getAll(HttpHeaders.Origin)
        val checkedOrigins = if (!mutation && origins == null && hosts?.size == 1) {
            listOf("${request.local.scheme}://${hosts.single()}")
        } else origins
        if (!isTrustedWebSocketOrigin(hosts, checkedOrigins, request.local.scheme, request.local.localPort,
                application.attributes.getOrNull(allowedLanHostsKey).orEmpty())) {
            throw ApiFailure(HttpStatusCode.Forbidden, "FORBIDDEN", "Request origin is not allowed")
        }
        if (mutation && request.contentType().withoutParameters() != ContentType.Application.Json) {
            throw ApiFailure(HttpStatusCode.UnsupportedMediaType, "INVALID_CONTENT_TYPE", "Use application/json")
        }
        val actor = if (requireActor) sessionToken()?.let { services.sessions.resolve(it, touch = touchSession) } else null
        if (requireActor && actor == null) throw ApiFailure(HttpStatusCode.Unauthorized, "UNAUTHENTICATED", "Sign in is required")
        if (requireAdmin && actor?.role != AccountRole.ADMIN) throw ApiFailure(HttpStatusCode.Forbidden, "FORBIDDEN", "Administrator access is required")
        block(actor)
    } catch (error: ApiFailure) {
        apiError(error.status, error.code, error.message ?: "Request failed")
    } catch (_: TimeoutCancellationException) {
        apiError(HttpStatusCode.RequestTimeout, "REQUEST_TIMEOUT", "Request body was not received in time")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SecurityException) {
        apiError(HttpStatusCode.Forbidden, "FORBIDDEN", "This operation is not permitted")
    } catch (_: AccountOperationBusyException) {
        apiError(HttpStatusCode.ServiceUnavailable, "BUSY", "Please try again later")
    } catch (error: IllegalArgumentException) {
        apiError(HttpStatusCode.BadRequest, "INVALID_REQUEST", error.message ?: "Invalid request")
    } catch (_: Exception) {
        apiError(HttpStatusCode.InternalServerError, "INTERNAL_ERROR", "The account operation could not be completed")
    }
}

internal suspend fun ApplicationCall.accountBody(): FlatJsonObject = withTimeout(5_000) {
    val declared = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (declared != null && declared > MAX_ACCOUNT_BODY_BYTES) {
        throw ApiFailure(HttpStatusCode.PayloadTooLarge, "BODY_TOO_LARGE", "Request body is too large")
    }
    val channel = receiveChannel()
    val bytes = ByteArrayOutputStream()
    val buffer = ByteArray(2_048)
    while (true) {
        val count = channel.readAvailable(buffer, 0, buffer.size)
        if (count < 0) break
        if (bytes.size() + count > MAX_ACCOUNT_BODY_BYTES) {
            channel.cancel(null)
            throw ApiFailure(HttpStatusCode.PayloadTooLarge, "BODY_TOO_LARGE", "Request body is too large")
        }
        bytes.write(buffer, 0, count)
    }
    val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    val text = try { decoder.decode(ByteBuffer.wrap(bytes.toByteArray())).toString() }
        catch (_: Exception) { throw IllegalArgumentException("Request body must be valid UTF-8") }
    parseFlatJsonObject(text, MAX_ACCOUNT_BODY_BYTES)
}

private fun ApplicationCall.setSessionCookie(token: String, clear: Boolean = false) {
    response.cookies.append(Cookie(
        name = SESSION_COOKIE_NAME, value = token, encoding = CookieEncoding.RAW,
        path = "/", httpOnly = true, secure = request.local.scheme == "https",
        maxAge = if (clear) 0 else null,
        extensions = mapOf("SameSite" to "Strict"),
    ))
}

private suspend fun ApplicationCall.respondJson(value: Any?, status: HttpStatusCode = HttpStatusCode.OK) =
    respondText(encodeJson(value), ContentType.Application.Json, status)

private fun FlatJsonObject.passwordChars(name: String): CharArray =
    (optionalString(name) ?: throw IllegalArgumentException("$name is required")).toCharArray()

private fun FlatJsonObject.expiry(): Instant? = optionalString("expiresAt")?.let {
    try { Instant.parse(it) } catch (_: Exception) { throw IllegalArgumentException("expiresAt must be an ISO-8601 timestamp") }
}
