package app.guidecast.transmitter

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

internal fun filterPlaybackApps(apps: List<PlaybackTargetApp>, query: String): List<PlaybackTargetApp> {
    val words = query.trim().split(Regex("\\s+")).filter(String::isNotBlank)
    return apps.filter { app -> words.all { word ->
        app.label.contains(word, ignoreCase = true) || app.packageName.contains(word, ignoreCase = true)
    } }
}

@Composable
internal fun PlaybackAppPicker(apps: List<PlaybackTargetApp>, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(apps, query) { filterPlaybackApps(apps, query) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).imePadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("출력 앱 검색", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text("앱 이름 또는 패키지명") }, modifier = Modifier.fillMaxWidth(),
                    trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("지우기") } })
                if (filtered.isEmpty()) Text("검색 결과가 없습니다.")
                LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth()) {
                    items(filtered, key = { it.packageName }) { app ->
                        TextButton(onClick = { onSelect(app.packageName) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(app.label, style = MaterialTheme.typography.titleMedium)
                                Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("닫기") }
            }
        }
    }
}
