package app.guidecast.transmitter

import android.content.Context
import app.guidecast.core.translation.BriefingFormality
import app.guidecast.core.translation.InterpretationBriefing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 행사 참고 정보만 저장한다. 서비스 이용 동의나 비밀값을 포함하지 않는다. */
class InterpretationBriefingSettings internal constructor(initial: InterpretationBriefing,
    private val persist: (InterpretationBriefing) -> Unit) {
    private val mutableState = MutableStateFlow(initial.bounded())
    val state = mutableState.asStateFlow()
    constructor(context: Context) : this(read(context), { value ->
        context.getSharedPreferences("interpretation_briefing", Context.MODE_PRIVATE).edit()
            .putString("topic", value.topic).putString("situation", value.situation).putString("speaker", value.speaker)
            .putString("audience", value.audience).putString("formality", value.formality.name).putString("notes", value.notes)
            .putString("terms", value.terms).putBoolean("configured", value.configured).apply()
    })
    @Synchronized fun save(value: InterpretationBriefing) {
        val bounded = value.bounded().copy(configured = true)
        persist(bounded)
        mutableState.value = bounded
    }
    private companion object {
        fun read(context: Context): InterpretationBriefing {
            val prefs = context.getSharedPreferences("interpretation_briefing", Context.MODE_PRIVATE)
            return InterpretationBriefing(prefs.getString("topic", "").orEmpty(), prefs.getString("situation", "").orEmpty(),
                prefs.getString("speaker", "").orEmpty(), prefs.getString("audience", "").orEmpty(),
                runCatching { BriefingFormality.valueOf(prefs.getString("formality", "FORMAL").orEmpty()) }.getOrDefault(BriefingFormality.FORMAL),
                prefs.getString("notes", "").orEmpty(), configured = prefs.getBoolean("configured", false),
                terms = prefs.getString("terms", "").orEmpty()).bounded()
        }
    }
}
