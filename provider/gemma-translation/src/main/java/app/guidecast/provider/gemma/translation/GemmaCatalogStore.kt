package app.guidecast.provider.gemma.translation

import android.content.Context
import android.content.pm.PackageManager
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.cert.CertificateFactory

/** Signed, offline catalog only. No repository credentials, remote polling or imported trust keys. */
class GemmaCatalogStore(context: Context) {
    private val app = context.applicationContext
    private val file = AtomicFile(File(app.filesDir, "gemma-catalog.signed"))
    private val publicKey by lazy {
        @Suppress("DEPRECATION")
        val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signers = requireNotNull(info.signingInfo).apkContentsSigners
        require(signers.size == 1) { "모델 목록 서명 인증서를 확인할 수 없습니다." }
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(signers.single().toByteArray())).publicKey
    }

    fun entries(): List<GemmaModelVariant> = synchronized(lock) {
        GemmaModelVariant.entries + loadModels()
    }

    fun resolve(id: String): GemmaModelVariant = entries().firstOrNull { it.id == id }
        ?: error("검증된 모델 목록에 없는 모델입니다: $id")

    /** Caller executes on IO dispatcher. Atomic replacement retains the previous valid catalog. */
    fun importSigned(input: InputStream): List<GemmaModelVariant> = synchronized(lock) {
        val bytes = readBounded(input)
        val next = GemmaSignedCatalog.decode(bytes, publicKey)
        if (hasCatalog()) {
            val previous = file.openRead().use { GemmaSignedCatalog.decode(readBounded(it), publicKey) }
            require(next.version > previous.version) { "이미 적용했거나 이전 버전의 모델 목록입니다." }
            // IDs identify immutable artifacts in IPC, verified markers and native caches.
            previous.models.forEach { existing ->
                require(next.models.firstOrNull { it.id == existing.id } == existing) {
                    "기존 모델 정의는 변경·삭제할 수 없습니다. 새 모델 ID로 등록하세요."
                }
            }
        }
        var output: java.io.FileOutputStream? = null
        try {
            output = file.startWrite()
            output.write(bytes)
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
        GemmaModelVariant.entries + next.models
    }

    private fun loadModels(): List<GemmaModelVariant> {
        if (!hasCatalog()) return emptyList()
        return file.openRead().use { GemmaSignedCatalog.decode(readBounded(it), publicKey).models }
    }

    private fun hasCatalog(): Boolean = file.baseFile.exists() ||
        File(file.baseFile.path + ".bak").exists()

    companion object {
        private val lock = Any()
        private const val MAX_BYTES = 65_536
        private fun readBounded(input: InputStream): ByteArray {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4_096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_BYTES) { "모델 목록 파일이 너무 큽니다." }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }
}
