package app.guidecast.transmitter

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import app.guidecast.core.translation.TranslationStyle

internal data class InterpretationToneSupport(val supported: Boolean, val explanation: String? = null)

internal fun interpretationToneSupport(options: TranslationApiOptions, local: OperatorOptions): InterpretationToneSupport = when {
    options.provider == TranslationApiProvider.GEMINI_LIVE && options.model != GEMINI_LIVE_AGENT ->
        InterpretationToneSupport(false, "이 음성 통역에서는 말투를 고를 수 없습니다.")
    options.provider == TranslationApiProvider.LOCAL && !local.useGemma ->
        InterpretationToneSupport(false, "휴대폰 기본 번역에서는 말투를 고를 수 없습니다.")
    options.provider == TranslationApiProvider.LOCAL && local.selectiveRefinement ->
        InterpretationToneSupport(true, "일부 문장에만 고른 말투가 적용됩니다.")
    else -> InterpretationToneSupport(true)
}

internal val interpretationToneChoices = listOf(
    ExperienceChoice("CONVERSATIONAL", "편하게(대화하듯)", "상황에 맞는 존댓말로 자연스럽게 전달합니다."),
    ExperienceChoice("FORMAL", "격식 있게", "격식을 갖추되 듣기 쉬운 말로 전달합니다."),
)

@Composable
internal fun InterpretationToneSettings(settings: TranslationApiSettings, options: TranslationApiOptions, enabled: Boolean) {
    val app = LocalContext.current.applicationContext as GuideCastApplication
    val local by app.operatorSettings.state.collectAsState()
    val support = interpretationToneSupport(options, local)
    if (support.supported) {
        ServiceExperienceChoices("말투", interpretationToneChoices, options.tone.name, enabled) {
            settings.setTone(TranslationStyle.valueOf(it))
        }
        if (options.tone == TranslationStyle.AUTO)
            Text("이전에 고른 자동 말투를 유지합니다. 위에서 바꿀 수 있습니다.", style = MaterialTheme.typography.bodySmall)
    }
    support.explanation?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    if (!support.supported && options.provider == TranslationApiProvider.GEMINI_LIVE) {
        TextButton(enabled = enabled, onClick = { settings.selectModel(GEMINI_LIVE_AGENT) }) {
            Text("방식 바꾸기")
        }
    }
}
