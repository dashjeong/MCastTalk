package app.guidecast.provider.gemma.translation

import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.util.Base64
import java.util.Locale

/**
 * Validated, cryptographically signed catalog of Gemma models.
 *
 * File format:
 * ```
 * GUIDECAST-CATALOG-1
 * <base64 payload>
 * <base64 signature>
 * ```
 *
 * Payload format:
 * - Line 1: positive integer catalog version
 * - Subsequent lines: TSV with exactly 10 fields per model:
 *   `id\tlabel\tfileName\trevision\tsizeBytes\tsha256\tgpuOnly\tminSdk\truntimeContract\trepositoryUrl`
 */
data class GemmaSignedCatalog(
    val version: Int,
    val models: List<GemmaModelVariant>,
) {
    companion object {
        const val MAGIC_HEADER = "GUIDECAST-CATALOG-1"
        const val MAX_FILE_SIZE_BYTES = 64 * 1024 // 64 KiB
        const val MAX_MODELS = 32
        const val REQUIRED_RUNTIME_CONTRACT = "gemma4-translator-v1"
        const val MAX_MODEL_SIZE_BYTES = 16L * 1024 * 1024 * 1024 // 16 GiB
        private const val SIGNATURE_ALGORITHM = "SHA256withRSA"

        private val SAFE_ID_REGEX = Regex("^[a-zA-Z0-9_-]{1,64}$")
        private val SAFE_FILE_NAME_REGEX = Regex("^[a-zA-Z0-9._-]+\\.litertlm$")
        private val HEX_40_REGEX = Regex("^[0-9a-fA-F]{40}$")
        private val HEX_64_REGEX = Regex("^[0-9a-fA-F]{64}$")
        private val HF_REPO_URL_REGEX = Regex("^https://huggingface\\.co/[a-zA-Z0-9._-]+/[a-zA-Z0-9._-]+$")

        /**
         * Decodes and cryptographically verifies a signed catalog file.
         *
         * @throws IllegalArgumentException if the format, headers, fields, or constraints are invalid
         * @throws SecurityException if the cryptographic signature is invalid or tampered with
         */
        fun decode(bytes: ByteArray, publicKey: PublicKey): GemmaSignedCatalog {
            require(bytes.size <= MAX_FILE_SIZE_BYTES) {
                "카탈로그 파일 크기가 허용치(64KiB)를 초과했습니다: ${bytes.size} bytes"
            }
            require(bytes.isNotEmpty()) { "카탈로그 파일이 비어 있습니다." }

            val text = bytes.toString(Charsets.UTF_8)
            val lines = text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()

            require(lines.size == 3) {
                "카탈로그 파일 구조가 올바르지 않습니다 (헤더, 페이로드, 서명 3개 항목 필요)."
            }
            require(lines[0] == MAGIC_HEADER) {
                "알 수 없는 카탈로그 매직 헤더입니다: ${lines[0]}"
            }

            val payloadBytes = runCatching {
                Base64.getDecoder().decode(lines[1])
            }.getOrElse { error ->
                throw IllegalArgumentException("페이로드 Base64 디코딩에 실패했습니다.", error)
            }

            val signatureBytes = runCatching {
                Base64.getDecoder().decode(lines[2])
            }.getOrElse { error ->
                throw IllegalArgumentException("서명 Base64 디코딩에 실패했습니다.", error)
            }

            // Cryptographic verification
            val verifier = Signature.getInstance(SIGNATURE_ALGORITHM)
            verifier.initVerify(publicKey)
            verifier.update(payloadBytes)
            if (!verifier.verify(signatureBytes)) {
                throw SecurityException("카탈로그 디지털 서명 검증에 실패했습니다. (위조 또는 변조된 카탈로그)")
            }

            // Parse payload TSV
            val payloadText = payloadBytes.toString(Charsets.UTF_8)
            val payloadLines = payloadText.lineSequence()
                .map { it.trimEnd('\r', '\n') }
                .filter { it.isNotEmpty() }
                .toList()

            require(payloadLines.isNotEmpty()) { "카탈로그 페이로드가 비어 있습니다." }

            val version = requireNotNull(payloadLines[0].toIntOrNull()?.takeIf { it > 0 }) {
                "카탈로그 버전은 양의 정수여야 합니다: ${payloadLines[0]}"
            }

            val modelLines = payloadLines.drop(1)
            require(modelLines.size <= MAX_MODELS) {
                "카탈로그 모델 개수는 최대 $MAX_MODELS 개 이하여야 합니다 (현재: ${modelLines.size})."
            }

            val models = modelLines.mapIndexed { index, line ->
                parseModelRow(line, index + 1)
            }

            // Duplicate validation across catalog
            val ids = mutableSetOf<String>()
            val fileNames = mutableSetOf<String>()
            for (model in models) {
                require(ids.add(model.id)) {
                    "카탈로그 내 중복된 모델 ID가 존재합니다: ${model.id}"
                }
                require(fileNames.add(model.fileName)) {
                    "카탈로그 내 중복된 파일명이 존재합니다: ${model.fileName}"
                }
            }

            return GemmaSignedCatalog(version = version, models = models)
        }

        private fun parseModelRow(line: String, rowNumber: Int): GemmaModelVariant {
            val fields = line.split('\t')
            require(fields.size == 10) {
                "행 $rowNumber: 필드 수가 10개가 아닙니다 (실제: ${fields.size})"
            }

            val id = fields[0].trim()
            require(id.matches(SAFE_ID_REGEX)) {
                "행 $rowNumber: ID는 1~64자의 안전한 ASCII여야 합니다: $id"
            }
            require(id != GemmaModelVariant.STANDARD_ID && id != GemmaModelVariant.GPU_OPTIMIZED_ID) {
                "행 $rowNumber: 기본 제공 모델 ID '$id'는 덮어쓸 수 없습니다."
            }

            val label = fields[1].trim()
            require(label.isNotEmpty() && label.length <= 64) {
                "행 $rowNumber: 라벨은 비어있지 않고 64자 이하여야 합니다."
            }

            val fileName = fields[2].trim()
            require(fileName.matches(SAFE_FILE_NAME_REGEX)) {
                "행 $rowNumber: 파일명은 .litertlm 확장자의 안전한 ASCII여야 합니다: $fileName"
            }
            require(!fileName.contains('/') && !fileName.contains('\\') && !fileName.contains("..")) {
                "행 $rowNumber: 파일명에 경로 순회 문자가 포함될 수 없습니다: $fileName"
            }
            require(fileName != GemmaModelVariant.STANDARD.fileName && fileName != GemmaModelVariant.GPU_OPTIMIZED.fileName) {
                "행 $rowNumber: 기본 제공 파일명 '$fileName'은 덮어쓸 수 없습니다."
            }

            val revision = fields[3].trim()
            require(revision.matches(HEX_40_REGEX)) {
                "행 $rowNumber: revision은 40자리 16진수여야 합니다: $revision"
            }

            val sizeBytes = requireNotNull(fields[4].trim().toLongOrNull()?.takeIf { it in 1..MAX_MODEL_SIZE_BYTES }) {
                "행 $rowNumber: sizeBytes는 0보다 크고 16GiB 이하여야 합니다: ${fields[4]}"
            }

            val sha256 = fields[5].trim().lowercase(Locale.ROOT)
            require(sha256.matches(HEX_64_REGEX)) {
                "행 $rowNumber: sha256은 64자리 16진수여야 합니다: $sha256"
            }

            val gpuOnly = when (fields[6].trim()) {
                "true" -> true
                "false" -> false
                else -> throw IllegalArgumentException("행 $rowNumber: gpuOnly는 'true' 또는 'false'여야 합니다: ${fields[6]}")
            }

            val minSdk = requireNotNull(fields[7].trim().toIntOrNull()?.takeIf { it >= 29 }) {
                "행 $rowNumber: minSdk는 29 이상의 정수여야 합니다: ${fields[7]}"
            }

            val runtimeContract = fields[8].trim()
            require(runtimeContract == REQUIRED_RUNTIME_CONTRACT) {
                "행 $rowNumber: 지원하지 않는 runtimeContract입니다: $runtimeContract (필수: $REQUIRED_RUNTIME_CONTRACT)"
            }

            val repositoryUrl = fields[9].trim()
            require(repositoryUrl.matches(HF_REPO_URL_REGEX)) {
                "행 $rowNumber: repositoryUrl은 HTTPS HuggingFace 리포지토리 네임스페이스 경로여야 합니다: $repositoryUrl"
            }

            return GemmaModelVariant(
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

        /**
         * Encodes and cryptographically signs a catalog.
         */
        fun encode(version: Int, models: List<GemmaModelVariant>, privateKey: PrivateKey): ByteArray {
            require(version > 0) { "카탈로그 버전은 양의 정수여야 합니다: $version" }
            require(models.size <= MAX_MODELS) { "모델 개수는 최대 $MAX_MODELS 개 이하여야 합니다." }

            val payloadBuilder = StringBuilder()
            payloadBuilder.append(version).append('\n')
            for (model in models) {
                payloadBuilder.append(model.id).append('\t')
                    .append(model.label).append('\t')
                    .append(model.fileName).append('\t')
                    .append(model.revision).append('\t')
                    .append(model.sizeBytes).append('\t')
                    .append(model.sha256).append('\t')
                    .append(model.gpuOnly).append('\t')
                    .append(model.minSdk).append('\t')
                    .append(model.runtimeContract).append('\t')
                    .append(model.repositoryUrl).append('\n')
            }

            val payloadBytes = payloadBuilder.toString().toByteArray(Charsets.UTF_8)
            val signer = Signature.getInstance(SIGNATURE_ALGORITHM)
            signer.initSign(privateKey)
            signer.update(payloadBytes)
            val signatureBytes = signer.sign()

            val base64Payload = Base64.getEncoder().encodeToString(payloadBytes)
            val base64Signature = Base64.getEncoder().encodeToString(signatureBytes)

            val output = "$MAGIC_HEADER\n$base64Payload\n$base64Signature\n"
            val outputBytes = output.toByteArray(Charsets.UTF_8)
            require(outputBytes.size <= MAX_FILE_SIZE_BYTES) {
                "인코딩된 카탈로그가 64KiB를 초과했습니다: ${outputBytes.size} bytes"
            }
            return outputBytes
        }
    }
}
