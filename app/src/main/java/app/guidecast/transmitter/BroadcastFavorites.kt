package app.guidecast.transmitter

import android.content.Context
import app.guidecast.core.audio.AudioInputKind
import app.guidecast.core.translation.BriefingFormality
import app.guidecast.core.translation.InterpretationBriefing
import app.guidecast.core.translation.TranslationStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 서비스 선택값만 보관한다. 자격·전송 동의·자유 입력 지시문은 포함하지 않는다. */
internal data class FavoriteTranslationService(
    val provider: TranslationApiProvider = TranslationApiProvider.LOCAL,
    val model: String = "gpt-5.4-mini", val baseUrl: String = "https://api.openai.com/v1",
    val protocol: TranslationApiProtocol = TranslationApiProtocol.RESPONSES,
    val mode: OnlineInterpretationMode = OnlineInterpretationMode.CONTINUOUS,
    val realtimeAudio: Boolean = false, val liveVoice: RelayVoiceGender = RelayVoiceGender.AUTO,
) {
    fun options(current: TranslationApiOptions = TranslationApiOptions()): TranslationApiOptions = current.copy(
        provider = provider, model = model, baseUrl = baseUrl, protocol = protocol,
        interpretationMode = mode, realtimeAudio = realtimeAudio, liveVoice = liveVoice)

    fun json(): JSONObject = JSONObject().put("provider", provider.name).put("model", model)
        .put("baseUrl", baseUrl).put("protocol", protocol.name).put("mode", mode.name)
        .put("realtimeAudio", realtimeAudio).put("liveVoice", liveVoice.name)

    companion object {
        fun from(options: TranslationApiOptions) = FavoriteTranslationService(options.provider, options.model,
            options.baseUrl, options.protocol, options.interpretationMode, options.realtimeAudio, options.liveVoice)
        fun read(row: JSONObject): FavoriteTranslationService = FavoriteTranslationService(
            TranslationApiProvider.valueOf(row.getString("provider")), row.getString("model"), row.getString("baseUrl"),
            TranslationApiProtocol.valueOf(row.getString("protocol")), OnlineInterpretationMode.valueOf(row.getString("mode")),
            row.optBoolean("realtimeAudio"), RelayVoiceGender.valueOf(row.optString("liveVoice", "AUTO")))
            .also { require(validTranslationApiOptions(it.options())) }
    }
}

internal data class BroadcastFavoriteSetup(
    val source: String = "ko-KR", val targets: List<String> = listOf("en"),
    val service: FavoriteTranslationService = FavoriteTranslationService(),
    val output: BroadcastOutput = BroadcastOutput.LISTENERS, val speakerSound: String = "en",
    val inputKind: AudioInputKind? = null, val voices: Map<String, SpeechVoicePreference> = emptyMap(),
    val tone: TranslationStyle = TranslationStyle.CONVERSATIONAL,
    val useGemma: Boolean = true, val speechEnabled: Boolean = true,
    val briefing: InterpretationBriefing = InterpretationBriefing(),
) {
    fun json(): JSONObject {
        val brief = briefing.bounded()
        return JSONObject().put("source", source).put("targets", JSONArray(targets)).put("service", service.json())
            .put("output", output.name).put("speakerSound", speakerSound).put("inputKind", inputKind?.name)
            .put("voices", JSONObject(voices.mapValues { it.value.name })).put("tone", tone.name)
            .put("useGemma", useGemma).put("speechEnabled", speechEnabled)
            .put("briefing", JSONObject().put("topic", brief.topic).put("situation", brief.situation)
                .put("speaker", brief.speaker).put("audience", brief.audience).put("formality", brief.formality.name)
                .put("notes", brief.notes).put("terms", brief.terms).put("configured", brief.configured))
    }

    companion object {
        fun read(row: JSONObject): BroadcastFavoriteSetup {
            val brief = row.getJSONObject("briefing")
            val voices = row.getJSONObject("voices")
            val targets = row.getJSONArray("targets")
            return BroadcastFavoriteSetup(source = row.getString("source"),
                targets = (0 until targets.length()).map { targets.getString(it) }.distinct().take(MAX_SIMULTANEOUS_TRANSLATION_LANGUAGES),
                service = FavoriteTranslationService.read(row.getJSONObject("service")),
                output = BroadcastOutput.valueOf(row.getString("output")), speakerSound = row.getString("speakerSound"),
                inputKind = row.optString("inputKind").takeIf(String::isNotBlank)?.let(AudioInputKind::valueOf),
                voices = voices.keys().asSequence().associateWith { SpeechVoicePreference.valueOf(voices.getString(it)) },
                tone = TranslationStyle.valueOf(row.getString("tone")), useGemma = row.optBoolean("useGemma", true),
                speechEnabled = row.optBoolean("speechEnabled", true),
                briefing = InterpretationBriefing(topic = brief.optString("topic"), situation = brief.optString("situation"),
                    speaker = brief.optString("speaker"), audience = brief.optString("audience"),
                    formality = BriefingFormality.valueOf(brief.optString("formality", "FORMAL")), notes = brief.optString("notes"),
                    configured = brief.optBoolean("configured"), terms = brief.optString("terms")).bounded())
        }
    }
}

