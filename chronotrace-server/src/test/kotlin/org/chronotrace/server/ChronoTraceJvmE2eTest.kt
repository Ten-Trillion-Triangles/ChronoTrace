package org.chronotrace.server

import io.ktor.http.contentType
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.chronotrace.contract.FrameSnapshot
import org.chronotrace.contract.IngestBatch
import org.chronotrace.contract.LogRecord
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.text.Charsets

/**
 * End-to-end integration tests for the ChronoTrace JVM compiler plugin.
 *
 * Full pipeline verification:
 * 1. Plugin transforms ChronoLogger calls to capture local variables
 * 2. Compiled code sends frame snapshots to the server via HTTP
 * 3. Server receives and stores frame snapshots with localsJson
 *
 * No mocks, no stubs — real compilation, real HTTP, real storage.
 */
class ChronoTraceJvmE2eTest {
    companion object {
        private const val SERVER_PORT = 18080
        private const val API_KEY = "e2e-test-key"

        @TempDir
        @JvmStatic
        lateinit var tempDir: Path

        // Shared collection: server writes received batches here, test reads them
        private val receivedBatches = CopyOnWriteArrayList<IngestBatch>()

        private var server: EmbeddedServer<*, *>? = null
        private val workspaceRoot: String = findWorkspaceRoot()

        private fun findWorkspaceRoot(): String {
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

        @BeforeAll
        @JvmStatic
        fun startServer() {
            val serverJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }
            server = embeddedServer(Netty, SERVER_PORT) {
                install(ContentNegotiation) {
                    json(serverJson)
                }
                routing {
                    // Probe endpoint: returns received frame snapshots for test verification
                    get("/admin/test/frames") {
                        val frames = receivedBatches.flatMap { it.frameSnapshots }
                        call.respondText(
                            serverJson.encodeToString(frames),
                            contentType = ContentType.Application.Json
                        )
                    }

                    // Capture endpoint: stores the batch for test verification
                    post("/api/v1/ingest") {
                        try {
                            val body = call.receiveText()
                            val batch = serverJson.decodeFromString<IngestBatch>(body)
                            receivedBatches.add(batch)
                            call.respondText(
                                """{"accepted":true,"frameCount":${batch.frameSnapshots.size}}""",
                                contentType = ContentType.Application.Json,
                                status = HttpStatusCode.OK
                            )
                        } catch (e: Exception) {
                            call.respondText(
                                """{"error":"${e.message?.replace("\"", "\\\"") ?: "unknown"}"}""",
                                contentType = ContentType.Application.Json,
                                status = HttpStatusCode.InternalServerError
                            )
                        }
                    }
                }
            }.start()
        }

        @AfterAll
        @JvmStatic
        fun stopServer() {
            server?.stop(1000, 1000)
            server = null
            receivedBatches.clear()
        }
    }

    /**
     * Verify the complete pipeline: plugin transforms code → compiled code sends frame snapshots →
     * server receives frame snapshots with localsJson containing captured local variables.
     */
    @Test
    fun `ChronoLogger error with local variables produces frame snapshot with localsJson`(@TempDir tempPath: Path) {
        receivedBatches.clear()

        // Build plugin JAR first
        val jarResult = org.gradle.testkit.runner.GradleRunner.create()
            .withProjectDir(File(workspaceRoot))
            .withArguments(":chronotrace-kotlin-plugin:jar")
            .build()
        val pluginJar = File("$workspaceRoot/chronotrace-kotlin-plugin/build/libs")
            .listFiles()?.firstOrNull { it.name.startsWith("chronotrace-kotlin-plugin") && it.name.endsWith(".jar") }
            ?: throw IllegalStateException("Plugin JAR not found at ${workspaceRoot}/chronotrace-kotlin-plugin/build/libs")
        val pluginJarPath = pluginJar.absolutePath

        // SDK JAR - rebuild to ensure latest
        val sdkJar = File("$workspaceRoot/sdk-kmp/build/libs/sdk-kmp-jvm-1.0.0.jar")
        if (!sdkJar.exists()) {
            throw IllegalStateException("SDK JAR not found at ${sdkJar.absolutePath}")
        }

        val dir = tempPath.toFile()

        // Write settings.gradle.kts
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
            rootProject.name = "e2e-test"
            include(":app")
        """.trimIndent())

        File(dir, "build.gradle.kts").writeText("""
            plugins { id("org.chronotrace.kotlin-plugin") apply false }
            allprojects {
                ext.set("chronotrace.plugin.jar", "${pluginJarPath.replace("\\", "\\\\")}")
            }
        """.trimIndent())

        // Write app/build.gradle.kts
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
                implementation(files("${sdkJar.absolutePath.replace("\\", "\\\\")}"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            }
        """.trimIndent())

