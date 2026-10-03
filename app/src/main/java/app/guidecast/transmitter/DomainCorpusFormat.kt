package app.guidecast.transmitter

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.text.Normalizer

object DomainCorpusFormat {
    const val MAX_FILE_SIZE_BYTES = 4 * 1024 * 1024 // 4 MiB
    const val MAX_PAIRS = 10_000
    const val MAX_TEXT_LENGTH = 500
    const val MAX_NAME_LENGTH = 80
    const val MAX_DESCRIPTION_LENGTH = 400
    const val MAX_HINTS_LENGTH = 1_200

    const val MAX_LINES_LIMIT = 20_000

    data class ParsedPair(
        val sourceText: String,
        val targetText: String,
        val sourceNfcTrimmed: String,
    )

    sealed interface ParseResult {
        data class Success(val pairs: List<ParsedPair>) : ParseResult
        data class Error(val lineNumber: Int?, val reason: String) : ParseResult
    }

    fun validateMetadata(name: String, description: String): String? {
        val trimmedName = name.trim()
        val trimmedDesc = description.trim()
        if (trimmedName.isEmpty() || trimmedName.length > MAX_NAME_LENGTH) {
            return "도메인 이름은 1자 이상 ${MAX_NAME_LENGTH}자 이하이어야 합니다."
        }
        if (trimmedDesc.length > MAX_DESCRIPTION_LENGTH) {
            return "도메인 설명은 ${MAX_DESCRIPTION_LENGTH}자 이하이어야 합니다."
        }
        for (c in trimmedName) {
            if (c.code < 0x20 || c.code == 0x7F) {
                return "도메인 이름에 허용되지 않는 제어 문자가 포함되어 있습니다."
            }
        }
        for (c in trimmedDesc) {
            if (c.code < 0x20 || c.code == 0x7F) {
                return "도메인 설명에 허용되지 않는 제어 문자가 포함되어 있습니다."
            }
        }
        // Calculate JSON encoded length directly without Android framework stub dependency
        val nameEscapedLen = jsonEscapedLength(trimmedName)
        val descEscapedLen = if (trimmedDesc.isNotEmpty()) jsonEscapedLength(trimmedDesc) else 0
        val totalJsonLen = if (trimmedDesc.isNotEmpty()) {
            "{\"domain\":,\"description\":}".length + nameEscapedLen + descEscapedLen
        } else {
            "{\"domain\":}".length + nameEscapedLen
        }
        if (totalJsonLen > MAX_HINTS_LENGTH) {
            return "도메인 메타데이터 JSON 인코딩 크기가 상한(${MAX_HINTS_LENGTH}자)을 초과했습니다."
        }
        return null
    }

    private fun jsonEscapedLength(s: String): Int {
        var len = 2 // enclosing quotes ""
        for (c in s) {
            len += when (c) {
                '\\', '"' -> 2
                '\n', '\r', '\t', '\b', '\u000c' -> 2
                else -> if (c.code < 0x20) 6 else 1
            }
        }
        return len
    }

    fun parseAndValidate(input: InputStream): ParseResult {
        // Enforce maximum file size by reading through bounded counting stream
        val buffer = ByteArrayOutputStream()
        val temp = ByteArray(8192)
        var totalBytes = 0L
        while (true) {
            val read = input.read(temp)
            if (read == -1) break
            totalBytes += read
            if (totalBytes > MAX_FILE_SIZE_BYTES) {
                return ParseResult.Error(
                    null,
                    "파일 크기가 최대 제한(4MiB)을 초과했습니다.",
                )
            }
            buffer.write(temp, 0, read)
        }

        val rawBytes = buffer.toByteArray()
        if (rawBytes.isEmpty()) {
            return ParseResult.Error(null, "입력 파일이 비어 있습니다.")
        }

        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)

        val fullText = try {
            decoder.decode(ByteBuffer.wrap(rawBytes)).toString()
        } catch (_: Exception) {
            return ParseResult.Error(null, "올바른 UTF-8 인코딩 형식이 아닙니다.")
        }

