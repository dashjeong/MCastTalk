package app.guidecast.transmitter

import kotlinx.coroutines.CancellationException
import app.guidecast.core.stream.AudioChannelDescriptor
import app.guidecast.core.stream.MAX_SIMULTANEOUS_TRANSLATED_CHANNELS

/** Automatic use must never enter the asset downloader or its repair/delete path. */
internal suspend fun prepareSpeechAssetsForUse(
    allowAssetDownloads: Boolean,
    installed: () -> Boolean,
    prepareAssets: suspend () -> Unit,
) {
    if (allowAssetDownloads) prepareAssets()
    else check(installed()) { "설치된 음성이 없습니다. 음성 준비에서 설치 상태를 확인하세요." }
}

/** Retain native resources across caption gaps, then release before stored replay waits. */
internal suspend fun <T> withMenuSpeechContentLease(
    acquire: suspend () -> AutoCloseable?,
    isCurrent: () -> Boolean,
    releasePreparation: () -> Unit,
    content: suspend () -> T,
): T {
    if (!isCurrent()) throw CancellationException("방송 준비가 종료됐습니다.")
    val lease = acquire() ?: throw CancellationException("방송 준비가 종료됐습니다.")
    try {
        if (!isCurrent()) throw CancellationException("방송 준비가 종료됐습니다.")
        return content()
    } finally {
        try { releasePreparation() } finally { lease.close() }
    }
}

/** Original PCM and stored-history playback never require a synthesizer. */
internal fun menuSpeechLanguageTags(
    channels: List<AudioChannelDescriptor>, needsSynthesis: Boolean,
): Set<String> = if (!needsSynthesis) emptySet() else channels.asSequence()
    .filter { it.id != "source" }.map { it.languageTag }.distinct()
    .take(MAX_SIMULTANEOUS_TRANSLATED_CHANNELS).toSet()

internal enum class MenuSpeechContentKind { SAVED_MEDIA, LIVE_NOTE, HISTORY }

/** Live capture already has a 15-second server-readiness contract before opening its microphone. */
internal suspend fun <T> prepareMenuSpeechVoices(
    kind: MenuSpeechContentKind,
    prepareInstalled: suspend () -> T,
): T? = if (kind == MenuSpeechContentKind.SAVED_MEDIA) prepareInstalled() else null
