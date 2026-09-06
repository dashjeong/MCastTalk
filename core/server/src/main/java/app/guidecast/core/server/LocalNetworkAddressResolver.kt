package app.guidecast.core.server

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object LocalNetworkAddressResolver {
    data class Candidate(
        val interfaceName: String,
        val address: Inet4Address,
        val score: Int,
    )

    fun resolve(): Candidate? = runCatching {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .asSequence()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { network ->
                Collections.list(network.inetAddresses)
                    .asSequence()
                    .filterIsInstance<Inet4Address>()
                    .filter(::isPrivateAddress)
                    .map { address ->
                        Candidate(network.name, address, score(network.name, address))
                    }
            }
            .maxByOrNull(Candidate::score)
    }.getOrNull()

    private fun score(interfaceName: String, address: Inet4Address): Int {
        val normalized = interfaceName.lowercase()
        val interfaceScore = when {
            normalized.startsWith("swlan") || normalized.startsWith("ap") -> 100
            normalized.startsWith("wlan") || normalized.startsWith("wifi") -> 80
            normalized.startsWith("eth") -> 60
            else -> 20
        }
        val gatewayScore = if (address.address.last().toInt() and 0xff == 1) 10 else 0
        return interfaceScore + gatewayScore
    }

    private fun isPrivateAddress(address: Inet4Address): Boolean {
        val bytes = address.address.map { it.toInt() and 0xff }
        return bytes[0] == 10 ||
            (bytes[0] == 172 && bytes[1] in 16..31) ||
            (bytes[0] == 192 && bytes[1] == 168)
    }
}
