package app.guidecast.transmitter

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.guidecast.core.translation.TranslationStyle
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.launch

@Composable
fun DomainCorpusScreen(
    repository: DomainCorpusRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val revision by repository.revision.collectAsStateWithLifecycle()

    var profiles by remember { mutableStateOf<List<DomainCorpusProfile>>(emptyList()) }
    var showImportDialog by remember { mutableStateOf(false) }
    var selectedImportUri by remember { mutableStateOf<Uri?>(null) }
    var importName by remember { mutableStateOf("") }
    var importDesc by remember { mutableStateOf("") }
    var importTargetLang by remember { mutableStateOf("en") }
    var importStyle by remember { mutableStateOf(TranslationStyle.AUTO) }

    var importErrorLine by remember { mutableStateOf<Int?>(null) }
    var importErrorMessage by remember { mutableStateOf<String?>(null) }
    var showFailureDialog by remember { mutableStateOf(false) }

    var profileToDelete by remember { mutableStateOf<DomainCorpusProfile?>(null) }
    var profileToExport by remember { mutableStateOf<DomainCorpusProfile?>(null) }
    var dbError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(revision) {
        try {
            profiles = repository.profiles()
            dbError = null
        } catch (cancellation: java.util.concurrent.CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            dbError = "저장된 코퍼스를 읽지 못했습니다. 화면을 다시 열어 확인해 주세요."
        }
    }

    val templateLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val stream = context.contentResolver.openOutputStream(uri)
                    if (stream == null) {
                        Toast.makeText(context, "템플릿 파일을 생성할 수 없습니다.", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    stream.use { out ->
                        out.write(DomainCorpusFormat.generateTemplateTxt().toByteArray(StandardCharsets.UTF_8))
                    }
                    Toast.makeText(context, "템플릿 파일이 저장되었습니다.", Toast.LENGTH_SHORT).show()
                } catch (e: java.util.concurrent.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Toast.makeText(context, "템플릿 저장 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val target = profileToExport
        if (uri != null && target != null) {
            scope.launch {
                try {
                    val stream = context.contentResolver.openOutputStream(uri)
                    if (stream == null) {
                        Toast.makeText(context, "내보내기 파일을 생성할 수 없습니다.", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    stream.use { out ->
                        repository.exportTxt(target.id, out)
                    }
                    Toast.makeText(context, "코퍼스 내보내기가 완료되었습니다.", Toast.LENGTH_SHORT).show()
                } catch (e: java.util.concurrent.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Toast.makeText(context, "내보내기 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                } finally {
                    profileToExport = null
                }
            }
        } else {
            profileToExport = null
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            selectedImportUri = uri
            showImportDialog = true
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Start,
            ) {
                TextButton(onClick = onBack) {
                    Text("← 설정으로 돌아가기")
                }
                TextButton(onClick = { templateLauncher.launch("domain-template.txt") }) {
                    Text("템플릿 내려받기")
                }
            }
        }

        item {
            Text(
                text = "전문 용어·참고 문장",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }

        item {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "확인한 번역을 바로 적용하고 유사한 문장의 표현을 참고합니다. 선택 모델 가중치는 변경하지 않습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "같은 원문·번역 언어에는 자료 모음 하나를 적용합니다. 다른 언어 조합에는 각각 적용할 수 있습니다. 자료는 기기에 보관합니다. 온라인 설정에서 참고 자료 전송을 허용하면 관련 예시만 API로 전달합니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("On-통의 문장 번역에 적용됩니다. On-통 Live(AI 통역)에는 이 자료가 자동 적용되지 않습니다. 중계 설정의 분야 안내는 별도로 입력하세요.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item {
            Button(
                onClick = { filePickerLauncher.launch(arrayOf("text/plain", "*/*")) },
                modifier = Modifier
                    .fillMaxWidth()
                    .sizeIn(minHeight = 50.dp),
            ) {
                Text("새 참고 자료 가져오기 (.txt)")
            }
        }

        item {
            Text(
                text = "내 참고 자료 (${profiles.size}개) · 켜서 적용, 꺼서 사용 중지",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (dbError != null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "코퍼스 데이터베이스 읽기 실패",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = dbError.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        } else if (profiles.isEmpty()) {
            item {
                Text(
                    text = "등록된 도메인 코퍼스가 없습니다. 템플릿을 내려받아 작성 후 등록하세요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        } else {
            items(profiles, key = { it.id }) { profile ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (profile.active)
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        else MaterialTheme.colorScheme.surface,
                    ),
                    border = BorderStroke(
                        1.dp,
                        if (profile.active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = profile.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = "${profile.sourceLanguageTag} → ${profile.targetLanguageTag} · ${profile.style.koreanLabel()} · ${profile.pairCount}쌍",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Switch(
                                modifier = Modifier.semantics {
                                    contentDescription = "도메인 프로필 ${profile.name} 활성화"
                                },
                                checked = profile.active,
                                onCheckedChange = { active ->
                                    scope.launch {
                                        try {
                                            if (active) repository.activate(profile.id)
                                            else repository.deactivate(profile.id)
                                        } catch (e: java.util.concurrent.CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "활성화 상태 변경 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                            )
                        }
                        if (profile.description.isNotBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = profile.description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(onClick = {
                                profileToExport = profile
                                exportLauncher.launch("${profile.name}.txt")
                            }) {
                                Text("내보내기")
                            }
                            TextButton(
                                onClick = { profileToDelete = profile },
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            ) {
                                Text("삭제")
                            }
                        }
                    }
                }
            }
        }
    }

    // Import Metadata Dialog
    if (showImportDialog && selectedImportUri != null) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("코퍼스 프로필 정보 입력") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = importName,
                        onValueChange = { if (it.length <= DomainCorpusFormat.MAX_NAME_LENGTH) importName = it },
                        label = { Text("도메인 이름 (최대 80자)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = importDesc,
                        onValueChange = { if (it.length <= DomainCorpusFormat.MAX_DESCRIPTION_LENGTH) importDesc = it },
                        label = { Text("설명 (최대 400자)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("원문 언어: ko", style = MaterialTheme.typography.bodyMedium)
                    Text("번역 대상 언어 선택:", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("en", "ja", "zh").forEach { lang ->
                            FilterChip(
                                selected = importTargetLang == lang,
                                onClick = { importTargetLang = lang },
                                label = { Text(lang) },
                            )
                        }
                    }
                    Text("어체(Style) 선택:", style = MaterialTheme.typography.bodyMedium)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TranslationStyle.entries.forEach { style ->
                            FilterChip(
                                selected = importStyle == style,
                                onClick = { importStyle = style },
                                label = { Text(style.koreanLabel()) },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val uri = selectedImportUri ?: return@Button
                        if (importName.isBlank()) {
                            Toast.makeText(context, "도메인 이름을 입력하세요.", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        showImportDialog = false
                        scope.launch {
                            try {
                                val stream = context.contentResolver.openInputStream(uri)
                                if (stream == null) {
                                    Toast.makeText(context, "파일을 열 수 없습니다.", Toast.LENGTH_SHORT).show()
                                    return@launch
                                }
                                val result = stream.use {
                                    repository.importTxt(
                                        input = it,
                                        name = importName,
                                        description = importDesc,
                                        sourceLanguageTag = "ko",
                                        targetLanguageTag = importTargetLang,
                                        style = importStyle,
                                    )
                                }
                                when (result) {
                                    is DomainImportResult.Success -> {
                                        Toast.makeText(context, "코퍼스를 성공적으로 가져왔습니다 (${result.profile.pairCount}쌍).", Toast.LENGTH_SHORT).show()
                                        importName = ""
                                        importDesc = ""
                                        selectedImportUri = null
                                    }
                                    is DomainImportResult.Failure -> {
                                        importErrorLine = result.lineNumber
                                        importErrorMessage = result.reason
                                        showFailureDialog = true
                                    }
                                }
                            } catch (e: java.util.concurrent.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                importErrorLine = null
                                importErrorMessage = e.message ?: "가져오기 중 오류 발생"
                                showFailureDialog = true
                            }
                        }
                    },
                ) {
                    Text("가져오기 실행")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) { Text("취소") }
            },
        )
    }

    // Import Failure Rework UI
    if (showFailureDialog) {
        AlertDialog(
            onDismissRequest = { showFailureDialog = false },
            title = { Text("코퍼스 가져오기 실패") },
            text = {
                Column {
                    if (importErrorLine != null) {
                        Text(
                            text = "오류 위치: ${importErrorLine}행",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    Text(
                        text = "원인: ${importErrorMessage.orEmpty()}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "기존 코퍼스는 변경되지 않고 온전히 보존되었습니다. 해당 행의 형식을 수정한 후 다시 시도하세요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    showFailureDialog = false
                    // Keep user inputs so they can easily retry with updated file
                    showImportDialog = true
                }) {
                    Text("다시 시도")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showFailureDialog = false
                    selectedImportUri = null
                }) {
                    Text("닫기")
                }
            },
        )
    }

    // Delete Confirmation Dialog
    profileToDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { profileToDelete = null },
            title = { Text("코퍼스 프로필 삭제") },
            text = { Text("'${profile.name}' 코퍼스 프로필과 포함된 문장 쌍을 모두 삭제하시겠습니까?") },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch { repository.remove(profile.id) }
                        profileToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("삭제")
                }
            },
            dismissButton = {
                TextButton(onClick = { profileToDelete = null }) { Text("취소") }
            },
        )
    }
}

fun TranslationStyle.koreanLabel(): String = when (this) {
    TranslationStyle.FORMAL -> "문어체·격식 (FORMAL)"
    TranslationStyle.CONVERSATIONAL -> "구어체·대화 (CONVERSATIONAL)"
    TranslationStyle.AUTO -> "자동 (AUTO)"
}
