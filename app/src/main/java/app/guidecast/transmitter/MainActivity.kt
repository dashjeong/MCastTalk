package app.guidecast.transmitter

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.log10
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.guidecast.core.audio.AudioInputDevice
import app.guidecast.core.audio.AudioInputKind
import app.guidecast.core.audio.AudioInputPreference
import app.guidecast.core.audio.AudioOutputDevice
import app.guidecast.core.audio.AudioOutputKind
import app.guidecast.core.audio.PlaybackCaptureDiagnostics
import app.guidecast.core.translation.ModelReadiness
import app.guidecast.provider.gemma.translation.GemmaModelManager
import app.guidecast.provider.gemma.translation.GemmaModelVariant
import app.guidecast.provider.gemma.translation.GemmaModelReadiness
import app.guidecast.provider.gemma.translation.GemmaTranslationProvider
import app.guidecast.provider.moonshine.tts.MoonshineTtsReadiness
import app.guidecast.provider.moonshine.tts.MoonshineTtsStatus
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val viewModel: AudioInputViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GuideCastTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                val broadcast by viewModel.broadcastState.collectAsStateWithLifecycle()
                val translationModels by viewModel.translationModelState.collectAsStateWithLifecycle()
                val gemmaState by viewModel.gemmaState.collectAsStateWithLifecycle()
                val transcriptArchive by viewModel.transcriptArchiveState.collectAsStateWithLifecycle()
                val context = LocalContext.current
                var permissions by remember { mutableStateOf(context.inputPermissionState()) }
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) {
                    permissions = context.inputPermissionState()
                    viewModel.refresh()
                }
                val projectionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult(),
                ) { result ->
                    val data = result.data
                    if (result.resultCode == Activity.RESULT_OK && data != null) {
                        viewModel.startInput(result.resultCode, data)
                    } else {
                        viewModel.reportProjectionDenied()
                    }
                }
                val catalogImportLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument(),
                ) { uri -> if (uri != null) viewModel.importGemmaCatalog(uri) }
                val modelImportLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    if (uri != null) {
                        runCatching {
                            context.contentResolver.takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION,
                            )
                        }
                        viewModel.importGemmaModel(uri)
                    }
                }

                GuideCastScreen(
                    state = state,
                    broadcast = broadcast,
                    translationModels = translationModels,
                    gemmaState = gemmaState,
                    transcriptArchive = transcriptArchive,
                    permissions = permissions,
                    onRequestPermissions = { requested -> permissionLauncher.launch(requested) },
                    onSelectAutomatic = viewModel::useAutomaticSelection,
                    onSelectDevice = viewModel::selectDevice,
                    onSelectPlaybackTarget = viewModel::selectPlaybackTarget,
                    onRegisterPlaybackTarget = viewModel::registerPlaybackTarget,
                    onSelectSourceLanguage = viewModel::selectSourceLanguage,
                    onToggleTranslationLanguage = viewModel::toggleTranslationLanguage,
                    onSelectAllTranslationLanguages = viewModel::selectAllTranslationLanguages,
                    onClearTranslationLanguages = viewModel::clearTranslationLanguages,
                    onPrepareTranslationModels = viewModel::prepareSelectedTranslationModels,
                    onSelectSpeechVoice = viewModel::selectSpeechVoice,
                    onCancelModelPreparation = viewModel::cancelModelPreparation,
                    onRemoveTranslationModel = viewModel::removeTranslationModel,
                    onSetTranslationBroadcastEnabled = viewModel::setTranslationBroadcastEnabled,
                    onDownloadGemma = viewModel::downloadGemmaModel,
                    onSelectGemmaModel = viewModel::selectGemmaModel,
                    onImportGemmaCatalog = { catalogImportLauncher.launch(arrayOf("*/*")) },
                    onRemoveGemma = viewModel::removeGemmaModel,
                    onSetUseGemma = viewModel::setUseGemma,
                    onSetSelectiveTranslationRefinement =
                        viewModel::setSelectiveTranslationRefinement,
                    onTestGemma = viewModel::testGemma,
                    onImportGemma = {
                        modelImportLauncher.launch(
                            arrayOf("application/octet-stream", "application/*", "*/*"),
                        )
                    },
                    onStartInput = {
                        if (state.selectedDevice?.kind == AudioInputKind.DEVICE_PLAYBACK) {
                            val manager = context.getSystemService(MediaProjectionManager::class.java)
                            projectionLauncher.launch(manager.createGuideCastCaptureIntent())
                        } else {
                            viewModel.startInput()
                        }
                    },
                    onPauseInput = viewModel::pauseInput,
                    onResumeInput = viewModel::resumeInput,
                    onStopInput = viewModel::stopInput,
                    onStartTranslationTest = viewModel::startTranslationTest,
                    onStopTranslationTest = viewModel::stopTranslationTest,
                    onClearTranscripts = viewModel::clearTranscripts,
                    onDeleteArchivedTranscripts = viewModel::deleteArchivedTranscripts,
                    onDeleteArchivedTranscriptSession = viewModel::deleteArchivedTranscriptSession,
                    onStartBroadcast = viewModel::startBroadcast,
                    onPauseBroadcast = viewModel::pauseBroadcast,
                    onResumeBroadcast = viewModel::resumeBroadcast,
                    onStopBroadcast = viewModel::stopBroadcast,
                    onPlayTestTone = viewModel::playTestTone,
                    onStartLocalMonitor = viewModel::startLocalMonitor,
                    onPauseLocalMonitor = viewModel::pauseLocalMonitor,
                    onResumeLocalMonitor = viewModel::resumeLocalMonitor,
                    onStopLocalMonitor = viewModel::stopLocalMonitor,
                    onLocalMonitorVolume = viewModel::setLocalMonitorVolume,
                )
            }
        }
    }
}

private fun Context.inputPermissionState() = InputPermissionState(
    recordAudioGranted = hasPermission(Manifest.permission.RECORD_AUDIO),
    bluetoothConnectGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        hasPermission(Manifest.permission.BLUETOOTH_CONNECT),
    notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        hasPermission(Manifest.permission.POST_NOTIFICATIONS),
)

private fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

@Composable
private fun GuideCastScreen(
    state: AudioInputUiState,
    broadcast: BroadcastSnapshot,
    translationModels: TranslationModelUiState,
    gemmaState: GemmaUiState,
    transcriptArchive: TranscriptArchiveSnapshot,
    permissions: InputPermissionState,
    onRequestPermissions: (Array<String>) -> Unit,
    onSelectAutomatic: () -> Unit,
    onSelectDevice: (Int) -> Unit,
    onSelectPlaybackTarget: (String) -> Unit,
    onRegisterPlaybackTarget: (String) -> Unit,
    onSelectSourceLanguage: (String) -> Unit,
    onToggleTranslationLanguage: (String) -> Unit,
    onSelectAllTranslationLanguages: () -> Unit,
    onClearTranslationLanguages: () -> Unit,
    onPrepareTranslationModels: () -> Unit,
    onSelectSpeechVoice: (String, SpeechVoicePreference) -> Unit,
    onCancelModelPreparation: () -> Unit,
    onRemoveTranslationModel: (String) -> Unit,
    onSetTranslationBroadcastEnabled: (Boolean) -> Unit,
    onDownloadGemma: () -> Unit,
    onSelectGemmaModel: (GemmaModelVariant) -> Unit = {},
    onImportGemmaCatalog: () -> Unit = {},
    onRemoveGemma: () -> Unit,
    onSetUseGemma: (Boolean) -> Unit,
    onSetSelectiveTranslationRefinement: (Boolean) -> Unit,
    onTestGemma: () -> Unit,
    onImportGemma: () -> Unit,
    onStartInput: () -> Unit,
    onPauseInput: () -> Unit,
    onResumeInput: () -> Unit,
    onStopInput: () -> Unit,
    onStartTranslationTest: (String) -> Unit,
    onStopTranslationTest: () -> Unit,
    onClearTranscripts: () -> Unit,
    onDeleteArchivedTranscripts: (Set<TranscriptArchiveKey>) -> Unit,
    onDeleteArchivedTranscriptSession: (Long) -> Unit,
    onStartBroadcast: (OperatorAccessMode, CharArray?, CharArray?) -> Unit,
    onPauseBroadcast: () -> Unit,
    onResumeBroadcast: () -> Unit,
    onStopBroadcast: () -> Unit,
    onPlayTestTone: () -> Unit,
    onStartLocalMonitor: (String, Int) -> Unit,
    onPauseLocalMonitor: () -> Unit,
    onResumeLocalMonitor: () -> Unit,
    onStopLocalMonitor: () -> Unit,
    onLocalMonitorVolume: (Float) -> Unit,
) {
    var section by rememberSaveable { mutableStateOf(GuideCastSection.BROADCAST) }
    var showLicenses by rememberSaveable { mutableStateOf(false) }
    var showGlossary by rememberSaveable { mutableStateOf(false) }
    var showSpeechCorrections by rememberSaveable { mutableStateOf(false) }
    var settingsCategory by rememberSaveable { mutableStateOf(SettingsCategory.LANGUAGES) }
    val noiseSettings = (LocalContext.current.applicationContext as GuideCastApplication).microphoneNoiseSettings
    val noiseMode by noiseSettings.mode.collectAsStateWithLifecycle()
    val glossaryWarning by (LocalContext.current.applicationContext as GuideCastApplication).glossary.warning.collectAsStateWithLifecycle()
    var sectionTopRequest by remember { mutableStateOf(0) }
    var accessMode by rememberSaveable { mutableStateOf(OperatorAccessMode.OPEN) }
    var broadcastPin by remember { mutableStateOf("") }
    var micPinEnabled by rememberSaveable { mutableStateOf(false) }
    var micPin by remember { mutableStateOf("") }
    val broadcastListState = rememberLazyListState()
    val testListState = rememberLazyListState()
    val settingsListState = rememberLazyListState()
    val currentListState = when (section) {
        GuideCastSection.BROADCAST -> broadcastListState
        GuideCastSection.TEST -> testListState
        GuideCastSection.MODELS -> settingsListState
    }
    BackHandler(enabled = showLicenses) { showLicenses = false }
    BackHandler(enabled = showGlossary) { showGlossary = false }
    BackHandler(enabled = showSpeechCorrections) { showSpeechCorrections = false }
    val inputActive = broadcast.inputPhase == InputPhase.STARTING ||
        broadcast.inputPhase == InputPhase.ACTIVE ||
        broadcast.inputPhase == InputPhase.PAUSED
    val broadcastActive = broadcast.phase == BroadcastPhase.STARTING ||
        broadcast.phase == BroadcastPhase.LIVE ||
        broadcast.phase == BroadcastPhase.PAUSED
    val effectiveAccessMode = if (broadcastActive) {
        broadcast.accessMode ?: accessMode
    } else {
        accessMode
    }
    LaunchedEffect(section, sectionTopRequest) {
        currentListState.scrollToItem(0)
    }
    LaunchedEffect(broadcastActive) {
        if (broadcastActive) broadcastListState.scrollToItem(0)
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            OperatorHeader(
                broadcast = broadcast,
                onOpenBroadcast = {
                    showLicenses = false
                    showGlossary = false
                    showSpeechCorrections = false
                    section = GuideCastSection.BROADCAST
                    sectionTopRequest += 1
                },
                onOpenLicenses = { showSpeechCorrections = false; showGlossary = false; showLicenses = true },
                onStopTranslationTest = onStopTranslationTest,
            )
        },
        bottomBar = {
            if (!showLicenses && !showGlossary && !showSpeechCorrections) {
                GuideCastSectionTabs(
                    section = section,
                    onSelect = {
                        section = it
                        sectionTopRequest += 1
                    },
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { scaffoldPadding ->
        if (showSpeechCorrections) {
            SpeechCorrectionScreen(
                modifier = Modifier.padding(scaffoldPadding),
                initialLanguageTag = translationModels.selectedSourceLanguageTag,
                onBack = { showSpeechCorrections = false },
            )
            return@Scaffold
        }
        if (showGlossary) {
            GlossaryScreen(Modifier.padding(scaffoldPadding), onBack = { showGlossary = false })
            return@Scaffold
        }
        if (showLicenses) {
            LicenseScreen(
                modifier = Modifier.padding(scaffoldPadding),
                onBack = { showLicenses = false },
            )
            return@Scaffold
        }
        LazyColumn(
            state = currentListState,
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding)
                .imePadding(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            glossaryWarning?.let { warning ->
                item { Text(warning, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
            item {
                SectionIntroduction(
                    eyebrow = section.eyebrow,
                    title = section.title,
                    description = section.description,
                )
            }

            val needsAudioPermission = !permissions.recordAudioGranted &&
                section != GuideCastSection.MODELS
            val needsBluetoothPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                !permissions.bluetoothConnectGranted &&
                section == GuideCastSection.BROADCAST
            val needsNotificationPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !permissions.notificationsGranted &&
                section == GuideCastSection.BROADCAST
            if (needsAudioPermission || needsBluetoothPermission || needsNotificationPermission) {
                item {
                    PermissionSetupCard(
                        needsAudioPermission = needsAudioPermission,
                        needsBluetoothPermission = needsBluetoothPermission,
                        needsNotificationPermission = needsNotificationPermission,
                        onRequestAudio = {
                            onRequestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO))
                        },
                        onRequestBluetooth = {
                            onRequestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
                        },
                        onRequestNotifications = {
                            onRequestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                        },
                    )
                }
            }

            when (section) {
                GuideCastSection.BROADCAST -> {
                    if (broadcastActive) {
                        item {
                            BroadcastControls(
                                broadcast = broadcast,
                                accessMode = effectiveAccessMode,
                                pin = broadcastPin,
                                micPinEnabled = micPinEnabled,
                                micPin = micPin,
                                canStart = true,
                                onAccessModeChange = { accessMode = it },
                                onPinChange = { broadcastPin = it },
                                onMicPinEnabledChange = { micPinEnabled = it },
                                onMicPinChange = { micPin = it },
                                onStart = onStartBroadcast,
                                onPause = onPauseBroadcast,
                                onResume = onResumeBroadcast,
                                onStop = onStopBroadcast,
                                onPlayTestTone = onPlayTestTone,
                            )
                        }
                    }
                    item {
                        InputSourcePicker(
                            state = state,
                            sourceLanguageLabel = translationModels.selectedSourceLanguage.label,
                            enabled = !inputActive,
                            permissionsGranted = permissions.recordAudioGranted,
                            onSelectAutomatic = onSelectAutomatic,
                            onSelectDevice = onSelectDevice,
                        )
                    }
                    item {
                        InputControls(
                            broadcast = broadcast,
                            selectedDevice = state.selectedDevice,
                            playbackDiagnostics = state.playbackCaptureDiagnostics,
                            playbackTargetApps = state.playbackTargetApps,
                            selectedPlaybackTarget = state.selectedPlaybackTarget,
                            playbackTargetRegistrationMessage =
                                state.playbackTargetRegistrationMessage,
                            canStart = permissions.canStartInput(state.selectedDevice?.kind) &&
                                (state.selectedDevice?.kind != AudioInputKind.DEVICE_PLAYBACK ||
                                    state.selectedPlaybackTarget != null),
                            onSelectPlaybackTarget = onSelectPlaybackTarget,
                            onRegisterPlaybackTarget = onRegisterPlaybackTarget,
                            onStart = onStartInput,
                            onPause = onPauseInput,
                            onResume = onResumeInput,
                            onStop = onStopInput,
                        )
                    }
                    item {
                        MicrophoneNoiseOptions(noiseMode, !inputActive &&
                            state.selectedDevice?.kind != AudioInputKind.DEVICE_PLAYBACK &&
                            state.selectedDevice?.kind != AudioInputKind.WEB_SPEAKER, noiseSettings::select)
                    }
                    item { OperatorStatusStrip(broadcast = broadcast, models = translationModels) }
                    item {
                        LiveSentenceMonitor(
                            broadcast = broadcast,
                            models = translationModels,
                            onOpenTest = { section = GuideCastSection.TEST },
                        )
                    }
                    item {
                        WorkspaceDisclosure(
                            title = "처리 상태 · 입력 진단",
                            summary = "입력·인식·번역·음성·웹 송출을 각각 확인합니다. 방송 시작과 중지는 직접 결정할 수 있습니다.",
                        ) {
                            ReadinessCard(state, broadcast, permissions)
                            ProcessingPipelineCard(broadcast = broadcast, models = translationModels)
                        }
                    }
                    item {
                        OutputChannelsCard(
                            broadcast = broadcast,
                            models = translationModels,
                            onOpenTest = { section = GuideCastSection.TEST },
                        )
                    }
                    if (broadcastActive) {
                        item {
                            BroadcastLocalMonitorCard(
                                broadcast = broadcast,
                                selectedInput = state.selectedDevice,
                                outputDevices = state.availableOutputDevices,
                                onStart = onStartLocalMonitor,
                                onPause = onPauseLocalMonitor,
                                onResume = onResumeLocalMonitor,
                                onStop = onStopLocalMonitor,
                                onVolume = onLocalMonitorVolume,
                            )
                        }
                    }
                    item {
                        BroadcastModeSelector(
                            translationEnabled = translationModels.broadcastTranslationEnabled,
                            selectedInputKind = state.selectedDevice?.kind,
                            selectedLanguageLabels = selectedTranslationLanguageOptions(translationModels)
                                .map { it.label.substringBefore(" ·") },
                            enabled = !broadcastActive,
                            onSetTranslationEnabled = onSetTranslationBroadcastEnabled,
                        )
                    }
                    if (!broadcastActive) {
                        item {
                            BroadcastControls(
                                broadcast = broadcast,
                                accessMode = effectiveAccessMode,
                                pin = broadcastPin,
                                micPinEnabled = micPinEnabled,
                                micPin = micPin,
                                canStart = true,
                                onAccessModeChange = { accessMode = it },
                                onPinChange = { broadcastPin = it },
                                onMicPinEnabledChange = { micPinEnabled = it },
                                onMicPinChange = { micPin = it },
                                onStart = onStartBroadcast,
                                onPause = onPauseBroadcast,
                                onResume = onResumeBroadcast,
                                onStop = onStopBroadcast,
                                onPlayTestTone = onPlayTestTone,
                            )
                        }
                    }
                }

                GuideCastSection.TEST -> {
                    item {
                        InputControls(
                            broadcast = broadcast,
                            selectedDevice = state.selectedDevice,
                            playbackDiagnostics = state.playbackCaptureDiagnostics,
                            playbackTargetApps = state.playbackTargetApps,
                            selectedPlaybackTarget = state.selectedPlaybackTarget,
                            playbackTargetRegistrationMessage =
                                state.playbackTargetRegistrationMessage,
                            canStart = permissions.canStartInput(state.selectedDevice?.kind) &&
                                (state.selectedDevice?.kind != AudioInputKind.DEVICE_PLAYBACK ||
                                    state.selectedPlaybackTarget != null),
                            onSelectPlaybackTarget = onSelectPlaybackTarget,
                            onRegisterPlaybackTarget = onRegisterPlaybackTarget,
                            onStart = onStartInput,
                            onPause = onPauseInput,
                            onResume = onResumeInput,
                            onStop = onStopInput,
                        )
                    }
                    item {
                        TranslationTestPanel(
                            broadcast = broadcast,
                            models = translationModels,
                            onStart = onStartTranslationTest,
                            onStop = onStopTranslationTest,
                            onClear = onClearTranscripts,
                        )
                    }
                    item {
                        WorkspaceDisclosure(
                            title = "방송 스크립트 보관함",
                            summary = transcriptArchive.warning ?: "최근 ${transcriptArchive.lines.size}개 문장 · 언어별 조회 및 선택 삭제",
                        ) {
                            TranscriptArchivePanel(
                                archive = transcriptArchive,
                                onDeleteSelected = onDeleteArchivedTranscripts,
                                onDeleteSession = onDeleteArchivedTranscriptSession,
                            )
                        }
                    }
                }

                GuideCastSection.MODELS -> {
                  item(key = "settings-navigation") {
                    SettingsCategoryPicker(settingsCategory) {
                        settingsCategory = it
                        sectionTopRequest += 1
                    }
                  }
                  item(key = "settings-${settingsCategory.name}") {
                    if (broadcast.translationTestActive) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = GuideCastWarningContainer,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "통번역 시험 진행 중",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = GuideCastWarning,
                                    )
                                    Text(
                                        "언어 설정을 변경하려면 시험을 중지하세요.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = GuideCastWarning,
                                    )
                                }
                                Button(
                                    onClick = onStopTranslationTest,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error,
                                    ),
                                ) {
                                    Text("시험 중지")
                                }
                            }
                        }
                    }
                    if (settingsCategory == SettingsCategory.LANGUAGES) TranslationModelCard(
                        state = translationModels,
                        enabled = !broadcastActive && !broadcast.translationTestActive,
                        onSelectSource = onSelectSourceLanguage,
                        onToggle = onToggleTranslationLanguage,
                        onSelectAll = onSelectAllTranslationLanguages,
                        onClearSelection = onClearTranslationLanguages,
                        onPrepare = onPrepareTranslationModels,
                        onSelectSpeechVoice = onSelectSpeechVoice,
                        onCancelPreparation = onCancelModelPreparation,
                        onRemove = onRemoveTranslationModel,
                    )
                    if (settingsCategory == SettingsCategory.MODELS) GemmaModelCard(
                        state = gemmaState,
                        enabled = !broadcastActive && !broadcast.translationTestActive,
                        onSelectModel = onSelectGemmaModel,
                        onImportCatalog = onImportGemmaCatalog,
                        onDownload = onDownloadGemma,
                        onRemove = onRemoveGemma,
                        onSetUseGemma = onSetUseGemma,
                        selectiveRefinementControlEnabled = !inputActive,
                        onSetSelectiveTranslationRefinement =
                            onSetSelectiveTranslationRefinement,
                        onTest = onTestGemma,
                        onImport = onImportGemma,
                    )
                    if (settingsCategory == SettingsCategory.TOOLS) Column(
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        OutlinedButton(
                            onClick = { showGlossary = true },
                            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 56.dp),
                        ) { Text("번역 용어 사전 · 검색 / 수정 / 일괄 등록") }
                        OutlinedButton(
                            onClick = { showSpeechCorrections = true },
                            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 56.dp),
                        ) { Text("인식 학습·보정") }
                        SettingsInformationCard(onOpenLicenses = { showLicenses = true })
                        DiagnosticsAndVoiceSettings()
                    }
                  }
                }
            }
        }
    }
}

