package app.guidecast.transmitter

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal enum class MCastService(val title: String, val description: String, val outcome: String, val preparation: String) {
    NOTES("음성 노트", "녹음하고 문장으로 보관하세요.", "녹음 → 문장 수정·번역 → 원음 대조·내려받기", "원음과 전사는 기기에 저장됩니다. 사용할 언어를 먼저 준비하세요."),
    VOICE("원음 방송", "입력 소리를 함께 들어보세요.", "방송 열기 → QR로 초대 → 함께 듣기", "송신자와 청취자는 같은 네트워크에 연결하세요. 먼저 테스트음을 확인하세요."),
    MULTILINGUAL("실시간 방송·통역", "말소리와 번역을 실시간으로 확인하세요.", "언어 선택 → 통역 준비 → 언어별 채널로 듣기", "최초 모델 준비에는 인터넷·저장 공간이 필요합니다. 원음 방송은 통역 준비 중에도 열 수 있습니다."),
    FILES("파일 변환·재생", "음성 파일을 문장으로 바꾸고 다시 들으세요.", "파일 선택 → 전사·번역 → 스크립트 보관·재생", "기기에 있는 파일로 시작하세요. 받아쓰기는 기기의 오프라인 음성 인식 지원이 필요합니다."),
}

@Composable
internal fun ServiceHomeScreen(activeService: MCastService?, onSelect: (MCastService) -> Unit) {
    val normalizedActive = if (activeService == MCastService.VOICE) MCastService.MULTILINGUAL else activeService
    Surface(Modifier.fillMaxSize()) {
      BoxWithConstraints {
        val fontScale = LocalDensity.current.fontScale
        val columns = if ((maxWidth >= 360.dp && fontScale <= 1.1f) || (maxWidth >= 480.dp && fontScale <= 1.3f)) 2 else 1
        val services = listOf(MCastService.MULTILINGUAL, MCastService.NOTES, MCastService.FILES)
        LazyColumn(Modifier.fillMaxSize(),
            contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("MCastTalk", style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.height(8.dp))
                Text("오늘은 무엇을 할까요?", style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() })
            }
            normalizedActive?.let { active -> item {
                FilledTonalButton(onClick = { onSelect(active) }, modifier = Modifier.fillMaxWidth()) {
                    Text("진행 중인 작업으로 · ${active.title}")
                }
                Text("작업을 마친 뒤 다른 음성 작업을 시작할 수 있습니다. 설정은 아래 탭에서 확인하세요.", style = MaterialTheme.typography.bodySmall)
            } }
            items(services.chunked(columns)) { row ->
              Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
               row.forEach { service ->
                val isServiceActive = normalizedActive == service
                Card(onClick = { onSelect(service) }, enabled = normalizedActive == null || isServiceActive,
                    modifier = Modifier.weight(1f).heightIn(min = 104.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(service.title, style = MaterialTheme.typography.titleMedium)
                        Text(service.description, style = MaterialTheme.typography.bodyMedium)
                    }
                }
               }
              }
            }
            item { Text("언어·음성과 데이터 관리는 설정에서, 동작 확인은 시험에서 이용하세요.", style = MaterialTheme.typography.bodySmall) }
        }
      }
    }
}