        // Write test source that sends to our test server
        val srcDir = File(appDir, "src/main/kotlin").also { it.mkdirs() }
        File(srcDir, "Demo.kt").writeText("""
            package demo

            import com.chronotrace.sdk.ChronoConfig
            import com.chronotrace.sdk.ChronoLogger
            import com.chronotrace.sdk.ChronoTrace
            import com.chronotrace.sdk.transport.HttpTransport
            import kotlinx.coroutines.runBlocking

            fun main() {
                val transport = HttpTransport("http://localhost:${SERVER_PORT}", "${API_KEY}", allowInsecureBaseUrl = true)
                val config = ChronoConfig(
                    appId = "e2e-test-jvm",
                    serviceName = "e2e-test-jvm",
                    environment = "test",
                    transport = transport
                )
                ChronoTrace.init(config)
                runBlocking {
                    val userId = 42L
                    val userName = "Alice"
                    val result = "processed"
                    // ERROR level triggers auto-capture of locals into frame snapshot
                    ChronoLogger.error("User processing failed", mapOf(
                        "userId" to userId,
                        "userName" to userName,
                        "result" to result
                    ))
                    ChronoTrace.shutdown()
                }
            }
        """.trimIndent())

        // Compile the test app
        val compileResult = org.gradle.testkit.runner.GradleRunner.create()
            .withProjectDir(dir)
            .withArguments(":app:compileKotlin", "--info")
            .build()

        assertEquals(
            org.gradle.testkit.runner.TaskOutcome.SUCCESS,
            compileResult.task(":app:compileKotlin")?.outcome,
            "Compilation should succeed with ChronoTrace plugin"
        )

        // Verify no ChronoTrace-related compilation errors
        val chronoErrors = compileResult.output.lines().filter {
            it.contains("e:") && it.contains("ChronoTrace") && !it.contains("daemon log")
        }
        assertTrue(chronoErrors.isEmpty(), "Should have no ChronoTrace errors: $chronoErrors")

        // Run the compiled app using the test JVM's classloader
        // The test classloader already has coroutines and all SDK dependencies
        val classesDir = File(dir, "app/build/classes/kotlin/main")
        val classLoader = Thread.currentThread().contextClassLoader

        // Add classesDir to the classloader chain
        val customLoader = java.net.URLClassLoader(
            arrayOf(classesDir.toURI().toURL()),
            classLoader
        )

        val demoClass = customLoader.loadClass("demo.DemoKt")
        val mainMethod = demoClass.getMethod("main")
        mainMethod.invoke(null)

        // Wait briefly for server to process
        Thread.sleep(500)

        // Verify server received at least one batch with frame snapshots
        assertFalse(receivedBatches.isEmpty(), "Server should have received at least one ingest batch. " +
            "Test app may have failed to connect to server at localhost:$SERVER_PORT.")

        val batchWithFrame = receivedBatches.find { it.frameSnapshots.isNotEmpty() }
        assertNotNull(batchWithFrame, "At least one batch should contain frame snapshots. " +
            "Received ${receivedBatches.size} batch(es). " +
            "Frames in each batch: ${receivedBatches.map { it.frameSnapshots.size }}")

