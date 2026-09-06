package app.guidecast.provider.moonshine.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MoonshineTtsPinnedDependenciesTest {
    @Test
    fun allSixOfficialVoicesHaveUniquePathsAndReleasePinnedSha256() {
        val expectedCounts = mapOf(
            "kokoro_af_heart" to 10,
            "kokoro_jf_alpha" to 11,
            "kokoro_zf_xiaoxiao" to 12,
            "piper_nl_NL-mls-medium" to 6,
            "piper_es_MX-ald-medium" to 5,
            "piper_ar_JO-kareem-medium" to 11,
        )

        expectedCounts.forEach { (voiceId, expectedCount) ->
            val files = MoonshineTtsPinnedDependencies.forVoice(voiceId)
            assertEquals(expectedCount, files.size)
            assertEquals(files.size, files.map { it.relativePath }.distinct().size)
            assertTrue(files.all { it.expectedSize > 0L })
            assertTrue(files.all { it.sha256?.matches(Regex("[0-9a-f]{64}")) == true })
        }
    }

    @Test
    fun spanishAndArabicPinsMatchOfficialCdnBytes() {
        val spanish = MoonshineTtsPinnedDependencies.forVoice("piper_es_MX-ald-medium")
        assertEquals(
            "00b9d2eec92c52c10656b2f2016ef9a500732381db0c0e021753c0f7dba387c0",
            spanish.single { it.relativePath.endsWith("upstream.model.ort") }.sha256,
        )
        assertEquals(17_399_321L, spanish.sumOf { it.expectedSize })

        val arabic = MoonshineTtsPinnedDependencies.forVoice("piper_ar_JO-kareem-medium")
        assertEquals(
            "d306a6d635e87a66be265970d1b43d330d3d01eef7f5a3d09e05ff41112eb5ce",
            arabic.single { it.relativePath.endsWith("model.weights.ort") }.sha256,
        )
        assertEquals(154_959_419L, arabic.sumOf { it.expectedSize })
    }

    @Test
    fun nullSizesFromMoonshine015ManifestUsePinnedOfficialCdnLengths() {
        val pinned = MoonshineTtsPinnedDependencies.forVoice("kokoro_af_heart")
        val reconciled = reconcileMoonshineTtsManifest(
            voiceId = "kokoro_af_heart",
            manifestEntries = pinned.map { file ->
                MoonshineTtsManifestEntry(
                    relativePath = file.relativePath,
                    declaredSize = null,
                    checksum = "",
                    checksumType = "",
                )
            },
        )

        assertEquals(pinned, reconciled)
        assertEquals(
            22_143_488L,
            reconciled.single { it.relativePath == "en_us/oov/model.ort" }.expectedSize,
        )
        assertEquals(pinned.first().sha256, reconciled.first().sha256)
    }

    @Test
    fun nativePathOrDeclaredSizeChangeIsRejected() {
        val pinned = MoonshineTtsPinnedDependencies.forVoice("piper_nl_NL-mls-medium")
        val entries = pinned.map { file ->
            MoonshineTtsManifestEntry(file.relativePath, file.expectedSize, "", "")
        }

        assertThrows(IllegalArgumentException::class.java) {
            reconcileMoonshineTtsManifest(
                "piper_nl_NL-mls-medium",
                entries.dropLast(1) + entries.last().copy(relativePath = "nl/unreviewed.bin"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            reconcileMoonshineTtsManifest(
                "piper_nl_NL-mls-medium",
                entries.mapIndexed { index, entry ->
                    if (index == 0) entry.copy(declaredSize = entry.declaredSize!! + 1) else entry
                },
            )
        }
    }

    @Test
    fun matchingManifestSha256KeepsReleasePinnedIntegrityExpectation() {
        val pinned = MoonshineTtsPinnedDependencies.forVoice("piper_nl_NL-mls-medium")
        val firstPinnedSha256 = requireNotNull(pinned.first().sha256)
        val reconciled = reconcileMoonshineTtsManifest(
            "piper_nl_NL-mls-medium",
            pinned.mapIndexed { index, file ->
                MoonshineTtsManifestEntry(
                    relativePath = file.relativePath,
                    declaredSize = file.expectedSize,
                    checksum = if (index == 0) firstPinnedSha256.uppercase() else "",
                    checksumType = if (index == 0) "sha256" else "",
                )
            },
        )

        assertEquals(firstPinnedSha256, reconciled.first().sha256)
    }

    @Test
    fun changedManifestSha256IsRejected() {
        val pinned = MoonshineTtsPinnedDependencies.forVoice("piper_nl_NL-mls-medium")

        assertThrows(IllegalArgumentException::class.java) {
            reconcileMoonshineTtsManifest(
                "piper_nl_NL-mls-medium",
                pinned.mapIndexed { index, file ->
                    MoonshineTtsManifestEntry(
                        relativePath = file.relativePath,
                        declaredSize = file.expectedSize,
                        checksum = if (index == 0) "ab".repeat(32) else "",
                        checksumType = if (index == 0) "sha-256" else "",
                    )
                },
            )
        }
    }
}
