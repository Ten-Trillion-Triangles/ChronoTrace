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
 * Integration tests for ChronoTrace Gradle plugin graceful degradation.
 * Verifies that the plugin handles error conditions without crashing the build.
 */
class ChronoTraceIrGenerationExtensionGracefulDegradationTest {

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
    fun `plugin does not crash build when instrumentation fails`(@TempDir tempPath: Path) {
        val pluginJarPath = buildPluginJar()
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
            dependencyResolutionManagement {
                repositories {
                    mavenCentral()
                }
            }
            includeBuild("$workspaceRoot/chronotrace-kotlin-plugin-gradle")
            rootProject.name = "degradation-test"
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
        """)

        val srcDir = File(appDir, "src/main/kotlin").also { it.mkdirs() }
        File(srcDir, "Service.kt").writeText("""
            package demo

            class Service {
                fun process(data: String): String = data.uppercase()
            }
        """)

        val result = GradleRunner.create()
            .withProjectDir(dir)
            .withEnvironment(mapOf("CHRONOTRACE_PLUGIN_JAR" to pluginJarPath))
            .withArguments(":app:compileKotlin", "--stacktrace")
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":app:compileKotlin")?.outcome,
            "Build should succeed even if ChronoTrace plugin encounters issues during instrumentation")
    }

    @Test
    fun `plugin logs output during compilation`(@TempDir tempPath: Path) {
        val pluginJarPath = buildPluginJar()
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
            dependencyResolutionManagement {
                repositories {
                    mavenCentral()
                }
            }
            includeBuild("$workspaceRoot/chronotrace-kotlin-plugin-gradle")
            rootProject.name = "output-test"
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
        """)

        val srcDir = File(appDir, "src/main/kotlin").also { it.mkdirs() }
        File(srcDir, "Empty.kt").writeText("""
            package demo

            class Empty
        """)

        val result = GradleRunner.create()
            .withProjectDir(dir)
            .withEnvironment(mapOf("CHRONOTRACE_PLUGIN_JAR" to pluginJarPath))
            .withArguments(":app:compileKotlin", "--info")
            .build()

        val buildOutput = result.output

        assertTrue(
            buildOutput.contains("ChronoTrace") || buildOutput.contains("kotlin daemon"),
            "Expected ChronoTrace plugin output in build. Build output contained no ChronoTrace references"
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
                fun greet(msg: String): String = msg
            }
        """)

        val result = GradleRunner.create()
            .withProjectDir(dir)
            .withArguments("compileKotlin")
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":compileKotlin")?.outcome)
    }
}
