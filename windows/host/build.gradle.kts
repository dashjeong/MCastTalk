import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("app.mcasttalk.windows.host.MainKt")
}

tasks.test {
    // A synthetic user home must be outside Windows Temp: AF_UNIX connections
    // may be blocked there even for writable directories. Keep test data local.
    systemProperty("mcasttalk.test.socketRoot", rootProject.file(".run/st").absolutePath)
}

dependencies {
    implementation(project(":core:stream"))
    implementation(project(":core:translation"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation("io.ktor:ktor-server-netty:3.5.2")
    implementation("io.ktor:ktor-network-tls-certificates:3.5.2")
    implementation(libs.ktor.server.websockets)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.websockets)
}
