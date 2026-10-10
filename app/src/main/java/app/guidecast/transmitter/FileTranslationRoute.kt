package app.guidecast.transmitter

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Composable
internal fun FileTranslationRoute(model: FileTranslationViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val state by model.uiState.collectAsStateWithLifecycle()
    val app = context.applicationContext as GuideCastApplication
    val defaults by app.serviceDefaults.state.collectAsStateWithLifecycle()
    val commonApi by app.commonServiceApiSettings.state.collectAsStateWithLifecycle()
    val localWorkActive by app.localFileWorkActive.collectAsStateWithLifecycle()
    val ownBroadcastActiveFlow = remember(app) {
        app.menuBroadcast.state.map { it.isActive && it.origin == MenuBroadcastOrigin.FILES }
            .distinctUntilChanged()
    }
    val ownBroadcastActive by ownBroadcastActiveFlow.collectAsStateWithLifecycle(
        initialValue = app.menuBroadcast.owns(MenuBroadcastOrigin.FILES))
    LaunchedEffect(defaults, commonApi.revision, state.isConverting, state.isLoading,
        localWorkActive, ownBroadcastActive) {
        if (!state.isConverting && !state.isLoading) app.serviceMenuProfiles.refreshInheritedDefaults(ServiceMenuProfile.FILES)
    }
    val playback by model.playbackState.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.selectFile(uri) else model.cancelPicker()
    }
    val multiplePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) model.selectFiles(uris)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) model.selectFolder(uri)
    }
    var pendingFailedOnly by rememberSaveable { mutableStateOf(false) }
    var pendingRetryUri by rememberSaveable { mutableStateOf<String?>(null) }
    val speechPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) model.convert(pendingFailedOnly, pendingRetryUri) else model.reportSpeechPermissionDenied()
    }
    val requestConversion: (Boolean, String?) -> Unit = { failedOnly, retryUri ->
        pendingFailedOnly = failedOnly; pendingRetryUri = retryUri
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) model.convert(failedOnly, retryUri)
        else speechPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    val playing = playback
    val webStartIssue = fileWebBroadcastStartIssue(playing != null, state.isConverting,
        state.isLoading, playing?.isTranslating == true)
    Column(Modifier.fillMaxSize()) {
    MenuWebBroadcastCard(context.applicationContext as GuideCastApplication, MenuBroadcastOrigin.FILES,
        canStart = webStartIssue == null,
        onStart = model::startWebBroadcast, onStop = model::stopWebBroadcast,
        startUnavailableReason = webStartIssue)
    Box(Modifier.weight(1f)) {
    if (playing != null) {
        FilePlaybackTranscriptScreen(playing, model::playPause, model::stop, model::seek,
            onPrevious = { model.adjacent(-1) }, onNext = { model.adjacent(1) },
            onRepeatChange = model::setRepeat, onSpeedChange = model::setSpeed,
            onTranslationLanguageChange = model::translatePlayback,
            onRelinkFile = { model.prepareRelink(); picker.launch(arrayOf("audio/*", "video/*")) },
            onBack = model::closePlayback)
    } else {
        FileTranslationScreen(state, onChooseFile = { picker.launch(arrayOf("audio/*", "video/*")) },
            onSourceLanguageChange = model::selectSource, onTargetLanguageToggle = model::toggleTarget,
            onTranslationEngineChange = model::selectEngine,
            onConvert = { requestConversion(false, null) }, onCancel = { model.cancel() }, onOpenEntry = model::openEntry,
            onDeleteEntry = model::delete, onBack = onBack,
            onChooseFiles = { multiplePicker.launch(arrayOf("audio/*", "video/*")) },
            onChooseFolder = { folderPicker.launch(null) },
            onRetryFailed = { requestConversion(true, null) },
            onRetryFile = { requestConversion(false, it) })
    }
    }
    }
}

@Composable
internal fun FileTaskLifecycle(model: FileTranslationViewModel) {
    val state by model.uiState.collectAsStateWithLifecycle()
    val playback by model.playbackState.collectAsStateWithLifecycle()
    val view = LocalView.current
    val owner = LocalLifecycleOwner.current
    val keepAwake = state.isConverting || playback?.isPlaying == true
    DisposableEffect(view, keepAwake) {
        val previous = view.keepScreenOn
        if (keepAwake) view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }
    DisposableEffect(owner, model) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> model.refreshRecognitionSupport()
                Lifecycle.Event.ON_STOP -> model.pauseForBackground()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

/** The enabled action and its explanation share the same preparation state. */
internal fun fileWebBroadcastStartIssue(hasSelectedFile: Boolean, isConverting: Boolean,
    isLoading: Boolean, isTranslating: Boolean): String? = when {
    isConverting -> "파일 변환을 마친 뒤 웹오디오방송을 시작할 수 있습니다."
    isLoading -> "선택한 파일을 확인하고 있습니다. 준비가 끝나면 웹오디오방송을 시작할 수 있습니다."
    isTranslating -> "번역을 저장하고 있습니다. 완료 후 원음과 번역을 웹으로 방송할 수 있습니다."
    !hasSelectedFile -> "저장된 파일의 스크립트 재생을 열고 웹오디오방송을 시작하세요."
    else -> null
}
