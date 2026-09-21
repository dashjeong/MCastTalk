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

internal enum class MCastService(val title: String, val description: String, val outcome: String) {
    NOTES("녹톡", "녹음하고, 글로 남기는 음성노트", "녹음 · 받아쓰기 · 번역 노트 · TXT / SRT"),
    VOICE("라이브톡", "내 목소리를 그대로 전하는 원음 방송", "같은 네트워크 · QR 접속 · 청취자 관리"),
    MULTILINGUAL("통역톡", "한 번 말하고, 여러 언어로 전하는 다국어 통역 방송", "실시간 통역 · 다국어 채널 · 학습 HUD"),
    FILES("스크립톡", "음성파일 전사 · 읽고 듣는 스크립트로", "파일 전사 · 번역 · 스크립트 재생"),
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
                        Text("${service.title} 열기 →", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            item { Text("언어·모델 설정과 사용 설명은 각 서비스 안에서 확인할 수 있습니다.", style = MaterialTheme.typography.bodySmall) }
        }
    }
}
