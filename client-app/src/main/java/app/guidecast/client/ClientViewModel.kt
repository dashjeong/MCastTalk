package app.guidecast.client

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.StateFlow

class ClientViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as GuideCastClientApplication
    private val controller = app.sessionController
    val state: StateFlow<ClientUiState> = controller.state

    fun updateAddress(value: String) = controller.updateAddress(value)
    fun updatePin(value: String) = controller.updatePin(value)
    fun selectTarget(target: ClientTargetLanguage) = controller.selectTarget(target)
    fun prepareModels() = controller.prepareSelectedModels()

    fun start() = ClientListeningService.start(getApplication(), state.value)

    fun pauseOrResume() {
        if (state.value.phase == ClientSessionPhase.PAUSED) {
            ClientListeningService.resume(getApplication())
        } else {
            ClientListeningService.pause(getApplication())
        }
    }

    fun stop() = ClientListeningService.stop(getApplication())

    fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "guidecast-client" || uri.host != "connect") return
        uri.getQueryParameter("url")?.takeIf(String::isNotBlank)?.let(::updateAddress)
        uri.getQueryParameter("pin")?.let(::updatePin)
        uri.getQueryParameter("target")?.let { selectTarget(ClientTargetLanguage.fromId(it)) }
    }
}

fun buildClientDeepLink(address: String, target: ClientTargetLanguage): Uri =
    Uri.Builder()
        .scheme("guidecast-client")
        .authority("connect")
        .appendQueryParameter("url", address)
        .appendQueryParameter("target", target.name)
        .build()
