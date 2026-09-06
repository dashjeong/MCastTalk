package app.guidecast.provider.gemma.translation

import java.net.URI
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaDownloadUrlPolicyTest {
    @Test fun `official pinned models and signed CDN are accepted`() {
        GemmaModelVariant.entries.forEach { requireTrustedGemmaDownloadUrl(URI(it.downloadUrl)) }
        requireTrustedGemmaDownloadUrl(URI("https://cas-bridge.xethub.hf.co/x?Signature=test"))
        requireTrustedGemmaDownloadUrl(URI("https://cdn-lfs.huggingface.co:443/x"))
    }

    @Test fun `redirect cannot reach local services credentials or lookalike hosts`() {
        listOf(
            "http://huggingface.co/model", "https://127.0.0.1/model", "https://[::1]/model",
            "https://192.168.1.1/model", "https://localhost/model",
            "https://huggingface.co.evil.test/model", "https://evilhf.co/model",
            "https://user:pass@huggingface.co/model", "https://huggingface.co:8787/model",
            "file:///data/local/tmp/model", "https://huggingface.co./model",
        ).forEach { candidate ->
            assertTrue(candidate, runCatching { requireTrustedGemmaDownloadUrl(URI(candidate)) }.isFailure)
        }
    }
}
