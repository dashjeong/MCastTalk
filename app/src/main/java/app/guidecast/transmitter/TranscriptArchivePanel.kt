package app.guidecast.transmitter

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Session selection changes only the archive browser, never the active listener transcript. */
@Composable
internal fun BroadcastTranscriptArchivePanel(
    archive: TranscriptArchiveSnapshot,
    loadPage: suspend (TranscriptArchiveFilter) -> TranscriptArchivePage,
    onDeleteSelected: (Set<TranscriptArchiveKey>) -> Unit,
    onDeleteSession: (Long) -> Unit,
    onRetentionPolicyChange: (TranscriptRetentionPolicy) -> Unit,
) {
    var sessionFilter by rememberSaveable { mutableStateOf<Long?>(null) }
    var languageFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var oldestFirst by rememberSaveable { mutableStateOf(false) }
    var pageIndex by rememberSaveable { mutableStateOf(0) }
    var sessionMenuExpanded by remember { mutableStateOf(false) }
    var retryRevision by remember { mutableStateOf(0) }
    var fromDate by rememberSaveable { mutableStateOf("") }
    var throughDate by rememberSaveable { mutableStateOf("") }
    var fromEpochMillis by rememberSaveable { mutableStateOf<Long?>(null) }
    var beforeEpochMillis by rememberSaveable { mutableStateOf<Long?>(null) }
    var settingsExpanded by rememberSaveable { mutableStateOf(false) }
    var draftFromDate by rememberSaveable { mutableStateOf("") }
    var draftThroughDate by rememberSaveable { mutableStateOf("") }
    var draftPolicy by remember { mutableStateOf(archive.retentionPolicy) }
    var dateError by remember { mutableStateOf<String?>(null) }
    val filter = TranscriptArchiveFilter(sessionFilter, languageFilter, oldestFirst, pageIndex,
        startedFromEpochMillis = fromEpochMillis, startedBeforeEpochMillis = beforeEpochMillis)
    var page by remember(filter) { mutableStateOf(TranscriptArchivePage()) }
    var loading by remember(filter) { mutableStateOf(true) }
    var loadFailed by remember(filter) { mutableStateOf(false) }
    var selectedKeys by remember(filter) { mutableStateOf<Set<TranscriptArchiveKey>>(emptySet()) }
    var deleteKeys by remember { mutableStateOf<Set<TranscriptArchiveKey>?>(null) }
    var deleteSession by remember { mutableStateOf<ArchivedBroadcastSession?>(null) }
    val sessions = remember(archive.sessions, oldestFirst, fromEpochMillis, beforeEpochMillis) {
        archive.sessions.filter { session ->
            (fromEpochMillis == null || session.startedAtEpochMillis >= requireNotNull(fromEpochMillis)) &&
                (beforeEpochMillis == null || session.startedAtEpochMillis < requireNotNull(beforeEpochMillis))
        }.sortedWith(
            compareBy<ArchivedBroadcastSession> { it.startedAtEpochMillis }.thenBy { it.sessionId }
                .let { if (oldestFirst) it else it.reversed() },
        )
    }
    val selectedSession = sessions.firstOrNull { it.sessionId == sessionFilter }
    val languages = remember(archive.sessions, sessionFilter, page.lines) {
        (archive.sessions.asSequence().filter { sessionFilter == null || it.sessionId == sessionFilter }
            .flatMap { it.translationLanguages.asSequence() } +
            page.lines.asSequence().flatMap { it.line.translations.keys.asSequence() })
            .distinct().sorted().toList()
    }
    val visibleSelection = visibleArchiveSelection(selectedKeys, page.lines)

    LaunchedEffect(filter, archive.revision, retryRevision) {
        // Coalesce the final-source, translation and TTS updates without reading the complete
        // archive on the main thread or increasing the live listener's bounded snapshot.
        delay(120L)
        try {
            page = loadPage(filter)
            if (pageIndex > 0 && page.lines.isEmpty()) pageIndex--
            loadFailed = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            loadFailed = true
        } finally {
            loading = false
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("방송 스크립트 보관함", fontWeight = FontWeight.Bold)
            Text(
                "조회 조건에 맞는 ${page.totalMatchingLines}개 문장 · 1,000개씩 표시",
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                "방송 시작 시각으로 찾아보세요. 문장은 각 방송 안에서 발화 순서로 표시합니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (archive.retentionPolicy == TranscriptRetentionPolicy.DAILY_BACKUP)
                    "기본 보관 500,000개 · 날짜별 백업 사용 · 기간 조회에 백업 포함"
                else "최대 500,000개 · 초과 시 오래된 문장부터 덮어쓰기",
                style = MaterialTheme.typography.labelSmall,
            )
            Text(if (fromDate.isBlank() && throughDate.isBlank()) "전체 기간"
                else "방송 시작일: ${fromDate.ifBlank { "처음" }} ~ ${throughDate.ifBlank { "오늘 이후 포함" }}",
                style = MaterialTheme.typography.labelMedium)
            OutlinedButton(onClick = {
                draftFromDate = fromDate; draftThroughDate = throughDate
                draftPolicy = archive.retentionPolicy; dateError = null; settingsExpanded = true
            }, modifier = Modifier.fillMaxWidth()) { Text("기간 조회 · 보관 설정") }
            if (archive.pendingWriteCount > 0) {
                Text("저장 중 ${archive.pendingWriteCount}개", style = MaterialTheme.typography.labelSmall)
            }
            archive.warning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("방송 시작 시각", fontWeight = FontWeight.SemiBold)
            Box {
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { sessionMenuExpanded = true },
                ) {
                    Text(selectedSession?.archiveSessionLabel()
                        ?: if (sessionFilter == null) "기간 내 전체 방송" else "목록에 없는 방송")
                }
                DropdownMenu(
                    expanded = sessionMenuExpanded,
                    onDismissRequest = { sessionMenuExpanded = false },
                    modifier = Modifier.heightIn(max = 360.dp),
                ) {
                    fun selectSession(id: Long?) {
                        sessionFilter = id
                        languageFilter = null
                        pageIndex = 0
                        sessionMenuExpanded = false
                    }
                    DropdownMenuItem(
                        text = { Text("기간 내 전체 방송") },
                        onClick = { selectSession(null) },
                    )
                    sessions.forEach { session ->
                        DropdownMenuItem(
                            text = { Text("${session.archiveSessionLabel()} · ${session.storedLineCount}개") },
                            onClick = { selectSession(session.sessionId) },
                        )
                    }
                }
            }
            Text("선택 목록은 최근 1,000회 방송입니다. 이전 방송은 기간 조회로 찾을 수 있습니다.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !oldestFirst, onClick = { oldestFirst = false; pageIndex = 0 },
                    label = { Text("최근 방송순") })
                FilterChip(selected = oldestFirst, onClick = { oldestFirst = true; pageIndex = 0 },
                    label = { Text("오래된 방송순") })
            }
            if (languages.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(selected = languageFilter == null,
                        onClick = { languageFilter = null; pageIndex = 0 }, label = { Text("전체 언어") })
                    languages.forEach { language ->
                        FilterChip(selected = languageFilter == language,
                            onClick = { languageFilter = language; pageIndex = 0 },
                            label = { Text(language.uppercase(Locale.ROOT)) })
                    }
                }
            }
            if (loadFailed) {
                Text("보관함을 불러오지 못했습니다.", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { retryRevision++ }) { Text("보관함 다시 불러오기") }
            }
            if (page.lines.isEmpty()) {
                Text(when {
                    loading -> "스크립트를 불러오는 중입니다."
                    languageFilter != null -> "선택한 언어로 저장된 문장이 없습니다."
                    selectedSession != null -> "이 방송에 저장된 확정 문장이 아직 없습니다."
                    else -> "저장된 방송 스크립트가 없습니다."
                }, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("문장 선택과 숨기기는 현재 페이지에 적용됩니다. 페이지나 조회 조건을 바꾸면 선택이 해제됩니다.",
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(modifier = Modifier.weight(1f), onClick = {
                        selectedKeys = if (visibleSelection.size == page.lines.size) emptySet()
                        else page.lines.mapTo(mutableSetOf()) { it.key }
                    }) { Text(if (visibleSelection.size == page.lines.size) "선택 해제" else "현재 페이지 선택") }
                    OutlinedButton(
                        modifier = Modifier.weight(1f), enabled = visibleSelection.isNotEmpty(),
                        onClick = { deleteKeys = visibleSelection.toSet() },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                    ) { Text("선택 ${visibleSelection.size}개 숨기기") }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  itemsIndexed(page.lines, key = { _, line -> "${line.key.sessionId}/${line.key.sequence}" }) { index, archived ->
                    if (archived.key.sessionId != page.lines.getOrNull(index - 1)?.key?.sessionId) {
                        Text(
                            "방송 시작 ${formatArchiveSessionTime(archived.sessionStartedAtEpochMillis)} · ${archived.sourceLanguageTag}",
                            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.Top) {
                            Checkbox(
                                checked = archived.key in visibleSelection,
                                onCheckedChange = { checked ->
                                    selectedKeys = if (checked) selectedKeys + archived.key else selectedKeys - archived.key
                                },
                                modifier = Modifier.semantics { contentDescription = "문장 ${archived.key.sequence} 선택 · ${archived.line.sourceText.take(160)}" },
                            )
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                SelectionContainer { Text(archived.line.sourceText) }
                                SpeechCorrectionAction(archived.line, archived.sourceLanguageTag)
                                archived.line.translations.filterKeys { languageFilter == null || it == languageFilter }
                                    .forEach { (tag, text) ->
                                        SelectionContainer { Text("${tag.uppercase(Locale.ROOT)} · $text") }
                                    }
                            }
                        }
                    }
                  }
                }
                Text(
                    "${page.totalMatchingLines}개 중 ${pageIndex * ARCHIVE_PAGE_SIZE + 1}–" +
                        "${pageIndex * ARCHIVE_PAGE_SIZE + page.lines.size}개 표시",
                    style = MaterialTheme.typography.labelSmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(modifier = Modifier.weight(1f), enabled = pageIndex > 0,
                        onClick = { pageIndex-- }) { Text("이전 페이지") }
                    OutlinedButton(modifier = Modifier.weight(1f),
                        enabled = (pageIndex + 1L) * ARCHIVE_PAGE_SIZE < page.totalMatchingLines,
                        onClick = { pageIndex++ }) { Text("다음 페이지") }
                }
            }
            selectedSession?.let { session ->
                TextButton(onClick = { deleteSession = session }) {
                    Text("선택한 방송 숨기기 (${session.storedLineCount}개)", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (settingsExpanded) {
        AlertDialog(
            onDismissRequest = { settingsExpanded = false }, title = { Text("기간 조회 · 보관 설정") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("방송을 시작한 날짜로 조회합니다. 빈 날짜는 기간을 제한하지 않습니다. 날짜별 백업도 함께 찾습니다.")
                    OutlinedTextField(value = draftFromDate, onValueChange = { draftFromDate = it.take(10); dateError = null },
                        label = { Text("시작일 · YYYY-MM-DD") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = draftThroughDate, onValueChange = { draftThroughDate = it.take(10); dateError = null },
                        label = { Text("종료일 · YYYY-MM-DD") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { draftFromDate = ""; draftThroughDate = ""; dateError = null }) { Text("전체 기간으로") }
                    dateError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Text("기본 보관량을 넘을 때", fontWeight = FontWeight.SemiBold)
                    FilterChip(selected = draftPolicy == TranscriptRetentionPolicy.OVERWRITE_OLDEST,
                        onClick = { draftPolicy = TranscriptRetentionPolicy.OVERWRITE_OLDEST },
                        label = { Text("오래된 문장부터 덮어쓰기") })
                    FilterChip(selected = draftPolicy == TranscriptRetentionPolicy.DAILY_BACKUP,
                        onClick = { draftPolicy = TranscriptRetentionPolicy.DAILY_BACKUP },
                        label = { Text("날짜별 백업으로 보관") })
                    Text("백업은 앱 전용 저장공간을 사용합니다. 저장공간이 부족하면 경고를 표시합니다. " +
                        "이미 만들어진 백업은 덮어쓰기 모드로 바꿔도 기간 조회에 포함합니다.",
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = {
                try {
                    val range = parseArchiveDateRange(draftFromDate, draftThroughDate)
                    fromDate = draftFromDate; throughDate = draftThroughDate
                    fromEpochMillis = range.startedFromEpochMillis; beforeEpochMillis = range.startedBeforeEpochMillis
                    sessionFilter = null; languageFilter = null; pageIndex = 0
                    onRetentionPolicyChange(draftPolicy)
                    settingsExpanded = false
                } catch (_: IllegalArgumentException) {
                    dateError = "실제 날짜를 YYYY-MM-DD로 입력하고 종료일을 시작일 이후로 지정하세요."
                }
            }) { Text("적용") } },
            dismissButton = { TextButton(onClick = { settingsExpanded = false }) { Text("취소") } },
        )
    }
    deleteKeys?.let { capturedKeys ->
        AlertDialog(
            onDismissRequest = { deleteKeys = null }, title = { Text("보관함에서 숨기기 (백업 사본 유지)") },
            text = { Text("현재 페이지에서 선택한 ${capturedKeys.size}개 문장과 번역을 보관함 조회에서 제외합니다. " +
                "기본 보관 본문은 삭제되며, 이미 생성된 일일 백업 사본은 유지됩니다.") },
            confirmButton = { TextButton(onClick = {
                onDeleteSelected(capturedKeys); selectedKeys = emptySet(); deleteKeys = null
            }) { Text("보관함에서 숨기기", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleteKeys = null }) { Text("취소") } },
        )
    }
    deleteSession?.let { session ->
        AlertDialog(
            onDismissRequest = { deleteSession = null }, title = { Text("방송 숨기기 (백업 사본 유지)") },
            text = { Text("${session.archiveSessionLabel()}에 시작한 방송의 모든 문장과 번역을 보관함 조회에서 제외합니다. " +
                "언어 필터와 관계없이 이 방송 전체에 적용합니다. 기본 보관 본문은 삭제되며, 일일 백업 사본은 유지됩니다. " +
                "진행 중인 방송이면 이후 문장도 이 보관함에 저장하지 않습니다.") },
            confirmButton = { TextButton(onClick = {
                onDeleteSession(session.sessionId)
                sessionFilter = null; languageFilter = null; pageIndex = 0; deleteSession = null
            }) { Text("방송 숨기기", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleteSession = null }) { Text("취소") } },
        )
    }
}

private fun ArchivedBroadcastSession.archiveSessionLabel(): String =
    "${formatArchiveSessionTime(startedAtEpochMillis)} · $sourceLanguageTag"

internal fun formatArchiveSessionTime(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(epochMillis))

internal data class ArchiveDateRange(val startedFromEpochMillis: Long?, val startedBeforeEpochMillis: Long?)

/** Inclusive calendar end date becomes an exclusive next-day boundary, including DST changes. */
internal fun parseArchiveDateRange(from: String, through: String, zone: ZoneId = ZoneId.systemDefault()): ArchiveDateRange {
    fun parse(value: String): LocalDate? {
        if (value.isBlank()) return null
        require(value.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
        return try { LocalDate.parse(value) } catch (error: java.time.DateTimeException) {
            throw IllegalArgumentException("Invalid archive date", error)
        }
    }
    val start = parse(from)
    val end = parse(through)
    require(start == null || end == null || !end.isBefore(start))
    return ArchiveDateRange(start?.atStartOfDay(zone)?.toInstant()?.toEpochMilli(),
        end?.plusDays(1L)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli())
}
