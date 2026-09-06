import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.Sync
import java.io.File
import java.util.Properties
import java.util.zip.ZipFile
import java.security.MessageDigest

val guideCastSigningPropertiesFile = rootProject.file("keystore.properties")
val guideCastSigningProperties = Properties().apply {
    if (guideCastSigningPropertiesFile.isFile) {
        guideCastSigningPropertiesFile.inputStream().use(::load)
    }
}

val thirdPartyLicenseArtifacts = configurations.create("thirdPartyLicenseArtifacts") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

val generatedThirdPartyLicenseAssets = layout.buildDirectory.dir("generated/thirdPartyLicenseAssets")
val staticThirdPartyLicenseAssets = layout.projectDirectory.dir("src/main/assets/licenses")
val requiredStaticLicenseAssetNames = setOf(
    "APACHE-2.0.txt",
    "KISSFFT-BSD-3-CLAUSE.txt",
    "MIT.txt",
    "MODEL-AND-VOICE-NOTICES.txt",
    "PUBLIC-TERMINOLOGY-NOTICES.txt",
    "RNNOISE-BSD-3-CLAUSE.txt",
    "MOONSHINE-0.1.5-LICENSE.txt",
    "MOONSHINE-CPP-ANNOTE-MIT.txt",
    "MOONSHINE-CPP-ANNOTE-README.txt",
    "MOONSHINE-EIGEN-MPL-2.0.txt",
    "MOONSHINE-EIGEN-README.txt",
    "MOONSHINE-KALDI-NATIVE-FBANK-APACHE-2.0.txt",
    "MOONSHINE-KALDI-NATIVE-FBANK-README.txt",
    "MOONSHINE-KISSFFT-BSD.txt",
    "MOONSHINE-KISSFFT-NOTICE-AND-LICENSE.txt",
    "MOONSHINE-KISSFFT-README.txt",
    "MOONSHINE-NLOHMANN-MIT.txt",
    "MOONSHINE-NLOHMANN-README.txt",
    "MOONSHINE-NOTICE.txt",
    "MOONSHINE-ONNXRUNTIME-MIT.txt",
    "ONNXRUNTIME-1.23.2-THIRD-PARTY-NOTICES.txt",
    "MOONSHINE-TTS-DATA-README.txt",
    "MOONSHINE-TTS-EN-US-README.txt",
    "MOONSHINE-TTS-JA-README.txt",
    "MOONSHINE-TTS-KOKORO-README.txt",
    "MOONSHINE-TTS-NL-README.txt",
    "MOONSHINE-TTS-ZH-HANS-README.txt",
    "MOONSHINE-UTF8CPP-BSL-1.0.txt",
    "MOONSHINE-UTF8CPP-README.txt",
    "MOONSHINE-UTF8PROC-MIT-UNICODE.txt",
    "MOONSHINE-UTF8PROC-README.txt",
    "SILERO-VAD-PINNED-MIT.txt",
    "SLF4J-2.0.18-LICENSE.txt",
    "THIRD-PARTY-NOTICES.txt",
    "OPENCC-APACHE-2.0.txt",
    "OPENCC-README.txt",
)
val requiredGeneratedLicenseAssetNames = setOf(
    "upstream/LICENSE",
    "upstream/THIRD_PARTY_NOTICE.txt",
    "upstream/third_party_licenses.json",
    "upstream/third_party_licenses.txt",
)
val generateThirdPartyLicenseAssets by tasks.registering(Sync::class) {
    val licenseArchives = thirdPartyLicenseArtifacts.elements.map { artifacts ->
        artifacts.map { archive -> zipTree(archive.asFile) }
    }
    from(licenseArchives) {
        include(
            "LICENSE",
            "THIRD_PARTY_NOTICE.txt",
            "third_party_licenses.json",
            "third_party_licenses.txt",
        )
        into("licenses/upstream")
        // Some upstream AARs store notices as mode 0555. Sync then cannot replace its own output
        // on the next incremental build. Normalize generated assets so repeated release builds
        // are deterministic and never require deleting the build directory by hand.
        eachFile {
            permissions { unix("0644") }
        }
    }
    into(generatedThirdPartyLicenseAssets)
}