private enum class GuideCastSection(
    val label: String,
    val eyebrow: String,
    val title: String,
    val description: String,
) {
    BROADCAST("운영", "현장 운영", "안내방송 운영", "입력부터 청취자 웹 송출까지 한눈에 확인하세요."),
    TEST("시험", "방송 전 점검", "통번역 사전 점검", "실제 문장과 통역 음성을 먼저 듣고 확인하세요."),
    MODELS("설정", "방송 환경", "언어·모델 설정", "송출 언어와 오프라인 모델을 관리하세요."),
}

@Composable
private fun GuideCastSectionTabs(
    section: GuideCastSection,
    onSelect: (GuideCastSection) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 10.dp,
    ) {
        NavigationBar(
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
        ) {
            GuideCastSection.entries.forEach { item ->
                val selected = item == section
                NavigationBarItem(
                    selected = selected,
                    onClick = { onSelect(item) },
                    icon = {
                        Box(
                            modifier = Modifier
                                .size(width = 40.dp, height = 28.dp)
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else Color.Transparent,
                                    CircleShape,
                                )
                                .clearAndSetSemantics { },
                            contentAlignment = Alignment.Center,
                        ) {
                            GuideCastSectionIcon(section = item, selected = selected)
                        }
                    },
                    label = {
                        Text(
                            item.label,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = Color.Transparent,
                        unselectedIconColor = MaterialTheme.colorScheme.outlineVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }
        }
    }
}

@Composable
private fun GuideCastSectionIcon(section: GuideCastSection, selected: Boolean) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val iconContainerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    Canvas(
        modifier = Modifier
            .size(22.dp)
            .clearAndSetSemantics { },
    ) {
        val strokeWidth = if (selected) 2.2.dp.toPx() else 1.8.dp.toPx()
        when (section) {
            GuideCastSection.BROADCAST -> {
                drawCircle(color = color, radius = 2.4.dp.toPx(), center = Offset(size.width / 2f, size.height / 2f))
                drawArc(
                    color = color,
                    startAngle = 135f,
                    sweepAngle = 270f,
                    useCenter = false,
                    topLeft = Offset(5.dp.toPx(), 5.dp.toPx()),
                    size = Size(12.dp.toPx(), 12.dp.toPx()),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
                drawArc(
                    color = color,
                    startAngle = 135f,
                    sweepAngle = 270f,
                    useCenter = false,
                    topLeft = Offset(1.5.dp.toPx(), 1.5.dp.toPx()),
                    size = Size(19.dp.toPx(), 19.dp.toPx()),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
            }

            GuideCastSection.TEST -> {
                drawCircle(
                    color = color,
                    radius = 9.dp.toPx(),
                    center = Offset(size.width / 2f, size.height / 2f),
                    style = Stroke(width = strokeWidth),
                )
                val check = Path().apply {
                    moveTo(6.5.dp.toPx(), 11.dp.toPx())
                    lineTo(9.5.dp.toPx(), 14.dp.toPx())
                    lineTo(15.7.dp.toPx(), 7.8.dp.toPx())
                }
                drawPath(check, color = color, style = Stroke(width = strokeWidth, cap = StrokeCap.Round))
            }

            GuideCastSection.MODELS -> {
                val positions = listOf(7f to 7f, 15f to 11f, 10f to 15f)
                positions.forEach { (knobX, lineY) ->
                    val y = lineY.dp.toPx()
                    drawLine(
                        color = color,
                        start = Offset(2.dp.toPx(), y),
                        end = Offset(20.dp.toPx(), y),
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round,
                    )
                    drawCircle(
                        color = iconContainerColor,
                        radius = 3.dp.toPx(),
                        center = Offset(knobX.dp.toPx(), y),
                    )
                    drawCircle(
                        color = color,
                        radius = 2.3.dp.toPx(),
                        center = Offset(knobX.dp.toPx(), y),
                        style = Stroke(width = strokeWidth),
                    )
                }
            }
        }
    }
}

@Composable
private fun OperatorHeader(
    broadcast: BroadcastSnapshot,
    onOpenBroadcast: () -> Unit,
    onOpenLicenses: () -> Unit,
    onStopTranslationTest: () -> Unit,
) {
    val active = broadcast.phase == BroadcastPhase.LIVE || broadcast.phase == BroadcastPhase.PAUSED
    val serverLabel = when {
        broadcast.phase == BroadcastPhase.LIVE -> "방송 중"
        broadcast.phase == BroadcastPhase.PAUSED -> "일시정지"
        broadcast.phase == BroadcastPhase.STARTING -> "방송 준비 중"
        broadcast.translationTestActive -> "통번역 시험 중"
        broadcast.phase == BroadcastPhase.FAILED -> "확인 필요"
        else -> "방송 대기"
    }
    val statusColor = when {
        broadcast.phase == BroadcastPhase.LIVE -> GuideCastSuccess
        broadcast.phase == BroadcastPhase.PAUSED ||
            broadcast.phase == BroadcastPhase.STARTING ||
            broadcast.translationTestActive -> GuideCastWarning
        broadcast.phase == BroadcastPhase.FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusContainer = when {
        broadcast.phase == BroadcastPhase.LIVE -> GuideCastSuccessContainer
        broadcast.phase == BroadcastPhase.PAUSED ||
            broadcast.phase == BroadcastPhase.STARTING ||
            broadcast.translationTestActive -> GuideCastWarningContainer
        broadcast.phase == BroadcastPhase.FAILED -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.app_subtitle),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    onClick = onOpenLicenses,
                ) { Text("라이선스") }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .semantics(mergeDescendants = true) {
                            liveRegion = LiveRegionMode.Polite
                        },
                    color = statusContainer,
                    shape = CircleShape,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(statusColor, CircleShape)
                                .clearAndSetSemantics { },
                        )
                        Text(serverLabel, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (broadcast.translationTestActive && !active) "· 단말 점검" else "· 청취자 ${broadcast.listenerCount}명",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (active) {
                    TextButton(
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                        onClick = onOpenBroadcast,
                    ) { Text("방송 보기") }
                } else if (broadcast.translationTestActive) {
                    Button(
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                        onClick = onStopTranslationTest,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                        ),
                    ) { Text("시험 중지") }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun SectionIntroduction(
    eyebrow: String,
    title: String,
    description: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            eyebrow,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            description,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun PermissionSetupCard(
    needsAudioPermission: Boolean,
    needsBluetoothPermission: Boolean,
    needsNotificationPermission: Boolean,
    onRequestAudio: () -> Unit,
    onRequestBluetooth: () -> Unit,
    onRequestNotifications: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (needsAudioPermission) {
                GuideCastWarningContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        border = BorderStroke(
            1.dp,
            if (needsAudioPermission) GuideCastWarning.copy(alpha = 0.35f)
            else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (needsAudioPermission) {
                Text(
                    "먼저 할 일",
                    style = MaterialTheme.typography.labelMedium,
                    color = GuideCastWarning,
                )
                Text(
                    "오디오 입력 권한이 필요합니다",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "가이드 음성을 받아 방송하려면 마이크 권한을 허용하세요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    modifier = Modifier
                        .fillMaxWidth()
                        .sizeIn(minHeight = 52.dp),
                    onClick = onRequestAudio,
                ) { Text("오디오 입력 허용") }
            }

            if (needsBluetoothPermission || needsNotificationPermission) {
                if (needsAudioPermission) {
                    HorizontalDivider(color = GuideCastWarning.copy(alpha = 0.35f))
                }
                Text(
                    "선택 권한 · 허용하지 않아도 기본 방송은 동작합니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (needsBluetoothPermission) {
                        OutlinedButton(
                            modifier = Modifier
                                .weight(1f)
                                .sizeIn(minHeight = 48.dp),
                            onClick = onRequestBluetooth,
                        ) { Text("Bluetooth") }
                    }
                    if (needsNotificationPermission) {
                        OutlinedButton(
                            modifier = Modifier
                                .weight(1f)
                                .sizeIn(minHeight = 48.dp),
                            onClick = onRequestNotifications,
                        ) { Text("상태 알림") }
                    }
                }
            }
        }
    }
}

internal enum class OperatorStatusTone { READY, WORKING, WARNING, ERROR, NEUTRAL }

internal data class OperatorStageStatus(
    val label: String,
    val state: String,
    val tone: OperatorStatusTone,
)

internal fun selectedTranslationLanguageOptions(
    state: TranslationModelUiState,
): List<TranslationLanguageOption> = state.selectedLanguageTags.mapNotNull { selectedTag ->
    state.options.firstOrNull { it.languageTag == selectedTag }
}

internal fun translationModelReadyForChannel(
    models: TranslationModelUiState,
    languageTag: String,
): Boolean {
    val lightweightFallbackReady = models.readiness(languageTag) == ModelReadiness.READY
    return if (models.useGemma &&
        GemmaTranslationProvider.supportsTranslation(models.selectedSourceLanguageTag, languageTag)
    ) {
        models.gemmaReady || lightweightFallbackReady
    } else {
        lightweightFallbackReady
    }
}

internal fun translationWorkerStatus(
    channel: BroadcastChannelSnapshot?,
    modelReady: Boolean,
): OperatorStageStatus = when {
    channel?.translationState == BroadcastChannelWorkerState.DEGRADED ||
        channel?.lastTranslationError != null ->
        OperatorStageStatus("번역", "복구 필요", OperatorStatusTone.ERROR)
    channel?.translationState == BroadcastChannelWorkerState.ACTIVE ->
        OperatorStageStatus("번역", "처리 중", OperatorStatusTone.WORKING)
    channel?.lastTranslatedSequence != null ->
        OperatorStageStatus("번역", "정상", OperatorStatusTone.READY)
    modelReady -> OperatorStageStatus("번역", "문장 대기", OperatorStatusTone.READY)
    else -> OperatorStageStatus("번역", "준비 필요", OperatorStatusTone.WARNING)
}

internal fun synthesisWorkerStatus(
    channel: BroadcastChannelSnapshot?,
    ttsReady: Boolean,
): OperatorStageStatus = when {
    channel?.synthesisState == BroadcastChannelWorkerState.DEGRADED ||
        channel?.lastSynthesisError != null ->
        OperatorStageStatus("음성", "복구 필요", OperatorStatusTone.ERROR)
    channel?.synthesisState == BroadcastChannelWorkerState.ACTIVE ->
        OperatorStageStatus("음성", "합성 중", OperatorStatusTone.WORKING)
    channel?.listenerDroppedFrames?.let { it > 0L } == true ->
        OperatorStageStatus("음성", "일부 웹 전송 누락", OperatorStatusTone.WARNING)
    channel?.lastPublishedSequence != null && channel.listenerCount == 0 ->
        OperatorStageStatus("음성", "서버 게시·청취자 대기", OperatorStatusTone.READY)
    channel?.lastPublishedSequence != null &&
        channel.lastWebSocketDeliveredSequence == channel.lastPublishedSequence ->
        OperatorStageStatus("음성", "웹 전송 확인", OperatorStatusTone.READY)
    channel?.lastPublishedSequence != null ->
        OperatorStageStatus("음성", "서버 게시·전송 대기", OperatorStatusTone.WARNING)
    channel?.lastSynthesizedSequence != null ->
        OperatorStageStatus("음성", "음원 생성·미게시", OperatorStatusTone.WARNING)
    ttsReady -> OperatorStageStatus("음성", "번역문 대기", OperatorStatusTone.READY)
    else -> OperatorStageStatus("음성", "준비 필요", OperatorStatusTone.WARNING)
}

internal fun channelAudioDeliverySummary(channel: BroadcastChannelSnapshot?): String =
    "최근 합성 문장 ${channel?.lastSynthesizedSequence?.let { "#$it" } ?: "--"}" +
        " · 서버 게시 ${channel?.publishedFrameCount ?: 0}프레임" +
        " · 웹 전송 ${channel?.webSocketDeliveredFrameCount ?: 0}회"

/** Silence is not a recognizer failure; PCM receipt is not proof of a working microphone. */
internal fun inputListeningStatus(broadcast: BroadcastSnapshot): OperatorStageStatus = when {
    broadcast.inputErrorMessage != null || broadcast.inputPhase == InputPhase.FAILED ->
        OperatorStageStatus("입력", "입력 확인 필요", OperatorStatusTone.ERROR)
    broadcast.inputPhase == InputPhase.PAUSED ->
        OperatorStageStatus("입력", "입력 일시정지", OperatorStatusTone.NEUTRAL)
    broadcast.inputPhase != InputPhase.ACTIVE ->
        OperatorStageStatus("입력", "입력 시작 대기", OperatorStatusTone.NEUTRAL)
    broadcast.inputFrameCount == 0L ->
        OperatorStageStatus("입력", "입력 연결 중 · PCM 수신 대기", OperatorStatusTone.WORKING)
    broadcast.inputSignalActive || broadcast.inputRms >= 0.002f || broadcast.inputPeak >= 0.01f ->
        OperatorStageStatus("입력", "입력 감지", OperatorStatusTone.READY)
    else -> OperatorStageStatus("입력", "발화 대기 · PCM 수신 중 (현재 조용함)", OperatorStatusTone.NEUTRAL)
}

internal fun interpretationProgressStatus(broadcast: BroadcastSnapshot): OperatorStageStatus = when {
    broadcast.inputErrorMessage != null || broadcast.inputPhase == InputPhase.FAILED ->
        OperatorStageStatus("문장", "입력 오류 · 입력 장치를 확인하세요", OperatorStatusTone.ERROR)
    broadcast.inputPhase == InputPhase.PAUSED ->
        OperatorStageStatus("문장", "입력 일시정지 · 재개하면 이어 듣습니다", OperatorStatusTone.NEUTRAL)
    broadcast.inputPhase != InputPhase.ACTIVE ->
        OperatorStageStatus("문장", "입력을 시작하면 발화를 받습니다", OperatorStatusTone.NEUTRAL)
    !broadcast.translationTestActive && broadcast.translationChannels.isEmpty() ->
        OperatorStageStatus("문장", "입력 수신 중 · 통역 방송 또는 시험을 시작하세요", OperatorStatusTone.NEUTRAL)
    broadcast.transcripts.any { !it.isFinal } ->
        OperatorStageStatus("문장", "문장 이어 듣는 중 · 완결되면 순서대로 번역합니다", OperatorStatusTone.WORKING)
    broadcast.translationChannels.any { it.translationState == BroadcastChannelWorkerState.ACTIVE } ->
        OperatorStageStatus("문장", "확정 문장 번역 중 · 다음 발화도 계속 받습니다", OperatorStatusTone.WORKING)
    broadcast.translationChannels.any { it.synthesisState == BroadcastChannelWorkerState.ACTIVE } ->
        OperatorStageStatus("문장", "통역 음성 처리 중 · 다음 발화도 계속 받습니다", OperatorStatusTone.WORKING)
    else -> OperatorStageStatus("문장", "다음 발화를 기다립니다 · 언어별 오류는 채널 상태를 확인하세요", OperatorStatusTone.NEUTRAL)
}

internal fun broadcastChannelStatus(
    phase: BroadcastPhase,
    channel: BroadcastChannelSnapshot?,
): OperatorStageStatus {
    val channelDegraded = channel?.lastError != null ||
        channel?.translationState == BroadcastChannelWorkerState.DEGRADED ||
        channel?.synthesisState == BroadcastChannelWorkerState.DEGRADED
    return when {
        phase == BroadcastPhase.FAILED ->
            OperatorStageStatus("방송", "서버 오류", OperatorStatusTone.ERROR)
        channelDegraded ->
            OperatorStageStatus("방송", "채널 확인 필요", OperatorStatusTone.WARNING)
        phase == BroadcastPhase.LIVE ->
            OperatorStageStatus("방송", "송출 중", OperatorStatusTone.READY)
        phase == BroadcastPhase.PAUSED ->
            OperatorStageStatus("방송", "일시정지", OperatorStatusTone.WARNING)
        phase == BroadcastPhase.STARTING ->
            OperatorStageStatus("방송", "여는 중", OperatorStatusTone.WORKING)
        else -> OperatorStageStatus("방송", "대기", OperatorStatusTone.NEUTRAL)
    }
}

internal fun operatorStageStatuses(
    broadcast: BroadcastSnapshot,
    models: TranslationModelUiState,
): List<OperatorStageStatus> {
    val input = when (broadcast.inputPhase) {
        InputPhase.ACTIVE -> OperatorStageStatus("입력", "동작 중", OperatorStatusTone.READY)
        InputPhase.PAUSED -> OperatorStageStatus("입력", "일시정지", OperatorStatusTone.WARNING)
        InputPhase.STARTING -> OperatorStageStatus("입력", "준비 중", OperatorStatusTone.WORKING)
        InputPhase.FAILED -> OperatorStageStatus("입력", "확인 필요", OperatorStatusTone.ERROR)
        InputPhase.IDLE -> OperatorStageStatus("입력", "대기", OperatorStatusTone.NEUTRAL)
    }
    val selected = models.selectedLanguageTags
    val preparedCount = selected.count {
        translationModelReadyForChannel(models, it) && models.ttsReady(it)
    }
    val translationReady = selected.isNotEmpty() && selected.all {
        translationModelReadyForChannel(models, it)
    }
    val interpretation = when {
        !models.broadcastTranslationEnabled ->
            OperatorStageStatus("통역", "원음 모드", OperatorStatusTone.NEUTRAL)
        selected.isEmpty() ->
            OperatorStageStatus("통역", "언어 선택 필요", OperatorStatusTone.WARNING)
        models.speechRecognitionReady && translationReady && models.selectedTtsReady ->
            OperatorStageStatus("통역", "${selected.size}개 언어 준비", OperatorStatusTone.READY)
        models.speechRecognitionReady && preparedCount > 0 ->
            OperatorStageStatus("통역", "일부 준비 $preparedCount/${selected.size}", OperatorStatusTone.WARNING)
        else -> OperatorStageStatus("통역", "준비 확인", OperatorStatusTone.WARNING)
    }
    val server = when (broadcast.phase) {
        BroadcastPhase.LIVE -> OperatorStageStatus("웹 방송", "송출 중", OperatorStatusTone.READY)
        BroadcastPhase.PAUSED -> OperatorStageStatus("웹 방송", "일시정지", OperatorStatusTone.WARNING)
        BroadcastPhase.STARTING -> OperatorStageStatus("웹 방송", "여는 중", OperatorStatusTone.WORKING)
        BroadcastPhase.FAILED -> OperatorStageStatus("웹 방송", "오류", OperatorStatusTone.ERROR)
        BroadcastPhase.IDLE -> OperatorStageStatus("웹 방송", "대기", OperatorStatusTone.NEUTRAL)
    }
    return listOf(input, interpretation, server)
}

@Composable
private fun OperatorStatusStrip(
    broadcast: BroadcastSnapshot,
    models: TranslationModelUiState,
) {
    val statuses = operatorStageStatuses(broadcast, models)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            statuses.forEachIndexed { index, status ->
                val color = operatorStatusColor(status.tone)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .semantics(mergeDescendants = true) { },
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        status.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .background(color, CircleShape)
                                .clearAndSetSemantics { },
                        )
                        Text(
                            status.state,
                            style = MaterialTheme.typography.bodySmall,
                            color = color,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                        )
                    }
                }
                if (index < statuses.lastIndex) {
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 10.dp)
                            .size(width = 1.dp, height = 36.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusLine(status: OperatorStageStatus) {
    val color = operatorStatusColor(status.tone)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(color, CircleShape)
                .clearAndSetSemantics { },
        )
        Text(status.label, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
        Text(status.state, color = color, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun operatorStatusColor(tone: OperatorStatusTone): Color = when (tone) {
    OperatorStatusTone.READY -> GuideCastSuccess
    OperatorStatusTone.WORKING,
    OperatorStatusTone.WARNING,
    -> GuideCastWarning
    OperatorStatusTone.ERROR -> MaterialTheme.colorScheme.error
    OperatorStatusTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun InputSourcePicker(
    state: AudioInputUiState,
    sourceLanguageLabel: String,
    enabled: Boolean,
    permissionsGranted: Boolean,
    onSelectAutomatic: () -> Unit,
    onSelectDevice: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val automatic = state.preference.mode == AudioInputPreference.Mode.AUTOMATIC
    val selectionLabel = when {
        automatic && state.selectedDevice != null -> "자동 · ${state.selectedDevice.label}"
        automatic -> "자동 선택"
        state.selectedDevice != null -> state.selectedDevice.label
        else -> "입력 장치 선택"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("입력 장치", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(
                "${sourceLanguageLabel.substringBefore(" ·")} 음성 입력",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "선택된 소스만 표시합니다. 입력이 멈춘 상태에서 다른 장치로 바꿀 수 있습니다.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .sizeIn(minHeight = 48.dp),
                enabled = enabled && permissionsGranted,
                onClick = { expanded = true },
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Text(selectionLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("자동 선택")
                            Text(
                                "USB-C → 유선 → Bluetooth → 내장",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    },
                    onClick = {
                        onSelectAutomatic()
                        expanded = false
                    },
                )
                state.availableDevices.forEach { device ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(device.label)
                                Text(device.kind.displayName(), style = MaterialTheme.typography.labelSmall)
                            }
                        },
                        onClick = {
                            onSelectDevice(device.platformId)
                            expanded = false
                        },
                    )
                }
            }
            if (state.availableDevices.isEmpty()) {
                Text(
                    if (permissionsGranted) "연결된 입력 장치를 찾는 중입니다." else "오디오 권한을 먼저 허용하세요.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LiveSentenceMonitor(
    broadcast: BroadcastSnapshot,
    models: TranslationModelUiState,
    onOpenTest: () -> Unit,
) {
    val latest = broadcast.transcripts.maxByOrNull { it.sequence }
    val languageOptions = selectedTranslationLanguageOptions(models)
    var monitoredLanguage by rememberSaveable { mutableStateOf<String?>(null) }
    val firstLanguageOption = languageOptions.firstOrNull { it.languageTag == monitoredLanguage }
        ?: languageOptions.firstOrNull()
    val firstLanguage = firstLanguageOption?.languageTag
    val languageLabel = firstLanguageOption?.label
    val captureState = when (broadcast.inputPhase) {
        InputPhase.ACTIVE -> if (latest?.isFinal == true) "최근 문장 확정" else "듣는 중"
        InputPhase.PAUSED -> "입력 일시정지"
        InputPhase.STARTING -> "입력 준비 중"
        InputPhase.FAILED -> "입력 확인 필요"
        InputPhase.IDLE -> "입력 대기"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = GuideCastLiveSurface),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "실시간 문장",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Surface(color = GuideCastLiveChip, shape = CircleShape) {
                    Text(
                        captureState,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = GuideCastLiveAccent,
                    )
                }
            }
            SelectionContainer {
                Text(
                    latest?.sourceText ?: "입력을 시작하고 말하면 " +
                        "${models.selectedSourceLanguage.label.substringBefore(" ·")} 원문이 표시됩니다.",
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
            }
            latest?.takeIf { it.isFinal }?.let { line ->
                SpeechCorrectionAction(line, models.selectedSourceLanguageTag, onDark = true)
            }
            if (firstLanguage != null) {
                HorizontalDivider(color = GuideCastLiveDivider)
                if (languageOptions.size > 1) {
                    Text("자막 모니터 언어", style = MaterialTheme.typography.labelMedium, color = GuideCastLiveMuted)
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        languageOptions.forEach { option ->
                            FilterChip(
                                selected = option.languageTag == firstLanguage,
                                onClick = { monitoredLanguage = option.languageTag },
                                label = { Text(option.label.substringBefore(" ·")) },
                                modifier = Modifier.sizeIn(minHeight = 48.dp),
                                colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                                    containerColor = GuideCastLiveChip, labelColor = GuideCastLiveMuted,
                                    selectedContainerColor = GuideCastLiveAccent, selectedLabelColor = GuideCastLiveSurface,
                                ),
                            )
                        }
                    }
                }
                Text(
                    languageLabel ?: firstLanguage,
                    style = MaterialTheme.typography.labelMedium,
                    color = GuideCastLiveAccent,
                )
                SelectionContainer {
                    Text(
                        latest?.translations?.get(firstLanguage) ?: "문장이 확정되면 번역문이 표시됩니다.",
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = GuideCastLiveMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            TextButton(
                modifier = Modifier
                    .align(Alignment.End)
                    .sizeIn(minHeight = 48.dp),
                onClick = onOpenTest,
                colors = ButtonDefaults.textButtonColors(contentColor = GuideCastLiveAccent),
            ) { Text("전체 스크립트·음성 시험") }
        }
    }
}

@Composable
private fun ProcessingPipelineCard(
    broadcast: BroadcastSnapshot,
    models: TranslationModelUiState,
) {
    val selected = models.selectedLanguageTags
    val translatedReadyCount = selected.count { tag ->
        translationModelReadyForChannel(models, tag)
    }
    val ttsReadyCount = selected.count { tag ->
        models.ttsReady(tag)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("처리 흐름", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text("통번역 처리 상태", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            StatusLine(operatorStageStatuses(broadcast, models)[0])
            StatusLine(
                OperatorStageStatus(
                    "음성인식",
                    if (models.speechRecognitionReady) "준비됨" else "확인 필요",
                    if (models.speechRecognitionReady) OperatorStatusTone.READY else OperatorStatusTone.WARNING,
                ),
            )
            StatusLine(
                OperatorStageStatus(
                    "번역·TTS",
                    "$translatedReadyCount/${selected.size} 번역 · $ttsReadyCount/${selected.size} 음성",
                    if (selected.isNotEmpty() && translatedReadyCount == selected.size && ttsReadyCount == selected.size) {
                        OperatorStatusTone.READY
                    } else {
                        OperatorStatusTone.WARNING
                    },
                ),
            )
            StatusLine(operatorStageStatuses(broadcast, models)[2])
        }
    }
}

@Composable
private fun OutputChannelsCard(
    broadcast: BroadcastSnapshot,
    models: TranslationModelUiState,
    onOpenTest: () -> Unit,
) {
    val context = LocalContext.current
    var expandedQrChannelId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedOptions = selectedTranslationLanguageOptions(models)
    val channels = if (broadcast.translationChannels.isNotEmpty()) {
        broadcast.translationChannels.map { runtime ->
            val option = models.options.firstOrNull { it.languageTag == runtime.languageTag }
                ?: TranslationLanguageOption(runtime.languageTag, runtime.displayName)
            option to runtime
        }
    } else {
        selectedOptions.map { it to null }
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("언어별 상태", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "통역 ${channels.size}개 · 방송 시 원음 별도 제공",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    buildString {
                        append("청취자 ${broadcast.listenerCount}명")
                        append(" · 웹 전송 ${broadcast.webSocketDeliveredFrameCount}회")
                        if (broadcast.listenerDroppedFrames > 0L) {
                            append(" · 누락 ${broadcast.listenerDroppedFrames}")
                        }
                    },
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Text(
                "각 언어는 번역·음성·청취 대기열이 분리됩니다. 한 채널의 오류는 다른 채널의 방송을 멈추지 않습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (channels.isEmpty()) {
                Text("설정에서 통역 언어를 선택하세요.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                channels.forEachIndexed { index, (option, channel) ->
                    val runtimeChannelId = channel?.channelId.orEmpty()
                    val ttsReady = models.ttsReady(option.languageTag)
                    val modelReady = translationModelReadyForChannel(models, option.languageTag)
                    val translationStatus = translationWorkerStatus(channel, modelReady)
                    val synthesisStatus = synthesisWorkerStatus(channel, ttsReady)
                    val routeStatus = broadcastChannelStatus(broadcast.phase, channel)
                    val latest = broadcast.transcripts
                        .asReversed()
                        .firstOrNull { line ->
                            line.isFinal && (
                                option.languageTag in line.translations ||
                                    option.languageTag in line.firstAudioLatencyMillis ||
                                    option.languageTag in line.synthesisLatencyMillis
                                )
                        }
                    val translationLatency = latest?.translationLatencyMillis?.get(option.languageTag)
                        ?: channel?.lastTranslationElapsedMillis
                    val firstAudioLatency = latest?.firstAudioLatencyMillis?.get(option.languageTag)
                    val synthesisLatency = latest?.synthesisLatencyMillis?.get(option.languageTag)
                        ?: channel?.lastSynthesisElapsedMillis
                    val errors = listOfNotNull(
                        channel?.lastTranslationError?.let { "번역 · $it" },
                        channel?.lastSynthesisError?.let { "음성 · $it" },
                    ).distinct()
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                liveRegion = LiveRegionMode.Polite
                            },
                        color = if (errors.isEmpty()) {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        } else {
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.38f)
                        },
                        shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(
                            1.dp,
                            if (errors.isEmpty()) {
                                MaterialTheme.colorScheme.outlineVariant
                            } else {
                                MaterialTheme.colorScheme.error.copy(alpha = 0.45f)
                            },
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(9.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(
                                            MaterialTheme.colorScheme.primaryContainer,
                                            RoundedCornerShape(12.dp),
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(option.languageTag.uppercase(), fontWeight = FontWeight.Bold)
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "${index + 1}. ${option.label}",
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        "청취자 ${channel?.listenerCount ?: 0}명",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    routeStatus.state,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = operatorStatusColor(routeStatus.tone),
                                )
                            }
                            ChannelWorkerStatusStrip(
                                translation = translationStatus,
                                synthesis = synthesisStatus,
                                broadcast = routeStatus,
                            )
                            Text(
                                "번역 ${channel?.translationProvider ?: translationProviderEstimate(models, option.languageTag)}" +
                                    " · 음성 ${channel?.synthesisProvider ?: models.ttsDisplayName(option.languageTag)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                channelAudioDeliverySummary(channel),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "번역 ${translationLatency?.let { "${it}ms" } ?: "--"}" +
                                    " · 확정→첫 음성 ${firstAudioLatency?.let { "${it}ms" } ?: "--"}" +
                                    " · 합성 ${synthesisLatency?.let { "${it}ms" } ?: "--"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (firstAudioLatency != null && firstAudioLatency > 2_000L) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            if ((channel?.droppedUtterances ?: 0L) > 0L ||
                                (channel?.translationFailures ?: 0L) > 0L ||
                                (channel?.synthesisFailures ?: 0L) > 0L ||
                                (channel?.listenerDroppedFrames ?: 0L) > 0L
                            ) {
                                Text(
                                    "누락 ${channel?.droppedUtterances ?: 0} · " +
                                        "번역 오류 ${channel?.translationFailures ?: 0}" +
                                        "/복구 ${channel?.translationRecoveries ?: 0} · " +
                                        "음성 오류 ${channel?.synthesisFailures ?: 0}" +
                                        "/복구 ${channel?.synthesisRecoveries ?: 0} · " +
                                        "느린 청취자 프레임 누락 " +
                                        "${channel?.listenerDroppedFrames ?: 0}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (channel?.lastError != null) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                            errors.forEach { error ->
                                Text(
                                    error,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            channel?.listenerUrl?.let { url ->
                                SelectionContainer {
                                    Text(
                                        url,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                ) {
                                    TextButton(
                                        modifier = Modifier.sizeIn(minHeight = 48.dp),
                                        onClick = { copyText(context, url) },
                                    ) { Text("주소 복사") }
                                    TextButton(
                                        modifier = Modifier.sizeIn(minHeight = 48.dp),
                                        onClick = {
                                            expandedQrChannelId =
                                                if (expandedQrChannelId == runtimeChannelId) {
                                                    null
                                                } else {
                                                    runtimeChannelId
                                                }
                                        },
                                    ) {
                                        Text(
                                            if (expandedQrChannelId == runtimeChannelId) {
                                                "QR 닫기"
                                            } else {
                                                "채널 QR"
                                            },
                                        )
                                    }
                                }
                                if (expandedQrChannelId == runtimeChannelId) {
                                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        QrCode(
                                            content = url,
                                            description = "${option.label} 청취 페이지 QR 코드",
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            OutlinedButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .sizeIn(minHeight = 48.dp),
                onClick = onOpenTest,
            ) { Text("시험 탭에서 최종 음원 직접 듣기") }
        }
    }
}

internal data class LocalMonitorChannelOption(
    val channelId: String,
    val label: String,
)

internal fun localMonitorChannels(translations: List<BroadcastChannelSnapshot>): List<LocalMonitorChannelOption> =
    listOf(LocalMonitorChannelOption("source", "원음/앱 출력")) + translations
        .filter { it.channelId != "source" }.distinctBy { it.channelId }.map {
            LocalMonitorChannelOption(it.channelId, it.displayName.substringBefore(" ·"))
        }

@Composable
private fun BroadcastLocalMonitorCard(
    broadcast: BroadcastSnapshot,
    selectedInput: AudioInputDevice?,
    outputDevices: List<AudioOutputDevice>,
    onStart: (String, Int) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onVolume: (Float) -> Unit,
) {
    val channels = localMonitorChannels(broadcast.translationChannels)
    var selectedChannelId by rememberSaveable { mutableStateOf(channels.first().channelId) }
    LaunchedEffect(channels.map(LocalMonitorChannelOption::channelId), broadcast.localMonitor.channelId) {
        selectedChannelId = when {
            broadcast.localMonitor.channelId in channels.map(LocalMonitorChannelOption::channelId) ->
                requireNotNull(broadcast.localMonitor.channelId)
            selectedChannelId in channels.map(LocalMonitorChannelOption::channelId) -> selectedChannelId
            else -> channels.first().channelId
        }
    }
    val selected = channels.firstOrNull { it.channelId == selectedChannelId } ?: channels.first()
    val monitor = broadcast.localMonitor
    val active = monitor.phase != LocalMonitorPhase.IDLE && monitor.phase != LocalMonitorPhase.FAILED
    val eligibleOutputs = if (selectedInput == null) {
        emptyList()
    } else {
        LocalMonitorOutputRoutePlanner.eligible(
            input = selectedInput,
            availableOutputs = outputDevices.map { it.toLocalMonitorRoute() },
        )
    }
    var selectedOutputId by rememberSaveable { mutableStateOf<Int?>(null) }
    LaunchedEffect(
        selectedInput?.platformId,
        eligibleOutputs.map(LocalMonitorRoute::platformId),
        monitor.requestedOutputDeviceId,
    ) {
        val eligibleIds = eligibleOutputs.mapNotNull(LocalMonitorRoute::platformId)
        selectedOutputId = when {
            monitor.requestedOutputDeviceId in eligibleIds -> monitor.requestedOutputDeviceId
            selectedOutputId in eligibleIds -> selectedOutputId
            else -> eligibleIds.firstOrNull()
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "로컬 방송 음원 모니터",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "현재 서버에 이미 송출된 PCM을 한 채널씩 듣습니다. 별도 번역·TTS를 실행하지 않으며 원격 청취자 수에도 포함되지 않습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                channels.forEach { channel ->
                    FilterChip(
                        selected = selectedChannelId == channel.channelId,
                        onClick = {
                            selectedChannelId = channel.channelId
                            if (monitor.phase == LocalMonitorPhase.PLAYING) {
                                selectedOutputId?.let { onStart(channel.channelId, it) }
                            } else if (active) {
                                // Choosing a target while paused must not silently resume audio.
                                onStop()
                            }
                        },
                        label = { Text(channel.label) },
                    )
                }
            }
            Text(
                "출력 장치",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text("모니터 음량 · 웹 송출 음량과 별도", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0f, 0.25f, 0.5f, 1f).forEach { gain ->
                    FilterChip(
                        selected = monitor.volume == gain,
                        onClick = { onVolume(gain) },
                        label = { Text(if (gain == 0f) "음소거" else "${(gain * 100).toInt()}%") },
                    )
                }
            }
            if (selectedInput?.kind == AudioInputKind.BUILT_IN &&
                eligibleOutputs.any { it.platformId == selectedOutputId && it.kind == AudioOutputKind.BLUETOOTH }
            ) {
                Text(
                    "스피커를 멀리 두어도 원음이 마이크에 재입력될 수 있습니다. 낮은 음량으로 시작하고 반복음이 들리면 음소거 후 이어폰으로 확인하세요.",
                    style = MaterialTheme.typography.labelSmall,
                    color = GuideCastWarning,
                )
            }
            if (eligibleOutputs.isEmpty()) {
                Text(
                    when (selectedInput?.kind) {
                        AudioInputKind.BUILT_IN -> "내장 마이크에서는 유선/USB 이어폰 또는 Bluetooth 출력을 먼저 연결하세요."
                        null -> "음성 입력 장치를 먼저 선택하세요."
                        else -> "현재 입력과 함께 안전하게 사용할 출력 장치를 찾지 못했습니다."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    eligibleOutputs.forEach { output ->
                        FilterChip(
                            selected = selectedOutputId == output.platformId,
                            enabled = !active,
                            onClick = { selectedOutputId = output.platformId },
                            label = { Text(output.label) },
                        )
                    }
                }
                Text(
                    if (active) {
                        "출력을 바꾸려면 모니터를 중지한 뒤 다시 선택하세요. 방송과 마이크 입력은 유지됩니다."
                    } else {
                        "선택한 장치로만 출력을 요청하고 실제 라우팅이 다르면 로컬 모니터만 멈춥니다."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = buildString {
                            append("로컬 모니터 ").append(localMonitorPhaseLabel(monitor.phase))
                            monitor.channelLabel?.let { append(", 채널 ").append(it) }
                            monitor.outputRouteLabel?.let { append(", 출력 ").append(it) }
                        }
                    },
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "${localMonitorPhaseLabel(monitor.phase)} · " +
                            (monitor.channelLabel ?: selected.label),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "출력 ${monitor.outputRouteLabel ?: "Android 시스템 미디어 출력"} · " +
                            "PCM ${monitor.renderedFrames}프레임/비무음 ${monitor.renderedNonSilentFrames}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    monitor.warning?.let { warning ->
                        Text(
                            warning,
                            style = MaterialTheme.typography.labelSmall,
                            color = GuideCastWarning,
                        )
                    }
                    monitor.errorMessage?.let { error ->
                        Text(
                            error,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            Text(
                "내장 마이크는 유선/USB 이어폰 또는 Bluetooth만 표시합니다. Bluetooth 마이크는 내장 스피커·유선/USB 이어폰·Bluetooth를 선택할 수 있으며 실제 경로를 계속 확인합니다.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (monitor.phase) {
                    LocalMonitorPhase.PLAYING -> OutlinedButton(
                        modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp),
                        onClick = onPause,
                    ) { Text("일시정지") }
                    LocalMonitorPhase.PAUSED -> Button(
                        modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp),
                        onClick = onResume,
                    ) { Text("계속 듣기") }
                    LocalMonitorPhase.STARTING -> Button(
                        modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp),
                        enabled = false,
                        onClick = {},
                    ) { Text("출력 확인 중") }
                    else -> Button(
                        modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp),
                        enabled = selectedOutputId != null,
                        onClick = {
                            selectedOutputId?.let { outputId -> onStart(selected.channelId, outputId) }
                        },
                    ) { Text("선택 채널 듣기") }
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp),
                    enabled = active,
                    onClick = onStop,
                    border = BorderStroke(
                        1.dp,
                        if (active) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.outlineVariant,
                    ),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("중지") }
            }
            if (channels.size > 1) {
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
                    onClick = {
                        val currentIndex = channels.indexOfFirst {
                            it.channelId == (monitor.channelId ?: selected.channelId)
                        }.coerceAtLeast(0)
                        val next = channels[(currentIndex + 1) % channels.size]
                        selectedChannelId = next.channelId
                        selectedOutputId?.let { outputId -> onStart(next.channelId, outputId) }
                    },
                ) { Text("다음 언어 채널 듣기") }
            }
        }
    }
}

private fun localMonitorPhaseLabel(phase: LocalMonitorPhase): String = when (phase) {
    LocalMonitorPhase.IDLE -> "대기"
    LocalMonitorPhase.STARTING -> "출력 확인 중"
    LocalMonitorPhase.PLAYING -> "재생 중"
    LocalMonitorPhase.PAUSED -> "일시정지"
    LocalMonitorPhase.FAILED -> "확인 필요"
}

private fun translationProviderEstimate(
    models: TranslationModelUiState,
    languageTag: String,
): String = if (models.useGemma &&
    GemmaTranslationProvider.supportsTranslation(models.selectedSourceLanguageTag, languageTag)
) {
    "Gemma 우선 · ML Kit 복구"
} else {
    "ML Kit 독립 경로"
}

@Composable
private fun ChannelWorkerStatusStrip(
    translation: OperatorStageStatus,
    synthesis: OperatorStageStatus,
    broadcast: OperatorStageStatus,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        listOf(translation, synthesis, broadcast).forEachIndexed { index, status ->
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    status.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    status.state,
                    style = MaterialTheme.typography.labelMedium,
                    color = operatorStatusColor(status.tone),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                )
            }
            if (index < 2) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .size(width = 1.dp, height = 32.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }
    }
}

@Composable
private fun SettingsInformationCard(onOpenLicenses: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("앱 정보", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "버전 ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                GUIDECAST_PRODUCT_ATTRIBUTION,
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                "Moonshine 구성요소 필수 고지 · $MOONSHINE_REQUIRED_ATTRIBUTION",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .sizeIn(minHeight = 48.dp)
                    .semantics { contentDescription = "설정 메뉴 라이선스 열기" },
                onClick = onOpenLicenses,
            ) { Text("라이선스") }
        }
    }
}

@Composable
private fun LicenseScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<LicenseCategory?>(null) }
    var expandedIds by remember { mutableStateOf(emptySet<String>()) }
    var selectedDocumentId by rememberSaveable { mutableStateOf<String?>(null) }
    val licenseListState = rememberLazyListState()
    val selectedDocument = GUIDECAST_LICENSE_CATALOG.firstOrNull {
        it.id == selectedDocumentId && it.offlineDocumentAsset != null
    }
    BackHandler(enabled = selectedDocument != null) { selectedDocumentId = null }
    if (selectedDocument != null) {
        OfflineLicenseDocumentScreen(
            modifier = modifier,
            title = selectedDocument.name,
            assetPath = requireNotNull(selectedDocument.offlineDocumentAsset),
            onBack = { selectedDocumentId = null },
        )
        return
    }
    val entries = filteredLicenseCatalog(query, category)
    LazyColumn(
        state = licenseListState,
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .semantics { paneTitle = "라이선스 목록" },
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            TextButton(
                modifier = Modifier.sizeIn(minHeight = 48.dp),
                onClick = onBack,
            ) { Text("← 이전 화면") }
        }
        item {
            SectionIntroduction(
                eyebrow = "오픈소스와 이용조건",
                title = "라이선스",
                description = "패키지·버전·라이선스와 배포 가능한 고지는 오프라인으로 확인하고, 최신 서비스 약관은 공식 링크에서 확인합니다.",
            )
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(GUIDECAST_PRODUCT_ATTRIBUTION, fontWeight = FontWeight.SemiBold)
                    Text(
                        MOONSHINE_REQUIRED_ATTRIBUTION,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "두 표기는 제품 제작 고지와 Moonshine Community License 필수 고지로 각각 적용됩니다.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = query,
                onValueChange = { query = it },
                label = { Text("패키지·버전·라이선스 검색") },
                singleLine = true,
            )
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = category == null, onClick = { category = null }, label = { Text("전체") })
                LicenseCategory.entries.forEach { option ->
                    FilterChip(
                        selected = category == option,
                        onClick = { category = option },
                        label = { Text(option.displayName) },
                    )
                }
            }
        }
        item {
            Text(
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                text = "${entries.size}개 항목 · 버전은 이 APK의 최종 런타임 기준",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(entries, key = { it.id }) { entry ->
            LicenseEntryCard(
                entry = entry,
                expanded = entry.id in expandedIds,
                onToggle = {
                    expandedIds = if (entry.id in expandedIds) {
                        expandedIds - entry.id
                    } else {
                        expandedIds + entry.id
                    }
                },
                onOpenSource = { openExternalOrCopy(context, entry.sourceUrl) },
                onOpenOfflineDocument = { selectedDocumentId = entry.id },
            )
        }
        if (entries.isEmpty()) {
            item { Text("검색 결과가 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("개발자 정보", fontWeight = FontWeight.SemiBold)
                    SelectionContainer { Text(GUIDECAST_DEVELOPER_CONTACT) }
                    Text(
                        "제3자 저작권과 상표는 각 권리자에게 있습니다.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun LicenseEntryCard(
    entry: ThirdPartyLicenseEntry,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenSource: () -> Unit,
    onOpenOfflineDocument: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(entry.category.displayName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(entry.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            SelectionContainer {
                Text(entry.packageName, style = MaterialTheme.typography.bodySmall)
            }
            Text("버전 · ${entry.version}", style = MaterialTheme.typography.bodySmall)
            Text("라이선스 · ${entry.licenseName}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            TextButton(
                modifier = Modifier
                    .sizeIn(minHeight = 48.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = if (expanded) {
                            "${entry.name} 상세 접기"
                        } else {
                            "${entry.name} 상세 보기"
                        }
                    },
                onClick = onToggle,
            ) { Text(if (expanded) "상세 접기" else "상세 보기") }
            if (expanded) {
                Text(entry.notice, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (entry.offlineDocumentAsset != null) {
                    OutlinedButton(
                        modifier = Modifier
                            .fillMaxWidth()
                            .sizeIn(minHeight = 48.dp)
                            .semantics(mergeDescendants = true) {
                                contentDescription = "${entry.name} 오프라인 전문·고지 보기"
                            },
                        onClick = onOpenOfflineDocument,
                    ) { Text("오프라인 전문·고지 보기") }
                }
                OutlinedButton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .sizeIn(minHeight = 48.dp)
                        .semantics(mergeDescendants = true) {
                            contentDescription = "${entry.name} 공식 라이선스·출처 열기"
                        },
                    onClick = onOpenSource,
                ) { Text("공식 라이선스·출처 열기") }
            }
        }
    }
}

@Composable
private fun OfflineLicenseDocumentScreen(
    modifier: Modifier,
    title: String,
    assetPath: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val documentChunks by produceState<List<String>?>(initialValue = null, assetPath) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.readLicenseDocumentChunks(assetPath) }
                .getOrElse { error ->
                    listOf(
                        "오프라인 고지를 열지 못했습니다.\n" +
                            (error.message ?: error.javaClass.simpleName),
                    )
                }
        }
    }
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .semantics { paneTitle = "$title 라이선스 전문" },
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            TextButton(
                modifier = Modifier.sizeIn(minHeight = 48.dp),
                onClick = onBack,
            ) { Text("← 라이선스 목록") }
        }
        item {
            SectionIntroduction(
                eyebrow = "오프라인 고지",
                title = title,
                description = assetPath.substringAfterLast('/'),
            )
        }
        val chunks = documentChunks
        if (chunks == null) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        } else {
            items(chunks.size) { index ->
                SelectionContainer {
                    Text(chunks[index], style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun Context.readLicenseDocumentChunks(assetPath: String): List<String> {
    val chunks = mutableListOf<String>()
    assets.open(assetPath).bufferedReader(Charsets.UTF_8).use { reader ->
        val buffer = CharArray(LICENSE_DOCUMENT_CHUNK_SIZE)
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            if (count > 0) chunks += String(buffer, 0, count)
        }
    }
    return chunks.ifEmpty { listOf("(빈 문서)") }
}

private const val LICENSE_DOCUMENT_CHUNK_SIZE = 4_096

@Composable
private fun BroadcastModeSelector(
    translationEnabled: Boolean,
    selectedInputKind: AudioInputKind?,
    selectedLanguageLabels: List<String>,
    enabled: Boolean,
    onSetTranslationEnabled: (Boolean) -> Unit,
) {
    Text("송출 음원", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    Text(
        if (selectedInputKind == AudioInputKind.DEVICE_PLAYBACK) {
            "삼성 통역 등에서 이미 합성된 음성을 전달하려면 앱 출력 그대로를 선택합니다."
        } else {
            "시험 결과와 관계없이 운영자가 송출 방식을 선택합니다."
        },
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        if (selectedLanguageLabels.isEmpty()) {
            "선택된 통역 채널 없음 · 모델·설정에서 언어를 선택하세요."
        } else {
            "선택된 통역 채널 ${selectedLanguageLabels.size}개 · ${selectedLanguageLabels.joinToString()}"
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (selectedLanguageLabels.isEmpty()) GuideCastWarning
        else MaterialTheme.colorScheme.primary,
    )
    Row(
        modifier = Modifier
            .padding(top = 8.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        InputChoiceCard(
            title = if (selectedInputKind == AudioInputKind.DEVICE_PLAYBACK) {
                "앱 출력 그대로"
            } else {
                "원음 그대로"
            },
            subtitle = if (selectedInputKind == AudioInputKind.DEVICE_PLAYBACK) {
                "재번역 없이 웹 송출"
            } else {
                "번역하지 않음"
            },
            selected = !translationEnabled,
            enabled = enabled,
            onClick = { onSetTranslationEnabled(false) },
            modifier = Modifier.weight(1f),
        )
        InputChoiceCard(
            title = "실시간 통역",
            subtitle = "선택 언어 TTS",
            selected = translationEnabled,
            enabled = enabled,
            onClick = { onSetTranslationEnabled(true) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TranslationTestPanel(
    broadcast: BroadcastSnapshot,
    models: TranslationModelUiState,
    onStart: (String) -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
) {
    var requestedLanguage by rememberSaveable { mutableStateOf("en") }
    val selectedOptions = selectedTranslationLanguageOptions(models)
    val testLanguage = requestedLanguage.takeIf { tag -> selectedOptions.any { it.languageTag == tag } }
        ?: selectedOptions.firstOrNull()?.languageTag
    val testLabel = models.options.firstOrNull { it.languageTag == testLanguage }?.label
        ?: "통역 언어 미선택"
    val statusColor = when {
        broadcast.translationTestActive -> GuideCastSuccess
        broadcast.translationTestPassed -> GuideCastSuccess
        broadcast.translationTestMessage != null -> GuideCastWarning
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusLabel = when {
        broadcast.translationTestActive -> "시험 중"
        broadcast.translationTestPassed -> "음성 생성 확인"
        broadcast.translationTestMessage != null -> "확인 필요"
        else -> "미시험"
    }

    Text("통번역 시험", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    Text(
        "${models.selectedSourceLanguage.label.substringBefore(" ·")}로 말하면 원문·번역문을 " +
            "표시하고 통역 음성을 이 폰의 스피커로 재생합니다.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("상태 · $statusLabel", color = statusColor, fontWeight = FontWeight.Bold)
            Text(
                broadcast.translationTestMessage
                    ?: "입력과 모델을 준비한 뒤 시험하세요. 시험하지 않아도 방송 시작은 가능합니다.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("시험 언어", fontWeight = FontWeight.SemiBold)
            if (selectedOptions.isEmpty()) {
                Text(
                    "모델·설정에서 통역 언어를 하나 이상 선택하세요.",
                    color = GuideCastWarning,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Column(
                    modifier = Modifier.selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    selectedOptions.forEach { option ->
                        InputChoiceCard(
                            title = option.label,
                            subtitle = "통역 음성 ${models.ttsDisplayName(option.languageTag)}",
                            selected = option.languageTag == testLanguage,
                            enabled = !broadcast.translationTestActive,
                            onClick = { requestedLanguage = option.languageTag },
                        )
                    }
                }
            }
            if (broadcast.translationTestActive) {
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onStop,
                    colors = destructiveOutlinedButtonColors(),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                ) {
                    Text("통번역 시험 중지")
                }
            } else {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = testLanguage != null && broadcast.inputPhase == InputPhase.ACTIVE,
                    onClick = { testLanguage?.let(onStart) },
                ) {
                    Text(if (broadcast.inputPhase == InputPhase.ACTIVE) "$testLabel 시험 시작" else "입력을 먼저 시작하세요")
                }
            }
            Text(
                "결과는 안내 정보입니다. 음원 품질을 듣고 방송 여부와 시작 시점은 운영자가 직접 결정합니다.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("통역 스크립트", fontWeight = FontWeight.Bold)
        OutlinedButton(enabled = broadcast.transcripts.isNotEmpty(), onClick = onClear) {
            Text("지우기")
        }
    }
    if (broadcast.transcripts.isEmpty()) {
        Text(
            interpretationProgressStatus(broadcast).state,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        broadcast.transcripts.asReversed().forEach { line ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        models.selectedSourceLanguage.label.substringBefore(" ·") +
                            if (line.isFinal) " · 문장 확정" else " · 문장 이어 듣는 중…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    SelectionContainer { Text(line.sourceText, fontWeight = FontWeight.SemiBold) }
                    if (line.isFinal) SpeechCorrectionAction(line, models.selectedSourceLanguageTag)
                    val translated = testLanguage?.let(line.translations::get)
                    Text(testLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    SelectionContainer {
                        Text(
                            translated ?: if (line.isFinal) {
                                "번역 처리 중…"
                            } else {
                                "문장이 확정되면 번역합니다."
                            },
                        )
                    }
                    val translateMs = testLanguage?.let(line.translationLatencyMillis::get)
                    val firstAudioMs = testLanguage?.let(line.firstAudioLatencyMillis::get)
                    val speechMs = testLanguage?.let(line.synthesisLatencyMillis::get)
                    if (translateMs != null || firstAudioMs != null || speechMs != null) {
                        Text(
                            listOfNotNull(
                                translateMs?.let { "번역 ${it}ms" },
                                firstAudioMs?.let {
                                    "확정 후 첫 음성 ${it}ms (${if (it <= 2_000L) "2초 목표 이내" else "2초 초과"})"
                                },
                                speechMs?.let { "음성 완료 ${it}ms" },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (firstAudioMs != null && firstAudioMs > 2_000L) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TranscriptArchivePanel(
    archive: TranscriptArchiveSnapshot,
    onDeleteSelected: (Set<TranscriptArchiveKey>) -> Unit,
    onDeleteSession: (Long) -> Unit,
) {
    var languageFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedKeys by remember { mutableStateOf<Set<TranscriptArchiveKey>>(emptySet()) }
    var confirmSelectedDelete by remember { mutableStateOf(false) }
    var confirmSessionDelete by remember { mutableStateOf<Long?>(null) }
    val languages = remember(archive.lines) { archive.lines.asSequence()
        .flatMap { it.line.translations.keys.asSequence() }
        .distinct()
        .sorted()
        .toList() }
    val filtered = remember(archive.lines, languageFilter) { archive.lines.filter { archived ->
        languageFilter == null || languageFilter in archived.line.translations
    } }
    val visible = filtered.take(MAX_VISIBLE_ARCHIVE_LINES)
    val selectedVisible = selectedKeys.intersect(visible.mapTo(mutableSetOf()) { it.key })

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("방송 스크립트 보관함", fontWeight = FontWeight.Bold)
                    Text(
                        "${archive.lines.size}개 문장 · 30일/최대 5,000개 자동 보존",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    if (archive.pendingWriteCount > 0) "저장 중 ${archive.pendingWriteCount}" else "저장 완료",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (archive.pendingWriteCount > 0) GuideCastWarning else GuideCastSuccess,
                )
            }
            archive.warning?.let { warning ->
                Text(warning, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            if (languages.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = languageFilter == null,
                        onClick = { languageFilter = null },
                        label = { Text("전체 언어") },
                    )
                    languages.forEach { language ->
                        FilterChip(
                            selected = languageFilter == language,
                            onClick = { languageFilter = language },
                            label = { Text(language.uppercase()) },
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = visible.isNotEmpty(),
                    onClick = {
                        selectedKeys = if (selectedVisible.size == visible.size) {
                            selectedKeys - visible.mapTo(mutableSetOf()) { it.key }
                        } else {
                            selectedKeys + visible.map { it.key }
                        }
                    },
                ) { Text(if (selectedVisible.size == visible.size && visible.isNotEmpty()) "보이는 항목 해제" else "보이는 항목 선택") }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = selectedKeys.isNotEmpty(),
                    onClick = { confirmSelectedDelete = true },
                    colors = destructiveOutlinedButtonColors(),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                ) { Text("선택 ${selectedKeys.size}개 삭제") }
            }
            if (visible.isEmpty()) {
                Text(
                    "저장된 확정 문장이 없습니다.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                visible.forEach { archived ->
                    val translation = languageFilter?.let(archived.line.translations::get)
                        ?: archived.line.translations.entries.firstOrNull()?.let { (tag, text) ->
                            "${tag.uppercase()} · $text"
                        }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Checkbox(
                                checked = archived.key in selectedKeys,
                                onCheckedChange = { checked ->
                                    selectedKeys = if (checked) {
                                        selectedKeys + archived.key
                                    } else {
                                        selectedKeys - archived.key
                                    }
                                },
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    formatArchiveSessionTime(archived.sessionStartedAtEpochMillis) +
                                        " · ${archived.sourceLanguageTag}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                SelectionContainer { Text(archived.line.sourceText) }
                                SpeechCorrectionAction(archived.line, archived.sourceLanguageTag)
                                translation?.let { SelectionContainer { Text(it) } }
                                TextButton(
                                    onClick = { confirmSessionDelete = archived.key.sessionId },
                                ) { Text("이 방송 전체 삭제") }
                            }
                        }
                    }
                }
                if (filtered.size > visible.size) {
                    Text(
                        "최신 ${visible.size}개만 표시합니다. 언어 필터로 범위를 줄일 수 있습니다.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (confirmSelectedDelete) {
        AlertDialog(
            onDismissRequest = { confirmSelectedDelete = false },
            title = { Text("선택한 스크립트 삭제") },
            text = { Text("선택한 ${selectedKeys.size}개 문장을 기기에서 삭제합니다.") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteSelected(selectedKeys)
                    selectedKeys = emptySet()
                    confirmSelectedDelete = false
                }) { Text("삭제", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmSelectedDelete = false }) { Text("취소") }
            },
        )
    }
    confirmSessionDelete?.let { sessionId ->
        AlertDialog(
            onDismissRequest = { confirmSessionDelete = null },
            title = { Text("방송 세션 전체 삭제") },
            text = { Text("이 방송에서 저장한 모든 언어 스크립트를 삭제합니다.") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteSession(sessionId)
                    selectedKeys = selectedKeys.filterNotTo(mutableSetOf()) { it.sessionId == sessionId }
                    confirmSessionDelete = null
                }) { Text("세션 삭제", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmSessionDelete = null }) { Text("취소") }
            },
        )
    }
}

private fun formatArchiveSessionTime(epochMillis: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(epochMillis))

private const val MAX_VISIBLE_ARCHIVE_LINES = 100

@Composable
private fun PlaybackTargetSelector(
    apps: List<PlaybackTargetApp>,
    selected: PlaybackTargetApp?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    registrationMessage: String?,
    onRegister: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var manualPackageName by rememberSaveable {
        mutableStateOf("com.samsung.android.app.interpreter")
    }
    Text("출력 대상 앱", fontWeight = FontWeight.SemiBold)
    OutlinedButton(
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled && apps.isNotEmpty(),
        onClick = { expanded = true },
    ) {
        Text(selected?.label ?: "앱 선택")
    }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
    ) {
        apps.forEach { app ->
            DropdownMenuItem(
                text = {
                    Column {
                        Text(app.label)
                        Text(
                            app.packageName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                onClick = {
                    onSelect(app.packageName)
                    expanded = false
                },
            )
        }
    }
    if (apps.isEmpty()) {
        Text(
            "Android에서 실행 가능한 앱 목록을 제공하지 않았습니다.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = manualPackageName,
        enabled = enabled,
        singleLine = true,
        label = { Text("패키지명 수동 등록") },
        supportingText = { Text("예: com.samsung.android.app.interpreter") },
        onValueChange = { manualPackageName = it.trim() },
    )
    OutlinedButton(
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled && manualPackageName.isNotBlank(),
        onClick = { onRegister(manualPackageName) },
    ) {
        Text("패키지 등록 후 선택")
    }
    registrationMessage?.let { message ->
        Text(
            message,
            style = MaterialTheme.typography.labelSmall,
            color = if (message.startsWith("등록됨:")) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
        )
    }
}

@Composable
private fun InputControls(
    broadcast: BroadcastSnapshot,
    selectedDevice: AudioInputDevice?,
    playbackDiagnostics: PlaybackCaptureDiagnostics,
    playbackTargetApps: List<PlaybackTargetApp>,
    selectedPlaybackTarget: PlaybackTargetApp?,
    playbackTargetRegistrationMessage: String?,
    canStart: Boolean,
    onSelectPlaybackTarget: (String) -> Unit,
    onRegisterPlaybackTarget: (String) -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (broadcast.inputPhase == InputPhase.ACTIVE) {
                GuideCastSuccessContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        border = BorderStroke(
            1.dp,
            if (broadcast.inputPhase == InputPhase.ACTIVE) {
                GuideCastSuccess.copy(alpha = 0.42f)
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("입력 제어", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                selectedDevice?.let { "${it.label} · ${it.kind.displayName()}" }
                    ?: "입력 장치를 먼저 선택하세요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (selectedDevice?.kind == AudioInputKind.DEVICE_PLAYBACK) {
                PlaybackTargetSelector(
                    apps = playbackTargetApps,
                    selected = selectedPlaybackTarget,
                    enabled = broadcast.inputPhase == InputPhase.IDLE ||
                        broadcast.inputPhase == InputPhase.FAILED,
                    onSelect = onSelectPlaybackTarget,
                    registrationMessage = playbackTargetRegistrationMessage,
                    onRegister = onRegisterPlaybackTarget,
                )
                Text(
                    if (playbackDiagnostics.mediaOutputRouteLabels.isEmpty()) {
                        "감지된 미디어 출력 경로: 확인 불가"
                    } else {
                        "감지된 미디어 출력 경로: " +
                            playbackDiagnostics.mediaOutputRouteLabels.joinToString()
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "선택한 앱 UID의 허용된 재생음만 가상 라인 입력으로 복사합니다. 다음 Android " +
                        "권한 창은 이 오디오 캡처 토큰을 위한 것이며 이 앱은 화면 영상을 저장하거나 " +
                        "웹으로 보내지 않습니다. Android 창에서도 '앱 하나'와 같은 대상 앱을 선택하세요.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "다른 앱이 제3자 캡처를 허용한 미디어/게임 소리만 받을 수 있습니다. 통화·보호된 " +
                        "통역 출력은 Android 정책상 무음이며 가상 Bluetooth 장치로 우회할 수 없습니다.",
                    style = MaterialTheme.typography.labelSmall,
                    color = GuideCastWarning,
                )
                Text(
                    "운영 중에는 화면을 잠그거나 송출 폰에서 청취 페이지를 재생하지 마세요. " +
                        "화면 잠금은 캡처 동의를 종료할 수 있고, 같은 폰의 청취음은 되먹임을 만들 수 있습니다.",
                    style = MaterialTheme.typography.labelSmall,
                    color = GuideCastWarning,
                )
            }
            broadcast.inputProcessingSummary?.let { processingSummary ->
                Text(
                    processingSummary,
                    style = MaterialTheme.typography.labelSmall,
                    color = if ("미지원" in processingSummary) {
                        GuideCastWarning
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }

            when (broadcast.inputPhase) {
                InputPhase.IDLE,
                InputPhase.FAILED,
                -> {
                    if (broadcast.inputErrorMessage != null) {
                        Text(
                            broadcast.inputErrorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Button(
                        modifier = Modifier
                            .fillMaxWidth()
                            .sizeIn(minHeight = 56.dp),
                        enabled = canStart,
                        onClick = onStart,
                    ) {
                        Text(
                            if (selectedDevice?.kind == AudioInputKind.DEVICE_PLAYBACK) {
                                "선택 앱 재생음 권한 허용"
                            } else {
                                "입력 시작"
                            },
                        )
                    }
                }

                InputPhase.STARTING -> {
                    Text("입력 스트림을 여는 중입니다…", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onStop,
                        colors = destructiveOutlinedButtonColors(),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                    ) {
                        Text("입력 시작 취소")
                    }
                }

                InputPhase.ACTIVE -> {
                    InputLevel(
                        broadcast = broadcast,
                        selectedInputKind = selectedDevice?.kind,
                        playbackDiagnostics = playbackDiagnostics,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(modifier = Modifier.weight(1f), onClick = onPause) {
                            Text("입력 일시정지")
                        }
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = onStop,
                            colors = destructiveOutlinedButtonColors(),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                        ) {
                            Text("입력 중지")
                        }
                    }
                }

                InputPhase.PAUSED -> {
                    Text("입력이 일시정지됐습니다. 방송 서버 상태와 별개입니다.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(modifier = Modifier.weight(1f), onClick = onResume) {
                            Text("입력 재개")
                        }
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = onStop,
                            colors = destructiveOutlinedButtonColors(),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                        ) {
                            Text("입력 중지")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InputLevel(
    broadcast: BroadcastSnapshot,
    selectedInputKind: AudioInputKind?,
    playbackDiagnostics: PlaybackCaptureDiagnostics,
) {
    val inputLevel = (broadcast.inputRms * 8f).coerceIn(0f, 1f)
    val dbFs = if (broadcast.inputRms > 0f) {
        (20.0 * log10(broadcast.inputRms.toDouble())).toInt().coerceAtLeast(-90)
    } else {
        -90
    }
    LinearProgressIndicator(progress = { inputLevel }, modifier = Modifier.fillMaxWidth())
    val listening = inputListeningStatus(broadcast)
    Text(
        text = "${listening.state} · $dbFs dBFS · ${broadcast.inputFrameCount} 프레임",
        style = MaterialTheme.typography.bodySmall,
        color = operatorStatusColor(listening.tone),
    )
    if (broadcast.translationTestActive || broadcast.translationChannels.isNotEmpty()) {
        val progress = interpretationProgressStatus(broadcast)
        Text(progress.state, style = MaterialTheme.typography.bodySmall,
            color = operatorStatusColor(progress.tone))
    }
    if (broadcast.inputFrameCount > 0L && !broadcast.inputSignalActive && broadcast.inputPeak < 0.01f) {
        Text("말하는 중에도 입력 막대가 움직이지 않으면 마이크 연결·권한·다른 녹음 앱의 점유를 확인하세요.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (selectedInputKind == AudioInputKind.DEVICE_PLAYBACK) {
        val status = playbackCaptureUiStatus(
            diagnostics = playbackDiagnostics,
            signalActive = broadcast.inputSignalActive,
        )
        Text(
            text = status.message,
            fontWeight = FontWeight.SemiBold,
            color = when (status.tone) {
                PlaybackCaptureUiTone.SUCCESS -> GuideCastSuccess
                PlaybackCaptureUiTone.WARNING -> MaterialTheme.colorScheme.error
                PlaybackCaptureUiTone.NEUTRAL -> MaterialTheme.colorScheme.primary
            },
        )
        Text(
            text = status.detail,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TranslationModelCard(
    state: TranslationModelUiState,
    enabled: Boolean,
    onSelectSource: (String) -> Unit,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onPrepare: () -> Unit,
    onSelectSpeechVoice: (String, SpeechVoicePreference) -> Unit,
    onCancelPreparation: () -> Unit,
    onRemove: (String) -> Unit,
) {
    var languageQuery by rememberSaveable { mutableStateOf("") }
    var showAdditionalLanguages by rememberSaveable { mutableStateOf(false) }
    var detailLanguageTag by rememberSaveable { mutableStateOf<String?>(null) }
    detailLanguageTag?.let { tag ->
        val translationError = state.statuses.firstOrNull { it.languageTag == tag }?.errorMessage
        val speechStatus = state.ttsStatuses.firstOrNull { it.languageTag == tag }
        AlertDialog(
            onDismissRequest = { detailLanguageTag = null },
            title = { Text(state.options.firstOrNull { it.languageTag == tag }?.label ?: tag) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("번역 ${state.translationDisplayName(tag)}")
                    Text("통역 음성 ${state.ttsDisplayName(tag)}")
                    translationError?.let { Text("번역 오류 · ${it.take(1200)}", color = MaterialTheme.colorScheme.error) }
                    moonshineTtsFailureDetail(speechStatus)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Text("다른 언어와 원음 방송은 별도로 동작합니다. 준비 상태를 확인한 뒤 다시 준비할 수 있습니다.")
                }
            },
            confirmButton = { TextButton(onClick = { detailLanguageTag = null }) { Text("닫기") } },
        )
    }
    val selectedInOrder = state.selectedLanguageTags.toList()
    val isSearching = languageQuery.isNotBlank()
    val visibleOptions = remember(state.options, state.selectedLanguageTags, languageQuery, showAdditionalLanguages) { state.options.filter { option ->
        val matchesQuery = !isSearching ||
            option.languageTag.contains(languageQuery.trim(), ignoreCase = true) ||
            option.label.contains(languageQuery.trim(), ignoreCase = true)
        val isDefaultOrSelected = option.languageTag in PRIMARY_TRANSLATION_LANGUAGE_TAGS ||
            option.languageTag in state.selectedLanguageTags
        matchesQuery && (isSearching || showAdditionalLanguages || isDefaultOrSelected)
    } }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "원문 언어",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "말하는 언어를 먼저 선택하세요. 한국어는 Moonshine을 우선 사용하고, " +
                    "그 밖의 언어는 준비된 Galaxy 온디바이스 음성인식을 사용합니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.sourceOptions.forEach { option ->
                    FilterChip(
                        selected = option.languageTag == state.selectedSourceLanguageTag,
                        onClick = { onSelectSource(option.languageTag) },
                        enabled = enabled && !state.isBusy,
                        label = { Text(option.label.substringBefore(" ·")) },
                    )
                }
            }
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "통역 언어",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Surface(
                    color = if (selectedInOrder.size == MAX_SIMULTANEOUS_TRANSLATION_LANGUAGES) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    },
                    shape = CircleShape,
                ) {
                    Text(
                        "${selectedInOrder.size}/$MAX_SIMULTANEOUS_TRANSLATION_LANGUAGES 선택",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                "${state.selectedSourceLanguage.label.substringBefore(" ·")} 입력 한 개를 " +
                    "언어별 독립 채널로 나눕니다. " +
                    "관광객이 들을 언어를 최대 ${MAX_SIMULTANEOUS_TRANSLATION_LANGUAGES}개까지 " +
                    "선택하세요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.resourceDiagnostics?.let { diagnostics ->
                WorkspaceDisclosure(
                    title = "동시 통역 자원 진단",
                    summary = when (val count = diagnostics.recommendedChannelCount) {
                        null -> "권장 수 산정 불가 · 메모리 정보 확인 필요"
                        0 -> "메모리 여유 주의 · 현재 안전 기준을 만족하는 권장 수 없음. 선택은 제한하지 않습니다."
                        else -> "권장 최대 ${count}개 · 현재 자원 기준 추정 · 원음 별도"
                    },
                ) { MultiLanguageResourceDiagnosticsCard(diagnostics) }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    modifier = Modifier
                        .weight(1f)
                        .sizeIn(minHeight = 48.dp),
                    enabled = enabled && !state.isBusy &&
                        selectedInOrder != recommendedTranslationLanguageSelection(
                            options = state.options,
                            sourceLanguageTag = state.selectedSourceLanguageTag,
                        ).toList(),
                    onClick = onSelectAll,
                ) { Text("기본 5개 선택") }
                OutlinedButton(
                    modifier = Modifier
                        .weight(1f)
                        .sizeIn(minHeight = 48.dp),
                    enabled = enabled && !state.isBusy && state.selectedLanguageTags.isNotEmpty(),
                    onClick = onClearSelection,
                ) { Text("선택 해제") }
            }
            if (state.options.size > MAX_SIMULTANEOUS_TRANSLATION_LANGUAGES) {
                OutlinedTextField(
                    value = languageQuery,
                    onValueChange = { languageQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("언어 검색") },
                    supportingText = {
                        Text("언어명 또는 en, ja 같은 언어 코드로 찾으세요.")
                    },
                )
            }
            TranslationPreparationActions(state, enabled, onPrepare, onCancelPreparation)
            visibleOptions.forEach { option ->
                val selected = option.languageTag in state.selectedLanguageTags
                val selectedOrder = selectedInOrder.indexOf(option.languageTag).takeIf { it >= 0 }
                val readiness = state.readiness(option.languageTag)
                val translationModel = state.statuses.firstOrNull {
                    it.languageTag == option.languageTag
                }
                val tts = state.ttsStatuses.firstOrNull { it.languageTag == option.languageTag }
                val canToggle = enabled && !state.isBusy &&
                    (selected || selectedInOrder.size < MAX_SIMULTANEOUS_TRANSLATION_LANGUAGES)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = selected,
                            enabled = canToggle,
                            role = Role.Checkbox,
                            onValueChange = { onToggle(option.languageTag) },
                        )
                        .semantics(mergeDescendants = true) { }
                        .sizeIn(minHeight = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = selected,
                        enabled = canToggle,
                        onCheckedChange = null,
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 6.dp),
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(option.label, fontWeight = FontWeight.SemiBold)
                            if (selectedOrder != null) {
                                Text(
                                    if (state.useGemma &&
                                        GemmaTranslationProvider.supportsTargetLanguage(
                                            option.languageTag,
                                        )
                                    ) {
                                        "${selectedOrder + 1}번 채널 · 공유 Gemma"
                                    } else {
                                        "${selectedOrder + 1}번 채널"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        Text(
                            text = "번역 ${state.translationDisplayName(option.languageTag)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (!translationModelReadyForChannel(state, option.languageTag) ||
                                !state.ttsReady(option.languageTag)
                            ) MaterialTheme.colorScheme.error else GuideCastSuccess,
                        )
                        Text(
                            text = when {
                                state.ttsUnavailableReason(option.languageTag) != null -> "통역 음성 · 확인 필요"
                                state.ttsFallbackReady(option.languageTag) -> "통역 음성 · Galaxy 대체 음성 준비"
                                else -> "통역 음성 · ${state.ttsReadiness(option.languageTag).displayName()}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.ttsReady(option.languageTag)) GuideCastSuccess else MaterialTheme.colorScheme.error,
                        )
                        if (tts?.readiness == MoonshineTtsReadiness.DOWNLOADING) {
                            LinearProgressIndicator(
                                progress = { tts.progress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                tts.currentFile ?: "Moonshine 음성 파일 다운로드 중",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (option.languageTag in state.selectedLanguageTags) {
                            SpeechVoicePreferencePicker(
                                languageTag = option.languageTag,
                                selected = state.voicePreferences[option.languageTag] ?: SpeechVoicePreference.AUTO,
                                enabled = enabled && !state.isBusy,
                                onSelect = { onSelectSpeechVoice(option.languageTag, it) },
                            )
                        }
                        if (translationModel?.errorMessage != null || moonshineTtsFailureDetail(tts) != null ||
                            state.ttsUnavailableReason(option.languageTag) != null
                        ) {
                            TextButton(
                                onClick = { detailLanguageTag = option.languageTag },
                                modifier = Modifier.sizeIn(minHeight = 48.dp).semantics {
                                    contentDescription = "${option.label} 문제 상세"
                                },
                            ) { Text("문제 상세 · 복구 안내", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                    if (readiness == ModelReadiness.READY) {
                        OutlinedButton(
                            enabled = enabled && !state.isBusy,
                            onClick = { onRemove(option.languageTag) },
                        ) {
                            Text("삭제")
                        }
                    }
                }
            }

            val hasHiddenOptions = !isSearching && !showAdditionalLanguages && state.options.any { option ->
                option.languageTag !in PRIMARY_TRANSLATION_LANGUAGE_TAGS &&
                    option.languageTag !in state.selectedLanguageTags
            }
            if (hasHiddenOptions) {
                OutlinedButton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .sizeIn(minHeight = 44.dp),
                    enabled = enabled && !state.isBusy,
                    onClick = { showAdditionalLanguages = true },
                ) {
                    Text("+ 언어 추가")
                }
            } else if (showAdditionalLanguages && !isSearching) {
                TextButton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .sizeIn(minHeight = 44.dp),
                    enabled = enabled && !state.isBusy,
                    onClick = { showAdditionalLanguages = false },
                ) {
                    Text("추가 언어 접기")
                }
            }

            if (selectedInOrder.size == MAX_SIMULTANEOUS_TRANSLATION_LANGUAGES) {
                Text(
                    "선택 한도에 도달했습니다. 다른 언어를 추가하려면 선택된 언어 하나를 먼저 해제하세요.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = if (state.useGemma) {
                    "지원 언어는 Gemma 하나를 공정하게 공유합니다. 완료된 언어부터 송출하며, " +
                        "미지원 언어와 장애 복구는 준비된 ML Kit를 사용합니다."
                } else {
                    "현재 경량 ML Kit 번역을 사용합니다. 최초 준비 후 오프라인으로 동작합니다."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TranslationPreparationActions(
    state: TranslationModelUiState,
    enabled: Boolean,
    onPrepare: () -> Unit,
    onCancelPreparation: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.operationProgress?.takeIf { state.isBusy }?.let { progress ->
            Text(progress, style = MaterialTheme.typography.bodySmall)
        }
        state.message?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled && !state.isBusy && state.selectedLanguageTags.isNotEmpty(),
            onClick = onPrepare,
        ) {
            if (state.isBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.size(8.dp))
                Text(state.operationLabel)
            } else {
                Text(
                    if (state.useGemma) {
                        "음성인식 · 선택한 통역 음성 준비"
                    } else {
                        "번역 · 음성인식 · 선택 음성 준비"
                    },
                )
            }
        }
        if (state.isBusy) {
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isCancelling,
                onClick = onCancelPreparation,
            ) {
                Text(if (state.isCancelling) "중지 처리 중" else "준비 중지 · 선택 변경")
            }
        }
    }
}

@Composable
private fun SpeechVoicePreferencePicker(
    languageTag: String,
    selected: SpeechVoicePreference,
    enabled: Boolean,
    onSelect: (SpeechVoicePreference) -> Unit,
) {
    var expanded by remember(languageTag) { mutableStateOf(false) }
    Box {
        OutlinedButton(enabled = enabled, onClick = { expanded = true }) {
            Text("음성: ${selected.label}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SpeechVoicePreference.entries.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice.label) },
                    onClick = { expanded = false; onSelect(choice) },
                )
            }
        }
    }
    Text(
        "자동은 설치된 오프라인 음성을 사용합니다. Moonshine 파일은 해당 옵션 선택 후 준비할 때 내려받습니다. 음성 실패 시 설치된 다른 엔진으로 복구합니다.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun MultiLanguageResourceDiagnosticsCard(
    diagnostics: MultiLanguageResourceDiagnostics,
) {
    val statusLabel = when (diagnostics.state) {
        MultiLanguageResourceState.SAFE -> "안정"
        MultiLanguageResourceState.CAUTION -> "주의"
        MultiLanguageResourceState.PRESSURE -> "메모리 압력"
    }
    val statusColor = when (diagnostics.state) {
        MultiLanguageResourceState.SAFE -> MaterialTheme.colorScheme.primary
        MultiLanguageResourceState.CAUTION -> GuideCastWarning
        MultiLanguageResourceState.PRESSURE -> MaterialTheme.colorScheme.error
    }
    val background = when (diagnostics.state) {
        MultiLanguageResourceState.SAFE -> MaterialTheme.colorScheme.primaryContainer
        MultiLanguageResourceState.CAUTION -> GuideCastWarningContainer
        MultiLanguageResourceState.PRESSURE -> MaterialTheme.colorScheme.errorContainer
    }
    val semanticSummary = buildString {
        append("동시 통역 자원 상태 ").append(statusLabel)
        append(", 선택 채널 ").append(diagnostics.selectedChannelCount).append("개")
        append(", 총 메모리 ").append(formatMemoryGiB(diagnostics.memory.totalMemoryBytes))
        append(", 가용 메모리 ").append(formatMemoryGiB(diagnostics.memory.availableMemoryBytes))
        append(", 추가 준비 예약 추정 ")
        append(formatMemoryMiB(diagnostics.estimatedAdditionalReservationBytes))
        if (diagnostics.appProcessMemory.valuesValid) {
            append(", 현재 GuideCast 합산 PSS ")
            append(formatMemoryMiB(diagnostics.appProcessMemory.totalPssBytes))
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = semanticSummary
            },
        shape = MaterialTheme.shapes.medium,
        color = background,
        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.45f)),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "동시 통역 자원 진단",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    statusLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = statusColor,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                when (val count = diagnostics.recommendedChannelCount) {
                    null -> "권장 동시 통역 수: 산정 불가 · 메모리 정보 확인 필요"
                    0 -> "현재 안전 여유 기준을 만족하는 권장 통역 수가 없습니다."
                    else -> "메모리 기준 권장: 최대 ${count}개 · 속도는 시험 후 확인"
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text("원음 채널은 별도입니다. 언어·모델 준비 상태에 따라 권장 수가 달라지며 선택을 제한하지 않습니다.",
                style = MaterialTheme.typography.labelSmall)
            Text(diagnostics.initialMemoryProfileLabel, style = MaterialTheme.typography.labelSmall)
            Text(
                "총 ${formatMemoryGiB(diagnostics.memory.totalMemoryBytes)} · " +
                    "가용 ${formatMemoryGiB(diagnostics.memory.availableMemoryBytes)} · " +
                    "원음 1개 + 통역 ${diagnostics.selectedChannelCount}개",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "추가 준비 예약 추정 ${formatMemoryMiB(diagnostics.estimatedAdditionalReservationBytes)} · " +
                    "준비 후 예상 여유 ${formatMemoryGiB(diagnostics.projectedAvailableMemoryBytes)}",
                style = MaterialTheme.typography.bodySmall,
            )
            diagnostics.appProcessMemory.takeIf { it.valuesValid }?.let { measured ->
                Text(
                    "GuideCast ${measured.processCount}개 프로세스 · 합산 PSS " +
                        "${formatMemoryMiB(measured.totalPssBytes)} · 전용 변경 메모리 " +
                        formatMemoryMiB(measured.totalPrivateDirtyBytes),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "실측값은 품질·언어 수를 제한하지 않고 누수와 적재 피크를 찾는 진단에만 사용합니다.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (diagnostics.gemmaColdLoadFloorApplies) {
                Text(
                    "Gemma는 파일 크기를 RAM으로 환산하지 않고, 적재 시점 가용 RAM 3 GiB 조건을 별도로 확인합니다.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                diagnostics.summary,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                diagnostics.recommendation,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            diagnostics.languagePlans.forEach { plan ->
                val languageName = TRANSLATION_LANGUAGE_OPTIONS.firstOrNull {
                    it.languageTag == plan.languageTag
                }?.label?.substringBefore(" ·") ?: plan.languageTag
                Text(
                    "$languageName · " +
                        "${plan.translationPlan} · ${plan.synthesisPlan}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatMemoryGiB(bytes: Long, unknownWhenNonPositive: Boolean = false): String = when {
    bytes <= 0L && unknownWhenNonPositive -> "확인 필요"
    bytes <= 0L -> "0.0 GiB"
    else -> "%.1f GiB".format(bytes / 1_073_741_824.0)
}

private fun formatMemoryMiB(bytes: Long): String = when {
    bytes <= 0L -> "0 MiB"
    bytes == Long.MAX_VALUE -> "상한 초과"
    else -> "${bytes / 1_048_576L} MiB"
}

@Composable
private fun GemmaModelCard(
    state: GemmaUiState,
    enabled: Boolean,
    onSelectModel: (GemmaModelVariant) -> Unit,
    onImportCatalog: () -> Unit,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
    onSetUseGemma: (Boolean) -> Unit,
    selectiveRefinementControlEnabled: Boolean,
    onSetSelectiveTranslationRefinement: (Boolean) -> Unit,
    onTest: () -> Unit,
    onImport: () -> Unit,
) {
    val context = LocalContext.current
    var candidateId by rememberSaveable(state.appliedVariant.id) { mutableStateOf(state.appliedVariant.id) }
    val candidate = state.availableModels.firstOrNull { it.id == candidateId } ?: state.appliedVariant
    val ready = state.model.readiness == GemmaModelReadiness.READY
    val operationActive = state.model.readiness == GemmaModelReadiness.DOWNLOADING ||
        state.model.readiness == GemmaModelReadiness.VERIFYING ||
        state.model.readiness == GemmaModelReadiness.ENGINE_TESTING
    val progress = if (state.model.totalBytes > 0) {
        (state.model.downloadedBytes.toDouble() / state.model.totalBytes).toFloat().coerceIn(0f, 1f)
    } else {
        0f
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Gemma 고급 번역", fontWeight = FontWeight.Bold)
            Text("단말: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}",
                style = MaterialTheme.typography.labelSmall)
            Text("현재 적용 모델: ${state.appliedVariant.label}", fontWeight = FontWeight.SemiBold)
            Text("설치 후에도 모델을 변경할 수 있습니다. 목록 선택만으로는 현재 모델이 바뀌지 않습니다.",
                style = MaterialTheme.typography.bodySmall)
            Text(
                "${GemmaModelManager.RUNTIME_VERSION} · ${state.model.variant.label} · " +
                    "${formatMemoryGiB(state.model.totalBytes)} (파일)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "두 모델 모두 모바일 양자화 계열입니다. GPU형은 새 QAT 학습 모델이 아닌 별도 실행 파일이며, " +
                    "속도·메모리·번역 품질 개선은 기기에서 비교해야 합니다. 한 번에 하나만 실행합니다.",
                style = MaterialTheme.typography.bodySmall,
            )
            state.availableModels.forEach { variant ->
                val compatible = variant.compatibilityIssue(Build.VERSION.SDK_INT) == null
                Row(
                    modifier = Modifier.fillMaxWidth().selectable(
                        selected = candidate == variant,
                        enabled = enabled && !state.isBusy && !operationActive && compatible,
                        role = Role.RadioButton,
                        onClick = { candidateId = variant.id },
                    ).padding(vertical = 4.dp).heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = candidate == variant, onClick = null, enabled = compatible)
                    Text(variant.label, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text("선택한 파일: ${candidate.fileName} · ${formatMemoryGiB(candidate.sizeBytes)}",
                style = MaterialTheme.typography.labelSmall)
            Button(
                onClick = { onSelectModel(candidate) },
                enabled = enabled && !state.isBusy && !operationActive &&
                    candidate.compatibilityIssue(Build.VERSION.SDK_INT) == null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("선택 모델 다운로드·점검 후 적용") }
            OutlinedButton(onClick = onImportCatalog,
                enabled = enabled && !state.isBusy && !operationActive,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("서명된 모델 목록 가져오기") }
            Text("새 목록은 앱 개발자 서명을 확인합니다. 기존 파일은 보존하며 실행 점검 실패 시 이전 모델로 복구합니다.",
                style = MaterialTheme.typography.labelSmall)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                Text(
                    "Note9 · Android 10 호환: E2B 기본형 CPU 실행. GPU 최적화형은 지원하지 않습니다. " +
                        "방송 전 시험 탭에서 번역·음성과 지연을 확인하세요.",
                    style = MaterialTheme.typography.labelSmall,
                    color = GuideCastWarning,
                )
            }
            Text(
                state.model.variant.fileName,
                style = MaterialTheme.typography.labelSmall,
            )
            if (state.model.variant.gpuOnly) {
                Text(
                    "Android 12 이상 GPU 시험용 · GPU 실행 실패 시 기본형을 다시 선택하세요. " +
                        "기존 다운로드는 보존됩니다.",
                    style = MaterialTheme.typography.labelSmall,
                    color = GuideCastWarning,
                )
            }
            Text(
                "google-gemma/gemma-translator와 동일한 Gemma 4 E2B 번역 모델과 " +
                    "Moonshine Android 통역 음성을 사용합니다. 공개 파일은 로그인·토큰 없이 " +
                    "내려받지만 사용 전 Gemma 약관과 모델별 상업 이용 조건을 확인해야 합니다.",
                style = MaterialTheme.typography.labelSmall,
                color = GuideCastWarning,
            )
            Text(
                text = when (state.model.readiness) {
                    GemmaModelReadiness.NOT_INSTALLED -> "모델 미설치"
                    GemmaModelReadiness.DOWNLOADING ->
                        "다운로드 중 · ${state.model.downloadedBytes / 1_048_576} / ${state.model.totalBytes / 1_048_576} MiB"
                    GemmaModelReadiness.VERIFYING -> "SHA-256 무결성 검증 중"
                    GemmaModelReadiness.VERIFIED -> "파일 검증 완료 · 실제 추론 점검 필요"
                    GemmaModelReadiness.ENGINE_TESTING -> "실제 번역 추론 점검 중"
                    GemmaModelReadiness.READY -> "번역 추론 점검 통과 · 오프라인 준비됨"
                    GemmaModelReadiness.FAILED -> "준비 실패"
                },
                color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            state.broadcastCapabilityMessage?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.broadcastCapable) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
            if (state.model.readiness == GemmaModelReadiness.DOWNLOADING ||
                state.model.readiness == GemmaModelReadiness.VERIFYING ||
                state.model.readiness == GemmaModelReadiness.ENGINE_TESTING
            ) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
            (state.model.errorMessage ?: state.message)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = enabled && !state.isBusy && !operationActive,
                    onClick = {
                        openExternalOrCopy(context, GemmaModelManager.UPSTREAM_REPOSITORY_URL)
                    },
                ) {
                    Text("공식 프로젝트")
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = enabled && !state.isBusy && !operationActive,
                    onClick = { openExternalOrCopy(context, GemmaModelManager.TERMS_URL) },
                ) {
                    Text("Gemma 약관")
                }
            }
            if (!ready) {
                if (state.model.readiness == GemmaModelReadiness.VERIFIED) {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = enabled && !state.isBusy && !operationActive,
                        onClick = onTest,
                    ) {
                        Text("기존 검증 모델로 추론·TTS 다시 점검")
                    }
                } else {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = enabled && !state.isBusy && !operationActive,
                        onClick = onDownload,
                    ) {
                        Text(
                            if (state.model.downloadedBytes > 0L) {
                                "공식 모델 이어받기 · 검증 · 자체점검"
                            } else {
                                "공식 모델 다운로드 · 검증 · 자체점검"
                            },
                        )
                    }
                }
                Text(
                    "화면을 꺼도 전경 서비스가 다운로드를 계속합니다. 파일 검증과 실제 번역을 자동 검사합니다. " +
                        "음성은 ‘번역·TTS 자체 점검’에서 별도로 확인하세요.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled && !state.isBusy && !operationActive,
                    onClick = onImport,
                ) {
                    Text("이미 받은 .litertlm 파일 가져오기")
                }
                Text(
                    "검증 기준: ${state.model.totalBytes} bytes · SHA-256 ${state.model.variant.sha256.take(12)}…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        enabled = enabled && !state.isBusy,
                        onClick = onTest,
                    ) { Text("번역·TTS 자체 점검") }
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        enabled = enabled && !state.isBusy,
                        onClick = onRemove,
                    ) { Text("모델 삭제") }
                }
                val gemmaToggleEnabled = enabled && !state.isBusy && state.broadcastCapable
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = state.useForTranslation,
                            enabled = gemmaToggleEnabled,
                            role = Role.Checkbox,
                            onValueChange = onSetUseGemma,
                        )
                        .semantics(mergeDescendants = true) { }
                        .sizeIn(minHeight = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = state.useForTranslation,
                        enabled = gemmaToggleEnabled,
                        onCheckedChange = null,
                    )
                    Text("방송 번역에 Gemma 사용", fontWeight = FontWeight.SemiBold)
                }
            }
            val selectiveRefinementEnabled = enabled && selectiveRefinementControlEnabled &&
                !state.isBusy && state.broadcastCapable && state.useForTranslation
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = state.useForTranslation && state.selectiveTranslationRefinement,
                        enabled = selectiveRefinementEnabled,
                        role = Role.Checkbox,
                        onValueChange = onSetSelectiveTranslationRefinement,
                    )
                    .semantics(mergeDescendants = true) { }
                    .sizeIn(minHeight = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = state.useForTranslation && state.selectiveTranslationRefinement,
                    enabled = selectiveRefinementEnabled,
                    onCheckedChange = null,
                )
                Text("선택적 번역 보완 (시험)", fontWeight = FontWeight.SemiBold)
            }
            Text(
                "ML Kit 초안 중 용어·숫자·긴 문장만 Gemma가 검토합니다. " +
                    "보완 실패 시 초안을 한 번만 송출합니다. 지연·품질은 시험 후 판단하세요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReadinessCard(
    state: AudioInputUiState,
    broadcast: BroadcastSnapshot,
    permissions: InputPermissionState,
) {
    val pinnedDeviceMissing = state.preference.mode == AudioInputPreference.Mode.PINNED_DEVICE &&
        state.selectedDevice == null
    val bluetoothPermissionMissing = state.selectedDevice?.kind == AudioInputKind.BLUETOOTH &&
        !permissions.bluetoothConnectGranted
    val ready = permissions.canStartInput(state.selectedDevice?.kind)
    val playbackSelected = state.selectedDevice?.kind == AudioInputKind.DEVICE_PLAYBACK
    val playbackSignalConfirmed = playbackSelected && broadcast.inputSignalActive
    val background = when {
        ready && (!playbackSelected || playbackSignalConfirmed) -> GuideCastSuccessContainer
        pinnedDeviceMissing -> MaterialTheme.colorScheme.errorContainer
        else -> GuideCastWarningContainer
    }
    val accent = when {
        ready && (!playbackSelected || playbackSignalConfirmed) -> GuideCastSuccess
        pinnedDeviceMissing -> MaterialTheme.colorScheme.error
        else -> GuideCastWarning
    }
    val status = when {
        playbackSignalConfirmed -> "앱 출력 수신 확인"
        ready && playbackSelected && broadcast.inputPhase == InputPhase.ACTIVE ->
            "가상 라인 연결됨 · 음성 신호 확인 필요"
        ready && playbackSelected -> "가상 라인 선택됨 · 입력 시작 필요"
        ready -> "입력 준비됨"
        pinnedDeviceMissing -> "선택한 입력 연결 끊김"
        !permissions.recordAudioGranted -> "오디오 입력 권한 필요"
        bluetoothPermissionMissing -> "Bluetooth 마이크 권한 필요"
        else -> "오디오 입력 연결 대기"
    }
    val detail = state.selectedDevice?.let { "${it.label} · ${it.kind.displayName()}" }
        ?: "방송을 시작하기 전에 입력 상태를 확인하세요."

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        color = background,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.24f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(width = 4.dp, height = 38.dp)
                    .background(accent, CircleShape)
                    .clearAndSetSemantics { },
            )
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(status, fontWeight = FontWeight.SemiBold)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun InputChoiceCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .sizeIn(minHeight = 48.dp)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) { }
            .alpha(if (enabled) 1f else 0.38f),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        border = BorderStroke(
            if (selected) 1.5.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = selected,
                enabled = enabled,
                onClick = null,
            )
            Column(Modifier.padding(start = 8.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BroadcastControls(
    broadcast: BroadcastSnapshot,
    accessMode: OperatorAccessMode,
    pin: String,
    micPinEnabled: Boolean,
    micPin: String,
    canStart: Boolean,
    onAccessModeChange: (OperatorAccessMode) -> Unit,
    onPinChange: (String) -> Unit,
    onMicPinEnabledChange: (Boolean) -> Unit,
    onMicPinChange: (String) -> Unit,
    onStart: (OperatorAccessMode, CharArray?, CharArray?) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onPlayTestTone: () -> Unit,
) {
    val active = broadcast.phase == BroadcastPhase.STARTING ||
        broadcast.phase == BroadcastPhase.LIVE ||
        broadcast.phase == BroadcastPhase.PAUSED
    val validPin = pin.length in 4..8 && pin.all(Char::isDigit)
    val validMicPin = micPin.length in 4..8 && micPin.all(Char::isDigit)

    Text(
        text = "로컬 방송",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
    )
    Text(
        text = "핫스팟을 먼저 켠 뒤 접속 방식을 선택하세요.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(10.dp))

    Row(
        modifier = Modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AccessModeButton(
            modifier = Modifier.weight(1f),
            label = "QR 입장코드",
            selected = accessMode == OperatorAccessMode.QR_TOKEN,
            enabled = !active,
        ) { onAccessModeChange(OperatorAccessMode.QR_TOKEN) }
        AccessModeButton(
            modifier = Modifier.weight(1f),
            label = "PIN",
            selected = accessMode == OperatorAccessMode.PIN,
            enabled = !active,
        ) { onAccessModeChange(OperatorAccessMode.PIN) }
        AccessModeButton(
            modifier = Modifier.weight(1f),
            label = "공개",
            selected = accessMode == OperatorAccessMode.OPEN,
            enabled = !active,
        ) { onAccessModeChange(OperatorAccessMode.OPEN) }
    }

    if (accessMode != OperatorAccessMode.OPEN) {
        Text(
            modifier = Modifier.padding(top = 8.dp),
            text = "QR/PIN은 같은 핫스팟의 입장 제어입니다. 전송 암호화는 아니므로 " +
                "신뢰하는 WPA2/WPA3 핫스팟에서 사용하세요.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (accessMode == OperatorAccessMode.PIN && !active) {
        OutlinedTextField(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            value = pin,
            onValueChange = { value -> onPinChange(value.filter(Char::isDigit).take(8)) },
            label = { Text("청취 PIN (4~8자리)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true,
        )
    }

    if (!active) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .toggleable(
                    value = micPinEnabled,
                    role = Role.Checkbox,
                    onValueChange = onMicPinEnabledChange,
                )
                .sizeIn(minHeight = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = micPinEnabled,
                onCheckedChange = null,
            )
            Column(Modifier.padding(start = 8.dp)) {
                Text("강사 웹 마이크 PIN 사용", fontWeight = FontWeight.SemiBold)
                Text(
                    "기본은 사용 안 함 · 필요할 때 켜서 /mic 접속을 보호하세요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (micPinEnabled) {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = micPin,
                onValueChange = { value -> onMicPinChange(value.filter(Char::isDigit).take(8)) },
                label = { Text("강사 마이크 PIN (4~8자리)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true,
            )
        }
        Text(
            modifier = Modifier.padding(top = 6.dp),
            text = "강사는 http://송출주소:8787/mic 하나로 입장합니다. 브라우저 마이크 권한 단계는 HTTPS가 필요하며 PIN도 암호화된 화면에서만 전송됩니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    when (broadcast.phase) {
        BroadcastPhase.IDLE,
        BroadcastPhase.FAILED,
        -> {
            if (broadcast.phase == BroadcastPhase.FAILED) {
                Text(
                    modifier = Modifier.padding(top = 10.dp),
                    text = broadcast.errorMessage ?: "방송을 시작하지 못했습니다.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .sizeIn(minHeight = 56.dp),
                enabled = canStart &&
                    (accessMode != OperatorAccessMode.PIN || validPin) &&
                    (!micPinEnabled || validMicPin),
                onClick = {
                    val suppliedPin = pin.takeIf { accessMode == OperatorAccessMode.PIN }
                        ?.toCharArray()
                    val suppliedMicPin = micPin.takeIf { micPinEnabled }?.toCharArray()
                    onStart(accessMode, suppliedPin, suppliedMicPin)
                    onPinChange("")
                    onMicPinChange("")
                },
            ) {
                Text("방송 시작")
            }
        }

        BroadcastPhase.STARTING -> {
            OutlinedButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                onClick = onStop,
                colors = destructiveOutlinedButtonColors(),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
            ) {
                Text("준비 중 · 취소")
            }
        }

        BroadcastPhase.LIVE -> LiveBroadcastCard(
            broadcast = broadcast,
            accessMode = accessMode,
            paused = false,
            onPauseOrResume = onPause,
            onStop = onStop,
            onPlayTestTone = onPlayTestTone,
        )

        BroadcastPhase.PAUSED -> LiveBroadcastCard(
            broadcast = broadcast,
            accessMode = accessMode,
            paused = true,
            onPauseOrResume = onResume,
            onStop = onStop,
            onPlayTestTone = onPlayTestTone,
        )
    }
}

@Composable
private fun AccessModeButton(
    modifier: Modifier,
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier
            .sizeIn(minHeight = 48.dp)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) { }
            .alpha(if (enabled) 1f else 0.38f),
        shape = MaterialTheme.shapes.small,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        contentColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(
            if (selected) 1.5.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LiveBroadcastCard(
    broadcast: BroadcastSnapshot,
    accessMode: OperatorAccessMode,
    paused: Boolean,
    onPauseOrResume: () -> Unit,
    onStop: () -> Unit,
    onPlayTestTone: () -> Unit,
) {
    val url = broadcast.listenerUrl ?: return
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.5.dp, GuideCastSuccess.copy(alpha = 0.5f)),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                color = if (paused) GuideCastWarningContainer else GuideCastSuccessContainer,
                shape = CircleShape,
            ) {
                Text(
                    if (paused) {
                        "방송 일시정지 · 청취자 ${broadcast.listenerCount}명"
                    } else {
                        "방송 중 · 청취자 ${broadcast.listenerCount}명"
                    },
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    color = if (paused) GuideCastWarning else GuideCastSuccess,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                "접속 방식 · ${accessMode.displayName()}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = broadcast.inputLabel.orEmpty(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            broadcast.channelSummary?.let { channels ->
                Text(
                    text = "채널 · $channels",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            broadcast.translationWarning?.let { warning ->
                Text(
                    text = warning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            QrCode(url)
            SelectionContainer {
                Text(url, style = MaterialTheme.typography.bodySmall)
            }
            broadcast.speakerUrl?.let { speakerUrl ->
                var showSpeakerQr by remember { mutableStateOf(false) }
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { showSpeakerQr = !showSpeakerQr },
                ) {
                    Text(
                        if (broadcast.webSpeakerConnected) {
                            "강사 웹 마이크 연결됨 (QR 보기)"
                        } else {
                            "강사 웹 마이크 연결 QR 열기"
                        },
                    )
                }
                if (showSpeakerQr) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                    ) {
                        Text(
                            "강사용 스마트폰 원격 마이크 QR",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        broadcast.caSha256Fingerprint?.let { fingerprint ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = GuideCastWarningContainer,
                                shape = MaterialTheme.shapes.medium,
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        "이 송출기의 설치별 사설 CA · SHA-256",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = GuideCastWarning,
                                    )
                                    SelectionContainer {
                                        Text(
                                            text = fingerprint,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                        )
                                    }
                                    Text(
                                        "공식·공개 신뢰 인증서가 아닙니다. 인증서 설치 전에 강사 폰의 " +
                                            "다운로드 인증서 상세 화면에 표시된 SHA-256 지문과 이 값을 " +
                                            "한 글자씩 비교하세요. 다르면 변조되었거나 다른 송출기용이므로 " +
                                            "설치하지 마세요.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                        broadcast.caFingerprintWarning?.let { warning ->
                            Text(
                                text = warning,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        QrCode(speakerUrl)
                        SelectionContainer {
                            Text(speakerUrl, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            "강사가 자신의 스마트폰 브라우저로 접속하여 원격 음성을 송출합니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = !paused && !broadcast.testToneActive,
                onClick = onPlayTestTone,
            ) {
                Text(if (broadcast.testToneActive) "테스트음 송출 중…" else "모든 채널 3초 테스트음")
            }
            Text(
                text = "청취 브라우저에서 삐 소리가 들리면 해당 웹 송출 경로는 정상입니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (paused) {
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = onPauseOrResume,
                    ) { Text("방송 재개") }
                } else {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = onPauseOrResume,
                    ) { Text("방송 일시정지") }
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = onStop,
                    colors = destructiveOutlinedButtonColors(),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                ) {
                    Text("방송 중지")
                }
            }
        }
    }
}

@Composable
private fun QrCode(
    content: String,
    description: String = "청취 페이지 QR 코드",
) {
    val bitmap = remember(content) { createQrBitmap(content, 512) }
    Image(
        modifier = Modifier
            .size(224.dp)
            .background(Color.White, RoundedCornerShape(12.dp))
            .padding(8.dp),
        bitmap = bitmap.asImageBitmap(),
        contentDescription = description,
    )
}

private fun createQrBitmap(content: String, size: Int): Bitmap {
    val matrix = MultiFormatWriter().encode(
        content,
        BarcodeFormat.QR_CODE,
        size,
        size,
        mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 2,
        ),
    )
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            pixels[y * size + x] = if (matrix[x, y]) 0xFF10221E.toInt() else 0xFFFFFFFF.toInt()
        }
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

private fun AudioInputKind.displayName(): String = when (this) {
    AudioInputKind.DEVICE_PLAYBACK -> "가상 라인 입력 · 허용된 앱 재생음"
    AudioInputKind.WEB_SPEAKER -> "🌐 강사 웹 마이크 (원격 입력)"
    AudioInputKind.BUILT_IN -> "내장 마이크"
    AudioInputKind.WIRED_HEADSET -> "유선 이어폰/헤드셋 마이크"
    AudioInputKind.USB -> "USB-C/USB 오디오 입력"
    AudioInputKind.BLUETOOTH -> "Bluetooth 통화 마이크"
    AudioInputKind.OTHER -> "기타 Android 오디오 입력"
}

private fun OperatorAccessMode.displayName(): String = when (this) {
    OperatorAccessMode.QR_TOKEN -> "QR 입장코드"
    OperatorAccessMode.PIN -> "PIN"
    OperatorAccessMode.OPEN -> "공개"
}

internal fun openExternalOrCopy(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "브라우저에서 열기"))
    }.onFailure {
        copyText(context, url)
        Toast.makeText(
            context,
            "브라우저를 열 수 없어 주소를 클립보드에 복사했습니다.",
            Toast.LENGTH_LONG,
        ).show()
    }
}

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("${context.getString(R.string.app_name)} URL", text))
    Toast.makeText(context, "주소를 복사했습니다.", Toast.LENGTH_SHORT).show()
}

private fun ModelReadiness.displayName(): String = when (this) {
    ModelReadiness.NOT_INSTALLED -> "미설치"
    ModelReadiness.DOWNLOADING -> "다운로드 중"
    ModelReadiness.VERIFYING -> "설치 확인 중"
    ModelReadiness.READY -> "오프라인 준비됨"
    ModelReadiness.FAILED -> "준비 실패"
}

private fun ModelReadiness.statusColor(): Color = when (this) {
    ModelReadiness.READY -> GuideCastSuccess
    ModelReadiness.FAILED -> GuideCastError
    ModelReadiness.DOWNLOADING,
    ModelReadiness.VERIFYING,
    -> GuideCastWarning

    ModelReadiness.NOT_INSTALLED -> GuideCastMuted
}

private fun MoonshineTtsReadiness.displayName(): String = when (this) {
    MoonshineTtsReadiness.NOT_INSTALLED -> "미설치"
    MoonshineTtsReadiness.DOWNLOADING -> "다운로드 중"
    MoonshineTtsReadiness.READY -> "오프라인 준비됨"
    MoonshineTtsReadiness.FAILED -> "준비 실패"
}

private fun TranslationModelUiState.translationDisplayName(languageTag: String): String {
    val lightweight = readiness(languageTag)
    val gemmaPriority = useGemma &&
        GemmaTranslationProvider.supportsTranslation(selectedSourceLanguageTag, languageTag)
    return when {
        gemmaPriority && gemmaReady && lightweight == ModelReadiness.READY ->
            "Gemma 우선 · 경량 복구 준비"
        gemmaPriority && gemmaReady -> "Gemma 준비 · 경량 복구 ${lightweight.displayName()}"
        gemmaPriority -> "Gemma 미준비 · 경량 ${lightweight.displayName()}"
        else -> lightweight.displayName()
    }
}

private fun TranslationModelUiState.ttsDisplayName(languageTag: String): String =
    if (ttsUnavailableReason(languageTag) != null) {
        "사용 불가 · ${ttsUnavailableReason(languageTag)?.take(120)}"
    } else if (ttsFallbackReady(languageTag)) {
        "설치된 Android 오프라인 음성 준비"
    } else {
        ttsReadiness(languageTag).displayName()
    }

internal fun moonshineTtsFailureDetail(status: MoonshineTtsStatus?): String? = status
    ?.takeIf { it.readiness == MoonshineTtsReadiness.FAILED }
    ?.errorMessage
    ?.trim()
    ?.takeIf(String::isNotEmpty)
    ?.let { "Moonshine 오류 · ${it.take(300)}" }
