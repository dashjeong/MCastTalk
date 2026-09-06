package app.guidecast.provider.gemma.translation

import java.security.KeyPair
import java.security.KeyPairGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class GemmaSignedCatalogTest {

    companion object {
        private lateinit var keyPair: KeyPair
        private lateinit var attackerKeyPair: KeyPair

        @BeforeClass
        @JvmStatic
        fun generateKeys() {
            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(2048)
            keyPair = generator.generateKeyPair()
            attackerKeyPair = generator.generateKeyPair()
        }

        private fun createSampleModel(
            id: String = "e2b_ko_en_extended",
            label: String = "E2B 확장 번역 모델",
            fileName: String = "gemma-4-E2B-it-ext.litertlm",
            revision: String = "1234567890abcdef1234567890abcdef12345678",
            sizeBytes: Long = 2_100_000_000L,
            sha256: String = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            gpuOnly: Boolean = false,
            minSdk: Int = 29,
            runtimeContract: String = "gemma4-translator-v1",
            repositoryUrl: String = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm",
        ) = GemmaModelVariant(
            id = id,
            label = label,
            fileName = fileName,
            revision = revision,
            sizeBytes = sizeBytes,
            sha256 = sha256,
            gpuOnly = gpuOnly,
            minSdk = minSdk,
            runtimeContract = runtimeContract,
            repositoryUrl = repositoryUrl,
            isBuiltin = false,
        )
    }

    @Test
    fun `decodes valid signed catalog and matches all fields`() {
        val model = createSampleModel()
        val encoded = GemmaSignedCatalog.encode(
            version = 1,
            models = listOf(model),
            privateKey = keyPair.private,
        )

        val catalog = GemmaSignedCatalog.decode(encoded, keyPair.public)

        assertEquals(1, catalog.version)
        assertEquals(1, catalog.models.size)
        val decoded = catalog.models[0]
        assertEquals("e2b_ko_en_extended", decoded.id)
        assertEquals("E2B 확장 번역 모델", decoded.label)
        assertEquals("gemma-4-E2B-it-ext.litertlm", decoded.fileName)
        assertEquals(2_100_000_000L, decoded.sizeBytes)
        assertEquals("cache-e2b_ko_en_extended", decoded.cacheDirectoryName)
        assertEquals(
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/1234567890abcdef1234567890abcdef12345678/gemma-4-E2B-it-ext.litertlm?download=true",
            decoded.downloadUrl,
        )
        assertFalse(decoded.gpuOnly)
        assertFalse(decoded.isBuiltin)
    }

    @Test(expected = SecurityException::class)
    fun `rejects tampered payload content`() {
        val model = createSampleModel()
        val encoded = GemmaSignedCatalog.encode(
            version = 1,
            models = listOf(model),
            privateKey = keyPair.private,
        )

        // Keep Base64 valid so this exercises signature verification, not syntax rejection.
        val lines = encoded.toString(Charsets.UTF_8).lines().toMutableList()
        val payload = java.util.Base64.getDecoder().decode(lines[1])
        payload[0] = (payload[0].toInt() xor 1).toByte()
        lines[1] = java.util.Base64.getEncoder().encodeToString(payload)
        val tamperedBytes = lines.joinToString("\n").toByteArray(Charsets.UTF_8)

        GemmaSignedCatalog.decode(tamperedBytes, keyPair.public)
    }

    @Test(expected = SecurityException::class)
    fun `rejects catalog signed with wrong key`() {
        val model = createSampleModel()
        val encoded = GemmaSignedCatalog.encode(
            version = 1,
            models = listOf(model),
            privateKey = attackerKeyPair.private, // Attacker key
        )

        GemmaSignedCatalog.decode(encoded, keyPair.public) // Trusted public key
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects path traversal in fileName`() {
        val model = createSampleModel(fileName = "../malicious.litertlm")
        val encoded = GemmaSignedCatalog.encode(
            version = 1,
            models = listOf(model),
            privateKey = keyPair.private,
        )
        GemmaSignedCatalog.decode(encoded, keyPair.public)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects absolute path in fileName`() {
        val model = createSampleModel(fileName = "/data/local/tmp/malicious.litertlm")
        val encoded = GemmaSignedCatalog.encode(
            version = 1,
            models = listOf(model),
            privateKey = keyPair.private,
        )
        GemmaSignedCatalog.decode(encoded, keyPair.public)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects unknown runtime contract`() {
        val model = createSampleModel(runtimeContract = "arbitrary-v2")
        val encoded = GemmaSignedCatalog.encode(
            version = 1,
            models = listOf(model),
            privateKey = keyPair.private,
        )
        GemmaSignedCatalog.decode(encoded, keyPair.public)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects duplicate model id within catalog`() {
        val model1 = createSampleModel(id = "shared_id", fileName = "model1.litertlm")
        val model2 = createSampleModel(id = "shared_id", fileName = "model2.litertlm")
        val encoded = GemmaSignedCatalog.encode(
            version = 1,
            models = listOf(model1, model2),
            privateKey = keyPair.private,
        )
        GemmaSignedCatalog.decode(encoded, keyPair.public)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects duplicate fileName within catalog`() {
        val model1 = createSampleModel(id = "model_1", fileName = "shared.litertlm")
        val model2 = createSampleModel(id = "model_2", fileName = "shared.litertlm")
        val encoded = GemmaSignedCatalog.encode(
            version = 1,
            models = listOf(model1, model2),
            privateKey = keyPair.private,
        )
        GemmaSignedCatalog.decode(encoded, keyPair.public)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects collision with builtin standard id`() {
        val model = createSampleModel(id = "standard", fileName = "new_standard.litertlm")
        val encoded = GemmaSignedCatalog.encode(
            version = 1,
            models = listOf(model),
            privateKey = keyPair.private,
        )
        GemmaSignedCatalog.decode(encoded, keyPair.public)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects collision with builtin standard fileName`() {
        val model = createSampleModel(id = "my_custom_model", fileName = GemmaModelVariant.STANDARD.fileName)
        val encoded = GemmaSignedCatalog.encode(
            version = 1,
            models = listOf(model),
            privateKey = keyPair.private,
        )
        GemmaSignedCatalog.decode(encoded, keyPair.public)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects invalid non-https or non-huggingface repositoryUrl`() {
        val model = createSampleModel(repositoryUrl = "http://insecure-server.com/repo")
        val encoded = GemmaSignedCatalog.encode(
            version = 1,
            models = listOf(model),
            privateKey = keyPair.private,
        )
        GemmaSignedCatalog.decode(encoded, keyPair.public)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects invalid revision hex length`() {
        val model = createSampleModel(revision = "12345") // Not 40 hex chars
        val encoded = GemmaSignedCatalog.encode(
            version = 1,
            models = listOf(model),
            privateKey = keyPair.private,
        )
        GemmaSignedCatalog.decode(encoded, keyPair.public)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects invalid sha256 hex length`() {
        val model = createSampleModel(sha256 = "deadbeef") // Not 64 hex chars
        val encoded = GemmaSignedCatalog.encode(
            version = 1,
            models = listOf(model),
            privateKey = keyPair.private,
        )
        GemmaSignedCatalog.decode(encoded, keyPair.public)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects file size exceeding 64KiB`() {
        val oversizedBytes = ByteArray(64 * 1024 + 1)
        GemmaSignedCatalog.decode(oversizedBytes, keyPair.public)
    }
}
