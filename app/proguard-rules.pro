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
# Synthetic user journeys render the real composables from the separate test APK. These
# Kotlin facades otherwise disappear through inlining even when the underlying UI remains.
-keep class androidx.activity.compose.ComponentActivityKt { *; }
-keep class androidx.compose.runtime.SnapshotStateKt { *; }
-keep class androidx.compose.runtime.CompositionLocalKt { *; }
-keep class androidx.compose.runtime.ComposablesKt { *; }
-keep class androidx.compose.runtime.ComposerKt { *; }
# Compiler-generated test composables also invoke these public ABI owners directly. Keeping
# their original signatures prevents R8 from specializing return types only in the target APK.
-keep class androidx.compose.runtime.Composer { *; }
-keep class androidx.compose.runtime.Composer$Companion { *; }
-keep class androidx.compose.runtime.ProvidableCompositionLocal { *; }
-keep class androidx.compose.runtime.ProvidedValue { *; }
-keep class androidx.compose.ui.unit.Dp { *; }
-keep class androidx.compose.ui.Alignment$Companion { *; }
-keep class androidx.compose.ui.Alignment { *; }
-keep class androidx.compose.ui.Modifier { *; }
-keep class androidx.compose.ui.node.ComposeUiNode$Companion { *; }
-keep class androidx.compose.runtime.Updater { *; }
-keep class androidx.compose.runtime.Composable { *; }
-keep class androidx.compose.runtime.ComposableTarget { *; }
-keep class androidx.compose.runtime.internal.ComposableLambda { *; }
-keep class androidx.compose.runtime.internal.ComposableLambdaKt { *; }
-keep class androidx.compose.runtime.internal.StabilityInferred { *; }
-keep class androidx.compose.ui.ComposedModifierKt { *; }
-keep class androidx.compose.ui.platform.AndroidCompositionLocals_androidKt { *; }
-keep class androidx.compose.ui.platform.CompositionLocalsKt { *; }
-keep class androidx.compose.ui.unit.DensityKt { *; }
-keep class androidx.compose.foundation.layout.SizeKt { *; }
-keep class androidx.compose.foundation.layout.BoxKt { *; }
-keep class androidx.compose.material3.TextKt { *; }

# GuideCast is open-source and its public app/provider surface is called from the separately
# installed device-test APK and from Android component/JNI boundaries. Keep that surface stable;
# dependency and resource shrinking remain enabled for the alpha artifact.
-keep class app.guidecast.** { *; }