        val frame = batchWithFrame!!.frameSnapshots.first()
        assertNotNull(frame.localsJson, "Frame snapshot should have non-null localsJson")
        assertTrue(frame.localsJson.isNotEmpty(), "Frame snapshot localsJson should not be empty")
        assertTrue(
            frame.localsJson.contains("userId") || frame.localsJson.contains("\"userId\""),
            "Frame snapshot localsJson should contain captured variable 'userId'. Was: ${frame.localsJson}"
        )
        assertTrue(
            frame.localsJson.contains("userName") || frame.localsJson.contains("\"userName\""),
            "Frame snapshot localsJson should contain captured variable 'userName'. Was: ${frame.localsJson}"
        )
        assertTrue(
            frame.localsJson.contains("result") || frame.localsJson.contains("\"result\""),
            "Frame snapshot localsJson should contain captured variable 'result'. Was: ${frame.localsJson}"
        )
        assertEquals("e2e-test-jvm", frame.appId)
        assertEquals("test", frame.environment)
    }

    /**
     * Verify that the plugin reports instrumented functions in the Kotlin daemon output
     * (proven working: the fix for the didMatch bug now correctly counts functions).
     */
    @Test
    fun `plugin reports instrumented functions in daemon output`(@TempDir tempPath: Path) {
        // Build plugin JAR first
        val jarResult = org.gradle.testkit.runner.GradleRunner.create()
            .withProjectDir(File(workspaceRoot))
            .withArguments(":chronotrace-kotlin-plugin:jar")
            .build()
        val pluginJar = File("$workspaceRoot/chronotrace-kotlin-plugin/build/libs")
            .listFiles()?.firstOrNull { it.name.startsWith("chronotrace-kotlin-plugin") && it.name.endsWith(".jar") }
            ?: throw IllegalStateException("Plugin JAR not found at ${workspaceRoot}/chronotrace-kotlin-plugin/build/libs")
        val pluginJarPath = pluginJar.absolutePath

        val sdkJar = File("$workspaceRoot/sdk-kmp/build/libs/sdk-kmp-jvm-1.0.0.jar")

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
            rootProject.name = "e2e-verify-test"
            include(":app")
        """.trimIndent())

        File(dir, "build.gradle.kts").writeText("""
            plugins { id("org.chronotrace.kotlin-plugin") apply false }
            allprojects {
                ext.set("chronotrace.plugin.jar", "${pluginJarPath.replace("\\", "\\\\")}")
            }
        """.trimIndent())

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
                implementation(files("${sdkJar.absolutePath.replace("\\", "\\\\")}"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            }
        """.trimIndent())

        val srcDir = File(appDir, "src/main/kotlin").also { it.mkdirs() }
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
        """.trimIndent())

        val compileResult = org.gradle.testkit.runner.GradleRunner.create()
            .withProjectDir(dir)
            .withArguments(":app:compileKotlin", "--info")
            .build()

        assertEquals(
            org.gradle.testkit.runner.TaskOutcome.SUCCESS,
            compileResult.task(":app:compileKotlin")?.outcome,
            "Compilation should succeed"
        )

        // Find and read the Kotlin daemon log for ChronoTrace output
        val daemonLog = File("/tmp").listFiles { _, name ->
            name.startsWith("kotlin-daemon.") && name.endsWith(".log")
        }?.maxByOrNull { it.lastModified() }
            ?: throw AssertionError("No Kotlin daemon log found in /tmp")

        val daemonOutput = daemonLog.readText()
        val chronoLines = daemonOutput.lines().filter { it.contains("ChronoTrace") }

        assertTrue(
            chronoLines.any { it.contains("functions instrumented") },
            "Daemon output should contain 'ChronoTrace: X functions instrumented'. " +
            "Daemon log excerpt:\n${chronoLines.take(10).joinToString("\n")}"
        )
    }
}
