package app.guidecast.transmitter

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*

@Composable
internal fun TranslationComparisonSettings(settings: TranslationApiSettings, service: TranslationApiService,
    enabled: Boolean, corpus: DomainCorpusRepository? = null,
    automaticExampleControls: (@Composable () -> Unit)? = null) {
    val options by settings.state.collectAsState()
    val shadow by service.shadow.collectAsState()
    val sessionLearning by settings.sessionLearning.collectAsState()
    var learningConsent by remember { mutableStateOf(false) }
    var consentReferences by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val capability = serviceExperience(options)
    val learningSupported = capability.supportsLearningComparison
    if (learningConsent) {
        val learningProvider = if (options.provider == TranslationApiProvider.LOCAL) settings.preparedLearningProvider() else options
        AlertDialog(onDismissRequest = { learningConsent = false }, title = { Text("이번 실행의 번역 결과 비교") },
            text = { Column {
                Text(if (options.provider == TranslationApiProvider.LOCAL)
                    "주 방송은 OFFLINE을 유지합니다. 보조 비교: ${learningProvider?.provider?.label ?: "미설정"} / ${learningProvider?.model ?: "미설정"}. 원문과 최대 1,000자의 직전 문맥을 이 API로 추가 전송하며 별도 요금이 발생합니다. 원음 파일은 보내지 않습니다."
                else "주 방송은 선택한 ONLINE API를 유지합니다. 같은 원문에 대한 온라인 결과와 준비된 기기 내 번역을 비교합니다. 비교를 위한 추가 API 요청은 없으며 원음 파일은 보내지 않습니다.")
                if (options.provider == TranslationApiProvider.LOCAL)
                    Text("설정된 API 요청 예산: USD ${learningProvider?.budgetLimitUsd ?: options.budgetLimitUsd}. 실제 사용량과 청구액은 제공자 계정에서도 확인하세요.")
                Row { Checkbox(consentReferences, { consentReferences = it }); Text("검색된 관련 근거 최대 600자를 기존 번역 요청 또는 보조 비교 API에 전송하는 데도 동의") }
            } }, confirmButton = { TextButton(onClick = {
                message = if (settings.beginSessionLearning(true, consentReferences)) "이번 실행의 번역 결과 비교를 켰습니다. 비교 완료만으로 예문이 적용되지는 않습니다."
                    else if (options.provider == TranslationApiProvider.LOCAL)
                        "ONLINE 설정에서 비교 제공자와 키를 먼저 저장하세요. OFFLINE으로 돌아온 뒤 비교를 켤 수 있습니다."
                    else "선택한 ONLINE API의 키와 전송 허용을 확인하세요."
                learningConsent = false
            }) { Text(if (options.provider == TranslationApiProvider.LOCAL) "추가 전송·비용에 동의하고 비교 켜기" else "번역 결과 비교 켜기") } },
            dismissButton = { TextButton(onClick = { learningConsent = false }) { Text("취소") } })
    }
    ServiceExperienceToggle("번역 결과 비교 (상시 설정 포함)",
        if (learningSupported) "주 방송 경로를 유지하면서 같은 원문을 보조 엔진과 비교합니다. OFFLINE의 온라인 보조 비교에는 이번 실행의 추가 전송·비용 동의가 필요합니다."
        else if (capability.supportsNativePairComparison) "직접 음성 비교는 통역 중계의 ‘오프라인 결과와 비교’에서 켜세요. 상시 문장 비교 설정은 이 경로에 적용되지 않습니다."
        else "이 Live 음성 경로에서는 자동 비교를 지원하지 않습니다. 저장된 상시 비교 설정은 적용되지 않습니다.",
        learningSupported && (sessionLearning || (options.provider != TranslationApiProvider.LOCAL && options.alwaysLearnOnline)),
        enabled && learningSupported) { on ->
        if (!on) { settings.endSessionLearning(); if (options.provider != TranslationApiProvider.LOCAL) settings.setAlwaysLearnOnline(false) }
        else { consentReferences = false; learningConsent = true }
    }
    Text("ONLINE의 기기 내 보조 비교는 추가 API 요청 없이 진행합니다. OFFLINE에서 비교를 끄면 비교를 위한 네트워크 전송도 꺼집니다. 비교 기록과 예문 적용은 별도이며 모델 가중치를 학습하지 않습니다.", style = MaterialTheme.typography.bodySmall)
    if (options.provider != TranslationApiProvider.LOCAL) {
        ServiceExperienceToggle("온라인 결과 자동 비교 (상시)",
            if (capability.supportsLearningComparison) "준비된 기기 내 엔진과 비교를 자동 실행합니다. 비교 완료는 품질 승인이나 직접 검수 자료의 자동 저장을 뜻하지 않습니다."
            else if (capability.supportsNativePairComparison) "통역 중계의 ‘오프라인 결과와 비교’를 별도로 켜세요. 저장된 상시 문장 비교 설정은 적용하지 않습니다."
            else "이 음성 경로에서는 저장된 비교 옵션을 적용하지 않습니다.",
            capability.supportsLearningComparison && options.alwaysLearnOnline,
            capability.supportsLearningComparison && (enabled || options.alwaysLearnOnline), settings::setAlwaysLearnOnline)
        Text("기본 꺼짐. 이미 요청한 온라인 번역과 같은 원문의 기기 내 결과를 비교하므로 추가 API 요청은 없습니다. 로컬 엔진이 준비되지 않았거나 바쁘면 비교만 보류합니다. 이 상시 옵션으로 OFFLINE 전송이 켜지지 않으며, OFFLINE의 온라인 보조 비교에는 별도 동의가 필요합니다.", style = MaterialTheme.typography.bodySmall)
    }
    shadow.lastPause?.let { Text("비교 보류: $it", style = MaterialTheme.typography.bodySmall) }
    Text("번역 결과 비교 시도 ${shadow.attempted} · 비교 완료 ${shadow.completed} (승인·적용 횟수 아님) · 미완료 ${shadow.incomplete} · 건너뜀 ${shadow.skipped}", style = MaterialTheme.typography.bodySmall)
    shadow.last?.let { comparison ->
        Text("${comparison.source} → ${comparison.target} · 로컬 근거 버전 ${comparison.corpusRevision} · 최근 비교 기록 (예문 적용 상태는 아래에서 확인)" +
            "\n원문: ${comparison.original}\n온라인: ${comparison.online}\n오프라인: ${comparison.offline}", style = MaterialTheme.typography.bodySmall)
    }
    corpus?.let { LearningComparisonReview(shadow.last, it) }
    automaticExampleControls?.invoke()
    message?.let { Text(it) }
}
