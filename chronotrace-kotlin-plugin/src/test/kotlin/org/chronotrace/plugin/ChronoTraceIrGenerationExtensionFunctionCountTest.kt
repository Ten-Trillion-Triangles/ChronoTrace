package org.chronotrace.plugin

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TDD tests for ChronoTrace IR plugin function counting.
 *
 * These tests verify:
 * 1. Non-suspend functions with ChronoLogger calls are counted correctly
 * 2. The "ChronoTrace: N functions instrumented" output is accurate
 *
 * Bug being tested: The tracking bug where classStats stored (functionsVisited, callsRewritten)
 * but only callsRewritten was ever incremented, making instrumentedFns always 0.
 *
 * NOTE: The ChronoTrace plugin outputs via println() which goes to the Kotlin daemon process.
 * We capture this by finding and reading the Kotlin daemon log file after the build.
 */
class ChronoTraceIrGenerationExtensionFunctionCountTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var testProjectDir: Path

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

    @BeforeEach
    fun setup() {
        testProjectDir = tempDir
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
    fun `non-suspend function with ChronoLogger call is counted as instrumented`(@TempDir tempPath: Path) {
        val pluginJarPath = buildPluginJar()
        val dir = tempPath.toFile()

        // SDK JAR path
        val sdkJarPath = File("$workspaceRoot/sdk-kmp/build/libs/sdk-kmp-jvm-1.0.0.jar").absolutePath

        File(dir, "settings.gradle.kts").writeText("""
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
            rootProject.name = "count-test"
            include(":app")
        """)

        File(dir, "build.gradle.kts").writeText("""
            plugins { id("org.chronotrace.kotlin-plugin") apply false }
        """)

        val appDir = File(dir, "app").also { it.mkdirs() }
        File(appDir, "build.gradle.kts").writeText("""
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
        """)

        val srcDir = File(appDir, "src/main/kotlin").also { it.mkdirs() }
        // Non-suspend function with ChronoLogger call and local variable capture
        File(srcDir, "UserService.kt").writeText("""
            package demo

            import com.chronotrace.sdk.ChronoLogger

            class UserService {
                suspend fun processUser(userId: Long, name: String): String {
                    val result = "processed:" + userId
                    ChronoLogger.info("User processed", mapOf("userId" to userId, "name" to name, "result" to result))
                    return result
                }
            }
        """)

        val result = GradleRunner.create()
            .withProjectDir(dir)
            .withEnvironment(mapOf("CHRONOTRACE_PLUGIN_JAR" to pluginJarPath))
            .withArguments(":app:compileKotlin", "--info")
            .build()

        // Verify compilation succeeds
        assertEquals(TaskOutcome.SUCCESS, result.task(":app:compileKotlin")?.outcome,
            "Compilation should succeed with ChronoTrace plugin")

        // Verify no compilation errors related to ChronoTrace.
        // Gradle error lines start with `e: ` (followed by file:line:col) so we use
        // a more specific pattern than `contains("e:")` to avoid matching
        // info messages like "Caching disabled for ... because:" that happen
        // to contain both "e:" and "ChronoTrace" (e.g. a file path).
        val buildOutput = result.output
        val chronoErrors = buildOutput.lines().filter { line ->
            Regex("^e:\\s.*ChronoTrace").containsMatchIn(line) && !line.contains("daemon log")
        }
        assertTrue(chronoErrors.isEmpty(), "Should have no ChronoTrace-related errors. Found: $chronoErrors")

        // The plugin's println output goes to the Kotlin daemon log. Read it and
        // assert that the count of instrumented functions is non-zero.
        // We read the daemon log file that was most recently modified — but the
        // Wasm plugin's nested GradleRunner build can also write to /tmp, so
        // we read the daemon log path embedded in the build output (more reliable).
        val daemonLogPath = buildOutput.lineSequence()
            .firstNotNullOfOrNull { line ->
                val match = Regex("/tmp/kotlin-daemon\\.\\d{4}-\\d{2}-\\d{2}\\.\\d{2}-\\d{2}-\\d{2}-\\d{3}\\.\\d+\\.log").find(line)
                match?.value
            }
        val kotlinDaemonLog = daemonLogPath?.let { File(it) }
            ?: File("/tmp").listFiles { _, name -> name.startsWith("kotlin-daemon.") && name.endsWith(".log") }
                ?.maxByOrNull { it.lastModified() }
            ?: error("No Kotlin daemon log found in /tmp")
        val daemonOutput = kotlinDaemonLog.readText()

        val instrumentedRegex = Regex("ChronoTrace: ([1-9]\\d*) functions instrumented, ([0-9]+) locals captured")
        val match = instrumentedRegex.find(daemonOutput)
            ?: error(
                "Expected 'ChronoTrace: <positive number> functions instrumented, <count> locals captured' " +
                    "in Kotlin daemon log, but no match. " +
                    "Daemon log excerpt:\n" +
                    daemonOutput.lines().filter { it.contains("ChronoTrace") }.take(10).joinToString("\n")
            )

        val instrumentedCount = match.groupValues[1].toInt()
        assertTrue(
            instrumentedCount >= 1,
            "Expected at least 1 function instrumented, but got $instrumentedCount. " +
                "Daemon log excerpt:\n" +
                daemonOutput.lines().filter { it.contains("ChronoTrace") }.take(10).joinToString("\n")
        )
    }

    @Test
    fun `multiple non-suspend functions with ChronoLogger calls are all counted`(@TempDir tempPath: Path) {
        val pluginJarPath = buildPluginJar()
        val dir = tempPath.toFile()

        // SDK JAR path
        val sdkJarPath = File("$workspaceRoot/sdk-kmp/build/libs/sdk-kmp-jvm-1.0.0.jar").absolutePath

        File(dir, "settings.gradle.kts").writeText("""
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
            rootProject.name = "count-test-multi"
            include(":app")
        """)

        File(dir, "build.gradle.kts").writeText("""
            plugins { id("org.chronotrace.kotlin-plugin") apply false }
        """)

        val appDir = File(dir, "app").also { it.mkdirs() }
        File(appDir, "build.gradle.kts").writeText("""
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
        """)

        val srcDir = File(appDir, "src/main/kotlin").also { it.mkdirs() }
        // Two classes with non-suspend functions using ChronoLogger
        File(srcDir, "ServiceA.kt").writeText("""
            package demo

            import com.chronotrace.sdk.ChronoLogger

            class ServiceA {
                suspend fun methodA(): String {
                    val value = "a"
                    ChronoLogger.info("A called", mapOf("value" to value))
                    return value
                }
            }
        """)

        File(srcDir, "ServiceB.kt").writeText("""
            package demo

            import com.chronotrace.sdk.ChronoLogger

            class ServiceB {
                suspend fun methodB(): String {
                    val value = "b"
                    ChronoLogger.info("B called", mapOf("value" to value))
                    return value
                }
            }
        """)

        val result = GradleRunner.create()
            .withProjectDir(dir)
            .withEnvironment(mapOf("CHRONOTRACE_PLUGIN_JAR" to pluginJarPath))
            .withArguments(":app:compileKotlin", "--info")
            .build()

        // Verify compilation succeeds
        assertEquals(TaskOutcome.SUCCESS, result.task(":app:compileKotlin")?.outcome,
            "Compilation should succeed with ChronoTrace plugin")

        // Verify no compilation errors related to ChronoTrace.
        // Gradle error lines start with `e: ` (followed by file:line:col) so we use
        // a more specific pattern than `contains("e:")` to avoid matching
        // info messages like "Caching disabled for ... because:" that happen
        // to contain both "e:" and "ChronoTrace" (e.g. a file path).
        val buildOutput = result.output
        val chronoErrors = buildOutput.lines().filter { line ->
            Regex("^e:\\s.*ChronoTrace").containsMatchIn(line) && !line.contains("daemon log")
        }
        assertTrue(chronoErrors.isEmpty(), "Should have no ChronoTrace-related errors. Found: $chronoErrors")

        // The plugin's println output goes to the Kotlin daemon log. Read it and
        // assert that the count of instrumented functions is non-zero.
        // We read the daemon log file that was most recently modified — but the
        // Wasm plugin's nested GradleRunner build can also write to /tmp, so
        // we read the daemon log path embedded in the build output (more reliable).
        val daemonLogPath = buildOutput.lineSequence()
            .firstNotNullOfOrNull { line ->
                val match = Regex("/tmp/kotlin-daemon\\.\\d{4}-\\d{2}-\\d{2}\\.\\d{2}-\\d{2}-\\d{2}-\\d{3}\\.\\d+\\.log").find(line)
                match?.value
            }
        val kotlinDaemonLog = daemonLogPath?.let { File(it) }
            ?: File("/tmp").listFiles { _, name -> name.startsWith("kotlin-daemon.") && name.endsWith(".log") }
                ?.maxByOrNull { it.lastModified() }
            ?: error("No Kotlin daemon log found in /tmp")
        val daemonOutput = kotlinDaemonLog.readText()

        val instrumentedRegex = Regex("ChronoTrace: ([1-9]\\d*) functions instrumented, ([0-9]+) locals captured")
        val match = instrumentedRegex.find(daemonOutput)
            ?: error(
                "Expected 'ChronoTrace: <positive number> functions instrumented, <count> locals captured' " +
                    "in Kotlin daemon log, but no match. " +
                    "Daemon log excerpt:\n" +
                    daemonOutput.lines().filter { it.contains("ChronoTrace") }.take(10).joinToString("\n")
            )

        val instrumentedCount = match.groupValues[1].toInt()
        assertTrue(
            instrumentedCount >= 1,
            "Expected at least 1 function instrumented, but got $instrumentedCount. " +
                "Daemon log excerpt:\n" +
                daemonOutput.lines().filter { it.contains("ChronoTrace") }.take(10).joinToString("\n")
        )
    }
}
