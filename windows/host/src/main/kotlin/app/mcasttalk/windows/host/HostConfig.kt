package app.mcasttalk.windows.host

import java.nio.file.Path
import java.nio.file.Paths

data class HostConfig(
    val dataRoot: Path,
    val bindAddress: String = "127.0.0.1",
    val port: Int = 8787,
    val launchBrowser: Boolean = true,
    val lanHosts: Set<String> = emptySet(),
) {
    init {
        require(port in 1..65535) { "port must be between 1 and 65535" }
        require(lanHosts.all(::isPrivateIpv4)) { "LAN hosts must be private IPv4 addresses" }
        require(bindAddress in setOf("127.0.0.1", "::1", "localhost") ||
            (lanHosts.isNotEmpty() && (bindAddress == "0.0.0.0" || bindAddress in lanHosts))) {
            "Non-loopback binding requires explicit --lan-hosts and HTTPS"
        }
    }

    companion object {
        fun parse(args: Array<String>): HostConfig {
            var dataRoot: Path? = null
            var bindAddress = "127.0.0.1"
            var port = 8787
            var launchBrowser = true
            var lanHosts = emptySet<String>()
            for (argument in args) {
                when {
                    argument.startsWith("--data-dir=") ->
                        dataRoot = Paths.get(argument.substringAfter("=")).toAbsolutePath().normalize()
                    argument.startsWith("--bind=") ->
                        bindAddress = argument.substringAfter("=")
                    argument.startsWith("--port=") ->
                        port = argument.substringAfter("=").toIntOrNull()
                            ?: throw IllegalArgumentException("Invalid port")
                    argument == "--no-browser" -> launchBrowser = false
                    argument.startsWith("--lan-hosts=") -> lanHosts = argument.substringAfter("=").split(',').toSet()
                    else -> throw IllegalArgumentException("Unknown argument: $argument")
                }
            }
            val resolvedDataRoot =
                dataRoot ?: Paths.get(System.getProperty("user.dir"), "MCastTalkData")
                    .toAbsolutePath()
                    .normalize()
            return HostConfig(
                dataRoot = resolvedDataRoot,
                bindAddress = bindAddress,
                port = port,
                launchBrowser = launchBrowser,
                lanHosts = lanHosts,
            )
        }
    }
}

internal fun isPrivateIpv4(value: String): Boolean {
    val parts = value.split('.')
    if (parts.size != 4 || parts.any { it.toIntOrNull() !in 0..255 || it != it.toIntOrNull().toString() }) return false
    val octets = parts.map(String::toInt)
    return octets[0] == 10 || (octets[0] == 172 && octets[1] in 16..31) ||
        (octets[0] == 192 && octets[1] == 168)
}
