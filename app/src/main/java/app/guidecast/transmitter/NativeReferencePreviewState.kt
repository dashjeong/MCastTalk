package app.guidecast.transmitter

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class NativeReferencePreviewLoad(val references: NativeReferenceSnapshot, val failed: Boolean = false)

internal suspend fun refreshNativeReferencePreview(
    load: suspend () -> NativeReferenceSnapshot): NativeReferencePreviewLoad =
    try {
        val loaded = load()
        currentCoroutineContext().ensureActive()
        NativeReferencePreviewLoad(loaded)
    }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) {
        currentCoroutineContext().ensureActive()
        NativeReferencePreviewLoad(NativeReferenceSnapshot(), failed = true)
    }

internal fun nativeReferenceTransmissionPreview(candidate: NativeReferenceSnapshot, supported: Boolean,
    permission: Boolean): NativeReferenceSnapshot =
    if (supported && permission) candidate else candidate.copy(payload = "", includedEntries = 0)

internal fun nativeReferencePreviewNotice(candidate: NativeReferenceSnapshot, supported: Boolean,
    permission: Boolean): String =
    when {
        !supported -> "현재 모델은 참고 자료 전송을 지원하지 않습니다. 보관 자료는 이번 연결에 보내지 않습니다."
        !permission -> "참고 자료 전송을 허용하지 않았으므로 전달하지 않습니다."
        candidate.includedEntries == 0 -> "전송은 허용했지만 준비된 발췌가 없어 이번에는 자료를 보내지 않습니다."
        else -> "다음 연결을 시작할 때 이 발췌를 포함할 수 있습니다. 현재 AI 서비스에 전달되었다는 뜻은 아닙니다."
    }