val verifyThirdPartyLicenseAssets by tasks.registering {
    dependsOn(generateThirdPartyLicenseAssets)
    doLast {
        val staticRoot = staticThirdPartyLicenseAssets.asFile
        val actualStaticNames = staticRoot.listFiles()
            ?.filter(File::isFile)
            ?.map(File::getName)
            ?.toSet()
            .orEmpty()
        check(actualStaticNames == requiredStaticLicenseAssetNames) {
            "Static license asset set changed. Missing=" +
                "${requiredStaticLicenseAssetNames - actualStaticNames}, unexpected=" +
                "${actualStaticNames - requiredStaticLicenseAssetNames}"
        }
        requiredStaticLicenseAssetNames.forEach { name ->
            val notice = staticRoot.resolve(name)
            check(notice.length() > 0L) { "Required static license is empty: ${notice.absolutePath}" }
        }

        val generatedRoot = generatedThirdPartyLicenseAssets.get().asFile.resolve("licenses")
        requiredGeneratedLicenseAssetNames.forEach { name ->
            val notice = generatedRoot.resolve(name)
            check(notice.isFile && notice.length() > 0L) {
                "Required third-party notice was not extracted: ${notice.absolutePath}"
            }
        }
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.guidecast.transmitter"
    compileSdk = 36
    // Device integration tests must exercise the same R8/minified variant that users install.
    // A debug-only green test cannot certify the alpha APK's native translation path.
    testBuildType = "alpha"

    defaultConfig {
        applicationId = "app.guidecast.transmitter"
        minSdk = 30
        targetSdk = 36
        versionCode = 44
        versionName = "0.2.38"

        // Galaxy Note9/S23 and newer targets are ARM64. Keeping only the required ABI
        // avoids shipping an unused second LiteRT-LM native runtime in the sideload APK.
        ndk.abiFilters += "arm64-v8a"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (guideCastSigningPropertiesFile.isFile) {
            create("developerAlpha") {
                storeFile = rootProject.file(
                    requireNotNull(guideCastSigningProperties.getProperty("storeFile")) {
                        "keystore.properties must define storeFile"
                    },
                )
                storePassword = requireNotNull(guideCastSigningProperties.getProperty("storePassword")) {
                    "keystore.properties must define storePassword"
                }
                keyAlias = requireNotNull(guideCastSigningProperties.getProperty("keyAlias")) {
                    "keystore.properties must define keyAlias"
                }
                keyPassword = requireNotNull(guideCastSigningProperties.getProperty("keyPassword")) {
                    "keystore.properties must define keyPassword"
                }
                enableV1Signing = false
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        create("alpha") {
            initWith(getByName("release"))
            applicationIdSuffix = ".alpha"
            versionNameSuffix = "-alpha"
            // Never silently fall back to Android's debug key. A checkout without the ignored
            // developer credentials may produce an unsigned CI artifact for compile/testing;
            // every distributable Alpha handoff must pass the explicit signer verification gate.
            signingConfig = signingConfigs.findByName("developerAlpha")
            matchingFallbacks += listOf("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets.getByName("main").assets.srcDir(generatedThirdPartyLicenseAssets)

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // Moonshine 0.1.5's reduced ORT omits QLinearMatMul required by its
        // current Kokoro model. Prefer the pinned full ORT 1.23.2 binary from
        // provider:moonshine-tts until the upstream AAR ships that operator.
        jniLibs.pickFirsts += "lib/arm64-v8a/libonnxruntime.so"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    add(
        thirdPartyLicenseArtifacts.name,
        "com.google.ai.edge.litertlm:litertlm-android:0.16.1",
    )
    add(thirdPartyLicenseArtifacts.name, "com.google.mlkit:translate:17.0.3")

    implementation(project(":core:audio"))
    implementation(project(":core:server"))
    implementation(project(":core:stream"))
    implementation(project(":core:translation"))
    implementation(project(":provider:mlkit-translation"))
    implementation(project(":provider:android-stt"))
    implementation(project(":provider:android-tts"))
    implementation(project(":provider:moonshine-tts"))
    implementation(project(":provider:moonshine-stt"))
    implementation(project(":provider:gemma-translation"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.zxing.core)
    debugImplementation(libs.androidx.compose.ui.tooling)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.moonshine.voice)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.named("preBuild").configure {
    dependsOn(generateThirdPartyLicenseAssets, verifyThirdPartyLicenseAssets)
}

val verifyPackagedThirdPartyLicenseAssets by tasks.registering {
    group = "verification"
    description = "Checks every reviewed license and notice in the distributable alpha APK."
    dependsOn("assembleAlpha")
    doLast {
        // Public builds are unsigned. Resolve this build's output metadata instead of
        // accidentally checking a stale signed APK or requiring private signing keys.
        val outputDirectory = layout.buildDirectory.dir("outputs/apk/alpha").get()
        val metadata = groovy.json.JsonSlurper().parse(
            outputDirectory.file("output-metadata.json").asFile,
        ) as Map<*, *>
        check(metadata["variantName"] == "alpha") { "Unexpected APK variant" }
        val element = (metadata["elements"] as List<*>).single() as Map<*, *>
        val outputFile = element["outputFile"] as String
        check(outputFile in setOf("app-alpha.apk", "app-alpha-unsigned.apk")) {
            "Unexpected Alpha APK output filename"
        }
        val apk = File(outputDirectory.asFile, outputFile)
        check(apk.canonicalFile.parentFile == outputDirectory.asFile.canonicalFile) {
            "Alpha APK must be inside the variant output directory"
        }
        check(apk.isFile) { "Alpha APK was not built: ${apk.absolutePath}" }
        val expected = requiredStaticLicenseAssetNames.mapTo(mutableSetOf()) {
            "assets/licenses/$it"
        }.apply {
            addAll(requiredGeneratedLicenseAssetNames.map { "assets/licenses/$it" })
        }
        ZipFile(apk).use { zip ->
            // AAPT expands source .gz assets and strips .gz. Verify the actual runtime path,
            // not merely the source-tree file: a mismatch left the first device trial empty.
            val glossary = requireNotNull(zip.getEntry("assets/glossary/public-20260905.db")) {
                "The runtime glossary asset is missing from Alpha APK"
            }
            check(glossary.size == 29_405_184L) { "Packaged glossary size changed" }
            val digest = MessageDigest.getInstance("SHA-256")
            zip.getInputStream(glossary).use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            check(digest.digest().joinToString("") { "%02x".format(it) } ==
                "5f39f27faf0c68b305d06e67c09eb929398e0242dd0278eb8430c49070d2ccb3") {
                "Packaged glossary SHA-256 changed"
            }
            val actual = zip.entries().asSequence()
                .filterNot { it.isDirectory }
                .map { it.name }
                .filter { it.startsWith("assets/licenses/") }
                .toSet()
            check(actual == expected) {
                "Packaged license asset set changed. Missing=${expected - actual}, " +
                    "unexpected=${actual - expected}"
            }
            expected.forEach { name ->
                val entry = requireNotNull(zip.getEntry(name)) { "Missing packaged notice: $name" }
                zip.getInputStream(entry).use { input ->
                    check(input.read() >= 0) { "Packaged notice is empty: $name" }
                }
            }
        }
    }
}
