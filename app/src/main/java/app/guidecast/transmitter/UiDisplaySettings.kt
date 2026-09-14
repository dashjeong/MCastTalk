package app.guidecast.transmitter

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Display preference only: diagnostic collection/export and engine operation do not depend on it. */
class UiDisplaySettings internal constructor(private val store: UiDisplayPreferenceStore) {
    constructor(context: Context) : this(AndroidUiDisplayPreferenceStore(context.applicationContext))

    private val mutableDeveloperInfo = MutableStateFlow(store.readDeveloperInfo())
    val developerInfo = mutableDeveloperInfo.asStateFlow()

    fun setDeveloperInfo(enabled: Boolean) {
        store.writeDeveloperInfo(enabled)
        mutableDeveloperInfo.value = enabled
    }
}

internal interface UiDisplayPreferenceStore {
    fun readDeveloperInfo(): Boolean
    fun writeDeveloperInfo(enabled: Boolean)
}

private class AndroidUiDisplayPreferenceStore(context: Context) : UiDisplayPreferenceStore {
    private val preferences = context.getSharedPreferences("ui_display", Context.MODE_PRIVATE)
    override fun readDeveloperInfo() = preferences.getBoolean("developer_info", false)
    override fun writeDeveloperInfo(enabled: Boolean) {
        preferences.edit().putBoolean("developer_info", enabled).apply()
    }
}

val LocalDeveloperInfo = staticCompositionLocalOf { false }
internal val LocalUiDisplaySettings = staticCompositionLocalOf<UiDisplaySettings?> { null }

@Composable
internal fun DeveloperInformationSettings() {
    val settings = LocalUiDisplaySettings.current
    val checked = LocalDeveloperInfo.current
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().toggleable(
                value = checked, enabled = settings != null, role = Role.Switch,
                onValueChange = { settings?.setDeveloperInfo(it) },
            ).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("개발자 정보 표시", style = MaterialTheme.typography.titleMedium)
                Text("처리 시간과 상세 진단 수치를 표시합니다. 기본 화면은 음성과 스크립트 중심으로 표시됩니다.",
                    style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = checked, onCheckedChange = null, enabled = settings != null)
        }
    }
}
