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
        // Reviewed local native repairs must never resolve to an unrelated remote package.
        exclusiveContent {
            forRepository {
                maven {
                    name = "reviewedMoonshinePatch"
                    url = uri("third_party/maven")
                }
            }
            filter { includeGroup("app.guidecast.thirdparty") }
        }
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