internal data class BroadcastFavorite(val id: String, val name: String, val setup: BroadcastFavoriteSetup)

internal const val FAVORITE_ALIAS_MAX_LENGTH = 20
private fun favoriteLanguageLabel(tag: String): String = relayCaptionLanguageLabel(tag).let {
    if (it == tag) relayCaptionLanguageLabel(tag.substringBefore('-')) else it
}

internal fun suggestedFavoriteAlias(setup: BroadcastFavoriteSetup): String = setup.briefing.topic.trim().ifBlank {
    val first = setup.targets.firstOrNull()?.let(::relayCaptionLanguageLabel) ?: "언어 선택"
    "${favoriteLanguageLabel(setup.source)} → $first${if (setup.targets.size > 1) " 외 ${setup.targets.size - 1}" else ""} · " +
        if (setup.service.provider == TranslationApiProvider.LOCAL) "오프라인" else "온라인"
}.take(FAVORITE_ALIAS_MAX_LENGTH).dropLastWhile(Char::isHighSurrogate)

/** 행사 정보 화면을 한 번 열어 저장만 한 빈 상태(기본 말투)는 "없음"으로 센다. */
private fun InterpretationBriefing.hasEventDetails(): Boolean = formality != BriefingFormality.FORMAL ||
    listOf(topic, situation, speaker, audience, notes, terms).any(String::isNotBlank)

internal fun favoriteSetupSummary(setup: BroadcastFavoriteSetup, includeBriefing: Boolean = false): String = buildString {
    append(favoriteLanguageLabel(setup.source)).append(" → ")
    append(setup.targets.joinToString(" · ", transform = ::relayCaptionLanguageLabel))
    append(if (setup.service.provider == TranslationApiProvider.LOCAL) " · 오프라인" else " · 온라인")
    append(" · ").append(setup.output.label)
    if (includeBriefing) append("\n행사 정보 ").append(if (setup.briefing.hasEventDetails()) "있음" else "없음")
}

internal class BroadcastFavorites internal constructor(raw: String, private val persist: (String) -> Unit) {
    private val mutableState = MutableStateFlow(runCatching {
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { index -> runCatching {
            val row = array.getJSONObject(index)
            BroadcastFavorite(row.getString("id"), row.getString("name"), BroadcastFavoriteSetup.read(row.getJSONObject("setup")))
                .takeIf { it.id.isNotBlank() && it.name.isNotBlank() }
        }.getOrNull() }.distinctBy { it.id }.take(6)
    }.getOrDefault(emptyList()))
    val state = mutableState.asStateFlow()

    constructor(context: Context) : this(context.getSharedPreferences("broadcast_favorites", Context.MODE_PRIVATE)
        .getString("items", "").orEmpty(), { json -> context.getSharedPreferences("broadcast_favorites", Context.MODE_PRIVATE)
        .edit().putString("items", json).apply() })

