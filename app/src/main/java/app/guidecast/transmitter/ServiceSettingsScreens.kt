package app.guidecast.transmitter

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CommonServiceSettingsScreen(app: GuideCastApplication, onBack: () -> Unit,
    onOpenGlossary: (() -> Unit)? = null, onOpenDomainCorpus: (() -> Unit)? = null,
    onRecheckSpeechVoices: () -> Unit = {}) {
    val defaults by app.serviceDefaults.state.collectAsStateWithLifecycle()
    val runtime by app.broadcastRuntime.state.collectAsStateWithLifecycle()
    val menuBroadcast by app.menuBroadcast.state.collectAsStateWithLifecycle()
    val fileWork by app.localFileWorkActive.collectAsStateWithLifecycle()
    val noteWork by app.localVoiceNoteWorkActive.collectAsStateWithLifecycle()
    val modelWork by app.localModelWorkActive.collectAsStateWithLifecycle()
    val exampleChangesEnabled = !app.webBroadcastOwnership.isOwned && !menuBroadcast.isActive &&
        runtime.phase == BroadcastPhase.IDLE && runtime.inputPhase == InputPhase.IDLE && !runtime.inputStopping &&
        !fileWork && !noteWork && !modelWork
    BackHandler(onBack = onBack)
    LazyColumn(Modifier.fillMaxSize().imePadding().semantics { paneTitle = "공통 설정" },
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            TextButton(onClick = onBack) { Text("돌아가기") }
            Text("공통 설정", style = MaterialTheme.typography.headlineMedium)
            Text("음성 노트·파일 변환의 새 작업에 사용할 언어·모델 기본값과 API 계정을 관리합니다. 언어·모델 기본값은 진행 중인 작업에 적용하지 않습니다.")
            Text("통번역 스트리밍·통역 중계의 언어·모델은 해당 메뉴에서 따로 설정합니다.")
        }
        item {
            Text("기본 원문 언어", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FILE_LANGUAGE_OPTIONS.forEach { (tag, label) -> FilterChip(defaults.sourceTag == tag,
                    onClick = { app.serviceDefaults.setLanguages(tag, defaults.targetTags) }, label = { Text(label) }) }
            }
            Text("기본 번역 언어 · 최대 5개")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FILE_LANGUAGE_OPTIONS.forEach { (tag, label) ->
                    val selected = defaults.targetTags.any { it == tag || it.substringBefore('-') == tag.substringBefore('-') }
                    FilterChip(selected, enabled = selected || defaults.targetTags.size < 5, onClick = {
                        val same = defaults.targetTags.filter { it.substringBefore('-') == tag.substringBefore('-') }.toSet()
                        app.serviceDefaults.setLanguages(defaults.sourceTag, if (selected) defaults.targetTags - same else defaults.targetTags + tag)
                    }, label = { Text(label) })
                }
            }
        }
        item {
            Text("기본 기기 내 번역", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(FileTranslationEngine.GEMMA, FileTranslationEngine.MLKIT).forEach { engine ->
                    FilterChip(defaults.localEngine == engine, onClick = { app.serviceDefaults.setLocalEngine(engine) },
                        label = { Text(if (engine == FileTranslationEngine.GEMMA) "Gemma" else "기기 내 번역") })
                }
            }
            Text("각 메뉴에서 지원하는 언어와 준비된 모델을 사용합니다. 자료·모델 설치와 사용 동의는 해당 메뉴에서 확인합니다.")
        }
        item {
            Text("API 계정·기본 문장 번역 서비스", style = MaterialTheme.typography.titleMedium)
            Text("Google·OpenAI 키는 같은 제공자의 메뉴에서 사용합니다. 키를 변경하거나 삭제하면 같은 제공자의 전송 동의가 즉시 해제되어 진행 중인 온라인 통역이 중단될 수 있습니다. 다시 이용하려면 해당 메뉴에서 전송 동의를 확인하세요. 키 등록만으로 방송을 시작하거나 음성·문장 전송에 동의하지는 않습니다.")
            TranslationApiPanel(app.commonServiceApiSettings, app.commonServiceApiService, enabled = true, textOnly = true)
        }
        item {
            Text("전문 자료·용어집", style = MaterialTheme.typography.titleMedium)
            onOpenGlossary?.let { open ->
                OutlinedButton(onClick = open, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("번역 용어 사전 · 검색 / 수정 / 일괄 등록")
                }
            }
            onOpenDomainCorpus?.let { open ->
                OutlinedButton(onClick = open, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("전문 용어·참고 문장 · 검수 예문")
                }
            }
            Text("중계의 강의 대본·참고 글·통역 지침은 ‘통역 중계 → 서비스 설정 → 전문 분야·자료·지침’에서 관리합니다.",
                style = MaterialTheme.typography.bodySmall)
        }
        item { DiagnosticsAndVoiceSettings(onRecheckSpeechVoices) }
        item { AutomaticExampleControls(app.automaticTranslationExamples, exampleChangesEnabled, reviewRepository = app.domainCorpus) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MenuServiceSettingsScreen(app: GuideCastApplication, profile: ServiceMenuProfile,
    enabled: Boolean, onBack: () -> Unit) {
    val all by app.serviceMenuProfiles.state.collectAsStateWithLifecycle()
    val options = all.getValue(profile)
    val languages = if (profile == ServiceMenuProfile.NOTES) VOICE_NOTE_LANGUAGES else FILE_LANGUAGE_OPTIONS
    val maximum = if (profile == ServiceMenuProfile.NOTES) 1 else 4
    val effectiveTargets = effectiveMenuTargetTags(profile, options.targetTags)
    val effectiveSource = if (options.automaticSource) null else supportedMenuLanguageTag(options.sourceTag, languages.keys)
    BackHandler(onBack = onBack)
    LazyColumn(Modifier.fillMaxSize().imePadding().semantics { paneTitle = "이 메뉴 설정" },
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            TextButton(onClick = onBack) { Text("돌아가기") }
            Text("이 메뉴 설정", style = MaterialTheme.typography.headlineMedium)
            Text(if (profile == ServiceMenuProfile.NOTES) "음성 노트" else "파일 변환·재생")
            Text(if (options.usesCommonDefaults) "공통 기본값 · 새 작업 준비 시 적용" else "이 메뉴에서 따로 선택한 설정")
            if (!enabled) Text("진행 중인 작업과 방송을 마친 뒤 설정을 변경하세요.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = enabled, onClick = { app.serviceMenuProfiles.useCommonDefaults(profile) }) { Text("공통 기본값 사용") }
                Button(enabled = enabled, onClick = { app.serviceMenuProfiles.useMenuSettings(profile) }) { Text("이 메뉴만 따로 설정") }
            }
        }
        item {
            Text("원문 언어", style = MaterialTheme.typography.titleMedium)
            if (options.automaticSource) Text(
                if (profile == ServiceMenuProfile.NOTES) "자동 감지 · 녹음 후 처리" else "자동 감지 · 파일별 처리",
                style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                languages.forEach { (tag, label) -> FilterChip(effectiveSource == tag,
                    enabled = enabled, onClick = { app.serviceMenuProfiles.setLanguages(profile, tag, options.targetTags) }, label = { Text(label) }) }
            }
            Text("번역 언어 · 최대 ${maximum}개")
            if (options.usesCommonDefaults && options.targetTags.size > maximum)
                Text("공통 언어 중 이 메뉴가 지원하는 처음 ${maximum}개를 사용합니다.", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                languages.forEach { (tag, label) ->
                    val selected = tag in effectiveTargets
                    FilterChip(selected, enabled = enabled && (maximum == 1 || selected || effectiveTargets.size < maximum), onClick = {
                        val next = if (maximum == 1) setOf(tag) else if (selected)
                            effectiveTargets - tag else effectiveTargets + tag
                        app.serviceMenuProfiles.setTargetLanguages(profile, next)
                    }, label = { Text(label) })
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(FileTranslationEngine.GEMMA, FileTranslationEngine.MLKIT).forEach { engine ->
                    FilterChip(options.localEngine == engine, enabled = enabled, onClick = { app.serviceMenuProfiles.setLocalEngine(profile, engine) },
                        label = { Text(if (engine == FileTranslationEngine.GEMMA) "Gemma" else "기기 내 번역") })
                }
            }
        }
        item {
            Text("이 메뉴의 AI 서비스·말투·전송 동의", style = MaterialTheme.typography.titleMedium)
            if (options.usesCommonDefaults) Text("서비스·모델·말투·분야는 공통 기본값을 사용합니다. API 키·전송 동의·연결 확인은 이 메뉴에서 관리합니다. 내용을 바꾸려면 ‘이 메뉴만 따로 설정’을 선택하세요.")
            TranslationApiPanel(app.serviceMenuProfiles.settings(profile), app.serviceMenuProfiles.service(profile),
                enabled = enabled, contentOptionsEnabled = !options.usesCommonDefaults, textOnly = true)
        }
    }
}
