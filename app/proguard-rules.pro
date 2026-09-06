# Keep rules will be added only for libraries that require reflection.

# Ktor's optional IntelliJ debugger detector probes these desktop-JVM APIs.
# Android does not provide them and the detector safely falls back when absent.
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean

# Alpha is the actual sideload artifact and is also the instrumentation target. The separately
# packaged AndroidX test runner calls Kotlin's public null-check helpers across the APK boundary;
# renaming these methods caused a pre-test NoSuchMethodError before the translation app started.
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class androidx.tracing.** { *; }
# The minified alpha is the real device-test target. Its test APK retrieves the exact
# Activity-scoped AudioInputViewModel used by the Compose controls, so this bridge must remain
# present across the target/test APK boundary instead of being inlined away by R8.
-keep class androidx.lifecycle.ViewModelProvider { *; }

# GuideCast is open-source and its public app/provider surface is called from the separately
# installed device-test APK and from Android component/JNI boundaries. Keep that surface stable;
# dependency and resource shrinking remain enabled for the alpha artifact.
-keep class app.guidecast.** { *; }
