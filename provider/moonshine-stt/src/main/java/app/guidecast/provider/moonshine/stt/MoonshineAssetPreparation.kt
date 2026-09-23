package app.guidecast.provider.moonshine.stt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/** AssetDownloader checks thread interruption; ordinary withContext does not deliver it. */
internal suspend fun <T> runMoonshineAssetPreparation(block: () -> T): T =
    runInterruptible(Dispatchers.IO, block)
