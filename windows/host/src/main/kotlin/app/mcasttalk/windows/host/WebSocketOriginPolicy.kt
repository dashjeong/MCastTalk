package app.mcasttalk.windows.host

import java.net.URI
import java.util.Locale
import io.ktor.util.AttributeKey

internal val allowedLanHostsKey = AttributeKey<Set<String>>("mcasttalk.lan.hosts")

/** Direct loopback browser connections only; forwarded headers are never trusted. */
fun isTrustedWebSocketOrigin(
    hostHeaders: List<String>?,
    originHeaders: List<String>?,
    localScheme: String,
    localPort: Int,
    allowedLanHosts: Set<String> = emptySet(),
): Boolean {
    if (hostHeaders?.size != 1 || originHeaders?.size != 1) return false
    val scheme = localScheme.lowercase(Locale.ROOT)
    if (scheme !in setOf("http", "https") || localPort !in 1..65535) return false
    val host = parseOrigin("$scheme://${hostHeaders.single()}") ?: return false
    val origin = parseOrigin(originHeaders.single()) ?: return false
    val loopbackNames = setOf("localhost", "127.0.0.1", "[::1]")
    val allowed = host.host.lowercase(Locale.ROOT) in loopbackNames ||
        (scheme == "https" && host.host in allowedLanHosts)
    return allowed &&
        origin.host.equals(host.host, ignoreCase = true) &&
        origin.scheme.equals(scheme, ignoreCase = true) &&
        effectivePort(host) == localPort && effectivePort(origin) == localPort
}

private fun parseOrigin(value: String): URI? = runCatching {
    // Serialized browser Origin has no path, credentials, query or fragment.
    require(value.none { it.isWhitespace() || it.code < 0x20 })
    val uri = URI(value)
    require(uri.isAbsolute && !uri.isOpaque && uri.host != null && uri.rawUserInfo == null)
    require(uri.rawPath.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null)
    require(uri.port == -1 || uri.port in 1..65535)
    uri
}.getOrNull()

private fun effectivePort(uri: URI): Int =
    if (uri.port >= 0) uri.port else if (uri.scheme.equals("https", true)) 443 else 80
