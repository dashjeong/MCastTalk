import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

val guideCastSigningPropertiesFile = rootProject.file("keystore.properties")
val guideCastSigningProperties = Properties().apply {
    if (guideCastSigningPropertiesFile.isFile) {
        guideCastSigningPropertiesFile.inputStream().use(::load)
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.guidecast.client"
    compileSdk = 36
    testBuildType = "alpha"

    defaultConfig {
        applicationId = "app.guidecast.client"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
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
                storePassword = requireNotNull(
                    guideCastSigningProperties.getProperty("storePassword"),
                ) {
                    "keystore.properties must define storePassword"
                }
                keyAlias = requireNotNull(guideCastSigningProperties.getProperty("keyAlias")) {
                    "keystore.properties must define keyAlias"
                }
                keyPassword = requireNotNull(
                    guideCastSigningProperties.getProperty("keyPassword"),
                ) {
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
            // Match the transmitter's private developer identity. Never make a distributable
            // client Alpha replaceable with Android's publicly reproducible debug key.
            signingConfig = signingConfigs.findByName("developerAlpha")
            matchingFallbacks += listOf("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs.pickFirsts += "lib/arm64-v8a/libonnxruntime.so"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:stream"))
    implementation(project(":core:translation"))
    implementation(project(":provider:mlkit-translation"))
    implementation(project(":provider:moonshine-stt"))
    implementation(project(":provider:moonshine-tts"))

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
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
}
