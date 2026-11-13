import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

val reuseResearch = providers.gradleProperty("reuseResearch").map(String::toBoolean).getOrElse(false)
val nemotronResearch = providers.gradleProperty("nemotronResearch").map(String::toBoolean).getOrElse(false)

android {
    namespace = "app.guidecast.core.audio"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    if (reuseResearch) {
        sourceSets.getByName("main").jniLibs.srcDir("src/reuseResearch/jniLibs")
        sourceSets.getByName("main").assets.srcDir("src/reuseResearch/assets")
    }
    if (nemotronResearch) {
        sourceSets.getByName("main").java.srcDirs("src/nemotronResearch/java", "../../third_party/nemotron/android")
        sourceSets.getByName("main").assets.srcDir("src/nemotronResearch/assets")
        sourceSets.getByName("androidTest").java.srcDir("src/nemotronTest/java")
        sourceSets.getByName("test").java.srcDir("src/nemotronUnitTest/java")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    if (nemotronResearch) implementation("com.google.ai.edge.litert:litert:2.2.0")
}
