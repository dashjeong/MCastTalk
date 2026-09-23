package app.mcasttalk.windows.host

import java.nio.file.Paths
import java.time.Instant

/** Test-source-only fixture. It is NOT in host.jar or an installed executable. */
object AccountFixtureMain {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1) { "Supply a fresh test workspace below source/.run" }
        val root = Paths.get(args.single()).toAbsolutePath().normalize()
        val allowed = Paths.get(System.getProperty("user.dir"), ".run").toAbsolutePath().normalize()
        require(root.startsWith(allowed) && root != allowed) { "Test workspace must be below the checkout's .run directory" }
        val password = System.getenv("MCASTTALK_TEST_PASSWORD")?.toCharArray()
            ?: error("Set MCASTTALK_TEST_PASSWORD for this test process; no default password exists")
        try {
            WorkspaceSetup.initialize(root)
            LocalAccounts(root).use { accounts ->
                check(!accounts.isInitialized()) { "Refusing to overwrite an initialized account store" }
                val admin = accounts.createInitialAdmin("smoke-admin", "Test administrator", password)
                for (name in listOf("Alice", "Bob", "Carol", "Other", "Mallory")) {
                    accounts.createAccount(admin, name.lowercase(), name, password, AccountRole.USER)
                }
                for (username in listOf("twin-one", "twin-two")) {
                    accounts.createAccount(admin, username, "동명이인", password, AccountRole.USER)
                }
                accounts.createAccount(
                    admin, "test-guest", "Test guest", password, AccountRole.GUEST,
                    expiresAt = Instant.now().plusSeconds(3600), guestRoomId = "browser-smoke",
                )
            }
            println("Fresh test accounts initialized using the real account store; credentials omitted.")
        } finally {
            password.fill('\u0000')
        }
    }
}
