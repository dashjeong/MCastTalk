package app.guidecast.provider.moonshine.tts

/**
 * Byte lengths and SHA-256 digests independently recorded from the Moonshine Voice 0.1.5 official
 * CDN on 2026-09-03 for the dependency paths returned by that exact AAR. The CDN bytes were also
 * matched against its FILES.tsv byte lengths and MD5 inventory. Moonshine's native manifest returns null `size`
 * and an empty checksum for these TTS entries, so GuideCast must not silently fall back to an
 * existence-only or byte-length-only check. A future Moonshine dependency/path change must update
 * this table deliberately after the upstream inventory and downloaded bytes are re-verified.
 */
internal object MoonshineTtsPinnedDependencies {
    fun forVoice(voiceId: String): List<MoonshineTtsExpectedFile> =
        requireNotNull(BY_VOICE[voiceId]) { "No pinned dependencies for Moonshine voice $voiceId" }

    private val kokoroRuntime = listOf(
        expected("kokoro/prosody.model.ort", 12_376_656, "e4f83bf18d786ab8c9cd2ad21e326021b9d7d5fe776458755e075c8574ff876a"),
        expected("kokoro/prosody.weights.ort", 16_480_288, "7ead0eed0498a6b8f7abebb7c9bc2867293c5d7cbb71d2bdf442b7ba48ae77e7"),
        expected("kokoro/decoder.model.ort", 39_257_640, "1deaf4c42124a9034e325cf6057ebe58f80f429af68401c573c519a3dd7e56bd"),
        expected("kokoro/decoder.weights.ort", 15_299_512, "80272c16d48026641ee0971252be2a3e24ae0bea75a35ad1a822c124cb3a266e"),
        expected("kokoro/config.json", 2_351, "5abb01e2403b072bf03d04fde160443e209d7a0dad49a423be15196b9b43c17f"),
    )