    private fun store(items: List<BroadcastFavorite>) {
        val json = JSONArray().apply { items.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("setup", it.setup.json())) } }
        persist(json.toString())
        mutableState.value = items
    }
    @Synchronized fun save(name: String, setup: BroadcastFavoriteSetup): Boolean {
        val alias = name.trim()
        if (mutableState.value.size >= 6 || alias.isBlank() || alias.length > FAVORITE_ALIAS_MAX_LENGTH ||
            mutableState.value.any { it.name == alias }) return false
        store(mutableState.value + BroadcastFavorite(UUID.randomUUID().toString(), alias, setup))
        return true
    }
    @Synchronized fun rename(id: String, name: String): Boolean {
        val alias = name.trim()
        if (alias.isBlank() || alias.length > FAVORITE_ALIAS_MAX_LENGTH || mutableState.value.none { it.id == id } ||
            mutableState.value.any { it.id != id && it.name == alias }) return false
        store(mutableState.value.map { if (it.id == id) it.copy(name = alias) else it })
        return true
    }
    @Synchronized fun overwrite(id: String, setup: BroadcastFavoriteSetup): Boolean {
        if (mutableState.value.none { it.id == id }) return false
        store(mutableState.value.map { if (it.id == id) it.copy(setup = setup) else it })
        return true
    }
    @Synchronized fun delete(id: String) { store(mutableState.value.filterNot { it.id == id }) }
}

internal data class FavoriteAvailability(
    val sources: Set<String> = emptySet(), val targets: Set<String> = emptySet(),
    val inputKinds: Set<AudioInputKind> = emptySet(),
    val voices: Map<String, Set<SpeechVoicePreference>> = emptyMap(),
    val serviceAvailable: Boolean = true, val gemmaAvailable: Boolean = true,
    val maximumTargets: Int = MAX_SIMULTANEOUS_TRANSLATION_LANGUAGES,
    val fallbackSource: String? = null, val fallbackTargets: List<String>? = null,
)

internal fun selectedFavoriteVoices(targets: List<String>, preferences: Map<String, SpeechVoicePreference>) =
    targets.associateWith { preferences.getOrDefault(it, SpeechVoicePreference.AUTO) }

internal data class FavoriteLoadResult(val setup: BroadcastFavoriteSetup, val skipped: List<String>, val applied: Boolean) {
    val notice: String get() = if (!applied) skipped.firstOrNull() ?: "방송을 마친 뒤 불러와 주세요"
        else if (skipped.isEmpty()) "즐겨찾기를 불러왔어요" else "${skipped.distinct().joinToString(" · ")} 빼고 불러왔어요"
}

internal fun resolveBroadcastFavorite(current: BroadcastFavoriteSetup, saved: BroadcastFavoriteSetup,
    available: FavoriteAvailability, broadcasting: Boolean): FavoriteLoadResult {
    if (broadcasting) return FavoriteLoadResult(current, emptyList(), false)
    val skipped = mutableListOf<String>()
    var source = saved.source.takeIf { it in available.sources }
        ?: (available.fallbackSource ?: current.source).also { skipped += "말하는 언어" }
    val accepted = saved.targets.filter { tag -> (tag in available.targets).also {
        if (!it) skipped += "${relayCaptionLanguageLabel(tag)}는 음성 준비가 필요해"
    } }.distinct().take(available.maximumTargets)
    val targets = if (accepted.isEmpty() && saved.targets.isNotEmpty()) {
        val fallbackSource = available.fallbackSource ?: current.source
        if (source != fallbackSource) skipped += "말하는 언어"
        source = fallbackSource
        available.fallbackTargets ?: current.targets
    } else accepted
    if (saved.targets.size > available.maximumTargets) skipped += "언어 개수 초과"
    val service = if (available.serviceAvailable) saved.service else current.service.also { skipped += "이용 키 없는 서비스" }
    val input = if (saved.inputKind == null || saved.inputKind in available.inputKinds) saved.inputKind
        else current.inputKind.also { skipped += "연결 안 된 마이크" }
    val voices = current.voices.toMutableMap()
    saved.voices.forEach { (tag, voice) ->
        if (tag in targets && (available.voices[tag] == null || voice in available.voices[tag].orEmpty())) voices[tag] = voice
        else if (tag in targets) skipped += "${relayCaptionLanguageLabel(tag)} 통역 음성은 준비가 필요해"
    }
    val output = availableBroadcastOutput(saved.output)
    if (output != saved.output) skipped += "이 휴대폰 스피커"
    val sound = saved.speakerSound.takeIf { it == "source" || it in targets }
        ?: current.speakerSound.takeIf { it == "source" || it in targets } ?: targets.firstOrNull() ?: "source"
    val useGemma = if (!saved.useGemma || available.gemmaAvailable) saved.useGemma else current.useGemma.also { skipped += "내려받지 않은 통역 모델" }
    return FavoriteLoadResult(saved.copy(source = source, targets = targets, service = service, inputKind = input,
        voices = voices, output = output, speakerSound = sound, useGemma = useGemma), skipped, true)
}
