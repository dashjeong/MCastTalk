package app.guidecast.transmitter

import app.guidecast.provider.gemma.translation.GemmaModelStatus
import app.guidecast.provider.gemma.translation.GemmaModelVariant

/** Catalog descriptors contain URLs and user-visible labels; never serialize their toString. */
internal fun gemmaModelDiagnostic(status: GemmaModelStatus): String {
    val model = if (status.variant in GemmaModelVariant.BUILTIN_VARIANTS) status.variant.id else "custom"
    return "$model:${status.readiness}:${status.downloadedBytes / 1048576}MiB:" +
        "error=${status.errorMessage != null}"
}
