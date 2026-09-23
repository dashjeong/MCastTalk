package app.mcasttalk.windows.host

import java.nio.file.Path

internal class TestAccountFixture(root: Path) : AutoCloseable {
    val services = AccountServices.open(root)
    val admin: LocalAccount = services.accounts.createInitialAdmin("test-admin", "Test Administrator", PASSWORD.toCharArray())
    private val accountsByDisplayName = mutableMapOf<String, LocalAccount>()

    fun account(displayName: String): LocalAccount = accountsByDisplayName.getOrPut(displayName) {
        val username = "user-" + accountsByDisplayName.size
        services.accounts.createAccount(admin, username, displayName, PASSWORD.toCharArray(), AccountRole.USER)
    }

    fun cookie(displayName: String = "Alice"): String =
        "$SESSION_COOKIE_NAME=" + checkNotNull(services.sessions.issue(account(displayName)))

    fun adminCookie(): String = "$SESSION_COOKIE_NAME=" + checkNotNull(services.sessions.issue(admin))

    override fun close() { services.close() }

    companion object { const val PASSWORD = "Test-only password 9!" }
}
