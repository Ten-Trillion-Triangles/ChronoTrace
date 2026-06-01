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
 * Integration tests for ChronoTrace Gradle plugin using Gradle TestKit.
 * Tests verify that the plugin applies and the build completes without errors.
 */
class ChronoTraceIrGenerationExtensionIntegrationTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var testProjectDir: Path

    // Detect workspace root by walking up from user.dir or using default
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

    private fun writeSettingsWithPlugin(consumerDir: File, pluginJarPath: String) {
        val settingsContent = """
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
            rootProject.name = "test-app"
            include(":chronotrace-kotlin-plugin")
            include(":app")
        """.trimIndent()
        File(consumerDir, "settings.gradle.kts").writeText(settingsContent)
        File(consumerDir, "gradle.properties").writeText("chronotrace.plugin.jar=$pluginJarPath")
    }

    @Test
    fun `plugin applies to project without errors`(@TempDir tempPath: Path) {
        // Build the compiler plugin jar first
        val jarResult = GradleRunner.create()
            .withProjectDir(File(workspaceRoot))
            .withArguments(":chronotrace-kotlin-plugin:jar")
            .build()
        val jarOutcome = jarResult.task(":chronotrace-kotlin-plugin:jar")?.outcome
        assertTrue(jarOutcome == TaskOutcome.SUCCESS || jarOutcome == TaskOutcome.UP_TO_DATE,
            "Expected jar task to succeed but was $jarOutcome")

        val pluginJarFile = File("$workspaceRoot/chronotrace-kotlin-plugin/build/libs").listFiles()?.firstOrNull { it.name.startsWith("chronotrace-kotlin-plugin") && it.name.endsWith(".jar") }
            ?: throw IllegalStateException("No chronotrace-kotlin-plugin JAR found in build/libs")
        val pluginJarPath = pluginJarFile.absolutePath
        if (!File(pluginJarPath).exists()) {
            throw IllegalStateException("Plugin JAR not found at $pluginJarPath")
        }

        val dir = tempPath.toFile()
        writeSettingsWithPlugin(dir, pluginJarPath)

        File(dir, "build.gradle.kts").writeText(
            "plugins { id(\"org.chronotrace.kotlin-plugin\") apply false }"
        )

        val appBuildFile = File(dir, "app/build.gradle.kts").also { it.parentFile.mkdirs() }
        appBuildFile.writeText("""
            plugins {
                id("org.chronotrace.kotlin-plugin")
                kotlin("jvm")
            }
            kotlin { jvmToolchain(17) }
        """)

        val result = GradleRunner.create()
            .withProjectDir(dir)
            .withArguments(":app:tasks", "--info")
            .build()

        assertNotNull(result)
        assertEquals(TaskOutcome.SUCCESS, result.task(":app:tasks")?.outcome)
    }

    @Test
    fun `instrumentation output is captured during compilation`(@TempDir tempPath: Path) {
        // Build the compiler plugin jar first
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
        val pluginJarPath = pluginJarFile.absolutePath

        val dir = tempPath.toFile()
        writeSettingsWithPlugin(dir, pluginJarPath)

        // Write app build with ChronoLogger source
        File(dir, "build.gradle.kts").writeText(
            "plugins { id(\"org.chronotrace.kotlin-plugin\") apply false }"
        )

        val appBuildFile = File(dir, "app/build.gradle.kts").also { it.parentFile.mkdirs() }
        // Find the pre-built SDK JAR for the ChronoLogger API
        val sdkJar = File("$workspaceRoot/sdk-kmp/build/libs/sdk-kmp-jvm-1.0.0.jar")
        appBuildFile.writeText("""
            plugins {
                id("org.chronotrace.kotlin-plugin")
                kotlin("jvm")
            }
            kotlin { jvmToolchain(17) }
            dependencies {
                implementation(files("${sdkJar.absolutePath.replace("\\", "\\\\")}"))
            }
        """.trimIndent())

        // Write a source file that uses ChronoLogger with local variable capture
        val srcDir = File(dir, "app/src/main/kotlin/demo").also { it.mkdirs() }
        val userServiceContent = """
            package demo

            import com.chronotrace.sdk.ChronoLogger

            class UserService {
                suspend fun processUser(userId: Long, name: String): String {
                    val result = "processed:" + userId
                    val timestamp = System.currentTimeMillis()
                    ChronoLogger.info("User processed", mapOf(
                        "userId" to userId,
                        "name" to name,
                        "result" to result,
                        "timestamp" to timestamp
                    ))
                    return result
                }

                suspend fun getUser(userId: Long): String {
                    val name = "User-" + userId
                    ChronoLogger.debug("Getting user", mapOf("userId" to userId, "name" to name))
                    return name
                }
            }
        """.trimIndent()
        File(srcDir, "UserService.kt").writeText(userServiceContent)

        val result = GradleRunner.create()
            .withProjectDir(dir)
            .withEnvironment(mapOf("CHRONOTRACE_PLUGIN_JAR" to pluginJarPath))
            .withArguments(":app:compileKotlin", "--info")
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":app:compileKotlin")?.outcome)

        // The ChronoTrace println output goes to the Kotlin daemon's stdout,
        // not Gradle's stdout. The Gradle output mentions "The daemon log file:"
        // but that's the Gradle daemon log, not the Kotlin daemon log.
        // Find the Kotlin daemon log from /tmp/kotlin-daemon.* files.
        val kotlinDaemonLog = File("/tmp").listFiles { _, name -> name.startsWith("kotlin-daemon.") && name.endsWith(".log") }
            ?.maxByOrNull { it.lastModified() }
            ?: throw AssertionError("No Kotlin daemon log found in /tmp")

        val daemonOutput = kotlinDaemonLog.readText()

        // Verify instrumentation summary appears in daemon output
        // The plugin must report a NON-ZERO count of functions instrumented
        // (the sample has ChronoLogger calls in 2 suspend fun bodies).
        val instrumentedPattern = Regex("ChronoTrace: ([1-9]\\d*) functions instrumented, ([0-9]+) locals captured")
        val match = instrumentedPattern.find(daemonOutput)
            ?: error("Expected 'ChronoTrace: <positive number> functions instrumented, <count> locals captured' " +
                "in daemon log but it was not found. " +
                "Daemon log excerpt:\n${daemonOutput.lines().filter { it.contains("ChronoTrace") || it.contains("compileKotlin") }.take(10).joinToString("\n")}")

        val instrumentedCount = match.groupValues[1].toInt()
        assertTrue(
            instrumentedCount >= 1,
            "Expected at least 1 function instrumented, but got $instrumentedCount. " +
                "Daemon log excerpt:\n${daemonOutput.lines().filter { it.contains("ChronoTrace") }.take(10).joinToString("\n")}"
        )

        val capturedLocals = match.groupValues[2].toInt()
        assertTrue(
            capturedLocals >= 1,
            "Expected at least 1 captured local, but got $capturedLocals. " +
                "Daemon log excerpt:\n${daemonOutput.lines().filter { it.contains("ChronoTrace") }.take(10).joinToString("\n")}"
        )
    }

    @Test
    fun `baseline build without plugin succeeds`(@TempDir tempPath: Path) {
        val dir = tempPath.toFile()
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
            rootProject.name = "baseline-test"
        """)
        File(dir, "build.gradle.kts").writeText("""
            plugins { kotlin("jvm") }
            repositories { mavenCentral() }
            kotlin { jvmToolchain(17) }
        """)

        val srcDir = File(dir, "src/main/kotlin").also { it.mkdirs() }
        File(srcDir, "Baseline.kt").writeText("""
            package test

            class Baseline {
                suspend fun greet(msg: String) {
                    println(msg)
                }
            }
        """)

        val result = GradleRunner.create()
            .withProjectDir(dir)
            .withArguments("compileKotlin")
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":compileKotlin")?.outcome)
    }
}
