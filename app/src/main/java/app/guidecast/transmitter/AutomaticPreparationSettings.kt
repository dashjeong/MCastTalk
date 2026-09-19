package app.guidecast.transmitter

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role

@Composable
internal fun AutomaticPreparationSettings(settings: OperatorSettings) {
    val options by settings.state.collectAsState()
    Column {
        Row(Modifier.fillMaxWidth().toggleable(options.automaticPreparation, role = Role.Switch,
            onValueChange = settings::setAutomaticPreparation)) {
            Text("선택 언어 자동 준비", Modifier.weight(1f))
            Switch(options.automaticPreparation, onCheckedChange = null)
        }
        Text("저장된 언어 자료를 재사용하고 누락된 자료만 받습니다. 새 자료는 인터넷·저장 공간이 필요합니다. 입력·방송·파일 사용 중에는 준비를 미루며, 준비 중지를 누르면 자동 준비도 꺼집니다.")
    }
}
