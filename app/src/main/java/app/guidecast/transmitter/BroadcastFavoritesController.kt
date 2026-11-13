package app.guidecast.transmitter

import android.speech.tts.TextToSpeech
import app.guidecast.provider.moonshine.tts.MoonshineTtsReadiness
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

internal fun favoriteAvailability(app: GuideCastApplication, current: BroadcastFavoriteSetup,
    saved: BroadcastFavoriteSetup, models: TranslationModelUiState, input: AudioInputUiState,
    installedVoices: Map<String, Set<SpeechVoicePreference>> = emptyMap()): FavoriteAvailability {
    val usableService = app.translationApiSettings.favoriteServiceAvailable(saved.service.options())
    val effective = (if (usableService) saved.service else current.service).options()
    val native = effective.usesNativeLiveAudio
    val sources = (if (native) NATIVE_RELAY_SOURCE_LANGUAGE_OPTIONS else SOURCE_LANGUAGE_OPTIONS).map { it.languageTag }.toSet()
    val relay = app.interpreterRelaySettings.state.value
    val fallbackSource = if (native) relay.source else models.selectedSourceLanguageTag
    val fallbackTargets = if (native) relay.targetLanguageTags else models.selectedLanguageTags.toList()
    val source = saved.source.takeIf { it in sources } ?: fallbackSource
    val targetOptions = if (native) nativeRelayTargetLanguageOptions(source) else translationTargetLanguageOptions(source)
    // 시작 화면의 언어 선택은 준비 상태로 선택지를 막지 않는다. 모르는 상태는 적용 후 준비 확인에 맡긴다.
    val targets = targetOptions.map { it.languageTag }.toSet()
    val voices = saved.voices.mapValues { (tag, _) -> SpeechVoicePreference.entries.filter { voice ->
        when (voice) {
            SpeechVoicePreference.AUTO -> true
            SpeechVoicePreference.MOONSHINE -> models.ttsReadiness(tag) == MoonshineTtsReadiness.READY || models.ttsStatuses.none { it.languageTag == tag }
            else -> voice in installedVoices[tag].orEmpty() || installedVoices[tag] == null
        }
    }.toSet() }
    return FavoriteAvailability(sources, targets, input.availableDevices.map { it.kind }.toSet(), voices,
        usableService, models.gemmaReady, if (native) MAX_RELAY_LANGUAGES else MAX_SIMULTANEOUS_TRANSLATION_LANGUAGES,
        fallbackSource, fallbackTargets)
}

internal data class FavoriteSystemVoice(val language: String, val offline: Boolean, val installed: Boolean)

internal fun favoriteSystemVoiceReady(language: String, engineInstalled: Boolean, voices: List<FavoriteSystemVoice>): Boolean =
    engineInstalled && voices.any { it.offline && it.installed &&
        Locale.forLanguageTag(it.language).language == Locale.forLanguageTag(language).language }

private suspend fun installedFavoriteVoices(app: GuideCastApplication, requested: Map<String, SpeechVoicePreference>): Map<String, Set<SpeechVoicePreference>> {
    val ready = mutableMapOf<String, Set<SpeechVoicePreference>>()
    for ((preference, entries) in requested.entries.filter { it.value.enginePackage != null }.groupBy { it.value }) {
        val initialized = CompletableDeferred<Int>()
        val engine = TextToSpeech(app, { initialized.complete(it) }, preference.enginePackage)
        try {
            if (withTimeoutOrNull(3_000L) { initialized.await() } != TextToSpeech.SUCCESS) continue
            val installed = engine.engines.any { it.name == preference.enginePackage }
            val voices = engine.voices.orEmpty().map { voice -> FavoriteSystemVoice(voice.locale.toLanguageTag(),
                !voice.isNetworkConnectionRequired, TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in voice.features.orEmpty()) }
            entries.forEach { (tag, _) -> if (favoriteSystemVoiceReady(tag, installed, voices)) ready[tag] = setOf(preference) }
        } catch (_: IllegalArgumentException) {
            // 연결할 수 없는 음성 서비스는 바꾸지 않고 건너뛴 항목으로 알린다.
        } finally { engine.shutdown() }
    }
    return ready
}

internal suspend fun withPreparedFavoriteSource(source: String, currentSource: String,
    onSource: (String) -> Unit, awaitReady: suspend (String) -> Boolean, stillIdle: () -> Boolean,
    apply: suspend () -> Unit): Boolean {
    if (!stillIdle()) return false
    if (source != currentSource) {
        onSource(source)
        if (!awaitReady(source)) return false
    }
    if (!stillIdle()) return false
    apply()
    return true
}

