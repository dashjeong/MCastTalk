package app.mcasttalk.windows.host

import java.awt.GraphicsEnvironment
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JTextField
import javax.swing.SwingUtilities

class AdminSetupCancelled : IllegalStateException("Administrator setup was cancelled; the server was not started")

internal class InitialAdminCredentials(
    val username: String,
    val displayName: String,
    val password: CharArray,
) {
    // Intentionally no generated data-class toString/copy containing credentials.
    fun clear() = password.fill('\u0000')
}

object AdminSetup {
    /** No default credential and no HTTP bootstrap endpoint: the installer user chooses locally. */
    fun ensureAdministrator(accounts: LocalAccounts) = ensureAdministrator(
        initialized = accounts::isInitialized,
        requestCredentials = {
            check(!GraphicsEnvironment.isHeadless()) {
                "Administrator setup is required. Launch MCastTalk interactively to choose the first administrator."
            }
            requestCredentials()
        },
        createAdministrator = { credentials ->
            accounts.createInitialAdmin(credentials.username, credentials.displayName, credentials.password)
        },
        onInvalidInput = {
            SwingUtilities.invokeAndWait {
                JOptionPane.showMessageDialog(
                    null,
                    "관리자 계정 정보를 확인해 주세요. 아이디는 영문·숫자 등 허용 문자 3~32자, 비밀번호는 ${PasswordHasher.MIN_LENGTH}~${PasswordHasher.MAX_LENGTH}자입니다.",
                    "MCastTalk 관리자 설정",
                    JOptionPane.WARNING_MESSAGE,
                )
            }
        },
    )

    internal fun ensureAdministrator(
        initialized: () -> Boolean,
        requestCredentials: () -> InitialAdminCredentials?,
        createAdministrator: (InitialAdminCredentials) -> Unit,
        onInvalidInput: () -> Unit,
    ) {
        while (!initialized()) {
            val credentials = requestCredentials() ?: throw AdminSetupCancelled()
            try {
                createAdministrator(credentials)
                check(initialized()) { "Administrator setup did not initialize the account store" }
                return
            } catch (_: IllegalArgumentException) {
                onInvalidInput()
            } finally {
                credentials.clear()
            }
        }
    }

    private fun requestCredentials(): InitialAdminCredentials? {
        var result: InitialAdminCredentials? = null
        SwingUtilities.invokeAndWait {
            val username = JTextField(24)
            val displayName = JTextField(24)
            val password = JPasswordField(24)
            val confirmation = JPasswordField(24)
            val panel = JPanel(GridBagLayout())
            fun row(index: Int, label: String, input: java.awt.Component) {
                panel.add(JLabel(label), GridBagConstraints().apply {
                    gridx = 0; gridy = index; anchor = GridBagConstraints.LINE_START
                    insets = Insets(6, 0, 6, 12)
                })
                panel.add(input, GridBagConstraints().apply {
                    gridx = 1; gridy = index; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL
                    insets = Insets(6, 0, 6, 0)
                })
            }
            panel.add(JLabel("<html>이 작업 공간의 첫 관리자 계정을 직접 지정하세요.<br>기본 계정·기본 비밀번호는 없으며, 서버 시작 전에 설정해야 합니다.<br>비밀번호는 ${PasswordHasher.MIN_LENGTH}~${PasswordHasher.MAX_LENGTH}자입니다. 공백도 사용할 수 있습니다.</html>"), GridBagConstraints().apply {
                gridx = 0; gridy = 0; gridwidth = 2; anchor = GridBagConstraints.LINE_START
                insets = Insets(0, 0, 12, 0)
            })
            row(1, "관리자 아이디", username)
            row(2, "표시 이름", displayName)
            row(3, "비밀번호", password)
            row(4, "비밀번호 확인", confirmation)
            password.accessibleContext.accessibleName = "관리자 비밀번호"
            confirmation.accessibleContext.accessibleName = "관리자 비밀번호 확인"
            while (true) {
                val choice = JOptionPane.showConfirmDialog(
                    null, panel, "MCastTalk 최초 관리자 설정",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE,
                )
                val secret = password.password
                val repeated = confirmation.password
                password.text = ""
                confirmation.text = ""
                try {
                    if (choice != JOptionPane.OK_OPTION) break
                    if (!secret.contentEquals(repeated)) {
                        JOptionPane.showMessageDialog(null, "비밀번호가 일치하지 않습니다. 다시 입력해 주세요.")
                        continue
                    }
                    result = InitialAdminCredentials(username.text.trim(), displayName.text.trim(), secret.copyOf())
                    break
                } finally {
                    secret.fill('\u0000')
                    repeated.fill('\u0000')
                }
            }
        }
        return result
    }
}
