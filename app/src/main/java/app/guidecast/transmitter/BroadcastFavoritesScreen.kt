package app.guidecast.transmitter

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 한 줄만 차지한다. 관리 · 저장 단추는 고정이고 별칭 칩만 가로로 넘겨 본다. */
@Composable
internal fun BroadcastFavoritesStrip(store: BroadcastFavorites, current: BroadcastFavoriteSetup,
    enabled: Boolean, loading: Boolean = false, onApply: (BroadcastFavorite) -> Unit, onNotice: (String) -> Unit) {
    val items by store.state.collectAsState()
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    var duplicateId by rememberSaveable { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val selected = items.firstOrNull { it.id == selectedId }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = { dialog = "manage" }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (loading) "불러오는 중…" else "즐겨찾기 ▾") }
        // 별칭 칩만 가로로 넘긴다. 별칭이 길거나 많아도 "지금 설정 저장"은 늘 보인다.
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items.forEach { item ->
                SuggestionChip(onClick = { if (enabled) onApply(item) }, enabled = enabled,
                    label = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.widthIn(max = 160.dp).heightIn(min = 48.dp))
            }
        }
        TextButton(enabled = enabled, onClick = {
            if (items.size >= 6) onNotice("즐겨찾기는 6개까지 저장할 수 있어요. 관리에서 덮어쓰거나 지워 주세요")
            else { val proposal = suggestedFavoriteAlias(current); name = TextFieldValue(proposal, TextRange(0, proposal.length)); dialog = "save" }
        }, modifier = Modifier.heightIn(min = 48.dp)) { Text("지금 설정 저장") }
    }
    if (dialog == "manage") AlertDialog(onDismissRequest = { dialog = null }, title = { Text("즐겨찾기 관리") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            if (items.isEmpty()) Text("자주 쓰는 설정을 저장해 보세요. 최대 6개까지 담을 수 있어요.")
            items.forEach { item -> TextButton(onClick = { selectedId = item.id; dialog = "actions" },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Column(Modifier.fillMaxWidth()) {
                    Text(item.name)
                    Text(favoriteSetupSummary(item.setup), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } } }
        } }, confirmButton = { TextButton(onClick = { dialog = null }) { Text("닫기") } })
    if (dialog == "actions" && selected != null) AlertDialog(onDismissRequest = { dialog = null },
        title = { Text(selected.name) }, text = { Column {
            Text(favoriteSetupSummary(selected.setup))
            TextButton(enabled = enabled, onClick = { name = TextFieldValue(selected.name, TextRange(0, selected.name.length)); dialog = "rename" }, modifier = Modifier.heightIn(min = 48.dp)) { Text("별칭 바꾸기") }
            TextButton(enabled = enabled, onClick = { dialog = "overwrite" }, modifier = Modifier.heightIn(min = 48.dp)) { Text("지금 설정으로 덮어쓰기") }
            TextButton(enabled = enabled, onClick = { dialog = "delete" }, modifier = Modifier.heightIn(min = 48.dp)) { Text("지우기") }
        } }, confirmButton = { TextButton(onClick = { dialog = null }) { Text("닫기") } })
    LaunchedEffect(dialog) { if (dialog == "save" || dialog == "rename") focus.requestFocus() }
    if (dialog == "save" || dialog == "rename") AlertDialog(onDismissRequest = { dialog = null },
        title = { Text(if (dialog == "save") "즐겨찾기 저장" else "별칭 바꾸기") },
        text = { Column {
            Text(favoriteSetupSummary(if (dialog == "save") current else selected?.setup ?: current, includeBriefing = true))
            OutlinedTextField(value = name, onValueChange = { if (it.text.length <= FAVORITE_ALIAS_MAX_LENGTH) name = it }, singleLine = true,
                label = { Text("별칭") }, isError = name.text.isBlank(),
                supportingText = { Text(if (name.text.isBlank()) "별칭을 입력해 주세요" else "최대 20자") },
                modifier = Modifier.fillMaxWidth().focusRequester(focus))
        } },
        confirmButton = { TextButton(enabled = enabled && name.text.isNotBlank(), onClick = {
            val duplicate = items.firstOrNull { it.name == name.text.trim() && (dialog == "save" || it.id != selectedId) }
            if (duplicate != null && dialog == "rename") { onNotice("같은 별칭이 있어요. 다른 별칭을 입력해 주세요"); return@TextButton }
            if (duplicate != null) { duplicateId = duplicate.id; dialog = "duplicate"; return@TextButton }
            val saved = if (dialog == "save") store.save(name.text, current) else selected?.let { store.rename(it.id, name.text) } == true
            onNotice(if (saved) "즐겨찾기를 저장했어요" else "저장하지 못했어요. 즐겨찾기 개수를 확인해 주세요")
            dialog = null
        }) { Text("저장") } }, dismissButton = { TextButton(onClick = { dialog = null }) { Text("취소") } })
    if (dialog == "duplicate") AlertDialog(onDismissRequest = { dialog = null },
        title = { Text("덮어쓸까요?") }, text = { Text("같은 별칭이 있어요.\n${name.text.trim()}\n${favoriteSetupSummary(current)}") },
        confirmButton = { TextButton(enabled = enabled, onClick = {
            val saved = duplicateId?.let { store.overwrite(it, current) } == true
            onNotice(if (saved) "즐겨찾기를 저장했어요" else "저장하지 못했어요")
            dialog = null
        }) { Text("덮어쓰기") } }, dismissButton = { TextButton(onClick = { dialog = null }) { Text("취소") } })
    if ((dialog == "delete" || dialog == "overwrite") && selected != null) AlertDialog(onDismissRequest = { dialog = null },
        title = { Text(if (dialog == "delete") "즐겨찾기를 지울까요?" else "지금 설정으로 바꿀까요?") },
        text = { Text(selected.name) }, confirmButton = { TextButton(enabled = enabled, onClick = {
            if (dialog == "delete") store.delete(selected.id) else store.overwrite(selected.id, current)
            dialog = null
        }) { Text(if (dialog == "delete") "지우기" else "덮어쓰기") } },
        dismissButton = { TextButton(onClick = { dialog = null }) { Text("취소") } })
}
