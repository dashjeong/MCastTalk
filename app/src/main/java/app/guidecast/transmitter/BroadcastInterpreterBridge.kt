package app.guidecast.transmitter

import app.guidecast.core.server.InterpreterReferences
import app.guidecast.core.server.InterpreterSessions
import app.guidecast.core.server.InterpreterSupport
import app.guidecast.core.stream.StreamSession
import app.guidecast.core.stream.ChannelAudioPublicationCoordinator
import app.guidecast.core.stream.PcmAudioFrame
import app.guidecast.core.translation.HumanInterpretationFloor

/** 한 방송 세대의 통역사 입력과 AI 문장 발언권을 연결한다. */
internal class BroadcastInterpreterBridge(private val app: GuideCastApplication, val stream: StreamSession,
    private val publication: ChannelAudioPublicationCoordinator) {
    val floor = HumanInterpretationFloor()
    private fun state(channel: String): String = floor.state(channel).also { value ->
        if (stream.isActive()) app.broadcastRuntime.update { current ->
            if (current.interpreterStates[channel] == value) current
            else current.copy(interpreterStates = current.interpreterStates + (channel to value))
        }
    }
    val support = InterpreterSupport(
        sessions = InterpreterSessions(stream.channels.filter { it.id != "source" }.map { it.id }.toSet()),
        state = ::state,
        connected = { floor.connected(it); state(it) },
        disconnected = { floor.disconnected(it); state(it) },
        pause = { floor.pause(it); state(it) },
        frame = { channel, frame, audible ->
            publishInterpreterFrame(floor, stream, publication, channel, frame, audible).also { state(channel) }
        },
        references = { channel, text ->
            val briefing = app.interpretationBriefingSettings.state.value.bounded()
            val readable = listOf("주제" to briefing.topic, "장소·상황" to briefing.situation,
                "화자" to briefing.speaker, "청중" to briefing.audience,
                "말투" to briefing.formality.label, "꼭 지킬 용어" to briefing.notes)
                .filter { it.second.isNotBlank() }.joinToString("\n") { "${it.first}: ${it.second}" }
            val target = stream.descriptor(channel)?.languageTag.orEmpty()
            val source = stream.descriptor("source")?.languageTag.orEmpty()
            val terms = app.glossary.matching(text, source, target).take(12).map { it.sourceTerm to it.preferredTerm }
            InterpreterReferences(readable, terms)
        },
    )
}

internal fun publishInterpreterFrame(floor: HumanInterpretationFloor, stream: StreamSession,
    publication: ChannelAudioPublicationCoordinator, channel: String, frame: PcmAudioFrame, audible: Boolean): Boolean {
    // 먼저 사람의 발화 요청을 기록해야 AI가 읽던 문장 뒤의 발언권을 넘길 수 있다.
    return floor.humanFrame(channel, audible, frame.bytes.size, requireNotNull(stream.descriptor(channel)).sampleRateHz) {
        val lease = publication.tryAcquireChannel(channel)
        if (lease == null) false else lease.use { stream.tryPublish(channel, frame).accepted }
    }
}
