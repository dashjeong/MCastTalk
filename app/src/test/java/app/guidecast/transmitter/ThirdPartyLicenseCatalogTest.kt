package app.guidecast.transmitter

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThirdPartyLicenseCatalogTest {
    @Test
    fun firstPartyUsesApacheWithoutServicePermissionRequirement() {
        val entry = GUIDECAST_LICENSE_CATALOG.single { it.id == "mcasttalk-first-party-license" }
        assertEquals("Apache License 2.0", entry.licenseName)
        assertEquals("licenses/APACHE-2.0.txt", entry.offlineDocumentAsset)
        assertTrue(entry.notice.contains("상업적 이용을 포함"))
        assertTrue(entry.notice.contains("독립적인 서비스 구현에는 별도 협의를 요구하지 않습니다"))
        assertFalse(File("src/main/assets/licenses/POLYFORM-NONCOMMERCIAL-1.0.0.txt").exists())
    }

    @Test
    fun catalogHasUniqueCompleteOfflineMetadata() {
        assertEquals(47, GUIDECAST_LICENSE_CATALOG.size)
        assertEquals(
            GUIDECAST_LICENSE_CATALOG.size,
            GUIDECAST_LICENSE_CATALOG.map { it.id }.distinct().size,
        )
        GUIDECAST_LICENSE_CATALOG.forEach { entry ->
            assertTrue(entry.id.matches(Regex("[a-z0-9-]+")))
            assertFalse(entry.name.isBlank())
            assertFalse(entry.packageName.isBlank())
            assertFalse(entry.version.isBlank())
            assertFalse(entry.licenseName.isBlank())
            assertFalse(entry.notice.isBlank())
            assertTrue(entry.sourceUrl.startsWith("https://"))
            entry.offlineDocumentAsset?.let { asset ->
                assertTrue(asset.startsWith("licenses/"))
            }
        }
    }

    @Test
    fun searchMatchesPackageVersionAndLicenseWithoutNetwork() {
        assertEquals(
            listOf("opencc-dictionary-data"),
            filteredLicenseCatalog("Open Chinese Convert", null).map { it.id },
        )
        assertEquals(
            listOf("gemma-gpu-model"),
            filteredLicenseCatalog("gemma-4-E2B-it-gpu.litertlm", null).map { it.id },
        )
        assertEquals(
            listOf("moonshine-voice"),
            filteredLicenseCatalog("ai.moonshine:moonshine-voice", null).map { it.id },
        )
        assertEquals(listOf("zxing"), filteredLicenseCatalog("3.5.3", null).map { it.id })
        assertEquals(
            listOf("androidx-storage-work"),
            filteredLicenseCatalog("Work 2.9.1", null).map { it.id },
        )
        assertEquals(
            listOf("mlkit-runtime-notices"),
            filteredLicenseCatalog("firebase-encoders-json 17.1.0", null).map { it.id },
        )
        assertTrue(
            filteredLicenseCatalog("apache", LicenseCategory.APP_LIBRARY).all {
                it.category == LicenseCategory.APP_LIBRARY
            },
        )
        assertTrue(filteredLicenseCatalog("없는 패키지", null).isEmpty())
    }

    @Test
    fun developerInformationContainsOnlyApprovedContact() {
        assertEquals("dash.jeong@gmail.com", GUIDECAST_DEVELOPER_CONTACT)
    }

    @Test
    fun productAndMoonshineAttributionsRemainExactAndIndependent() {
        assertEquals("Powered by Codex, Gemini with dash.jeong", GUIDECAST_PRODUCT_ATTRIBUTION)
        assertEquals("Powered by Moonshine AI", MOONSHINE_REQUIRED_ATTRIBUTION)
        assertFalse(GUIDECAST_PRODUCT_ATTRIBUTION.contains(MOONSHINE_REQUIRED_ATTRIBUTION))

        val listener = File("../core/server/src/main/assets/listener/index.html").readText()
        assertTrue(listener.contains(">Powered by Codex, Gemini with dash.jeong</strong>"))
        assertTrue(listener.contains(">Powered by Moonshine AI</strong>"))
        assertEquals(1, listener.split("Powered by Codex, Gemini with dash.jeong").size - 1)
        assertEquals(1, listener.split("Powered by Moonshine AI").size - 1)
    }

    @Test
    fun everyStaticOfflineDocumentIsPresentAndNonEmpty() {
        val assetRoot = File("src/main/assets")
        GUIDECAST_LICENSE_CATALOG
            .mapNotNull { it.offlineDocumentAsset }
            .distinct()
            .filterNot { it.startsWith("licenses/upstream/") }
            .forEach { assetPath ->
                val asset = assetRoot.resolve(assetPath)
                assertTrue("Missing offline license asset: $assetPath", asset.isFile)
                assertTrue("Empty offline license asset: $assetPath", asset.length() > 0L)
            }
    }

    @Test
    fun moonshineAttributionAndPinnedRuntimeMetadataAreAuditableOffline() {
        val notice = File("src/main/assets/licenses/MOONSHINE-NOTICE.txt").readText()
        assertTrue(notice.contains(MOONSHINE_REQUIRED_ATTRIBUTION))
        assertTrue(notice.contains("Moonshine AI Community License"))

        val kissFft = GUIDECAST_LICENSE_CATALOG.single { it.id == "moonshine-native-kissfft" }
        assertTrue(kissFft.version.contains("febd4caeed32e33ad8b2e0bb5ea77542c40f18ec"))
        assertEquals("BSD-3-Clause", kissFft.licenseName)

        val utf8Cpp = GUIDECAST_LICENSE_CATALOG.single { it.id == "moonshine-native-utf8cpp" }
        assertTrue(utf8Cpp.version.contains("upstream revision 미기록"))
        val utf8Proc = GUIDECAST_LICENSE_CATALOG.single { it.id == "moonshine-native-utf8proc" }
        assertTrue(utf8Proc.version.startsWith("2.9.0"))
    }

    @Test
    fun onnxRuntimeThirdPartyNoticesAndGoogleApiTermsAreDiscoverable() {
        val onnxNotices = GUIDECAST_LICENSE_CATALOG.single {
            it.id == "onnx-runtime-third-party-notices"
        }
        assertEquals(
            "licenses/ONNXRUNTIME-1.23.2-THIRD-PARTY-NOTICES.txt",
            onnxNotices.offlineDocumentAsset,
        )
        val noticeFile = File("src/main/assets/${onnxNotices.offlineDocumentAsset}")
        val noticeText = noticeFile.readText()
        assertTrue(noticeText.startsWith("THIRD PARTY SOFTWARE NOTICES AND INFORMATION"))
        assertTrue(noticeText.contains("Intel Math Kernel Library"))
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(noticeFile.readBytes())
            .joinToString("") { byte -> "%02x".format(byte) }
        assertEquals(
            "e9e90971a8e75a9a8ac0c6412e29c1202d079998389915aa485f46c816c3b4cc",
            digest,
        )

        val googleTerms = GUIDECAST_LICENSE_CATALOG.single { it.id == "google-apis-terms" }
        assertEquals("https://developers.google.com/terms", googleTerms.sourceUrl)
        assertTrue(googleTerms.licenseName.contains("Google APIs Terms"))

        val gemmaModel = GUIDECAST_LICENSE_CATALOG.single { it.id == "gemma-model" }
        val pinnedRevision = "6e5c4f1e395deb959c494953478fa5cec4b8008f"
        assertTrue(gemmaModel.version.contains(pinnedRevision))
        assertTrue(gemmaModel.sourceUrl.contains("/blob/$pinnedRevision/"))

        val modelNotices = File("src/main/assets/licenses/MODEL-AND-VOICE-NOTICES.txt").readText()
        assertTrue(modelNotices.contains("/blob/$pinnedRevision/"))
        assertFalse(modelNotices.contains("gemma-4-E2B-it-litert-lm/blob/main/"))
    }

    @Test
    fun spanishAndArabicVoiceRightsAreNotFlattenedIntoTheSdkLicense() {
        val spanish = GUIDECAST_LICENSE_CATALOG.single { it.id == "piper-spanish-voice" }
        assertEquals("piper_es_MX-ald-medium", spanish.packageName)
        assertTrue(spanish.licenseName.contains("Unlicense"))
        assertTrue(spanish.licenseName.contains("CC0"))
        assertTrue(spanish.sourceUrl.contains("39ab474be869e9181350af6a65e4953eef67aaa0"))

        val arabic = GUIDECAST_LICENSE_CATALOG.single { it.id == "piper-arabic-voice" }
        assertTrue(arabic.packageName.contains("piper_ar_JO-kareem-medium"))
        assertTrue(arabic.packageName.contains("arabertv02_tashkeel_fadel"))
        assertTrue(arabic.licenseName.contains("미명시"))
        assertTrue(arabic.notice.contains("판매 전 별도 권리 확인"))

        val modelNotices = File("src/main/assets/licenses/MODEL-AND-VOICE-NOTICES.txt").readText()
        assertTrue(modelNotices.contains("piper_es_MX-ald-medium"))
        assertTrue(modelNotices.contains("piper_ar_JO-kareem-medium"))
        assertTrue(modelNotices.contains("AbderrahmanSkiredj1/arabertv02_tashkeel_fadel"))
    }
}