    private val BY_VOICE = mapOf(
        "kokoro_af_heart" to listOf(
            expected("en_us/dict_filtered_heteronyms.tsv", 2_900_453, "8fb0fa0e3ce1a74b864f03c06ace015257660fa2116c6157d11061f4e35bb6b7"),
            expected("en_us/g2p-config.json", 60, "f10e652b28c49edd90a94ceb139b94d2368de5814650d81289fcb985fe1ca0f5"),
            expected("en_us/oov/model.ort", 22_143_488, "ef8d07a0577a07617fabf5282d80d680e4e17ad07a763e7e3748417f94554d94"),
            expected("en_us/oov/onnx-config.json", 4_641, "60a7cf2592ae66702f56e4368a8614e72235eef89205de96f4cf6bace96c5692"),
        ) + kokoroRuntime + expected("kokoro/voices/af_heart.kokorovoice", 522_252, "908e14de5b4709da55562129164e618f5d135fcc34dac419e0c3de5189b72d2c"),
        "kokoro_jf_alpha" to listOf(
            expected("ja/dict.tsv", 6_408_365, "407bc8fc9fcedfc8e2207a27432137708be1a9d8d68438dd03837b2dd0adeec7"),
            expected("ja/roberta_japanese_char_luw_upos_onnx/meta.json", 885, "ed58413a73756bb3375e1e41d743aa40aaadb0cf0c5bc86ce35306a760e2bf73"),
            expected("ja/roberta_japanese_char_luw_upos_onnx/vocab.txt", 37_193, "879d9a69df070dbdf143f64bb3daf73d367741980f18a2f6d08a12e68a598a11"),
            expected("ja/roberta_japanese_char_luw_upos_onnx/tokenizer_config.json", 1_333, "3ccbfbdadf135ed8f8b0c8f09d34409c41b9aa3c4b9714477a5561af51065083"),
            expected("ja/roberta_japanese_char_luw_upos_onnx/model.ort", 41_685_760, "27271e7a81c5cb7c07c5c79b58a49546fbc821a0fe70caafe29bdf4547d22bdf"),
        ) + kokoroRuntime + expected("kokoro/voices/jf_alpha.kokorovoice", 522_252, "9987931587be194fe7fea5d4e009dd285b0674915713be99f009ea206729184b"),
        "kokoro_zf_xiaoxiao" to listOf(
            expected("zh_hans/dict.tsv", 1_295_571, "f48d297d0ced50a261ea8220f343a59ccb4f992137be81205ecc69a6aaa939ab"),
            expected("zh_hans/roberta_chinese_base_upos_onnx/meta.json", 773, "5d1e89cb53593401a1573b8215a1a099f1686fea8d8bd0caae9e387ef72164a5"),
            expected("zh_hans/roberta_chinese_base_upos_onnx/vocab.txt", 109_540, "45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c"),
            expected("zh_hans/roberta_chinese_base_upos_onnx/tokenizer_config.json", 1_272, "7b082b48a08a0b5c5939d99472e33addce3d96202155e33f7928cecd171186a0"),
            expected("zh_hans/roberta_chinese_base_upos_onnx/model.model.ort", 733_776, "5cab9fabd1b0b523c795c88c7ce3ea166617ce145c04f0b1e06930dcf2e456f3"),
            expected("zh_hans/roberta_chinese_base_upos_onnx/model.weights.ort", 101_713_624, "f3ff1dd7fe1ec6eec19c71ff4dbefb0a1a1f59da03f5a872f183f2b7e39283ee"),
        ) + kokoroRuntime + expected("kokoro/voices/zf_xiaoxiao.kokorovoice", 522_252, "5d38da0fac953cf9052a3bfa8f7eda372b711a8f396b917c53e0188756080298"),
        "piper_nl_NL-mls-medium" to listOf(
            expected("nl/dict.tsv", 3_789_110, "7c0c0705cb99434aa1229220efb679acc8513d728865598183a357f661348863"),
            expected("nl/piper-voices/nl_NL-mls-medium.upstream.model.ort", 1_289_272, "58afd165c9b50177a21255f3c2f0353bb21a70e2b797c40ea7d5b86b609872e9"),
            expected("nl/piper-voices/nl_NL-mls-medium.upstream.weights.ort", 17_377_152, "e76eb39d017cbc5b8ac3e6b53f7327e6d25b5d1f57732d0a1217bc2b127c73d2"),
            expected("nl/piper-voices/nl_NL-mls-medium.generator.model.ort", 273_728, "ee1ccc17818bebaeb02e2eef7363d104f7d83a2af25896723419d53918b4f897"),
            expected("nl/piper-voices/nl_NL-mls-medium.generator.weights.ort", 1_767_408, "6ae5f4b04632044dc76fce670c5f71b6ac3b4219f820e9d5b8bdcae42a4e5c75"),
            expected("nl/piper-voices/nl_NL-mls-medium.onnx.json", 5_856, "6ddb215d38f1392ab935ad45441b82ada1eeae0452a2d6849ed71ea4f2e0aa63"),
        ),
        "piper_es_MX-ald-medium" to listOf(
            expected("es_mx/piper-voices/es_MX-ald-medium.upstream.model.ort", 1_390_344, "00b9d2eec92c52c10656b2f2016ef9a500732381db0c0e021753c0f7dba387c0"),
            expected("es_mx/piper-voices/es_MX-ald-medium.upstream.weights.ort", 14_095_440, "180e9a3c64cec8c1ef89e357789b3f757248de587e2947ff05bb000cce497200"),
            expected("es_mx/piper-voices/es_MX-ald-medium.generator.model.ort", 274_176, "879a51109adf71762032f98ca54b5fd54b535b76774b639cc8dd06ae52d6c117"),
            expected("es_mx/piper-voices/es_MX-ald-medium.generator.weights.ort", 1_634_472, "17036957e57294f5b610373e47b18873ec28fe563b97e73e09d82f1f7900eea7"),
            expected("es_mx/piper-voices/es_MX-ald-medium.onnx.json", 4_889, "efab736e62e5321dd5d063d1b46e63c59ce655419816355b81d025c1a8d6b03c"),
        ),
        "piper_ar_JO-kareem-medium" to listOf(
            expected("ar_msa/dict.tsv", 1_388_886, "023dd8cb67fa6834ce2ffbb44e63a7c287182baad123ca280759a184a0f28775"),
            expected("ar_msa/arabertv02_tashkeel_fadel_onnx/meta.json", 415, "82373027bd50ea1b2109e535d5db5c033be7618aae4f7555e53e6ffea59395b7"),
            expected("ar_msa/arabertv02_tashkeel_fadel_onnx/vocab.txt", 760_795, "57f15013d81a48c99ee349f0c55e617123d2ea4f2e6fc99e9380a4bc8b830e37"),
            expected("ar_msa/arabertv02_tashkeel_fadel_onnx/tokenizer_config.json", 491, "0feef985f16bb3b8eed36b2112a473f85ae7f349357987ec947edd0bba6b2dad"),
            expected("ar_msa/arabertv02_tashkeel_fadel_onnx/model.model.ort", 798_528, "2956923d67959098ddcdf3748b588321502feeab08d1c380b09fec3e29dbed76"),
            expected("ar_msa/arabertv02_tashkeel_fadel_onnx/model.weights.ort", 134_610_872, "d306a6d635e87a66be265970d1b43d330d3d01eef7f5a3d09e05ff41112eb5ce"),
            expected("ar_msa/piper-voices/ar_JO-kareem-medium.upstream.model.ort", 1_390_320, "82176dee41296e7068f2df04107eaca723056523c7801498466ae60858903aaf"),
            expected("ar_msa/piper-voices/ar_JO-kareem-medium.upstream.weights.ort", 14_095_440, "a5b859acdd81a8fccabf1f54a8f958831a4baba4eafa265dd25a682c2958ea17"),
            expected("ar_msa/piper-voices/ar_JO-kareem-medium.generator.model.ort", 274_176, "7183edeb1091665995c8c319abfb69f2b5e7b0e07b52fcb47a91b789957bada3"),
            expected("ar_msa/piper-voices/ar_JO-kareem-medium.generator.weights.ort", 1_634_472, "97e16ff72360d36e1cdde5ed6f55494cfe08877ce605491167403662412431e3"),
            expected("ar_msa/piper-voices/ar_JO-kareem-medium.onnx.json", 5_024, "ea6d9b9d9076dbdb6bf5c98c6a141ef154959d2359709b37855727964e7d6c4d"),
        ),
    )

