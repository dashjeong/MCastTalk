package app.guidecast.provider.gemma.translation

import java.net.URI
import java.util.Locale

/** Pinned Hugging Face artifacts may redirect only into its HTTPS artifact/CDN namespaces. */
internal fun requireTrustedGemmaDownloadUrl(uri: URI) {
    val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
    val trustedHost = host == "huggingface.co" || host.endsWith(".huggingface.co") ||
        host == "hf.co" || host.endsWith(".hf.co")
    require(uri.scheme.equals("https", ignoreCase = true) && trustedHost &&
        uri.rawUserInfo == null && (uri.port == -1 || uri.port == 443)
    ) { "허용되지 않은 모델 다운로드 주소입니다. 공식 HTTPS 배포 경로를 확인하세요." }
}
