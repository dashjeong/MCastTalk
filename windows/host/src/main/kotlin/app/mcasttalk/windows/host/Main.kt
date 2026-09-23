package app.mcasttalk.windows.host

import java.awt.Desktop
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import io.ktor.server.cio.CIO
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import java.net.URI
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

fun main(args: Array<String>) {
    var dataRoot: Path? = null
    try {
        WindowsSocketTemp.configure()
        runHost(args) { dataRoot = it }
    } catch (_: AdminSetupCancelled) {
        System.err.println("MCastTalk administrator setup cancelled; the server was not started.")
    } catch (error: Throwable) {
        val diagnostic = dataRoot?.resolve("logs/startup-failure.log")
        runCatching {
            diagnostic?.let {
                Files.createDirectories(it.parent)
                Files.writeString(it, error.stackTraceToString())
            }
        }
        System.err.println("MCastTalk could not start: ${error.message}")
        if ("--no-browser" !in args && !GraphicsEnvironment.isHeadless()) {
            runCatching {
                SwingUtilities.invokeAndWait {
                    JOptionPane.showMessageDialog(
                        null,
                        "MCastTalk를 시작하지 못했습니다.\n${error.message}" +
                            (diagnostic?.let { "\n진단 파일: $it" } ?: ""),
                        "MCastTalk 시작 오류",
                        JOptionPane.ERROR_MESSAGE,
                    )
                }
            }
        }
        throw error
    }
}

private fun runHost(args: Array<String>, onDataRootResolved: (Path) -> Unit) {
    val parsed = HostConfig.parse(args)
    val hasExplicitDataRoot = args.any { it.startsWith("--data-dir=") }
    val packagedWorkspace = System.getProperty("jpackage.app-path")?.let(Path::of)?.parent?.resolve("workspace.txt")
        ?.takeIf(Files::isRegularFile)?.let { Path.of(Files.readString(it).removePrefix("\uFEFF").trim()).toAbsolutePath().normalize() }
    val dataRoot = if (hasExplicitDataRoot) {
        parsed.dataRoot
    } else if (packagedWorkspace != null) {
        packagedWorkspace
    } else {
        WorkspaceSetup.resolveOrPrompt(WorkspaceSetup.defaultRoot())
    }
    onDataRootResolved(dataRoot)
    val identity = WorkspaceSetup.initialize(dataRoot)
    BundledInference.configure(dataRoot)
    val config = NetworkSetup.configure(parsed.copy(dataRoot = dataRoot),
        args.any { it.startsWith("--bind=") || it.startsWith("--lan-hosts=") })
    AccountServices.open(dataRoot).use { accountServices ->
        AdminSetup.ensureAdministrator(accountServices.accounts)
        runConfiguredHost(config, identity, accountServices)
    }
}

private fun runConfiguredHost(
    config: HostConfig,
    identity: DataRootIdentity,
    accountServices: AccountServices,
) {
    val stopped = CountDownLatch(1)
    val engineFailure = AtomicReference<Throwable?>(null)
    val engineScope = CoroutineScope(
        SupervisorJob() + CoroutineExceptionHandler { _, error ->
            engineFailure.compareAndSet(null, error)
            stopped.countDown()
        }
    )
    val tls = if (config.lanHosts.isNotEmpty()) LanTls.openOrCreate(config.dataRoot, config.lanHosts) else null
    val server = if (tls != null) embeddedServer(Netty, configure = {
        // The strict origin gate uses HTTP/1.1 Host + Upgrade semantics. Do not negotiate
        // HTTP/2 until its :authority/scheme mapping has the same adversarial coverage.
        enableHttp2 = false
        sslConnector(tls.keyStore, "mcasttalk", { tls.password.toCharArray() }, { tls.password.toCharArray() }) {
            host = config.bindAddress
            port = config.port
        }
    }) {
        mcastTalkModule(config.dataRoot, accountServices = accountServices, lanHosts = config.lanHosts)
    } else engineScope.embeddedServer(
        factory = CIO,
        host = config.bindAddress,
        port = config.port,
    ) {
        mcastTalkModule(config.dataRoot, accountServices = accountServices)
    }
    server.monitor.subscribe(ApplicationStopped) { stopped.countDown() }
    val shutdownHook = Thread {
        try {
            server.stop(1_000, 5_000, TimeUnit.MILLISECONDS)
        } finally {
            stopped.countDown()
        }
    }
    Runtime.getRuntime().addShutdownHook(shutdownHook)
    try {
        val readyUri = startAndAwaitReady(server, identity.instanceId, engineFailure = engineFailure::get,
            readinessUri = if (tls != null) URI("https://localhost:${config.port}/") else null,
            sslContext = tls?.clientContext())
        if (
            config.launchBrowser &&
            !GraphicsEnvironment.isHeadless() &&
            Desktop.isDesktopSupported() &&
            Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)
        ) {
            runCatching { Desktop.getDesktop().browse(readyUri) }
        }
        stopped.await()
        engineFailure.get()?.let { throw IllegalStateException("MCastTalk server stopped unexpectedly", it) }
    } finally {
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
        try {
            server.stop(0, 1_000, TimeUnit.MILLISECONDS)
        } finally {
            engineScope.cancel()
        }
    }
}
