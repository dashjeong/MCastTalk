package app.guidecast.core.audio

/** Request acceptance is not evidence that an OEM separated one speaker acoustically. */
enum class MicrophoneFocusStatus(val summary: String?) {
    OFF(null),
    ACCEPTED("가까운 화자 · 방향/집중 요청 수락 (화자 분리 효과는 환경에 따라 다름)"),
    PARTIAL("가까운 화자 · 방향/집중 중 일부만 요청 수락"),
    UNSUPPORTED("가까운 화자 · 단말이 방향/집중 요청을 지원하지 않아 기본 입력 유지"),
    EXTERNAL("가까운 화자 · 외부 마이크를 화자 가까이에 두세요"),
    UNKNOWN("가까운 화자 · 마이크 경로를 확인할 수 없어 기본 입력 유지"),
}

/** Never gate/drop PCM by loudness: distant loud speech and quiet target speech can overlap. */
fun requestNearSpeakerFocus(
    enabled: Boolean,
    builtInMicrophone: Boolean?,
    requestDirection: () -> Boolean,
    requestField: () -> Boolean,
): MicrophoneFocusStatus {
    if (!enabled) return MicrophoneFocusStatus.OFF
    if (builtInMicrophone == null) return MicrophoneFocusStatus.UNKNOWN
    if (!builtInMicrophone) return MicrophoneFocusStatus.EXTERNAL
    val direction = try { requestDirection() } catch (_: Exception) { false }
    val field = try { requestField() } catch (_: Exception) { false }
    return when {
        direction && field -> MicrophoneFocusStatus.ACCEPTED
        direction || field -> MicrophoneFocusStatus.PARTIAL
        else -> MicrophoneFocusStatus.UNSUPPORTED
    }
}
