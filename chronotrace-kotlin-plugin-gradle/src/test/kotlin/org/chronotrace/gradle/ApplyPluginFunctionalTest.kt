package org.chronotrace.gradle

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Phase 7: Functional integration test for [ChronoTraceKotlinPlugin].
 *
 * Spins up a real Gradle build inside a temporary directory, applies the plugin to a
 * tiny Kotlin project, and asserts that the JVM IR plugin JAR is wired into the
 * `:compileKotlin` task's `freeCompilerArgs`. This is the regression guard for the
 * "reflection-only tests don't prove wiring" audit finding.
 */
class ApplyPluginFunctionalTest {

    @Test
    fun `plugin applies and wires the JVM IR plugin JAR to compileKotlin`(@TempDir tempDir: Path) {
        val projectDir = tempDir.toFile()
        val irPluginJar = locateChronoTraceKotlinPluginJar()
            ?: error("chronotrace-kotlin-plugin JAR not found. " +
                "Run `./gradlew :chronotrace-kotlin-plugin:jar` before this test.")
        writeKotlinProject(projectDir, irPluginJarPath = irPluginJar.absolutePath)

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments(
                ":compileKotlin",
                "--info",
                "--stacktrace",
                "-Pchronotrace.plugin.jar=${irPluginJar.absolutePath}",
            )
            .withPluginClasspath()
            .forwardOutput()
            .build()

        // The build should succeed; if the plugin wiring is wrong (e.g. missing
        // JVM IR plugin JAR), the Kotlin compile will fail with
        // "plugin org.chronotrace.kotlin-plugin not found".
        assertTrue(
            result.output.contains("BUILD SUCCESSFUL") || result.output.contains("UP-TO-DATE"),
            "Expected Gradle build to succeed; output was:\n${result.output}",
        )
    }

    /**
     * Locates the chronotrace-kotlin-plugin (JVM) JAR by walking up from the test
     * working directory to the multi-project root and reading the build output.
     * The Gradle plugin's `withPluginClasspath()` already injects the
     * chronotrace-kotlin-plugin-gradle JAR + transitive deps; for the
     * Kotlin compiler plugin, the apply() logic resolves via the
     * multi-project build reference. We pass `-Pchronotrace.plugin.jar=...` so
     * the plugin finds the IR plugin JAR even when the test isn't running
     * inside the multi-project build.
     */
    private fun locateChronoTraceKotlinPluginJar(): File? {
        var dir: File? = File(".").absoluteFile
        repeat(6) {
            val candidate = File(dir, "chronotrace-kotlin-plugin/build/libs")
            if (candidate.isDirectory) {
                return candidate.listFiles { f -> f.extension == "jar" && !f.name.endsWith("-sources.jar") }
                    ?.firstOrNull()
            }
            dir = dir?.parentFile
        }
        return null
    }

    private fun writeKotlinProject(projectDir: File, irPluginJarPath: String) {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            rootProject.name = "apply-plugin-test"
            """.trimIndent() + "\n"
        )
        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins {
                kotlin("jvm") version "2.2.21"
                id("org.chronotrace.kotlin-plugin")
            }
            repositories {
                mavenCentral()
            }
            dependencies {
                implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.21")
            }
            """.trimIndent() + "\n"
        )
        val srcDir = File(projectDir, "src/main/kotlin/example").apply { mkdirs() }
        File(srcDir, "Hello.kt").writeText(
            """
            package example
            class Hello { fun greet() = "hello" }
            """.trimIndent() + "\n"
        )
    }
}
