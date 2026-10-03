package app.guidecast.transmitter

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/** Whole-row selection, one accessibility target, wrapping labels, no fixed text height. */
@Composable
internal fun ServiceExperienceChoices(
    title: String,
    choices: List<ExperienceChoice>,
    selectedId: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.forEach { choice ->
                val selected = selectedId == choice.id
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .selectable(selected, enabled = enabled, role = Role.RadioButton) {
                                if (!selected) onSelect(choice.id)
                            }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(selected, onClick = null, enabled = enabled)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(choice.title, style = MaterialTheme.typography.titleSmall)
                            Text(choice.description, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ServiceExperienceSummary(options: TranslationApiOptions) {
    val experience = serviceExperience(options)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(experience.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        if (options.provider != TranslationApiProvider.LOCAL) Text("선택 모델 · ${options.model}", style = MaterialTheme.typography.bodyMedium)
        Text(experience.processing, style = MaterialTheme.typography.bodyMedium)
        Text(experience.transmitted, style = MaterialTheme.typography.bodyMedium)
        experience.limitation?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
internal fun ServiceModelPicker(options: TranslationApiOptions, enabled: Boolean, onChange: (TranslationApiOptions) -> Unit) {
    val models = serviceModelChoices(options)
    if (models.isNotEmpty()) ServiceExperienceChoices(
        title = "사용할 통역 모델",
        choices = models.map { ExperienceChoice(it.id, it.title, it.description) },
        selectedId = options.model,
        enabled = enabled,
    ) { id -> models.firstOrNull { it.id == id }?.let { onChange(applyServiceModelChoice(options, it)) } }
}

/** Checkbox/switch labels and control share the same focus and click target. */
@Composable
internal fun ServiceExperienceToggle(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodyMedium)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** Announce meaningful state changes once; never attach liveRegion to a token counter. */
@Composable
internal fun ServiceConnectionFeedback(message: String, checking: Boolean = false) {
    Text(
        message,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth().semantics {
            liveRegion = LiveRegionMode.Polite
            stateDescription = if (checking) "연결 확인 중" else "연결 상태 안내"
        },
    )
}
