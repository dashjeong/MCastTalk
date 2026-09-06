pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "GuideCast"

include(":app")
include(":client-app")
include(":core:audio")
include(":core:stream")
include(":core:server")
include(":core:translation")
include(":provider:mlkit-translation")
include(":provider:android-tts")
include(":provider:moonshine-tts")
include(":provider:moonshine-stt")
include(":provider:android-stt")
include(":provider:gemma-translation")
