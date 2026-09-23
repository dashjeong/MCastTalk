package app.mcasttalk.windows.host

import java.awt.GraphicsEnvironment
import java.net.NetworkInterface
import java.nio.file.Files
import java.util.Properties
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

/** Local operator selection only. Does not install trust or open firewall ports. */
internal object NetworkSetup {
    fun configure(config: HostConfig, explicitNetwork: Boolean): HostConfig {
        if (explicitNetwork) return config
        val file = config.dataRoot.resolve("config/network.properties")
        if (Files.exists(file)) {
            val p=Properties().apply { Files.newBufferedReader(file).use { load(it) } }
            val hosts=p.getProperty("lanHosts", "").split(',').filter(String::isNotBlank).toSet()
            return config.copy(bindAddress=if(hosts.isEmpty()) "127.0.0.1" else "0.0.0.0",lanHosts=hosts)
        }
        if (!config.launchBrowser || GraphicsEnvironment.isHeadless()) return config
        val addresses=NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.map { it.hostAddress }.filter(::isPrivateIpv4).distinct()
        var selection: Any? = null
        SwingUtilities.invokeAndWait {
            val options=arrayOf("이 PC에서만 사용") + addresses.map { "LAN HTTPS · $it" }
            selection=JOptionPane.showInputDialog(null,
                "회의 접속 범위를 선택하세요.\nLAN 사용 시 참가 기기에 인증서 신뢰 설정과 방화벽 허용이 필요합니다.\n프로그램은 OS 신뢰 저장소·방화벽을 자동 변경하지 않습니다.",
                "MCastTalk 네트워크 설정",JOptionPane.QUESTION_MESSAGE,null,options,options.first())
        }
        if (selection == null) throw AdminSetupCancelled()
        val address=addresses.firstOrNull { selection == "LAN HTTPS · $it" }
        Files.writeString(file,"lanHosts=${address.orEmpty()}\n")
        return config.copy(bindAddress=if(address==null) "127.0.0.1" else "0.0.0.0",lanHosts=setOfNotNull(address))
    }
}
