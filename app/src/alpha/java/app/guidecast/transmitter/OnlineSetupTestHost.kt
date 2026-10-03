package app.guidecast.transmitter

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.Keep
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier

/** Instrumentation-only fixture host in the alpha variant. No exported activity or intent entry. */
@Keep
internal object OnlineSetupTestHost {
    fun show(activity: ComponentActivity, settings: TranslationApiSettings,
        check: suspend (TranslationApiOptions) -> OnlineConnectionResult) {
        activity.setContent {
            MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
                TranslationApiPanel(settings, TranslationApiService(settings), true, checkConnection = check)
            } }
        }
    }
}
