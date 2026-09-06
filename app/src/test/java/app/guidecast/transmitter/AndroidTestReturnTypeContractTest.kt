package app.guidecast.transmitter

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Static regression check to prevent JUnit4 instrumentation test discovery failures (tests run = 0).
 *
 * In Kotlin, an expression-bodied method `fun foo() = runBlocking { ... }` infers its return type
 * from the last expression in the lambda (e.g. `Log.i()` returns `Int`). JUnit4 reflection requires
 * `@Test` methods to have a `void` return type; non-void methods are silently ignored by the test runner.
 */
class AndroidTestReturnTypeContractTest {

    @Test
    fun moonshineDeviceIntegrationTestMethodsHaveExplicitUnitOrVoidReturnType() {
        val androidTestDir = File("src/androidTest/java").let {
            if (it.exists()) it else File("app/src/androidTest/java")
        }
        assertTrue("androidTest directory must exist", androidTestDir.exists())

        val targetFiles = listOf(
            File(androidTestDir, "app/guidecast/transmitter/MoonshineSttDeviceIntegrationTest.kt"),
            File(androidTestDir, "app/guidecast/transmitter/MoonshineTtsDeviceIntegrationTest.kt"),
        )

        val violations = mutableListOf<String>()

        for (file in targetFiles) {
            assertTrue("${file.name} must exist", file.exists())
            val lines = file.readLines()
            for (i in lines.indices) {
                if (lines[i].trim().startsWith("@Test")) {
                    for (j in i + 1 until minOf(lines.size, i + 5)) {
                        val line = lines[j].trim()
                        if (line.startsWith("fun ")) {
                            if (line.contains("= runBlocking") &&
                                !line.contains(": Unit") &&
                                !line.contains("runBlocking<Unit>")
                            ) {
                                violations += "${file.name}:${j + 1}: $line (expression-bodied @Test method must specify ': Unit' or 'runBlocking<Unit>' to compile to void in DEX)"
                            }
                            break
                        }
                    }
                }
            }
        }

        assertTrue(
            "Found Moonshine @Test methods with inferred return types that risk non-void DEX compilation:\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }
}
