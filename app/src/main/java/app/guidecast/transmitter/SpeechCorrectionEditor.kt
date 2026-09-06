package app.guidecast.transmitter

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

private const val MAX_CORRECTED_SENTENCE_CODE_POINTS = 2000
private const val MAX_HINT_CODE_POINTS = 80

private fun String.codePointsCount(): Int =
    if (isEmpty()) 0 else codePointCount(0, length)

/**
 * 음성인식 결과에 대한 문장 교정 및 힌트 입력 편집창 컴포저블.
 *
 * 사용자가 확정한 인식 문장을 비교·교정하고, 필요 시 다음 인식에 참고할 핵심 고유명사/단어 힌트를 등록합니다.
 * 모델 가중치 재학습이나 원음 녹음은 수행하지 않으며, 이미 방송된 원문·번역·음성은 덮어쓰지 않습니다.
 */
@Composable
internal fun SpeechCorrectionEditor(
    recognizedText: String,
    initialCorrectedText: String,
    initialHint: String?,
    profile: String,
    languageLabel: String,
    busy: Boolean,
    error: String?,
    onSave: (correctedText: String, hint: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var correctedText by rememberSaveable(recognizedText, initialCorrectedText) {
        mutableStateOf(initialCorrectedText.ifBlank { recognizedText })
    }
    var applyHintToNextRecognition by rememberSaveable(initialHint) {
        mutableStateOf(!initialHint.isNullOrBlank())
    }
    var hintText by rememberSaveable(initialHint) {
        mutableStateOf(initialHint.orEmpty())
    }
    var localValidationMessage by remember { mutableStateOf<String?>(null) }

    val hasChanges = recognizedText.trim() != correctedText.trim()
    val sentenceCodePoints = correctedText.codePointsCount()
    val isSentenceOverLimit = sentenceCodePoints > MAX_CORRECTED_SENTENCE_CODE_POINTS

    val hintCodePoints = hintText.codePointsCount()
    val isHintOverLimit = hintCodePoints > MAX_HINT_CODE_POINTS

    val outlineColor = MaterialTheme.colorScheme.outline
    val primaryColor = MaterialTheme.colorScheme.primary

    AlertDialog(
        onDismissRequest = {
            if (!busy) onDismiss()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
        ),
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .padding(vertical = 16.dp)
            .imePadding(),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "음성인식 문장 교정",
                    style = MaterialTheme.typography.titleLarge,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text(
                            text = "언어: $languageLabel",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text(
                            text = "프로필: $profile",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // 1. 인식 원문 (불변 표시)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "인식된 원문 (불변)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            text = recognizedText.ifBlank { "(인식된 텍스트가 없습니다)" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }

                // 2. 정답 문장 편집 필드
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(
                        value = correctedText,
                        onValueChange = { input ->
                            correctedText = input
                            localValidationMessage = null
                        },
                        label = { Text("정답 문장 (수정)") },
                        supportingText = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                if (isSentenceOverLimit) {
                                    Text(
                                        text = "최대 $MAX_CORRECTED_SENTENCE_CODE_POINTS 자를 초과했습니다.",
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                } else {
                                    Text("오인식된 단어나 문장을 올바르게 수정하세요")
                                }
                                Text(
                                    text = "$sentenceCodePoints / $MAX_CORRECTED_SENTENCE_CODE_POINTS",
                                    color = if (isSentenceOverLimit) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        },
                        isError = isSentenceOverLimit || (localValidationMessage != null && correctedText.isBlank()),
                        enabled = !busy,
                        minLines = 2,
                        maxLines = 6,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = "인식 교정 정답"
                            },
                    )
                }

                // 3. 바뀐 내용 강조 (Diff 미리보기)
                if (hasChanges && correctedText.isNotBlank()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "변경 비교 미리보기",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            val diffComparison = remember(recognizedText, correctedText, outlineColor, primaryColor) {
                                val change = speechCorrectionChange(recognizedText, correctedText)
                                buildAnnotatedString {
                                    append(change.prefix)
                                    withStyle(
                                        SpanStyle(
                                            color = outlineColor,
                                            textDecoration = TextDecoration.LineThrough,
                                        ),
                                    ) {
                                        append(change.removed)
                                    }
                                    if (change.removed.isNotEmpty() && change.added.isNotEmpty()) append(" → ")
                                    withStyle(
                                        SpanStyle(
                                            fontWeight = FontWeight.Bold,
                                            color = primaryColor,
                                        ),
                                    ) {
                                        append(change.added)
                                    }
                                    append(change.suffix)
                                }
                            }
                            Text(
                                text = diffComparison,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                // 4. 다음 인식에 참고 (체크박스 및 고유명사/단어 힌트 입력)
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(enabled = !busy) {
                                    applyHintToNextRecognition = !applyHintToNextRecognition
                                    localValidationMessage = null
                                },
                        ) {
                            Checkbox(
                                checked = applyHintToNextRecognition,
                                onCheckedChange = { checked ->
                                    applyHintToNextRecognition = checked
                                    localValidationMessage = null
                                },
                                enabled = !busy,
                            )
                            Text(
                                text = "다음 인식에 참고 (고유명사·단어 힌트 등록)",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }

                        if (applyHintToNextRecognition) {
                            OutlinedTextField(
                                value = hintText,
                                onValueChange = { input ->
                                    hintText = input
                                    localValidationMessage = null
                                },
                                label = { Text("인식 참고 힌트 (단어 또는 짧은 표현)") },
                                placeholder = { Text("예: 국립중앙박물관") },
                                supportingText = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        if (isHintOverLimit) {
                                            Text(
                                                text = "최대 $MAX_HINT_CODE_POINTS 자를 초과했습니다.",
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        } else {
                                            Text("인식기에 전달할 핵심 고유명사만 입력")
                                        }
                                        Text(
                                            text = "$hintCodePoints / $MAX_HINT_CODE_POINTS",
                                            color = if (isHintOverLimit) {
                                                MaterialTheme.colorScheme.error
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                        )
                                    }
                                },
                                isError = isHintOverLimit || (localValidationMessage != null && hintText.isBlank()),
                                singleLine = true,
                                enabled = !busy,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 8.dp, end = 8.dp, bottom = 4.dp)
                                    .semantics {
                                        contentDescription = "인식 교정 힌트"
                                    },
                            )
                        }
                    }
                }

                // 5. 투명한 정책 및 엔진 한계 정확한 안내
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            text = "안내 사항",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "• 이미 방송된 원문·번역·음성은 바뀌지 않으며 교정 기록으로 보존됩니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "• 음성 모델 자체의 재학습이나 원음 녹음 수집은 수행하지 않습니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "• Android 13+ 지원 엔진(Galaxy/Google 등)에서 다음 요청 시 참고용으로 전달되며, 엔진 백엔드 정책에 따라 무시될 수 있습니다. 현재 탑재된 Moonshine Tiny 엔진은 힌트를 지원하지 않습니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // 6. 에러 메시지 표시
                val currentError = localValidationMessage ?: error
                if (currentError != null) {
                    Text(
                        text = currentError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmedSentence = correctedText.trim()
                    val sentencePoints = trimmedSentence.codePointsCount()
                    if (trimmedSentence.isEmpty()) {
                        localValidationMessage = "정답 문장을 입력해 주세요."
                        return@Button
                    }
                    if (sentencePoints > MAX_CORRECTED_SENTENCE_CODE_POINTS) {
                        localValidationMessage = "문장은 최대 $MAX_CORRECTED_SENTENCE_CODE_POINTS 자(code point)까지 입력할 수 있습니다."
                        return@Button
                    }

                    val finalHint = if (applyHintToNextRecognition) {
                        val trimmedHint = hintText.trim()
                        val hintPoints = trimmedHint.codePointsCount()
                        if (trimmedHint.isEmpty()) {
                            localValidationMessage = "다음 인식에 참고할 고유명사나 단어 힌트를 입력하거나, 체크를 해제하세요."
                            return@Button
                        }
                        if (hintPoints > MAX_HINT_CODE_POINTS) {
                            localValidationMessage = "힌트는 최대 $MAX_HINT_CODE_POINTS 자(code point)까지 입력할 수 있습니다."
                            return@Button
                        }
                        trimmedHint
                    } else {
                        null
                    }

                    onSave(trimmedSentence, finalHint)
                },
                enabled = !busy,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("교정 저장")
                }
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                enabled = !busy,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("취소")
            }
        },
    )
}
