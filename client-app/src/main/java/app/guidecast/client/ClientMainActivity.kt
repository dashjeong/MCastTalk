package app.guidecast.client

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class ClientMainActivity : ComponentActivity() {
    private val viewModel by viewModels<ClientViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.handleIntent(intent)
        setContent {
            GuideCastClientTheme {
                NotificationPermissionEffect()
                val state by viewModel.state.collectAsState()
                ClientScreen(
                    state = state,
                    onAddressChange = viewModel::updateAddress,
                    onPinChange = viewModel::updatePin,
                    onTargetChange = viewModel::selectTarget,
                    onPrepare = viewModel::prepareModels,
                    onStart = viewModel::start,
                    onPauseResume = viewModel::pauseOrResume,
                    onStop = viewModel::stop,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.handleIntent(intent)
    }

    @Composable
    private fun NotificationPermissionEffect() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { }
        LaunchedEffect(Unit) {
            if (ContextCompat.checkSelfPermission(
                    this@ClientMainActivity,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ClientScreen(
    state: ClientUiState,
    onAddressChange: (String) -> Unit,
    onPinChange: (String) -> Unit,
    onTargetChange: (ClientTargetLanguage) -> Unit,
    onPrepare: () -> Unit,
    onStart: () -> Unit,
    onPauseResume: () -> Unit,
    onStop: () -> Unit,
) {
    val busy = state.phase == ClientSessionPhase.PREPARING_MODELS ||
        state.phase == ClientSessionPhase.CONNECTING
    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF4F7FB)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "GUIDECAST CLIENT",
                color = Color(0xFF1267E8),
                fontWeight = FontWeight.ExtraBold,
                fontSize = 13.sp,
                letterSpacing = 1.4.sp,
            )
            Text(
                text = "내 폰에서 듣는 실시간 통역",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF172033),
            )
            Text(
                text = "가이드의 한국어 원음만 받고, 전사·번역·통역 음성은 이 휴대폰에서 처리합니다.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF536078),
            )

            StatusCard(state)

            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(20.dp),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text("1. 방송 연결", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    OutlinedTextField(
                        value = state.address,
                        onValueChange = onAddressChange,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.sessionActive,
                        singleLine = true,
                        label = { Text("송출기 웹 주소") },
                        supportingText = { Text("QR로 연 주소를 붙여넣어도 됩니다.") },
                    )
                    OutlinedTextField(
                        value = state.pin,
                        onValueChange = onPinChange,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.sessionActive,
                        singleLine = true,
                        label = { Text("방송 PIN · 선택사항") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    )
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(20.dp),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text("2. 내가 들을 언어", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ClientTargetLanguage.entries.forEach { target ->
                            FilterChip(
                                selected = state.target == target,
                                onClick = { onTargetChange(target) },
                                enabled = !state.sessionActive,
                                label = { Text(target.displayName) },
                            )
                        }
                    }
                    Text(
                        text = state.target.detail,
                        color = Color(0xFF536078),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        onClick = onPrepare,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy && !state.sessionActive,
                    ) {
                        if (state.phase == ClientSessionPhase.PREPARING_MODELS) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Color.White,
                            )
                            Spacer(Modifier.size(10.dp))
                        }
                        Text(if (state.modelReady) "언어팩 다시 점검" else "언어팩 준비")
                    }
                    Text(
                        text = "최초 준비에는 인터넷이 필요합니다. 준비 완료 뒤에는 같은 언어를 오프라인에서 사용합니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF6B768C),
                    )
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(20.dp),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("3. 통역 청취", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Button(
                        onClick = onStart,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.sessionActive && !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1267E8)),
                    ) {
                        Text("통역 시작")
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick = onPauseResume,
                            modifier = Modifier.weight(1f),
                            enabled = state.phase == ClientSessionPhase.LISTENING ||
                                state.phase == ClientSessionPhase.PAUSED,
                        ) {
                            Text(if (state.phase == ClientSessionPhase.PAUSED) "계속 듣기" else "일시정지")
                        }
                        OutlinedButton(
                            onClick = onStop,
                            modifier = Modifier.weight(1f),
                            enabled = state.sessionActive || state.phase == ClientSessionPhase.ERROR,
                        ) {
                            Text("중지")
                        }
                    }
                }
            }

            TranscriptCard(state)

            Text(
                text = "홍콩 항목은 번체 중국어 표기를 제공합니다. 현재 Moonshine 공식 음성팩에는 광둥어가 없어 중국어 음성으로 읽습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF6B768C),
                modifier = Modifier.padding(bottom = 20.dp),
            )
        }
    }
}

@Composable
private fun StatusCard(state: ClientUiState) {
    val color = when (state.phase) {
        ClientSessionPhase.LISTENING -> Color(0xFF0B8F65)
        ClientSessionPhase.PAUSED -> Color(0xFFB06B00)
        ClientSessionPhase.ERROR -> Color(0xFFC53B3B)
        else -> Color(0xFF1267E8)
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.09f)),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                stateDescription = state.errorMessage ?: state.statusMessage
            },
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(11.dp)
                    .background(color, RoundedCornerShape(50)),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.errorMessage ?: state.statusMessage,
                    color = if (state.errorMessage != null) Color(0xFF9F2525) else Color(0xFF25314A),
                    fontWeight = FontWeight.SemiBold,
                )
                if (state.receivedFrames > 0) {
                    Text(
                        text = "원음 ${state.receivedFrames} 프레임 수신",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF536078),
                    )
                }
            }
        }
    }
}

@Composable
private fun TranscriptCard(state: ClientUiState) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF172033)),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("실시간 스크립트", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("한국어 원문", color = Color(0xFF9FB3D1), style = MaterialTheme.typography.labelMedium)
            Text(
                text = state.originalTranscript.ifBlank { "수신된 말이 여기에 표시됩니다." },
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.14f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = state.target.displayName,
                    color = Color(0xFF8EC5FF),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                state.translationLatencyMillis?.let { latency ->
                    Text("번역 ${latency}ms", color = Color(0xFF9FB3D1), fontSize = 12.sp)
                }
            }
            Text(
                text = state.translatedTranscript.ifBlank { "번역문과 통역 음성이 준비되면 표시됩니다." },
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun GuideCastClientTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF1267E8),
            secondary = Color(0xFF0B8F65),
            background = Color(0xFFF4F7FB),
            surface = Color.White,
        ),
        content = content,
    )
}
