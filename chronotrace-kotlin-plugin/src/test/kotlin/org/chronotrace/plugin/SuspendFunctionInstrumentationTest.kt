package org.chronotrace.plugin

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TDD test for Phase 1: verifies that ChronoLogger calls inside
 * `suspend fun` bodies are actually instrumented by the K2 IR
 * compiler plugin.
 *
 * This is the headline feature of ChronoTrace: it must work for
 * `suspend fun` because the SDK's ChronoLogger methods are themselves
 * `suspend` and Kotlin's `suspend fun` body is lowered to a state
 * machine wrapped in an inner `IrFunction`.
 *
 * Failure mode: pre-fix plugin reports `ChronoTrace: 0 functions
 * instrumented` because (a) the `classStats` tracking bug only
 * increments `prevF` on the first call per class, and (b) the
 * inner-suspend-lambda descent does not visit the user-authored
 * `ChronoLogger.info(...)` invocation. The test asserts a non-zero
 * count to expose this bug.
 */
class SuspendFunctionInstrumentationTest {

    @TempDir
    lateinit var tempDir: Path

    private val workspaceRoot: String by lazy { discoverWorkspaceRoot() }

    private fun discoverWorkspaceRoot(): String {
        // Allow override via -Dchronotrace.workspace.root=/path/to/repo
        System.getProperty("chronotrace.workspace.root")?.let { return it }
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            val settings = dir.resolve("settings.gradle.kts")
            if (settings.exists() &&
                settings.readText().contains("chronotrace-kotlin-plugin-gradle")) {
                return dir.absolutePath
            }
            dir = dir.parentFile
        }
        error("Could not find ChronoTrace workspace root. " +
              "Run from the project root, or set -Dchronotrace.workspace.root=/path/to/repo")
    }

    private fun buildPluginJar(): String {
        val jarResult = GradleRunner.create()
            .withProjectDir(File(workspaceRoot))
            .withArguments(":chronotrace-kotlin-plugin:jar")
            .build()
        val jarOutcome = jarResult.task(":chronotrace-kotlin-plugin:jar")?.outcome
        assertTrue(jarOutcome == TaskOutcome.SUCCESS || jarOutcome == TaskOutcome.UP_TO_DATE,
            "Expected jar task to succeed but was $jarOutcome")

        val pluginJarFile = File("$workspaceRoot/chronotrace-kotlin-plugin/build/libs").listFiles()
            ?.filter { it.name.startsWith("chronotrace-kotlin-plugin") && it.name.endsWith(".jar") }
            ?.maxByOrNull { it.lastModified() }
            ?: throw IllegalStateException("No chronotrace-kotlin-plugin JAR found in build/libs")
        return pluginJarFile.absolutePath
    }

    @Test
    fun `ChronoLogger calls inside suspend fun bodies are instrumented`(@TempDir tempPath: Path) {
        val pluginJarPath = buildPluginJar()
        val dir = tempPath.toFile()
        val sdkJarPath = File("$workspaceRoot/sdk-kmp/build/libs")
            .listFiles()
            ?.firstOrNull { it.name.startsWith("sdk-kmp-jvm-") && it.name.endsWith(".jar") }
            ?.absolutePath
            ?: error("No sdk-kmp-jvm-*.jar found in $workspaceRoot/sdk-kmp/build/libs. Build the SDK first: ./gradlew :sdk-kmp:jvmJar")

        File(dir, "settings.gradle.kts").writeText(
            """
            pluginManagement {
                repositories {
                    mavenCentral()
                    gradlePluginPortal()
                }
                plugins {
                    kotlin("jvm") version "2.2.21"
                }
            }
            dependencyResolutionManagement {
                repositories {
                    mavenCentral()
                }
            }
            includeBuild("$workspaceRoot/chronotrace-kotlin-plugin-gradle")
            rootProject.name = "suspend-instrumentation-test"
            include(":app")
            """.trimIndent()
        )

        File(dir, "build.gradle.kts").writeText(
            """
            plugins { id("org.chronotrace.kotlin-plugin") apply false }
            """.trimIndent()
        )

        val appDir = File(dir, "app").also { it.mkdirs() }
        File(appDir, "build.gradle.kts").writeText(
            """
            plugins {
                id("org.chronotrace.kotlin-plugin")
                kotlin("jvm")
            }
            repositories {
                mavenCentral()
            }
            kotlin { jvmToolchain(17) }
            dependencies {
                implementation(files("${sdkJarPath}"))
            }
            """.trimIndent()
        )

        val srcDir = File(appDir, "src/main/kotlin/test").also { it.mkdirs() }
        File(srcDir, "Service.kt").writeText(
            """
            package test
            import com.chronotrace.sdk.ChronoLogger

            class Service {
                suspend fun doWork() {
                    ChronoLogger.info("from suspend")
                }

                suspend fun doMoreWork() {
                    ChronoLogger.info("from suspend 2")
                }
            }
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(dir)
            .withEnvironment(mapOf("CHRONOTRACE_PLUGIN_JAR" to pluginJarPath))
            .withArguments(":app:compileKotlin", "--info")
            .build()

        // 1) The compilation itself must succeed.
        assertEquals(
            TaskOutcome.SUCCESS,
            result.task(":app:compileKotlin")?.outcome,
            "Compilation should succeed with ChronoTrace plugin. " +
                "Build output:\n${result.output.lines().filter { it.contains("e:") || it.contains("ChronoTrace") }.take(20).joinToString("\n")}"
        )

        // 2) The Kotlin daemon log (the place where the plugin's println
        // output goes) must contain a non-zero instrumented count.
        val kotlinDaemonLog = File("/tmp").listFiles { _, name ->
            name.startsWith("kotlin-daemon.") && name.endsWith(".log")
        }
            ?.maxByOrNull { it.lastModified() }
            ?: error("No Kotlin daemon log found in /tmp")
        val daemonOutput = kotlinDaemonLog.readText()

        val countRegex = Regex("ChronoTrace: ([1-9]\\d*) functions instrumented")
        val match = countRegex.find(daemonOutput)
            ?: error(
                "Expected 'ChronoTrace: <positive number> functions instrumented' in " +
                    "Kotlin daemon log, but no match was found. Daemon log: " +
                    daemonOutput.lines().filter {
                        it.contains("ChronoTrace") || it.contains("compileKotlin")
                    }.take(20).joinToString("\n")
            )

        val instrumentedCount = match.groupValues[1].toInt()
        assertTrue(
            instrumentedCount >= 1,
            "Expected at least 1 function instrumented (the 2 suspend fun bodies in Service), " +
                "but got $instrumentedCount. " +
                "Daemon log excerpt:\n" +
                daemonOutput.lines().filter { it.contains("ChronoTrace") }.take(20).joinToString("\n")
        )

        // 3) The same log line should report non-zero captured locals
        // (the plugin visits the body and rewrites the call with a
        // locals map).
        val localsRegex = Regex("ChronoTrace: [0-9]+ functions instrumented, ([0-9]+) locals captured")
        val localsMatch = localsRegex.find(daemonOutput)
            ?: error(
                "Expected 'ChronoTrace: ... functions instrumented, ... locals captured' in " +
                    "Kotlin daemon log. Daemon log excerpt:\n" +
                    daemonOutput.lines().filter { it.contains("ChronoTrace") }.take(20).joinToString("\n")
            )

        val capturedLocals = localsMatch.groupValues[1].toInt()
        assertTrue(
            capturedLocals >= 1,
            "Expected at least 1 captured local in the instrumented suspend functions, " +
                "but got $capturedLocals. Daemon log excerpt:\n" +
                daemonOutput.lines().filter { it.contains("ChronoTrace") }.take(20).joinToString("\n")
        )
    }
}
