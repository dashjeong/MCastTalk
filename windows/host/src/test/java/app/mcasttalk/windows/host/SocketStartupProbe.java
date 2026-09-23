package app.mcasttalk.windows.host;

import java.nio.channels.Selector;

/** Runs in an isolated JVM so UnixDomainSockets has not cached a prior test's temp path. */
public final class SocketStartupProbe {
    public static void main(String[] args) throws Exception {
        WindowsSocketTemp.INSTANCE.configure();
        System.out.println("SOCKET_TEMP=" + System.getProperty("jdk.net.unixdomain.tmpdir"));
        try (var selector = Selector.open()) {
            System.out.println("SELECTOR_OPEN=" + selector.isOpen());
        }
    }
}