    private fun expected(path: String, size: Long, sha256: String) =
        MoonshineTtsExpectedFile(path, size, sha256)
}

internal data class MoonshineTtsManifestEntry(
    val relativePath: String,
    val declaredSize: Long?,
    val checksum: String?,
    val checksumType: String?,
)

/** Reconciles native paths with the release-pinned official CDN metadata without any I/O. */
internal fun reconcileMoonshineTtsManifest(
    voiceId: String,
    manifestEntries: Collection<MoonshineTtsManifestEntry>,
): List<MoonshineTtsExpectedFile> {
    val pinned = MoonshineTtsPinnedDependencies.forVoice(voiceId)
        .associateBy(MoonshineTtsExpectedFile::relativePath)
    require(manifestEntries.size == pinned.size) {
        "Moonshine TTS dependency count changed for $voiceId"
    }
    require(manifestEntries.map(MoonshineTtsManifestEntry::relativePath).distinct().size ==
        manifestEntries.size) {
        "Moonshine TTS manifest contains duplicate paths"
    }
    return manifestEntries.map { entry ->
        val releaseFile = requireNotNull(pinned[entry.relativePath]) {
            "Moonshine TTS dependency path changed: ${entry.relativePath}"
        }
        entry.declaredSize?.let { declaredSize ->
            require(declaredSize == releaseFile.expectedSize) {
                "Moonshine TTS dependency size changed: ${entry.relativePath}"
            }
        }
        val checksum = entry.checksum?.trim().orEmpty()
        val checksumType = entry.checksumType?.trim().orEmpty()
        val manifestSha256 = when {
            checksum.isEmpty() && checksumType.isEmpty() -> null
            checksumType.equals("sha256", ignoreCase = true) ||
                checksumType.equals("sha-256", ignoreCase = true) -> checksum
            else -> error("Moonshine TTS manifest contains an unsupported checksum type")
        }
        manifestSha256?.let { officialHash ->
            require(officialHash.equals(releaseFile.sha256, ignoreCase = true)) {
                "Moonshine TTS dependency checksum changed: ${entry.relativePath}"
            }
        }
        releaseFile
    }
}
