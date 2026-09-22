package app.guidecast.transmitter

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal enum class MCastService(val title: String, val description: String, val outcome: String, val preparation: String) {
    NOTES("녹톡", "녹음하고, 글로 남기는 음성노트", "녹음 → 문장 수정·번역 → 원음 대조·내려받기", "최대 60분 · 기기 내 저장 · 받아쓰기는 Android 13 이상과 오프라인 언어팩 필요"),
    VOICE("라이브톡", "내 목소리를 그대로 전하는 원음 방송", "방송 열기 → QR로 초대 → 함께 듣기", "송신자와 청취자는 같은 네트워크에 연결하세요. 먼저 테스트음을 확인하세요."),
    MULTILINGUAL("통역톡", "한 번 말하고, 여러 언어로 전하는 다국어 통역 방송", "언어 선택 → 통역 준비 → 언어별 채널로 듣기", "최초 모델 준비에는 인터넷·저장 공간이 필요합니다. 원음 방송은 통역 준비 중에도 열 수 있습니다."),
    FILES("스크립톡", "음성파일 전사 · 읽고 듣는 스크립트로", "파일 선택 → 전사·번역 → 스크립트 보관·재생", "기기에 있는 파일로 시작하세요. 받아쓰기는 기기의 오프라인 음성 인식 지원이 필요합니다."),
}

@Composable
internal fun ServiceHomeScreen(activeService: MCastService?, onSelect: (MCastService) -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().safeDrawingPadding(),
            contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text("MCastTalk", style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.height(12.dp))
                Text("오늘은 무엇을 할까요?", style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() })
                Text("하고 싶은 일을 선택하세요. 각 서비스의 작업 공간에서 바로 시작할 수 있습니다.",
                    modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
            }
            activeService?.let { active -> item {
                FilledTonalButton(onClick = { onSelect(active) }, modifier = Modifier.fillMaxWidth()) {
                    Text("진행 중인 ${active.title}(으)로 돌아가기")
                }
                Text("진행 중인 작업을 중지하면 다른 서비스를 시작할 수 있습니다.", style = MaterialTheme.typography.bodySmall)
            } }
            items(MCastService.entries) { service ->
                Card(onClick = { onSelect(service) }, enabled = activeService == null || activeService == service,
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(service.title, style = MaterialTheme.typography.titleLarge)
                        Text(service.description, style = MaterialTheme.typography.bodyMedium)
                        Text(service.outcome, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                        Text(service.preparation, style = MaterialTheme.typography.bodySmall)
                        Text("${service.title} 열기 →", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            item { Text("언어·모델 설정과 사용 설명은 각 서비스 안에서 확인할 수 있습니다.", style = MaterialTheme.typography.bodySmall) }
        }
    }
}