/**
 * 말하는 언어를 바꾼 직후에는 언어 준비 작업이 돌면서 언어 선택 변경이 조용히 무시된다(S21 실측: 두 번 눌러야 돌아옴).
 * 준비가 끝나기를 기다렸다가 넣고, 실제로 들어갔는지 확인한다.
 */
internal suspend fun applyFavoriteTargets(targets: List<String>, state: () -> Pair<Set<String>, Boolean>,
    onClear: () -> Unit, onTarget: (String) -> Unit, maximumBusyWaitMillis: Long = 30_000L): Boolean {
    val wanted = targets.toSet()
    repeat(3) {
        if (state().first == wanted) return true
        withTimeoutOrNull(maximumBusyWaitMillis) { while (state().second) delay(100) } ?: return false
        if (state().first == wanted) return true
        onClear()
        targets.forEach(onTarget)
        delay(250)
    }
    return state().first == wanted
}

internal fun favoriteRuntimeBlocked(app: GuideCastApplication): Boolean {
    val runtime = app.broadcastRuntime.state.value
    return runtime.phase in setOf(BroadcastPhase.STARTING, BroadcastPhase.LIVE, BroadcastPhase.PAUSED) ||
        runtime.inputPhase in setOf(InputPhase.STARTING, InputPhase.ACTIVE, InputPhase.PAUSED) ||
        runtime.inputDraining || runtime.inputStopping || runtime.broadcastFinishing || runtime.translationTestActive ||
        app.menuBroadcast.state.value.isActive
}

/** 실제 설정 변경은 기존 화면과 같은 콜백·저장소를 사용한다. */
internal suspend fun applyBroadcastFavorite(app: GuideCastApplication, current: BroadcastFavoriteSetup, saved: BroadcastFavoriteSetup,
    latestModels: () -> TranslationModelUiState, latestInput: () -> AudioInputUiState, blocked: () -> Boolean,
    awaitSourceReady: suspend (String) -> Boolean,
    onSource: (String) -> Unit, onClearTargets: () -> Unit, onTarget: (String) -> Unit,
    onInput: (Int) -> Unit, onVoice: (String, SpeechVoicePreference) -> Unit,
    onGemma: (Boolean) -> Unit, onSpeech: (Boolean) -> Unit): FavoriteLoadResult {
    fun idle() = !blocked() && !favoriteRuntimeBlocked(app) && !latestModels().isBusy
    val initialModels = latestModels()
    var result = resolveBroadcastFavorite(current, saved, favoriteAvailability(app, current, saved, initialModels, latestInput()), !idle())
    if (!result.applied) return result
    val nativeMode = result.setup.service.options().usesNativeLiveAudio
    val prepared = withPreparedFavoriteSource(if (nativeMode) initialModels.selectedSourceLanguageTag else result.setup.source,
        initialModels.selectedSourceLanguageTag, onSource, awaitSourceReady, ::idle) {
    val installedVoices = installedFavoriteVoices(app, saved.voices)
    if (!idle()) { result = FavoriteLoadResult(current, emptyList(), false); return@withPreparedFavoriteSource }
    val models = latestModels()
    val input = latestInput()
    result = resolveBroadcastFavorite(current, saved, favoriteAvailability(app, current, saved, models, input, installedVoices), false)
    val value = result.setup
    val settings = app.translationApiSettings
    val previous = settings.state.value
    val changedService = value.service != FavoriteTranslationService.from(previous)
    if (changedService) settings.configure(value.service.options(previous))
    settings.setTone(value.tone)
    val native = settings.state.value.usesNativeLiveAudio
    if (native) {
        val relay = app.interpreterRelaySettings.state.value.withSource(value.source).withTargets(value.targets)
        app.interpreterRelaySettings.update(relay.copy(target = value.speakerSound.takeIf { it in value.targets } ?: value.targets.first()))
    } else {
        applyFavoriteTargets(value.targets, { latestModels().let { it.selectedLanguageTags to it.isBusy } }, onClearTargets, onTarget)
    }
    input.availableDevices.firstOrNull { it.kind == value.inputKind }?.let { device ->
        if (device.platformId != input.selectedDevice?.platformId) onInput(device.platformId)
    }
    value.voices.forEach { (tag, voice) -> if (voice != models.voicePreferences.getOrDefault(tag, SpeechVoicePreference.AUTO)) onVoice(tag, voice) }
    if (value.useGemma != models.useGemma) onGemma(value.useGemma)
    onSpeech(value.speechEnabled)
    app.interpretationBriefingSettings.save(value.briefing)
    if (changedService && value.service.provider != TranslationApiProvider.LOCAL)
        result = result.copy(skipped = result.skipped + "온라인 통역 전송 동의 확인")
    }
    return if (prepared) result else FavoriteLoadResult(current, listOf("말하는 언어 준비 중 · 잠시 뒤 다시 눌러 주세요"), false)
}