        val pairs = ArrayList<ParsedPair>()
        val seenSources = HashMap<String, String>() // sourceNfcTrimmed -> original targetText

        var lineNumber = 0
        var pendingBlankLineNumber: Int? = null

        val reader = fullText.reader().buffered()
        val iterator = reader.lineSequence().iterator()

        while (iterator.hasNext()) {
            val rawLineWithBom = iterator.next()
            lineNumber++

            if (lineNumber > MAX_LINES_LIMIT) {
                return ParseResult.Error(lineNumber, "전체 행 수가 최대 제한(${MAX_LINES_LIMIT}행)을 초과했습니다.")
            }

            val rawLine = if (lineNumber == 1) rawLineWithBom.removePrefix("\uFEFF") else rawLineWithBom

            if (rawLine.isBlank()) {
                if (pendingBlankLineNumber != null) {
                    return ParseResult.Error(pendingBlankLineNumber, "빈 줄이 존재합니다.")
                }
                pendingBlankLineNumber = lineNumber
                continue
            }

            if (pendingBlankLineNumber != null) {
                return ParseResult.Error(pendingBlankLineNumber, "빈 줄이 존재합니다.")
            }

            // 제어 문자 검사 (탭 \t, 개행 제외한 0x00..0x1F, 0x7F)
            for (char in rawLine) {
                if (char != '\t' && (char.code < 0x20 || char.code == 0x7F)) {
                    return ParseResult.Error(lineNumber, "허용되지 않는 제어 문자가 포함되어 있습니다.")
                }
            }

            val columns = rawLine.split("\t")
            if (columns.size != 2) {
                return ParseResult.Error(
                    lineNumber,
                    "열 형식이 올바르지 않습니다 (원문과 번역은 탭으로 구분되어야 합니다).",
                )
            }

            val source = columns[0].trim()
            val target = columns[1].trim()

            if (source.isEmpty() || target.isEmpty()) {
                return ParseResult.Error(lineNumber, "원문 또는 번역문이 비어 있습니다.")
            }

            if (source.length > MAX_TEXT_LENGTH) {
                return ParseResult.Error(
                    lineNumber,
                    "원문 길이가 최대 제한(${MAX_TEXT_LENGTH}자)을 초과했습니다.",
                )
            }

            if (target.length > MAX_TEXT_LENGTH) {
                return ParseResult.Error(
                    lineNumber,
                    "번역문 길이가 최대 제한(${MAX_TEXT_LENGTH}자)을 초과했습니다.",
                )
            }

            val sourceNfc = Normalizer.normalize(source, Normalizer.Form.NFC).trim()
            val existingTarget = seenSources[sourceNfc]
            if (existingTarget != null) {
                if (existingTarget != target) {
                    return ParseResult.Error(
                        lineNumber,
                        "동일한 원문에 서로 다른 번역문이 지정되었습니다.",
                    )
                }
                // 동일 원문에 완전 동일 번역문인 경우 중복 스킵
                continue
            }

            seenSources[sourceNfc] = target
            pairs.add(ParsedPair(sourceText = source, targetText = target, sourceNfcTrimmed = sourceNfc))

            if (pairs.size > MAX_PAIRS) {
                return ParseResult.Error(
                    lineNumber,
                    "코퍼스 쌍 개수가 최대 상한(${MAX_PAIRS}쌍)을 초과했습니다.",
                )
            }
        }

        if (pairs.isEmpty()) {
            return ParseResult.Error(null, "유효한 코퍼스 데이터가 없습니다.")
        }

        return ParseResult.Success(pairs)
    }

    fun generateTemplateTxt(): String = buildString {
        appendLine("회의 시작 전 안건을 미리 공유해 주시기 바랍니다.\tPlease share the agenda in advance before the meeting begins.")
        appendLine("금일 논의된 세부 실행 계획은 내일까지 정리하여 보고하겠습니다.\tI will summarize and report the detailed execution plan discussed today by tomorrow.")
        appendLine("주요 지표 변동 사항에 대해 추가 질의가 있으시면 말씀해 주세요.\tIf you have further questions regarding the changes in key metrics, please let us know.")
    }
}
