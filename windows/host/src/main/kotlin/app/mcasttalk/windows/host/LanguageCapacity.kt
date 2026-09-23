package app.mcasttalk.windows.host

import java.lang.management.ManagementFactory
import kotlin.math.ceil

/** Conservative admission estimate, not a hardware certification or a percentile benchmark.
 * One active speaker, three-second calibration clips, 20% headroom and 800 ms VAD/transport reserve.
 * MT/TTS are deliberately summed even though the implementation can overlap them.
 */
internal class LanguageCapacity(
    private val languages: Set<String> = setOf("ko", "en", "ja", "zh-CN"),
    private val availableMemory: () -> Long? = {
    (ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)?.freeMemorySize
}) {
    init { require(languages.isNotEmpty()) }
    private var asrMs = 0
    private var mt = emptyMap<String,Int>()
    private var tts = emptyMap<String,Int>()
    private val observations = ArrayDeque<Triple<Int,Map<String,Int>,Map<String,Int>>>()
    private var calibratedBackend: String? = null
    private var samples = 0
    private var selected = emptySet<String>() // empty = automatic, not all simultaneously admitted
    private var allowSlow = false
    // Administrative language slots are not a promise that every translation
    // combination fits the machine. Actual demand is checked before admission.
    private var configuredLimit = 0 // automatic: up to five installed languages
    private var lastLatency: Int? = null
    private var invalidation: String? = null
    val targetMs = 5_000

    @Synchronized fun calibrate(result: FlatJsonObject) {
        val count=result.requiredInt("calibrationSamples")
        val measuredAsr=result.requiredInt("calibrationAsrMs")
        val measuredMt=languages.associateWith { result.requiredInt("calibrationMtMs_$it") }
        val measuredTts=languages.associateWith { result.requiredInt("calibrationTtsMs_$it") }
        require(count >= languages.size && (measuredMt.values+measuredTts.values+measuredAsr).all { it in 1..180_000 }) { "Incomplete calibration" }
        asrMs=measuredAsr;mt=measuredMt;tts=measuredTts;samples=count
        calibratedBackend=result.requiredString("backend");observations.clear();invalidation=null
    }

    @Synchronized fun observe(result: FlatJsonObject) {
        lastLatency=result.requiredInt("elapsedMs")
        if (samples == 0) return
        if (result.optionalString("backend") != calibratedBackend) {
            // Never keep a GPU recommendation after CPU fallback.
            samples=0;allowSlow=false;invalidation="backend-changed-restart-required";return
        }
        fun timing(key:String)=runCatching { result.requiredInt(key) }.getOrNull()?.takeIf { it in 1..180_000 }
        val measuredAsr=timing("asrMs") ?: return
        observations.addLast(Triple(measuredAsr,languages.mapNotNull { l->timing("translationMs_$l")?.let { l to it } }.toMap(),
            languages.mapNotNull { l->timing("ttsMs_$l")?.let { l to it } }.toMap()))
        if(observations.size>8)observations.removeFirst()
    }

    private fun lowMemory() = availableMemory()?.let { it < 512L*1024*1024 } == true
    private fun measuredAsr() = maxOf(asrMs,observations.maxOfOrNull { it.first } ?: 0)
    private fun mtCost(language:String)=maxOf(mt[language] ?: 0,observations.maxOfOrNull { it.second[language] ?: 0 } ?: 0)
    private fun ttsCost(language:String)=maxOf(tts[language] ?: 0,observations.maxOfOrNull { it.third[language] ?: 0 } ?: 0)
    private fun estimate(translations:Set<String>,audio:Set<String>,durationMs:Int=3_000):Int =
        800+ceil(1.2*(measuredAsr()*maxOf(1.0,durationMs/3000.0)+translations.sumOf(::mtCost)+audio.sumOf(::ttsCost))).toInt()
    private fun recommended():Int {
        if(samples==0 || lowMemory())return 0
        val ordered=languages.sortedByDescending { mtCost(it)+ttsCost(it) }
        return (1 until languages.size).lastOrNull { estimate(ordered.take(it).toSet(),ordered.take(it).toSet())<=targetMs } ?: 0
    }
    private fun meetingLimit()=if(configuredLimit>0)configuredLimit else minOf(5,languages.size)

    @Synchronized fun configure(languageSelection:Set<String>, overrideRecommended:Boolean, acknowledged:Boolean,
        meetingLanguageLimit:Int=0, activeRooms:List<List<Participant>> = emptyList()) {
        require(languageSelection.all { it in languages }) { "지원하지 않는 통역 언어입니다." }
        require(meetingLanguageLimit in 0..languages.size) { "설치 모델이 지원하는 언어 수를 초과했습니다." }
        require(!overrideRecommended || acknowledged) { "권장 초과 시 음성 지연·품질 저하 가능성을 확인해야 합니다." }
        val limit=if(meetingLanguageLimit==0)minOf(5,languages.size)else meetingLanguageLimit
        require(languageSelection.size<=limit) { "선택한 언어가 설정 개수를 넘습니다. 설정 단계에서 언어 수를 줄여 주세요." }
        val oldSelection=selected;val oldMode=allowSlow;val oldLimit=configuredLimit
        selected=languageSelection.toSet();allowSlow=overrideRecommended;configuredLimit=meetingLanguageLimit
        try { activeRooms.forEach(::validatePlan) }
        catch(error:Exception){selected=oldSelection;allowSlow=oldMode;configuredLimit=oldLimit;throw error}
    }

    /** Called under the membership lock BEFORE join/update. A failed proposal changes nothing. */
    @Synchronized fun validatePlan(people:List<Participant>) {
        if(people.isEmpty())return
        val planLanguages=people.flatMap { listOf(it.preferences.inputLanguage,it.preferences.publishLanguage,it.preferences.listenLanguage,it.preferences.displayLanguage) }.toSet()
        require(planLanguages.all { it in languages }) { "설치 모델이 지원하지 않는 회의 언어입니다." }
        require(selected.isEmpty() || selected.containsAll(planLanguages)) { "운영자가 선택한 회의 언어 범위를 벗어납니다. 참가/언어 설정 전에 허용 언어를 확인하세요." }
        require(planLanguages.size<=meetingLimit()) { "언어 설정 불가: 현재 회의 ${planLanguages.size}개 언어가 설정 한도 ${meetingLimit()}개를 넘습니다. 운영 설정에서 허용 언어 개수를 확인하세요. 기존 참가자의 통역은 유지됩니다." }
        // Allow original-only meetings while models warm up. Mixed-language
        // admission cannot rely on an unmeasured recommendation.
        for(speaker in people.filter { it.role!=ParticipantRole.LISTENER }) {
            val source=speaker.preferences.inputLanguage
            val audio=people.filter { it.id!=speaker.id && it.preferences.wantsTranslatedAudio(source) }.map { it.preferences.listenLanguage }.toSet()
            val targets=(people.filterNot { it.preferences.understands(source) }.map { it.preferences.displayLanguage }+audio+speaker.preferences.publishLanguage).toSet()-source
            if(targets.isNotEmpty()) {
                require(samples>0) { "언어 설정 전 모델 성능 측정을 완료해야 합니다." }
                require(allowSlow || (!lowMemory() && estimate(targets,audio)<=targetMs)) { "언어 설정 불가: 예상 통역 부하가 목표 지연을 넘습니다. 언어 개수/청취·자막 조합을 줄이거나 추가 원음 유지 언어를 설정하세요. 기존 설정은 유지됩니다." }
            }
        }
    }

    @Synchronized fun admit(translations:Set<String>,audio:Set<String>,durationMs:Int,busy:Boolean) {
        require((translations+audio).all { it in languages } && translations.containsAll(audio)) { "지원하지 않는 통역 언어입니다." }
        require(samples>0) { "자원 성능 측정이 완료되지 않았습니다. 준비 상태를 확인하세요. 실행 장치가 변경됐다면 서버를 다시 시작하세요." }
        if(!allowSlow) {
            require(!busy) { "앞선 통역이 처리 중입니다. 지연 누적을 막기 위해 잠시 후 다시 말해 주세요." }
            // Language capacity is checked at settings admission, never by
            // pruning recipients or rejecting already admitted language sets.
        }
    }

    @Synchronized fun snapshot():Map<String,Any?> = linkedMapOf(
        "calibrationStatus" to (invalidation ?: if(samples>0) "measured" else "pending"),
        "calibrationSamples" to samples,"sampleDurationMs" to 3000,"targetLatencyMs" to targetMs,
        "measurementBasis" to "synthetic-short-clips-not-a-guarantee", "backend" to calibratedBackend,
        "supportedLanguages" to languages.toList(),"recommendedInterpretedLanguages" to recommended(),
        "recommendedMeetingLanguages" to (recommended()+1),"selectedLanguages" to selected.toList(),
        "configuredMeetingLanguageLimit" to configuredLimit,"effectiveMeetingLanguageLimit" to meetingLimit(),
        "selectionRequiresWorkloadValidation" to true,
        "originalCaptionEstimatedMs" to if(samples>0)estimate(emptySet(),emptySet())else null,
        "originalCaptionOverTarget" to if(samples>0)(estimate(emptySet(),emptySet())>targetMs)else null,
        "countOptions" to ((1..minOf(4,languages.size)).toList()+listOf(5,7,10)+languages.size).distinct().sorted().map { count ->
            mapOf("count" to count,"modelSupported" to (count<=languages.size),"recommended" to (samples>0 && count<=recommended()+1)) },
        "mode" to if(allowSlow) "allow-delay" else "quality-first", "settingsLifetime" to "server-session",
        "asrMs" to measuredAsr().takeIf { samples>0 },
        "languageCosts" to languages.associateWith { mapOf("translationMs" to mtCost(it),"speechMs" to ttsCost(it),
            "estimatedOneLanguageMs" to if(samples>0)estimate(setOf(it),setOf(it))else null) },
        "availableMemoryBytes" to availableMemory(),"logicalProcessors" to Runtime.getRuntime().availableProcessors(),
        "memoryPressure" to lowMemory(),"lastRequestLatencyMs" to lastLatency,
        "lastRequestOverTarget" to lastLatency?.let { it>targetMs },
        "recentObservationCount" to observations.size,
    )
}
