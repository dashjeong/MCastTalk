package app.guidecast.transmitter

import androidx.compose.material3.Text
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

@Composable
internal fun GoogleTranslationAttribution() {
    Image(
        painter = painterResource(app.guidecast.provider.mlkit.translation.R.drawable.powered_by_google_translate),
        contentDescription = "Powered by Google Translate",
        modifier = Modifier.background(Color.White).padding(4.dp).width(176.dp),
    )
}

@Composable
internal fun TranslationProviderAttribution(provider: String?) {
    if (provider == "ML_KIT_PARTIAL") Text("일부 구간 번역")
    if (provider == "ML_KIT" || provider == "ML_KIT_PARTIAL") GoogleTranslationAttribution()
}
