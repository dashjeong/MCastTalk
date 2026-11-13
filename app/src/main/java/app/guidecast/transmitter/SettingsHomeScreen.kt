package app.guidecast.transmitter

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal fun commonSettingsPageFor(item: String): String? =
    item.takeIf { it in setOf("input", "defaults", "help", "troubleshoot", "licenses", "materials", "access", "test", "tools") }

@Composable
internal fun SettingsHomeScreen(native: Boolean, onOpen: (String) -> Unit) {
    val groups = listOf(
        "말하기" to listOf("마이크" to "input", "주변 소음 줄이기" to "noise"),
        "언어와 음성" to listOf("말하는 언어" to "source", "들려줄 언어" to "languages",
            (if (native) "통역 목소리" else "음성 내려받기 · 삭제") to "voices",
            "녹음 · 파일에 쓸 기본 언어" to "defaults", "자주 쓰는 말·자료" to "materials"),
        "방송 준비" to listOf("행사 정보" to "briefing", "입장 번호·접속" to "access", "방송 전 시험" to "test"),
        "온라인 통역" to listOf("이용할 서비스" to "service", "이용 키" to "key", "전송 동의" to "consent"),
        "정보" to listOf("사용 안내" to "help", "문제 해결" to "troubleshoot", "백업·내보내기·라이선스" to "tools"),
    )
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        groups.forEach { (heading, rows) ->
            item {
                Text(heading, style = MaterialTheme.typography.titleMedium)
                rows.forEach { (label, destination) ->
                    TextButton(onClick = { onOpen(destination) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp)) {
                        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Text("›")
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
